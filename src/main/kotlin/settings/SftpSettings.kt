package org.kavo.uploader.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.util.*

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

// Folder path of a folder entered under `parent`, normalized: empty and repeated separators collapse.
fun nestedFolderPath(parent: String, name: String): String =
    (pathSegments(parent) + pathSegments(name)).joinToString("/")

fun validateServerActions(actions: List<ServerAction>): ServerActionValidationError? {
    val names = mutableSetOf<String>()
    actions.forEach { action ->
        if (action.name.isBlank()) return ServerActionValidationError.BLANK_NAME
        if (action.command.isBlank()) return ServerActionValidationError.BLANK_COMMAND
        if (!names.add(action.name)) return ServerActionValidationError.DUPLICATE_NAME
    }
    return null
}

/**
 * Reusable username, shared by any number of server profiles. Its password is never part of this
 * record: it is stored in the PasswordSafe under `id` - see `PasswordStore`.
 */
data class CredentialsProfile(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var username: String = "",
)

/**
 * A server dialog's credentials choice: the entry it picked, or a new username and password that either
 * become an entry (`storeAsEntity`) or stay with this one server. `password` is the typed secret, null
 * when the user left the field blank to keep the current one.
 */
data class CredentialSelection(
    var existingId: String? = null,
    var name: String = "",
    var username: String = "",
    var password: String? = null,
    var storeAsEntity: Boolean = true,
)

data class ServerProfile(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var folder: String = "",
    var host: String = "",
    var port: Int = 22,
    var username: String = "",
    // Reference to a CredentialsProfile: id only, never a secret. Empty means a profile saved before
    // credentials entities existed, whose secret still sits under its own profile id.
    var credentialsId: String = "",
    var useRsync: Boolean = false,
    var type: ServerType = ServerType.SFTP,
    var basePath: String = "",
    var mappings: MutableList<PathMapping> = mutableListOf(),
    // LEGACY ONLY - read by settings/LegacyMappingsMigration.kt so configs that kept fnmatch rows
    // separately survive an upgrade. Delete this field together with that file.
    var fnPatterns: MutableList<PathMapping> = mutableListOf(),
    var actions: MutableList<ServerAction> = mutableListOf(),
)

const val MENU_BUTTONS_CLIP_THRESHOLD = 0.5
const val MENU_BUTTONS_GAP = 4

data class SftpSettingsState(
    var servers: MutableList<ServerProfile> = mutableListOf(),
    var credentials: MutableList<CredentialsProfile> = mutableListOf(),
    var folders: MutableList<String> = mutableListOf(),
    var withInnerClasses: Boolean = true,
    var uploadJavaClassFiles: Boolean = true,
    var newServerUseRsync: Boolean = false,
    var showMenuAddFolderButton: Boolean = false,
    var wrapMenuButtons: Boolean = true,
    var newCredentialsStored: Boolean = true,
    var lastCredentialsId: String = "",
)

@Service(Service.Level.APP)
@State(name = "SftpUploaderSettings", storages = [Storage("sftpUploader.xml")])
class SftpSettings : PersistentStateComponent<SftpSettingsState> {
    private var state = SftpSettingsState()

    override fun getState(): SftpSettingsState = state

    override fun loadState(state: SftpSettingsState) {
        // Legacy config migration: see settings/LegacyMappingsMigration.kt. Delete this call together with
        // that file and the `fnPatterns` field in ServerProfile.
        migrateLegacyMappings(state)
        // Legacy credentials: see settings/LegacyCredentialsMigration.kt.
        migrateLegacyCredentials(state)
        this.state = state
    }

    fun servers(): List<ServerProfile> = state.servers

