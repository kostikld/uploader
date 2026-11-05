package org.kavo.uploader.settings

// ---------------------------------------------------------------------------
// ONE-SHOT CONFIG MIGRATION - DELETE ME WHEN NO SUPPORTED VERSION NEEDS IT
// ---------------------------------------------------------------------------
//
// Old `sftpUploader.xml` files kept wildcard rows in `ServerProfile.fnPatterns`. A row is now read
// as a pattern when its local path contains a wildcard, so both kinds live in `ServerProfile.mappings`:
// this file moves the legacy rows in, above the direct mappings (the order the old dialog showed them
// in), and empties the legacy list so the rewritten config has a single source of rows. It runs once per
// profile on load, and nothing else in the plugin reads or writes the legacy list.
//
// Once every supported installation has loaded its config once, this is dead code. Remove it as one
// change:
//   1. delete this file,
//   2. delete the `fnPatterns` field in `ServerProfile` (marked LEGACY ONLY),
//   3. delete the `migrateLegacyMappings(state)` call in `SftpSettings.loadState`,
//   4. delete src/test/kotlin/settings/LegacyMappingsMigrationTest.kt.
//

/** Moves the legacy fnmatch rows of every profile in the loaded state into its mappings list. */
fun migrateLegacyMappings(state: SftpSettingsState) {
    state.servers.forEach { migrateLegacyMappings(it) }
}

/** Moves one profile's legacy rows and clears the legacy list, so a re-save writes one list. */
private fun migrateLegacyMappings(profile: ServerProfile) {
    if (profile.fnPatterns.isEmpty()) return
    profile.mappings = (profile.fnPatterns + profile.mappings).toMutableList()
    profile.fnPatterns = mutableListOf()
}
