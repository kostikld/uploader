package org.kavo.uploader.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.vfs.LocalFileSystem
import org.kavo.uploader.MyMessageBundle
import org.kavo.uploader.UploaderNotifications
import org.kavo.uploader.changedfiles.buildUploadRows
import org.kavo.uploader.changedfiles.ChangedFilesData
import org.kavo.uploader.changedfiles.ChangedFilesDialog
import org.kavo.uploader.changedfiles.UploadRow
import org.kavo.uploader.settings.PasswordStore
import org.kavo.uploader.settings.SftpSettings
import org.kavo.uploader.upload.ClassFileExpander
import org.kavo.uploader.upload.GitScanner
import org.kavo.uploader.upload.JavaClassResolver
import org.kavo.uploader.upload.PasswordAuthentication
import org.kavo.uploader.upload.PathMappingResolver
import org.kavo.uploader.upload.SftpUploadService
import org.kavo.uploader.upload.UploadRequest
import org.kavo.uploader.upload.UploadStrategy
import org.kavo.uploader.upload.uploadWithRsyncFallback
import java.nio.file.Path

fun launchUploadChangedFiles(project: Project) {
    val servers = SftpSettings.getInstance().servers()
    if (servers.isEmpty()) {
        UploaderNotifications.info(project, MyMessageBundle.message("changed.no.servers"))
        return
         }
    val workDir = project.basePath?.let { java.io.File(it) }
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
    workDir: java.io.File,
    servers: List<org.kavo.uploader.settings.ServerProfile>,
  ) {
    val basePath = project.basePath
    if (basePath == null) return
    val projectRoot = java.nio.file.Paths.get(basePath)
    val changed = GitScanner.changedFiles(workDir)
    if (changed.isEmpty()) {
        UploaderNotifications.info(project, MyMessageBundle.message("changed.none"))
        return
         }
    val classByJava: Map<Path, Path?> = ReadAction.computeBlocking<Map<Path, Path?>, RuntimeException> {
        changed.associateWith { path ->
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(path.toAbsolutePath().toString())
                  ?: return@associateWith null
            if (!virtualFile.name.endsWith(".java")) null else
                JavaClassResolver.compiledClassFile(project, virtualFile)
             }
          } as Map<Path, Path?>
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
        uploadSelected(project, dialog.result(), dialog.selectedServer(), data, settings)
          }
      }

private fun uploadSelected(
    project: Project,
    selectedRows: List<UploadRow>,
    profile: org.kavo.uploader.settings.ServerProfile?,
    data: ChangedFilesData,
    settings: SftpSettings,
  ) {
    if (profile == null || selectedRows.isEmpty()) return
    val expanded = ClassFileExpander.expand(selectedRows.map { it.uploadPath }, settings.withInnerClasses)
    val requests = expanded.mapNotNull { path ->
        val remote = PathMappingResolver.resolve(data.projectRoot, path, profile.mappings)
             ?: return@mapNotNull null
        UploadRequest(path, remote)
         }
    if (requests.isEmpty()) {
        UploaderNotifications.error(project, MyMessageBundle.message("upload.no.mapping", profile.name))
        return
         }
    object : Task.Backgroundable(project, MyMessageBundle.message("upload.progress", profile.name), true) {
        override fun run(indicator: ProgressIndicator) {
            try {
                val password = PasswordStore.get(profile.id)?.toByteArray()
                if (password == null) {
                    UploaderNotifications.error(project, MyMessageBundle.message("error.password.missing", profile.name))
                    return
                   }
                val service = ApplicationManager.getApplication().getService(SftpUploadService::class.java)
                val rsync = UploadStrategy { p, pwd, reqs, cancel ->
                    service.uploadViaRsync(p, pwd, reqs, cancel)
                 }
                val sftp = UploadStrategy { p, pwd, reqs, cancel ->
                    service.upload(p, PasswordAuthentication(pwd ?: ByteArray(0)), reqs, cancel)
                 }
                uploadWithRsyncFallback(
                    profile,
                    password,
                    requests,
                    indicator::checkCanceled,
                    rsync,
                    sftp,
                 )
                UploaderNotifications.info(project, MyMessageBundle.message("upload.success", requests.size, profile.name))
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
