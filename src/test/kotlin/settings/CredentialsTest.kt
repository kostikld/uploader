package org.kavo.uploader.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialsTest {
    private fun profile(name: String, credentialsId: String = "") =
        ServerProfile(name = name, username = "deploy", credentialsId = credentialsId)

    @Test
    fun `a new entry is named after its username`() {
        val settings = SftpSettings()

        val entry = settings.addCredential("", "deploy")

        assertEquals("deploy", entry.name)
        assertEquals("deploy", entry.username)
        assertTrue(entry.id.isNotBlank())
    }

    @Test
    fun `an entry name already used gets the next sequence`() {
        val settings = SftpSettings()
        settings.addCredential("deploy", "first")

        assertEquals("deploy(1)", settings.addCredential("deploy", "second").name)
        assertEquals("deploy(2)", settings.addCredential("deploy", "third").name)
    }

    @Test
    fun `profiles point at an entry by id and are found through it`() {
        val settings = SftpSettings()
        val entry = settings.addCredential("", "deploy")
        settings.save(profile("serve", entry.id))
        settings.save(profile("other", entry.id))
        settings.save(profile("own"))

        assertEquals(listOf("serve", "other"), settings.profilesUsing(entry.id).map { it.name })
        assertTrue(settings.profilesUsing("").isEmpty())
        assertEquals(entry.id, settings.credentialByName("deploy")?.id)
        assertNull(settings.credentialByName("missing"))
    }

    @Test
    fun `editing an entrys username follows onto the servers using it`() {
        val settings = SftpSettings()
        val entry = settings.addCredential("", "deploy")
        settings.save(profile("serve", entry.id))

        settings.saveCredential(CredentialsProfile(id = entry.id, name = entry.name, username = "release"))

        assertEquals("release", settings.credential(entry.id)?.username)
        assertEquals("release", settings.servers().single().username)
        assertEquals(entry.id, settings.servers().single().credentialsId)
    }

    @Test
    fun `saving an entry with a known id updates it and keeps its users`() {
        val settings = SftpSettings()
        val entry = settings.addCredential("", "deploy")
        settings.save(profile("serve", entry.id))

        val saved = settings.saveCredential(
            CredentialsProfile(id = entry.id, name = "production", username = "release")
        )

        assertEquals(entry.id, saved.id)
        assertEquals(listOf("production"), settings.credentials().map { it.name })
        assertEquals("release", settings.credential(entry.id)?.username)
        assertEquals(listOf("serve"), settings.profilesUsing(entry.id).map { it.name })
        assertEquals("release", settings.servers().single().username)
    }

    @Test
    fun `saving an entry without an id issues one, and removing drops it`() {
        val settings = SftpSettings()

        val created = settings.saveCredential(CredentialsProfile(name = "added", username = "third"))
        assertTrue(created.id.isNotBlank())
        assertEquals(listOf("added"), settings.credentials().map { it.name })

        settings.forgetCredential(created.id)

        assertTrue(settings.credentials().isEmpty())
    }

    @Test
    fun `forgetting an entry lets go of the servers that used it, keeping their username`() {
        val settings = SftpSettings()
        val entry = settings.addCredential("", "deploy")
        settings.save(profile("serve", entry.id))
        settings.save(profile("other", entry.id))

        settings.forgetCredential(entry.id)

        assertTrue(settings.credentials().isEmpty())
        assertEquals(listOf(""), settings.servers().map { it.credentialsId }.distinct())
        assertEquals(listOf("deploy"), settings.servers().map { it.username }.distinct())
    }

    @Test
    fun `a choice not to store keeps the credentials with the server alone`() {
        val settings = SftpSettings()

        val resolved = settings.resolveCredential(
            CredentialSelection(name = "", username = "deploy", storeAsEntity = false)
        )

        assertNull(resolved)
        assertTrue(settings.credentials().isEmpty())
    }

    @Test
    fun `a chosen entry is reused and a new choice creates one`() {
        val settings = SftpSettings()
        val entry = settings.addCredential("", "deploy")

        val reused = settings.resolveCredential(
            CredentialSelection(existingId = entry.id, name = "ignored", username = "ignored")
        )
        val created = settings.resolveCredential(
            CredentialSelection(existingId = null, name = "", username = "release")
        )

        assertEquals(entry.id, reused?.id)
        assertEquals("deploy", reused?.username)
        assertEquals("release", created?.name)
        assertNotEquals(entry.id, created?.id)
        assertNull(settings.resolveCredential(null))
    }
}
