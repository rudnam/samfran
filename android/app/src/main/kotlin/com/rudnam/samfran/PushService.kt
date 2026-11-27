package com.rudnam.samfran

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.rudnam.samfran.core.data.AppLocks
import com.rudnam.samfran.core.data.LocksRepository
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.data.Reminders
import com.rudnam.samfran.core.datastore.SessionStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Firebase Cloud Messaging (internal/push): the server's messages carry
 * data only, and this shows them. Each brings the phone's copy of the locks
 * up to date first, so the watcher and the cover see an answer at once.
 * - lock_request: the other person asks to open (or unlock) one of their
 *   apps, with Allow and Deny on the notification ([LockAnswerReceiver]).
 * - lock_answer: the answer to this person's asking.
 * - lock_override: the other person opened an app without asking.
 * - lock_cancelled: a request was taken back; its notification goes.
 * - lock_added: the other person locked an app.
 * - lock_check: the other person's phone stopped (or started again) covering
 *   their locked apps, was stopped for a while, or hasn't checked in for a day.
 */
@AndroidEntryPoint
class PushService : FirebaseMessagingService() {
    @Inject lateinit var locks: LocksRepository

    @Inject lateinit var appLocks: AppLocks

    @Inject lateinit var reminders: Reminders

    @Inject lateinit var session: SessionStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch { if (session.currentToken() != null) locks.savePushToken(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["type"] == "shifts_changed") {
            kotlinx.coroutines.runBlocking {
                if (session.currentToken() != null) reminders.sync(now = true)
            }
            return
        }
        // Run on this service's own thread, which may block: the notification after the copy is fresh.
        kotlinx.coroutines.runBlocking {
            if (session.currentToken() == null) return@runBlocking
            locks.refresh()
            if (data["type"] == "lock_added") appLocks.sync()
        }
        LockNotifications.show(this, data)
    }
}

/** Locked apps' notifications. */
object LockNotifications {
    private const val CHANNEL = "locks"

    /** Each request's notification is tagged with its id, so its answer or cancelling replaces it. */
    private const val TAG = "lock-request"

    /** The other phone's check-ins share one notification (request ids are never this). */
    private const val CHECK_NOTIFICATION = -1

