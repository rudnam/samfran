package com.rudnam.samfran.core.data

import com.rudnam.samfran.core.common.Dispatcher
import com.rudnam.samfran.core.common.SamFranDispatchers
import com.rudnam.samfran.core.model.InstalledApp
import com.rudnam.samfran.core.model.LockPage
import com.rudnam.samfran.core.model.LockRequest
import com.rudnam.samfran.core.model.LockRequestKind
import com.rudnam.samfran.core.model.LockSetup
import com.rudnam.samfran.core.network.SamFranNetwork
import com.rudnam.samfran.core.network.model.NetworkLockPage
import com.rudnam.samfran.core.network.model.NetworkLockRequest
import com.rudnam.samfran.core.network.model.asModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Locked apps (internal/applock): both people's locks and the latest
 * requests, kept on the phone so the watcher (the app's LockWatcher) knows
 * what to cover without asking the server each time. Every change brings
 * the copy up to date.
 */
interface LocksRepository {
    val page: Flow<LockPage?>

    suspend fun refresh(): Outcome<LockPage>

    /** Locks one of this phone's apps. */
    suspend fun lock(app: InstalledApp): Outcome<LockPage>

    /** Asks the other person to open [lockId] for [minutes], or ([kind] Remove) to unlock it for good. */
    suspend fun ask(lockId: Long, kind: LockRequestKind, minutes: Int, message: String): Outcome<LockRequest>

    suspend fun request(id: Long): Outcome<LockRequest>

    /** The other person's answer; [minutes] 0 allows what was asked. */
    suspend fun answer(id: Long, allow: Boolean, minutes: Int = 0, reply: String = ""): Outcome<LockRequest>

    /** Takes back a request still waiting. */
    suspend fun cancel(id: Long): Outcome<LockRequest>

    /** Opens [lockId] now without asking; the other person is told. */
    suspend fun openWithoutAsking(lockId: Long, minutes: Int, message: String): Outcome<LockRequest>

    /** This phone's push address, for the other person's asking and answers. */
    suspend fun savePushToken(token: String): Outcome<Unit>
}

@Singleton
internal class OfflineFirstLocksRepository @Inject constructor(
    private val network: SamFranNetwork,
    private val documents: Documents,
    @Dispatcher(SamFranDispatchers.IO) private val io: CoroutineDispatcher,
) : LocksRepository {
    override val page: Flow<LockPage?> = documents.observe(Keys.LOCKS, NetworkLockPage.serializer()) { it.asModel() }

    override suspend fun refresh() = kept { network.locks() }

    override suspend fun lock(app: InstalledApp) = kept { network.addLock(app.packageName, app.label) }

    override suspend fun ask(lockId: Long, kind: LockRequestKind, minutes: Int, message: String) =
        changed { network.askLock(lockId, kind.api, if (kind == LockRequestKind.Remove) 0 else minutes, message.trim()) }

    override suspend fun request(id: Long) = changed { network.lockRequest(id) }

    override suspend fun answer(id: Long, allow: Boolean, minutes: Int, reply: String) =
        changed { network.answerLock(id, allow, minutes, reply.trim()) }

    override suspend fun cancel(id: Long) = changed { network.cancelLockRequest(id) }

    override suspend fun openWithoutAsking(lockId: Long, minutes: Int, message: String) =
        changed { network.overrideLock(lockId, minutes, message.trim()) }

    override suspend fun savePushToken(token: String) = outcome(io) { network.savePushToken(token) }

    private suspend fun kept(block: suspend () -> NetworkLockPage) = outcome(io) {
        block().also { documents.put(Keys.LOCKS, NetworkLockPage.serializer(), it) }.asModel()
    }

    /** A request's call, then the page again (best effort), so the phone's copy says what changed. */
    private suspend fun changed(block: suspend () -> NetworkLockRequest) = outcome(io) {
        val r = block()
        runCatching { documents.put(Keys.LOCKS, NetworkLockPage.serializer(), network.locks()) }
        r.asModel()
    }
}

/**
 * What locking needs from the phone (the app's AndroidAppLocks): what it
 * allows SamFran, the apps on it, and the watcher that covers a locked app
 * when it opens.
 */
interface AppLocks {
    val setup: StateFlow<LockSetup>

    /** Reads again what the phone allows (the person may have just changed it in Settings). */
    fun checkSetup()

    /** Android's settings page for usage access. */
    fun openUsageAccess()

    /** Android's settings page for displaying over other apps. */
    fun openOverlay()

    /** The apps on this phone that open from the launcher, by name, leaving out SamFran itself. */
    suspend fun installedApps(): List<InstalledApp>

    /**
     * Starts the watcher while this person has locked apps and the phone
     * allows it, stops it otherwise, and gives the server this phone's push address.
     */
    suspend fun sync()

    /** Signing out: no more covering or pushes for this person. */
    fun turnOff()
}
