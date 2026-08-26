package org.kavo.uploader.upload

import com.intellij.openapi.components.Service
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import org.kavo.uploader.settings.ServerProfile
import java.nio.file.Files
import java.nio.file.Path

data class UploadRequest(
    val localFile: Path,
    val remoteFile: String,
)

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

    fun executeCommand(
        profile: ServerProfile,
        authentication: SftpAuthentication,
        command: String,
        checkCanceled: () -> Unit = {},
    ): Int = withSession(profile, authentication) { session ->
        var channel: ChannelExec? = null
        try {
            checkCanceled()
            channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            channel.connect(CONNECT_TIMEOUT_MS)
            while (!channel.isClosed) {
                checkCanceled()
                Thread.sleep(COMMAND_POLL_INTERVAL_MS)
            }
            checkCanceled()
            channel.exitStatus
        } finally {
            if (channel?.isConnected == true) channel.disconnect()
        }
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

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val COMMAND_POLL_INTERVAL_MS = 100L
    }
}
