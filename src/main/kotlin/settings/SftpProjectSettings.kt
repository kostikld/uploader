package org.kavo.uploader.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import java.util.UUID

data class SftpProjectSettingsState(
    var servers: MutableList<ServerProfile> = mutableListOf(),
    var folders: MutableList<String> = mutableListOf(),
)

@Service(Service.Level.PROJECT)
@State(name = "SftpUploaderProjectSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class SftpProjectSettings : PersistentStateComponent<SftpProjectSettingsState> {
    private var state = SftpProjectSettingsState()

    override fun getState(): SftpProjectSettingsState = state

    override fun loadState(state: SftpProjectSettingsState) {
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
            scope = ProfileScope.PROJECT,
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
        state.servers.forEach { profile ->
            when {
                profile.folder == name -> profile.folder = ""
                profile.folder.startsWith(prefix) -> profile.folder =
                    profile.folder.removePrefix(prefix)
            }
        }
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
        fun getInstance(project: Project): SftpProjectSettings =
            project.getService(SftpProjectSettings::class.java)
    }
}
