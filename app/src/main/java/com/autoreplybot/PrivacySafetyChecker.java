package com.autoreplybot;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Deterministic local safety checks. Model-provided flags can only make this stricter. */
public final class PrivacySafetyChecker {
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(password|passwd|api[ _-]?key|secret|bearer\\s+[a-z0-9._-]+|otp|pin\\s*(is|:)|"
                    + "\\b\\d{4,8}\\b\\s*(otp|code))");
    private static final Pattern HOME_OR_LIVE_LOCATION = Pattern.compile(
            "(?i)(home address|live location|my address|i am at|i'm at|currently at|"
                    + "मैं अभी .{0,25}(पर|में) हूँ|मेरा घर)");
    private static final Pattern PERSONAL_ACTIVITY = Pattern.compile(
            "(?i)(i am (currently )?(driving|travelling|traveling|sleeping|working|eating|outside|home)|"
                    + "i'm (driving|travelling|traveling|sleeping|working|outside|home)|"
                    + "मैं अभी (घर|बाहर|काम|गाड़ी|गाड़ी))");
    private static final Pattern COMMITMENT = Pattern.compile(
            "(?i)(i (will|can) (pay|send|book|reserve|meet|come|travel)|"
                    + "(booking|meeting|reservation|payment) (is )?(confirmed|done)|"
                    + "consider it (booked|confirmed)|मैं (पैसे|पेमेंट).{0,20}(कर दूँगा|भेज दूँगा))");
    private static final Pattern UNVERIFIED = Pattern.compile(
            "(?i)(definitely available|guaranteed|confirmed|for sure|always open|"
                    + "your payment (was|is) received|booked successfully)");
    private static final Pattern CROSS_CONTACT = Pattern.compile(
            "(?i)(another contact|other contact|another chat|other conversation|"
                    + "someone else told me|मेरी दूसरी चैट|किसी और ने बताया)");
    private static final Pattern RISK_TOPIC = Pattern.compile(
            "(?i)(money|payment|\\bpay\\b|booking|\\bbook\\b|reservation|meeting|travel|trip|flight|"
                    + "medical|medicine|doctor|legal|lawyer|court|emergency|hospital|ambulance|"
                    + "पैसे|पेमेंट|बुकिंग|मीटिंग|यात्रा|डॉक्टर|अस्पताल|आपात)");

    @NonNull
    public ReplyAction preGenerationAction(@NonNull String incoming,
                                           @NonNull MessageClassificationResult classification,
                                           @NonNull ContactProfile profile) {
        if (classification.shouldNeverReply || classification.isAutomatedMessage
                || classification.isPromotional || classification.isTransactional) {
            return ReplyAction.NO_REPLY;
        }
        if (classification.isCompanyMessage) {
            return classification.modelAction == ReplyAction.REQUIRE_APPROVAL
                    ? ReplyAction.REQUIRE_APPROVAL : ReplyAction.NO_REPLY;
        }
        if (profile.relationshipType == RelationshipType.BLOCKED
                || classification.intent == MessageIntent.SPAM
                || classification.intent == MessageIntent.ABUSIVE) {
            return ReplyAction.NO_REPLY;
        }
        if (!classification.canAutoReply && classification.confidence < 0.60d) {
            return ReplyAction.REQUIRE_APPROVAL;
        }
        if (SECRET.matcher(incoming).find() || RISK_TOPIC.matcher(incoming).find()) {
            return ReplyAction.REQUIRE_APPROVAL;
        }
        // Model review flags are advisory for ordinary messages. Deterministic risk checks and
        // high-risk intents remain authoritative so safe greetings/casual chat can auto-send.
        if (classification.sensitive || isHighRiskIntent(classification.intent)) {
            return ReplyAction.REQUIRE_APPROVAL;
        }
        return ReplyAction.SEND_REPLY;
    }

