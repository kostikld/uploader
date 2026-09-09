package org.kavo.uploader.transfer

import org.kavo.uploader.settings.PathMapping
import org.kavo.uploader.settings.ServerAction
import java.util.UUID

object ProfileTransfer {
    const val SEPARATOR = " -> "

    fun encodeMappings(mappings: List<PathMapping>): String =
        mappings.joinToString("\n") { "${it.localPath}$SEPARATOR${it.remotePath}" }

    fun decodeMappings(text: String): List<PathMapping> =
        parseLines(text).map { (local, remote) -> PathMapping(local, remote) }

    fun encodeActions(actions: List<ServerAction>): String =
        actions.joinToString("\n") { "${it.name}$SEPARATOR${it.command}" }

    fun decodeActions(text: String): List<ServerAction> =
        parseLines(text)
            .filter { (name, command) -> name.isNotBlank() && command.isNotBlank() }
            .map { (name, command) ->
                ServerAction(id = UUID.randomUUID().toString(), name = name, command = command)
            }

    private fun parseLines(text: String): List<Pair<String, String>> =
        text.split("\n").map { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@map null
            val separatorIndex = trimmed.indexOf(SEPARATOR)
            val local = if (separatorIndex < 0) trimmed else trimmed.substring(0, separatorIndex).trim()
            val remote = if (separatorIndex < 0) "" else trimmed.substring(separatorIndex + SEPARATOR.length).trim()
            local to remote
        }.filterNotNull()
}
