package com.rudnam.samfran;

import android.content.Intent;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Text shared to samfran from another app, e.g. a place shared from Google
 * Maps ("Name\nhttps://maps.app.goo.gl/…"). The web app (web/src/native.ts)
 * asks for it with take() when it starts, and hears "shared" when something
 * is shared while it's open; either way it opens Add a place, filled in.
 */
@CapacitorPlugin(name = "ShareTarget")
public class ShareTargetPlugin extends Plugin {
    private String pending;

    @Override
    public void load() {
        keep(getActivity().getIntent());
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        if (keep(intent)) {
            JSObject data = new JSObject();
            data.put("text", pending);
            notifyListeners("shared", data, true);
        }
    }

    /** The text waiting to be used, once. */
    @PluginMethod
    public void take(PluginCall call) {
        JSObject result = new JSObject();
        result.put("text", pending);
        pending = null;
        call.resolve(result);
    }

    private boolean keep(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return false;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        if (text == null || text.isEmpty()) return false;
        // Some apps put the name in the subject and only the link in the text.
        pending = subject != null && !subject.isEmpty() && !text.contains(subject) ? subject + "\n" + text : text;
        // Handled: a rotation or relaunch mustn't open the form again.
        intent.setAction(Intent.ACTION_MAIN);
        return true;
    }
}
