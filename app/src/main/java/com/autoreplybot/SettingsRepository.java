package com.autoreplybot;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Persists auto-reply settings to Firestore and a fast local cache.
 * OpenAI API key is supplied at build time via {@code OPENAI_API_KEY} in {@code local.properties}.
 */
public class SettingsRepository {

    private static final String KEY_COMPLETE_SETTINGS_CACHE = "complete_settings_cache_v2";
    private final SharedPreferences cachePrefs;

    public SettingsRepository(@NonNull Context context) {
        this.cachePrefs = context.getApplicationContext()
                .getSharedPreferences(AppConstants.PREFS_AUTO_REPLY, Context.MODE_PRIVATE);
    }

    @Nullable
    public String getOpenAiApiKey() {
        String k = BuildConfig.OPENAI_API_KEY;
        return !TextUtils.isEmpty(k) ? k.trim() : null;
    }

    public void cacheLocally(@NonNull UserSettings settings) {
        cachePrefs.edit()
                .putString(KEY_COMPLETE_SETTINGS_CACHE,
                        new JSONObject(settings.toFirestoreMap()).toString())
                .putString(AppConstants.KEY_ENABLED_PACKAGES,
                        new JSONObject(settings.getEnabledPackagesSnapshot()).toString())
                .putString(AppConstants.KEY_BUSINESS_INSTRUCTIONS, settings.getBusinessInstructions())
                .putString(AppConstants.KEY_PACKAGE_INSTRUCTIONS_JSON,
                        new JSONObject(settings.getPackageInstructions()).toString())
                .putString(AppConstants.KEY_REPLY_LANGUAGE, settings.getReplyLanguage().getFirestoreValue())
                .putString(AppConstants.KEY_CONTACT_FILTER, settings.getContactFilter().getFirestoreValue())
                .putString(AppConstants.KEY_WHITELIST_NUMBERS, settings.getWhitelistNumbers())
                .apply();
    }

    @NonNull
    public UserSettings readCached() {
        String complete = cachePrefs.getString(KEY_COMPLETE_SETTINGS_CACHE, "");
        if (!TextUtils.isEmpty(complete)) {
            try {
                return UserSettings.fromFirestoreMap(jsonObjectToMap(new JSONObject(complete)));
            } catch (JSONException ignored) {
                // Fall through to the legacy per-key cache and defaults.
            }
        }
        UserSettings s = new UserSettings();
        s.setEnabledPackages(parseEnabledPackages(
                cachePrefs.getString(AppConstants.KEY_ENABLED_PACKAGES, "")));

        Map<String, Object> legacyMap = new HashMap<>();
        boolean legacy = cachePrefs.getBoolean(AppConstants.KEY_WHATSAPP_ENABLED, false);
        legacyMap.put(AppConstants.KEY_WHATSAPP_ENABLED, legacy);
        if (cachePrefs.contains(AppConstants.KEY_PKG_WHATSAPP)) {
            legacyMap.put(AppConstants.KEY_PKG_WHATSAPP,
                    cachePrefs.getBoolean(AppConstants.KEY_PKG_WHATSAPP, false));
        }
        if (cachePrefs.contains(AppConstants.KEY_PKG_WHATSAPP_BUSINESS)) {
            legacyMap.put(AppConstants.KEY_PKG_WHATSAPP_BUSINESS,
                    cachePrefs.getBoolean(AppConstants.KEY_PKG_WHATSAPP_BUSINESS, false));
        }
        legacyMap.put(AppConstants.KEY_DUAL_EXTRA_ENABLED,
                cachePrefs.getBoolean(AppConstants.KEY_DUAL_EXTRA_ENABLED, false));
        legacyMap.put(AppConstants.KEY_DUAL_EXTRA_PACKAGES,
                cachePrefs.getString(AppConstants.KEY_DUAL_EXTRA_PACKAGES, ""));
        UserSettings.migrateLegacyPackageToggles(s, legacyMap);

        s.setBusinessInstructions(cachePrefs.getString(AppConstants.KEY_BUSINESS_INSTRUCTIONS, ""));
        s.setPackageInstructions(parsePackageInstructions(
                cachePrefs.getString(AppConstants.KEY_PACKAGE_INSTRUCTIONS_JSON, "")));
        s.setReplyLanguage(UserSettings.ReplyLanguage.fromFirestore(
                cachePrefs.getString(AppConstants.KEY_REPLY_LANGUAGE, null)));
        s.setContactFilter(UserSettings.ContactFilter.fromFirestore(
                cachePrefs.getString(AppConstants.KEY_CONTACT_FILTER, null)));
        s.setWhitelistNumbers(cachePrefs.getString(AppConstants.KEY_WHITELIST_NUMBERS, ""));
        return s;
    }

