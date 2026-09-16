package org.kavo.uploader.upload

import org.kavo.uploader.settings.ServerProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadOrchestratorTest {
    private val profile = ServerProfile(name = "prod", username = "user", host = "example.com", port = 22)
    private val request = UploadRequest(java.nio.file.Paths.get("/project/a.txt"), "/remote/a.txt")
    private val password = "secret".toByteArray()

     @Test
    fun `rsync command builds remote target and ssh transport`() {
        val command = SftpUploadService().buildRsyncCommand(profile, null, request)

        assertEquals(
            listOf(
                 "rsync",
                 "-avz",
                 "-e",
                 "ssh -p 22 -o BatchMode=yes -o StrictHostKeyChecking=no",
                 "/project/a.txt",
                 "user@example.com:/remote/a.txt",
              ),
            command,
          )
     }

      @Test
     fun `rsync command supplies password via SSH_ASKPASS when present`() {
         val command = SftpUploadService().buildRsyncCommand(profile, password, request)

         assertEquals(
             listOf(
                  "rsync",
                   "-avz",
                   "-e",
                   "ssh -p 22 -o BatchMode=no -o StrictHostKeyChecking=no",
                   "/project/a.txt",
                   "user@example.com:/remote/a.txt",
                ),
             command,
           )
         assertTrue("sshpass must not be used", command.none { it == "sshpass" })
       }

     @Test
    fun `orchestrator uses sftp directly when rsync disabled`() {
        val sftp = recordingStrategy()
        val rsync = failingStrategy()

        profile.useRsync = false
        uploadWithRsyncFallback(profile, password, listOf(request), {}, rsync, sftp) { false }

        assertTrue(sftp.invoked)
        assertFalse(rsync.invoked)
     }

     @Test
    fun `orchestrator falls back to sftp when rsync fails and user accepts`() {
        val sftp = recordingStrategy()
        val rsync = failingStrategy()

        profile.useRsync = true
        uploadWithRsyncFallback(profile, password, listOf(request), {}, rsync, sftp) { true }

        assertTrue(rsync.invoked)
        assertTrue(sftp.invoked)
     }

     @Test
    fun `orchestrator rethrows when rsync fails and user declines fallback`() {
        val sftp = recordingStrategy()
        var rethrown: Exception? = null

        profile.useRsync = true
        try {
            uploadWithRsyncFallback(profile, password, listOf(request), {}, failingStrategy(), sftp) { false }
             } catch (error: Exception) {
            rethrown = error
             }

        assertFalse(sftp.invoked)
        assertEquals("rsync boom", rethrown?.message)
     }

     @Test
    fun `orchestrator does not fall back when rsync succeeds`() {
        val sftp = recordingStrategy()
        val rsync = recordingStrategy()
        var confirmCalled = false

        profile.useRsync = true
        uploadWithRsyncFallback(profile, password, listOf(request), {}, rsync, sftp) {
            confirmCalled = true
            false
          }

        assertTrue(rsync.invoked)
        assertFalse(sftp.invoked)
        assertFalse(confirmCalled)
     }

     @Test
    fun `new profile defaults to rsync disabled`() {
        assertFalse(ServerProfile().useRsync)
      }

     private fun recordingStrategy() = object : UploadStrategy {
         var invoked = false

         override fun upload(profile: ServerProfile, password: ByteArray?, requests: List<UploadRequest>, checkCanceled: () -> Unit) {
            invoked = true
           }
       }

     private fun failingStrategy() = object : UploadStrategy {
         var invoked = false

         override fun upload(profile: ServerProfile, password: ByteArray?, requests: List<UploadRequest>, checkCanceled: () -> Unit) {
            invoked = true
            throw IllegalStateException("rsync boom")
           }
       }
}
