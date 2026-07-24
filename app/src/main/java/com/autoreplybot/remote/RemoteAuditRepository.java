package com.autoreplybot.remote;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.autoreplybot.AppConstants;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/** Append-only writes to users/{uid}/auditLogs/{logId}. */
public final class RemoteAuditRepository {
    public RemoteAuditRepository(@NonNull Context context) {
        // Context reserved for future local cache; cloud path is auth-scoped.
    }

    @NonNull
    public Task<Void> append(@NonNull RemoteAuditAction action,
                             @NonNull String deviceId,
                             @Nullable String clientId,
                             @Nullable String sessionId,
                             @NonNull String result,
                             @Nullable Map<String, Object> metadataWithoutSensitiveMedia) {
        try {
            String uid = requireUid();
            String logId = UUID.randomUUID().toString().replace("-", "");
            validateId(logId);
            if (deviceId != null && !deviceId.isEmpty()) {
                validateId(deviceId);
            }
            RemoteAuditLog log = new RemoteAuditLog(
                    logId,
                    action,
                    deviceId != null ? deviceId : "",
                    clientId != null ? clientId : "",
                    sessionId != null ? sessionId : "",
                    System.currentTimeMillis(),
                    result,
                    metadataWithoutSensitiveMedia != null
                            ? metadataWithoutSensitiveMedia
                            : Collections.emptyMap(),
                    uid);
            return FirebaseFirestore.getInstance()
                    .collection(AppConstants.FIRESTORE_USERS)
                    .document(uid)
                    .collection(AppConstants.FIRESTORE_AUDIT_LOGS)
                    .document(logId)
                    .set(log.toMap());
        } catch (RuntimeException error) {
            return Tasks.forException(error);
        }
    }

    @NonNull
    public Task<Void> append(@NonNull RemoteAuditLog log) {
        try {
            String uid = requireUid();
            validateId(log.logId);
            return FirebaseFirestore.getInstance()
                    .collection(AppConstants.FIRESTORE_USERS)
                    .document(uid)
                    .collection(AppConstants.FIRESTORE_AUDIT_LOGS)
                    .document(log.logId)
                    .set(log.toMap());
        } catch (RuntimeException error) {
            return Tasks.forException(error);
        }
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
