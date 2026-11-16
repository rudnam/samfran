package com.rudnam.samfran;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** "I'm up", or the arrival notification swiped away: stops the buzzing (BusTripService.ring). */
public class StopBuzzReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        BusTripService.stopBuzz(context);
    }
}
