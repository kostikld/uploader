package org.kavo.uploader.upload

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object JavaClassResolver {
    fun mapClassFile(source: Path, sourceRoot: Path, classRoots: List<Path>): Path? {
        val name = source.fileName?.toString() ?: return null
        if (!name.endsWith(".java")) return null
        val relative = sourceRoot.relativize(source)
        val className = name.removeSuffix(".java") + ".class"
        val classRelative = relative.resolveSibling(className)
        for (classRoot in classRoots) {
            val candidate = classRoot.resolve(classRelative).normalize()
            if (Files.isRegularFile(candidate)) return candidate
         }
        return null
     }

    fun classFilesFor(project: Project, files: List<VirtualFile>, enabled: Boolean): List<Path> {
        if (!enabled) return files.map { Paths.get(it.path) }

        val results = LinkedHashSet<Path>()
        files.forEach { file ->
            if (!file.name.endsWith(".java")) {
                results.add(Paths.get(file.path))
                return@forEach
              }
            resolveClassFile(project, file)?.let(results::add)
          }
        return results.toList()
       }

    fun compiledClassFile(project: Project, file: VirtualFile): Path? =
        if (!file.name.endsWith(".java")) null else resolveClassFile(project, file)

    private fun resolveClassFile(project: Project, file: VirtualFile): Path? {
        val fileIndex = ProjectFileIndex.getInstance(project)
        val sourceRoot = fileIndex.getSourceRootForFile(file) ?: return null
        val sourceRootPath = Paths.get(sourceRoot.path)

        val moduleClassRoots = fileIndex.getModuleForFile(file)
             ?.let { module ->
                 OrderEnumerator.orderEntries(module).productionOnly().classes()
                      .getRoots()
                      .map { Paths.get(it.path) }
              }
             .orEmpty()

        // The IDE module model can be out of sync for Maven/Gradle projects, where
        // target/classes exists on disk but is not registered as a class root. Fall
        // back to the conventional output locations of the owning module.
        val classRoots = moduleClassRoots + conventionalClassRoots(sourceRootPath)
        return mapClassFile(Paths.get(file.path), sourceRootPath, classRoots)
      }

    internal fun conventionalClassRoots(sourceRoot: Path): List<Path> {
        val roots = LinkedHashSet<Path>()
        var current = sourceRoot
        repeat(MAX_CONVENTION_ANCESTORS) {
            CONVENTION_OUTPUTS.forEach { output ->
                roots.add(current.resolve(output).normalize())
             }
            current = current.parent ?: return roots.toList()
         }
        return roots.toList()
       }
}

private const val MAX_CONVENTION_ANCESTORS = 8

private val CONVENTION_OUTPUTS = listOf(
     "target/classes",
     "build/classes/java/main",
     "build/classes/kotlin/main",
     "build/classes/groovy/main",
     "out/classes",
)
