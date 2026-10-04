package com.autoreplybot.remote;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.autoreplybot.AppConstants;
import com.autoreplybot.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Starts camera/screen sessions from Firestore when FCM is delayed or dropped.
 * Same launch path as {@link RemoteMessagingService}.
 */
public final class RemoteSessionRequestWatch {
    private static final String TAG = "RemoteSessionWatch";
    private static final long FRESH_MS = 4 * 60 * 1000L;

    @Nullable private static RemoteSessionRequestWatch instance;

    private final Context app;
    private final String deviceId;
    @Nullable private ListenerRegistration registration;
    private final Set<String> handled = new HashSet<>();

    private RemoteSessionRequestWatch(@NonNull Context context, @NonNull String deviceId) {
        this.app = context.getApplicationContext();
        this.deviceId = deviceId;
    }

    public static synchronized void start(@NonNull Context context) {
        RemoteControlPrefs prefs = new RemoteControlPrefs(context);
        if (!prefs.isRemoteControlEnabled()) {
            stop();
            return;
        }
        String deviceId = prefs.getOrCreateDeviceId();
        if (instance != null && instance.deviceId.equals(deviceId) && instance.registration != null) {
            return;
        }
        stop();
        instance = new RemoteSessionRequestWatch(context, deviceId);
        instance.attach();
    }

    public static synchronized void stop() {
        if (instance != null) {
            instance.detach();
            instance = null;
        }
    }

    private void attach() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        registration = FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(user.getUid())
                .collection(AppConstants.FIRESTORE_SESSION_REQUESTS)
                .whereEqualTo("deviceId", deviceId)
                .addSnapshotListener((snap, error) -> {
                    if (error != null) {
                        Log.w(TAG, "session request listen failed", error);
                        return;
                    }
                    if (snap == null) return;
                    long now = System.currentTimeMillis();
                    for (DocumentChange change : snap.getDocumentChanges()) {
                        if (change.getType() == DocumentChange.Type.REMOVED) continue;
                        handle(change.getDocument().getData(), now);
                    }
                });
    }

    private void detach() {
        if (registration != null) {
            registration.remove();
            registration = null;
        }
        handled.clear();
    }

    private void handle(@Nullable Map<String, Object> data, long now) {
        if (data == null || data.isEmpty()) return;
        String requestId = RemoteMapValues.string(data, "requestId");
        if (requestId.isEmpty()) return;
        if (!handled.add(requestId)) return;
        if (handled.size() > 80) {
            handled.clear();
            handled.add(requestId);
        }

        long createdAt = RemoteMapValues.longValue(data, "createdAt", 0L);
        long expiresAt = RemoteMapValues.longValue(data, "expiresAt", 0L);
        if (createdAt > 0 && now - createdAt > FRESH_MS) return;
        if (expiresAt > 0 && now >= expiresAt) return;

        String status = RemoteMapValues.string(data, "status").toLowerCase(Locale.US);
        String sessionId = RemoteMapValues.string(data, "sessionId");
        String clientId = RemoteMapValues.string(data, "clientId");
        String clientName = RemoteMapValues.string(data, "clientName");
        List<String> caps = RemoteMapValues.strings(data.get("requestedCapabilities"));
        boolean wantCamera = caps.isEmpty() || containsCap(caps, "camera");
        boolean wantMic = caps.isEmpty() || containsCap(caps, "microphone");
        String kind = RemoteMapValues.string(data, "sessionKind").toLowerCase(Locale.US);
        boolean screen = RemoteMapValues.bool(data, "screenMirror", false)
                || "screen".equals(kind);
        if (kind.isEmpty()) kind = screen ? "screen" : "camera";

        if ("pending".equals(status)) {
            launchIncoming(requestId, clientName);
            return;
        }
        if (!"approved".equals(status)) return;
        if (sessionId.isEmpty()) return;
        launchAutoStart(requestId, sessionId, clientId, clientName, wantCamera, wantMic, kind, screen);
    }

    private static boolean containsCap(@NonNull List<String> caps, @NonNull String key) {
        for (String c : caps) {
            if (key.equalsIgnoreCase(c)) return true;
        }
        return false;
    }

    private void launchIncoming(@NonNull String requestId, @NonNull String clientName) {
        Intent open = new Intent(app, RemoteIncomingRequestActivity.class);
        open.putExtra(RemoteIncomingRequestActivity.EXTRA_REQUEST_ID, requestId);
        if (!clientName.isEmpty()) {
            open.putExtra(RemoteIncomingRequestActivity.EXTRA_CLIENT_NAME, clientName);
        }
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            app.startActivity(open);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not open session request activity", e);
        }
    }

    private void launchAutoStart(@NonNull String requestId,
                                 @NonNull String sessionId,
                                 @NonNull String clientId,
                                 @NonNull String clientName,
                                 boolean cameraEnabled,
                                 boolean microphoneEnabled,
                                 @NonNull String sessionKind,
                                 boolean screenMirror) {
        String name = clientName.isEmpty()
                ? app.getString(R.string.remote_default_client_name)
                : clientName;
        Intent open = new Intent(app, RemoteAutoStartActivity.class);
        open.putExtra(RemoteAutoStartActivity.EXTRA_REQUEST_ID, requestId);
        open.putExtra(RemoteAutoStartActivity.EXTRA_SESSION_ID, sessionId);
        open.putExtra(RemoteAutoStartActivity.EXTRA_CLIENT_ID, clientId);
        open.putExtra(RemoteAutoStartActivity.EXTRA_CLIENT_NAME, name);
        open.putExtra(RemoteAutoStartActivity.EXTRA_CAMERA_ENABLED, cameraEnabled);
        open.putExtra(RemoteAutoStartActivity.EXTRA_MICROPHONE_ENABLED, microphoneEnabled);
        open.putExtra(RemoteAutoStartActivity.EXTRA_SESSION_KIND, sessionKind);
        open.putExtra(RemoteAutoStartActivity.EXTRA_SCREEN_MIRROR, screenMirror);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        RemoteSessionNotifAutoClick.disarm();
        try {
            RemoteSessionAutoStartLaunchService.start(app, open, screenMirror);
        } catch (Throwable t) {
            Log.i(TAG, "Launch service failed; trying startActivity", t);
            try {
                app.startActivity(open);
            } catch (RuntimeException e) {
                Log.i(TAG, "Could not start auto-start activity", e);
            }
        }
    }
}
