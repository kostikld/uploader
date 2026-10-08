package org.kavo.uploader.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import org.kavo.uploader.MyMessageBundle
import org.kavo.uploader.UploaderNotifications
import org.kavo.uploader.changedfiles.ChangedFilesData
import org.kavo.uploader.changedfiles.ChangedFilesDialog
import org.kavo.uploader.changedfiles.UploadRow
import org.kavo.uploader.changedfiles.buildUploadRows
import org.kavo.uploader.settings.PasswordStore
import org.kavo.uploader.settings.SftpSettings
import org.kavo.uploader.upload.*
import java.io.File
import java.util.concurrent.Callable

fun launchUploadChangedFiles(project: Project) {
    val servers = SftpSettings.getInstance().servers()
    if (servers.isEmpty()) {
        UploaderNotifications.info(project, MyMessageBundle.message("changed.no.servers"))
        return
    }
    val workDir = project.basePath?.let { File(it) }
    if (workDir == null || !GitScanner.isGitRepository(workDir)) {
        UploaderNotifications.info(project, MyMessageBundle.message("changed.not.git"))
        return
    }
    object : Task.Backgroundable(project, MyMessageBundle.message("changed.scanning"), true) {
        override fun run(indicator: ProgressIndicator) {
            scanAndShow(project, workDir, servers)
        }
    }.queue()
}

private fun scanAndShow(
    project: Project,
    workDir: File,
    servers: List<org.kavo.uploader.settings.ServerProfile>,
) {
    val basePath = project.basePath ?: return
    val projectRoot = java.nio.file.Paths.get(basePath)
    val changed = GitScanner.changedFiles(workDir)
    if (changed.isEmpty()) {
        UploaderNotifications.info(project, MyMessageBundle.message("changed.none"))
        return
    }
    val classByJava = ReadAction.nonBlocking(
        Callable {
            changed.associateWith { path ->
                val virtualFile = LocalFileSystem.getInstance().findFileByPath(path.toAbsolutePath().toString())
                    ?: return@associateWith null
                if (!virtualFile.name.endsWith(".java")) null else
                    JavaClassResolver.compiledClassFile(project, virtualFile)
            }
        },
    ).executeSynchronously()
    val settings = SftpSettings.getInstance()
    val rows = buildUploadRows(
        changed = changed,
        classByJava = classByJava,
        uploadJavaClassFiles = settings.uploadJavaClassFiles,
        projectRoot = projectRoot,
    )
    val data = ChangedFilesData(rows, projectRoot, servers)
    ApplicationManager.getApplication().invokeLater {
        val dialog = ChangedFilesDialog(project, data)
        if (!dialog.showAndGet()) return@invokeLater
        uploadSelected(
            project,
            dialog.result(),
            dialog.selectedServer(),
            data,
            settings,
            dialog.zipSelected(),
            dialog.archiveName(),
        )
    }
}

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
    val expanded = ClassFileExpander.expand(selectedRows.map { it.uploadPath }, settings.withInnerClasses)
    val requests = expanded.mapNotNull { path ->
        val remote = if (profile.type == org.kavo.uploader.settings.ServerType.LOCAL)
            PathMappingResolver.resolveLocal(data.projectRoot, path, profile.mappings, profile.basePath)
        else
            PathMappingResolver.resolve(data.projectRoot, path, profile.mappings)
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
                        resolveArchivePath(data.projectRoot, archiveName, profile.mappings, profile.basePath)
                    service.zipLocal(
                        data.projectRoot,
                        archivePath,
                        requests,
                        profile.mappings,
                        indicator::checkCanceled
                    )
                    UploaderNotifications.info(
                        project,
                        MyMessageBundle.message("changed.zip.success", requests.size, archivePath.toString())
                    )
                    return
                }
                if (!isLocal) {
                    val password = PasswordStore.get(profile.id)?.toByteArray()
                    if (password == null) {
                        UploaderNotifications.error(
                            project,
                            MyMessageBundle.message("error.password.missing", profile.name)
                        )
                        return
                    }
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
                    if (isLocal) null else PasswordStore.get(profile.id)?.toByteArray(),
                    requests,
                    indicator::checkCanceled,
                    local,
                    rsync,
                    sftp,
                )
                UploaderNotifications.info(
                    project,
                    MyMessageBundle.message("upload.success", requests.size, profile.name)
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
    projectRoot: java.nio.file.Path,
    archiveName: String,
    mappings: List<org.kavo.uploader.settings.PathMapping>,
    basePath: String,
): java.nio.file.Path {
    if (archiveName.startsWith("/")) return withZipSuffix(java.nio.file.Paths.get(archiveName))
    val base = basePath.trim().replace('\\', '/').trimEnd('/')
    if (base.isNotEmpty()) return withZipSuffix(java.nio.file.Paths.get("$base/$archiveName"))
    val resolved = PathMappingResolver.resolve(projectRoot, java.nio.file.Paths.get(archiveName), mappings)
    if (resolved != null) return withZipSuffix(java.nio.file.Paths.get(resolved))
    val root = java.nio.file.Paths.get(projectRoot.toAbsolutePath().normalize().toString())
    return withZipSuffix(
        java.nio.file.Paths.get(
            root.toString().let { if (it.endsWith("/")) it + archiveName else "$it/$archiveName" })
    )
}

private fun withZipSuffix(path: java.nio.file.Path): java.nio.file.Path {
    val name = File(path.toString()).name
    return if (name.endsWith(".zip", ignoreCase = true)) path else java.nio.file.Paths.get(path.toString() + ".zip")
}
