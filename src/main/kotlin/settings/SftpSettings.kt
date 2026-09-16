package org.kavo.uploader.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.util.UUID

data class PathMapping(
    var localPath: String = "",
    var remotePath: String = "",
)

data class ServerAction(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var command: String = "",
)

enum class ServerActionValidationError {
    BLANK_NAME,
    BLANK_COMMAND,
    DUPLICATE_NAME,
}

fun validateServerActions(actions: List<ServerAction>): ServerActionValidationError? {
    val names = mutableSetOf<String>()
    actions.forEach { action ->
        if (action.name.isBlank()) return ServerActionValidationError.BLANK_NAME
        if (action.command.isBlank()) return ServerActionValidationError.BLANK_COMMAND
        if (!names.add(action.name)) return ServerActionValidationError.DUPLICATE_NAME
    }
    return null
}

data class ServerProfile(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var host: String = "",
    var port: Int = 22,
    var username: String = "",
    var useRsync: Boolean = false,
    var mappings: MutableList<PathMapping> = mutableListOf(),
    var actions: MutableList<ServerAction> = mutableListOf(),
)

data class SftpSettingsState(
    var servers: MutableList<ServerProfile> = mutableListOf(),
    var withInnerClasses: Boolean = true,
    var uploadJavaClassFiles: Boolean = true,
    var newServerUseRsync: Boolean = false,
)

@Service(Service.Level.APP)
@State(name = "SftpUploaderSettings", storages = [Storage("sftpUploader.xml")])
class SftpSettings : PersistentStateComponent<SftpSettingsState> {
    private var state = SftpSettingsState()

    override fun getState(): SftpSettingsState = state

    override fun loadState(state: SftpSettingsState) {
        this.state = state
    }

    fun servers(): List<ServerProfile> = state.servers

    var withInnerClasses: Boolean
        get() = state.withInnerClasses
        set(value) {
            state.withInnerClasses = value
         }

    var uploadJavaClassFiles: Boolean
        get() = state.uploadJavaClassFiles
        set(value) {
            state.uploadJavaClassFiles = value
          }

    var newServerUseRsync: Boolean
        get() = state.newServerUseRsync
        set(value) {
            state.newServerUseRsync = value
          }

    fun save(profile: ServerProfile) {
        val index = state.servers.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            state.servers[index] = profile
        } else {
            state.servers.add(profile)
        }
    }

    fun remove(profileId: String) {
        state.servers.removeIf { it.id == profileId }
    }

    companion object {
        fun getInstance(): SftpSettings =
            ApplicationManager.getApplication().getService(SftpSettings::class.java)
    }
}

object PasswordStore {
    private fun attributes(profileId: String) =
        CredentialAttributes("org.kavo.uploader.sftp.$profileId")

    fun get(profileId: String): String? =
        PasswordSafe.instance.getPassword(attributes(profileId))

    fun set(profileId: String, username: String, password: String) {
        PasswordSafe.instance[attributes(profileId)] = Credentials(username, password)
    }

    fun remove(profileId: String) {
        PasswordSafe.instance[attributes(profileId)] = null
    }
}
