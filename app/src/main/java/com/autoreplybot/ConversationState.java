package com.autoreplybot;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

public final class ConversationState {
    @NonNull public final String contactId;
    @NonNull public final MessageIntent lastIntent;
    @NonNull public final String conversationSummary;
    @NonNull public final String lastIncomingMessage;
    @NonNull public final String lastOutgoingMessage;
    public final long lastReplyTimestamp;
    public final int consecutiveBotReplies;
    public final boolean unansweredBotMessage;
    public final long updatedAt;

    public ConversationState(@NonNull String contactId, @NonNull MessageIntent lastIntent,
                             @NonNull String conversationSummary,
                             @NonNull String lastIncomingMessage,
                             @NonNull String lastOutgoingMessage, long lastReplyTimestamp,
                             int consecutiveBotReplies, boolean unansweredBotMessage,
                             long updatedAt) {
        this.contactId = contactId;
        this.lastIntent = lastIntent;
        this.conversationSummary = conversationSummary;
        this.lastIncomingMessage = lastIncomingMessage;
        this.lastOutgoingMessage = lastOutgoingMessage;
        this.lastReplyTimestamp = lastReplyTimestamp;
        this.consecutiveBotReplies = Math.max(0, consecutiveBotReplies);
        this.unansweredBotMessage = unansweredBotMessage;
        this.updatedAt = updatedAt;
    }

    @NonNull public static ConversationState empty(@NonNull String contactId) {
        return new ConversationState(contactId, MessageIntent.UNKNOWN, "", "", "",
                0L, 0, false, 0L);
    }

    @NonNull public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("contactId", contactId);
        map.put("lastIntent", lastIntent.name());
        map.put("conversationSummary", conversationSummary);
        map.put("lastIncomingMessage", lastIncomingMessage);
        map.put("lastOutgoingMessage", lastOutgoingMessage);
        map.put("lastReplyTimestamp", lastReplyTimestamp);
        map.put("consecutiveBotReplies", consecutiveBotReplies);
        map.put("unansweredBotMessage", unansweredBotMessage);
        map.put("updatedAt", updatedAt);
        return map;
    }

    @NonNull public static ConversationState fromMap(@NonNull String expectedContactId,
                                                     @NonNull Map<String, Object> map) {
        return new ConversationState(expectedContactId,
                MessageIntent.fromValue(map.get("lastIntent")),
                ModelValues.string(map, "conversationSummary"),
                ModelValues.string(map, "lastIncomingMessage"),
                ModelValues.string(map, "lastOutgoingMessage"),
                ModelValues.longValue(map, "lastReplyTimestamp", 0L),
                ModelValues.intValue(map, "consecutiveBotReplies", 0),
                ModelValues.bool(map, "unansweredBotMessage", false),
                ModelValues.longValue(map, "updatedAt", 0L));
    }
}
