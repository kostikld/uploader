package org.kavo.uploader.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloneFactoryTest {
    @Test
    fun `clone name numbers the copy`() {
        assertEquals("serve(1)", uniqueCloneName("serve", listOf("serve")))
        assertEquals("serve(2)", uniqueCloneName("serve", listOf("serve", "serve(1)")))
        assertEquals("serve(3)", uniqueCloneName("serve", listOf("serve(2)")))
    }

    @Test
    fun `cloning a named copy continues the same family`() {
        assertEquals("serve(2)", uniqueCloneName("serve(1)", listOf("serve", "serve(1)")))
    }

    @Test
    fun `names that are not part of the family keep their own sequence`() {
        assertEquals("serve(2)", uniqueCloneName("serve(1)", listOf("prod", "serve(1)(1)")))
        assertEquals("serve(1x)(1)", uniqueCloneName("serve(1x)", listOf("serve(1x)")))
        assertEquals("(1)(1)", uniqueCloneName("(1)", listOf("(1)")))
    }

    @Test
    fun `profile clone keeps the configured connection and gets a new id`() {
        val source = ServerProfile(
            id = "source-id",
            name = "serve",
            folder = "staging",
            host = "example.com",
            port = 4242,
            username = "deploy",
            useRsync = true,
            basePath = "",
            mappings = mutableListOf(PathMapping("src", "/remote/src")),
        )

        val clone = cloneProfile(source, listOf(source.name))

        assertEquals("serve(1)", clone.name)
        assertNotEquals(source.id, clone.id)
        assertEquals("staging", clone.folder)
        assertEquals("example.com", clone.host)
        assertEquals(4242, clone.port)
        assertEquals("deploy", clone.username)
        assertTrue(clone.useRsync)
        assertEquals(listOf("/remote/src"), clone.mappings.map { it.remotePath })
    }

    @Test
    fun `profile clone does not share its entries with the source`() {
        val source = ServerProfile(
            mappings = mutableListOf(PathMapping("src", "/remote/src")),
            fnPatterns = mutableListOf(PathMapping("docs/**", "/remote/docs")),
            actions = mutableListOf(ServerAction(id = "restart", name = "Restart", command = "systemctl restart app")),
        )

        val clone = cloneProfile(source, listOf(source.name))
        clone.mappings.first().localPath = "target"
        clone.fnPatterns.first().remotePath = "/other/docs"
        clone.actions.first().command = "uptime"

        assertEquals(listOf("src"), source.mappings.map { it.localPath })
        assertEquals(listOf("/remote/docs"), source.fnPatterns.map { it.remotePath })
        assertEquals(listOf("systemctl restart app"), source.actions.map { it.command })
    }

    @Test
    fun `profile clone keeps action names but issues new action ids`() {
        val source = ServerProfile(
            name = "serve",
            actions = mutableListOf(
                ServerAction(id = "restart", name = "Restart", command = "systemctl restart app"),
                ServerAction(id = "status", name = "Status", command = "systemctl status app"),
            ),
        )

        val clone = cloneProfile(source, listOf(source.name))

        assertEquals(listOf("Restart", "Status"), clone.actions.map { it.name })
        assertTrue(clone.actions.none { it.id == "restart" || it.id == "status" })
        assertEquals(clone.actions.map { it.id }.toSet().size, clone.actions.size)
    }

    @Test
    fun `cloned action rows are named uniquely inside the profile`() {
        val actions = listOf(
            ServerAction(id = "1", name = "Status", command = "uptime"),
            ServerAction(id = "2", name = "Deploy", command = "rsync"),
        )

        val cloned = cloneActions(actions, actions.map { it.name })

        assertEquals(listOf("Status(1)", "Deploy(1)"), cloned.map { it.name })
        assertTrue(cloned.none { it.id == "1" || it.id == "2" })
    }

    @Test
    fun `cloned action rows continue the names already used`() {
        val actions = listOf(ServerAction(id = "1", name = "Status", command = "uptime"))

        val cloned = cloneActions(actions, listOf("Status", "Status(2)"))

        assertEquals(listOf("Status(3)"), cloned.map { it.name })
    }
}
