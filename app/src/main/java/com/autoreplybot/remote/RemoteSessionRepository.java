package com.autoreplybot.remote;

import androidx.annotation.NonNull;

import com.autoreplybot.AppConstants;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Minimal CRUD for users/{uid}/sessions/{sessionId}. */
public final class RemoteSessionRepository {

    @NonNull
    public Task<RemoteSession> get(@NonNull String sessionId) {
        try {
            validateId(sessionId);
            String uid = requireUid();
            return doc(uid, sessionId).get().continueWith(task -> {
                DocumentSnapshot snap = task.getResult();
                if (snap == null || !snap.exists() || snap.getData() == null) {
                    throw new IllegalStateException("Session not found");
                }
                return RemoteSession.fromMap(sessionId, snap.getData());
            });
        } catch (RuntimeException error) {
            return Tasks.forException(error);
        }
    }

    @NonNull
    public Task<RemoteSession> createActive(@NonNull String deviceId,
                                            @NonNull String clientId,
                                            boolean cameraEnabled,
                                            boolean microphoneEnabled) {
        try {
            validateId(deviceId);
            validateId(clientId);
            String uid = requireUid();
            String sessionId = UUID.randomUUID().toString().replace("-", "");
            validateId(sessionId);
            long now = System.currentTimeMillis();
            RemoteSession session = new RemoteSession(
                    sessionId,
                    deviceId,
                    clientId,
                    RemoteSession.Status.CONNECTED,
                    now,
                    0L,
                    cameraEnabled ? "front" : "none",
                    microphoneEnabled,
                    false,
                    "auto",
                    "",
                    uid);
            return doc(uid, sessionId).set(session.toMap()).continueWith(task -> {
                if (!task.isSuccessful()) {
                    Exception e = task.getException();
                    throw e != null ? e : new IllegalStateException("Failed to create session");
                }
                return session;
            });
        } catch (RuntimeException error) {
            return Tasks.forException(error);
        }
    }

    @NonNull
    public Task<Void> endSession(@NonNull String sessionId, @NonNull String reason) {
        try {
            validateId(sessionId);
            String uid = requireUid();
            Map<String, Object> patch = new HashMap<>();
            patch.put("status", RemoteSession.Status.ENDED.wireValue());
            patch.put("endedAt", System.currentTimeMillis());
            patch.put("terminationReason", reason != null ? reason : "");
            return doc(uid, sessionId).set(patch, SetOptions.merge());
        } catch (RuntimeException error) {
            return Tasks.forException(error);
        }
    }

    @NonNull
    public Task<Void> upsert(@NonNull RemoteSession session) {
        try {
            validateId(session.sessionId);
            String uid = requireUid();
            return doc(uid, session.sessionId).set(session.toMap(), SetOptions.merge());
        } catch (RuntimeException error) {
            return Tasks.forException(error);
        }
    }

    @NonNull
    public Task<Void> findActiveForDeviceAndEnd(@NonNull String deviceId, @NonNull String reason) {
        try {
            validateId(deviceId);
            String uid = requireUid();
            return FirebaseFirestore.getInstance()
                    .collection(AppConstants.FIRESTORE_USERS)
                    .document(uid)
                    .collection(AppConstants.FIRESTORE_SESSIONS)
                    .whereEqualTo("deviceId", deviceId)
                    .get()
                    .continueWithTask(task -> {
                        if (!task.isSuccessful()) {
                            Exception e = task.getException();
                            throw e != null ? e : new IllegalStateException("Session query failed");
                        }
                        com.google.firebase.firestore.WriteBatch batch =
                                FirebaseFirestore.getInstance().batch();
                        long now = System.currentTimeMillis();
                        int count = 0;
                        if (task.getResult() != null) {
                            for (DocumentSnapshot snap : task.getResult().getDocuments()) {
                                Map<String, Object> data = snap.getData();
                                if (data == null) continue;
                                String status = RemoteMapValues.string(data, "status");
                                if (!"requesting".equals(status)
                                        && !"connecting".equals(status)
                                        && !"connected".equals(status)
                                        && !"reconnecting".equals(status)) {
                                    continue;
                                }
                                Map<String, Object> patch = new HashMap<>();
                                patch.put("status", RemoteSession.Status.ENDED.wireValue());
                                patch.put("endedAt", now);
                                patch.put("terminationReason", reason);
                                batch.set(snap.getReference(), patch, SetOptions.merge());
                                count++;
                            }
                        }
                        if (count == 0) return Tasks.forResult(null);
                        return batch.commit();
                    });
        } catch (RuntimeException error) {
            return Tasks.forException(error);
        }
    }

    @NonNull
    private DocumentReference doc(@NonNull String uid, @NonNull String sessionId) {
        return FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(uid)
                .collection(AppConstants.FIRESTORE_SESSIONS)
                .document(sessionId);
    }

    @NonNull
    private static String requireUid() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) throw new IllegalStateException("Not signed in");
        return user.getUid();
    }

    private static void validateId(@NonNull String id) {
        if (!id.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Invalid document identifier");
        }
    }
}
