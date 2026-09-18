package org.kavo.uploader.actions

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import org.kavo.uploader.MyMessageBundle
import org.kavo.uploader.UploaderNotifications
import org.kavo.uploader.settings.PasswordStore
import org.kavo.uploader.settings.ServerProfile
import org.kavo.uploader.settings.SftpSettings
import org.kavo.uploader.upload.ClassFileExpander
import org.kavo.uploader.upload.JavaClassResolver
import org.kavo.uploader.upload.PasswordAuthentication
import org.kavo.uploader.upload.PathMappingResolver
import org.kavo.uploader.upload.RemoteFileMissingException
import org.kavo.uploader.upload.SftpUploadService
import java.nio.file.Path
import java.util.concurrent.Callable

enum class CompareMode {
    SOURCE,
    COMPILED,
}

abstract class CompareActionGroup(
    private val mode: CompareMode,
) : ActionGroup(
    if (mode == CompareMode.COMPILED)
        MyMessageBundle.message("action.compare.compiled.group")
       else MyMessageBundle.message("action.compare.group"),
    true,
) {
    override fun getChildren(event: AnActionEvent?): Array<AnAction> =
        SftpSettings.getInstance().servers()
             .map { CompareWithProfileAction(it, mode) }
             .toTypedArray()

    override fun update(event: AnActionEvent) {
        val files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        event.presentation.isEnabledAndVisible =
            event.project != null && !files.isNullOrEmpty() && files.all { !it.isDirectory && it.isInLocalFileSystem }
        event.presentation.text =
            if (mode == CompareMode.COMPILED)
                MyMessageBundle.message("action.compare.compiled.group")
               else MyMessageBundle.message("action.compare.group")
        if (mode == CompareMode.COMPILED) {
            event.presentation.isEnabledAndVisible =
                event.project != null && !files.isNullOrEmpty() &&
                    files.all { !it.isDirectory && it.isInLocalFileSystem } &&
                    files.any { it.name.endsWith(".java") }
             }
        }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

class CompareWithServerActionGroup : CompareActionGroup(CompareMode.SOURCE)

class CompareCompiledActionGroup : CompareActionGroup(CompareMode.COMPILED)

private class CompareWithProfileAction(
    private val profile: ServerProfile,
    private val mode: CompareMode,
) : AnAction(profile.name) {
    override fun update(event: AnActionEvent) {
        val project = event.project
        val files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        event.presentation.isEnabled = project != null &&
             !files.isNullOrEmpty() &&
            files.all { !it.isDirectory && it.isInLocalFileSystem }
        event.presentation.description =
            if (mode == CompareMode.COMPILED)
                MyMessageBundle.message("action.compare.compiled.description", profile.name)
               else MyMessageBundle.message("action.compare.description", profile.name)
        }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val vFiles = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList().orEmpty()
        val compiled = mode == CompareMode.COMPILED
        object : Task.Backgroundable(project, compareProgress(mode, profile.name), true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val settings = SftpSettings.getInstance()
                    val (files, missingCompiled) = ReadAction.nonBlocking(
                        Callable {
                            val resolvedFiles = JavaClassResolver.classFilesFor(project, vFiles, compiled)
                            val missing = if (compiled) {
                                vFiles.filter { it.name.endsWith(".java") }
                                    .mapNotNull { file ->
                                        if (JavaClassResolver.compiledClassFile(project, file) == null) file.name else null
                                    }
                            } else {
                                emptyList()
                            }
                            resolvedFiles to missing
                        },
                    ).executeSynchronously()
                    val basePath = project.basePath ?: return
                    val projectRoot = java.nio.file.Paths.get(basePath)
                    val expanded = if (compiled) {
                        ClassFileExpander.expand(files, settings.withInnerClasses)
                    } else {
                        files
                    }
                    val pairs = expanded.mapNotNull { file ->
                        PathMappingResolver.resolve(projectRoot, file, profile.mappings)?.let { file to it }
                    }
                    when {
                        pairs.isNotEmpty() -> compareFiles(project, pairs, profile, vFiles, missingCompiled, indicator)
                        compiled && missingCompiled.isNotEmpty() ->
                            UploaderNotifications.error(
                                project,
                                MyMessageBundle.message("compare.compiled.not.built", missingCompiled.joinToString("\n")),
                            )
                        compiled ->
                            UploaderNotifications.error(project, MyMessageBundle.message("compare.compiled.not.built.none"))
                        else ->
                            UploaderNotifications.error(project, MyMessageBundle.message("upload.no.mapping", profile.name))
                    }
                } catch (error: ProcessCanceledException) {
                    throw error
                } catch (error: Exception) {
                    UploaderNotifications.error(
                        project,
                        MyMessageBundle.message("compare.failed", profile.name, error.message ?: error.javaClass.simpleName),
                    )
                }
            }
        }.queue()
    }

    private fun compareFiles(
        project: Project,
        pairs: List<Pair<Path, String>>,
        profile: ServerProfile,
        vFiles: List<VirtualFile>,
        missingCompiled: List<String>,
        indicator: ProgressIndicator,
    ) {
        val password = PasswordStore.get(profile.id)?.toByteArray()
        if (password == null) {
            UploaderNotifications.error(project, MyMessageBundle.message("error.password.missing", profile.name))
            return
        }
        val service = ApplicationManager.getApplication().getService(SftpUploadService::class.java)
        val results = mutableListOf<CompareResult>()
        pairs.forEach { (local, remote) ->
            indicator.checkCanceled()
            val bytes = try {
                service.download(profile, PasswordAuthentication(password), remote, indicator::checkCanceled)
            } catch (_: RemoteFileMissingException) {
                results.add(CompareResult(local, null, remote))
                return@forEach
            }
            results.add(CompareResult(local, bytes, remote))
        }
        ApplicationManager.getApplication().invokeLater {
            showDiffView(project, results, profile, vFiles)
            if (missingCompiled.isNotEmpty()) {
                UploaderNotifications.error(
                    project,
                    MyMessageBundle.message("compare.compiled.not.built", missingCompiled.joinToString("\n")),
                )
            }
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

private class CompareResult(val local: Path, val remote: ByteArray?, val remotePath: String)

private fun compareProgress(mode: CompareMode, profileName: String): String =
    if (mode == CompareMode.COMPILED)
        MyMessageBundle.message("compare.compiled.progress", profileName)
       else
        MyMessageBundle.message("compare.progress", profileName)

private fun showDiffView(
    project: Project,
    results: List<CompareResult>,
    profile: ServerProfile,
    selected: List<VirtualFile>,
) {
    val factory = DiffContentFactory.getInstance()
    val requests: List<DiffRequest> = results.mapNotNull { result ->
        if (result.remote == null) return@mapNotNull null
        val vFile = localVirtualFile(selected, result.local) ?: return@mapNotNull null
        val left = factory.create(project, vFile)
        val right = factory.createFromBytes(project, result.remote, vFile)
        SimpleDiffRequest(
            MyMessageBundle.message("compare.title", result.remotePath),
            left,
            right,
            MyMessageBundle.message("compare.local.label"),
            MyMessageBundle.message("compare.remote.label", profile.name),
          )
        }

    val missing = results.filter { it.remote == null }.map { it.remotePath }
    if (missing.isNotEmpty()) {
        UploaderNotifications.error(
            project,
            MyMessageBundle.message("compare.not.found", missing.joinToString("\n")),
          )
        }

    if (requests.isNotEmpty()) {
        DiffManager.getInstance().showDiff(
            project,
            SimpleDiffRequestChain(requests),
            DiffDialogHints.FRAME,
          )
        }
}

private fun localVirtualFile(selected: List<VirtualFile>, local: Path): VirtualFile? {
    val absolute = local.toAbsolutePath().normalize()
    selected.firstOrNull { sameFile(it, absolute) }?.let { return it }
    return LocalFileSystem.getInstance().findFileByPath(absolute.toString())
}

private fun sameFile(virtualFile: VirtualFile, absolute: Path): Boolean =
    java.nio.file.Paths.get(virtualFile.path).toAbsolutePath().normalize() == absolute