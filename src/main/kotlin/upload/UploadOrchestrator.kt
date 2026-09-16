package org.kavo.uploader.upload

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import org.kavo.uploader.MyMessageBundle
import org.kavo.uploader.settings.ServerProfile
import java.util.concurrent.CountDownLatch

fun interface UploadStrategy {
    fun upload(profile: ServerProfile, password: ByteArray?, requests: List<UploadRequest>, checkCanceled: () -> Unit)
}

fun uploadWithRsyncFallback(
    profile: ServerProfile,
    password: ByteArray?,
    requests: List<UploadRequest>,
    checkCanceled: () -> Unit,
    rsync: UploadStrategy,
    sftp: UploadStrategy,
) = uploadWithRsyncFallback(profile, password, requests, checkCanceled, rsync, sftp, ::confirmSftpFallback)

fun uploadWithRsyncFallback(
    profile: ServerProfile,
    password: ByteArray?,
    requests: List<UploadRequest>,
    checkCanceled: () -> Unit,
    rsync: UploadStrategy,
    sftp: UploadStrategy,
    confirmFallback: () -> Boolean,
) {
    if (!profile.useRsync) {
        sftp.upload(profile, password, requests, checkCanceled)
        return
      }
    try {
        rsync.upload(profile, password, requests, checkCanceled)
      } catch (error: Exception) {
        if (!confirmFallback()) throw error
        sftp.upload(profile, password, requests, checkCanceled)
     }
 }

internal fun confirmSftpFallback(): Boolean {
    val latch = CountDownLatch(1)
    var accepted = false
    ApplicationManager.getApplication().invokeLater {
        accepted = Messages.showYesNoDialog(
            MyMessageBundle.message("rsync.failed.title"),
            MyMessageBundle.message("rsync.failed.fallback"),
            null,
            ) != Messages.NO
        latch.countDown()
         }
    latch.await()
    return accepted
       }
