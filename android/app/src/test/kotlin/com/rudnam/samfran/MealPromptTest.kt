package com.rudnam.samfran

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.rudnam.samfran.core.model.MealKind
import com.rudnam.samfran.core.model.ScheduledMeal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class MealPromptTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `save schedules next alarm and receiving notifies and schedules the following one`() {
        val now = System.currentTimeMillis()
        val first = Instant.ofEpochMilli(now - 5 * 60 * 1000L) // 5 minutes ago: within LATE_MS
        val second = Instant.ofEpochMilli(now + 60 * 60 * 1000L) // 1 hour from now

        MealPrompt.setEnabled(context, true)
        assertTrue(MealPrompt.isEnabled(context))
        MealPrompt.save(
            context,
            listOf(
                ScheduledMeal(first, MealKind.Breakfast, "Early"),
                ScheduledMeal(second, MealKind.Lunch, "Early"),
            ),
        )

        val am = context.getSystemService(AlarmManager::class.java)
        val shadowAm = shadowOf(am)
        assertNotNull(shadowAm.nextScheduledAlarm)
        assertEquals(second.toEpochMilli(), shadowAm.nextScheduledAlarm.triggerAtTime)

        // Simulate firing ACTION_MEAL when `first` is due.
        MealPrompt.save(
            context,
            listOf(
                ScheduledMeal(first, MealKind.Breakfast, "Early"),
                ScheduledMeal(second, MealKind.Lunch, "Early"),
            ),
        )
        // Restore raw prefs with `first` included since save() pruned past items.
        ShiftPrompt.prefs(context).edit().putString(
            MealPrompt.MEALS,
            """[{"at":${first.toEpochMilli()},"meal":"breakfast","shift":"Early"},{"at":${second.toEpochMilli()},"meal":"lunch","shift":"Early"}]""",
        ).commit()

        MealPrompt().onReceive(context, Intent(MealPrompt.ACTION_MEAL))

        val nm = context.getSystemService(NotificationManager::class.java)
        val notifications = shadowOf(nm).allNotifications
        assertEquals(1, notifications.size)
        assertEquals("Time for breakfast", notifications[0].extras.getString("android.title"))

        // After receiving, `first` is pruned and `second` remains scheduled.
        assertEquals(listOf(second), MealPrompt.scheduledInstants(context))
        assertEquals(second.toEpochMilli(), shadowAm.nextScheduledAlarm.triggerAtTime)

        // Clear and disable.
        MealPrompt.clear(context)
        assertEquals(0, shadowOf(nm).allNotifications.size)
        MealPrompt.setEnabled(context, false)
        assertFalse(MealPrompt.isEnabled(context))
        assertNull(shadowAm.nextScheduledAlarm)
    }
}
