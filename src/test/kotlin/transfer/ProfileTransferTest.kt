package org.kavo.uploader.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kavo.uploader.settings.PathMapping
import org.kavo.uploader.settings.ServerAction

class ProfileTransferTest {
    @Test
    fun `encodes mappings one per line`() {
        val text = ProfileTransfer.encodeMappings(
            listOf(
                PathMapping("target/classes", "/remote/APP-INF/classes"),
                PathMapping("src", "/remote/src"),
             )
         )

        assertEquals("target/classes -> /remote/APP-INF/classes\nsrc -> /remote/src", text)
     }

    @Test
    fun `decodes mappings with blank lines ignored`() {
        val mappings = ProfileTransfer.decodeMappings(
            "target/classes -> /remote/APP-INF/classes\n\n  src -> /remote/src  \n"
         )

        assertEquals(
            mappings,
            listOf(PathMapping("target/classes", "/remote/APP-INF/classes"), PathMapping("src", "/remote/src"))
        )
     }

    @Test
    fun `decodes mapping line without remote path as blank remote`() {
        val mappings = ProfileTransfer.decodeMappings("target/classes\n")

        assertEquals(mappings, listOf(PathMapping("target/classes", "")))
     }

    @Test
    fun `mapping round trip preserves entries`() {
        val mappings = listOf(
            PathMapping("target/classes", "/remote/APP-INF/classes"),
            PathMapping("src/main", "/remote/src"),
         )

        assertEquals(mappings, ProfileTransfer.decodeMappings(ProfileTransfer.encodeMappings(mappings)))
     }

    @Test
    fun `encodes and decodes actions with fresh ids`() {
        val actions = listOf(
            ServerAction(id = "old-1", name = "Restart", command = "sudo systemctl restart app"),
            ServerAction(id = "old-2", name = "Status", command = "systemctl status app"),
         )

        val decoded = ProfileTransfer.decodeActions(ProfileTransfer.encodeActions(actions))

        assertEquals(listOf("Restart", "Status"), decoded.map { it.name })
        assertEquals(listOf("sudo systemctl restart app", "systemctl status app"), decoded.map { it.command })
        assertTrue(decoded.none { it.id == "old-1" || it.id == "old-2" })
        assertTrue(decoded.all { it.id.isNotBlank() })
        assertTrue(decoded.map { it.id }.toSet().size == decoded.size)
     }

    @Test
    fun `decodes actions ignoring blank and incomplete lines`() {
        val actions = ProfileTransfer.decodeActions(
            "Restart -> sudo systemctl restart app\n\nName-only\n -> blank command\n"
         )

        assertEquals(listOf("Restart"), actions.map { it.name })
     }
}
