package org.kavo.uploader.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandExecutionTest {
    @Test
    fun `formats elapsed time as minutes and seconds`() {
        assertEquals("00:00", formatElapsedTime(0))
        assertEquals("00:01", formatElapsedTime(1_999))
        assertEquals("01:01", formatElapsedTime(61_000))
    }

    @Test
    fun `only zero exit status is successful`() {
        assertTrue(isSuccessfulCommandExit(0))
        assertFalse(isSuccessfulCommandExit(1))
        assertFalse(isSuccessfulCommandExit(-1))
    }
}