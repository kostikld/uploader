package org.kavo.uploader.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SftpSettingsFolderTest {
    private fun profile(name: String, folder: String = "") =
        ServerProfile(name = name, folder = folder)

    @Test
    fun `new servers default to no folder`() {
        assertEquals("", ServerProfile(name = "x").folder)
    }

    @Test
    fun `path segments split folder paths and drop blanks`() {
        assertEquals(listOf("a", "b", "c"), pathSegments("a/b/c"))
        assertEquals(listOf("a", "b"), pathSegments("/a//b/"))
        assertTrue(pathSegments("").isEmpty())
        assertTrue(pathSegments("///").isEmpty())
    }

    @Test
    fun `folders union explicit and derived and deduplicate`() {
        val settings = SftpSettings()
        assertTrue(settings.addFolder("staging"))
        assertTrue(settings.addFolder("prod/nested"))
        assertFalse(settings.addFolder("staging"))
        assertFalse(settings.addFolder("prod/nested"))

        assertEquals(listOf("prod/nested", "staging"), settings.folders())

        settings.save(profile("alpha", "staging"))
        settings.save(profile("beta", "prod/nested"))
        settings.save(profile("ungrouped"))

        assertEquals(listOf("prod/nested", "staging"), settings.folders())
    }

    @Test
    fun `rename folder migrates children and subfolder prefixes`() {
        val settings = SftpSettings()
        settings.addFolder("prod/dev")
        settings.save(profile("alpha", "prod"))
        settings.save(profile("beta", "prod/dev"))
        settings.save(profile("root", ""))

        settings.renameFolder("prod", "live")

        assertEquals("live", settings.servers().find { it.name == "alpha" }?.folder)
        assertEquals("live/dev", settings.servers().find { it.name == "beta" }?.folder)
        assertEquals("", settings.servers().find { it.name == "root" }?.folder)
        assertEquals(listOf("live", "live/dev"), settings.folders())
    }

    @Test
    fun `delete folder ungroups children and prunes subfolders`() {
        val settings = SftpSettings()
        settings.addFolder("prod/dev")
        settings.save(profile("alpha", "prod"))
        settings.save(profile("beta", "prod/dev"))
        settings.save(profile("root", ""))

        settings.deleteFolder("prod")

        assertEquals("", settings.servers().find { it.name == "alpha" }?.folder)
        assertEquals("dev", settings.servers().find { it.name == "beta" }?.folder)
        assertEquals("", settings.servers().find { it.name == "root" }?.folder)
        assertFalse(settings.folders().contains("prod"))
    }
}
