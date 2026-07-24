package com.autoreplybot;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

public final class PendingApproval {
    public enum Status { PENDING, SENDING, SENT, IGNORED, EXPIRED }

    @NonNull public final String approvalId;
    @NonNull public final String contactId;
    @NonNull public final String incomingMessage;
    @NonNull public final String suggestedReply;
    @NonNull public final MessageIntent intent;
    @NonNull public final String reasonCode;
    @NonNull public final Status status;
    public final long createdAt;
    public final long updatedAt;

    public PendingApproval(@NonNull String approvalId, @NonNull String contactId,
                           @NonNull String incomingMessage, @NonNull String suggestedReply,
                           @NonNull MessageIntent intent, @NonNull String reasonCode,
                           @NonNull Status status, long createdAt, long updatedAt) {
        this.approvalId = approvalId;
        this.contactId = contactId;
        this.incomingMessage = incomingMessage;
        this.suggestedReply = suggestedReply;
        this.intent = intent;
        this.reasonCode = reasonCode;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    @NonNull public PendingApproval withStatus(@NonNull Status value, long when) {
        return new PendingApproval(approvalId, contactId, incomingMessage, suggestedReply,
                intent, reasonCode, value, createdAt, when);
    }

    @NonNull public PendingApproval withSuggestedReply(@NonNull String value, long when) {
        return new PendingApproval(approvalId, contactId, incomingMessage, value.trim(),
                intent, reasonCode, status, createdAt, when);
    }

    @NonNull public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("approvalId", approvalId);
        map.put("contactId", contactId);
        map.put("incomingMessage", incomingMessage);
        map.put("suggestedReply", suggestedReply);
        map.put("intent", intent.name());
        map.put("reasonCode", reasonCode);
        map.put("status", status.name());
        map.put("createdAt", createdAt);
        map.put("updatedAt", updatedAt);
        return map;
    }

    @NonNull public static PendingApproval fromMap(@NonNull Map<String, Object> map) {
        Status status;
        try {
            status = Status.valueOf(ModelValues.string(map, "status").toUpperCase());
        } catch (IllegalArgumentException ignored) {
            status = Status.PENDING;
        }
        return new PendingApproval(ModelValues.string(map, "approvalId"),
                ModelValues.string(map, "contactId"), ModelValues.string(map, "incomingMessage"),
                ModelValues.string(map, "suggestedReply"),
                MessageIntent.fromValue(map.get("intent")), ModelValues.string(map, "reasonCode"),
                status, ModelValues.longValue(map, "createdAt", 0L),
                ModelValues.longValue(map, "updatedAt", 0L));
    }
}
