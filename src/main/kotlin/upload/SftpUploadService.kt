package org.kavo.uploader.upload

import com.intellij.openapi.components.Service
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import org.kavo.uploader.settings.ServerProfile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

data class UploadRequest(
    val localFile: Path,
    val remoteFile: String,
)

data class CommandExecutionResult(
    val exitStatus: Int,
    val standardOutput: String,
    val isOutputTruncated: Boolean,
)

class RemoteFileMissingException(remoteFile: String) :
      RuntimeException("Remote file not found: $remoteFile")

internal const val MAX_STANDARD_OUTPUT_BYTES = 64 * 1024

internal class BoundedStandardOutput(private val maximumBytes: Int) {
    private val bytes = ByteArrayOutputStream(maximumBytes)

    var isTruncated = false
        private set

    init {
        require(maximumBytes >= 0) { "Maximum output size must not be negative" }
    }

    fun append(source: ByteArray, offset: Int, length: Int) {
        val remainingBytes = maximumBytes - bytes.size()
        val bytesToRetain = length.coerceAtMost(remainingBytes.coerceAtLeast(0))
        if (bytesToRetain > 0) bytes.write(source, offset, bytesToRetain)
        if (bytesToRetain < length) isTruncated = true
    }

    fun decoded(): String = bytes.toString(StandardCharsets.UTF_8)
}

fun interface SftpAuthentication {
    fun configure(session: Session)
}

class PasswordAuthentication(private val password: ByteArray) : SftpAuthentication {
    override fun configure(session: Session) {
        session.setPassword(password)
    }
}

internal fun formatElapsedTime(elapsedMillis: Long): String {
    val elapsedSeconds = (elapsedMillis.coerceAtLeast(0) / 1_000).toInt()
    return "%02d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60)
}

internal fun isSuccessfulCommandExit(exitStatus: Int): Boolean = exitStatus == 0

internal fun escapeSingleQuoteShell(value: String): String =
     value.replace("'", "'\\''")

@Service(Service.Level.APP)
class SftpUploadService {
    fun upload(
        profile: ServerProfile,
        authentication: SftpAuthentication,
        requests: List<UploadRequest>,
        checkCanceled: () -> Unit = {},
    ) {
        withChannel(profile, authentication) { channel ->
            requests.forEach { request ->
                checkCanceled()
                require(Files.isRegularFile(request.localFile)) {
                    "Not a regular file: ${request.localFile}"
                }
                val remoteDirectory = request.remoteFile.substringBeforeLast('/', "")
                require(remoteDirectory.startsWith("/")) {
                    "Remote path must be absolute: ${request.remoteFile}"
                }
                ensureDirectory(channel, remoteDirectory)
                Files.newInputStream(request.localFile).use { input ->
                    channel.put(input, request.remoteFile)
                }
            }
        }
    }

    fun testConnection(profile: ServerProfile, authentication: SftpAuthentication) {
        withChannel(profile, authentication) { channel -> channel.pwd() }
       }

    fun download(
        profile: ServerProfile,
        authentication: SftpAuthentication,
        remoteFile: String,
        checkCanceled: () -> Unit = {},
     ): ByteArray {
        require(remoteFile.startsWith("/")) { "Remote path must be absolute: $remoteFile" }
        return withChannel(profile, authentication) { channel ->
            checkCanceled()
            try {
                channel.get(remoteFile).use { input ->
                    input.readAllBytes()
                 }
            } catch (error: SftpException) {
                if (error.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    throw RemoteFileMissingException(remoteFile)
                 }
                throw error
            }
         }
       }

    fun uploadViaRsync(
        profile: ServerProfile,
        password: ByteArray?,
        requests: List<UploadRequest>,
        checkCanceled: () -> Unit = {},
    ) {
        requests.forEach { request ->
            checkCanceled()
            runRsync(profile, password, request)
        }
    }

