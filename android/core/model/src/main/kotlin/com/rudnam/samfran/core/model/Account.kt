package com.rudnam.samfran.core.model

import java.time.Instant

/** Who is signed in (GET /api/me). */
data class Me(
    val email: String,
    /** False for accounts that only sign in with Google. */
    val hasPassword: Boolean = true,
    /** The server emailed a link to confirm the address, and it hasn't been opened yet. */
    val confirmEmail: Boolean = false,
    /** A throwaway demo account full of sample data, deleted within a day. */
    val demo: Boolean = false,
)

/** The ways this server offers to sign in. */
data class AuthOptions(
    val google: Boolean,
    /** Visitors can try a demo without an account. */
    val demo: Boolean,
    /** A forgotten password can be reset by email. */
    val reset: Boolean,
)

/**
 * One release's user-facing notes, baked into the APK's assets/changelog.json
 * and returned by GET /api/downloads.
 */
data class ChangelogEntry(
    val versionCode: Int,
    val versionName: String = "",
    val en: List<String> = emptyList(),
    val ja: List<String> = emptyList(),
) {
    fun notesFor(japanese: Boolean): List<String> =
        if (japanese && ja.isNotEmpty()) ja else en
}

/**
 * A newer build of the app: the one the server offers (GET /api/downloads),
 * or for a copy installed from the Play Store, the one Play has.
 */
data class AppUpdate(
    val versionCode: Int,
    /** e.g. "2.0.9"; may be empty (Play doesn't say). */
    val name: String,
    /** Where to download it; empty when it comes from Play. */
    val url: String,
    /** Installed through Play's own update screen rather than downloaded. */
    val fromPlay: Boolean = false,
    /** Changes since the installed version, newest first. */
    val changelog: List<ChangelogEntry> = emptyList(),
)

/** A connection from Claude (or another MCP client). */
data class McpToken(
    val id: Long,
    val name: String,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
)
