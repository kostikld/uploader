package org.kavo.uploader.actions

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import org.kavo.uploader.MyMessageBundle
import org.kavo.uploader.UploaderNotifications
import org.kavo.uploader.settings.PasswordStore
import org.kavo.uploader.settings.ServerProfile
import org.kavo.uploader.settings.SftpSettings
import org.kavo.uploader.upload.PasswordAuthentication
import org.kavo.uploader.upload.PathMappingResolver
import org.kavo.uploader.upload.SftpUploadService
import org.kavo.uploader.upload.UploadRequest
import java.nio.file.Path
import java.nio.file.Paths

class UploadToServerActionGroup : ActionGroup(
    MyMessageBundle.message("action.upload.group"),
    true,
) {
    override fun getChildren(event: AnActionEvent?): Array<AnAction> =
        SftpSettings.getInstance().servers()
            .map { UploadToProfileAction(it) }
            .toTypedArray()

    override fun update(event: AnActionEvent) {
        val files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        event.presentation.isEnabledAndVisible =
            event.project != null && !files.isNullOrEmpty() && files.all { !it.isDirectory && it.isInLocalFileSystem }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

private class UploadToProfileAction(private val profile: ServerProfile) : AnAction(profile.name) {
    override fun update(event: AnActionEvent) {
        val project = event.project
        val files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        event.presentation.isEnabled = project != null &&
            !files.isNullOrEmpty() &&
            files.all { !it.isDirectory && it.isInLocalFileSystem } &&
            resolveRequests(project, files.map { Paths.get(it.path) }, profile) != null
        event.presentation.description = if (event.presentation.isEnabled) {
            MyMessageBundle.message("action.upload.description", profile.name)
        } else {
            MyMessageBundle.message("action.upload.unmapped")
        }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.map { Paths.get(it.path) }.orEmpty()
        val requests = resolveRequests(project, files, profile)
        if (requests == null) {
            UploaderNotifications.error(project, MyMessageBundle.message("upload.no.mapping", profile.name))
            return
        }
        val password = PasswordStore.get(profile.id)?.toByteArray()
        if (password == null) {
            UploaderNotifications.error(project, MyMessageBundle.message("error.password.missing", profile.name))
            return
        }

        object : Task.Backgroundable(project, MyMessageBundle.message("upload.progress", profile.name), true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    ApplicationManager.getApplication().getService(SftpUploadService::class.java).upload(
                        profile,
                        PasswordAuthentication(password),
                        requests,
                        indicator::checkCanceled,
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

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

internal fun resolveRequests(
    project: Project,
    files: List<Path>,
    profile: ServerProfile,
): List<UploadRequest>? {
    val basePath = project.basePath ?: return null
    val projectRoot = Paths.get(basePath)
    return files.map { file ->
        val remote = PathMappingResolver.resolve(projectRoot, file, profile.mappings) ?: return null
        UploadRequest(file, remote)
    }
}