    @NonNull
    public SafetyClassificationResult validate(@NonNull String incoming,
                                               @NonNull GeneratedReply generated,
                                               @NonNull MessageClassificationResult classification,
                                               @NonNull ContactProfile profile,
                                               @NonNull UserSettings settings,
                                               boolean locallyRepeated) {
        List<String> flags = new ArrayList<>(generated.safetyFlags);
        String reply = generated.reply;
        boolean privateInfo = generated.containsPrivateInformation;
        boolean unverified = generated.containsUnverifiedClaim;

        if (SECRET.matcher(reply).find()) {
            privateInfo = true;
            add(flags, "SECRET_OR_CREDENTIAL");
        }
        if (CROSS_CONTACT.matcher(reply).find()) {
            privateInfo = true;
            add(flags, "CROSS_CONTACT_LEAKAGE");
        }
        if ((settings.isNeverShareHomeAddress() || settings.isNeverShareLiveLocation())
                && HOME_OR_LIVE_LOCATION.matcher(reply).find()) {
            privateInfo = true;
            add(flags, "LOCATION_OR_HOME_ADDRESS");
        }
        if (PERSONAL_ACTIVITY.matcher(reply).find()) {
            privateInfo = true;
            add(flags, "PERSONAL_CURRENT_ACTIVITY");
        }
        if (COMMITMENT.matcher(reply).find()) {
            add(flags, "UNSAFE_COMMITMENT");
        }
        if (RISK_TOPIC.matcher(reply).find()) {
            add(flags, "CONTROLLED_RISK_TOPIC");
        }
        if (UNVERIFIED.matcher(reply).find()) {
            unverified = true;
            add(flags, "UNVERIFIED_CLAIM");
        }
        if (isHighRiskIntent(classification.intent)) add(flags, "HIGH_RISK_CATEGORY");
        if (generated.intent != classification.intent
                && generated.intent != MessageIntent.UNKNOWN) {
            add(flags, "INTENT_MISMATCH");
        }
        if (locallyRepeated || generated.repeatedReply) add(flags, "REPEATED_REPLY");
        if (reply.length() > 1500) add(flags, "REPLY_TOO_LONG");
        if (looksLikePromptLeak(reply)) {
            privateInfo = true;
            add(flags, "PROMPT_OR_CONTEXT_LEAK");
        }

        // A non-empty, locally safe draft should auto-send even if the model was overly cautious.
        // NO_REPLY remains authoritative; approval is re-applied below for every real risk.
        ReplyAction action = generated.action == ReplyAction.NO_REPLY
                ? ReplyAction.NO_REPLY : ReplyAction.SEND_REPLY;
        if (!generated.valid || reply.isEmpty()) action = ReplyAction.REQUIRE_APPROVAL;
        if (privateInfo || unverified || locallyRepeated || generated.repeatedReply
                || hasBlockingFlag(flags) || classification.sensitive
                || isHighRiskIntent(classification.intent)) {
            action = ReplyAction.REQUIRE_APPROVAL;
        }
        if (profile.relationshipType == RelationshipType.BLOCKED) action = ReplyAction.NO_REPLY;

        InformationClassification info = informationClassification(
                classification.intent, privateInfo, flags);
        String reason = action == generated.action ? generated.reasonCode : firstReason(flags);
        return new SafetyClassificationResult(action, info, flags, privateInfo,
                unverified, locallyRepeated || generated.repeatedReply, reason);
    }

    private static boolean isHighRiskIntent(MessageIntent intent) {
        return intent == MessageIntent.MEDICAL || intent == MessageIntent.LEGAL
                || intent == MessageIntent.FINANCIAL || intent == MessageIntent.EMERGENCY
                || intent == MessageIntent.SENSITIVE_PERSONAL
                || intent == MessageIntent.BUSINESS_PAYMENT
                || intent == MessageIntent.BUSINESS_BOOKING
                || intent == MessageIntent.BUSINESS_CANCELLATION;
    }

    private static boolean looksLikePromptLeak(String reply) {
        String lower = reply.toLowerCase(Locale.ROOT);
        return lower.contains("trusted_policy") || lower.contains("untrusted_message")
                || lower.contains("system prompt") || lower.contains("allowedcontextclasses");
    }

    private static boolean hasBlockingFlag(List<String> flags) {
        for (String flag : flags) {
            String upper = flag.toUpperCase(Locale.ROOT);
            if (upper.contains("SECRET") || upper.contains("PRIVATE")
                    || upper.contains("LOCATION") || upper.contains("COMMITMENT")
                    || upper.contains("PAYMENT") || upper.contains("BOOKING")
                    || upper.contains("MEETING") || upper.contains("TRAVEL")
                    || upper.contains("MEDICAL") || upper.contains("LEGAL")
                    || upper.contains("EMERGENCY") || upper.contains("LEAK")
                    || upper.contains("RISK_TOPIC") || upper.contains("UNVERIFIED")
                    || upper.contains("REPEATED")) return true;
        }
        return false;
    }

    private static InformationClassification informationClassification(
            MessageIntent intent, boolean privateInfo, List<String> flags) {
        if (privateInfo || hasBlockingFlag(flags)) return InformationClassification.HIGHLY_SENSITIVE;
        if (intent.name().startsWith("BUSINESS_")) return InformationClassification.PUBLIC_BUSINESS;
        if (intent == MessageIntent.PERSONAL_CASUAL
                || intent == MessageIntent.FRIEND_CONVERSATION
                || intent == MessageIntent.FAMILY_CONVERSATION) {
            return InformationClassification.CONTACT_SPECIFIC;
        }
        return InformationClassification.PRIVATE_PERSONAL;
    }

    private static void add(List<String> flags, String flag) {
        if (!flags.contains(flag)) flags.add(flag);
    }

    @NonNull
    private static String firstReason(List<String> flags) {
        return flags.isEmpty() ? "LOCAL_SAFETY_REVIEW" : flags.get(0);
    }
}
