package com.autoreplybot;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

/** Auditable user-visible reply outcome. Hidden reasoning must never be added to this model. */
public final class ReplyEvent {
    @NonNull public final String eventId;
    @NonNull public final String contactId;
    @NonNull public final String incomingMessage;
    @NonNull public final String replyText;
    @NonNull public final ReplyAction action;
    @NonNull public final MessageIntent intent;
    public final double confidence;
    @NonNull public final String reasonCode;
    @NonNull public final String approvalStatus;
    public final long timestamp;
    @NonNull public final SenderCategory senderCategory;
    public final boolean sensitiveContentRedacted;
    public final long processedAt;

    public ReplyEvent(@NonNull String eventId, @NonNull String contactId,
                      @NonNull String incomingMessage, @NonNull String replyText,
                      @NonNull ReplyAction action, @NonNull MessageIntent intent,
                      double confidence, @NonNull String reasonCode,
                      @NonNull String approvalStatus, long timestamp) {
        this(eventId, contactId, incomingMessage, replyText, action, intent, confidence,
                reasonCode, approvalStatus, timestamp, SenderCategory.PERSONAL_HUMAN,
                false, timestamp);
    }

    public ReplyEvent(@NonNull String eventId, @NonNull String contactId,
                      @NonNull String incomingMessage, @NonNull String replyText,
                      @NonNull ReplyAction action, @NonNull MessageIntent intent,
                      double confidence, @NonNull String reasonCode,
                      @NonNull String approvalStatus, long timestamp,
                      @NonNull SenderCategory senderCategory,
                      boolean sensitiveContentRedacted, long processedAt) {
        this.eventId = eventId;
        this.contactId = contactId;
        this.incomingMessage = incomingMessage;
        this.replyText = replyText;
        this.action = action;
        this.intent = intent;
        this.confidence = Math.max(0d, Math.min(1d, confidence));
        this.reasonCode = reasonCode;
        this.approvalStatus = approvalStatus;
        this.timestamp = timestamp;
        this.senderCategory = senderCategory;
        this.sensitiveContentRedacted = sensitiveContentRedacted;
        this.processedAt = processedAt;
    }

    @NonNull public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("eventId", eventId);
        map.put("contactId", contactId);
        map.put("incomingMessage", incomingMessage);
        map.put("replyText", replyText);
        map.put("action", action.name());
        map.put("intent", intent.name());
        map.put("confidence", confidence);
        map.put("reasonCode", reasonCode);
        map.put("approvalStatus", approvalStatus);
        map.put("timestamp", timestamp);
        map.put("senderCategory", senderCategory.name());
        map.put("sensitiveContentRedacted", sensitiveContentRedacted);
        map.put("processedAt", processedAt);
        return map;
    }

    @NonNull public static ReplyEvent fromMap(@NonNull Map<String, Object> map) {
        ReplyAction action;
        try {
            action = ReplyAction.valueOf(ModelValues.string(map, "action").toUpperCase());
        } catch (IllegalArgumentException ignored) {
            action = ReplyAction.NO_REPLY;
        }
        return new ReplyEvent(ModelValues.string(map, "eventId"),
                ModelValues.string(map, "contactId"), ModelValues.string(map, "incomingMessage"),
                ModelValues.string(map, "replyText"), action,
                MessageIntent.fromValue(map.get("intent")),
                ModelValues.doubleValue(map, "confidence", 0d),
                ModelValues.string(map, "reasonCode"), ModelValues.string(map, "approvalStatus"),
                ModelValues.longValue(map, "timestamp", 0L),
                SenderCategory.fromValue(map.get("senderCategory")),
                ModelValues.bool(map, "sensitiveContentRedacted", false),
                ModelValues.longValue(map, "processedAt",
                        ModelValues.longValue(map, "timestamp", 0L)));
    }
}
