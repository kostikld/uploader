package org.kavo.uploader.settings

import java.util.UUID

// ---------------------------------------------------------------------------
// ONE-SHOT CONFIG MIGRATION - DELETE ME WHEN NO SUPPORTED VERSION NEEDS IT
// ---------------------------------------------------------------------------
//
// Profiles used to carry their own `username`, and their password was stored in the PasswordSafe under
// the profile id. Usernames can now be reusable `CredentialsProfile` entries, so this file gives every profile
// that still has a bare username an entry to point at: profiles that share a username share one entry.
// The stored secret is deliberately left where it is - reading and writing the PasswordSafe while settings
// load could prompt the IDE for its passcode - and `PasswordStore.passwordOf` keeps reading that profile-keyed
// entry as a fallback until a password is typed again, which moves it onto the entry.
//
// Once every supported installation has loaded its config once, this is dead code. Remove it as one
// change:
//   1. delete this file,
//   2. delete the profile-keyed fallback in `PasswordStore.passwordOf` - the profile-keyed functions
//      themselves stay, they serve servers that keep their credentials to themselves,
//   3. delete the `migrateLegacyCredentials(state)` call in `SftpSettings.loadState`,
//   4. delete src/test/kotlin/settings/LegacyCredentialsMigrationTest.kt.
//

/** Points every profile that still keeps its username inline at a shared credentials entry. */
fun migrateLegacyCredentials(state: SftpSettingsState) {
    state.servers.forEach { profile -> migrateLegacyCredentials(state, profile) }
}

private fun migrateLegacyCredentials(state: SftpSettingsState, profile: ServerProfile) {
    if (profile.credentialsId.isNotEmpty() || profile.username.isBlank()) return
    val credential = state.credentials.firstOrNull { it.username == profile.username }
        ?: CredentialsProfile(
            id = UUID.randomUUID().toString(),
            name = credentialName(state, profile.username),
            username = profile.username,
        ).also { state.credentials.add(it) }
    profile.credentialsId = credential.id
}

/** A new entry is named after the username; an entry with that name already is sequenced. */
private fun credentialName(state: SftpSettingsState, username: String): String =
    if (state.credentials.none { it.name == username }) username
    else uniqueCloneName(username, state.credentials.map { it.name })
