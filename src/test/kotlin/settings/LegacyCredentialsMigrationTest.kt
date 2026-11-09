package org.kavo.uploader.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers settings/LegacyCredentialsMigration.kt. Delete this file with it - see the checklist there. */
class LegacyCredentialsMigrationTest {
    @Test
    fun `a profile with a bare username gets an entry named after it`() {
        val state = SftpSettingsState(
            servers = mutableListOf(ServerProfile(name = "serve", username = "deploy")),
        )

        migrateLegacyCredentials(state)

        val profile = state.servers.single()
        assertTrue(profile.credentialsId.isNotBlank())
        assertEquals(listOf("deploy"), state.credentials.map { it.name })
        assertEquals("deploy", state.credentials.single().username)
        assertEquals(profile.credentialsId, state.credentials.single().id)
    }

    @Test
    fun `servers sharing a username share one entry`() {
        val state = SftpSettingsState(
            servers = mutableListOf(
                ServerProfile(name = "prod", username = "deploy"),
                ServerProfile(name = "staging", username = "deploy"),
                ServerProfile(name = "other", username = "release"),
            ),
        )

        migrateLegacyCredentials(state)

        assertEquals(listOf("deploy", "release"), state.credentials.map { it.name })
        assertEquals(
            state.credentials.single { it.name == "deploy" }.id,
            state.servers.filter { it.username == "deploy" }.map { it.credentialsId }.distinct().single(),
        )
        assertNotEquals(
            state.servers.single { it.name == "prod" }.credentialsId,
            state.servers.single { it.name == "other" }.credentialsId,
        )
    }

    @Test
    fun `a profile that already points at an entry is left alone`() {
        val state = SftpSettingsState(
            servers = mutableListOf(ServerProfile(name = "serve", username = "deploy", credentialsId = "entry")),
            credentials = mutableListOf(CredentialsProfile(id = "entry", name = "deploy", username = "deploy")),
        )

        migrateLegacyCredentials(state)

        assertEquals(listOf("entry"), state.servers.map { it.credentialsId })
        assertEquals(listOf("entry"), state.credentials.map { it.id })
    }

    @Test
    fun `the local profile and an entrys taken name are handled`() {
        val state = SftpSettingsState(
            servers = mutableListOf(
                ServerProfile(id = LOCAL_PROFILE_ID, name = "Local", type = ServerType.LOCAL),
                ServerProfile(name = "serve", username = "deploy"),
            ),
            credentials = mutableListOf(CredentialsProfile(id = "entry", name = "deploy", username = "someone")),
        )

        migrateLegacyCredentials(state)

        assertEquals("", state.servers.single { it.type == ServerType.LOCAL }.credentialsId)
        assertEquals(listOf("deploy", "deploy(1)"), state.credentials.map { it.name })
        assertEquals("deploy(1)", state.credentials.single { it.id == state.servers.single { it.name == "serve" }.credentialsId }.name)
    }
}
