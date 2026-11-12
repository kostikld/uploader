package org.kavo.uploader.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FacilityInheritanceTest {
    private val facilityMappings = listOf(
        PathMapping("src/main/resources", "/opt/app/resources"),
        PathMapping("module/*/target/classes/*.class", "/opt/app/classes"),
    )
    private val facilityActions = listOf(
        ServerAction(id = "f1", name = "deploy", command = "systemctl restart app"),
        ServerAction(id = "f2", name = "logs", command = "journalctl -u app"),
    )

    @Test
    fun `the server keeps its own rows when the facility has none`() {
        val own = listOf(PathMapping("src", "/srv"))
        assertEquals(own, mergeMappings(emptyList(), own))
    }

    @Test
    fun `the facility rows are used as they are when the server has none`() {
        assertEquals(facilityMappings, mergeMappings(facilityMappings, emptyList()))
        assertEquals(facilityActions, mergeActions(facilityActions, emptyList()))
    }

    @Test
    fun `a server mapping replaces the facility row for the same local path`() {
        val merged = mergeMappings(
            facilityMappings,
            listOf(PathMapping("src/main/resources", "/opt/other")),
        )
        assertEquals(
            listOf(
                PathMapping("module/*/target/classes/*.class", "/opt/app/classes"),
                PathMapping("src/main/resources", "/opt/other"),
            ),
            merged,
        )
    }

    @Test
    fun `local paths match as overrides after normalization`() {
        // A pattern row is replaced the same way a direct path is, and path spelling differences count.
        val merged = mergeMappings(
            facilityMappings,
            listOf(PathMapping("./module/*/target/classes/*.class/", "/opt/own")),
        )
        assertEquals(
            listOf(
                PathMapping("src/main/resources", "/opt/app/resources"),
                PathMapping("./module/*/target/classes/*.class/", "/opt/own"),
            ),
            merged,
        )
    }

    @Test
    fun `facility rows keep their order and the server rows come last`() {
        val merged = mergeMappings(
            facilityMappings,
            listOf(PathMapping("extra", "/opt/extra"), PathMapping("src/main/resources", "/opt/other")),
        )
        assertEquals(
            listOf(
                PathMapping("module/*/target/classes/*.class", "/opt/app/classes"),
                PathMapping("extra", "/opt/extra"),
                PathMapping("src/main/resources", "/opt/other"),
            ),
            merged,
        )
    }

    @Test
    fun `a server action replaces the facility action with the same name`() {
        val merged = mergeActions(
            facilityActions,
            listOf(ServerAction(id = "s1", name = " deploy ", command = "systemctl restart mine")),
        )
        assertEquals(
            listOf(
                ServerAction(id = "f2", name = "logs", command = "journalctl -u app"),
                ServerAction(id = "s1", name = " deploy ", command = "systemctl restart mine"),
            ),
            merged,
        )
    }

    @Test
    fun `a facility with no entries leaves the server untouched`() {
        val ownActions = listOf(ServerAction(id = "s1", name = "deploy", command = "go"))
        assertEquals(ownActions, mergeActions(emptyList(), ownActions))
    }

    @Test
    fun `facility keys for highlighting are normalized paths and trimmed names`() {
        val facility = Facility(
            name = "Payment Service",
            mappings = mutableListOf(PathMapping("/src/main//", "/opt/app")),
            actions = mutableListOf(ServerAction(id = "f1", name = " deploy ", command = "go")),
        )
        assertTrue(PathMapping("x", "").let { "src/main" in facilityMappingKeys(facility) })
        assertEquals(setOf("deploy"), facilityActionNames(facility))
    }

    @Test
    fun `a missing facility supplies no keys to highlight`() {
        assertTrue(facilityMappingKeys(null).isEmpty())
        assertTrue(facilityActionNames(null).isEmpty())
    }
}
