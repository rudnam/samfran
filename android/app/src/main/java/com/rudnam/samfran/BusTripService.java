package com.rudnam.samfran;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.Locale;

/**
 * A bus trip: while it runs, the phone watches its position (GPS, the
 * network as a fallback) and, once it's within the alert distance of the
 * stop, rings an alarm that keeps going until it's dismissed (AlarmActivity).
 * It's a foreground service, so it shows a notification for as long as it
 * runs, and Android doesn't stop it while the screen is off. It stops itself
 * after ringing, or after three hours. Positions never leave the phone.
 */
public class BusTripService extends Service implements LocationListener {
    static final String ACTION_STOP = "com.rudnam.samfran.STOP_TRIP";
    static final String EXTRA_LAT = "lat", EXTRA_LNG = "lng", EXTRA_RADIUS = "radius", EXTRA_NAME = "name";

    static final String TRIP_CHANNEL = "trip";
    static final String ALARM_CHANNEL = "alarm";
    static final int TRIP_NOTIFICATION = 1;
    static final int ALARM_NOTIFICATION = 2;
    private static final long MAX_TRIP_MS = 3 * 60 * 60 * 1000L;

    /** Read by BusAlarmPlugin.status. */
    static volatile boolean running;
    static volatile float distance = -1;
    static volatile String stopName = "";

    private double lat, lng;
    private float radius;
    private LocationManager locations;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::stopSelf;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        lat = intent.getDoubleExtra(EXTRA_LAT, 0);
        lng = intent.getDoubleExtra(EXTRA_LNG, 0);
        radius = intent.getFloatExtra(EXTRA_RADIUS, 800);
        stopName = intent.getStringExtra(EXTRA_NAME) == null ? "" : intent.getStringExtra(EXTRA_NAME);
        distance = -1;
        channels(this);
        Notification n = tripNotification(getString(R.string.trip_starting));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(TRIP_NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(TRIP_NOTIFICATION, n);
        }
        running = true;
        locations = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        try {
            locations.removeUpdates(this);
            if (locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 0, this, Looper.getMainLooper());
            }
            if (locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locations.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000, 0, this, Looper.getMainLooper());
            }
        } catch (SecurityException e) {
            stopSelf(); // no location permission: BusAlarmPlugin asks before starting
            return START_NOT_STICKY;
        }
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, MAX_TRIP_MS);
        return START_NOT_STICKY;
    }

    @Override
    public void onLocationChanged(Location here) {
        float[] d = new float[1];
        Location.distanceBetween(here.getLatitude(), here.getLongitude(), lat, lng, d);
        distance = d[0];
        if (d[0] <= radius) {
            ring(this, stopName);
            stopSelf();
            return;
        }
        notifyManager().notify(TRIP_NOTIFICATION, tripNotification(getString(R.string.trip_distance, km(d[0]))));
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacks(timeout);
        if (locations != null) locations.removeUpdates(this);
        super.onDestroy();
    }

    static String km(float meters) {
        return meters < 1000 ? String.format(Locale.ROOT, "%d m", Math.round(meters / 10) * 10)
                : String.format(Locale.ROOT, "%.1f km", meters / 1000);
    }

    private NotificationManager notifyManager() {
        return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private Notification tripNotification(String text) {
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Intent stop = new Intent(this, BusTripService.class).setAction(ACTION_STOP);
        String title = stopName.isEmpty() ? getString(R.string.trip_title) : getString(R.string.trip_title_named, stopName);
        return new Notification.Builder(this, TRIP_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_bus)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
                .addAction(new Notification.Action.Builder(null, getString(R.string.trip_stop),
                        PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE)).build())
                .build();
    }

    /** The two notification channels: a quiet one for the trip, a loud one for the alarm. */
    static void channels(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(TRIP_CHANNEL, c.getString(R.string.channel_trip), NotificationManager.IMPORTANCE_LOW));
        NotificationChannel alarm = new NotificationChannel(ALARM_CHANNEL, c.getString(R.string.channel_alarm), NotificationManager.IMPORTANCE_HIGH);
        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        alarm.setSound(sound, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        alarm.enableVibration(true);
        alarm.setVibrationPattern(new long[]{0, 800, 400, 800, 400, 800});
        alarm.setBypassDnd(true);
        alarm.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(alarm);
    }

    /**
     * Rings: a full-screen alarm over the lock screen (AlarmActivity), and an
     * insistent notification that repeats its sound until it's dismissed, in
     * case the phone doesn't allow full-screen alarms.
     */
    static void ring(Context c, String name) {
        channels(c);
        Intent show = new Intent(c, AlarmActivity.class)
                .putExtra(EXTRA_NAME, name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        PendingIntent full = PendingIntent.getActivity(c, 2, show, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, ALARM_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_bus)
                .setContentTitle(c.getString(R.string.alarm_title))
                .setContentText(name.isEmpty() ? c.getString(R.string.alarm_text) : c.getString(R.string.alarm_text_named, name))
                .setCategory(Notification.CATEGORY_ALARM)
                .setFullScreenIntent(full, true)
                .setContentIntent(full)
                .setAutoCancel(true)
                .build();
        n.flags |= Notification.FLAG_INSISTENT;
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(ALARM_NOTIFICATION, n);
    }
}
