package com.autoreplybot;

import androidx.annotation.NonNull;

/** Final local authority for send/no-reply (approval queue disabled). */
public final class AutoReplyDecisionEngine {
    @NonNull
    public ReplyAction decide(@NonNull MessageClassificationResult classification,
                              @NonNull GeneratedReply generated,
                              @NonNull SafetyClassificationResult safety,
                              @NonNull ContactProfile profile,
                              @NonNull UserSettings settings) {
        if (!profile.autoReplyEnabled || profile.relationshipType == RelationshipType.BLOCKED) {
            return ReplyAction.NO_REPLY;
        }
        if (safety.action == ReplyAction.NO_REPLY
                || generated.action == ReplyAction.NO_REPLY
                || safety.containsPrivateInformation) {
            return ReplyAction.NO_REPLY;
        }
        if (generated.valid && generated.reply != null && !generated.reply.trim().isEmpty()) {
            return ReplyAction.SEND_REPLY;
        }
        return ReplyAction.NO_REPLY;
    }
}
