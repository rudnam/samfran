package com.rudnam.samfran.feature.us

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rudnam.samfran.core.data.AppLocks
import com.rudnam.samfran.core.data.LocksRepository
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.model.AppLock
import com.rudnam.samfran.core.model.InstalledApp
import com.rudnam.samfran.core.model.LockPage
import com.rudnam.samfran.core.model.LockRequestKind
import com.rudnam.samfran.core.model.LockSetup
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

data class LocksUiState(
    val page: LockPage? = null,
    val setup: LockSetup = LockSetup(),
    /** The phone's apps, for choosing one to lock; null until read. */
    val apps: List<InstalledApp>? = null,
    val query: String = "",
    val busy: Boolean = false,
    val problem: Outcome<Any>? = null,
    /** For each request waiting for an answer: the minutes to allow, if changed from what was asked. */
    val allowMinutes: Map<Long, Int> = emptyMap(),
    val replies: Map<Long, String> = emptyMap(),
    /** Asking to unlock this one for good, once confirmed. */
    val unlocking: AppLock? = null,
    val now: Instant = Instant.EPOCH,
) {
    /** The apps that match what's typed, leaving out those already locked. */
    val shownApps: List<InstalledApp>?
        get() {
            val locked = page?.mine.orEmpty().map { it.packageName }.toSet()
            val q = query.trim().lowercase()
            return apps?.filter { it.packageName !in locked && (q.isEmpty() || it.label.lowercase().contains(q)) }
        }
}

interface LocksActions {
    fun openUsageAccess()
    fun openOverlay()
    fun openWatchNotice()
    fun setAllowMinutes(request: Long, minutes: Int)
    fun setReply(request: Long, reply: String)
    fun answer(request: Long, allow: Boolean)
    fun lock(app: InstalledApp, done: () -> Unit = {})
    fun confirmUnlock(lock: AppLock?)
    fun askUnlock()
    fun cancel(request: Long)
    fun setQuery(query: String)
}

/** Locked apps (Us › Locked apps), and choosing an app to lock. */
@HiltViewModel
class LocksViewModel @Inject constructor(
    private val locks: LocksRepository,
    private val appLocks: AppLocks,
    private val clock: Clock,
) : ViewModel(), LocksActions {
    private val local = MutableStateFlow(LocksUiState(now = clock.instant()))

    val state: StateFlow<LocksUiState> = combine(local, locks.page, appLocks.setup) { s, page, setup ->
        s.copy(page = page, setup = setup)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        viewModelScope.launch {
            val r = locks.refresh()
            if (r !is Outcome.Done) local.update { it.copy(problem = r) }
        }
    }

    /** The page came back into view: Settings may have changed what's allowed, and answers may have come. */
    fun resumed() {
        appLocks.checkSetup()
        local.update { it.copy(now = clock.instant()) }
        viewModelScope.launch {
            locks.refresh()
            appLocks.sync()
        }
    }

    fun loadApps() {
        if (local.value.apps != null) return
        viewModelScope.launch {
            val apps = appLocks.installedApps()
            local.update { it.copy(apps = apps) }
        }
    }

    private fun run(block: suspend () -> Outcome<Any>, after: (LocksUiState) -> LocksUiState = { it }, done: () -> Unit = {}) {
        if (local.value.busy) return
        local.update { it.copy(busy = true, problem = null) }
        viewModelScope.launch {
            val r = block()
            local.update { s ->
                val next = s.copy(busy = false, problem = r.takeIf { it !is Outcome.Done }, now = clock.instant())
                if (r is Outcome.Done) after(next) else next
            }
            if (r is Outcome.Done) {
                appLocks.sync()
                done()
            }
        }
    }

    override fun openUsageAccess() = appLocks.openUsageAccess()

    override fun openOverlay() = appLocks.openOverlay()

    override fun openWatchNotice() = appLocks.openWatchNotice()

    override fun setAllowMinutes(request: Long, minutes: Int) = local.update { it.copy(allowMinutes = it.allowMinutes + (request to minutes)) }

    override fun setReply(request: Long, reply: String) = local.update { it.copy(replies = it.replies + (request to reply.take(200))) }

    override fun answer(request: Long, allow: Boolean) {
        val s = local.value
        run({ locks.answer(request, allow, s.allowMinutes[request] ?: 0, s.replies[request].orEmpty()) }, { it.copy(replies = it.replies - request, allowMinutes = it.allowMinutes - request) })
    }

    override fun lock(app: InstalledApp, done: () -> Unit) = run({ locks.lock(app) }, done = done)

    override fun confirmUnlock(lock: AppLock?) = local.update { it.copy(unlocking = lock) }

    override fun askUnlock() {
        val lock = local.value.unlocking ?: return
        run({ locks.ask(lock.id, LockRequestKind.Remove, 0, "") }, { it.copy(unlocking = null) })
    }

    override fun cancel(request: Long) = run({ locks.cancel(request) })

    override fun setQuery(query: String) = local.update { it.copy(query = query) }
}
