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
import androidx.core.content.edit
import com.rudnam.samfran.core.model.MealKind
import com.rudnam.samfran.core.model.ScheduledMeal
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant

/**
 * Local meal reminder alarms (breakfast, lunch, dinner) based on the shift
 * schedule. Uses inexact [AlarmManager.setAndAllowWhileIdle] like [ShiftPrompt]
 * so no extra permission is needed, and reschedules on boot or package update.
 */
class MealPrompt : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_MEAL && isEnabled(context)) {
            val now = System.currentTimeMillis()
            val due = loadItems(context).filter { it.at <= now && it.at > now - LATE_MS }
            for (item in due) {
                notifyMeal(context, item)
            }
        }
        schedule(context)
    }

    data class Item(val at: Long, val meal: MealKind, val shiftKey: String)

    companion object {
        const val ACTION_MEAL = "com.rudnam.samfran.MEAL_REMINDER"
        private const val CHANNEL = "meal_reminders"
        private const val NOTIFICATION_BASE = 50
        private const val ALARM_REQUEST_CODE = 20

        /** A meal reminder older than this isn't shown (phone was off). */
        private const val LATE_MS = 45 * 60 * 1000L

        /** Notifications auto-dismiss after 2 hours. */
        private const val SHOWN_MS = 2 * 60 * 60 * 1000L

        const val MEALS_ON = "meals-on"
        const val MEALS = "meals"

        private fun prefs(c: Context): SharedPreferences = ShiftPrompt.prefs(c)

        fun isEnabled(c: Context): Boolean = prefs(c).getBoolean(MEALS_ON, false)

        fun setEnabled(c: Context, on: Boolean) {
            prefs(c).edit { putBoolean(MEALS_ON, on) }
            if (!on) {
                save(c, emptyList())
                clear(c)
            }
        }

        private fun alarm(c: Context): PendingIntent {
            val i = Intent(c, MealPrompt::class.java).setAction(ACTION_MEAL)
            return PendingIntent.getBroadcast(
                c,
                ALARM_REQUEST_CODE,
                i,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        fun save(c: Context, items: List<ScheduledMeal>) {
            val arr = JSONArray()
            for (item in items) {
                arr.put(
                    JSONObject()
                        .put("at", item.at.toEpochMilli())
                        .put("meal", item.meal.api)
                        .put("shift", item.shiftKey),
                )
            }
            prefs(c).edit { putString(MEALS, arr.toString()) }
            schedule(c)
        }

        internal fun loadItems(c: Context): List<Item> {
            val raw = prefs(c).getString(MEALS, "[]") ?: "[]"
            val arr = try {
                JSONArray(raw)
            } catch (_: JSONException) {
                return emptyList()
            }
            val out = mutableListOf<Item>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val at = o.optLong("at", 0L)
                val meal = MealKind.entries.firstOrNull { it.api == o.optString("meal") } ?: continue
                val shiftKey = o.optString("shift", "")
                if (at > 0L) out += Item(at, meal, shiftKey)
            }
            return out
        }

        fun schedule(c: Context, nowMs: Long = System.currentTimeMillis()) {
            val left = if (isEnabled(c)) loadItems(c).filter { it.at > nowMs } else emptyList()
            val arr = JSONArray()
            for (item in left) {
                arr.put(
                    JSONObject()
                        .put("at", item.at)
                        .put("meal", item.meal.api)
                        .put("shift", item.shiftKey),
                )
            }
            prefs(c).edit { putString(MEALS, arr.toString()) }
            val am = c.getSystemService(AlarmManager::class.java)
            val next = left.minOfOrNull { it.at }
            if (next == null) {
                am.cancel(alarm(c))
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarm(c))
            }
        }

        private fun notifyMeal(c: Context, item: Item) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    c.getString(R.string.channel_meal_reminders),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
            val title = when (item.meal) {
                MealKind.Breakfast -> c.getString(R.string.meal_title_breakfast)
                MealKind.Lunch -> c.getString(R.string.meal_title_lunch)
                MealKind.Dinner -> c.getString(R.string.meal_title_dinner)
            }
            val shiftName = when (item.shiftKey) {
                "Early" -> c.getString(R.string.meal_shift_early)
                "Middle" -> c.getString(R.string.meal_shift_middle)
                "Late" -> c.getString(R.string.meal_shift_late)
                "Night" -> c.getString(R.string.meal_shift_night)
                "Off" -> c.getString(R.string.meal_shift_off)
                else -> ""
            }
            val open = ShiftPrompt.open(c, 21 + item.meal.ordinal, "shifts")
            val builder = Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_meal)
                .setContentTitle(title)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setTimeoutAfter(SHOWN_MS)
            if (shiftName.isNotEmpty()) {
                builder.setContentText(shiftName)
            }
            nm.notify(NOTIFICATION_BASE + item.meal.ordinal, builder.build())
        }

        fun clear(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            for (meal in MealKind.entries) {
                nm.cancel(NOTIFICATION_BASE + meal.ordinal)
            }
        }

        fun scheduledInstants(c: Context): List<Instant> = loadItems(c).map { Instant.ofEpochMilli(it.at) }
    }
}
