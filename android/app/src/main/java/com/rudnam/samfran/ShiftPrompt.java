package com.rudnam.samfran;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/**
 * "Start bus alarm?" when a shift ends. The web app gives the times shifts
 * end (RemindersPlugin keeps them); this sets an alarm for the next one,
 * and when it goes off shows a notification whose button opens the Bus tab
 * and starts the trip (<package>://bus?start=1, web/src/native.ts). Then it
 * sets the alarm for the one after. The alarm is inexact (Android may run
 * it a few minutes late), which needs no special permission. Alarms don't
 * survive a restart of the phone, so it sets them again then.
 */
public class ShiftPrompt extends BroadcastReceiver {
    static final String ACTION_ASK = "com.rudnam.samfran.SHIFT_ENDED";
    static final String CHANNEL = "shift_end";
    static final int NOTIFICATION = 3;
    /** A shift that ended longer ago than this isn't asked about (the phone was off). */
    private static final long LATE_MS = 45 * 60 * 1000L;
    /** The question goes away by itself after this. */
    private static final long SHOWN_MS = 90 * 60 * 1000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_ASK.equals(intent.getAction())) {
            long now = System.currentTimeMillis();
            for (long end : ends(context)) {
                if (end <= now && end > now - LATE_MS) {
                    ask(context);
                    break;
                }
            }
        }
        // Also after the phone restarts (BOOT_COMPLETED), and for the next shift.
        schedule(context);
    }

    private static List<Long> ends(Context c) {
        List<Long> out = new ArrayList<>();
        for (String s : RemindersPlugin.prefs(c).getString(RemindersPlugin.ENDS, "").split(",")) {
            try {
                if (!s.isEmpty()) out.add(Long.parseLong(s));
            } catch (NumberFormatException e) {
                // Skip it.
            }
        }
        return out;
    }

    private static PendingIntent alarm(Context c) {
        Intent i = new Intent(c, ShiftPrompt.class).setAction(ACTION_ASK);
        return PendingIntent.getBroadcast(c, 10, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Sets the alarm for the next time a shift ends, forgetting the ones that have passed. */
    static void schedule(Context c) {
        long now = System.currentTimeMillis();
        long next = Long.MAX_VALUE;
        StringBuilder left = new StringBuilder();
        for (long end : ends(c)) {
            if (end <= now) continue;
            if (left.length() > 0) left.append(',');
            left.append(end);
            next = Math.min(next, end);
        }
        SharedPreferences p = RemindersPlugin.prefs(c);
        p.edit().putString(RemindersPlugin.ENDS, left.toString()).apply();
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (next == Long.MAX_VALUE) {
            am.cancel(alarm(c));
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarm(c));
        }
    }

    /** Opens the web app at <package>://path, e.g. bus?start=1. */
    static PendingIntent open(Context c, int requestCode, String path) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(c.getPackageName() + "://" + path), c, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, requestCode, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void ask(Context c) {
        if (BusTripService.running) return; // already on the way
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, c.getString(R.string.channel_shift_end), NotificationManager.IMPORTANCE_HIGH));
        String stop = RemindersPlugin.prefs(c).getString(RemindersPlugin.STOP, "");
        PendingIntent start = open(c, 11, "bus?start=1");
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_bus)
                .setContentTitle(c.getString(R.string.shift_end_title))
                .setContentText(stop.isEmpty() ? c.getString(R.string.shift_end_text) : c.getString(R.string.shift_end_text_named, stop))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(open(c, 12, "bus")) // a tap on the rest of it opens the Bus tab without starting
                .addAction(new Notification.Action.Builder(null, c.getString(R.string.shift_end_start), start).build())
                .setAutoCancel(true)
                .setTimeoutAfter(SHOWN_MS)
                .build();
        nm.notify(NOTIFICATION, n);
    }

    /** Takes the question away: samfran was opened. */
    static void clear(Context c) {
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIFICATION);
    }
}
