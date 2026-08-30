package org.kavo.uploader.upload

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class ClassFileExpanderTest {
    private lateinit var classesDir: Path

    @Before
    fun setUp() {
        classesDir = Files.createTempDirectory("class-expander-test")
     }

     @After
     fun cleanup() {
        if (Files.exists(classesDir)) {
            Files.walk(classesDir)
                  .sorted(Comparator.reverseOrder())
                  .forEach(Files::delete)
          }
      }

    @Test
    fun `includes sibling inner classes of a class file`() {
        val outer = write("CBMConstants.class")
        write("CBMConstants\$AddressTypes.class")
        write("CBMConstants\$AddressTypes\$Kind.class")
        write("Other.class")

        val result = ClassFileExpander.expand(listOf(outer), includeInnerClasses = true)
        val resultNames = result.map { it.fileName.toString() }

        assertEquals(
             listOf("CBMConstants\$AddressTypes\$Kind.class", "CBMConstants\$AddressTypes.class"),
             resultNames.filter { it.contains('$') },
         )
        assertFalse(resultNames.any { it == "Other.class" })
        }

    @Test
    fun `does not include inner classes when disabled and ignores non class files`() {
        val outer = write("Service.class")
        val config = write("config.xml")
        write("Service\$Inner.class")

        val result = ClassFileExpander.expand(listOf(outer, config), includeInnerClasses = false)

        assertEquals(listOf(outer, config), result)
        assertFalse(result.any { it.fileName.toString().contains("\$") })
      }

    @Test
    fun `does not treat a non class selection as a class`() {
        val config = write("application.properties")

        val result = ClassFileExpander.expand(listOf(config), includeInnerClasses = true)

        assertEquals(listOf(config), result)
      }

    private fun write(name: String): Path {
        val file = Paths.get(classesDir.toString(), name)
        Files.write(file, "x".toByteArray())
        return file
      }
}
