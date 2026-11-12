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

    @Test
    fun `direct mapping wins over a fnmatch pattern`() {
        val result = PathMappingResolver.resolve(
            project,
            project.resolve("target/classes/example.txt"),
            listOf(
                PathMapping("target/**", "/remote/fallback"),
                PathMapping("target/classes", "/remote/classes"),
            ),
        )

        assertEquals("/remote/classes/example.txt", result)
    }

    @Test
    fun `fnmatch pattern resolves when no direct mapping matches`() {
        val result = PathMappingResolver.resolve(
            project,
            project.resolve("src/main/web/app.js"),
            listOf(
                PathMapping("target/classes", "/remote/classes"),
                PathMapping("src/**", "/remote/web"),
            ),
        )

        assertEquals("/remote/web/main/web/app.js", result)
    }

    @Test
    fun `keeps the path below the pattern literal prefix`() {
        val result = PathMappingResolver.resolve(
            project,
            project.resolve("target/classes/com/example/ui/View.js"),
            listOf(PathMapping("target/classes/**/ui/*.js", "/remote/WEB-INF/classes")),
        )

        assertEquals("/remote/WEB-INF/classes/com/example/ui/View.js", result)
    }

    @Test
    fun `a pattern ending in a bare globstar keeps only the globstar remainder`() {
        val result = PathMappingResolver.resolve(
            project,
            project.resolve("module/sub-module/target/classes/com/example/Service.class"),
            listOf(PathMapping("module/*/target/classes/**", "/remote/APP-INF/classes")),
        )

        assertEquals("/remote/APP-INF/classes/com/example/Service.class", result)
    }

    @Test
    fun `the trailing globstar remainder starts below the last literal occurrence`() {
        val result = PathMappingResolver.resolve(
            project,
            project.resolve("a/x/target/classes/com/target/classes/y.class"),
            listOf(PathMapping("a/*/target/classes/**", "/remote/classes")),
        )

        assertEquals("/remote/classes/y.class", result)
    }

    @Test
    fun `a whole-project globstar keeps the full path`() {
        val result = PathMappingResolver.resolve(
            project,
            project.resolve("any/where/file.txt"),
            listOf(PathMapping("**", "/remote/all")),
        )

        assertEquals("/remote/all/any/where/file.txt", result)
    }

    @Test
    fun `a pattern ending in a bare globstar resolves a local path`() {
        val result = PathMappingResolver.resolveLocal(
            project,
            project.resolve("module/app/target/classes/com/example/Service.class"),
            listOf(PathMapping("module/*/target/classes/**", "/remote/APP-INF/classes")),
            "/local",
        )

        assertEquals("/local/remote/APP-INF/classes/com/example/Service.class", result)
    }

    @Test
    fun `most concrete fnmatch pattern wins`() {
        val result = PathMappingResolver.resolve(
            project,
            project.resolve("src/web/app.js"),
            listOf(
                PathMapping("**/*.js", "/remote/any"),
                PathMapping("src/web/**.js", "/remote/web"),
            ),
        )

        assertEquals("/remote/web/app.js", result)
    }

    @Test
    fun `configured order breaks equal fnmatch concreteness`() {
        val result = PathMappingResolver.resolve(
            project,
            project.resolve("src/a.js"),
            listOf(
                PathMapping("src/*.js", "/remote/first"),
                PathMapping("src/?.js", "/remote/second"),
            ),
        )

        assertEquals("/remote/first/a.js", result)
    }

    @Test
    fun `pattern rows never match by path prefix`() {
        val result = PathMappingResolver.resolve(
            project,
            project.resolve("src-old/main/app.js"),
            listOf(PathMapping("src/**", "/remote/web")),
        )

        assertNull(result)
    }

    @Test
    fun `a local path with brackets is read as a pattern`() {
        val result = PathMappingResolver.resolve(
            project,
            project.resolve("src/[draft]/a.md"),
            listOf(PathMapping("src/[draft]", "/remote/draft")),
        )

        assertNull(result)
    }

    @Test
    fun `fnmatch pattern resolves a local path`() {
        val result = PathMappingResolver.resolveLocal(
            project,
            project.resolve("docs/guide.html"),
            listOf(PathMapping("docs/**.html", "/docs")),
            "/local",
        )

        assertEquals("/local/docs/guide.html", result)
    }
}
