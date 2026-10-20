package org.kavo.uploader.settings

import com.intellij.openapi.project.Project

object SettingsAccess {
    fun allServers(project: Project?): List<ServerProfile> =
        SftpSettings.getInstance().servers()

    fun folders(project: Project?): List<String> =
        SftpSettings.getInstance().folders()

    fun local(project: Project?): ServerProfile =
        SftpSettings.getInstance().local()

    fun save(project: Project?, profile: ServerProfile) {
        SftpSettings.getInstance().save(profile)
    }

    fun remove(project: Project?, profileId: String) {
        SftpSettings.getInstance().remove(profileId)
    }

    fun addFolder(project: Project?, name: String): Boolean =
        SftpSettings.getInstance().addFolder(name)

    fun renameFolder(project: Project?, from: String, to: String) {
        SftpSettings.getInstance().renameFolder(from, to)
    }

    fun deleteFolder(project: Project?, folder: String) {
        SftpSettings.getInstance().deleteFolder(folder)
    }
}
