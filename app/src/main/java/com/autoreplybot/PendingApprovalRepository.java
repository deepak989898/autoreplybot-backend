package com.autoreplybot;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class PendingApprovalRepository {
    private static final String CACHE_NAMESPACE = "approval";
    private final DataRepositorySupport data;

    public PendingApprovalRepository(@NonNull Context context) {
        data = new DataRepositorySupport(context);
    }

    @NonNull public Task<Void> save(@NonNull PendingApproval approval) {
        try {
            data.cache(CACHE_NAMESPACE, approval.approvalId, approval.toMap());
            return data.userDocument(AppConstants.FIRESTORE_PENDING_APPROVALS,
                    approval.approvalId).set(approval.toMap(), SetOptions.merge());
        } catch (RuntimeException error) {
            return Tasks.forException(error);
        }
    }

    @NonNull public Task<PendingApproval> get(@NonNull String approvalId) {
        try {
            return data.userDocument(AppConstants.FIRESTORE_PENDING_APPROVALS, approvalId).get()
                    .continueWithTask(task -> {
                        if (task.isSuccessful() && task.getResult() != null
                                && task.getResult().exists() && task.getResult().getData() != null) {
                            PendingApproval value = PendingApproval.fromMap(task.getResult().getData());
                            data.cache(CACHE_NAMESPACE, approvalId, value.toMap());
                            return Tasks.forResult(value);
                        }
                        Map<String, Object> cached = data.cached(CACHE_NAMESPACE, approvalId);
                        if (cached != null) return Tasks.forResult(PendingApproval.fromMap(cached));
                        Exception error = task.getException();
                        return Tasks.forException(error != null ? error
                                : new IllegalStateException("Approval not found"));
                    });
        } catch (RuntimeException error) {
            return Tasks.forException(error);
        }
    }

    @NonNull public Task<List<PendingApproval>> loadPending(int requestedLimit) {
        int limit = Math.max(1, Math.min(100, requestedLimit));
        try {
            return data.userCollection(AppConstants.FIRESTORE_PENDING_APPROVALS)
                    .whereEqualTo("status", PendingApproval.Status.PENDING.name())
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(limit).get()
                    .continueWithTask(task -> {
                        if (!task.isSuccessful() || task.getResult() == null) {
                            List<PendingApproval> cached = cachedPending(limit);
                            if (!cached.isEmpty()) return Tasks.forResult(cached);
                            Exception error = task.getException();
                            return Tasks.forException(error != null ? error
                                    : new IllegalStateException("Approval query failed"));
                        }
                        List<PendingApproval> values = new ArrayList<>();
                        for (DocumentSnapshot snapshot : task.getResult().getDocuments()) {
                            if (snapshot.getData() == null) continue;
                            PendingApproval value = PendingApproval.fromMap(snapshot.getData());
                            values.add(value);
                            data.cache(CACHE_NAMESPACE, value.approvalId, value.toMap());
                        }
                        values.sort(Comparator.comparingLong(
                                (PendingApproval value) -> value.createdAt).reversed());
                        return Tasks.forResult(values);
                    });
        } catch (RuntimeException error) {
            return Tasks.forException(error);
        }
    }

    @NonNull private List<PendingApproval> cachedPending(int limit) {
        List<PendingApproval> values = new ArrayList<>();
        for (Map<String, Object> map : data.cachedAll(CACHE_NAMESPACE)) {
            PendingApproval value = PendingApproval.fromMap(map);
            if (value.status == PendingApproval.Status.PENDING) values.add(value);
        }
        values.sort(Comparator.comparingLong(
                (PendingApproval value) -> value.createdAt).reversed());
        return values.size() <= limit ? values : new ArrayList<>(values.subList(0, limit));
    }

    /** Atomically claims a pending item so repeated taps cannot dispatch twice. */
    @NonNull public Task<Boolean> claimForSend(@NonNull String approvalId) {
        return transition(approvalId, PendingApproval.Status.PENDING,
                PendingApproval.Status.SENDING);
    }

    @NonNull public Task<Boolean> markSent(@NonNull String approvalId) {
        return transition(approvalId, PendingApproval.Status.SENDING,
                PendingApproval.Status.SENT);
    }

    @NonNull public Task<Boolean> markExpiredAfterClaim(@NonNull String approvalId) {
        return transition(approvalId, PendingApproval.Status.SENDING,
                PendingApproval.Status.EXPIRED);
    }

    @NonNull public Task<Boolean> ignore(@NonNull String approvalId) {
        return transition(approvalId, PendingApproval.Status.PENDING,
                PendingApproval.Status.IGNORED);
    }

    @NonNull public Task<Boolean> expire(@NonNull String approvalId) {
        return transition(approvalId, PendingApproval.Status.PENDING,
                PendingApproval.Status.EXPIRED);
    }

    @NonNull public Task<Boolean> updateSuggestion(@NonNull String approvalId,
                                                   @NonNull String suggestion) {
        return get(approvalId).continueWithTask(task -> {
            if (!task.isSuccessful() || task.getResult() == null) {
                Exception error = task.getException();
                return Tasks.forException(error != null ? error
                        : new IllegalStateException("Approval not found"));
            }
            PendingApproval current = task.getResult();
            if (current.status != PendingApproval.Status.PENDING) return Tasks.forResult(false);
            return save(current.withSuggestedReply(suggestion, System.currentTimeMillis()))
                    .continueWith(saveTask -> saveTask.isSuccessful());
        });
    }

    @NonNull private Task<Boolean> transition(@NonNull String approvalId,
                                              @NonNull PendingApproval.Status expected,
                                              @NonNull PendingApproval.Status next) {
        try {
            DocumentReference ref = data.userDocument(
                    AppConstants.FIRESTORE_PENDING_APPROVALS, approvalId);
            FirebaseFirestore db = ref.getFirestore();
            return db.runTransaction(transaction -> {
                DocumentSnapshot snapshot = transaction.get(ref);
                if (!snapshot.exists() || snapshot.getData() == null) return false;
                PendingApproval current = PendingApproval.fromMap(snapshot.getData());
                if (current.status != expected) return false;
                PendingApproval updated = current.withStatus(next, System.currentTimeMillis());
                transaction.set(ref, updated.toMap(), SetOptions.merge());
                return true;
            }).continueWith(task -> {
                if (!task.isSuccessful()) throw task.getException();
                if (Boolean.TRUE.equals(task.getResult())) {
                    Map<String, Object> cached = data.cached(CACHE_NAMESPACE, approvalId);
                    if (cached != null) {
                        PendingApproval current = PendingApproval.fromMap(cached);
                        data.cache(CACHE_NAMESPACE, approvalId,
                                current.withStatus(next, System.currentTimeMillis()).toMap());
                    }
                }
                return Boolean.TRUE.equals(task.getResult());
            });
        } catch (RuntimeException error) {
            return Tasks.forException(error);
        }
    }
}
