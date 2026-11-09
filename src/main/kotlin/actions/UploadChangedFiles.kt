package org.kavo.uploader.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import org.kavo.uploader.MyMessageBundle
import org.kavo.uploader.UploaderNotifications
import org.kavo.uploader.changedfiles.ChangedFilesData
import org.kavo.uploader.changedfiles.ChangedFilesDialog
import org.kavo.uploader.changedfiles.UploadRow
import org.kavo.uploader.changedfiles.buildUploadRows
import org.kavo.uploader.changedfiles.dedupeRows
import org.kavo.uploader.changedfiles.rowKey
import org.kavo.uploader.settings.PasswordStore
import org.kavo.uploader.settings.SftpSettings
import org.kavo.uploader.settings.SettingsAccess
import org.kavo.uploader.settings.effectiveMappings
import org.kavo.uploader.upload.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import javax.swing.JComponent

fun launchUploadChangedFiles(
    project: Project,
    selected: org.kavo.uploader.settings.ServerProfile? = null,
) {
    val servers = SettingsAccess.allServers(project)
    if (servers.isEmpty()) {
        UploaderNotifications.info(project, MyMessageBundle.message("changed.no.servers"))
        return
    }
    val workDir = project.basePath?.let { File(it) }
    if (workDir == null || !GitScanner.isGitRepository(workDir)) {
        UploaderNotifications.info(project, MyMessageBundle.message("changed.not.git"))
        return
    }
    val commitDialog = CommitInputDialog(project)
    if (!commitDialog.showAndGet()) return
    val commit = commitDialog.commit().takeIf { it.isNotEmpty() }
    object : Task.Backgroundable(project, MyMessageBundle.message("changed.scanning"), true) {
        override fun run(indicator: ProgressIndicator) {
            scanAndShow(project, workDir, servers, commit, selected)
        }
    }.queue()
}

private fun scanAndShow(
    project: Project,
    workDir: File,
    servers: List<org.kavo.uploader.settings.ServerProfile>,
    commit: String?,
    selected: org.kavo.uploader.settings.ServerProfile?,
) {
    val basePath = project.basePath ?: return
    val projectRoot = java.nio.file.Paths.get(basePath)
    if (commit != null && !GitScanner.isValidCommit(workDir, commit)) {
        UploaderNotifications.error(project, MyMessageBundle.message("changed.commit.invalid", commit))
        return
    }
    val changed = if (commit != null) GitScanner.filesInCommit(workDir, commit) else GitScanner.changedFiles(workDir)
    if (changed.isEmpty()) {
        UploaderNotifications.info(
            project,
            if (commit != null) MyMessageBundle.message("changed.commit.none", commit)
            else MyMessageBundle.message("changed.none"),
        )
        return
    }
    val classByJava = classesFor(project, changed)
    val settings = SftpSettings.getInstance()
    val rows = buildUploadRows(
        changed = changed,
        classByJava = classByJava,
        uploadJavaClassFiles = settings.uploadJavaClassFiles,
        projectRoot = projectRoot,
    )
    // Without the compiled classes of a Java file there is nothing a constant change could add.
    promptForSelection(
        project,
        ChangedFilesData(rows, emptyList(), projectRoot, servers, selected),
        null,
        settings.uploadJavaClassFiles,
        workDir,
        commit,
    )
}

/**
 * Asks which files to upload. Pressing "Analyze Constants Changes" adds the classes that read a
 * constant whose value changed to the list below, keeping the previous picks, and asks again.
 */
private fun promptForSelection(
    project: Project,
    data: ChangedFilesData,
    preselected: Set<String>?,
    canAnalyzeConstants: Boolean,
    workDir: File,
    commit: String?,
) {
    ApplicationManager.getApplication().invokeLater {
        val dialog = ChangedFilesDialog(project, data, preselected, canAnalyzeConstants)
        if (!dialog.showAndGet()) return@invokeLater
        if (!dialog.analyzeRequested()) {
            uploadSelected(
                project,
                dialog.result(),
                dialog.selectedServer(),
                data,
                SftpSettings.getInstance(),
                dialog.zipSelected(),
                dialog.archiveName(),
            )
            return@invokeLater
        }
        // The analysis never uploads: it only widens the list, then asks again with the earlier choices kept.
        object : Task.Backgroundable(project, MyMessageBundle.message("changed.constants.searching"), true) {
            override fun run(indicator: ProgressIndicator) {
                val derived =
                    constantDependentRows(project, workDir, commit, dialog.analyzedJavaFiles(), data.projectRoot)
                promptForSelection(
                    project,
                    ChangedFilesData(
                        rows = data.rows,
                        derivedRows = dedupeRows(data.derivedRows + derived),
                        projectRoot = data.projectRoot,
                        servers = data.servers,
                        selectedServer = dialog.selectedServer(),
                        zip = dialog.zipSelected(),
                        archiveName = dialog.archiveName(),
                    ),
                    // The earlier picks plus the newly found classes: they are what the analysis was for,
                    // so they start checked, and the analysis can be pressed again to widen the list.
                    dialog.pickedKeys() + derived.map { rowKey(it) },
                    canAnalyzeConstants,
                    workDir,
                    commit,
                )
            }
        }.queue()
    }
}

