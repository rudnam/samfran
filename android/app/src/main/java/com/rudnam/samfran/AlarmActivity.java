package com.rudnam.samfran;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The wake-up alarm: over the lock screen, screen on, alarm sound and
 * vibration on repeat, until "I'm awake" is tapped. Drawn in code, in the
 * app's tomato red, so it needs no layout files.
 */
public class AlarmActivity extends Activity {
    private MediaPlayer player;
    private Vibrator vibrator;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String name = getIntent().getStringExtra(BusTripService.EXTRA_NAME);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#E23D28"));
        int pad = dp(32);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.alarm_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(40);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView text = new TextView(this);
        text.setText(name == null || name.isEmpty() ? getString(R.string.alarm_text) : getString(R.string.alarm_text_named, name));
        text.setTextColor(Color.WHITE);
        text.setTextSize(20);
        text.setGravity(Gravity.CENTER);
        text.setPadding(0, dp(12), 0, dp(48));
        root.addView(text);

        Button awake = new Button(this);
        awake.setText(R.string.alarm_awake);
        awake.setTextSize(22);
        awake.setAllCaps(false);
        awake.setTextColor(Color.parseColor("#E23D28"));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(20));
        awake.setBackground(bg);
        awake.setPadding(dp(32), dp(20), dp(32), dp(20));
        awake.setOnClickListener(v -> finish());
        root.addView(awake, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            player.setDataSource(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception e) {
            player = null; // the notification still sounds
        }
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null) {
            vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 800, 400}, 0));
        }
        // This screen makes the noise now; quiet the notification's.
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).cancel(BusTripService.ALARM_NOTIFICATION);
    }

    @Override
    protected void onDestroy() {
        if (player != null) {
            player.stop();
            player.release();
        }
        if (vibrator != null) vibrator.cancel();
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
