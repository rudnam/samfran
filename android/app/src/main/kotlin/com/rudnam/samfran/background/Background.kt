package com.rudnam.samfran.background

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.rudnam.samfran.ActivityJob
import com.rudnam.samfran.BusTripService
import com.rudnam.samfran.core.data.ActivityRepository
import com.rudnam.samfran.core.data.BusAlarm
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.data.Reminders
import com.rudnam.samfran.core.model.BusStop
import com.rudnam.samfran.core.model.Buzz
import com.rudnam.samfran.core.model.newestId
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** The bus-stop alarm: [BusTripService] does the watching. */
@Singleton
class AndroidBusAlarm @Inject constructor(@ApplicationContext private val context: Context) : BusAlarm {
    override val status: StateFlow<com.rudnam.samfran.core.model.TripStatus> = BusTripService.status

    override fun start(stop: BusStop) {
        val i = Intent(context, BusTripService::class.java)
            .putExtra(BusTripService.EXTRA_LAT, stop.lat)
            .putExtra(BusTripService.EXTRA_LNG, stop.lng)
            .putExtra(BusTripService.EXTRA_RADIUS, stop.alertMeters.toFloat())
            .putExtra(BusTripService.EXTRA_NAME, stop.name)
            .putExtra(BusTripService.EXTRA_BUZZ, stop.buzz.api)
        ContextCompat.startForegroundService(context, i)
    }

    override fun stop() {
        context.startService(Intent(context, BusTripService::class.java).setAction(BusTripService.ACTION_STOP))
    }

    override fun stopBuzz() = BusTripService.stopBuzz(context)

    override fun test(stopName: String, buzz: Buzz) = BusTripService.ring(context, stopName, buzz)

    @SuppressLint("MissingPermission") // checked first
    override suspend fun here(): Pair<Double, Double>? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val lm = context.getSystemService(LocationManager::class.java)
        val provider = if (fine && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
        return try {
            val loc = if (Build.VERSION.SDK_INT >= 30) {
                withTimeoutOrNull(20_000) {
                    suspendCancellableCoroutine { cont ->
                        val cancel = CancellationSignal()
                        cont.invokeOnCancellation { cancel.cancel() }
                        lm.getCurrentLocation(provider, cancel, context.mainExecutor) { cont.resume(it) }
                    }
                }
            } else {
                lm.getLastKnownLocation(provider)
            }
            loc?.let { it.latitude to it.longitude }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // location off, or no provider
        }
    }
}

/**
 * What the phone does while the app is closed: [ActivityJob] looks for the
 * other person's changes. It keeps its settings in the "reminders"
 * preferences, as the Capacitor build did, so they carry on through the
 * update.
 */
@Singleton
class AndroidReminders @Inject constructor(
    @ApplicationContext private val context: Context,
    private val activity: ActivityRepository,
    private val clock: Clock,
) : Reminders {
    private val prefs = ActivityJob.prefs(context)

    init {
        val old = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
        if (old.contains("bus-prompt") || old.contains("activity-alerts")) {
            old.edit { remove("bus-prompt").remove("activity-alerts") }
        }
        forgetShiftPrompts()
    }

    /**
     * There used to be a "Start bus alarm?" notification when a shift ended:
     * once, after the update, take away its alarm, its notification, its
     * channel in the phone's settings, and what it kept.
     */
    private fun forgetShiftPrompts() {
        val keys = listOf("ends", "stop", "stops", "stop-count", "prompts")
        if (keys.none(prefs::contains)) return
        runCatching {
            val i = Intent("com.rudnam.samfran.SHIFT_ENDED").setComponent(ComponentName(context.packageName, "com.rudnam.samfran.ShiftPrompt"))
            PendingIntent.getBroadcast(context, 10, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)
                ?.let(context.getSystemService(AlarmManager::class.java)::cancel)
            context.getSystemService(NotificationManager::class.java).run {
                cancel(3)
                deleteNotificationChannel("shift_end")
            }
        }
        prefs.edit { keys.forEach(::remove) }
    }

    private val alerts = MutableStateFlow(prefs.getBoolean(ActivityJob.ALERTS, false))
    override val activityAlerts: StateFlow<Boolean> = alerts

    private val allowed: Boolean get() = NotificationManagerCompat.from(context).areNotificationsEnabled()

    override suspend fun setActivityAlerts(on: Boolean): Boolean {
        val now = on && allowed
        if (now) {
            // Only changes after this one are news.
            val newest = (activity.refresh() as? Outcome.Done)?.value?.newestId() ?: 0
            prefs.edit { putLong(ActivityJob.SEEN, maxOf(prefs.getLong(ActivityJob.SEEN, 0), newest)) }
        }
        prefs.edit { putBoolean(ActivityJob.ALERTS, now) }
        ActivityJob.schedule(context, now)
        alerts.value = now
        return now
    }

    @Volatile private var last = 0L

    override suspend fun sync(now: Boolean) {
        if (!now && clock.millis() - last < 5 * 60 * 1000) return
        last = clock.millis()
        if (alerts.value) {
            ActivityJob.schedule(context, true)
            (activity.refresh() as? Outcome.Done)?.value?.newestId()?.let { newest ->
                // Never backwards: the job may have notified about newer ones.
                prefs.edit { putLong(ActivityJob.SEEN, maxOf(prefs.getLong(ActivityJob.SEEN, 0), newest)) }
            }
        }
    }

    override fun clearNotifications() {
        BusTripService.stopBuzz(context)
        ActivityJob.clear(context)
    }

    override fun turnOff() {
        prefs.edit {
            putBoolean(ActivityJob.ALERTS, false)
            putLong(ActivityJob.SEEN, 0)
        }
        ActivityJob.schedule(context, false)
        alerts.value = false
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface BackgroundModule {
    @Binds fun busAlarm(impl: AndroidBusAlarm): BusAlarm

    @Binds fun reminders(impl: AndroidReminders): Reminders
}
