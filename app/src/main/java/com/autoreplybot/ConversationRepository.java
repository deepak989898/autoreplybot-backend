package com.autoreplybot;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.SetOptions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class ConversationRepository {
    private static final String STATE_CACHE = "conversation_state";
    private static final String MESSAGES_CACHE = "conversation_messages";
    private final DataRepositorySupport data;

    public ConversationRepository(@NonNull Context context) {
        data = new DataRepositorySupport(context);
    }

    @NonNull private DocumentReference conversation(@NonNull String contactId) {
        return data.userDocument(AppConstants.FIRESTORE_CONVERSATIONS, contactId);
    }

    @NonNull
    public Task<ConversationState> loadState(@NonNull String contactId) {
        try {
            return conversation(contactId).get().continueWithTask(task -> {
                if (task.isSuccessful()) {
                    DocumentSnapshot snapshot = task.getResult();
                    if (snapshot != null && snapshot.exists() && snapshot.getData() != null) {
                        ConversationState state = ConversationState.fromMap(
                                contactId, snapshot.getData());
                        data.cache(STATE_CACHE, contactId, state.toMap());
                        return Tasks.forResult(state);
                    }
                    return Tasks.forResult(ConversationState.empty(contactId));
                }
                Map<String, Object> cached = data.cached(STATE_CACHE, contactId);
                if (cached != null) {
                    return Tasks.forResult(ConversationState.fromMap(contactId, cached));
                }
                // Start with an isolated empty context when cloud data is unavailable.
                return Tasks.forResult(ConversationState.empty(contactId));
            });
        } catch (RuntimeException error) {
            return Tasks.forException(error);
        }
    }

    @NonNull
    public Task<Void> saveState(@NonNull ConversationState state) {
        try {
            data.cache(STATE_CACHE, state.contactId, state.toMap());
            return conversation(state.contactId).set(state.toMap(), SetOptions.merge());
        } catch (RuntimeException error) {
            return Tasks.forException(error);
        }
    }

    @NonNull
    public Task<Void> saveMessage(@NonNull ConversationMessage message) {
        try {
            DataRepositorySupport.validateId(message.messageId);
            List<ConversationMessage> local = cachedMessages(message.contactId, 49);
            local.removeIf(existing -> existing.messageId.equals(message.messageId));
            local.add(message);
            cacheMessages(message.contactId, local);
            return conversation(message.contactId).collection(AppConstants.FIRESTORE_MESSAGES)
                    .document(message.messageId).set(message.toMap(), SetOptions.merge());
        } catch (RuntimeException error) {
            return Tasks.forException(error);
        }
    }

    /** Returns oldest-to-newest messages from this contact's subcollection only. */
    @NonNull
    public Task<List<ConversationMessage>> loadRecentMessages(@NonNull String contactId,
                                                              int requestedLimit) {
        int limit = Math.max(1, Math.min(50, requestedLimit));
        try {
            return conversation(contactId).collection(AppConstants.FIRESTORE_MESSAGES)
                    .orderBy("timestamp", Query.Direction.DESCENDING).limit(limit).get()
                    .continueWithTask(task -> {
                        if (task.isSuccessful() && task.getResult() != null) {
                            List<ConversationMessage> messages = new ArrayList<>();
                            for (DocumentSnapshot snapshot : task.getResult().getDocuments()) {
                                Map<String, Object> map = snapshot.getData();
                                if (map == null) continue;
                                String storedContactId = ModelValues.string(map, "contactId");
                                if (!contactId.equals(storedContactId)) continue;
                                messages.add(ConversationMessage.fromMap(contactId, map));
                            }
                            Collections.reverse(messages);
                            cacheMessages(contactId, messages);
                            return Tasks.forResult(messages);
                        }
                        List<ConversationMessage> cached = cachedMessages(contactId, limit);
                        if (!cached.isEmpty()) return Tasks.forResult(cached);
                        // Empty local history is safer than blocking every auto-reply.
                        return Tasks.forResult(Collections.emptyList());
                    });
        } catch (RuntimeException error) {
            return Tasks.forException(error);
        }
    }

    private void cacheMessages(@NonNull String contactId,
                               @NonNull List<ConversationMessage> messages) {
        JSONArray array = new JSONArray();
        for (ConversationMessage message : messages) array.put(new JSONObject(message.toMap()));
        Map<String, Object> wrapper = new java.util.HashMap<>();
        wrapper.put("contactId", contactId);
        wrapper.put("itemsJson", array.toString());
        data.cache(MESSAGES_CACHE, contactId, wrapper);
    }

    @NonNull
    private List<ConversationMessage> cachedMessages(@NonNull String contactId, int limit) {
        Map<String, Object> wrapper = data.cached(MESSAGES_CACHE, contactId);
        if (wrapper == null || !contactId.equals(ModelValues.string(wrapper, "contactId"))) {
            return Collections.emptyList();
        }
        List<ConversationMessage> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(ModelValues.string(wrapper, "itemsJson"));
            int start = Math.max(0, array.length() - limit);
            for (int i = start; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                Map<String, Object> map = jsonMap(object);
                if (contactId.equals(ModelValues.string(map, "contactId"))) {
                    result.add(ConversationMessage.fromMap(contactId, map));
                }
            }
        } catch (JSONException ignored) {
            return Collections.emptyList();
        }
        return result;
    }

    @NonNull
    private static Map<String, Object> jsonMap(@NonNull JSONObject object) throws JSONException {
        Map<String, Object> map = new HashMap<>();
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = object.get(key);
            map.put(key, value == JSONObject.NULL ? null : value);
        }
        return map;
    }
}