    fun executeCommand(
        profile: ServerProfile,
        authentication: SftpAuthentication,
        command: String,
        checkCanceled: () -> Unit = {},
    ): CommandExecutionResult = withSession(profile, authentication) { session ->
        var channel: ChannelExec? = null
        try {
            checkCanceled()
            channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            val standardOutput = BoundedStandardOutput(MAX_STANDARD_OUTPUT_BYTES)
            val outputStream = channel.inputStream
            val buffer = ByteArray(STANDARD_OUTPUT_READ_BUFFER_BYTES)
            channel.connect(CONNECT_TIMEOUT_MS)
            while (!channel.isClosed) {
                drainAvailable(outputStream, buffer, standardOutput)
                checkCanceled()
                Thread.sleep(COMMAND_POLL_INTERVAL_MS)
            }
            drainToEnd(outputStream, buffer, standardOutput)
            checkCanceled()
            CommandExecutionResult(
                exitStatus = channel.exitStatus,
                standardOutput = standardOutput.decoded(),
                isOutputTruncated = standardOutput.isTruncated,
            )
        } finally {
            if (channel?.isConnected == true) channel.disconnect()
        }
    }

    internal fun buildSshBase(profile: ServerProfile, password: ByteArray?): List<String> {
        val batchMode = if (password == null) "yes" else "no"
        return listOf(
              "ssh",
              "-p", profile.port.toString(),
               "-o", "BatchMode=$batchMode",
               "-o", "StrictHostKeyChecking=no",
             )
        }

    internal fun buildRsyncCommand(profile: ServerProfile, password: ByteArray?, request: UploadRequest): List<String> {
        val source = request.localFile.toAbsolutePath().toString()
        val remote = "${profile.username}@${profile.host}:${request.remoteFile}"
        val ssh = buildSshBase(profile, password).joinToString(" ")
        return listOf(
              "rsync",
               "-avz",
               "-e",
              ssh,
              source,
              remote,
             )
        }

     private fun runRsync(profile: ServerProfile, password: ByteArray?, request: UploadRequest) {
         val source = request.localFile.toAbsolutePath()
         require(Files.isRegularFile(source)) { "Not a regular file: $source" }
         val remoteDirectory = request.remoteFile.substringBeforeLast('/', "")
         require(remoteDirectory.startsWith("/")) { "Remote path must be absolute: ${request.remoteFile}" }

         val helper = if (password == null) null else createAskpass(password)
         try {
             runRemoteMkdir(profile, password, remoteDirectory, helper)
             runNamedProcess(
                "rsync",
                buildRsyncCommand(profile, password, request),
                helper,
                errorLabel = "rsync",
             )
           } finally {
            deleteQuietly(helper)
            }
        }

     private fun runRemoteMkdir(profile: ServerProfile, password: ByteArray?, remoteDirectory: String, helper: File?) {
         if (remoteDirectory == "/") return
         val command = buildSshBase(profile, password) +
                     listOf("${profile.username}@${profile.host}", "mkdir", "-p", remoteDirectory)
         runNamedProcess(command.first(), command, helper, errorLabel = "mkdir")
        }

     private fun runNamedProcess(
         executable: String,
         command: List<String>,
         helper: File?,
         errorLabel: String,
       ) {
         val builder = ProcessBuilder(command).redirectErrorStream(true)
         if (helper != null) configureAskpass(builder, helper)
         applyProcessEnvironment(builder)
         val process = try {
             builder.start()
           } catch (launch: IOException) {
             throw RuntimeException(
                  "$errorLabel: cannot start '$executable' (${launch.message}). " +
                  "Make sure $executable is installed and available on your PATH.",
             )
           }
         val output = process.inputStream.bufferedReader().use { it.readText() }
         val exitStatus = process.waitFor()
         if (exitStatus != 0) {
             throw RuntimeException("$errorLabel exited with status $exitStatus: ${output.trim()}")
          }
       }

     private fun applyProcessEnvironment(builder: ProcessBuilder) {
         val env = builder.environment()
         val augmentedPath = augmentPath(
             env["PATH"] ?: "",
             executableDirectories(),
         )
         env["PATH"] = augmentedPath
      }

