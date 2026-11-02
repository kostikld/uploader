package org.kavo.uploader.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuButtonsLayoutTest {
    @Test
    fun `buttons that all fit stay on one row`() {
        assertEquals(listOf(listOf(0, 1, 2)), planMenuButtonRows(listOf(100, 100, 100), 400, 4, true))
    }

    @Test
    fun `a button that fits fully is not moved down`() {
        assertEquals(listOf(listOf(0, 1, 2)), planMenuButtonRows(listOf(100, 100, 100), 308, 4, true))
    }

    @Test
    fun `a button half of which would be hidden stays on the row`() {
        assertEquals(listOf(listOf(0, 1, 2)), planMenuButtonRows(listOf(100, 100, 100), 258, 4, true))
    }

    @Test
    fun `a button more than half of which would be hidden moves to a new row`() {
        assertEquals(listOf(listOf(0, 1), listOf(2)), planMenuButtonRows(listOf(100, 100, 100), 257, 4, true))
    }

    @Test
    fun `wrapping off keeps a single row however narrow the panel is`() {
        assertEquals(listOf(listOf(0, 1, 2)), planMenuButtonRows(listOf(100, 100, 100), 20, 4, false))
    }

    @Test
    fun `gaps take part in the used width`() {
        assertEquals(listOf(listOf(0, 1)), planMenuButtonRows(listOf(100, 100), 204, 4, true))
        assertEquals(listOf(listOf(0), listOf(1)), planMenuButtonRows(listOf(100, 100), 103, 4, true))
    }

    @Test
    fun `a button wider than the row is still placed once`() {
        assertEquals(listOf(listOf(0)), planMenuButtonRows(listOf(300), 100, 4, true))
        assertEquals(listOf(listOf(0), listOf(1, 2)), planMenuButtonRows(listOf(300, 100, 100), 210, 4, true))
    }

    @Test
    fun `unknown panel width keeps a single row`() {
        assertEquals(listOf(listOf(0, 1)), planMenuButtonRows(listOf(100, 100), 0, 4, true))
        assertTrue(planMenuButtonRows(listOf(), 100, 4, true).isEmpty())
    }

    @Test
    fun `menu settings default to hidden add folder button and wrapping on`() {
        val settings = SftpSettings()
        assertFalse(settings.showMenuAddFolderButton)
        assertTrue(settings.wrapMenuButtons)
        assertFalse(SftpSettingsState().showMenuAddFolderButton)
        assertTrue(SftpSettingsState().wrapMenuButtons)
    }
}
