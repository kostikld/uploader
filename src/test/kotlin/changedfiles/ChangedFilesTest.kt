package org.kavo.uploader.changedfiles

import org.kavo.uploader.settings.PathMapping
import org.kavo.uploader.settings.ServerProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths

class ChangedFilesTest {
    private val projectRoot: Path = Paths.get("/project")
    private val mappings = listOf(
        PathMapping("target/classes", "/remote/APP-INF/classes"),
        PathMapping("src", "/remote/src"),
      )

     @Test
    fun `non java file without mapping is disabled`() {
        val path = Paths.get("/project/unmapped.txt")
        val row = buildUploadRows(listOf(path), emptyMap(), true, projectRoot).single()
        assertFalse(rowIsEnabled(row, projectRoot, mappings))
       }

     @Test
    fun `non java file with mapping is enabled`() {
        val path = Paths.get("/project/src/Config.xml")
        val row = buildUploadRows(listOf(path), emptyMap(), true, projectRoot).single()
        assertTrue(rowIsEnabled(row, projectRoot, mappings))
       }

     @Test
    fun `java file without compiled class is disabled and flagged`() {
        val path = Paths.get("/project/src/Service.java")
        val row = buildUploadRows(listOf(path), emptyMap(), true, projectRoot).single()
        assertTrue(row.missingClass)
        assertFalse(rowIsEnabled(row, projectRoot, mappings))
       }

     @Test
    fun `java file with compiled class is enabled and mapped`() {
        val path = Paths.get("/project/src/Service.java")
        val classFile = Paths.get("/project/target/classes/Service.class")
        val row = buildUploadRows(listOf(path), mapOf(path to classFile), true, projectRoot).single()
        assertFalse(row.missingClass)
        assertTrue(rowIsEnabled(row, projectRoot, mappings))
       }

     @Test
    fun `java file without compiled class is shown as source when upload disabled`() {
        val path = Paths.get("/project/src/Service.java")
        val row = buildUploadRows(listOf(path), emptyMap(), false, projectRoot).single()
        assertFalse(row.missingClass)
        assertTrue(rowIsEnabled(row, projectRoot, mappings))
           }

     @Test
    fun `file enabled only by a fnmatch pattern`() {
        val path = Paths.get("/project/docs/guide.html")
        val row = buildUploadRows(listOf(path), emptyMap(), true, projectRoot).single()
        assertFalse(rowIsEnabled(row, projectRoot, mappings))
        assertTrue(rowIsEnabled(row, projectRoot, mappings + PathMapping("docs/**.html", "/remote/docs")))
    }

    @Test
    fun `preserves order of changed files`() {
        val one = Paths.get("/project/src/A.java")
        val two = Paths.get("/project/src/B.java")
        val rows = buildUploadRows(
            changed = listOf(one, two),
            classByJava = mapOf(
                one to Paths.get("/project/target/classes/A.class"),
                two to Paths.get("/project/target/classes/B.class")
                 ),
            uploadJavaClassFiles = true,
            projectRoot = projectRoot,
             )
        assertEquals(listOf("A.class", "B.class"), rows.map { it.displayPath.substringAfterLast('/') })
    }

    @Test
    fun `preselects the server selected in the tool window`() {
        val first = ServerProfile(name = "first")
        val second = ServerProfile(name = "second")
        assertEquals(second, initialServer(listOf(first, second), second))
    }

    @Test
    fun `falls back to the first server when nothing is selected`() {
        val first = ServerProfile(name = "first")
        val second = ServerProfile(name = "second")
        assertEquals(first, initialServer(listOf(first, second), null))
    }

    @Test
    fun `falls back to the first server when the selected one is gone`() {
        val first = ServerProfile(name = "first")
        val second = ServerProfile(name = "second")
        assertEquals(first, initialServer(listOf(first, second), ServerProfile(name = "removed")))
    }

    @Test
    fun `no server is preselected without profiles`() {
        assertTrue(initialServer(emptyList(), null) == null)
    }
}
