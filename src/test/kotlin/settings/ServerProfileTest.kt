package org.kavo.uploader.settings

import org.junit.Assert.assertFalse
import org.junit.Test

class ServerProfileTest {
    @Test
    fun `persistent profile contains no password field`() {
        val fieldNames = ServerProfile::class.java.declaredFields.map { it.name.lowercase() }

        assertFalse(fieldNames.any { it.contains("password") || it.contains("credential") })
    }
}
