package org.kavo.uploader.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.kavo.uploader.settings.PathMapping
import java.nio.file.Paths

class PathMappingResolverTest {
    private val project = Paths.get("/workspace/project")

    @Test
    fun `preserves path below matching root`() {
        val result = PathMappingResolver.resolve(
            project,
            project.resolve("target/classes/com/example/Service.class"),
            listOf(PathMapping("target/classes", "/remote/APP-INF/classes")),
        )

        assertEquals("/remote/APP-INF/classes/com/example/Service.class", result)
    }

    @Test
    fun `longest matching prefix wins`() {
        val result = PathMappingResolver.resolve(
            project,
            project.resolve("target/classes/com/example/ui/View.class"),
            listOf(
                PathMapping("target/classes", "/remote/APP-INF/classes"),
                PathMapping("target/classes/com/example/ui", "/remote/WEB-INF/classes/com/example/ui"),
            ),
        )

        assertEquals("/remote/WEB-INF/classes/com/example/ui/View.class", result)
    }

    @Test
    fun `normalizes mapping separators`() {
        val result = PathMappingResolver.resolve(
            project,
            project.resolve("target/classes/example.sql"),
            listOf(PathMapping("""target\classes\""", "/remote/sql/")),
        )

        assertEquals("/remote/sql/example.sql", result)
    }

    @Test
    fun `does not match partial path segment`() {
        val result = PathMappingResolver.resolve(
            project,
            project.resolve("target/classes-old/example.txt"),
            listOf(PathMapping("target/classes", "/remote/classes")),
        )

        assertNull(result)
    }

    @Test
    fun `rejects files outside project`() {
        val result = PathMappingResolver.resolve(
            project,
            Paths.get("/workspace/other/example.txt"),
            listOf(PathMapping("", "/remote/project")),
        )

        assertNull(result)
    }

    @Test
    fun `returns null without matching mapping`() {
        val result = PathMappingResolver.resolve(
            project,
            project.resolve("README.md"),
            listOf(PathMapping("target/classes", "/remote/classes")),
        )

        assertNull(result)
    }
}
