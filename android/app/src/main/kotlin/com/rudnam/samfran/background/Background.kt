package com.rudnam.samfran.background

import android.Manifest
import android.annotation.SuppressLint
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
import com.rudnam.samfran.ShiftPrompt
import com.rudnam.samfran.core.data.ActivityRepository
import com.rudnam.samfran.core.data.BusAlarm
import com.rudnam.samfran.core.data.BusRepository
import com.rudnam.samfran.core.data.Outcome
import com.rudnam.samfran.core.data.Reminders
import com.rudnam.samfran.core.data.ShiftsRepository
import com.rudnam.samfran.core.data.StopInput
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

/** The ride alarm: [BusTripService] does the watching. */
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
            .putExtra(BusTripService.EXTRA_SOUND_AFTER, stop.soundAfter)
        ContextCompat.startForegroundService(context, i)
    }

    override fun stop() {
        context.startService(Intent(context, BusTripService::class.java).setAction(BusTripService.ACTION_STOP))
    }

    override fun stopBuzz() = BusTripService.stopBuzz(context)

    override fun test(stopName: String, buzz: Buzz) = BusTripService.ring(context, stopName, buzz)

    /** Six seconds of the alarm sound, rising faster than on a ride so it's heard at full volume. */
    override fun hearSound() = BusTripService.sound(context, riseMs = 4_000, forMs = 6_000)

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
 * What the phone does while the app is closed: [ShiftPrompt] asks "Start
 * bus alarm?" at the times kept here, and [ActivityJob] looks for the other
 * person's changes. Both keep their settings in the "reminders" preferences,
 * as the Capacitor build did, so they carry on through the update; that
 * build kept whether the shift question was on in Capacitor's own
 * preferences ("bus-prompt"), which is read here once.
 */
@Singleton
class AndroidReminders @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shifts: ShiftsRepository,
    private val bus: BusRepository,
    private val activity: ActivityRepository,
    private val clock: Clock,
) : Reminders {
    private val prefs = ShiftPrompt.prefs(context)

    init {
        val old = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
        if (old.contains("bus-prompt") || old.contains("activity-alerts")) {
            if (old.getString("bus-prompt", null) == "1") prefs.edit { putBoolean(ShiftPrompt.PROMPTS, true) }
            old.edit { remove("bus-prompt").remove("activity-alerts") }
        }
    }

    private val alerts = MutableStateFlow(prefs.getBoolean(ActivityJob.ALERTS, false))
    override val activityAlerts: StateFlow<Boolean> = alerts

    private val allowed: Boolean get() = NotificationManagerCompat.from(context).areNotificationsEnabled()

    override fun notificationsAllowed() = allowed

    /**
     * The question used to be switched on for the phone ([ShiftPrompt.PROMPTS]);
     * now it's each stop's, off for all until switched on (the server's
     * migration 023). A phone that had it on switches it on for its person's
     * stops, once.
     */
    private suspend fun movePhoneSwitch() {
        if (!prefs.getBoolean(ShiftPrompt.PROMPTS, false)) return
        val all = (bus.refresh() as? Outcome.Done)?.value ?: return
        for (s in all.stops.filter { !it.ask }) {
            if (bus.change(s.id, StopInput(s.name, s.lat, s.lng, s.alertMeters, ask = true)) !is Outcome.Done) return
        }
        prefs.edit { remove(ShiftPrompt.PROMPTS) }
    }

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

    /**
     * Gives [ShiftPrompt] the times shifts end from now on, each with the stop
     * for that kind of shift (or else the one used last), leaving out those
     * whose stop doesn't ask; false if they can't be had. Without a stop
     * there's nothing to start.
     */
    private suspend fun sendShiftEnds(): Boolean {
        val all = (bus.refresh() as? Outcome.Done)?.value ?: return false
        if (all.stops.none { it.ask }) {
            ShiftPrompt.save(context, emptyList(), all.stops.size)
            return true
        }
        val upcoming = (shifts.upcomingShifts(clock.instant()) as? Outcome.Done)?.value ?: return false
        val ends = upcoming.mapNotNull { day ->
            val stop = all.pick(day)?.takeIf { it.ask } ?: return@mapNotNull null
            ShiftPrompt.Companion.End(day.end!!.toInstant().toEpochMilli(), stop.id, stop.name)
        }
        ShiftPrompt.save(context, ends, all.stops.size)
        return true
    }

    @Volatile private var last = 0L

    override suspend fun sync(now: Boolean) {
        if (!now && clock.millis() - last < 5 * 60 * 1000) return
        last = clock.millis()
        movePhoneSwitch()
        if (allowed) sendShiftEnds() else ShiftPrompt.save(context, emptyList(), 0)
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
        ShiftPrompt.clear(context)
        ActivityJob.clear(context)
    }

    override fun turnOff() {
        prefs.edit {
            remove(ShiftPrompt.PROMPTS)
            putBoolean(ActivityJob.ALERTS, false)
            putLong(ActivityJob.SEEN, 0)
        }
        ShiftPrompt.save(context, emptyList(), 0)
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
