package org.kavo.uploader.settings

import com.intellij.openapi.project.Project

object SettingsAccess {
    fun allServers(project: Project?): List<ServerProfile> =
        if (project == null) {
            SftpSettings.getInstance().servers()
        } else {
            SftpSettings.getInstance().servers() + SftpProjectSettings.getInstance(project).servers()
        }

    fun servers(scope: ProfileScope, project: Project?): List<ServerProfile> =
        when (scope) {
            ProfileScope.GLOBAL -> SftpSettings.getInstance().servers()
            ProfileScope.PROJECT -> project?.let { SftpProjectSettings.getInstance(it).servers() } ?: emptyList()
        }

    fun folders(scope: ProfileScope, project: Project?): List<String> =
        when (scope) {
            ProfileScope.GLOBAL -> SftpSettings.getInstance().folders()
            ProfileScope.PROJECT -> project?.let { SftpProjectSettings.getInstance(it).folders() } ?: emptyList()
        }

    fun local(scope: ProfileScope, project: Project?): ServerProfile =
        when (scope) {
            ProfileScope.GLOBAL -> SftpSettings.getInstance().local()
            ProfileScope.PROJECT -> project?.let { SftpProjectSettings.getInstance(it).local() }
                ?: SftpSettings.getInstance().local()
        }

    fun save(project: Project?, profile: ServerProfile) {
        when (profile.scope) {
            ProfileScope.GLOBAL -> SftpSettings.getInstance().save(profile)
            ProfileScope.PROJECT -> when (project) {
                null -> SftpSettings.getInstance().save(profile)
                else -> SftpProjectSettings.getInstance(project).save(profile)
            }
        }
    }

    fun remove(project: Project?, profileId: String) {
        SftpSettings.getInstance().remove(profileId)
        project?.let { SftpProjectSettings.getInstance(it).remove(profileId) }
    }

    fun addFolder(project: Project?, scope: ProfileScope, name: String): Boolean =
        when (scope) {
            ProfileScope.GLOBAL -> SftpSettings.getInstance().addFolder(name)
            ProfileScope.PROJECT -> when (project) {
                null -> false
                else -> SftpProjectSettings.getInstance(project).addFolder(name)
            }
        }

    fun renameFolder(project: Project?, scope: ProfileScope, from: String, to: String) {
        when (scope) {
            ProfileScope.GLOBAL -> SftpSettings.getInstance().renameFolder(from, to)
            ProfileScope.PROJECT -> project?.let { SftpProjectSettings.getInstance(it).renameFolder(from, to) }
        }
    }

    fun deleteFolder(project: Project?, scope: ProfileScope, folder: String) {
        when (scope) {
            ProfileScope.GLOBAL -> SftpSettings.getInstance().deleteFolder(folder)
            ProfileScope.PROJECT -> project?.let { SftpProjectSettings.getInstance(it).deleteFolder(folder) }
        }
    }
}
