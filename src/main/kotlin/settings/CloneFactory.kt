package org.kavo.uploader.settings

import java.util.UUID

fun cloneMapping(mapping: PathMapping): PathMapping =
    PathMapping(
        localPath = mapping.localPath,
        remotePath = mapping.remotePath,
    )

fun cloneAction(action: ServerAction): ServerAction =
    ServerAction(
        id = UUID.randomUUID().toString(),
        name = action.name,
        command = action.command,
    )

fun cloneAction(action: ServerAction, takenNames: MutableSet<String>): ServerAction =
    ServerAction(
        id = UUID.randomUUID().toString(),
        name = uniqueCloneName(action.name, takenNames).also { takenNames.add(it) },
        command = action.command,
    )

fun cloneActions(actions: List<ServerAction>, takenNames: Collection<String>): List<ServerAction> {
    val taken = takenNames.toMutableSet()
    return actions.map { cloneAction(it, taken) }
}

fun cloneProfile(profile: ServerProfile, takenNames: Collection<String>): ServerProfile =
    ServerProfile(
        id = UUID.randomUUID().toString(),
        name = uniqueCloneName(profile.name, takenNames),
        folder = profile.folder,
        host = profile.host,
        port = profile.port,
        username = profile.username,
        useRsync = profile.useRsync,
        type = profile.type,
        basePath = profile.basePath,
        mappings = profile.mappings.map { cloneMapping(it) }.toMutableList(),
        actions = profile.actions.map { cloneAction(it) }.toMutableList(),
    )

/**
 * Name for a cloned entry: `serve` -> `serve(1)`, then `serve(2)`.
 * A trailing `(n)` belongs to the sequence, so cloning `serve(1)` continues the `serve` family.
 */
fun uniqueCloneName(source: String, takenNames: Collection<String>): String {
    val root = cloneNameRoot(source)
    val highest = takenNames
        .filter { cloneNameRoot(it) == root }
        .map { cloneNameSequence(it) }
        .maxOrNull() ?: 0
    var sequence = maxOf(highest, cloneNameSequence(source)) + 1
    while (takenNames.contains("$root($sequence)")) sequence += 1
    return "$root($sequence)"
}

private fun cloneNameSequence(name: String): Int {
    if (!name.endsWith(")")) return 0
    val open = name.lastIndexOf('(')
    if (open <= 0) return 0
    val digits = name.substring(open + 1, name.length - 1)
    if (digits.isEmpty() || digits.any { !it.isDigit() }) return 0
    return digits.toIntOrNull() ?: 0
}

private fun cloneNameRoot(name: String): String {
    if (cloneNameSequence(name) == 0) return name
    return name.substring(0, name.lastIndexOf('('))
}
