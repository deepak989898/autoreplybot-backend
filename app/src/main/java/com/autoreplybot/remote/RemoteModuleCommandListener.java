package com.autoreplybot.remote;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.autoreplybot.AppConstants;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Listens for pending moduleCommands for this device. */
public final class RemoteModuleCommandListener {
    private static final String TAG = "RemoteModuleCmdListen";

    @Nullable private static RemoteModuleCommandListener instance;

    private final Context app;
    private final String deviceId;
    private final RemoteModuleCommandExecutor executor;
    @Nullable private ListenerRegistration registration;
    private final Set<String> seen = new HashSet<>();

    private RemoteModuleCommandListener(@NonNull Context context, @NonNull String deviceId) {
        this.app = context.getApplicationContext();
        this.deviceId = deviceId;
        this.executor = new RemoteModuleCommandExecutor(app);
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
        instance = new RemoteModuleCommandListener(context, deviceId);
        instance.attach();
    }

    public static synchronized void stop() {
        if (instance != null) {
            instance.detach();
            instance = null;
        }
    }

    public static synchronized void poke(@NonNull Context context) {
        start(context);
    }

    private void attach() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        Query q = FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(user.getUid())
                .collection(AppConstants.FIRESTORE_DEVICES)
                .document(deviceId)
                .collection(AppConstants.FIRESTORE_MODULE_COMMANDS)
                .whereEqualTo("status", "pending");
        registration = q.addSnapshotListener((snap, error) -> {
            if (error != null) {
                Log.w(TAG, "module command listen failed", error);
                return;
            }
            if (snap == null) return;
            long now = System.currentTimeMillis();
            for (DocumentChange change : snap.getDocumentChanges()) {
                if (change.getType() == DocumentChange.Type.REMOVED) continue;
                Map<String, Object> data = change.getDocument().getData();
                String commandId = RemoteMapValues.string(data, "commandId");
                if (commandId.isEmpty()) commandId = change.getDocument().getId();
                if (!seen.add(commandId)) continue;
                handle(user.getUid(), change.getDocument().getId(), data, now);
            }
        });
    }

    private void detach() {
        if (registration != null) {
            registration.remove();
            registration = null;
        }
        seen.clear();
    }

    private void handle(@NonNull String uid,
                        @NonNull String docId,
                        @NonNull Map<String, Object> data,
                        long now) {
        String cmdDevice = RemoteMapValues.string(data, "deviceId");
        if (!cmdDevice.isEmpty() && !deviceId.equals(cmdDevice)) {
            ack(uid, docId, "ignored", "DEVICE_MISMATCH", null);
            return;
        }
        long expiresAt = RemoteMapValues.longValue(data, "expiresAt", 0L);
        if (expiresAt > 0 && now >= expiresAt) {
            ack(uid, docId, "expired", "EXPIRED", null);
            return;
        }
        RemoteModuleCommandAction action = RemoteModuleCommandAction.tryParse(data.get("action"));
        if (action == null) {
            ack(uid, docId, "failed", "UNKNOWN_ACTION", null);
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = data.get("payload") instanceof Map
                ? (Map<String, Object>) data.get("payload")
                : new HashMap<>();
        final String commandId = docId;
        executor.execute(action, payload, commandId, new RemoteModuleCommandExecutor.Ack() {
            @Override
            public void ok(@NonNull String summary) {
                ack(uid, commandId, "acked", null, summary);
            }

            @Override
            public void fail(@NonNull String code, @Nullable String message) {
                ack(uid, commandId, "failed", code, message);
            }
        });
    }

    private void ack(@NonNull String uid,
                     @NonNull String commandId,
                     @NonNull String status,
                     @Nullable String errorCode,
                     @Nullable String summary) {
        Map<String, Object> patch = new HashMap<>();
        patch.put("status", status);
        patch.put("completedAt", System.currentTimeMillis());
        if (errorCode != null) patch.put("errorCode", errorCode);
        if (summary != null) patch.put("resultSummary", summary);
        if (errorCode != null) patch.put("errorMessage", summary);
        FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(uid)
                .collection(AppConstants.FIRESTORE_DEVICES)
                .document(deviceId)
                .collection(AppConstants.FIRESTORE_MODULE_COMMANDS)
                .document(commandId)
                .set(patch, SetOptions.merge());
    }
}
