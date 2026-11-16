package com.rudnam.samfran;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Tells one of you when the other changes something you share: about every
 * half hour, while the phone is online, it asks the server for the recent
 * changes (GET /api/activity, signed in as the app is) and shows a
 * notification for the other person's new places, visits, ratings and saved
 * shifts. Android runs it when it suits the battery, so it can be later
 * than that. It asks the server itself rather than being pushed to, which
 * needs no Google services. Switched on from the Us tab (RemindersPlugin).
 */
public class ActivityJob extends JobService {
    static final int JOB = 20;
    static final String CHANNEL = "changes";
    static final int NOTIFICATION = 4;
    private static final long EVERY_MS = 30 * 60 * 1000L;
    /** Where Capacitor's Preferences plugin keeps the session token (web/src/native.ts). */
    private static final String APP_PREFS = "CapacitorStorage";
    private static final String SESSION = "session";

    static void schedule(Context c, boolean on) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (!on) {
            js.cancel(JOB);
            return;
        }
        if (js.getPendingJob(JOB) != null) return; // setting it again would start the half hour over
        js.schedule(new JobInfo.Builder(JOB, new ComponentName(c, ActivityJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(EVERY_MS)
                .setPersisted(true) // across restarts of the phone
                .build());
    }

    /** Takes the notification away: samfran was opened. */
    static void clear(Context c) {
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIFICATION);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try {
                check(getApplicationContext());
            } catch (Exception e) {
                // Offline, signed out, or an answer it can't read: next time.
            }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }

    private static void check(Context c) throws Exception {
        SharedPreferences p = RemindersPlugin.prefs(c);
        String server = p.getString(RemindersPlugin.SERVER, "");
        String token = c.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE).getString(SESSION, "");
        if (!p.getBoolean(RemindersPlugin.ALERTS, false) || server.isEmpty() || token == null || token.isEmpty()) return;

        HttpURLConnection conn = (HttpURLConnection) new URL(server + "/api/activity").openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("X-App-Client", "android");
        JSONArray list;
        try {
            if (conn.getResponseCode() != 200) return;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) body.write(buf, 0, n);
                list = new JSONArray(new String(body.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            conn.disconnect();
        }

        long seen = p.getLong(RemindersPlugin.SEEN, 0);
        long newest = seen;
        List<String> lines = new ArrayList<>(); // newest first, as the server lists them
        for (int i = 0; i < list.length(); i++) {
            JSONObject e = list.getJSONObject(i);
            long id = e.getLong("id");
            if (id <= seen) continue;
            newest = Math.max(newest, id);
            if (e.optBoolean("me")) continue;
            String line = describe(c, e);
            if (line != null) lines.add(line);
        }
        if (newest > seen) p.edit().putLong(RemindersPlugin.SEEN, newest).apply();
        if (!lines.isEmpty()) show(c, lines);
    }

    /** One change in words, or null for a kind that isn't worth a notification. */
    private static String describe(Context c, JSONObject e) {
        String who = e.optString("who");
        if (who.isEmpty()) who = c.getString(R.string.change_someone);
        JSONObject d = e.optJSONObject("details");
        if (d == null) d = new JSONObject();
        String name = d.optString("name");
        switch (e.optString("kind")) {
            case "restaurant_added":
                return c.getString(d.optBoolean("been") ? R.string.change_added_been : R.string.change_added_want, who, name);
            case "visit_logged":
                return c.getString(R.string.change_visit, who, name);
            case "rated":
                int stars = d.optInt("stars");
                return stars > 0 ? c.getString(R.string.change_rated, who, name, stars) : null;
            case "shifts_saved":
                return c.getString(R.string.change_shifts, who);
            default:
                return null;
        }
    }

    private static void show(Context c, List<String> lines) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, c.getString(R.string.channel_changes), NotificationManager.IMPORTANCE_DEFAULT));
        Notification.Builder b = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_bus)
                .setContentTitle(lines.get(0))
                .setContentIntent(ShiftPrompt.open(c, 13, "us"))
                .setAutoCancel(true);
        if (lines.size() > 1) {
            b.setContentText(c.getResources().getQuantityString(R.plurals.change_more, lines.size() - 1, lines.size() - 1));
            Notification.InboxStyle all = new Notification.InboxStyle();
            for (String line : lines) all.addLine(line);
            b.setStyle(all);
        }
        nm.notify(NOTIFICATION, b.build());
    }
}
