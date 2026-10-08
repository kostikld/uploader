package org.kavo.uploader.upload

import org.kavo.uploader.settings.PathMapping
import java.nio.file.Path

object PathMappingResolver {
    fun resolve(projectRoot: Path, file: Path, mappings: List<PathMapping>): String? {
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

    private fun resolveMatch(projectRoot: Path, file: Path, mappings: List<PathMapping>): Pair<PathMapping, String>? {
        val root = projectRoot.toAbsolutePath().normalize()
        val normalizedFile = file.toAbsolutePath().normalize()
        if (!normalizedFile.startsWith(root)) return null

        val projectRelative = normalizeLocal(root.relativize(normalizedFile).toString())
        val match = mappings
            .mapNotNull { mapping ->
                val localRoot = normalizeLocal(mapping.localPath)
                if (!matches(projectRelative, localRoot)) null else mapping to localRoot
            }
            .maxByOrNull { (_, localRoot) -> localRoot.length }
            ?: return null

        val (mapping, localRoot) = match
        val remainder = if (localRoot.isEmpty()) {
            projectRelative
        } else {
            projectRelative.removePrefix(localRoot).removePrefix("/")
        }
        return mapping to remainder
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
