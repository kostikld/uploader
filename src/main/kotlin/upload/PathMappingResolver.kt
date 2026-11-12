package org.kavo.uploader.upload

import org.kavo.uploader.settings.PathMapping
import java.nio.file.Path

object PathMappingResolver {
    fun resolve(
        projectRoot: Path,
        file: Path,
        mappings: List<PathMapping>,
    ): String? {
        val match = resolveMatch(projectRoot, file, mappings) ?: return null
        val (mapping, remainder) = match
        return joinRemote(mapping.remotePath, remainder)
    }

    fun resolveLocal(
        projectRoot: Path,
        file: Path,
        mappings: List<PathMapping>,
        basePath: String,
    ): String? {
        val match = resolveMatch(projectRoot, file, mappings) ?: return null
        val (mapping, remainder) = match
        val remote = normalizeRemote(mapping.remotePath)
        val relative = normalizeLocal(remainder)
        val base = basePath.trim().replace('\\', '/')
        val body = listOf(base.trim('/'), remote.trim('/'), relative)
            .filter { it.isNotEmpty() }
            .joinToString("/")
        val absolute = base.startsWith("/") || remote.startsWith("/")
        return if (absolute) "/$body" else body
    }

    /**
     * Finds the mapping that owns a file. A row whose local path has no wildcard is a direct mapping and
     * wins over a pattern row. The file keeps everything below the matched local root (or below the
     * pattern's literal prefix), except that a pattern ending in a bare `**` keeps only the path the
     * globstar matched.
     */
    private fun resolveMatch(
        projectRoot: Path,
        file: Path,
        mappings: List<PathMapping>,
    ): Pair<PathMapping, String>? {
        val root = projectRoot.toAbsolutePath().normalize()
        val normalizedFile = file.toAbsolutePath().normalize()
        if (!normalizedFile.startsWith(root)) return null

        val projectRelative = normalizeLocal(root.relativize(normalizedFile).toString())
        val (direct, patterns) =
            mappings.partition { mapping -> !FnMatch.isPattern(normalizeLocal(mapping.localPath)) }
        return directMatch(direct, projectRelative) ?: fnMatch(patterns, projectRelative)
    }

    /** The winning direct mapping with the path of the file below its local root. */
    private fun directMatch(mappings: List<PathMapping>, projectRelative: String): Pair<PathMapping, String>? =
        mappings
            .mapNotNull { mapping ->
                val localRoot = normalizeLocal(mapping.localPath)
                if (!matches(projectRelative, localRoot)) null
                else Triple(mapping, localRoot.length, remainderBelow(projectRelative, localRoot))
            }
            .maxByOrNull { (_, rootLength, _) -> rootLength }
            ?.let { (mapping, _, remainder) -> mapping to remainder }

    /**
     * Matches a file against the configured patterns. The most concrete pattern wins: the longest
     * literal prefix, then the most literal characters, then the fewest wildcards, then the configured order.
     * A pattern ending in a bare `**` keeps only what the globstar matched, so the remote path starts
     * below the last directory the pattern spells out; other patterns keep everything below their
     * literal prefix.
     */
    private fun fnMatch(patterns: List<PathMapping>, projectRelative: String): Pair<PathMapping, String>? {
        val candidate = patterns
            .mapIndexed { order, mapping ->
                val pattern = normalizeLocal(mapping.localPath)
                when {
                    pattern.isEmpty() -> null
                    !FnMatch.matches(pattern, projectRelative) -> null
                    else -> FnPatternMatch(mapping, pattern, order)
                }
            }
            .filterNotNull()
            .sortedWith(moreConcreteFirst)
            .firstOrNull()
            ?: return null
        val remainder = FnMatch.trailingGlobstarRemainder(candidate.pattern, projectRelative)
            ?: remainderBelow(projectRelative, candidate.prefix)
        return candidate.mapping to remainder
    }

    private val moreConcreteFirst = Comparator<FnPatternMatch> { first, second ->
        when {
            first.prefixSegments != second.prefixSegments -> second.prefixSegments - first.prefixSegments
            first.prefixLength != second.prefixLength -> second.prefixLength - first.prefixLength
            first.literalChars != second.literalChars -> second.literalChars - first.literalChars
            first.wildcards != second.wildcards -> first.wildcards - second.wildcards
            else -> first.order - second.order
        }
    }

    private class FnPatternMatch(
        val mapping: PathMapping,
        val pattern: String,
        val order: Int,
    ) {
        val prefix: String = FnMatch.literalPrefix(pattern)
        val prefixSegments: Int = if (prefix.isEmpty()) 0 else prefix.count { it == '/' } + 1
        val prefixLength: Int = prefix.length
        val wildcards: Int = FnMatch.wildcardCount(pattern)
        val literalChars: Int = pattern.length - wildcards
    }

    fun normalizeLocal(path: String): String =
        path.replace('\\', '/').trim().trim('/').split('/')
            .filter { it.isNotBlank() && it != "." }
            .joinToString("/")

    fun normalizeRemote(path: String): String {
        val normalized = path.replace('\\', '/').trim()
        val absolute = normalized.startsWith("/")
        val segments = normalized.split('/').filter { it.isNotBlank() && it != "." }
        require(segments.none { it == ".." }) { "Remote paths cannot contain '..'" }
        return (if (absolute) "/" else "") + segments.joinToString("/")
    }

    private fun matches(file: String, localRoot: String): Boolean =
        localRoot.isEmpty() || file == localRoot || file.startsWith("$localRoot/")

    /** The path of the file below the matched local root. */
    private fun remainderBelow(file: String, localRoot: String): String =
        if (localRoot.isEmpty()) file else file.removePrefix(localRoot).removePrefix("/")

    private fun joinRemote(remoteRoot: String, remainder: String): String {
        val root = normalizeRemote(remoteRoot).trimEnd('/')
        val suffix = normalizeLocal(remainder)
        return if (suffix.isEmpty()) root else "$root/$suffix"
    }
}
