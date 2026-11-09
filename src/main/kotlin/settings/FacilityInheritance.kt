package org.kavo.uploader.settings

import org.kavo.uploader.upload.PathMappingResolver

/**
 * How a server combines with its facility: the facility supplies the shared mappings and actions, and the
 * server's own rows win over a facility row with the same local path or the same action name. The lists are
 * merged at read time and never stored on the server, so editing a facility reaches every server at once,
 * and a server whose facility was removed just keeps standing on its own rows.
 */

/** The facility a server picked, null when it picked none or the facility is gone. */
fun facilityFor(profile: ServerProfile): Facility? =
    SftpSettings.getInstance().facility(profile.facilityId)

/** The mappings an upload to this server resolves against: its own rows over the facility's. */
fun effectiveMappings(profile: ServerProfile): List<PathMapping> =
    mergeMappings(facilityFor(profile)?.mappings.orEmpty(), profile.mappings)

/** The actions a server's menu offers: its own rows over the facility's. */
fun effectiveActions(profile: ServerProfile): List<ServerAction> =
    mergeActions(facilityFor(profile)?.actions.orEmpty(), profile.actions)

/**
 * Facility mappings the server does not redefine, then the server's own rows: facility first keeps the
 * resolver's configured-order tie-break predictable, and a server row wins by removing the facility row
 * whose normalized local path it repeats.
 */
fun mergeMappings(facility: List<PathMapping>, own: List<PathMapping>): List<PathMapping> {
    if (facility.isEmpty()) return own
    if (own.isEmpty()) return facility
    val ownPaths = own.map { PathMappingResolver.normalizeLocal(it.localPath) }.toSet()
    return facility.filterNot { PathMappingResolver.normalizeLocal(it.localPath) in ownPaths } + own
}

/** Facility actions the server does not redefine, then the server's own rows; names match trimmed. */
fun mergeActions(facility: List<ServerAction>, own: List<ServerAction>): List<ServerAction> {
    if (facility.isEmpty()) return own
    if (own.isEmpty()) return facility
    val ownNames = own.map { it.name.trim() }.toSet()
    return facility.filterNot { it.name.trim() in ownNames } + own
}

/** The normalized local paths the facility already maps: a server row with one of them overrides it. */
fun facilityMappingKeys(facility: Facility?): Set<String> =
    facility?.mappings.orEmpty().map { PathMappingResolver.normalizeLocal(it.localPath) }.toSet()

/** The action names the facility already defines: a server row with one of them overrides it. */
fun facilityActionNames(facility: Facility?): Set<String> =
    facility?.actions.orEmpty().map { it.name.trim() }.toSet()
