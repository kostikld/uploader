package org.kavo.uploader.changedfiles

import org.kavo.uploader.settings.PathMapping
import org.kavo.uploader.upload.PathMappingResolver
import java.nio.file.Path

data class UploadRow(
    val displayPath: String,
    val uploadPath: Path,
    val missingClass: Boolean,
)

fun buildUploadRows(
    changed: List<Path>,
    classByJava: Map<Path, Path?>,
    uploadJavaClassFiles: Boolean,
    projectRoot: Path,
): List<UploadRow> {
    val root = projectRoot.toAbsolutePath().normalize()
    val results = mutableListOf<UploadRow>()
    changed.forEach { source ->
        val absolute = source.toAbsolutePath().normalize()
        val isJava = absolute.fileName?.toString()?.endsWith(".java") == true
        val compiled = when {
            isJava && uploadJavaClassFiles -> classByJava[absolute]
            else -> null
            }
        results.add(
            when {
                compiled != null ->
                    UploadRow(root.relativize(compiled.toAbsolutePath().normalize()).toString(), compiled, false)
                isJava && uploadJavaClassFiles ->
                    UploadRow(root.relativize(absolute).toString(), absolute, true)
                else ->
                    UploadRow(root.relativize(absolute).toString(), absolute, false)
                }
            )
           }
    return results
        }

fun rowIsEnabled(row: UploadRow, projectRoot: Path, mappings: List<PathMapping>): Boolean {
    if (row.missingClass) return false
    return PathMappingResolver.resolve(projectRoot.toAbsolutePath().normalize(), row.uploadPath, mappings) != null
       }
