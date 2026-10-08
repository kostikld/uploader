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
                   "user@example.com:'/remote/a.txt'",
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
                   "user@example.com:'/remote/a.txt'",
                ),
             command,
            )
        assertTrue("sshpass must not be used", command.none { it == "sshpass" })
        }

      @Test
      fun `rsync command single-quotes remote path so inner class dollar sign is not expanded`() {
          val inner = UploadRequest(
              java.nio.file.Paths.get("/project/Request\$Builder.class"),
               "/remote/com/example/Request\$Builder.class",
           )

          val command = SftpUploadService().buildRsyncCommand(profile, null, inner)

          assertEquals("user@example.com:'/remote/com/example/Request\$Builder.class'", command.last())
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

     @Test
    fun `new profile defaults to sftp type`() {
        assertEquals(org.kavo.uploader.settings.ServerType.SFTP, ServerProfile().type)
    }

    @Test
    fun `orchestrator uses local strategy for local profiles`() {
        val local = recordingStrategy()
        val sftp = recordingStrategy()

        profile.type = org.kavo.uploader.settings.ServerType.LOCAL
        uploadWithStrategy(profile, null, listOf(request), {}, local, sftp, sftp)

        assertTrue(local.invoked)
        assertFalse(sftp.invoked)
    }

    @Test
    fun `copyLocal copies local files to local destinations`() {
        val root = java.nio.file.Files.createTempDirectory("copy-local-test")
        val source = root.resolve("a.txt")
        java.nio.file.Files.write(source, "hi".toByteArray())
        val destination = root.resolve("out/a.txt")

        SftpUploadService().copyLocal(listOf(UploadRequest(source, destination.toString())))

        assertEquals("hi", java.nio.file.Files.readAllBytes(destination).decodeToString())
    }

    @Test
    fun `copyLocal overwrites existing local destination file`() {
        val root = java.nio.file.Files.createTempDirectory("copy-local-test")
        val source = root.resolve("a.txt")
        java.nio.file.Files.write(source, "new".toByteArray())
        val destination = root.resolve("out/a.txt")
        java.nio.file.Files.createDirectories(destination.parent)
        java.nio.file.Files.write(destination, "old".toByteArray())

        SftpUploadService().copyLocal(listOf(UploadRequest(source, destination.toString())))

        assertEquals("new", java.nio.file.Files.readAllBytes(destination).decodeToString())
    }

    @Test
    fun `copyLocal copies into existing local directory when destination path is a directory`() {
        val root = java.nio.file.Files.createTempDirectory("copy-local-test")
        val source = root.resolve("a.txt")
        java.nio.file.Files.write(source, "new".toByteArray())
        val destination = root.resolve("out/a.txt")
        java.nio.file.Files.createDirectories(destination)

        SftpUploadService().copyLocal(listOf(UploadRequest(source, destination.toString())))

        assertEquals(
            "new",
            java.nio.file.Files.readAllBytes(destination.resolve(java.io.File(source.toString()).name)).decodeToString(),
        )
    }

    @Test
    fun `zipLocal rejects an existing archive file with a clear message`() {
        val root = java.nio.file.Files.createTempDirectory("zip-local-test")
        val projectRoot = root.resolve("project")
        java.nio.file.Files.createDirectories(projectRoot)
        val archive = java.nio.file.Paths.get("$root/archive.zip")
        java.nio.file.Files.write(archive, ByteArray(0))
        var message = ""

        try {
            SftpUploadService().zipLocal(
                projectRoot,
                archive,
                emptyList(),
                emptyList(),
            )
        } catch (error: Exception) {
            message = error.message ?: error.javaClass.simpleName
        }

        assertTrue("expected clear error", message.contains("already exists"))
    }

    @Test
    fun `zipLocal rejects an existing archive directory with a clear message`() {
        val root = java.nio.file.Files.createTempDirectory("zip-local-test")
        val projectRoot = root.resolve("project")
        java.nio.file.Files.createDirectories(projectRoot)
        val archive = java.nio.file.Paths.get("$root/archive")
        java.nio.file.Files.createDirectories(archive)
        var message = ""

        try {
            SftpUploadService().zipLocal(
                projectRoot,
                archive,
                emptyList<UploadRequest>(),
                emptyList<org.kavo.uploader.settings.PathMapping>(),
            )
        } catch (error: Exception) {
            message = error.message ?: error.javaClass.simpleName
        }

        assertTrue("expected clear error", message.contains("points to a directory"))
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
