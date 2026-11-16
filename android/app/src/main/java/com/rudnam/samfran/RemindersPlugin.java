package com.rudnam.samfran;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONException;

/**
 * What the app does while it's closed, set from the web app
 * (web/src/background.ts): asking "Start bus alarm?" when a shift ends
 * (ShiftPrompt), and telling one person when the other changes something
 * (ActivityJob). Both are off until switched on, and both need
 * notifications, which this asks for (Android 13+) when one is switched on.
 * What it's told is kept in the "reminders" preferences, for the receivers
 * that run later without the web app.
 */
@CapacitorPlugin(
        name = "Reminders",
        permissions = {@Permission(alias = "notifications", strings = {"android.permission.POST_NOTIFICATIONS"})})
public class RemindersPlugin extends Plugin {
    static final String PREFS = "reminders";
    /** The times to ask, ms since 1970, joined with commas. */
    static final String ENDS = "ends";
    static final String STOP = "stop";
    static final String ALERTS = "alerts";
    static final String SERVER = "server";
    /** The newest change already shown or notified. */
    static final String SEEN = "seen";

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private boolean needsNotifications() {
        return Build.VERSION.SDK_INT >= 33 && getPermissionState("notifications") != PermissionState.GRANTED;
    }

    private void answer(PluginCall call) {
        JSObject o = new JSObject();
        o.put("notifications", !needsNotifications());
        call.resolve(o);
    }

    /** {on, ends: [ms], stop: name}: when to ask "Start bus alarm?". Off, or no ends, cancels. */
    @PluginMethod
    public void setShiftPrompts(PluginCall call) {
        boolean on = Boolean.TRUE.equals(call.getBoolean("on", false));
        if (on && needsNotifications() && getPermissionState("notifications") != PermissionState.DENIED) {
            requestPermissionForAlias("notifications", call, "shiftPromptsAfterPermission");
            return;
        }
        saveShiftPrompts(call, on && !needsNotifications());
    }

    @PermissionCallback
    private void shiftPromptsAfterPermission(PluginCall call) {
        saveShiftPrompts(call, !needsNotifications());
    }

    private void saveShiftPrompts(PluginCall call, boolean on) {
        StringBuilder ends = new StringBuilder();
        JSArray list = call.getArray("ends", new JSArray());
        if (on) {
            for (int i = 0; i < list.length(); i++) {
                try {
                    if (ends.length() > 0) ends.append(',');
                    ends.append(list.getLong(i));
                } catch (JSONException e) {
                    // Not a number: skip it.
                }
            }
        }
        prefs(getContext()).edit()
                .putString(ENDS, ends.toString())
                .putString(STOP, call.getString("stop", ""))
                .apply();
        ShiftPrompt.schedule(getContext());
        answer(call);
    }

    /** {on, server, seen}: look for the other person's changes now and then, newer than seen. */
    @PluginMethod
    public void setActivityAlerts(PluginCall call) {
        boolean on = Boolean.TRUE.equals(call.getBoolean("on", false));
        if (on && needsNotifications() && getPermissionState("notifications") != PermissionState.DENIED) {
            requestPermissionForAlias("notifications", call, "activityAlertsAfterPermission");
            return;
        }
        saveActivityAlerts(call, on && !needsNotifications());
    }

    @PermissionCallback
    private void activityAlertsAfterPermission(PluginCall call) {
        saveActivityAlerts(call, !needsNotifications());
    }

    private void saveActivityAlerts(PluginCall call, boolean on) {
        SharedPreferences p = prefs(getContext());
        Long seen = call.getLong("seen", 0L);
        p.edit()
                .putBoolean(ALERTS, on)
                .putString(SERVER, call.getString("server", ""))
                // Never backwards: the job may have notified about newer ones.
                .putLong(SEEN, Math.max(p.getLong(SEEN, 0), seen == null ? 0 : seen))
                .apply();
        ActivityJob.schedule(getContext(), on);
        if (on) ActivityJob.clear(getContext()); // the app is open: what was notified has been seen
        answer(call);
    }
}
