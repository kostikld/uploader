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

    fun credentials(project: Project?): List<CredentialsProfile> =
        SftpSettings.getInstance().credentials()

    fun saveCredential(project: Project?, credential: CredentialsProfile): CredentialsProfile =
        SftpSettings.getInstance().saveCredential(credential)

    fun forgetCredential(project: Project?, credentialsId: String) {
        SftpSettings.getInstance().forgetCredential(credentialsId)
    }

    fun profilesUsingCredentials(project: Project?, credentialsId: String): List<ServerProfile> =
        SftpSettings.getInstance().profilesUsing(credentialsId)

    fun resolveCredential(project: Project?, selection: CredentialSelection?): CredentialsProfile? =
        SftpSettings.getInstance().resolveCredential(selection)

    fun facilities(project: Project?): List<Facility> =
        SftpSettings.getInstance().facilities()

    fun saveFacility(project: Project?, facility: Facility) {
        SftpSettings.getInstance().saveFacility(facility)
    }

    fun removeFacility(project: Project?, facilityId: String) {
        SftpSettings.getInstance().removeFacility(facilityId)
    }

    fun profilesUsingFacility(project: Project?, facilityId: String): List<ServerProfile> =
        SftpSettings.getInstance().profilesUsingFacility(facilityId)

    fun addFolder(project: Project?, name: String): Boolean =
        SftpSettings.getInstance().addFolder(name)

    fun renameFolder(project: Project?, from: String, to: String) {
        SftpSettings.getInstance().renameFolder(from, to)
    }

    fun deleteFolder(project: Project?, folder: String) {
        SftpSettings.getInstance().deleteFolder(folder)
    }

    fun showMenuAddFolderButton(project: Project?): Boolean =
        SftpSettings.getInstance().showMenuAddFolderButton

    fun wrapMenuButtons(project: Project?): Boolean =
        SftpSettings.getInstance().wrapMenuButtons
}
