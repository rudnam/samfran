package com.rudnam.samfran

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.rudnam.samfran.core.model.Buzz
import com.rudnam.samfran.core.model.Distances
import com.rudnam.samfran.core.model.TripStatus
import com.rudnam.samfran.widget.Widgets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A ride, by bus or train: while it runs, the phone watches its position
 * (GPS, the network as a fallback) and, once it's within the alert distance
 * of the stop, buzzes: the phone vibrates the way the person's buzz says,
 * until "Stop" is tapped, the notification is swiped away or SamFran is
 * opened (or for ten minutes at most). If it isn't stopped within their
 * sound_after, the phone's alarm sound joins in, rising from quiet; it
 * plays as an alarm, so even on silent. The service stays until then, so the buzzing isn't cut short.
 * It's a foreground service, so it shows a notification for as long as it
 * runs, and Android doesn't stop it while the screen is off. It stops itself
 * after ringing, or after three hours. Positions never leave the phone.
 *
 * (The class keeps the Capacitor build's name: a trip running during the
 * update is the same service.)
 */
class BusTripService : Service(), LocationListener {
    private var lat = 0.0
    private var lng = 0.0
    private var radius = 800f
    private var buzz = Buzz.Strong
    private var soundAfter = 0

    /** The first distance the phone knew this ride, where the progress line starts (-1: none yet). */
    private var from = -1f
    private var locations: LocationManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
        lng = intent.getDoubleExtra(EXTRA_LNG, 0.0)
        radius = intent.getFloatExtra(EXTRA_RADIUS, 800f)
        stopName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        buzz = Buzz.from(intent.getStringExtra(EXTRA_BUZZ))
        soundAfter = intent.getIntExtra(EXTRA_SOUND_AFTER, 0)
        from = -1f
        channels(this)
        val n = tripNotification(-1f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(TRIP_NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(TRIP_NOTIFICATION, n)
        }
        state.value = TripStatus(running = true, distance = -1f, buzzing = state.value.buzzing)
        Widgets.updateBus(this)
        val lm = getSystemService(LocationManager::class.java).also { locations = it }
        try {
            lm.removeUpdates(this)
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 0f, this, Looper.getMainLooper())
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000, 0f, this, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            stopSelf() // no location permission: the Bus tab asks before starting
            return START_NOT_STICKY
        }
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, MAX_TRIP_MS)
        return START_NOT_STICKY
    }

    override fun onLocationChanged(here: Location) {
        val d = FloatArray(1)
        Location.distanceBetween(here.latitude, here.longitude, lat, lng, d)
        state.update { it.copy(distance = d[0]) }
        if (from < 0) from = d[0]
        if (d[0] <= radius) {
            // Arrived: stop watching, and buzz until she's up (stopBuzz stops this service).
            locations?.removeUpdates(this)
            ring(this, stopName, buzz, soundAfter)
            Widgets.updateBus(this)
            return
        }
        getSystemService(NotificationManager::class.java)
            .notify(TRIP_NOTIFICATION, tripNotification(d[0]))
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {
    }

    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {}

    override fun onDestroy() {
        state.update { it.copy(running = false, distance = -1f) }
        handler.removeCallbacks(timeout)
        locations?.removeUpdates(this)
        stopBuzz(this) // a trip stopped while buzzing stops the buzz too
        Widgets.updateBus(this)
        super.onDestroy()
    }

    /**
     * The ride's notification, [distance] metres from the stop (-1: not known yet).
     * On Android 16 and later it asks to be a Live Update, as Google Maps'
     * directions are: a chip in the status bar with the distance left, and
     * a progress line in the notification, on the lock screen too.
     */
    private fun tripNotification(distance: Float): Notification {
        val open = Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val stop = Intent(this, BusTripService::class.java).setAction(ACTION_STOP)
        val title = if (stopName.isEmpty()) getString(R.string.trip_title) else getString(R.string.trip_title_named, stopName)
        val left = if (distance < 0) null else Distances.format(distance)
        val b = Notification.Builder(this, TRIP_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bus)
            .setContentTitle(title)
            .setContentText(if (left == null) getString(R.string.trip_starting) else getString(R.string.trip_distance, left))
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
            .addAction(
                Notification.Action.Builder(null, getString(R.string.trip_stop), PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE)).build(),
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            // What setRequestPromotedOngoing (Android 16 QPR2) sets, which 16.0 reads too.
            b.addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
            if (left != null) b.setShortCriticalText(left)
            val style = Notification.ProgressStyle()
                .setStyledByProgress(false)
                .setProgressTrackerIcon(Icon.createWithResource(this, R.drawable.ic_stat_bus))
                .setProgressSegments(listOf(Notification.ProgressStyle.Segment(PROGRESS_MAX)))
            if (left == null) {
                style.setProgressIndeterminate(true)
            } else {
                style.setProgress((Distances.progress(from, distance, radius.toInt()) * PROGRESS_MAX).toInt())
            }
            b.setStyle(style)
        }
        return b.build()
    }

    companion object {
        const val ACTION_STOP = "com.rudnam.samfran.STOP_TRIP"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_RADIUS = "radius"
        const val EXTRA_NAME = "name"
        const val EXTRA_BUZZ = "buzz"
        const val EXTRA_SOUND_AFTER = "sound_after"

        private const val TRIP_CHANNEL = "trip"
        private const val ALARM_CHANNEL = "nearly"
        private const val TRIP_NOTIFICATION = 1
        private const val ALARM_NOTIFICATION = 2
        private const val MAX_TRIP_MS = 3 * 60 * 60 * 1000L
        private const val PROGRESS_MAX = 1000
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
        private const val MAX_BUZZ_MS = 10 * 60 * 1000L
        private val main = Handler(Looper.getMainLooper())
        private val autoStop = Any()
        private val soundStart = Any()
        private val soundSteps = Any()

        /** How long the sound takes to rise from quiet to the alarm volume. */
        private const val RISE_MS = 20_000L
        private const val QUIET = 0.1f

        /** The alarm sound, while it plays. */
        private var player: MediaPlayer? = null

        @Volatile private var stopName = ""

        /** The stop the trip on now is to (for the bus widget). */
        val currentStopName: String get() = stopName

        /** The trip as the Bus tab shows it. */
        internal val state = MutableStateFlow(TripStatus())
        val status: StateFlow<TripStatus> = state.asStateFlow()

        /** The two notification channels: a quiet one for the trip, a heads-up one for arriving. */
        fun channels(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.deleteNotificationChannel("alarm") // version 3's loud alarm
            nm.deleteNotificationChannel("arrival") // version 4's, which buzzed a few times
            nm.createNotificationChannel(NotificationChannel(TRIP_CHANNEL, c.getString(R.string.channel_trip), NotificationManager.IMPORTANCE_LOW))
            val nearly = NotificationChannel(ALARM_CHANNEL, c.getString(R.string.channel_alarm), NotificationManager.IMPORTANCE_HIGH)
            nearly.setSound(null, null) // no sound
            nearly.enableVibration(false) // ring() vibrates, until it's stopped
            nearly.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(nearly)
        }

        private fun vibrator(c: Context): Vibrator =
            if (Build.VERSION.SDK_INT >= 31) {
                c.getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

        /**
         * Buzzes: vibrates on repeat, with a notification whose "Stop" button
         * (or swiping it away, or opening SamFran) stops it. Ten minutes at
         * most. After [soundAfter] seconds (0: never), the alarm sound joins in.
         */
        fun ring(c: Context, name: String, buzz: Buzz, soundAfter: Int = 0) {
            val app = c.applicationContext
            channels(app)
            val v = vibrator(app)
            val timings = longArrayOf(0, buzz.onMs, buzz.offMs)
            // Without a strength, Android uses the phone's usual one, which on many is well below its most.
            val pattern = if (buzz.full && v.hasAmplitudeControl()) {
                VibrationEffect.createWaveform(timings, intArrayOf(0, 255, 0), 0)
            } else {
                VibrationEffect.createWaveform(timings, 0)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(pattern, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            }
            state.update { it.copy(buzzing = true) }
            val stop = PendingIntent.getBroadcast(app, 3, Intent(app, StopBuzzReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
            val open = Intent(app, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            val n = Notification.Builder(app, ALARM_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_bus)
                .setContentTitle(app.getString(R.string.alarm_title))
                .setContentText(if (name.isEmpty()) app.getString(R.string.alarm_text) else app.getString(R.string.alarm_text_named, name))
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(PendingIntent.getActivity(app, 2, open, PendingIntent.FLAG_IMMUTABLE)) // MainActivity stops the buzz
                .setDeleteIntent(stop)
                .addAction(Notification.Action.Builder(null, app.getString(R.string.alarm_stop), stop).build())
                .setAutoCancel(true)
                .build()
            app.getSystemService(NotificationManager::class.java).notify(ALARM_NOTIFICATION, n)
            main.removeCallbacksAndMessages(autoStop)
            main.postAtTime({ stopBuzz(app) }, autoStop, SystemClock.uptimeMillis() + MAX_BUZZ_MS)
            main.removeCallbacksAndMessages(soundStart)
            if (soundAfter > 0) {
                main.postAtTime({ if (state.value.buzzing) sound(app) }, soundStart, SystemClock.uptimeMillis() + soundAfter * 1000L)
            }
        }

        /** The phone's alarm sound; its ringtone or notification sound when it has none set. */
        private fun alarmSound(c: Context): Uri? =
            RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        /**
         * Plays the alarm sound on repeat, as an alarm (so even on silent, and
         * at the alarm volume), rising from quiet over [riseMs]; for [forMs],
         * or until [stopSound] when that's 0.
         */
        fun sound(c: Context, riseMs: Long = RISE_MS, forMs: Long = 0) {
            val app = c.applicationContext
            stopSound()
            val uri = alarmSound(app) ?: return
            player = try {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                    )
                    setDataSource(app, uri)
                    isLooping = true
                    setVolume(QUIET, QUIET)
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                return // nothing it can play: it still buzzes
            }
            val now = SystemClock.uptimeMillis()
            val steps = 20
            for (i in 1..steps) {
                val v = QUIET + (1f - QUIET) * i / steps
                main.postAtTime({ player?.setVolume(v, v) }, soundSteps, now + riseMs * i / steps)
            }
            if (forMs > 0) main.postAtTime({ stopSound() }, soundSteps, now + forMs)
        }

        fun stopSound() {
            main.removeCallbacksAndMessages(soundSteps)
            player?.run {
                try {
                    stop()
                } catch (e: IllegalStateException) {
                    // it hadn't started
                }
                release()
            }
            player = null
        }

        /** Stops buzzing, and the trip with it. */
        fun stopBuzz(c: Context) {
            val app = c.applicationContext
            main.removeCallbacksAndMessages(autoStop)
            main.removeCallbacksAndMessages(soundStart)
            stopSound()
            if (!state.value.buzzing) return
            state.update { it.copy(buzzing = false) }
            vibrator(app).cancel()
            app.getSystemService(NotificationManager::class.java).cancel(ALARM_NOTIFICATION)
            app.stopService(Intent(app, BusTripService::class.java))
        }
    }
}

/** "Stop", or the arrival notification swiped away: stops the buzzing and the sound (BusTripService.ring). */
class StopBuzzReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = BusTripService.stopBuzz(context)
}
