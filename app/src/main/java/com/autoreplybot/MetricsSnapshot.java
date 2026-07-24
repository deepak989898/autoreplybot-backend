package com.autoreplybot;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

public final class MetricsSnapshot {
    @NonNull public final String day;
    public final long processedMessages;
    public final long autoSentReplies;
    public final long approvalPending;
    public final long duplicatesBlocked;
    public final long sensitiveBlocked;

    public MetricsSnapshot(@NonNull String day, long processedMessages, long autoSentReplies,
                           long approvalPending, long duplicatesBlocked, long sensitiveBlocked) {
        this.day = day;
        this.processedMessages = Math.max(0L, processedMessages);
        this.autoSentReplies = Math.max(0L, autoSentReplies);
        this.approvalPending = Math.max(0L, approvalPending);
        this.duplicatesBlocked = Math.max(0L, duplicatesBlocked);
        this.sensitiveBlocked = Math.max(0L, sensitiveBlocked);
    }

    @NonNull public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("day", day);
        map.put("processedMessages", processedMessages);
        map.put("autoSentReplies", autoSentReplies);
        map.put("approvalPending", approvalPending);
        map.put("duplicatesBlocked", duplicatesBlocked);
        map.put("sensitiveBlocked", sensitiveBlocked);
        return map;
    }

    @NonNull public static MetricsSnapshot fromMap(@NonNull String day,
                                                   @NonNull Map<String, Object> map) {
        return new MetricsSnapshot(day, ModelValues.longValue(map, "processedMessages", 0L),
                ModelValues.longValue(map, "autoSentReplies", 0L),
                ModelValues.longValue(map, "approvalPending", 0L),
                ModelValues.longValue(map, "duplicatesBlocked", 0L),
                ModelValues.longValue(map, "sensitiveBlocked", 0L));
    }
}
