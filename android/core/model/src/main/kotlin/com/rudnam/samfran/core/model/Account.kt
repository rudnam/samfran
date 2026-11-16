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

/** A newer build of the app the server offers (GET /api/downloads). */
data class AppUpdate(
    val versionCode: Int,
    /** e.g. "2.0.9"; may be empty. */
    val name: String,
    /** Where to download it. */
    val url: String,
)

/** A connection from Claude (or another MCP client). */
data class McpToken(
    val id: Long,
    val name: String,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
)