private fun constantDependentRows(
    project: Project,
    workDir: File,
    commit: String?,
    sources: List<Path>,
    projectRoot: Path,
): List<UploadRow> {
    val changes = constantChanges(workDir, commit, sources)
    if (changes.isEmpty()) {
        UploaderNotifications.info(project, MyMessageBundle.message("changed.constants.none"))
        return emptyList()
    }
    var affected = indexAffectedFiles(project, changes)
    if (affected.isEmpty()) {
        // The code index gave nothing, so the sources under the project are read instead.
        affected = ConstantNameSearch.affectedFiles(projectRoot, changes)
    }
    if (affected.isEmpty()) {
        UploaderNotifications.info(project, MyMessageBundle.message("changed.constants.none"))
        return emptyList()
    }
    val dependentSources = affected.keys.toList()
    return buildUploadRows(
        changed = dependentSources,
        classByJava = classesFor(project, dependentSources),
        uploadJavaClassFiles = true,
        projectRoot = projectRoot,
        reasonBySource = affected,
    )
}

/**
 * The listed Java files whose own diff contains a constant whose value the compiler copies around.
 *
 * The earlier version of a file is what the currently deployed classes were built with: `HEAD` for working
 * changes, the commit before the chosen one when a commit was picked.
 */
private fun constantChanges(workDir: File, commit: String?, sources: List<Path>): Map<Path, ConstantFileChange> {
    val changes = mutableMapOf<Path, ConstantFileChange>()
    // The earlier version is what the deployed classes were built with: HEAD for working changes, the
    // parent of the chosen commit otherwise.
    val oldRef = if (commit != null) "$commit^" else "HEAD"
    sources.forEach { source ->
        if (!source.toString().endsWith(".java")) return@forEach
        val current = if (commit != null) GitScanner.contentAt(workDir, commit, source) else workingText(source)
        val earlier = GitScanner.contentAt(workDir, oldRef, source)
        // A constant that only exists now, or only existed before, has no compiled readers to fix.
        if (current == null || earlier == null) return@forEach
        val change = constantFileChange(earlier, current)
        if (!change.isEmpty()) changes[source] = change
    }
    return changes
}

private fun workingText(source: Path): String? =
    try {
        Files.readAllLines(source).joinToString("\n")
    } catch (_: Exception) {
        null
    }

/**
 * The classes that read one of those constants. The result carries the change that put the class on the list,
 * shown as the file's tooltip.
 */
/**
 * The files the IDE's Java index reports as reading one of the changed constants, empty when the index cannot
 * answer: while it is still building, or when the changed files are not part of it.
 */
private fun indexAffectedFiles(
    project: Project,
    changes: Map<Path, ConstantFileChange>,
): Map<Path, String> {
    if (DumbService.isDumb(project)) return emptyMap()
    return try {
        ReadAction.nonBlocking(
            Callable { ConstantUsageScanner.affectedFiles(project, changes) },
        ).executeSynchronously()
    } catch (_: IndexNotReadyException) {
        emptyMap()
    }
}

private fun classesFor(project: Project, sources: List<Path>): Map<Path, Path?> =
    ReadAction.nonBlocking(
        Callable {
            sources.associateWith { source ->
                val virtualFile = LocalFileSystem.getInstance().findFileByPath(source.toAbsolutePath().toString())
                    ?: return@associateWith null
                if (!virtualFile.name.endsWith(".java")) null else JavaClassResolver.compiledClassFile(project, virtualFile)
            }
        },
    ).executeSynchronously()

