package org.kavo.uploader.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerProfileTest {
    @Test
    fun `persistent profile contains no password field`() {
        val fieldNames = ServerProfile::class.java.declaredFields.map { it.name.lowercase() }

        assertFalse(fieldNames.any { it.contains("password") || it.contains("credential") })
    }

    @Test
    fun `actions preserve their configured order without credentials`() {
        val actions = mutableListOf(
            ServerAction(id = "restart", name = "Restart", command = "sudo systemctl restart app"),
            ServerAction(id = "status", name = "Status", command = "systemctl status app"),
        )
        val profile = ServerProfile(actions = actions)

        assertEquals(listOf("Restart", "Status"), profile.actions.map { it.name })
        val fieldNames = ServerAction::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(fieldNames.any { it.contains("password") || it.contains("credential") })
    }

    @Test
    fun `action validation rejects blank and duplicate values`() {
        assertEquals(
            ServerActionValidationError.BLANK_NAME,
            validateServerActions(listOf(ServerAction(name = " ", command = "uptime"))),
        )
        assertEquals(
            ServerActionValidationError.BLANK_COMMAND,
            validateServerActions(listOf(ServerAction(name = "Status", command = " "))),
        )
        assertEquals(
            ServerActionValidationError.DUPLICATE_NAME,
            validateServerActions(
                listOf(
                    ServerAction(name = "Status", command = "uptime"),
                    ServerAction(name = "Status", command = "whoami"),
                ),
            ),
        )
        assertNull(validateServerActions(listOf(ServerAction(name = "Status", command = "uptime"))))
    }
}
