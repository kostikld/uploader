package org.kavo.uploader.upload

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstantNameSearchTest {
    @Test
    fun `finds the java file that mentions the changed constant`() {
        val root = tempDir("name-search")
        try {
            val declaring = write(root, "src/main/java/pkg/Constants.java", """
                public interface Constants {
                    String MESSAGE_ATTR_ID = "Id";
                }
                """)
            val reader = write(root, "src/main/java/pkg/Reader.java", """
                class Reader {
                    void read(String message) {
                        if (Constants.MESSAGE_ATTR_ID != null) use(message);
                    }
                }
                """)
            write(root, "src/main/java/pkg/Unrelated.java", "class Unrelated { int otherMESSAGE_ATTR_ID = 1; }")
            write(root, "src/main/java/pkg/OnlyComment.java", "// MESSAGE_ATTR_ID was changed")
            write(root, "target/classes/pkg/Copied.java", "MESSAGE_ATTR_ID")
            write(root, "src/main/kt/pkg/Reader.kt", "Constants.MESSAGE_ATTR_ID")
            val change = ConstantFileChange(
                constantNames = listOf("MESSAGE_ATTR_ID"),
                summary = "MESSAGE_ATTR_ID: \"Id\" -> \"Ids\"",
            )
            val affected = ConstantNameSearch.affectedFiles(root, mapOf(declaring to change))
            assertEquals(listOf(reader), affected.keys.toList())
            assertEquals("MESSAGE_ATTR_ID: \"Id\" -> \"Ids\"", affected[reader])
        } finally {
            deleteRecursively(File(root.toString()))
        }
    }

    @Test
    fun `nothing is asked when no constant changed`() {
        val root = tempDir("name-search-empty")
        try {
            val declaring = write(root, "src/main/java/pkg/Constants.java", "interface C { String A = \"a\"; }")
            write(root, "src/main/java/pkg/Reader.java", "Constants.A")
            val change = ConstantFileChange(constantNames = emptyList(), summary = "")
            assertTrue(ConstantNameSearch.affectedFiles(root, mapOf(declaring to change)).isEmpty())
        } finally {
            deleteRecursively(File(root.toString()))
        }
    }

    private fun tempDir(name: String): Path {
        val dir = File(System.getProperty("java.io.tmpdir"), name)
        dir.mkdirs()
        return Paths.get(dir.getCanonicalPath())
    }

    private fun write(root: Path, relative: String, text: String): Path {
        val path = Paths.get(root.toString() + "/" + relative)
        File(path.toString()).getParentFile()?.mkdirs()
        Files.write(path, text.toByteArray())
        return path
    }

    private fun deleteRecursively(dir: File) {
        dir.listFiles()?.toList()?.forEach { child ->
            if (child.isDirectory) deleteRecursively(child) else child.delete()
        }
        dir.delete()
    }
}
