package org.kavo.uploader.changedfiles

import org.kavo.uploader.settings.PathMapping
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
}
