package org.kavo.uploader.upload

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class JavaClassResolverTest {
    private lateinit var root: Path

     @Before
     fun setUp() {
         root = Files.createTempDirectory("java-class-resolver-test")
       }

@After
     fun cleanup() {
        if (Files.exists(root)) {
            Files.walk(root)
                   .sorted(Comparator.reverseOrder())
                   .forEach(Files::delete)
           }
        }

    @Test
     fun `maps a java file to its compiled class in the output root`() {
         val sourceRoot = root.resolve("src/main/java")
         val source = sourceRoot.resolve("com/pkg/Foo.java")
         val classesRoot = root.resolve("target/classes")
         create(classesRoot.resolve("com/pkg/Foo.class"))

         val result = JavaClassResolver.mapClassFile(source, sourceRoot, listOf(classesRoot))

         assertEquals(classesRoot.resolve("com/pkg/Foo.class"), result)
          }

     @Test
     fun `returns null when no output root contains the class`() {
         val sourceRoot = root.resolve("src/main/java")
         val source = sourceRoot.resolve("com/pkg/Foo.java")
         val classesRoot = root.resolve("target/classes")

         val result = JavaClassResolver.mapClassFile(source, sourceRoot, listOf(classesRoot))

        assertNull(result)
          }

@Test
     fun `picks the output root that contains the class`() {
         val sourceRoot = root.resolve("src/main/java")
         val source = sourceRoot.resolve("com/pkg/Foo.java")
         val empty = root.resolve("out/empty")
         val populated = root.resolve("build/classes/java/main")
         create(populated.resolve("com/pkg/Foo.class"))

         val result = JavaClassResolver.mapClassFile(source, sourceRoot, listOf(empty, populated))

        assertEquals(populated.resolve("com/pkg/Foo.class"), result)
          }

@Test
     fun `ignores non java files`() {
         val sourceRoot = root.resolve("src/main")
         val config = sourceRoot.resolve("application.properties")
        create(config)

         val result = JavaClassResolver.mapClassFile(config, sourceRoot, listOf(sourceRoot))

        assertNull(result)
          }

     private fun create(path: Path) {
         Files.createDirectories(path.parent)
        Files.write(path, "x".toByteArray())
          }
}
