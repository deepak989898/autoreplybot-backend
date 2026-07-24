package com.autoreplybot;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

public final class ConversationMessage {
    public enum Direction { INCOMING, OUTGOING }

    @NonNull public final String messageId;
    @NonNull public final String contactId;
    @NonNull public final Direction direction;
    @NonNull public final String messageText;
    public final long timestamp;
    @NonNull public final MessageIntent intent;
    @NonNull public final String language;
    @NonNull public final String replyText;
    @NonNull public final String notificationHash;
    @NonNull public final String deliveryStatus;
    public final boolean requiresReview;

    public ConversationMessage(@NonNull String messageId, @NonNull String contactId,
                               @NonNull Direction direction, @NonNull String messageText,
                               long timestamp, @NonNull MessageIntent intent,
                               @NonNull String language, @NonNull String replyText,
                               @NonNull String notificationHash, @NonNull String deliveryStatus,
                               boolean requiresReview) {
        this.messageId = messageId;
        this.contactId = contactId;
        this.direction = direction;
        this.messageText = messageText;
        this.timestamp = timestamp;
        this.intent = intent;
        this.language = language;
        this.replyText = replyText;
        this.notificationHash = notificationHash;
        this.deliveryStatus = deliveryStatus;
        this.requiresReview = requiresReview;
    }

    @NonNull public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("messageId", messageId);
        map.put("contactId", contactId);
        map.put("direction", direction.name().toLowerCase());
        map.put("messageText", messageText);
        map.put("timestamp", timestamp);
        map.put("intent", intent.name());
        map.put("language", language);
        map.put("replyText", replyText);
        map.put("notificationHash", notificationHash);
        map.put("deliveryStatus", deliveryStatus);
        map.put("requiresReview", requiresReview);
        return map;
    }

    @NonNull public static ConversationMessage fromMap(@NonNull String expectedContactId,
                                                       @NonNull Map<String, Object> map) {
        String rawDirection = ModelValues.string(map, "direction");
        Direction direction = "outgoing".equalsIgnoreCase(rawDirection)
                ? Direction.OUTGOING : Direction.INCOMING;
        return new ConversationMessage(ModelValues.string(map, "messageId"), expectedContactId,
                direction, ModelValues.string(map, "messageText"),
                ModelValues.longValue(map, "timestamp", 0L),
                MessageIntent.fromValue(map.get("intent")), ModelValues.string(map, "language"),
                ModelValues.string(map, "replyText"), ModelValues.string(map, "notificationHash"),
                ModelValues.string(map, "deliveryStatus"),
                ModelValues.bool(map, "requiresReview", false));
    }
}
