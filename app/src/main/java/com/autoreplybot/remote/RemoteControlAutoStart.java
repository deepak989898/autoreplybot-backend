package com.autoreplybot.remote;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.messaging.FirebaseMessaging;

import java.util.Collections;

/** Turns remote control on after login/signup and registers this phone by model + device id. */
public final class RemoteControlAutoStart {
    private RemoteControlAutoStart() {}

    public static void apply(@NonNull Context context) {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        Context app = context.getApplicationContext();
        RemoteControlPrefs prefs = new RemoteControlPrefs(app);
        prefs.ensureDeviceIdentity();
        prefs.setRemoteControlEnabled(true);

        RemoteDeviceRepository repo = new RemoteDeviceRepository(app);
        RemoteAuditRepository audit = new RemoteAuditRepository(app);
        FirebaseMessaging.getInstance().getToken()
                .addOnSuccessListener(token -> {
                    if (token != null && !token.trim().isEmpty()) {
                        repo.updateFcmToken(token);
                    }
                });
        repo.registerOrUpdateDevice()
                .addOnSuccessListener(ignored -> {
                    audit.append(
                            RemoteAuditAction.REMOTE_CONTROL_ENABLED,
                            prefs.getOrCreateDeviceId(),
                            null, null, "ok",
                            Collections.emptyMap());
                    repo.heartbeat(true);
                    RemoteModuleRuntime.start(app);
                })
                .addOnFailureListener(ignored -> RemoteModuleRuntime.start(app));
    }
}