private fun uploadSelected(
    project: Project,
    selectedRows: List<UploadRow>,
    profile: org.kavo.uploader.settings.ServerProfile?,
    data: ChangedFilesData,
    settings: SftpSettings,
    zipSelected: Boolean,
    archiveName: String,
) {
    if (profile == null || selectedRows.isEmpty()) return
    val expanded = ClassFileExpander.expand(
        dedupeRows(selectedRows).map { it.uploadPath },
        settings.withInnerClasses,
    )
    val mappings = effectiveMappings(profile)
    val requests = expanded.mapNotNull { path ->
        val remote = if (profile.type == org.kavo.uploader.settings.ServerType.LOCAL)
            PathMappingResolver.resolveLocal(data.projectRoot, path, mappings, profile.basePath)
        else
            PathMappingResolver.resolve(data.projectRoot, path, mappings)
        remote ?: return@mapNotNull null
        UploadRequest(path, remote)
    }
    if (requests.isEmpty()) {
        UploaderNotifications.error(project, MyMessageBundle.message("upload.no.mapping", profile.name))
        return
    }
    object : Task.Backgroundable(project, MyMessageBundle.message("upload.progress", profile.name), true) {
        override fun run(indicator: ProgressIndicator) {
            try {
                val isLocal = profile.type == org.kavo.uploader.settings.ServerType.LOCAL
                val service = ApplicationManager.getApplication().getService(SftpUploadService::class.java)
                if (isLocal && zipSelected) {
                    if (archiveName.isBlank()) {
                        UploaderNotifications.error(project, MyMessageBundle.message("changed.zip.name.required"))
                        return
                    }
                    val archivePath =
                        resolveArchivePath(data.projectRoot, archiveName, mappings, profile.basePath)
                    service.zipLocal(
                        data.projectRoot,
                        archivePath,
                        requests,
                        mappings,
                        indicator::checkCanceled,
                    )
                    UploaderNotifications.info(
                        project,
                        MyMessageBundle.message("changed.zip.success", requests.size, archivePath.toString()),
                    )
                    return
                }
                if (!isLocal && PasswordStore.passwordBytes(profile) == null) {
                    UploaderNotifications.error(
                        project,
                        MyMessageBundle.message("error.password.missing", profile.name),
                    )
                    return
                }
                val local = UploadStrategy { _, _, reqs, cancel ->
                    service.copyLocal(reqs, cancel)
                }
                val rsync = UploadStrategy { p, pwd, reqs, cancel ->
                    service.uploadViaRsync(p, pwd, reqs, cancel)
                }
                val sftp = UploadStrategy { p, pwd, reqs, cancel ->
                    service.upload(p, PasswordAuthentication(pwd ?: ByteArray(0)), reqs, cancel)
                }
                uploadWithStrategy(
                    profile,
                    if (isLocal) null else PasswordStore.passwordBytes(profile),
                    requests,
                    indicator::checkCanceled,
                    local,
                    rsync,
                    sftp,
                )
                UploaderNotifications.info(
                    project,
                    MyMessageBundle.message("upload.success", requests.size, profile.name),
                )
            } catch (error: Exception) {
                UploaderNotifications.error(
                    project,
                    MyMessageBundle.message(
                        "upload.failed",
                        profile.name,
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
        }
    }.queue()
}

private fun resolveArchivePath(
    projectRoot: Path,
    archiveName: String,
    mappings: List<org.kavo.uploader.settings.PathMapping>,
    basePath: String,
): Path {
    if (archiveName.startsWith("/")) return withZipSuffix(java.nio.file.Paths.get(archiveName))
    val base = basePath.trim().replace('\\', '/').trimEnd('/')
    if (base.isNotEmpty()) return withZipSuffix(java.nio.file.Paths.get("$base/$archiveName"))
    val resolved = PathMappingResolver.resolve(projectRoot, java.nio.file.Paths.get(archiveName), mappings)
    if (resolved != null) return withZipSuffix(java.nio.file.Paths.get(resolved))
    val root = java.nio.file.Paths.get(projectRoot.toAbsolutePath().normalize().toString())
    return withZipSuffix(
        java.nio.file.Paths.get(
            root.toString().let { if (it.endsWith("/")) it + archiveName else "$it/$archiveName" },
        )
    )
}

private fun withZipSuffix(path: Path): Path {
    val name = File(path.toString()).name
    return if (name.endsWith(".zip", ignoreCase = true)) path else java.nio.file.Paths.get("$path.zip")
}

private class CommitInputDialog(project: Project) : DialogWrapper(project, true) {
    private val commitField = JBTextField()

    init {
        title = MyMessageBundle.message("changed.commit.dialog")
        init()
    }

    fun commit(): String = commitField.text.trim()

    override fun createCenterPanel(): JComponent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent(MyMessageBundle.message("changed.commit.prompt"), commitField)
            .panel
}
