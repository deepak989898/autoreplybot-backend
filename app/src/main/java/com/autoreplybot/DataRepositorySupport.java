package com.autoreplybot;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Shared auth scoping, contact identity, and bounded local fallback primitives. */
final class DataRepositorySupport {
    private static final int MAX_CACHE_ENTRIES = 120;
    private final Context app;
    private final SharedPreferences prefs;

    DataRepositorySupport(@NonNull Context context) {
        app = context.getApplicationContext();
        prefs = app.getSharedPreferences(AppConstants.PREFS_DATA_CACHE, Context.MODE_PRIVATE);
    }

    @NonNull String requireUid() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) throw new IllegalStateException("Not signed in");
        return user.getUid();
    }

    @NonNull CollectionReference userCollection(@NonNull String collection) {
        return FirebaseFirestore.getInstance().collection(AppConstants.FIRESTORE_USERS)
                .document(requireUid()).collection(collection);
    }

    @NonNull DocumentReference userDocument(@NonNull String collection, @NonNull String id) {
        validateId(id);
        return userCollection(collection).document(id);
    }

    @NonNull String contactId(@NonNull String packageName, @NonNull String senderIdentity) {
        String material = requireUid() + "\u0000" + app.getPackageName() + "\u0000"
                + packageName.trim().toLowerCase() + "\u0000"
                + senderIdentity.trim().toLowerCase();
        return sha256(material);
    }

    @NonNull private static String sha256(@NonNull String material) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            final char[] hex = "0123456789abcdef".toCharArray();
            for (byte value : bytes) {
                int unsigned = value & 0xff;
                result.append(hex[unsigned >>> 4]).append(hex[unsigned & 0x0f]);
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    static void validateId(@NonNull String id) {
        if (!id.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Invalid document identifier");
        }
    }

    void cache(@NonNull String namespace, @NonNull String id, @NonNull Map<String, Object> map) {
        validateId(id);
        String scopedNamespace = scopedNamespace(namespace);
        String key = "data_" + scopedNamespace + "_" + id;
        String indexKey = "index_" + scopedNamespace;
        List<String> ids = readIndex(indexKey);
        ids.remove(id);
        ids.add(id);
        SharedPreferences.Editor edit = prefs.edit().putString(key, new JSONObject(map).toString());
        while (ids.size() > MAX_CACHE_ENTRIES) {
            String removed = ids.remove(0);
            edit.remove("data_" + scopedNamespace + "_" + removed);
        }
        edit.putString(indexKey, new org.json.JSONArray(ids).toString()).apply();
    }

    @Nullable Map<String, Object> cached(@NonNull String namespace, @NonNull String id) {
        validateId(id);
        String scopedNamespace = scopedNamespace(namespace);
        String raw = prefs.getString("data_" + scopedNamespace + "_" + id, null);
        if (raw == null) return null;
        try {
            return toMap(new JSONObject(raw));
        } catch (JSONException ignored) {
            prefs.edit().remove("data_" + scopedNamespace + "_" + id).apply();
            return null;
        }
    }

    @NonNull List<Map<String, Object>> cachedAll(@NonNull String namespace) {
        String scopedNamespace = scopedNamespace(namespace);
        List<Map<String, Object>> values = new ArrayList<>();
        for (String id : readIndex("index_" + scopedNamespace)) {
            String raw = prefs.getString("data_" + scopedNamespace + "_" + id, null);
            if (raw == null) continue;
            try {
                values.add(toMap(new JSONObject(raw)));
            } catch (JSONException ignored) {
                // Skip a corrupt item; later cloud reads can repopulate it.
            }
        }
        return values;
    }

    void removeCached(@NonNull String namespace, @NonNull String id) {
        validateId(id);
        prefs.edit().remove("data_" + scopedNamespace(namespace) + "_" + id).apply();
    }

    @NonNull private String scopedNamespace(@NonNull String namespace) {
        return sha256(requireUid()).substring(0, 16) + "_" + namespace;
    }

    @NonNull private List<String> readIndex(@NonNull String key) {
        List<String> result = new ArrayList<>();
        String raw = prefs.getString(key, "[]");
        try {
            org.json.JSONArray array = new org.json.JSONArray(raw);
            for (int i = 0; i < array.length(); i++) result.add(array.getString(i));
        } catch (JSONException ignored) {
            // A corrupt index is safely rebuilt as entries are written.
        }
        return result;
    }

    @NonNull private static Map<String, Object> toMap(@NonNull JSONObject json)
            throws JSONException {
        Map<String, Object> map = new HashMap<>();
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = json.get(key);
            map.put(key, value == JSONObject.NULL ? null : value);
        }
        return map;
    }
}
