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

public final class ContactProfileRepository {
    private static final String CACHE_NAMESPACE = "contact";
    private final DataRepositorySupport data;

    public ContactProfileRepository(@NonNull Context context) {
        data = new DataRepositorySupport(context);
    }

    /** Opaque, deterministic identity scoped to signed-in user, this app, source app, and sender. */
    @NonNull
    public String createContactId(@NonNull String sourcePackage,
                                  @NonNull String stableSenderIdentity) {
        return data.contactId(sourcePackage, stableSenderIdentity);
    }

    @NonNull
    public Task<ContactProfile> get(@NonNull String contactId) {
        try {
            return data.userDocument(AppConstants.FIRESTORE_CONTACTS, contactId).get()
                    .continueWithTask(task -> {
                        if (task.isSuccessful()) {
                            DocumentSnapshot snapshot = task.getResult();
                            if (snapshot != null && snapshot.exists() && snapshot.getData() != null) {
                                ContactProfile profile = ContactProfile.fromMap(
                                        contactId, snapshot.getData());
                                data.cache(CACHE_NAMESPACE, contactId, profile.toMap());
                                return Tasks.forResult(profile);
                            }
                            return Tasks.forResult(null);
                        }
                        Map<String, Object> cached = data.cached(CACHE_NAMESPACE, contactId);
                        if (cached != null) {
                            return Tasks.forResult(ContactProfile.fromMap(contactId, cached));
                        }
                        // Cloud permissions/connectivity must not stop notification replies.
                        // A missing local profile is safely treated as an unknown contact.
                        return Tasks.forResult(null);
                    });
        } catch (RuntimeException error) {
            return Tasks.forException(error);
        }
    }

    @NonNull
    public Task<ContactProfile> getOrCreateUnknown(@NonNull String contactId,
                                                   @NonNull String displayName) {
        return getOrCreateUnknown(contactId, displayName, ReplyMode.SMART);
    }

    @NonNull
    public Task<ContactProfile> getOrCreateUnknown(@NonNull String contactId,
                                                   @NonNull String displayName,
                                                   @NonNull ReplyMode defaultMode) {
        return get(contactId).continueWithTask(task -> {
            if (!task.isSuccessful()) {
                ContactProfile local = ContactProfile.unknown(
                        contactId, displayName, defaultMode);
                data.cache(CACHE_NAMESPACE, contactId, local.toMap());
                return Tasks.forResult(local);
            }
            ContactProfile existing = task.getResult();
            if (existing != null) return Tasks.forResult(existing);
            ContactProfile created = ContactProfile.unknown(contactId, displayName, defaultMode);
            // save() caches first. Return the usable local profile even if cloud sync is denied.
            return save(created).continueWith(saveTask -> created);
        });
    }

    @NonNull
    public Task<Void> save(@NonNull ContactProfile profile) {
        try {
            data.cache(CACHE_NAMESPACE, profile.contactId, profile.toMap());
            return data.userDocument(AppConstants.FIRESTORE_CONTACTS, profile.contactId)
                    .set(profile.toMap(), SetOptions.merge());
        } catch (RuntimeException error) {
            return Tasks.forException(error);
        }
    }

    /** Lists only profiles observed from notifications; this never reads or uploads the address book. */
    @NonNull
    public Task<List<ContactProfile>> loadObserved(int requestedLimit) {
        int limit = Math.max(1, Math.min(100, requestedLimit));
        try {
            return data.userCollection(AppConstants.FIRESTORE_CONTACTS)
                    .orderBy("updatedAt", Query.Direction.DESCENDING).limit(limit).get()
                    .continueWithTask(task -> {
                        if (task.isSuccessful() && task.getResult() != null) {
                            List<ContactProfile> values = new ArrayList<>();
                            for (DocumentSnapshot snapshot : task.getResult().getDocuments()) {
                                if (snapshot.getData() == null) continue;
                                ContactProfile profile = ContactProfile.fromMap(
                                        snapshot.getId(), snapshot.getData());
                                values.add(profile);
                                data.cache(CACHE_NAMESPACE, profile.contactId, profile.toMap());
                            }
                            return Tasks.forResult(values);
                        }
                        List<ContactProfile> cached = cachedProfiles(limit);
                        if (!cached.isEmpty()) return Tasks.forResult(cached);
                        Exception error = task.getException();
                        return Tasks.forException(error != null ? error
                                : new IllegalStateException("Contacts load failed"));
                    });
        } catch (RuntimeException error) {
            return Tasks.forException(error);
        }
    }

    @NonNull
    private List<ContactProfile> cachedProfiles(int limit) {
        List<ContactProfile> values = new ArrayList<>();
        for (Map<String, Object> map : data.cachedAll(CACHE_NAMESPACE)) {
            String id = ModelValues.string(map, "contactId");
            if (!id.isEmpty()) values.add(ContactProfile.fromMap(id, map));
        }
        values.sort(Comparator.comparingLong((ContactProfile value) -> value.updatedAt).reversed());
        return values.size() <= limit ? values : new ArrayList<>(values.subList(0, limit));
    }
}
