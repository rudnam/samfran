package com.rudnam.samfran.core.testing

import com.rudnam.samfran.core.data.AppLocks
import com.rudnam.samfran.core.data.LocksRepository
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.model.AppLock
import com.rudnam.samfran.core.model.InstalledApp
import com.rudnam.samfran.core.model.LockPage
import com.rudnam.samfran.core.model.LockRequest
import com.rudnam.samfran.core.model.LockRequestKind
import com.rudnam.samfran.core.model.LockRequestStatus
import com.rudnam.samfran.core.model.LockSetup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

/** Locked apps for tests: Fran has locked Instagram and TikTok; Sam has locked YouTube and asks to open it. */
object LockSamples {
    val now: Instant = Instant.parse("2026-11-25T12:00:00Z")

    val instagram = AppLock(1, "com.instagram.android", "Instagram", mine = true, owner = "Fran", createdAt = now.minusSeconds(86_400), openUntil = null)
    val tiktok = AppLock(2, "com.zhiliaoapp.musically", "TikTok", mine = true, owner = "Fran", createdAt = now.minusSeconds(86_400), openUntil = now.plusSeconds(600))
    val youtube = AppLock(3, "com.google.android.youtube", "YouTube", mine = false, owner = "Sam", createdAt = now.minusSeconds(86_400), openUntil = null)

    private fun request(
        id: Long,
        lock: AppLock,
        mine: Boolean,
        status: LockRequestStatus,
        minutes: Int = 15,
        message: String = "",
        reply: String = "",
        kind: LockRequestKind = LockRequestKind.Open,
        agoSeconds: Long = 60,
    ) = LockRequest(
        id = id,
        lockId = lock.id,
        packageName = lock.packageName,
        label = lock.label,
        kind = kind,
        minutes = minutes,
        message = message,
        status = status,
        reply = reply,
        openUntil = if (status == LockRequestStatus.Allowed && kind != LockRequestKind.Remove) now.plusSeconds(minutes * 60L - agoSeconds) else null,
        mine = mine,
        who = if (mine) "Fran" else "Sam",
        createdAt = now.minusSeconds(agoSeconds),
        answeredAt = if (status == LockRequestStatus.Pending) null else now.minusSeconds(agoSeconds - 20),
        expiresAt = if (status == LockRequestStatus.Pending) now.minusSeconds(agoSeconds).plusSeconds(900) else null,
    )

    val samAsks = request(10, youtube, mine = false, status = LockRequestStatus.Pending, minutes = 15, message = "Just one video about the trip", agoSeconds = 120)
    val franAsks = request(9, instagram, mine = true, status = LockRequestStatus.Pending, minutes = 30, message = "Mum sent photos", agoSeconds = 90)
    val franAllowed = request(8, tiktok, mine = true, status = LockRequestStatus.Allowed, minutes = 10, reply = "10, then bed", agoSeconds = 300)
    val franDenied = request(7, instagram, mine = true, status = LockRequestStatus.Denied, minutes = 60, reply = "Not during dinner", agoSeconds = 7_200)
    val samOverride = request(6, youtube, mine = false, status = LockRequestStatus.Allowed, minutes = 5, message = "Bus times", kind = LockRequestKind.Override, agoSeconds = 86_000)

    val page = LockPage(
        locks = listOf(instagram, tiktok, youtube),
        requests = listOf(samAsks, franAsks, franAllowed, franDenied, samOverride),
        partner = "Sam",
    )

    val apps = listOf(
        InstalledApp("com.android.chrome", "Chrome"),
        InstalledApp("com.instagram.android", "Instagram"),
        InstalledApp("com.google.android.apps.maps", "Maps"),
        InstalledApp("com.netflix.mediaclient", "Netflix"),
        InstalledApp("com.zhiliaoapp.musically", "TikTok"),
        InstalledApp("com.google.android.youtube", "YouTube"),
    )
}

class FakeLocksRepository(page: LockPage? = LockSamples.page) : LocksRepository {
    val pageFlow = MutableStateFlow(page)
    var next: Outcome<Nothing>? = null
    val asked = mutableListOf<Triple<Long, LockRequestKind, Int>>()
    val answers = mutableListOf<Pair<Long, Boolean>>()
    val tokens = mutableListOf<String>()
    /** What [request] answers next, for a request being waited on. */
    var requestAnswer: LockRequest? = null

