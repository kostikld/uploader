package org.kavo.uploader.changedfiles

import org.kavo.uploader.settings.PathMapping
import org.kavo.uploader.settings.ServerProfile
import org.kavo.uploader.upload.PathMappingResolver
import java.nio.file.Path

data class UploadRow(
    val displayPath: String,
    val uploadPath: Path,
    val missingClass: Boolean,
    val sourcePath: Path,
    val reason: String? = null,
)

fun buildUploadRows(
    changed: List<Path>,
    classByJava: Map<Path, Path?>,
    uploadJavaClassFiles: Boolean,
    projectRoot: Path,
    reasonBySource: Map<Path, String> = emptyMap(),
): List<UploadRow> {
    val root = projectRoot.toAbsolutePath().normalize()
    val results = mutableListOf<UploadRow>()
    changed.forEach { source ->
        val absolute = source.toAbsolutePath().normalize()
        val isJava = absolute.fileName?.toString()?.endsWith(".java") == true
        val compiled = if (isJava && uploadJavaClassFiles) classByJava[absolute] else null
        val uploadPath = compiled ?: absolute
        val missingClass = isJava && uploadJavaClassFiles && compiled == null
        results.add(
            UploadRow(
                displayPath = root.relativize(uploadPath).toString(),
                uploadPath = uploadPath,
                missingClass = missingClass,
                sourcePath = absolute,
                reason = reasonBySource[absolute],
            ),
        )
    }
    return results
}

/** Two rows stand for the same file when their upload path is the same. */
fun rowKey(row: UploadRow): String = row.uploadPath.toAbsolutePath().normalize().toString()

/**
 * A class that both changed in git and reads a changed constant is listed twice; uploading it twice would
 * only repeat the same file, so the second row is dropped.
 */
fun dedupeRows(rows: List<UploadRow>): List<UploadRow> = rows.distinctBy { row -> rowKey(row) }

/** Whether the file a row stands for is Java source. */
fun isJavaSource(row: UploadRow): Boolean = row.sourcePath.toString().endsWith(".java")

/** The Java files a list of rows stands for, which is what the constants analysis works on. */
fun javaSources(rows: List<UploadRow>): List<Path> =
    rows.filter { row -> isJavaSource(row) }.map { row -> row.sourcePath }.distinct()

fun rowIsEnabled(
    row: UploadRow,
    projectRoot: Path,
    mappings: List<PathMapping>,
): Boolean {
    if (row.missingClass) return false
    return PathMappingResolver.resolve(
        projectRoot.toAbsolutePath().normalize(),
        row.uploadPath,
        mappings,
    ) != null
}

/**
 * Server preselected when the changed files dialog opens: the profile selected in the tool window,
 * falling back to the first one when nothing (or a removed profile) is selected.
 */
fun initialServer(servers: List<ServerProfile>, selected: ServerProfile?): ServerProfile? =
    servers.firstOrNull { it.id == selected?.id } ?: servers.firstOrNull()
