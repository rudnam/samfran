package com.rudnam.samfran.core.model

import java.time.Instant

/**
 * An app someone has locked on their own phone (internal/applock): opening
 * it asks the other person first.
 */
data class AppLock(
    val id: Long,
    /** The app's Android package, e.g. com.instagram.android. */
    val packageName: String,
    val label: String,
    /** It's on this person's phone; otherwise it's the other's. */
    val mine: Boolean,
    /** Whose phone it's on. */
    val owner: String,
    val createdAt: Instant,
    /** When an allowed opening ends; null while it's locked. */
    val openUntil: Instant?,
) {
    fun isOpen(now: Instant): Boolean = openUntil?.isAfter(now) == true
}

enum class LockRequestKind(val api: String) {
    /** Asking to open it for a while. */
    Open("open"),

    /** Asking to unlock it for good. */
    Remove("remove"),

    /** Opened without asking (an emergency); the other person was told. */
    Override("override"),
    ;

    companion object {
        fun of(api: String) = entries.firstOrNull { it.api == api } ?: Open
    }
}

enum class LockRequestStatus(val api: String) {
    Pending("pending"),
    Allowed("allowed"),
    Denied("denied"),
    Cancelled("cancelled"),

    /** Nobody answered in time (15 minutes). */
    Expired("expired"),
    ;

    companion object {
        fun of(api: String) = entries.firstOrNull { it.api == api } ?: Expired
    }
}

/** Asking to open (or unlock) a locked app, or opening it without asking. */
data class LockRequest(
    val id: Long,
    /** Null once the app is no longer locked. */
    val lockId: Long?,
    val packageName: String,
    val label: String,
    val kind: LockRequestKind,
    /** How long it was asked to be open for (as allowed, once allowed); 0 to unlock for good. */
    val minutes: Int,
    val message: String,
    val status: LockRequestStatus,
    val reply: String,
    val openUntil: Instant?,
    /** This person asked. */
    val mine: Boolean,
    /** Who asked. */
    val who: String,
    val createdAt: Instant,
    val answeredAt: Instant?,
    /** While pending: when it expires. */
    val expiresAt: Instant?,
) {
    val pending: Boolean get() = status == LockRequestStatus.Pending
}

/** Both people's locked apps and the latest requests (GET /api/locks). */
data class LockPage(
    val locks: List<AppLock>,
    val requests: List<LockRequest>,
    /** The other person's name; "" before they've joined, when nothing can be locked. */
    val partner: String,
) {
    val mine: List<AppLock> get() = locks.filter { it.mine }
    val theirs: List<AppLock> get() = locks.filter { !it.mine }

    /** The other person's requests waiting for this one's answer. */
    val waitingForMe: List<LockRequest> get() = requests.filter { !it.mine && it.pending }

    /** This person's own lock on [packageName], if it's locked. */
    fun lockOf(packageName: String): AppLock? = locks.firstOrNull { it.mine && it.packageName == packageName }

    /** The latest of this person's requests about [lock]. */
    fun latestFor(lock: AppLock): LockRequest? = requests.firstOrNull { it.mine && it.lockId == lock.id }
}

/** An app on this phone that could be locked. */
data class InstalledApp(val packageName: String, val label: String)

/**
 * What the phone allows SamFran, which locking needs: seeing which app is
 * open (usage access), drawing over it (display over other apps), and
 * notifications (to hear the answer, and the other person's asking).
 */
data class LockSetup(
    val usageAccess: Boolean = false,
    val overlay: Boolean = false,
    val notifications: Boolean = false,
) {
    val ready: Boolean get() = usageAccess && overlay
}

/** How long one can ask to open an app for, in minutes (the server allows 1 to 240). */
val LockMinutes = listOf(5, 15, 30, 60)
