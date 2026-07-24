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

/** Minimal CRUD for users/{uid}/sessionRequests/{requestId}. */
public final class RemoteSessionRequestRepository {

    @NonNull
    public Task<RemoteSessionRequest> get(@NonNull String requestId) {
        try {
            validateId(requestId);
            String uid = requireUid();
            return doc(uid, requestId).get().continueWith(task -> {
                DocumentSnapshot snap = task.getResult();
                if (snap == null || !snap.exists() || snap.getData() == null) {
                    throw new IllegalStateException("Session request not found");
                }
                return RemoteSessionRequest.fromMap(requestId, snap.getData());
            });
        } catch (RuntimeException error) {
            return Tasks.forException(error);
        }
    }

    @NonNull
    public Task<Void> upsert(@NonNull RemoteSessionRequest request) {
        try {
            validateId(request.requestId);
            String uid = requireUid();
            return doc(uid, request.requestId).set(request.toMap(), SetOptions.merge());
        } catch (RuntimeException error) {
            return Tasks.forException(error);
        }
    }

    @NonNull
    public Task<Void> markApproved(@NonNull String requestId) {
        return patchStatus(requestId, RemoteSessionRequest.Status.APPROVED, true, false);
    }

    @NonNull
    public Task<Void> markRejected(@NonNull String requestId) {
        return patchStatus(requestId, RemoteSessionRequest.Status.REJECTED, false, true);
    }

    @NonNull
    public Task<Void> markExpired(@NonNull String requestId) {
        return patchStatus(requestId, RemoteSessionRequest.Status.EXPIRED, false, false);
    }

    @NonNull
    private Task<Void> patchStatus(@NonNull String requestId,
                                   @NonNull RemoteSessionRequest.Status status,
                                   boolean setApprovedAt,
                                   boolean setRejectedAt) {
        try {
            validateId(requestId);
            String uid = requireUid();
            Map<String, Object> patch = new HashMap<>();
            patch.put("status", status.wireValue());
            long now = System.currentTimeMillis();
            if (setApprovedAt) patch.put("approvedAt", now);
            if (setRejectedAt) patch.put("rejectedAt", now);
            return doc(uid, requestId).set(patch, SetOptions.merge());
        } catch (RuntimeException error) {
            return Tasks.forException(error);
        }
    }

    @NonNull
    private DocumentReference doc(@NonNull String uid, @NonNull String requestId) {
        return FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(uid)
                .collection(AppConstants.FIRESTORE_SESSION_REQUESTS)
                .document(requestId);
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
