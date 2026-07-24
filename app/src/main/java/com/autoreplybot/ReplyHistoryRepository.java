package com.autoreplybot;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class ReplyHistoryRepository {
    private static final String CACHE_NAMESPACE = "reply_event";
    private final DataRepositorySupport data;

    public ReplyHistoryRepository(@NonNull Context context) {
        data = new DataRepositorySupport(context);
    }

    @NonNull public Task<Void> record(@NonNull ReplyEvent event) {
        try {
            data.cache(CACHE_NAMESPACE, event.eventId, event.toMap());
            return data.userDocument(AppConstants.FIRESTORE_REPLY_HISTORY, event.eventId)
                    .set(event.toMap(), SetOptions.merge());
        } catch (RuntimeException error) {
            return Tasks.forException(error);
        }
    }

    /** Contact filter is mandatory to prevent accidental cross-contact context loading. */
    @NonNull public Task<List<ReplyEvent>> loadForContact(@NonNull String contactId,
                                                         int requestedLimit) {
        DataRepositorySupport.validateId(contactId);
        int limit = Math.max(1, Math.min(100, requestedLimit));
        try {
            return data.userCollection(AppConstants.FIRESTORE_REPLY_HISTORY)
                    .whereEqualTo("contactId", contactId)
                    .orderBy("timestamp", Query.Direction.DESCENDING).limit(limit).get()
                    .continueWith(task -> {
                        if (!task.isSuccessful() || task.getResult() == null) {
                            List<ReplyEvent> cached = cachedForContact(contactId, limit);
                            return cached;
                        }
                        List<ReplyEvent> values = new ArrayList<>();
                        for (DocumentSnapshot snapshot : task.getResult().getDocuments()) {
                            if (snapshot.getData() == null) continue;
                            ReplyEvent event = ReplyEvent.fromMap(snapshot.getData());
                            if (!contactId.equals(event.contactId)) continue;
                            values.add(event);
                            data.cache(CACHE_NAMESPACE, event.eventId, event.toMap());
                        }
                        return values;
                    });
        } catch (RuntimeException error) {
            return Tasks.forException(error);
        }
    }

    @NonNull private List<ReplyEvent> cachedForContact(@NonNull String contactId, int limit) {
        List<ReplyEvent> values = new ArrayList<>();
        for (Map<String, Object> map : data.cachedAll(CACHE_NAMESPACE)) {
            ReplyEvent event = ReplyEvent.fromMap(map);
            if (contactId.equals(event.contactId)) values.add(event);
        }
        values.sort(Comparator.comparingLong((ReplyEvent e) -> e.timestamp).reversed());
        return values.size() <= limit ? values
                : new ArrayList<>(values.subList(0, limit));
    }

    @NonNull public Task<List<ReplyEvent>> loadRecent(int requestedLimit) {
        int limit = Math.max(1, Math.min(100, requestedLimit));
        try {
            return data.userCollection(AppConstants.FIRESTORE_REPLY_HISTORY)
                    .orderBy("timestamp", Query.Direction.DESCENDING).limit(limit).get()
                    .continueWithTask(task -> {
                        if (task.isSuccessful() && task.getResult() != null) {
                            List<ReplyEvent> values = new ArrayList<>();
                            for (DocumentSnapshot snapshot : task.getResult().getDocuments()) {
                                if (snapshot.getData() == null) continue;
                                ReplyEvent event = ReplyEvent.fromMap(snapshot.getData());
                                values.add(event);
                                data.cache(CACHE_NAMESPACE, event.eventId, event.toMap());
                            }
                            return Tasks.forResult(values);
                        }
                        List<ReplyEvent> cached = new ArrayList<>();
                        for (Map<String, Object> map : data.cachedAll(CACHE_NAMESPACE)) {
                            cached.add(ReplyEvent.fromMap(map));
                        }
                        cached.sort(Comparator.comparingLong((ReplyEvent e) -> e.timestamp).reversed());
                        if (!cached.isEmpty()) {
                            return Tasks.forResult(cached.size() <= limit ? cached
                                    : new ArrayList<>(cached.subList(0, limit)));
                        }
                        Exception error = task.getException();
                        return Tasks.forException(error != null ? error
                                : new IllegalStateException("Reply history load failed"));
                    });
        } catch (RuntimeException error) {
            return Tasks.forException(error);
        }
    }
}
