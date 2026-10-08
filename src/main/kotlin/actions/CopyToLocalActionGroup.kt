package org.kavo.uploader.actions

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import org.kavo.uploader.MyMessageBundle
import org.kavo.uploader.UploaderNotifications
import org.kavo.uploader.settings.SftpSettings
import org.kavo.uploader.upload.ClassFileExpander
import org.kavo.uploader.upload.JavaClassResolver
import org.kavo.uploader.upload.PathMappingResolver
import org.kavo.uploader.upload.SftpUploadService
import org.kavo.uploader.upload.UploadRequest
import org.kavo.uploader.upload.UploadStrategy
import org.kavo.uploader.upload.uploadWithStrategy
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Callable

class CopyToLocalActionGroup : ActionGroup(
    MyMessageBundle.message("action.copy.local.group"),
    true,
) {
    override fun getChildren(event: AnActionEvent?): Array<AnAction> =
        localMenuChildren { CopyToLocalProfileAction(it) }

    override fun update(event: AnActionEvent) {
        val files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        event.presentation.isEnabledAndVisible =
            event.project != null && !files.isNullOrEmpty() && files.all { !it.isDirectory && it.isInLocalFileSystem }
        val compiled = SftpSettings.getInstance().uploadJavaClassFiles &&
            !files.isNullOrEmpty() && files.any { it.name.endsWith(".java") }
        event.presentation.text = MyMessageBundle.message(
            if (compiled) "action.copy.local.compiled.group" else "action.copy.local.group",
        )
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

private class CopyToLocalProfileAction(private val profile: org.kavo.uploader.settings.ServerProfile) : AnAction(profile.name) {
    override fun update(event: AnActionEvent) {
        val project = event.project
        val files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        event.presentation.isEnabled = project != null &&
            !files.isNullOrEmpty() &&
            files.all { !it.isDirectory && it.isInLocalFileSystem }
        event.presentation.description = MyMessageBundle.message("action.copy.local.description", profile.name)
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val vFiles = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList().orEmpty()

        object : Task.Backgroundable(project, MyMessageBundle.message("copy.local.progress", profile.name), true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val uploadClassFiles = SftpSettings.getInstance().uploadJavaClassFiles
                    val (files, missingCompiled) = ReadAction.nonBlocking(
                        Callable {
                            val resolvedFiles = JavaClassResolver.classFilesFor(project, vFiles, uploadClassFiles)
                            val missing = if (uploadClassFiles) {
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
                    val requests = resolveCopyRequests(project, files, profile)
                    if (requests == null) {
                        UploaderNotifications.error(
                            project,
                            if (missingCompiled.isNotEmpty())
                                MyMessageBundle.message("upload.compiled.not.built", missingCompiled.joinToString("\n"))
                            else
                                MyMessageBundle.message("upload.no.mapping", profile.name),
                        )
                        return
                    }
                    val service = ApplicationManager.getApplication().getService(SftpUploadService::class.java)
                    val local = UploadStrategy { _, _, reqs, cancel ->
                        service.copyLocal(reqs, cancel)
                    }
                    uploadWithStrategy(
                        profile,
                        null,
                        requests,
                        indicator::checkCanceled,
                        local,
                        local,
                        local,
                    )
                    UploaderNotifications.info(
                        project,
                        MyMessageBundle.message("copy.local.success", requests.size, profile.name),
                    )
                } catch (error: Exception) {
                    UploaderNotifications.error(
                        project,
                        MyMessageBundle.message(
                            "copy.local.failed",
                            profile.name,
                            error.message ?: error.javaClass.simpleName,
                        ),
                    )
                }
            }
        }.queue()
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

internal fun resolveCopyRequests(
    project: Project,
    files: List<Path>,
    profile: org.kavo.uploader.settings.ServerProfile,
): List<UploadRequest>? {
    val basePath = project.basePath ?: return null
    val projectRoot = Paths.get(basePath)
    val expanded = ClassFileExpander.expand(files, SftpSettings.getInstance().withInnerClasses)
    val requests = expanded.mapNotNull { file ->
        val target = if (profile.type == org.kavo.uploader.settings.ServerType.LOCAL)
            PathMappingResolver.resolveLocal(projectRoot, file, profile.mappings, profile.basePath)
        else
            PathMappingResolver.resolve(projectRoot, file, profile.mappings)
        target ?: return@mapNotNull null
        UploadRequest(file, target)
    }
    return requests.ifEmpty { null }
}
