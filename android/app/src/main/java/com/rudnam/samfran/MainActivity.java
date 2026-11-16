package com.rudnam.samfran;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Before super.onCreate, which starts the bridge.
        registerPlugin(UpdatesPlugin.class);
        registerPlugin(ShareTargetPlugin.class);
        registerPlugin(BusAlarmPlugin.class);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Opening samfran means she's up: stop the bus buzz.
        BusTripService.stopBuzz(this);
    }
}
