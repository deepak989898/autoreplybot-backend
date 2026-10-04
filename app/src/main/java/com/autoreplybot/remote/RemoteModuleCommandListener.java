package com.autoreplybot.remote;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.autoreplybot.AppConstants;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
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
    /** Commands currently executing (removed when ack write finishes). */
    private final Set<String> inFlight = new HashSet<>();

    private RemoteModuleCommandListener(@NonNull Context context, @NonNull String deviceId) {
        this.app = context.getApplicationContext();
        this.deviceId = deviceId;
        this.executor = new RemoteModuleCommandExecutor(app);
    }

    public static synchronized void start(@NonNull Context context) {
        RemoteControlPrefs prefs = new RemoteControlPrefs(context);
        RemoteAccessibilityPrefs a11yPrefs = new RemoteAccessibilityPrefs(context);
        // Keep listening when either master Remote Control or Accessibility Control is on
        // so website Remote Control can connect while screen mirror is already live.
        if (!prefs.isRemoteControlEnabled() && !a11yPrefs.isAccessibilityControlEnabled()) {
            stop();
            return;
        }
        String deviceId = prefs.getOrCreateDeviceId();
        if (instance != null && instance.deviceId.equals(deviceId) && instance.registration != null) {
            // Already attached — still drain so late FCM/poke wakes stuck pending commands.
            instance.drainPending();
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

    /**
     * Ensure listener is running and actively pull any stuck pending commands.
     * FCM previously only called start(), which no-oped when already attached —
     * so a missed snapshot left Remote Control (and other modules) hanging until timeout.
     */
    public static synchronized void poke(@NonNull Context context) {
        start(context);
        if (instance != null) {
            instance.drainPending();
        }
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
                handle(user.getUid(), change.getDocument().getId(), data, now);
            }
        });
        // Also drain once on attach in case the first snapshot is delayed.
        drainPending();
    }

    private void drainPending() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        final String uid = user.getUid();
        FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(uid)
                .collection(AppConstants.FIRESTORE_DEVICES)
                .document(deviceId)
                .collection(AppConstants.FIRESTORE_MODULE_COMMANDS)
                .whereEqualTo("status", "pending")
                .get()
                .addOnSuccessListener(this::onPendingQuery)
                .addOnFailureListener(e -> Log.w(TAG, "drainPending failed", e));
    }

    private void onPendingQuery(@Nullable QuerySnapshot snap) {
        if (snap == null) return;
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        long now = System.currentTimeMillis();
        for (DocumentSnapshot doc : snap.getDocuments()) {
            Map<String, Object> data = doc.getData();
            if (data == null) continue;
            handle(user.getUid(), doc.getId(), data, now);
        }
    }

    private void detach() {
        if (registration != null) {
            registration.remove();
            registration = null;
        }
        synchronized (inFlight) {
            inFlight.clear();
        }
    }

    private void handle(@NonNull String uid,
                        @NonNull String docId,
                        @NonNull Map<String, Object> data,
                        long now) {
        String commandId = RemoteMapValues.string(data, "commandId");
        if (commandId.isEmpty()) commandId = docId;
        synchronized (inFlight) {
            if (!inFlight.add(commandId)) {
                return;
            }
        }
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
        final String ackId = docId;
        executor.execute(action, payload, ackId, new RemoteModuleCommandExecutor.Ack() {
            @Override
            public void ok(@NonNull String summary) {
                ack(uid, ackId, "acked", null, summary);
            }

            @Override
            public void fail(@NonNull String code, @Nullable String message) {
                ack(uid, ackId, "failed", code, message);
            }
        });
    }

    private void ack(@NonNull String uid,
                     @NonNull String commandId,
                     @NonNull String status,
                     @Nullable String errorCode,
                     @Nullable String summary) {
        Map<String, Object> patch = new HashMap<>();
        // Keep commandId + ownerUid in the merge so Firestore security rules accept the update.
        patch.put("commandId", commandId);
        patch.put("ownerUid", uid);
        patch.put("status", status);
        patch.put("completedAt", System.currentTimeMillis());
        if (errorCode != null) patch.put("errorCode", errorCode);
        if (summary != null) patch.put("resultSummary", summary);
        if (errorCode != null) {
            patch.put("errorMessage", summary != null ? summary : errorCode);
        }
        FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(uid)
                .collection(AppConstants.FIRESTORE_DEVICES)
                .document(deviceId)
                .collection(AppConstants.FIRESTORE_MODULE_COMMANDS)
                .document(commandId)
                .set(patch, SetOptions.merge())
                .addOnSuccessListener(v -> {
                    synchronized (inFlight) {
                        inFlight.remove(commandId);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "ack write failed " + commandId + " status=" + status, e);
                    synchronized (inFlight) {
                        inFlight.remove(commandId);
                    }
                });
    }
}
