package com.rudnam.samfran

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.rudnam.samfran.core.data.AppLocks
import com.rudnam.samfran.core.data.LocksRepository
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.model.LockPage
import com.rudnam.samfran.core.model.LockSetup
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/**
 * Locked apps: while this person has locked apps, a foreground service
 * (its notification says so) looks every half second, while the screen is
 * on, at which app is in front (Android's usage events, which need usage
 * access), and when it's a locked one that isn't open for a while, puts
 * [LockActivity] over it (starting an activity from the background needs
 * "display over other apps"). It reads the locks from the phone's copy
 * ([LocksRepository.page]), which the app and its push messages keep up to
 * date, and brings it up to date itself every few minutes. It stops by itself
 * when nothing is locked.
 */
@AndroidEntryPoint
class LockWatcher : Service() {
    @Inject lateinit var locks: LocksRepository

    @Inject lateinit var appLocks: AppLocks

    /** When this watcher started, for the check-ins: a later start than the last check-in means it was stopped in between. */
    private var startedAt = 0L

    /** The app was just updated: the first check-in says so, as that stop wasn't anyone's doing. */
    private var afterUpdate = false

    /** What the last check-in said the phone allows. */
    private var reported: LockSetup? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    @Volatile private var page: LockPage? = null

    /** The app in front, as the usage events last said. */
    private var front: String? = null
    private var readUpTo = 0L
    private var lastCover = 0L
    private var lastCovered: String? = null
    private var ticks = 0

    /** Tells the server how this phone is covering its locked apps (applock's check-ins). */
    private suspend fun checkIn() {
        appLocks.checkSetup()
        val setup = appLocks.setup.value
        val booted = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val r = locks.checkIn(
            setup.usageAccess, setup.overlay, watching = true,
            watchingSince = Instant.ofEpochMilli(startedAt), bootedAt = Instant.ofEpochMilli(booted),
            reason = if (afterUpdate) "updated" else "",
        )
        if (r is Outcome.Done) {
            reported = setup
            afterUpdate = false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val n = notification(this)
        // Android may refuse a foreground service started from the background: then there's no watching until the app opens.
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(NOTIFICATION, n)
        }.isSuccess
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.getBooleanExtra(EXTRA_UPDATED, false) == true) afterUpdate = true
        if (loop == null) {
            startedAt = System.currentTimeMillis()
            scope.launch { locks.page.collect { page = it } }
            scope.launch {
                var round = 0
                while (isActive) {
                    locks.refresh()
                    if (round++ % (CHECK_IN_MS / REFRESH_MS).toInt() == 0) checkIn()
                    delay(REFRESH_MS)
                }
            }
            loop = scope.launch { watch() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun watch() {
        val usage = getSystemService(UsageStatsManager::class.java)
        val power = getSystemService(PowerManager::class.java)
        readUpTo = System.currentTimeMillis() - 60_000
        while (scope.isActive) {
            val p = page
            if (p != null && p.mine.isEmpty()) {
                stopSelf()
                return
            }
            if (power.isInteractive && p != null) {
                runCatching { check(usage, p) } // usage access taken away: nothing to see until it's back
            }
            // A permission taken away (or given back) in Settings: the other person hears now, not in 15 minutes.
            if (++ticks % SETUP_TICKS == 0) {
                appLocks.checkSetup()
                if (reported != null && appLocks.setup.value != reported) checkIn()
            }
            delay(TICK_MS)
        }
    }

    private fun check(usage: UsageStatsManager, p: LockPage) {
        val now = System.currentTimeMillis()
        val events = usage.queryEvents(readUpTo, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == RESUMED) front = e.packageName
            if (e.timeStamp > readUpTo) readUpTo = e.timeStamp
        }
        val app = front ?: return
        if (app == packageName) return
        val lock = p.lockOf(app) ?: return
        if (lock.isOpen(Instant.ofEpochMilli(now))) return
        // Not again and again while the cover is coming up.
        if (app == lastCovered && now - lastCover < COVER_AGAIN_MS) return
        lastCovered = app
        lastCover = now
        runCatching { startActivity(LockActivity.intent(this, app, lock.label)) }
    }

    companion object {
        /** An activity coming to the front: ACTIVITY_RESUMED from Android 10, the same number as MOVE_TO_FOREGROUND before. */
        @Suppress("DEPRECATION")
        private const val RESUMED = UsageEvents.Event.MOVE_TO_FOREGROUND
        private const val CHANNEL = "lock_watch"
        private const val NOTIFICATION = 21
        private const val TICK_MS = 500L
        private const val REFRESH_MS = 5 * 60 * 1000L
        private const val CHECK_IN_MS = 15 * 60 * 1000L
        /** Every 30 seconds, what the phone allows is read again. */
        private const val SETUP_TICKS = 60
        private const val EXTRA_UPDATED = "updated"
        private const val COVER_AGAIN_MS = 2_000L
        private const val ACTION_STOP = "com.rudnam.samfran.LOCKS_STOP"

        /** [updated]: starting again after the app was updated. */
        fun start(c: Context, updated: Boolean = false) {
            runCatching { ContextCompat.startForegroundService(c, Intent(c, LockWatcher::class.java).putExtra(EXTRA_UPDATED, updated)) }
        }

        fun stop(c: Context) {
            runCatching { c.startService(Intent(c, LockWatcher::class.java).setAction(ACTION_STOP)) }
        }

        private fun notification(c: Context): Notification {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.channel_lock_watch), NotificationManager.IMPORTANCE_MIN))
            return Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_lock)
                .setContentTitle(c.getString(R.string.lock_watch_title))
                .setContentText(c.getString(R.string.lock_watch_text))
                .setContentIntent(ShiftPrompt.open(c, 30, "locks"))
                .setOngoing(true)
                .build()
        }
    }
}
