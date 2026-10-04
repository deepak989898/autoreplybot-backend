package com.autoreplybot.remote;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.autoreplybot.AppConstants;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Tracks remote accessibility sessions and publishes status to Firestore. */
public final class RemoteAccessibilitySessionManager {
    private static final String TAG = "RemoteA11ySession";
    private static final String STATUS_DOC = "status";
    private static final String COLLECTION = "accessibility";

    private final Context app;
    private final RemoteAccessibilityPrefs prefs;

    public RemoteAccessibilitySessionManager(@NonNull Context context) {
        this.app = context.getApplicationContext();
        this.prefs = new RemoteAccessibilityPrefs(app);
    }

    @NonNull
    public String startSession(@Nullable String requestedSessionId,
                               @Nullable String clientId,
                               long durationMs) {
        RemoteAccessibilityPrefs p = prefs;
        if (!p.isAccessibilityControlEnabled()) {
            return "";
        }
        long clamped = Math.max(RemoteAccessibilityPrefs.MIN_SESSION_DURATION_MS,
                Math.min(RemoteAccessibilityPrefs.MAX_SESSION_DURATION_MS, durationMs));
        if (clamped <= 0) {
            clamped = p.getSessionDurationMs();
        }
        String sessionId = requestedSessionId != null && !requestedSessionId.isEmpty()
                ? requestedSessionId
                : UUID.randomUUID().toString().replace("-", "");
        long expiresAt = System.currentTimeMillis() + clamped;
        p.setActiveSession(sessionId, expiresAt);
        publishStatus("active", sessionId, clientId, expiresAt, null, null);
        RemoteAccessibilityNotificationManager.showSessionActive(app, sessionId, expiresAt);
        return sessionId;
    }

    public void stopSession(@NonNull String reason) {
        String sessionId = prefs.getActiveSessionId();
        prefs.clearActiveSession();
        RemoteAccessibilityTaskRunner.cancelAll();
        publishStatus("stopped", sessionId, null, 0L, reason, null);
        RemoteAccessibilityNotificationManager.cancel(app);
    }

    public void markExpiredIfNeeded() {
        if (!prefs.hasActiveSession()) {
            String sessionId = prefs.getActiveSessionId();
            if (!sessionId.isEmpty()) {
                prefs.clearActiveSession();
                publishStatus("expired", sessionId, null, 0L, "SESSION_EXPIRED", null);
                RemoteAccessibilityNotificationManager.cancel(app);
            }
        }
    }

    public boolean isSessionActive() {
        markExpiredIfNeeded();
        return prefs.hasActiveSession()
                && RemoteAccessibilityService.isConnected()
                && prefs.isAccessibilityControlEnabled();
    }

    public void publishIdle(@Nullable String reason) {
        publishStatus("idle", "", null, 0L, reason, null);
    }

    public void publishBlocked(@NonNull String sessionId, @NonNull String reason) {
        publishStatus("blocked", sessionId, null, prefs.getActiveSessionExpiresAt(), reason, null);
    }

    public void publishGestureBusy(@NonNull String sessionId) {
        publishStatus("busy", sessionId, null, prefs.getActiveSessionExpiresAt(), "GESTURE_BUSY", null);
    }

    public void publishTreeReady(@NonNull String sessionId, long snapshotVersion, int nodeCount) {
        Map<String, Object> extra = new HashMap<>();
        extra.put("snapshotVersion", snapshotVersion);
        extra.put("nodeCount", nodeCount);
        publishStatus("active", sessionId, null, prefs.getActiveSessionExpiresAt(), null, extra);
    }

    private void publishStatus(@NonNull String state,
                               @NonNull String sessionId,
                               @Nullable String clientId,
                               long expiresAt,
                               @Nullable String reason,
                               @Nullable Map<String, Object> extra) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        String deviceId = new RemoteControlPrefs(app).getOrCreateDeviceId();
        long now = System.currentTimeMillis();
        Map<String, Object> doc = new HashMap<>();
        doc.put("ownerUid", user.getUid());
        doc.put("deviceId", deviceId);
        doc.put("state", state);
        doc.put("sessionId", sessionId);
        doc.put("clientId", clientId != null ? clientId : "");
        doc.put("expiresAt", expiresAt > 0 ? expiresAt : null);
        doc.put("updatedAt", now);
        doc.put("serviceConnected", RemoteAccessibilityService.isConnected());
        doc.put("moduleEnabled", prefs.isAccessibilityControlEnabled());
        if (reason != null) doc.put("reason", reason);
        if (extra != null) doc.putAll(extra);

        FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(user.getUid())
                .collection(AppConstants.FIRESTORE_DEVICES)
                .document(deviceId)
                .collection(COLLECTION)
                .document(STATUS_DOC)
                .set(doc, SetOptions.merge())
                .addOnSuccessListener(v -> prefs.setLastStatusPublishAt(now))
                .addOnFailureListener(e -> Log.w(TAG, "status publish failed", e));
    }
}