    fun local(): ServerProfile {
        val index = state.servers.indexOfFirst { it.type == ServerType.LOCAL }
        if (index >= 0) {
            state.servers[index].folder = ""
            return state.servers[index]
        }
        val profile = ServerProfile(
            id = LOCAL_PROFILE_ID,
            name = "Local",
            type = ServerType.LOCAL,
        )
        state.servers.add(profile)
        return profile
    }

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
        state.servers.removeIf { profile ->
            val inside = profile.folder == name || profile.folder.startsWith(prefix)
            if (inside && profile.type == ServerType.LOCAL) {
                profile.folder = ""
            }
            inside && profile.type != ServerType.LOCAL
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

    var showMenuAddFolderButton: Boolean
        get() = state.showMenuAddFolderButton
        set(value) {
            state.showMenuAddFolderButton = value
        }

    var wrapMenuButtons: Boolean
        get() = state.wrapMenuButtons
        set(value) {
            state.wrapMenuButtons = value
        }

    /** Whether new credentials typed in the server dialog default to being stored as a reusable entry. */
    var newCredentialsStored: Boolean
        get() = state.newCredentialsStored
        set(value) {
            state.newCredentialsStored = value
        }

    /** The entry a server dialog chose last, so the next new server starts with it. */
    var lastCredentialsId: String
        get() = state.lastCredentialsId
        set(value) {
            state.lastCredentialsId = value
        }

    fun save(profile: ServerProfile) {
        if (profile.type == ServerType.LOCAL) {
            profile.folder = ""
        }
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

    // ------------------------------------------------------------------
    // Credentials entities: a username several servers can share.
    // ------------------------------------------------------------------

    fun credentials(): List<CredentialsProfile> = state.credentials

    fun credential(credentialsId: String): CredentialsProfile? =
        state.credentials.firstOrNull { it.id == credentialsId }

    fun credentialByName(name: String): CredentialsProfile? =
        state.credentials.firstOrNull { it.name == name }

    /** Profiles that authenticate with these credentials; an empty id matches none. */
    fun profilesUsing(credentialsId: String): List<ServerProfile> =
        if (credentialsId.isEmpty()) emptyList()
        else state.servers.filter { it.credentialsId == credentialsId }

    /** Whether an entry already has a secret, on the entry itself or on a server that stored one under its own id. */
    fun hasPassword(credentialsId: String): Boolean =
        PasswordStore.getCredential(credentialsId) != null ||
            profilesUsing(credentialsId).any { PasswordStore.getOwn(it.id) != null }

    /**
     * Saves an entry from the credentials dialog: a blank id issues a new one, a known id updates that
     * entry in place. The username is copied onto every profile using the entry, because the profile is
     * what the server list and the upload commands read. The saved entry is returned, so the caller can
     * store a password against an id it did not have to invent.
     */
    fun saveCredential(credential: CredentialsProfile): CredentialsProfile {
        if (credential.id.isEmpty()) return addCredential(credential.name, credential.username)
        val index = state.credentials.indexOfFirst { it.id == credential.id }
        if (index < 0) return addCredential(credential.name, credential.username)
        if (state.credentials[index].username != credential.username) {
            profilesUsing(credential.id).forEach { it.username = credential.username }
        }
        state.credentials[index] = credential
        return credential
    }

    /** A new entry: its name starts as the username and is sequenced when that name is taken. */
    fun addCredential(name: String, username: String): CredentialsProfile =
        CredentialsProfile(
            id = UUID.randomUUID().toString(),
            name = uniqueCredentialName(if (name.isBlank()) username else name),
            username = username,
        ).also { state.credentials.add(it) }

    private fun uniqueCredentialName(name: String): String =
        if (state.credentials.none { it.name == name }) name
        else uniqueCloneName(name, state.credentials.map { it.name })

    /**
     * Removes an entry and lets go of the profiles that used it: their `credentialsId` is cleared while the
     * username they show stays, so a server dialog offers new credentials instead of pointing at an entry
     * that no longer exists.
     */
    fun forgetCredential(credentialsId: String) {
        profilesUsing(credentialsId).forEach { it.credentialsId = "" }
        state.credentials.removeIf { it.id == credentialsId }
    }

    /**
     * The entry the server dialog chose: the picked one, a new one when the username is to be stored as an
     * entry, or null when the credentials stay with this server alone.
     */
    fun resolveCredential(selection: CredentialSelection?): CredentialsProfile? {
        if (selection == null) return null
        selection.existingId?.let { credential(it) }?.let { return it }
        if (!selection.storeAsEntity) return null
        return addCredential(selection.name, selection.username)
    }

    companion object {
        fun getInstance(): SftpSettings =
            ApplicationManager.getApplication().getService(SftpSettings::class.java)
    }
}

object PasswordStore {
    // A credentials entity's secret, keyed by CredentialsProfile.id so every profile using that entity
    // authenticates with one stored password.
    private fun credentialAttributes(credentialsId: String) =
        CredentialAttributes("org.kavo.uploader.creds.$credentialsId")

    // A server that keeps its credentials to itself - the "Safe for others" box is off, or the config predates
    // credential entries - has its secret under the profile id instead of under an entry.
    private fun profileAttributes(profileId: String) =
        CredentialAttributes("org.kavo.uploader.sftp.$profileId")

    fun getCredential(credentialsId: String): String? =
        PasswordSafe.instance.getPassword(credentialAttributes(credentialsId))

    fun setCredential(credentialsId: String, username: String, password: String) {
        PasswordSafe.instance[credentialAttributes(credentialsId)] = Credentials(username, password)
    }

    fun removeCredential(credentialsId: String) {
        PasswordSafe.instance[credentialAttributes(credentialsId)] = null
    }

    fun getOwn(profileId: String): String? =
        PasswordSafe.instance.getPassword(profileAttributes(profileId))

    fun setOwn(profileId: String, username: String, password: String) {
        PasswordSafe.instance[profileAttributes(profileId)] = Credentials(username, password)
    }

    fun removeOwn(profileId: String) {
        PasswordSafe.instance[profileAttributes(profileId)] = null
    }

    /**
     * The secret a profile should authenticate with: the password of the credentials entry it points at,
     * falling back to the secret stored under the profile itself - its own when it keeps credentials to
     * itself, and the one saved before credential entries existed.
     */
    fun passwordOf(profile: ServerProfile): String? =
        when {
            profile.credentialsId.isEmpty() -> getOwn(profile.id)
            else -> getCredential(profile.credentialsId) ?: getOwn(profile.id)
        }

    fun passwordBytes(profile: ServerProfile): ByteArray? = passwordOf(profile)?.toByteArray()

    fun hasPassword(profile: ServerProfile): Boolean = passwordOf(profile) != null

    /** Stores a freshly typed password where the profile reads it back, retiring the profile's own entry. */
    fun setPassword(profile: ServerProfile, username: String, password: String) {
        if (profile.credentialsId.isEmpty()) {
            setOwn(profile.id, username, password)
            return
        }
        setCredential(profile.credentialsId, username, password)
        removeOwn(profile.id)
    }

    /**
     * Moves a profile's own stored password onto the entry it has started to use, so every server picking
     * that entry authenticates with it. Nothing to move when a password was just typed, or when the entry
     * already has one.
     */
    fun promoteOwnSecret(profile: ServerProfile) {
        if (profile.credentialsId.isEmpty() || getCredential(profile.credentialsId) != null) return
        val own = getOwn(profile.id) ?: return
        setCredential(profile.credentialsId, profile.username, own)
        removeOwn(profile.id)
    }

    /**
     * Gives a cloned profile the secret its source authenticates with: two profiles pointing at the same
     * entry share it, and a secret stored under the source alone is copied onto the clone.
     */
    fun storeCloneSecret(source: ServerProfile, clone: ServerProfile) {
        if (clone.credentialsId.isNotEmpty()) {
            if (getCredential(clone.credentialsId) != null) return
            val shared = getCredential(source.credentialsId) ?: getOwn(source.id) ?: return
            setCredential(clone.credentialsId, clone.username, shared)
            removeOwn(source.id)
            return
        }
        val own = getOwn(source.id) ?: return
        setOwn(clone.id, clone.username, own)
    }
}
