package org.kavo.uploader.upload

import java.nio.file.Files
import java.nio.file.Path

object ClassFileExpander {
    fun expand(files: List<Path>, includeInnerClasses: Boolean): List<Path> {
        if (!includeInnerClasses) return files

        val result = LinkedHashSet<Path>()
        result.addAll(files)
        files.forEach { file ->
            if (file.fileName?.toString()?.endsWith(".class") != true) return@forEach
            val base = file.fileName.toString().removeSuffix(".class")
            result.addAll(innerClassesOf(file, base))
         }
        return result.toList()
     }

    private fun innerClassesOf(file: Path, base: String): List<Path> {
        val directory = file.parent
        if (directory == null || !Files.isDirectory(directory)) return emptyList()

        val found = mutableListOf<Path>()
        Files.list(directory).use { stream ->
            stream.forEach { sibling ->
                if (!Files.isRegularFile(sibling)) return@forEach
                val name = sibling.fileName.toString()
                if (name.startsWith(base + "$", true) && name.endsWith(".class")) {
                    found.add(sibling)
                  }
              }
          }
        return found.sortedBy { it.fileName.toString() }
     }
}