    fun show(c: Context, data: Map<String, String>) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.channel_locks), NotificationManager.IMPORTANCE_HIGH))
        val id = data["request_id"]?.toLongOrNull() ?: 0L
        val who = data["who"].orEmpty()
        val label = data["label"].orEmpty()
        val minutes = data["minutes"]?.toIntOrNull() ?: 0
        fun quoted(s: String?) = s?.takeIf { it.isNotBlank() }?.let { c.getString(R.string.notify_quoted, it) }
        val builder = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setContentIntent(ShiftPrompt.open(c, 31 + (id % 1000).toInt(), "locks"))
            .setAutoCancel(true)
        when (data["type"]) {
            "lock_request" -> {
                val remove = data["kind"] == "remove"
                builder
                    .setContentTitle(if (remove) c.getString(R.string.notify_lock_remove, who, label) else c.getString(R.string.notify_lock_request, who, label, minutes))
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setTimeoutAfter(15 * 60 * 1000L)
                    .addAction(Notification.Action.Builder(null, c.getString(R.string.lock_allow), LockAnswerReceiver.intent(c, id, true)).build())
                    .addAction(Notification.Action.Builder(null, c.getString(R.string.lock_deny), LockAnswerReceiver.intent(c, id, false)).build())
                quoted(data["message"])?.let { builder.setContentText(it) }
                nm.notify(TAG, id.toInt(), builder.build())
            }
            "lock_answer" -> {
                val allowed = data["status"] == "allowed"
                val title = when {
                    !allowed -> c.getString(R.string.notify_lock_denied, who, label)
                    data["kind"] == "remove" -> c.getString(R.string.notify_lock_unlocked, who, label)
                    else -> c.getString(R.string.notify_lock_allowed, who, label, minutes)
                }
                builder.setContentTitle(title)
                quoted(data["reply"])?.let { builder.setContentText(it) }
                // Allowed: a tap opens the app itself.
                val app = data["package"]
                val launch = app?.let { c.packageManager.getLaunchIntentForPackage(it) }
                if (allowed && launch != null) {
                    builder.setContentIntent(PendingIntent.getActivity(c, 40 + (id % 1000).toInt(), launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                }
                nm.notify(TAG, id.toInt(), builder.build())
            }
            "lock_override" -> {
                builder.setContentTitle(c.getString(R.string.notify_lock_override, who, label, minutes))
                quoted(data["message"])?.let { builder.setContentText(it) }
                nm.notify(TAG, id.toInt(), builder.build())
            }
            "lock_added" -> {
                builder.setContentTitle(c.getString(R.string.notify_lock_added, who, label))
                nm.notify(TAG, (label.hashCode() and 0xffff) + 100_000, builder.build())
            }
            "lock_cancelled" -> nm.cancel(TAG, id.toInt())
            "lock_check" -> {
                fun at(key: String) = data[key]?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }?.let { when_ ->
                    when_.atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT))
                }.orEmpty()
                val title = when (data["state"]) {
                    "on" -> c.getString(R.string.notify_check_on, who)
                    "restarted" -> c.getString(R.string.notify_check_restarted, who, at("from"), at("to"))
                    "quiet" -> c.getString(R.string.notify_check_quiet, who, at("since"))
                    else -> when (data["reason"]) {
                        "usage_access" -> c.getString(R.string.notify_check_usage, who)
                        "overlay" -> c.getString(R.string.notify_check_overlay, who)
                        "signed_out" -> c.getString(R.string.notify_check_signed_out, who)
                        else -> c.getString(R.string.notify_check_stopped, who)
                    }
                }
                builder.setContentTitle(title).setStyle(Notification.BigTextStyle().bigText(title))
                if (data["state"] == "quiet") builder.setContentText(c.getString(R.string.notify_check_quiet_text))
                // One at a time: the latest says how it is now.
                nm.notify(TAG, CHECK_NOTIFICATION, builder.build())
            }
        }
    }

    /** A request's notification, after it's been answered from the notification itself. */
    fun answered(c: Context, id: Long, text: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.notify(
            TAG,
            id.toInt(),
            Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_lock)
                .setContentTitle(text)
                .setContentIntent(ShiftPrompt.open(c, 31 + (id % 1000).toInt(), "locks"))
                .setAutoCancel(true)
                .setTimeoutAfter(60_000)
                .build(),
        )
    }
}

/** Allow or Deny, tapped on the notification of the other person's asking. */
@AndroidEntryPoint
class LockAnswerReceiver : BroadcastReceiver() {
    @Inject lateinit var locks: LocksRepository

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, 0)
        val allow = intent.getBooleanExtra(EXTRA_ALLOW, false)
        if (id == 0L) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val r = locks.answer(id, allow)
                val text = when {
                    r !is Outcome.Done -> context.getString(R.string.lock_answer_failed)
                    allow -> context.getString(R.string.lock_answered_allow)
                    else -> context.getString(R.string.lock_answered_deny)
                }
                LockNotifications.answered(context, id, text)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_ALLOW = "allow"

        fun intent(c: Context, id: Long, allow: Boolean): PendingIntent {
            val i = Intent(c, LockAnswerReceiver::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_ALLOW, allow)
            // One for each request and answer.
            val code = ((id % 100_000).toInt() * 2) + if (allow) 1 else 0
            return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}

/** After the phone restarts or the app updates: the watcher again, if there's anything to watch. */
@AndroidEntryPoint
class LockBoot : BroadcastReceiver() {
    @Inject lateinit var appLocks: AppLocks

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                appLocks.sync(updated = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED)
            } finally {
                pending.finish()
            }
        }
    }
}
