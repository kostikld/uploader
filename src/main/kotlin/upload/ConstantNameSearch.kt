package org.kavo.uploader.upload

import java.io.File
import java.nio.file.Path
import java.util.regex.Pattern

/**
 * The Java files that mention a changed constant, found by reading the sources under the project.
 *
 * The IDE's code index answers this exactly; this search is the fallback for the case where that index knows
 * nothing about the files. It is a wider net: a file is listed when the name occurs in it outside a comment,
 * whatever type that name belongs to there, so the listed files are worth looking over before uploading.
 */
object ConstantNameSearch {
    private val SKIP_DIRS =
        setOf(".git", ".idea", ".gradle", ".cache", ".svn", "target", "build", "out", "bin", "dist", "node_modules")
    private const val MAX_DEPTH = 32

    fun affectedFiles(projectRoot: Path, changes: Map<Path, ConstantFileChange>): Map<Path, String> {
        val wanted = changes.flatMap { entry ->
            val source = entry.key.toAbsolutePath().normalize()
            val summary = entry.value.summary
            entry.value.constantNames.map { name ->
                Wanted(name, Pattern.compile("\\b${Pattern.quote(name)}\\b"), summary, source)
            }
        }
        if (wanted.isEmpty()) return emptyMap()
        val affected = linkedMapOf<Path, String>()
        scan(File(projectRoot.toString()), wanted, affected, 0)
        return affected
    }

    private data class Wanted(
        val name: String,
        val pattern: Pattern,
        val summary: String,
        val declaringFile: Path,
    )

    private fun scan(dir: File, wanted: List<Wanted>, affected: MutableMap<Path, String>, depth: Int) {
        if (depth > MAX_DEPTH) return
        val children = try {
            dir.listFiles()
        } catch (_: SecurityException) {
            null
        } ?: return
        children.forEach { child ->
            when {
                child.isDirectory -> if (!SKIP_DIRS.contains(child.name)) scan(child, wanted, affected, depth + 1)
                child.name.endsWith(".java") -> addIfReading(child, wanted, affected)
            }
        }
    }

    private fun addIfReading(
        file: File,
        wanted: List<Wanted>,
        affected: MutableMap<Path, String>,
    ) {
        val path = file.toPath().toAbsolutePath().normalize()
        val text = try {
            withoutComments(file.readText())
        } catch (_: Exception) {
            return
        }
        val match = wanted.firstOrNull { candidate ->
            candidate.declaringFile != path && text.contains(candidate.name)
                    && candidate.pattern.matcher(text).find()
        }
        if (match != null) affected[path] = match.summary
    }
}
