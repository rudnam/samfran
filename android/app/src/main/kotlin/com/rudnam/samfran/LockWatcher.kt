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
import androidx.core.content.ContextCompat
import com.rudnam.samfran.core.data.LocksRepository
import com.rudnam.samfran.core.model.LockPage
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    @Volatile private var page: LockPage? = null

    /** The app in front, as the usage events last said. */
    private var front: String? = null
    private var readUpTo = 0L
    private var lastCover = 0L
    private var lastCovered: String? = null

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
        if (loop == null) {
            scope.launch { locks.page.collect { page = it } }
            scope.launch {
                while (isActive) {
                    locks.refresh()
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
        private const val COVER_AGAIN_MS = 2_000L
        private const val ACTION_STOP = "com.rudnam.samfran.LOCKS_STOP"

        fun start(c: Context) {
            runCatching { ContextCompat.startForegroundService(c, Intent(c, LockWatcher::class.java)) }
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
