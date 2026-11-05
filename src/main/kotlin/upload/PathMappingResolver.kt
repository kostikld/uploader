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
     * wins over a pattern row; the file keeps everything below the matched local root (or below the
     * pattern's literal prefix).
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
        val match = directMatch(direct, projectRelative) ?: fnMatch(patterns, projectRelative) ?: return null
        val (mapping, localRoot) = match
        val remainder = if (localRoot.isEmpty()) {
            projectRelative
        } else {
            projectRelative.removePrefix(localRoot).removePrefix("/")
        }
        return mapping to remainder
    }

    private fun directMatch(mappings: List<PathMapping>, projectRelative: String): Pair<PathMapping, String>? =
        mappings
            .mapNotNull { mapping ->
                val localRoot = normalizeLocal(mapping.localPath)
                if (!matches(projectRelative, localRoot)) null else mapping to localRoot
            }
            .maxByOrNull { (_, localRoot) -> localRoot.length }

    /**
     * Matches a file against the configured patterns. The most concrete pattern wins: the longest
     * literal prefix, then the most literal characters, then the fewest wildcards, then the configured order.
     */
    private fun fnMatch(patterns: List<PathMapping>, projectRelative: String): Pair<PathMapping, String>? {
        val candidates = patterns
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
        return candidates.mapping to candidates.prefix
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

    private fun joinRemote(remoteRoot: String, remainder: String): String {
        val root = normalizeRemote(remoteRoot).trimEnd('/')
        val suffix = normalizeLocal(remainder)
        return if (suffix.isEmpty()) root else "$root/$suffix"
    }
}
