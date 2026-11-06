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

    @Test
    fun `a derived row keeps the Java file it was found from`() {
        val java = Paths.get("/project/src/Service.java")
        val classFile = Paths.get("/project/target/classes/Service.class")
        val row = buildUploadRows(
            changed = listOf(java),
            classByJava = mapOf(java to classFile),
            uploadJavaClassFiles = true,
            projectRoot = projectRoot,
            reasonBySource = mapOf(java to "MAX: 10 -> 20"),
        ).single()
        assertEquals(java, row.sourcePath)
        assertEquals("MAX: 10 -> 20", row.reason)
    }

    @Test
    fun `a class listed twice is uploaded once`() {
        val java = Paths.get("/project/src/Service.java")
        val classFile = Paths.get("/project/target/classes/Service.class")
        val changedRows = buildUploadRows(listOf(java), mapOf(java to classFile), true, projectRoot)
        val derivedRows = buildUploadRows(
            changed = listOf(java),
            classByJava = mapOf(java to classFile),
            uploadJavaClassFiles = true,
            projectRoot = projectRoot,
            reasonBySource = mapOf(java to "MAX: 10 -> 20"),
        )
        assertEquals(listOf(classFile), dedupeRows(changedRows + derivedRows).map { it.uploadPath })
    }

    @Test
    fun `the analysis gives the Java file a class row stands for`() {
        val java = Paths.get("/project/src/Service.java")
        val classFile = Paths.get("/project/target/classes/Service.class")
        val rows = buildUploadRows(listOf(java), mapOf(java to classFile), true, projectRoot) +
            buildUploadRows(listOf(Paths.get("/project/src/App.kt")), emptyMap(), true, projectRoot)
        assertEquals(listOf(classFile, Paths.get("/project/src/App.kt")), rows.map { it.uploadPath })
        assertEquals(listOf(java), javaSources(rows))
    }

    @Test
    fun `a list without a Java file has nothing to analyze`() {
        val rows = buildUploadRows(listOf(Paths.get("/project/src/App.kt")), emptyMap(), true, projectRoot)
        assertTrue(javaSources(rows).isEmpty())
    }

    @Test
    fun `a file that only appears as a dependent stays in the list`() {
        val java = Paths.get("/project/src/Service.java")
        val classFile = Paths.get("/project/target/classes/Service.class")
        val other = Paths.get("/project/src/Other.java")
        val otherClass = Paths.get("/project/target/classes/Other.class")
        val rows = dedupeRows(
            buildUploadRows(listOf(java), mapOf(java to classFile), true, projectRoot) +
                buildUploadRows(listOf(other), mapOf(other to otherClass), true, projectRoot),
        )
        assertEquals(listOf(classFile, otherClass), rows.map { it.uploadPath })
    }
}