    override val page: Flow<LockPage?> = pageFlow
    override suspend fun refresh(): Outcome<LockPage> = next ?: pageFlow.value?.let { Outcome.Done(it) } ?: Outcome.Offline

    override suspend fun lock(app: InstalledApp): Outcome<LockPage> {
        next?.let { return it }
        val p = pageFlow.value!!
        val lock = AppLock((p.locks.maxOfOrNull { it.id } ?: 0) + 1, app.packageName, app.label, true, "Fran", LockSamples.now, null)
        return Outcome.Done(p.copy(locks = p.locks + lock).also { pageFlow.value = it })
    }

    override suspend fun ask(lockId: Long, kind: LockRequestKind, minutes: Int, message: String): Outcome<LockRequest> {
        next?.let { return it }
        asked += Triple(lockId, kind, minutes)
        val p = pageFlow.value!!
        val lock = p.locks.first { it.id == lockId }
        val r = LockRequest(
            id = (p.requests.maxOfOrNull { it.id } ?: 0) + 1, lockId = lockId, packageName = lock.packageName, label = lock.label,
            kind = kind, minutes = minutes, message = message, status = LockRequestStatus.Pending, reply = "", openUntil = null,
            mine = true, who = "Fran", createdAt = LockSamples.now, answeredAt = null, expiresAt = LockSamples.now.plusSeconds(900),
        )
        pageFlow.value = p.copy(requests = listOf(r) + p.requests)
        return Outcome.Done(r)
    }

    override suspend fun request(id: Long): Outcome<LockRequest> {
        next?.let { return it }
        requestAnswer?.takeIf { it.id == id }?.let { r ->
            pageFlow.value = pageFlow.value?.let { p -> p.copy(requests = p.requests.map { if (it.id == id) r else it }) }
            return Outcome.Done(r)
        }
        return pageFlow.value?.requests?.firstOrNull { it.id == id }?.let { Outcome.Done(it) } ?: Outcome.Refused("no such request")
    }

    override suspend fun answer(id: Long, allow: Boolean, minutes: Int, reply: String): Outcome<LockRequest> {
        next?.let { return it }
        answers += id to allow
        return update(id) { it.copy(status = if (allow) LockRequestStatus.Allowed else LockRequestStatus.Denied, reply = reply, minutes = if (minutes > 0) minutes else it.minutes) }
    }

    override suspend fun cancel(id: Long): Outcome<LockRequest> = next ?: update(id) { it.copy(status = LockRequestStatus.Cancelled) }

    override suspend fun openWithoutAsking(lockId: Long, minutes: Int, message: String): Outcome<LockRequest> {
        next?.let { return it }
        val until = LockSamples.now.plusSeconds(minutes * 60L)
        pageFlow.value = pageFlow.value?.let { p -> p.copy(locks = p.locks.map { if (it.id == lockId) it.copy(openUntil = until) else it }) }
        val lock = pageFlow.value!!.locks.first { it.id == lockId }
        return Outcome.Done(
            LockRequest(99, lockId, lock.packageName, lock.label, LockRequestKind.Override, minutes, message, LockRequestStatus.Allowed, "", until, true, "Fran", LockSamples.now, LockSamples.now, null),
        )
    }

    override suspend fun savePushToken(token: String): Outcome<Unit> {
        tokens += token
        return Outcome.Done(Unit)
    }

    private fun update(id: Long, f: (LockRequest) -> LockRequest): Outcome<LockRequest> {
        val p = pageFlow.value!!
        val r = f(p.requests.first { it.id == id })
        pageFlow.value = p.copy(requests = p.requests.map { if (it.id == id) r else it })
        return Outcome.Done(r)
    }
}

class FakeAppLocks(setup: LockSetup = LockSetup(usageAccess = true, overlay = true, notifications = true)) : AppLocks {
    val setupFlow = MutableStateFlow(setup)
    var syncs = 0
    var openedUsage = false
    var openedOverlay = false

    override val setup: StateFlow<LockSetup> = setupFlow
    override fun checkSetup() {}
    override fun openUsageAccess() {
        openedUsage = true
    }
    override fun openOverlay() {
        openedOverlay = true
    }
    override suspend fun installedApps() = LockSamples.apps
    override suspend fun sync() {
        syncs++
    }
    override fun turnOff() {}
}
