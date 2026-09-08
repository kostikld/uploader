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

    private fun resolveClassFile(project: Project, file: VirtualFile): Path? {
        val fileIndex = ProjectFileIndex.getInstance(project)
        val sourceRoot = fileIndex.getSourceRootForFile(file) ?: return null
        val module = fileIndex.getModuleForFile(file) ?: return null
        val classRoots = OrderEnumerator.orderEntries(module).productionOnly().classes()
             .getRoots()
             .map { Paths.get(it.path) }
        return mapClassFile(Paths.get(file.path), Paths.get(sourceRoot.path), classRoots)
     }
}
