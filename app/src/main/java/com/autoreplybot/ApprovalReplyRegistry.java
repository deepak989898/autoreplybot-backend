package com.autoreplybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Process-only storage for live notification reply actions. PendingIntent is never persisted. */
public final class ApprovalReplyRegistry {
    private static final long DEFAULT_TTL_MS = 15L * 60L * 1000L;
    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();

    private ApprovalReplyRegistry() {}

    private static final class Entry {
        final NotificationReplyHelper.ReplyPayload payload;
        final long expiresAt;

        Entry(@NonNull NotificationReplyHelper.ReplyPayload payload, long expiresAt) {
            this.payload = payload;
            this.expiresAt = expiresAt;
        }
    }

    public static void register(@NonNull String approvalId,
                                @NonNull NotificationReplyHelper.ReplyPayload payload) {
        register(approvalId, payload, DEFAULT_TTL_MS);
    }

    static void register(@NonNull String approvalId,
                         @NonNull NotificationReplyHelper.ReplyPayload payload, long ttlMs) {
        purgeExpired();
        ENTRIES.put(approvalId,
                new Entry(payload, System.currentTimeMillis() + Math.max(1L, ttlMs)));
    }

    @Nullable
    public static NotificationReplyHelper.ReplyPayload take(@NonNull String approvalId) {
        Entry entry = ENTRIES.remove(approvalId);
        if (entry == null || entry.expiresAt <= System.currentTimeMillis()) return null;
        return entry.payload;
    }

    public static boolean isAvailable(@NonNull String approvalId) {
        Entry entry = ENTRIES.get(approvalId);
        if (entry == null) return false;
        if (entry.expiresAt <= System.currentTimeMillis()) {
            ENTRIES.remove(approvalId, entry);
            return false;
        }
        return true;
    }

    public static void remove(@NonNull String approvalId) {
        ENTRIES.remove(approvalId);
    }

    private static void purgeExpired() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Entry> item : ENTRIES.entrySet()) {
            if (item.getValue().expiresAt <= now) ENTRIES.remove(item.getKey(), item.getValue());
        }
    }
}
