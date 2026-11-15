package com.rudnam.samfran;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.provider.Settings;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * The bus-stop alarm for the web app (web/src/native.ts): start and stop a
 * trip (BusTripService), ring a test alarm, and read where the phone is now,
 * for choosing the stop while standing at it. Asks for location and, on
 * Android 13+, notification permission the first time it needs them.
 */
@CapacitorPlugin(
        name = "BusAlarm",
        permissions = {
                @Permission(alias = "location", strings = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}),
                @Permission(alias = "notifications", strings = {"android.permission.POST_NOTIFICATIONS"}),
        })
public class BusAlarmPlugin extends Plugin {

    private boolean needsNotifications() {
        return Build.VERSION.SDK_INT >= 33 && getPermissionState("notifications") != PermissionState.GRANTED;
    }

    /** Starts a trip: {lat, lng, radius (m), name}. */
    @PluginMethod
    public void start(PluginCall call) {
        if (getPermissionState("location") != PermissionState.GRANTED) {
            requestPermissionForAlias("location", call, "startAfterPermission");
            return;
        }
        if (needsNotifications()) {
            requestPermissionForAlias("notifications", call, "startAfterPermission");
            return;
        }
        Double lat = call.getDouble("lat"), lng = call.getDouble("lng");
        if (lat == null || lng == null) {
            call.reject("no stop");
            return;
        }
        Intent i = new Intent(getContext(), BusTripService.class)
                .putExtra(BusTripService.EXTRA_LAT, lat)
                .putExtra(BusTripService.EXTRA_LNG, lng)
                .putExtra(BusTripService.EXTRA_RADIUS, call.getFloat("radius", 800f))
                .putExtra(BusTripService.EXTRA_NAME, call.getString("name", ""));
        getContext().startForegroundService(i);
        BusTripService.running = true;
        call.resolve(status());
    }

    @PermissionCallback
    private void startAfterPermission(PluginCall call) {
        if (getPermissionState("location") != PermissionState.GRANTED) {
            call.reject("location permission denied", "LOCATION_DENIED");
            return;
        }
        if (needsNotifications() && getPermissionState("notifications") == PermissionState.PROMPT) {
            requestPermissionForAlias("notifications", call, "startAfterPermission");
            return;
        }
        start(call);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        getContext().startService(new Intent(getContext(), BusTripService.class).setAction(BusTripService.ACTION_STOP));
        BusTripService.running = false;
        call.resolve(status());
    }

    /** {running, distance (m, -1 until known), fullScreen (alarms can show over the lock screen)}. */
    @PluginMethod
    public void status(PluginCall call) {
        call.resolve(status());
    }

    private JSObject status() {
        JSObject o = new JSObject();
        o.put("running", BusTripService.running);
        o.put("distance", BusTripService.distance);
        boolean full = true;
        if (Build.VERSION.SDK_INT >= 34) {
            full = ((NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE)).canUseFullScreenIntent();
        }
        o.put("fullScreen", full);
        o.put("notifications", !needsNotifications());
        return o;
    }

    /** Rings the alarm now, to hear it and check it shows over the lock screen. */
    @PluginMethod
    public void test(PluginCall call) {
        if (needsNotifications()) {
            requestPermissionForAlias("notifications", call, "testAfterPermission");
            return;
        }
        BusTripService.ring(getContext(), call.getString("name", ""));
        call.resolve(status());
    }

    @PermissionCallback
    private void testAfterPermission(PluginCall call) {
        BusTripService.ring(getContext(), call.getString("name", ""));
        call.resolve(status());
    }

    /** Opens the setting that lets alarms show over the lock screen (Android 14+). */
    @PluginMethod
    public void openFullScreenSettings(PluginCall call) {
        Intent i = Build.VERSION.SDK_INT >= 34
                ? new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + getContext().getPackageName()))
                : new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getContext().getPackageName());
        getActivity().startActivity(i);
        call.resolve();
    }

    /** Where the phone is now: {lat, lng, accuracy (m)}. */
    @PluginMethod
    public void here(PluginCall call) {
        if (getPermissionState("location") != PermissionState.GRANTED) {
            requestPermissionForAlias("location", call, "hereAfterPermission");
            return;
        }
        LocationManager lm = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
        String provider = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, new CancellationSignal(), getContext().getMainExecutor(), loc -> answer(call, loc));
            } else {
                answer(call, lm.getLastKnownLocation(provider));
            }
        } catch (SecurityException | IllegalArgumentException e) {
            call.reject("location unavailable", "LOCATION_UNAVAILABLE");
        }
    }

    @PermissionCallback
    private void hereAfterPermission(PluginCall call) {
        if (getPermissionState("location") != PermissionState.GRANTED) {
            call.reject("location permission denied", "LOCATION_DENIED");
            return;
        }
        here(call);
    }

    private void answer(PluginCall call, Location loc) {
        if (loc == null) {
            call.reject("location unavailable", "LOCATION_UNAVAILABLE");
            return;
        }
        JSObject o = new JSObject();
        o.put("lat", loc.getLatitude());
        o.put("lng", loc.getLongitude());
        o.put("accuracy", loc.getAccuracy());
        call.resolve(o);
    }
}
