package com.autoreplybot;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists recent WhatsApp chat turns per conversation so follow-up questions stay coherent.
 * Stored locally only (not synced to Firebase).
 */
public final class ConversationMemoryStore {

    private static final String TAG = "ConversationMemory";

    /** Max chat-completions messages to send (alternating user/assistant), not counting system or latest user. */
    private static final int MAX_STORED_MESSAGES = 24;

    private static final int MAX_CHARS_PER_MESSAGE = 1200;

    private final SharedPreferences prefs;
    @NonNull
    private final String keyPrefix;

    public ConversationMemoryStore(@NonNull Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(AppConstants.PREFS_AUTO_REPLY, Context.MODE_PRIVATE);
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        keyPrefix = u != null ? "v1_" + u.getUid() + "_" : "v1_anon_";
    }

    @NonNull
    private String prefsKey(@NonNull String convKey) {
        int h = convKey.hashCode();
        return keyPrefix + Integer.toHexString(h) + "_" + Integer.toHexString(convKey.length());
    }

    /**
     * Prior turns only — caller appends the current customer message via {@link OpenAiClient}.
     */
    @NonNull
    public List<ChatHistoryMessage> loadPriorTurns(@NonNull String convKey) {
        String raw = prefs.getString(prefsKey(convKey), null);
        if (TextUtils.isEmpty(raw)) {
            return new ArrayList<>();
        }
        List<ChatHistoryMessage> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String role = o.optString("role", "");
                String content = o.optString("content", "");
                if (TextUtils.isEmpty(role) || TextUtils.isEmpty(content)) continue;
                if (!"user".equals(role) && !"assistant".equals(role)) continue;
                out.add(new ChatHistoryMessage(role, content));
            }
        } catch (JSONException e) {
            Log.w(TAG, "Corrupt history — clearing", e);
            prefs.edit().remove(prefsKey(convKey)).apply();
        }
        return trimToLimit(out);
    }

    /** Call after a reply was sent successfully. Stores this customer's text and our reply. */
    public void appendExchange(@NonNull String convKey,
                               @NonNull String customerIncoming,
                               @NonNull String assistantReply) {
        String ck = prefsKey(convKey);
        JSONArray arr;
        try {
            String raw = prefs.getString(ck, null);
            arr = TextUtils.isEmpty(raw) ? new JSONArray() : new JSONArray(raw);
        } catch (JSONException e) {
            arr = new JSONArray();
        }
        try {
            JSONObject u = new JSONObject();
            u.put("role", "user");
            u.put("content", truncate(customerIncoming));

            JSONObject a = new JSONObject();
            a.put("role", "assistant");
            a.put("content", truncate(assistantReply));

            arr.put(u);
            arr.put(a);

            while (arr.length() > MAX_STORED_MESSAGES) {
                JSONArray next = new JSONArray();
                for (int i = 2; i < arr.length(); i++) {
                    next.put(arr.get(i));
                }
                arr = next;
            }

            prefs.edit().putString(ck, arr.toString()).apply();
        } catch (JSONException e) {
            Log.e(TAG, "appendExchange failed", e);
        }
    }

    @NonNull
    private static List<ChatHistoryMessage> trimToLimit(@NonNull List<ChatHistoryMessage> in) {
        if (in.size() <= MAX_STORED_MESSAGES) {
            return in;
        }
        int drop = in.size() - MAX_STORED_MESSAGES;
        return new ArrayList<>(in.subList(drop, in.size()));
    }

    @NonNull
    private static String truncate(@NonNull String s) {
        String t = s.trim();
        if (t.length() <= MAX_CHARS_PER_MESSAGE) {
            return t;
        }
        return t.substring(0, MAX_CHARS_PER_MESSAGE) + "…";
    }
}