    @NonNull
    private static Map<String, Boolean> parseEnabledPackages(@Nullable String rawJson) {
        Map<String, Boolean> out = new HashMap<>();
        if (TextUtils.isEmpty(rawJson)) {
            return out;
        }
        try {
            JSONObject json = new JSONObject(rawJson);
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (json.optBoolean(key, false)) {
                    out.put(key, true);
                }
            }
        } catch (JSONException ignored) {
            // Ignore malformed cache; cloud values or defaults can recover.
        }
        return out;
    }

    @NonNull
    private static Map<String, String> parsePackageInstructions(@Nullable String rawJson) {
        Map<String, String> out = new HashMap<>();
        if (TextUtils.isEmpty(rawJson)) {
            return out;
        }
        try {
            JSONObject json = new JSONObject(rawJson);
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = json.optString(key, "").trim();
                if (!value.isEmpty()) {
                    out.put(key, value);
                }
            }
        } catch (JSONException ignored) {
            // Ignore malformed cache; cloud values or defaults can recover.
        }
        return out;
    }

    @NonNull
    private static Map<String, Object> jsonObjectToMap(@NonNull JSONObject object)
            throws JSONException {
        Map<String, Object> result = new HashMap<>();
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            result.put(key, jsonValue(object.get(key)));
        }
        return result;
    }

    @Nullable
    private static Object jsonValue(@Nullable Object value) throws JSONException {
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof JSONObject) return jsonObjectToMap((JSONObject) value);
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) out.add(jsonValue(array.get(i)));
            return out;
        }
        return value;
    }

    @Nullable
    private DocumentReference settingsRef() {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) return null;
        return FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(u.getUid())
                .collection(AppConstants.FIRESTORE_SETTINGS)
                .document("config");
    }

    @NonNull
    public Task<Void> pushToFirestore(@NonNull UserSettings settings) {
        DocumentReference ref = settingsRef();
        if (ref == null) {
            return com.google.android.gms.tasks.Tasks.forException(new IllegalStateException("Not signed in"));
        }
        return ref.set(settings.toFirestoreMap(), SetOptions.merge());
    }

    @NonNull
    public Task<UserSettings> pullFromFirestore() {
        DocumentReference ref = settingsRef();
        if (ref == null) {
            return com.google.android.gms.tasks.Tasks.forException(new IllegalStateException("Not signed in"));
        }
        return ref.get().continueWith(task -> {
            if (!task.isSuccessful() || task.getResult() == null) {
                throw task.getException() != null ? task.getException() : new IllegalStateException("Load failed");
            }
            DocumentSnapshot snap = task.getResult();
            if (!snap.exists()) {
                UserSettings defaults = new UserSettings();
                cacheLocally(defaults);
                return defaults;
            }
            Map<String, Object> data = snap.getData();
            if (data == null) {
                UserSettings defaults = new UserSettings();
                cacheLocally(defaults);
                return defaults;
            }
            UserSettings parsed = UserSettings.fromFirestoreMap(data);
            cacheLocally(parsed);
            return parsed;
        });
    }
}
