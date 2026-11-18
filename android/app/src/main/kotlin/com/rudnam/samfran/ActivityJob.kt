package com.rudnam.samfran

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import com.rudnam.samfran.core.data.ActivityRepository
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.datastore.SessionStore
import com.rudnam.samfran.core.model.ActivityEntry
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Tells one of you when the other changes something you share: about every
 * half hour, while the phone is online, it asks the server for the recent
 * changes (GET /api/activity, signed in as the app is) and shows a
 * notification for the other person's new places, visits, ratings and saved
 * shifts. Android runs it when it suits the battery, so it can be later
 * than that. It asks the server itself rather than being pushed to, which
 * needs no Google services. Switched on from the Us tab.
 *
 * (The class and its job id keep the Capacitor build's: a job scheduled
 * before the update runs this.)
 */
@AndroidEntryPoint
class ActivityJob : JobService() {
    @Inject lateinit var session: SessionStore

    @Inject lateinit var activity: ActivityRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch {
            runCatching { check() } // offline, signed out, or an answer it can't read: next time
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return false
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private suspend fun check() {
        val p = prefs(this)
        if (!p.getBoolean(ALERTS, false) || session.currentToken() == null) return
        val list = (activity.refresh() as? Outcome.Done)?.value ?: return
        val seen = p.getLong(SEEN, 0)
        val fresh = list.filter { it.id > seen }
        if (fresh.isEmpty()) return
        p.edit { putLong(SEEN, fresh.maxOf { it.id }) }
        // Newest first, as the server lists them.
        val lines = fresh.filter { !it.me }.mapNotNull { describe(this, it) }
        if (lines.isNotEmpty()) show(this, lines)
    }

    companion object {
        private const val JOB = 20
        private const val CHANNEL = "changes"
        private const val NOTIFICATION = 4
        private const val EVERY_MS = 30 * 60 * 1000L

        /** In [prefs]: alerts are on, and the newest change already shown or notified. */
        const val ALERTS = "alerts"
        const val SEEN = "seen"

        /** The "reminders" preferences (RemindersPlugin's in the Capacitor build). */
        fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("reminders", Context.MODE_PRIVATE)

        /** Opens the app at <package>://path, e.g. us. */
        private fun open(c: Context, requestCode: Int, path: String): PendingIntent {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(c.packageName + "://" + path), c, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(c, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        fun schedule(c: Context, on: Boolean) {
            // The phone refusing the job (a limit, a security rule) must not crash the app: the alerts just won't arrive.
            runCatching {
                val js = c.getSystemService(JobScheduler::class.java)
                if (!on) {
                    js.cancel(JOB)
                    return
                }
                if (js.getPendingJob(JOB) != null) return // setting it again would start the half hour over
                js.schedule(
                    JobInfo.Builder(JOB, ComponentName(c, ActivityJob::class.java))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPeriodic(EVERY_MS)
                        .setPersisted(true) // across restarts of the phone
                        .build(),
                )
            }
        }

        /** Takes the notification away: SamFran was opened. */
        fun clear(c: Context) = c.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)

        /** One change in words, or null for a kind that isn't worth a notification. */
        fun describe(c: Context, e: ActivityEntry): String? {
            val who = e.who.ifEmpty { c.getString(R.string.notify_change_someone) }
            val name = e.details.name
            return when (e.kind) {
                "restaurant_added" -> c.getString(if (e.details.been) R.string.notify_change_added_been else R.string.notify_change_added_want, who, name)
                "visit_logged" -> c.getString(R.string.notify_change_visit, who, name)
                "rated" -> if (e.details.stars > 0) c.getString(R.string.notify_change_rated, who, name, e.details.stars) else null
                "shifts_saved" -> c.getString(R.string.notify_change_shifts, who)
                else -> null
            }
        }

        private fun show(c: Context, lines: List<String>) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.channel_changes), NotificationManager.IMPORTANCE_DEFAULT))
            val b = Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_bus)
                .setContentTitle(lines[0])
                .setContentIntent(open(c, 13, "us"))
                .setAutoCancel(true)
            if (lines.size > 1) {
                b.setContentText(c.resources.getQuantityString(R.plurals.notify_change_more, lines.size - 1, lines.size - 1))
                b.setStyle(Notification.InboxStyle().also { style -> lines.forEach(style::addLine) })
            }
            nm.notify(NOTIFICATION, b.build())
        }
    }
}