     private fun augmentPath(existingPath: String, extraDirectories: List<String>): String {
         val separator = System.getProperty("path.separator") ?: ":"
         val existing = existingPath.split(separator).filter { it.isNotBlank() }
         val seen = LinkedHashSet<String>()
         (extraDirectories + existing).forEach { seen.add(it) }
         return seen.joinToString(separator)
         }

     private fun executableDirectories(): List<String> {
         val os = System.getProperty("os.name").lowercase()
         return if (os.contains("mac") || os.contains("nix")) {
            listOf("/usr/local/bin", "/opt/homebrew/bin", "/usr/bin", "/bin", "/usr/sbin", "/sbin")
         } else {
            listOf("/usr/local/bin", "/usr/bin", "/bin", "/usr/sbin", "/sbin")
          }
      }

     private fun createAskpass(password: ByteArray): File {
          val passwordText = String(password, StandardCharsets.UTF_8)
          val tmpDir = File(System.getProperty("java.io.tmpdir"))
          val helper = File.createTempFile("sftp-uploader-askpass-", ".sh", tmpDir)
         val script =
              "#!/bin/sh\nprintf '%s\\n' '" + escapeSingleQuoteShell(passwordText) + "'\n"
         Files.write(helper.toPath(), script.toByteArray(StandardCharsets.UTF_8))
         helper.setExecutable(true, false)
         return helper
       }

     private fun configureAskpass(builder: ProcessBuilder, helper: File) {
         builder.environment().apply {
             put("SSH_ASKPASS", helper.absolutePath)
             put("SSH_ASKPASS_REQUIRE", "force")
             put("DISPLAY", "dummy")
          }
      }

     private fun deleteQuietly(file: File?) {
         if (file != null) file.delete()
      }

    private fun <T> withChannel(
        profile: ServerProfile,
        authentication: SftpAuthentication,
        action: (ChannelSftp) -> T,
      ): T = withSession(profile, authentication) { session ->
        var channel: ChannelSftp? = null
        try {
            channel = session.openChannel("sftp") as ChannelSftp
            channel.connect(CONNECT_TIMEOUT_MS)
            action(channel)
        } finally {
            if (channel?.isConnected == true) channel.disconnect()
        }
    }

    private fun <T> withSession(
        profile: ServerProfile,
        authentication: SftpAuthentication,
        action: (Session) -> T,
    ): T {
        val session = JSch().getSession(profile.username, profile.host, profile.port)
        authentication.configure(session)
        // A host-key policy can be added to the profile model without changing authentication.
        session.setConfig("StrictHostKeyChecking", "no")

        try {
            session.connect(CONNECT_TIMEOUT_MS)
            return action(session)
        } finally {
            if (session.isConnected) session.disconnect()
        }
    }

    private fun ensureDirectory(channel: ChannelSftp, remoteDirectory: String) {
        channel.cd("/")
        remoteDirectory.split('/').filter { it.isNotBlank() }.forEach { segment ->
            require(segment != "." && segment != "..") { "Invalid remote directory" }
            try {
                channel.cd(segment)
            } catch (error: SftpException) {
                if (error.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw error
                channel.mkdir(segment)
                channel.cd(segment)
            }
        }
    }

    private fun drainAvailable(input: InputStream, buffer: ByteArray, output: BoundedStandardOutput) {
        while (input.available() > 0) {
            val bytesRead = input.read(buffer, 0, minOf(buffer.size, input.available()))
            if (bytesRead <= 0) return
            output.append(buffer, 0, bytesRead)
        }
    }

    private fun drainToEnd(input: InputStream, buffer: ByteArray, output: BoundedStandardOutput) {
        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead <= 0) return
            output.append(buffer, 0, bytesRead)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val COMMAND_POLL_INTERVAL_MS = 100L
        const val STANDARD_OUTPUT_READ_BUFFER_BYTES = 8 * 1024
    }
}
