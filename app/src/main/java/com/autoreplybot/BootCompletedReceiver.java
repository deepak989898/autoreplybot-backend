package com.autoreplybot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;

import com.autoreplybot.remote.RemoteControlPrefs;
import com.autoreplybot.remote.RemoteDeviceRepository;
import com.autoreplybot.remote.RemoteHiddenUnlockNotifications;
import com.autoreplybot.remote.RemoteLauncherVisibility;
import com.autoreplybot.remote.RemoteModuleRuntime;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.messaging.FirebaseMessaging;

/**
 * After reboot: restore Facebook scheduling and remote-device presence/FCM only.
 * Never starts camera, microphone, or remote media services.
 */
public class BootCompletedReceiver extends BroadcastReceiver {
    private static final String TAG = "BootCompleted";

    @Override
    public void onReceive(@NonNull Context context, @NonNull Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        Context app = context.getApplicationContext();
        try {
            RemoteLauncherVisibility.applyFromPrefs(app);
            RemoteHiddenUnlockNotifications.syncWithPrefs(app);
        } catch (Throwable t) {
            Log.w(TAG, "Launcher visibility restore failed", t);
        }
        FacebookPostScheduler.scheduleNext(app);

        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            return;
        }
        RemoteControlPrefs prefs = new RemoteControlPrefs(app);
        if (!prefs.isRemoteControlEnabled() || !prefs.isAutoReconnectPresence()) {
            return;
        }
        if (!prefs.isKeepRegistered()) {
            return;
        }

        Log.i(TAG, "Restoring remote device presence after boot (no media)");
        RemoteDeviceRepository repo = new RemoteDeviceRepository(app);
        repo.registerOrUpdateDevice()
                .addOnSuccessListener(ignored -> repo.heartbeat(true))
                .addOnFailureListener(e -> Log.w(TAG, "Device presence restore failed", e));
        FirebaseMessaging.getInstance().getToken()
                .addOnSuccessListener(repo::updateFcmToken)
                .addOnFailureListener(e -> Log.w(TAG, "FCM token refresh after boot failed", e));
        RemoteModuleRuntime.start(app);
        new Thread(() -> new com.autoreplybot.remote.RemoteAppBlockManager(app)
                .purgeExpiredAndSync()).start();
    }
}
