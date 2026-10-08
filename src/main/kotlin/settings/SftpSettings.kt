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

enum class ServerType {
    SFTP,
    LOCAL,
}

const val LOCAL_PROFILE_ID = "local"

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

fun pathSegments(path: String): List<String> =
    path.split('/').filter { it.isNotEmpty() }

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
    var folder: String = "",
    var host: String = "",
    var port: Int = 22,
    var username: String = "",
    var useRsync: Boolean = false,
    var type: ServerType = ServerType.SFTP,
    var basePath: String = "",
    var mappings: MutableList<PathMapping> = mutableListOf(),
    var actions: MutableList<ServerAction> = mutableListOf(),
)

data class SftpSettingsState(
    var servers: MutableList<ServerProfile> = mutableListOf(),
    var folders: MutableList<String> = mutableListOf(),
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

    fun local(): ServerProfile {
        val index = state.servers.indexOfFirst { it.type == ServerType.LOCAL }
        if (index >= 0) return state.servers[index]
        val profile = ServerProfile(
            id = LOCAL_PROFILE_ID,
            name = "Local",
            type = ServerType.LOCAL,
        )
        state.servers.add(profile)
        return profile
    }

    fun remoteServers(): List<ServerProfile> =
        state.servers.filter { it.type == ServerType.SFTP }

    fun localServers(): List<ServerProfile> =
        state.servers.filter { it.type == ServerType.LOCAL }

    fun folders(): List<String> =
        (state.folders + state.servers.map { it.folder })
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

    fun addFolder(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || state.folders.any { it == trimmed }) return false
        state.folders.add(trimmed)
        return true
    }

    fun renameFolder(from: String, to: String) {
        val oldPrefix = "$from/"
        fun migrate(old: String): String =
            when {
                old == from -> to
                old.startsWith(oldPrefix) -> "$to/" + old.removePrefix(oldPrefix)
                else -> old
            }
        state.folders.replaceAll { migrate(it) }
        state.servers.forEach { profile ->
            if (profile.folder.isNotEmpty()) {
                profile.folder = migrate(profile.folder)
            }
        }
    }

    fun deleteFolder(name: String) {
        val prefix = "$name/"
        state.folders.removeIf { it == name || it.startsWith(prefix) }
        state.servers.forEach { profile ->
            when {
                profile.folder == name -> profile.folder = ""
                profile.folder.startsWith(prefix) -> profile.folder =
                    profile.folder.removePrefix(prefix)
            }
        }
    }

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
