package com.rudnam.samfran

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * "Start bus alarm?" when a shift ends. The app gives the times shifts end,
 * each with the stop to get off at after it ([Reminders] keeps them in the
 * "reminders" preferences); this sets an alarm for the next one, and when it
 * goes off shows a notification naming the stop, whose button opens the Bus
 * tab and starts the trip there (<package>://bus?start=1&stop=<id>), and with
 * more than one stop, another to choose first (<package>://bus?pick=1).
 * Then it sets the alarm for the one after. The alarm is inexact (Android
 * may run it a few minutes late), which needs no special permission. Alarms
 * don't survive a restart of the phone, so it sets them again then.
 *
 * (The class, its action and the preferences keep the Capacitor build's
 * names: an alarm set before the update still finds this.)
 */
class ShiftPrompt : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_ASK) {
            val now = System.currentTimeMillis()
            ends(context).filter { it <= now && it > now - LATE_MS }.maxOrNull()?.let { ask(context, it) }
        }
        // Also after the phone restarts (BOOT_COMPLETED), and for the next shift.
        schedule(context)
    }

    companion object {
        const val ACTION_ASK = "com.rudnam.samfran.SHIFT_ENDED"
        private const val CHANNEL = "shift_end"
        private const val NOTIFICATION = 3

        /** A shift that ended longer ago than this isn't asked about (the phone was off). */
        private const val LATE_MS = 45 * 60 * 1000L

        /** The question goes away by itself after this. */
        private const val SHOWN_MS = 90 * 60 * 1000L

        // The "reminders" preferences (RemindersPlugin's in the Capacitor build).
        const val PREFS = "reminders"
        /** The times to ask, ms since 1970, joined with commas. */
        const val ENDS = "ends"
        /** The stop's name, from before there could be several: for a time kept then. */
        const val STOP = "stop"
        /** For each time in [ENDS], the stop: a JSON array of {at, id, name}. */
        const val STOPS = "stops"
        /** How many stops there are: "Other stop" is for more than one. */
        const val STOP_COUNT = "stop-count"
        /**
         * "Ask when a shift ends" was on for this phone, before it was each
         * stop's (the Capacitor build kept this in its own preferences). Read
         * once, to switch it on for the stops (AndroidReminders), then gone.
         */
        const val PROMPTS = "prompts"

        fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun ends(c: Context): List<Long> =
            prefs(c).getString(ENDS, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }

        private fun alarm(c: Context): PendingIntent {
            val i = Intent(c, ShiftPrompt::class.java).setAction(ACTION_ASK)
            return PendingIntent.getBroadcast(c, 10, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        /** A shift's end, and the stop to get off at after it. */
        data class End(val at: Long, val stopId: Long, val stopName: String)

        /** Keeps when to ask (none turns it off) and where to, and sets the alarm. */
        fun save(c: Context, ends: List<End>, stopCount: Int) {
            val stops = JSONArray()
            for (e in ends) stops.put(JSONObject().put("at", e.at).put("id", e.stopId).put("name", e.stopName))
            prefs(c).edit {
                putString(ENDS, ends.joinToString(",") { it.at.toString() })
                putString(STOPS, stops.toString())
                putInt(STOP_COUNT, stopCount)
                remove(STOP)
            }
            schedule(c)
        }

        /** The stop kept for the shift that ended [at], if any. */
        private fun stopFor(c: Context, at: Long): End? {
            val stops = try {
                JSONArray(prefs(c).getString(STOPS, "[]"))
            } catch (e: JSONException) {
                return null
            }
            for (i in 0 until stops.length()) {
                val o = stops.optJSONObject(i) ?: continue
                if (o.optLong("at") == at) return End(at, o.optLong("id"), o.optString("name"))
            }
            return null
        }

        /** Sets the alarm for the next time a shift ends, forgetting the ones that have passed. */
        fun schedule(c: Context) {
            val now = System.currentTimeMillis()
            val left = ends(c).filter { it > now }
            prefs(c).edit { putString(ENDS, left.joinToString(",")) }
            val am = c.getSystemService(AlarmManager::class.java)
            val next = left.minOrNull()
            if (next == null) am.cancel(alarm(c)) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarm(c))
        }

        /** Opens the app at <package>://path, e.g. bus?start=1. */
        fun open(c: Context, requestCode: Int, path: String): PendingIntent {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(c.packageName + "://" + path), c, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(c, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun ask(c: Context, at: Long) {
            if (BusTripService.status.value.running) return // already on the way
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.channel_shift_end), NotificationManager.IMPORTANCE_HIGH))
            val end = stopFor(c, at)
            val stop = end?.stopName ?: prefs(c).getString(STOP, "").orEmpty()
            val start = if (end != null && end.stopId > 0) "bus?start=1&stop=${end.stopId}" else "bus?start=1"
            val b = Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_bus)
                .setContentTitle(c.getString(R.string.shift_end_title))
                .setContentText(if (stop.isEmpty()) c.getString(R.string.shift_end_text) else c.getString(R.string.shift_end_text_named, stop))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(open(c, 12, "bus")) // a tap on the rest of it opens the Bus tab without starting
                .addAction(Notification.Action.Builder(null, c.getString(R.string.shift_end_start), open(c, 11, start)).build())
                .setAutoCancel(true)
                .setTimeoutAfter(SHOWN_MS)
            if (prefs(c).getInt(STOP_COUNT, 1) > 1) {
                b.addAction(Notification.Action.Builder(null, c.getString(R.string.shift_end_other), open(c, 13, "bus?pick=1")).build())
            }
            nm.notify(NOTIFICATION, b.build())
        }

        /** Takes the question away: SamFran was opened. */
        fun clear(c: Context) = c.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
    }
}
