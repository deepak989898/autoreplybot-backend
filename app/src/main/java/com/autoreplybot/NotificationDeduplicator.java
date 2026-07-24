package com.autoreplybot;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Process-safe notification dedup state persisted as hashes and timestamps only.
 * Message text and contact labels are never written to preferences.
 */
public final class NotificationDeduplicator {
    private static final String PREFS = "notification_dedup_v1";
    private static final String KEY_INCOMING = "incoming_hashes";
    private static final String KEY_REPLIES = "reply_hashes";
    private static final String KEY_CONTACTS = "contact_send_times";
    private static final String KEY_TRANSITIONS = "incoming_transitions";
    private static final int MAX_INCOMING = 512;
    private static final int MAX_REPLIES = 256;
    private static final int MAX_CONTACTS = 256;

    private final SharedPreferences prefs;
    private final Object lock = new Object();
    private final Map<String, Long> incoming;
    private final Map<String, Long> replies;
    private final Map<String, Long> contactSends;
    private final Map<String, Long> transitions;

    public NotificationDeduplicator(@NonNull Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        incoming = readRecords(prefs.getString(KEY_INCOMING, ""));
        replies = readRecords(prefs.getString(KEY_REPLIES, ""));
        contactSends = readRecords(prefs.getString(KEY_CONTACTS, ""));
        transitions = readRecords(prefs.getString(KEY_TRANSITIONS, ""));
    }

    /**
     * Atomically records an incoming notification and returns true if it was already seen.
     */
    public boolean isDuplicateAndRecord(@NonNull ParsedNotification parsed, long windowMs) {
        long effectiveWindow = Math.max(1L, windowMs);
        long now = System.currentTimeMillis();
        long bucket = Math.max(0L, parsed.getTimestamp()) / effectiveWindow;
        synchronized (lock) {
            prune(incoming, now - effectiveWindow, MAX_INCOMING);
            for (long candidate = Math.max(0L, bucket - 1L); candidate <= bucket + 1L; candidate++) {
                Long seenAt = incoming.get(notificationHash(parsed, candidate));
                if (seenAt != null && now - seenAt <= effectiveWindow) {
                    persistLocked();
                    return true;
                }
            }
            incoming.put(notificationHash(parsed, bucket), now);
            trim(incoming, MAX_INCOMING);
            persistLocked();
            return false;
        }
    }

    /**
     * Persistently claims the single processing transition for this incoming notification.
     * A null result means this hash has already entered the decision pipeline.
     */
    @Nullable
    public String claimIncomingTransition(@NonNull ParsedNotification parsed, long windowMs) {
        long effectiveWindow = Math.max(1L, windowMs);
        long now = System.currentTimeMillis();
        long bucket = Math.max(0L, parsed.getTimestamp()) / effectiveWindow;
        String token = notificationHash(parsed, bucket);
        synchronized (lock) {
            prune(transitions, now - effectiveWindow, MAX_INCOMING);
            for (long candidate = Math.max(0L, bucket - 1L); candidate <= bucket + 1L; candidate++) {
                if (transitions.containsKey(notificationHash(parsed, candidate))) {
                    persistLocked();
                    return null;
                }
            }
            transitions.put(token, now);
            trim(transitions, MAX_INCOMING);
            persistLocked();
            return token;
        }
    }

