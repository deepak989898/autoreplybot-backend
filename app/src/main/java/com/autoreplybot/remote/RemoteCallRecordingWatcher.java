package com.autoreplybot.remote;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

/**
 * Keeps {@link RemoteCallRecordingService} monitor in sync with prefs/permissions.
 * Recording itself runs inside that sticky FGS (not started from a background callback).
 */
public final class RemoteCallRecordingWatcher {
    private static final String TAG = "RemoteCallRecWatch";

    private RemoteCallRecordingWatcher() {}

    public static void start(@NonNull Context context) {
        syncWithPrefs(context);
    }

    public static void stop(@NonNull Context context) {
        RemoteCallRecordingService.stopMonitor(context.getApplicationContext());
    }

    public static void syncWithPrefs(@NonNull Context context) {
        Context app = context.getApplicationContext();
        if (RemoteCallRecordingService.canMonitor(app)) {
            Log.i(TAG, "ensuring call recording monitor");
            RemoteCallRecordingService.ensureMonitor(app);
        } else {
            Log.i(TAG, "stopping call recording monitor (missing prefs/permissions)");
            RemoteCallRecordingService.stopMonitor(app);
        }
    }
}
