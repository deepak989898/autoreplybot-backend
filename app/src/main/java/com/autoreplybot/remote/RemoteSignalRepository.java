package com.autoreplybot.remote;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.autoreplybot.AppConstants;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Firestore signalling for WebRTC under users/{uid}/sessions/{sessionId}/signals.
 * Stores SDP/ICE JSON only — never media bytes.
 */
public final class RemoteSignalRepository {
    public static final String TYPE_OFFER = "offer";
    public static final String TYPE_ANSWER = "answer";
    public static final String TYPE_ICE = "ice";
    public static final String SENDER_DEVICE = "device";
    public static final String SENDER_CLIENT = "client";

    /** Default signal document TTL (must exceed typical ICE gathering). */
    public static final long DEFAULT_SIGNAL_TTL_MS = 5 * 60 * 1000L;

    public interface SignalListener {
        void onSignal(@NonNull RemoteSignal signal);

        void onError(@NonNull Exception error);
    }

    public static final class RemoteSignal {
        @NonNull public final String signalId;
        @NonNull public final String type;
        @NonNull public final String sender;
        @NonNull public final String payload;
        public final long createdAt;
        public final long expiresAt;

        public RemoteSignal(@NonNull String signalId,
                            @NonNull String type,
                            @NonNull String sender,
                            @NonNull String payload,
                            long createdAt,
                            long expiresAt) {
            this.signalId = signalId;
            this.type = type;
            this.sender = sender;
            this.payload = payload;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }
    }

    @NonNull
    public Task<String> writeSignal(@NonNull String sessionId,
                                    @NonNull String type,
                                    @NonNull String sender,
                                    @NonNull String payload) {
        return writeSignal(sessionId, type, sender, payload, DEFAULT_SIGNAL_TTL_MS);
    }

    @NonNull
    public Task<String> writeSignal(@NonNull String sessionId,
                                    @NonNull String type,
                                    @NonNull String sender,
                                    @NonNull String payload,
                                    long ttlMs) {
        try {
            validateId(sessionId);
            if (!TYPE_OFFER.equals(type) && !TYPE_ANSWER.equals(type) && !TYPE_ICE.equals(type)) {
                throw new IllegalArgumentException("Invalid signal type");
            }
            if (!SENDER_DEVICE.equals(sender) && !SENDER_CLIENT.equals(sender)) {
                throw new IllegalArgumentException("Invalid signal sender");
            }
            if (payload == null || payload.isEmpty() || payload.length() > 200_000) {
                throw new IllegalArgumentException("Invalid signal payload");
            }
            String uid = requireUid();
            String signalId = UUID.randomUUID().toString().replace("-", "");
            validateId(signalId);
            long now = System.currentTimeMillis();
            long expiresAt = now + Math.max(60_000L, ttlMs);
            Map<String, Object> data = new HashMap<>();
            data.put("signalId", signalId);
            data.put("type", type);
            data.put("sender", sender);
            data.put("payload", payload);
            data.put("createdAt", now);
            data.put("expiresAt", expiresAt);
            return signals(uid, sessionId).document(signalId).set(data)
                    .continueWith(task -> {
                        if (!task.isSuccessful()) {
                            Exception e = task.getException();
                            throw e != null ? e : new IllegalStateException("Failed to write signal");
                        }
                        return signalId;
                    });
        } catch (RuntimeException error) {
            return Tasks.forException(error);
        }
    }

    /**
     * Listen for signals from the browser (answer + ice). Caller must remove the registration.
     */
    @NonNull
    public ListenerRegistration listenForClientSignals(@NonNull String sessionId,
                                                       @NonNull SignalListener listener) {
        String uid = requireUid();
        validateId(sessionId);
        Query query = signals(uid, sessionId).orderBy("createdAt", Query.Direction.ASCENDING);
        return query.addSnapshotListener((snap, error) -> {
            if (error != null) {
                listener.onError(error);
                return;
            }
            if (snap == null) return;
            long now = System.currentTimeMillis();
            for (DocumentSnapshot doc : snap.getDocuments()) {
                if (!doc.exists()) continue;
                Map<String, Object> data = doc.getData();
                if (data == null) continue;
                RemoteSignal signal = fromMap(doc.getId(), data);
                if (signal.expiresAt > 0L && now >= signal.expiresAt) {
                    continue;
                }
                if (!SENDER_CLIENT.equals(signal.sender)) continue;
                listener.onSignal(signal);
            }
        });
    }

    @NonNull
    public Task<Void> deleteAllSignals(@NonNull String sessionId) {
        try {
            validateId(sessionId);
            String uid = requireUid();
            return signals(uid, sessionId).get().continueWithTask(task -> {
                if (!task.isSuccessful()) {
                    Exception e = task.getException();
                    throw e != null ? e : new IllegalStateException("Failed to list signals");
                }
                com.google.firebase.firestore.WriteBatch batch =
                        FirebaseFirestore.getInstance().batch();
                int count = 0;
                if (task.getResult() != null) {
                    for (DocumentSnapshot doc : task.getResult().getDocuments()) {
                        batch.delete(doc.getReference());
                        count++;
                        if (count >= 400) break;
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
    private static RemoteSignal fromMap(@NonNull String id, @NonNull Map<String, Object> map) {
        String signalId = RemoteMapValues.string(map, "signalId");
        if (signalId.isEmpty()) signalId = id;
        return new RemoteSignal(
                signalId,
                RemoteMapValues.string(map, "type"),
                RemoteMapValues.string(map, "sender"),
                RemoteMapValues.string(map, "payload"),
                RemoteMapValues.longValue(map, "createdAt", 0L),
                RemoteMapValues.longValue(map, "expiresAt", 0L));
    }

    @NonNull
    private com.google.firebase.firestore.CollectionReference signals(
            @NonNull String uid, @NonNull String sessionId) {
        return FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(uid)
                .collection(AppConstants.FIRESTORE_SESSIONS)
                .document(sessionId)
                .collection(AppConstants.FIRESTORE_SIGNALS);
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
