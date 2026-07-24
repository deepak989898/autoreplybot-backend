package com.autoreplybot.remote;

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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Listens for users/{uid}/commands for the active session, validates, executes, acks.
 */
public final class RemoteCommandListener {
    private static final String TAG = "RemoteCommandListener";

    public interface Executor {
        void execute(@NonNull RemoteCommandAction action, @Nullable Map<String, Object> payload);

        void onCommandAudit(@NonNull RemoteCommandAction action, @NonNull String result);
    }

    @NonNull private final String sessionId;
    @NonNull private final String deviceId;
    @NonNull private final Executor executor;
    @Nullable private ListenerRegistration registration;
    @NonNull private final Set<String> seenCommandIds = new HashSet<>();

    public RemoteCommandListener(@NonNull String sessionId,
                                 @NonNull String deviceId,
                                 @NonNull Executor executor) {
        this.sessionId = sessionId;
        this.deviceId = deviceId;
        this.executor = executor;
    }

    public void start() {
        stop();
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.w(TAG, "Not signed in; command listener idle");
            return;
        }
        // Single-field equality avoids a composite index; validation enforces session+device.
        Query query = FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(user.getUid())
                .collection(AppConstants.FIRESTORE_COMMANDS)
                .whereEqualTo("sessionId", sessionId);

        registration = query.addSnapshotListener((snap, error) -> {
            if (error != null) {
                Log.w(TAG, "Command listen failed", error);
                return;
            }
            if (snap == null) return;
            long now = System.currentTimeMillis();
            for (DocumentChange change : snap.getDocumentChanges()) {
                if (change.getType() == DocumentChange.Type.REMOVED) continue;
                Map<String, Object> data = change.getDocument().getData();
                String commandId = RemoteMapValues.string(data, "commandId");
                if (commandId.isEmpty()) commandId = change.getDocument().getId();
                if (!seenCommandIds.add(commandId)) continue;
                handleCommand(change.getDocument().getId(), data, now);
            }
        });
    }

    public void stop() {
        if (registration != null) {
            registration.remove();
            registration = null;
        }
        seenCommandIds.clear();
    }

    private void handleCommand(@NonNull String docId,
                               @NonNull Map<String, Object> data,
                               long now) {
        RemoteCommandValidator.Result result =
                RemoteCommandValidator.validate(data, sessionId, deviceId, now);
        if (!result.accepted) {
            ack(docId, result.status, result.errorCode,
                    result.errorCode != null ? result.errorCode : "rejected");
            if ("UNKNOWN_ACTION".equals(result.errorCode)
                    || "SESSION_MISMATCH".equals(result.errorCode)
                    || "DEVICE_MISMATCH".equals(result.errorCode)) {
                Log.w(TAG, "Rejected command " + docId + " code=" + result.errorCode);
            }
            return;
        }

        RemoteCommandAction action = result.action;
        if (action == null) {
            ack(docId, "ignored", "UNKNOWN_ACTION", "null action");
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = data.get("payload") instanceof Map
                    ? (Map<String, Object>) data.get("payload")
                    : null;
            executor.execute(action, payload);
            ack(docId, "acked", null, "ok");
            executor.onCommandAudit(action, "ok");
        } catch (RuntimeException e) {
            Log.w(TAG, "Command execution failed " + action, e);
            String msg = e.getMessage() != null ? e.getMessage() : "exec_failed";
            String code = isBusyMessage(msg) ? "CAMERA_BUSY" : "EXEC_FAILED";
            ack(docId, "failed", code, msg);
            executor.onCommandAudit(action, "failed");
        }
    }

    private static boolean isBusyMessage(@NonNull String msg) {
        String lower = msg.toLowerCase(Locale.US);
        return lower.contains("busy") || lower.contains("in use");
    }

    private void ack(@NonNull String docId,
                     @NonNull String status,
                     @Nullable String errorCode,
                     @NonNull String errorMessage) {
        String code = errorCode;
        if ("EXEC_FAILED".equals(code) && isBusyMessage(errorMessage)) {
            code = "CAMERA_BUSY";
        }
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        Map<String, Object> patch = new HashMap<>();
        patch.put("status", status);
        patch.put("acknowledgedAt", System.currentTimeMillis());
        if (code != null) patch.put("errorCode", code);
        patch.put("errorMessage", errorMessage);
        FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(user.getUid())
                .collection(AppConstants.FIRESTORE_COMMANDS)
                .document(docId)
                .set(patch, SetOptions.merge())
                .addOnFailureListener(e -> Log.w(TAG, "Ack failed for " + docId, e));
    }
}
