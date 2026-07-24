package com.autoreplybot;

import androidx.annotation.NonNull;

/** Final local authority for send/approval/no-reply decisions. */
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
        if (profile.replyMode == ReplyMode.MANUAL_ONLY
                || profile.relationshipType == RelationshipType.MANUAL_ONLY) {
            return ReplyAction.REQUIRE_APPROVAL;
        }
        if (safety.action == ReplyAction.NO_REPLY || generated.action == ReplyAction.NO_REPLY) {
            return ReplyAction.NO_REPLY;
        }
        if (safety.action == ReplyAction.REQUIRE_APPROVAL) {
            return ReplyAction.REQUIRE_APPROVAL;
        }
        if (!categoryAllowsAutoReply(classification.intent, profile.relationshipType, settings)) {
            return ReplyAction.REQUIRE_APPROVAL;
        }
        if ((classification.businessContextRequired && !profile.allowBusinessContext)
                || (classification.personalContextRequired && !profile.allowPersonalContext)) {
            return ReplyAction.REQUIRE_APPROVAL;
        }

        double confidence = Math.min(classification.confidence, generated.confidence);
        double normalThreshold = Math.max(0.80d, settings.getMinimumAutoReplyConfidence());
        double cautiousThreshold = Math.max(0.60d,
                Math.min(normalThreshold, settings.getMinimumClarificationConfidence()));
        if (confidence < cautiousThreshold) return ReplyAction.REQUIRE_APPROVAL;
        if (confidence < normalThreshold) return ReplyAction.REQUIRE_APPROVAL;
        return ReplyAction.SEND_REPLY;
    }

    private static boolean categoryAllowsAutoReply(MessageIntent intent,
                                                   RelationshipType relationship,
                                                   UserSettings settings) {
        if (intent.name().startsWith("BUSINESS_")) {
            return settings.isBusinessAutoReplyEnabled()
                    && (relationship == RelationshipType.BUSINESS_CUSTOMER
                    || relationship == RelationshipType.BUSINESS_LEAD
                    || relationship == RelationshipType.UNKNOWN);
        }
        if (intent == MessageIntent.FRIEND_CONVERSATION) {
            return settings.isFriendAutoReplyEnabled()
                    && (relationship == RelationshipType.FRIEND
                    || relationship == RelationshipType.UNKNOWN);
        }
        if (intent == MessageIntent.FAMILY_CONVERSATION) {
            return settings.isFamilyAutoReplyEnabled()
                    && (relationship == RelationshipType.FAMILY
                    || relationship == RelationshipType.UNKNOWN);
        }
        if (intent == MessageIntent.GENERAL_QUESTION) {
            return settings.isGeneralQuestionAutoReplyEnabled();
        }
        if (intent == MessageIntent.MEDICAL || intent == MessageIntent.LEGAL
                || intent == MessageIntent.FINANCIAL || intent == MessageIntent.EMERGENCY
                || intent == MessageIntent.SENSITIVE_PERSONAL
                || intent == MessageIntent.SPAM || intent == MessageIntent.ABUSIVE
                || intent == MessageIntent.UNKNOWN) {
            return false;
        }
        return true;
    }
}
