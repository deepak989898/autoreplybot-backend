package com.autoreplybot.remote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Handles notification actions for remote accessibility sessions. */
public final class RemoteAccessibilityNotifReceiver extends BroadcastReceiver {
    private static final String TAG = "RemoteA11yNotifRx";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (!RemoteAccessibilityNotificationManager.ACTION_STOP.equals(action)) return;
        try {
            Context app = context.getApplicationContext();
            new RemoteAccessibilitySessionManager(app).stopSession("USER_STOPPED_FROM_NOTIFICATION");
            RemoteAccessibilityNotificationManager.cancel(app);
        } catch (Throwable t) {
            Log.w(TAG, "stop from notification failed", t);
        }
    }
}
