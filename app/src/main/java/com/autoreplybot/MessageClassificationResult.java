package com.autoreplybot;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

/** Safe, persisted classifier metadata. Never contains chain-of-thought or prompt text. */
public final class MessageClassificationResult {
    @NonNull public final MessageIntent intent;
    public final double confidence;
    @NonNull public final String language;
    @NonNull public final String tone;
    public final boolean sensitive;
    public final boolean needsHumanReview;
    public final boolean businessContextRequired;
    public final boolean personalContextRequired;
    public final boolean canAutoReply;
    @NonNull public final String reasonCode;
    public final boolean isCompanyMessage;
    public final boolean isAutomatedMessage;
    public final boolean isPromotional;
    public final boolean isTransactional;
    public final boolean shouldNeverReply;
    @NonNull public final SenderCategory senderCategory;
    @NonNull public final ReplyAction modelAction;

    public MessageClassificationResult(@NonNull MessageIntent intent, double confidence,
                                       @NonNull String language, @NonNull String tone,
                                       boolean sensitive, boolean needsHumanReview,
                                       boolean businessContextRequired, boolean personalContextRequired,
                                       boolean canAutoReply, @NonNull String reasonCode) {
        this(intent, confidence, language, tone, sensitive, needsHumanReview,
                businessContextRequired, personalContextRequired, canAutoReply, reasonCode,
                false, false, false, false, false, SenderCategory.PERSONAL_HUMAN,
                canAutoReply ? ReplyAction.SEND_REPLY : ReplyAction.NO_REPLY);
    }

    public MessageClassificationResult(@NonNull MessageIntent intent, double confidence,
                                       @NonNull String language, @NonNull String tone,
                                       boolean sensitive, boolean needsHumanReview,
                                       boolean businessContextRequired, boolean personalContextRequired,
                                       boolean canAutoReply, @NonNull String reasonCode,
                                       boolean isCompanyMessage, boolean isAutomatedMessage,
                                       boolean isPromotional, boolean isTransactional,
                                       boolean shouldNeverReply, @NonNull SenderCategory senderCategory,
                                       @NonNull ReplyAction modelAction) {
        this.intent = intent;
        this.confidence = Math.max(0d, Math.min(1d, confidence));
        this.language = language;
        this.tone = tone;
        this.sensitive = sensitive;
        this.needsHumanReview = needsHumanReview;
        this.businessContextRequired = businessContextRequired;
        this.personalContextRequired = personalContextRequired;
        this.canAutoReply = canAutoReply;
        this.reasonCode = reasonCode;
        this.isCompanyMessage = isCompanyMessage;
        this.isAutomatedMessage = isAutomatedMessage;
        this.isPromotional = isPromotional;
        this.isTransactional = isTransactional;
        this.shouldNeverReply = shouldNeverReply;
        this.senderCategory = senderCategory;
        this.modelAction = modelAction;
    }

    @NonNull public static MessageClassificationResult safeFallback(@NonNull String reasonCode) {
        return new MessageClassificationResult(MessageIntent.UNKNOWN, 0d, "unknown", "neutral",
                false, true, false, false, false, reasonCode);
    }

    @NonNull public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("intent", intent.name());
        map.put("confidence", confidence);
        map.put("language", language);
        map.put("tone", tone);
        map.put("isSensitive", sensitive);
        map.put("needsHumanReview", needsHumanReview);
        map.put("businessContextRequired", businessContextRequired);
        map.put("personalContextRequired", personalContextRequired);
        map.put("canAutoReply", canAutoReply);
        map.put("reasonCode", reasonCode);
        map.put("isCompanyMessage", isCompanyMessage);
        map.put("isAutomatedMessage", isAutomatedMessage);
        map.put("isPromotional", isPromotional);
        map.put("isTransactional", isTransactional);
        map.put("shouldNeverReply", shouldNeverReply);
        map.put("senderCategory", senderCategory.name());
        map.put("modelAction", modelAction.name());
        return map;
    }

    @NonNull public static MessageClassificationResult fromMap(@NonNull Map<String, Object> map) {
        return new MessageClassificationResult(
                MessageIntent.fromValue(map.get("intent")),
                ModelValues.doubleValue(map, "confidence", 0d),
                ModelValues.string(map, "language"), ModelValues.string(map, "tone"),
                ModelValues.bool(map, "isSensitive", false),
                ModelValues.bool(map, "needsHumanReview", true),
                ModelValues.bool(map, "businessContextRequired", false),
                ModelValues.bool(map, "personalContextRequired", false),
                ModelValues.bool(map, "canAutoReply", false),
                ModelValues.string(map, "reasonCode"),
                ModelValues.bool(map, "isCompanyMessage", false),
                ModelValues.bool(map, "isAutomatedMessage", false),
                ModelValues.bool(map, "isPromotional", false),
                ModelValues.bool(map, "isTransactional", false),
                ModelValues.bool(map, "shouldNeverReply", false),
                SenderCategory.fromValue(map.get("senderCategory")),
                actionFromValue(map.get("modelAction")));
    }

    @NonNull private static ReplyAction actionFromValue(Object value) {
        try {
            return ReplyAction.valueOf(String.valueOf(value).trim().toUpperCase());
        } catch (RuntimeException ignored) {
            return ReplyAction.NO_REPLY;
        }
    }
}