    /** Whether this text matches an auto-reply recently sent to this exact conversation. */
    public boolean isKnownReply(@NonNull ParsedNotification parsed, long windowMs) {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            prune(replies, now - Math.max(1L, windowMs), MAX_REPLIES);
            Long recorded = replies.get(replyHash(parsed.getPackageName(),
                    parsed.getStableContactIdentity(), parsed.getMessageText()));
            persistLocked();
            return recorded != null && now - recorded <= Math.max(1L, windowMs);
        }
    }

    /** Records a reply before dispatch, closing the notification callback race. */
    @NonNull
    public String recordReply(@NonNull String packageName, @NonNull String contactIdentity,
                              @NonNull String replyText, long windowMs) {
        String hash = replyHash(packageName, contactIdentity, replyText);
        long now = System.currentTimeMillis();
        synchronized (lock) {
            prune(replies, now - Math.max(1L, windowMs), MAX_REPLIES);
            replies.put(hash, now);
            trim(replies, MAX_REPLIES);
            persistLocked();
        }
        return hash;
    }

    public void removeReplyReservation(@NonNull String hash) {
        synchronized (lock) {
            if (replies.remove(hash) != null) persistLocked();
        }
    }

    public long remainingContactCooldownMs(@NonNull ParsedNotification parsed, long cooldownMs) {
        if (cooldownMs <= 0L) return 0L;
        long now = System.currentTimeMillis();
        synchronized (lock) {
            prune(contactSends, now - cooldownMs, MAX_CONTACTS);
            Long last = contactSends.get(contactHash(parsed.getPackageName(),
                    parsed.getStableContactIdentity()));
            persistLocked();
            if (last == null) return 0L;
            long remaining = cooldownMs - (now - last);
            return Math.max(0L, remaining);
        }
    }

    public void recordSuccessfulReply(@NonNull ParsedNotification parsed) {
        synchronized (lock) {
            contactSends.put(contactHash(parsed.getPackageName(),
                    parsed.getStableContactIdentity()), System.currentTimeMillis());
            trim(contactSends, MAX_CONTACTS);
            persistLocked();
        }
    }

    @NonNull
    static String notificationHash(@NonNull ParsedNotification parsed, long timestampBucket) {
        return sha256(parsed.getPackageName() + '\u0001'
                + parsed.getStableContactIdentity() + '\u0001'
                + NotificationContentExtractor.normalizeForIncomingDedup(parsed.getMessageText())
                + '\u0001' + timestampBucket);
    }

    @NonNull
    private static String replyHash(@NonNull String pkg, @NonNull String contact,
                                    @NonNull String text) {
        return sha256("reply\u0001" + pkg + '\u0001' + contact + '\u0001'
                + NotificationContentExtractor.normalizeForIncomingDedup(text));
    }

    @NonNull
    private static String contactHash(@NonNull String pkg, @NonNull String contact) {
        return sha256("contact\u0001" + pkg + '\u0001' + contact);
    }

    @NonNull
    private static String sha256(@NonNull String source) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format("%02x", b & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    @NonNull
    private static Map<String, Long> readRecords(String raw) {
        Map<String, Long> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String hash = item.optString("h", "");
                long time = item.optLong("t", 0L);
                if (!hash.isEmpty() && time > 0L) out.put(hash, time);
            }
        } catch (JSONException ignored) {
            // A malformed cache safely behaves like an empty cache.
        }
        return out;
    }

    private void persistLocked() {
        prefs.edit()
                .putString(KEY_INCOMING, toJson(incoming))
                .putString(KEY_REPLIES, toJson(replies))
                .putString(KEY_CONTACTS, toJson(contactSends))
                .putString(KEY_TRANSITIONS, toJson(transitions))
                .commit();
    }

    @NonNull
    private static String toJson(@NonNull Map<String, Long> records) {
        JSONArray array = new JSONArray();
        for (Map.Entry<String, Long> entry : records.entrySet()) {
            JSONObject item = new JSONObject();
            try {
                item.put("h", entry.getKey());
                item.put("t", entry.getValue());
                array.put(item);
            } catch (JSONException ignored) {
                // Strings and longs used here are always JSON-safe.
            }
        }
        return array.toString();
    }

    private static void prune(@NonNull Map<String, Long> records, long cutoff, int max) {
        records.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        trim(records, max);
    }

    private static void trim(@NonNull Map<String, Long> records, int max) {
        if (records.size() <= max) return;
        List<Map.Entry<String, Long>> ordered = new ArrayList<>(records.entrySet());
        ordered.sort(Comparator.comparingLong(Map.Entry::getValue));
        for (int i = 0; i < ordered.size() - max; i++) {
            records.remove(ordered.get(i).getKey());
        }
    }
}
