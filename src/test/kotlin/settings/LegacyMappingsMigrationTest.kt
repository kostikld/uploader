package org.kavo.uploader.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers settings/LegacyMappingsMigration.kt. Delete this file with it - see the checklist there. */
class LegacyMappingsMigrationTest {
    @Test
    fun `legacy fnmatch rows move into the mappings above the direct ones`() {
        val state = SftpSettingsState(
            servers = mutableListOf(
                ServerProfile(
                    name = "serve",
                    mappings = mutableListOf(PathMapping("src", "/remote/src")),
                    fnPatterns = mutableListOf(
                        PathMapping("docs/**.html", "/remote/docs"),
                        PathMapping("**/*.js", "/remote/js"),
                    ),
                ),
            ),
        )

        migrateLegacyMappings(state)

        val profile = state.servers.single()
        assertEquals(listOf("docs/**.html", "**/*.js", "src"), profile.mappings.map { it.localPath })
        assertTrue(profile.fnPatterns.isEmpty())
    }

    @Test
    fun `a profile saved with one mapping list is left unchanged`() {
        val state = SftpSettingsState(
            servers = mutableListOf(
                ServerProfile(
                    name = "serve",
                    mappings = mutableListOf(
                        PathMapping("**/*.js", "/remote/js"),
                        PathMapping("src", "/remote/src"),
                    ),
                ),
            ),
        )

        migrateLegacyMappings(state)

        val profile = state.servers.single()
        assertEquals(listOf("**/*.js", "src"), profile.mappings.map { it.localPath })
        assertTrue(profile.fnPatterns.isEmpty())
    }

    @Test
    fun `profiles without legacy rows are untouched`() {
        val state = SftpSettingsState(servers = mutableListOf(ServerProfile(name = "serve")))

        migrateLegacyMappings(state)

        assertTrue(state.servers.single().mappings.isEmpty())
        assertTrue(state.servers.single().fnPatterns.isEmpty())
    }
}
