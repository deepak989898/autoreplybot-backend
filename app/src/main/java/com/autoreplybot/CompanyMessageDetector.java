package com.autoreplybot;

import androidx.annotation.NonNull;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Deterministic, local-only gate that runs before any classifier or generator request. */
public final class CompanyMessageDetector {
    private static final Pattern OTP = p("\\b(?:otp|one[ -]?time password|verification code|security code|ओटीपी|सत्यापन कोड)\\b|(?:do not share|किसी से साझा न करें).{0,30}\\b\\d{4,8}\\b");
    private static final Pattern BANK = p("\\b(?:bank|बैंक|a/?c|account|खाता|card ending|credit card|debit card|ifsc)\\b.{0,80}\\b(?:credited|debited|balance|transaction|लेनदेन|जमा|निकासी)\\b");
    private static final Pattern TRANSACTION = p("\\b(?:credited|debited|transaction|txn|utr|payment received|payment successful|paid|refund|लेनदेन|भुगतान|जमा|निकासी|रिफंड)\\b.{0,80}(?:₹|rs\\.?|inr|amount|राशि|a/?c|account|wallet|upi)");
    private static final Pattern PAYMENT = p("\\b(?:upi|wallet|paytm|phonepe|gpay|google pay|payment alert|payment request|autopay)\\b.{0,80}\\b(?:paid|received|successful|failed|due|भुगतान|प्राप्त)\\b");
    private static final Pattern DELIVERY = p("\\b(?:order|parcel|shipment|delivery|courier|package|ऑर्डर|पार्सल|डिलीवरी)\\b.{0,80}\\b(?:confirmed|shipped|dispatched|out for delivery|delivered|tracking|arriving|पहुंच|भेज|वितरण)\\b");
    private static final Pattern PROMOTION = p("\\b(?:sale|offer|discount|cashback|coupon|promo|limited time|buy now|shop now|deal|छूट|ऑफर|बिक्री|आज ही|free)\\b");
    private static final Pattern AUTOMATED = p("\\b(?:automated message|auto-generated|system generated|do not reply|don'?t reply|no[- ]?reply|यह एक स्वचालित संदेश|कृपया उत्तर न दें|ticket (?:id|number)|service notification)\\b");
    private static final Pattern BROADCAST = p("\\b(?:broadcast|channel|community|catalog|status update|newsletter|marketing|bulk message|announcement|प्रसारण|समुदाय|चैनल)\\b");
    private static final Pattern VERIFIED = p("\\b(?:verified business|official account|business account|व्यापार खाता|आधिकारिक खाता)\\b");
    private static final Pattern TELECOM = p("\\b(?:recharge|data pack|validity|prepaid|postpaid|telecom|sim|रिचार्ज|वैधता|डेटा पैक)\\b");
    private static final Pattern HUMAN_QUESTION = p("(?:\\?|\\b(?:can you|could you|please tell|help me|want to know|kya aap|bata sakte|mujhe .* chahiye|क्या आप|बताइए|चाहिए)\\b)");
    private static final Pattern HUMAN_GREETING = p("^\\s*(?:hi|hello|hey|namaste|नमस्ते|हाय)(?:\\s|[!,.])*");
    private static final Pattern ORG_SUFFIX = p("\\b(?:ltd|limited|pvt|inc|corp|bank|payments|support|official|services|store|telecom|care)\\b");

    public static final class Evidence {
        @NonNull public final String sender;
        @NonNull public final String message;
        @NonNull public final String conversationTitle;
        public final boolean savedContact;
        public final boolean group;
        public final boolean broadcastOrChannel;
        public final boolean verifiedBusiness;
        @NonNull public final ContactProfile profile;

        public Evidence(@NonNull String sender, @NonNull String message,
                        @NonNull String conversationTitle, boolean savedContact,
                        boolean group, boolean broadcastOrChannel, boolean verifiedBusiness,
                        @NonNull ContactProfile profile) {
            this.sender = sender;
            this.message = message;
            this.conversationTitle = conversationTitle;
            this.savedContact = savedContact;
            this.group = group;
            this.broadcastOrChannel = broadcastOrChannel;
            this.verifiedBusiness = verifiedBusiness;
            this.profile = profile;
        }
    }

    public static final class DetectionResult {
        public final boolean companyMessage;
        public final boolean automatedMessage;
        public final boolean promotional;
        public final boolean transactional;
        public final boolean shouldNeverReply;
        public final boolean sensitiveRedaction;
        public final boolean genuineDirectHumanSupport;
        @NonNull public final SenderCategory category;
        @NonNull public final MessageIntent inferredIntent;
        public final double confidence;
        @NonNull public final List<String> signals;

        DetectionResult(boolean companyMessage, boolean automatedMessage, boolean promotional,
                        boolean transactional, boolean shouldNeverReply,
                        boolean sensitiveRedaction, boolean genuineDirectHumanSupport,
                        @NonNull SenderCategory category, @NonNull MessageIntent inferredIntent,
                        double confidence, @NonNull List<String> signals) {
            this.companyMessage = companyMessage;
            this.automatedMessage = automatedMessage;
            this.promotional = promotional;
            this.transactional = transactional;
            this.shouldNeverReply = shouldNeverReply;
            this.sensitiveRedaction = sensitiveRedaction;
            this.genuineDirectHumanSupport = genuineDirectHumanSupport;
            this.category = category;
            this.inferredIntent = inferredIntent;
            this.confidence = Math.max(0d, Math.min(1d, confidence));
            this.signals = Collections.unmodifiableList(new ArrayList<>(signals));
        }
    }

    @NonNull
    public DetectionResult detect(@NonNull Evidence evidence) {
        String message = normalize(evidence.message);
        String sender = normalize(evidence.sender);
        String title = normalize(evidence.conversationTitle);
        List<String> signals = new ArrayList<>();

        boolean hindiOtp = containsAny(message, "ओटीपी", "सत्यापन कोड")
                && containsAny(message, "साझा", "कोड", "पासवर्ड");
        if (matches(OTP, message) || hindiOtp) return strong(SenderCategory.OTP_SENDER,
                MessageIntent.OTP_MESSAGE, true, false, true, "OTP_PATTERN");
        boolean hindiBankAlert = containsAny(message, "बैंक", "खाता")
                && containsAny(message, "जमा", "निकासी", "लेनदेन", "राशि", "बैलेंस");
        if (matches(BANK, message) || hindiBankAlert) return strong(SenderCategory.BANK,
                MessageIntent.BANK_ALERT, true, true, true, "BANK_ALERT_PATTERN");
        if (matches(PAYMENT, message)) return strong(SenderCategory.PAYMENT_SERVICE,
                MessageIntent.PAYMENT_ALERT, true, true, true, "PAYMENT_PATTERN");
        if (matches(TRANSACTION, message)) return strong(SenderCategory.TRANSACTION_ALERT,
                MessageIntent.TRANSACTION_NOTIFICATION, true, true, true, "TRANSACTION_PATTERN");
        if (matches(AUTOMATED, message)) return strong(SenderCategory.AUTOMATED_SYSTEM,
                MessageIntent.SYSTEM_GENERATED_MESSAGE, true, false, false, "SYSTEM_PATTERN");

        int score = 0;
        boolean delivery = matches(DELIVERY, message);
        boolean promotion = matches(PROMOTION, message);
        boolean broadcast = evidence.broadcastOrChannel || matches(BROADCAST, message + " " + title);
        boolean verified = evidence.verifiedBusiness || matches(VERIFIED, message + " " + title);
        boolean telecom = matches(TELECOM, message);
        boolean organizationSender = matches(ORG_SUFFIX, sender + " " + title);
        if (delivery) { score += 6; signals.add("DELIVERY_PATTERN"); }
        if (promotion) { score += 5; signals.add("PROMOTION_PATTERN"); }
        if (broadcast) { score += 7; signals.add("BROADCAST_CHANNEL_METADATA"); }
        if (verified) { score += 6; signals.add("VERIFIED_BUSINESS_METADATA"); }
        if (telecom) { score += 6; signals.add("TELECOM_PATTERN"); }
        if (organizationSender) { score += 3; signals.add("ORGANIZATION_SENDER_STRUCTURE"); }
        if (organizationSender && matches(HUMAN_QUESTION, message)) {
            score += 3; signals.add("DIRECT_SUPPORT_CONTEXT");
        }
        if (evidence.profile.detectedAsCompany) { score += 6; signals.add("PROFILE_COMPANY"); }
        if (evidence.savedContact) { score -= 4; signals.add("SAVED_CONTACT"); }
        if (evidence.profile.relationshipType == RelationshipType.FRIEND
                || evidence.profile.relationshipType == RelationshipType.FAMILY) {
            score -= 7; signals.add("HUMAN_RELATIONSHIP_PROFILE");
        }
        if (HUMAN_GREETING.matcher(message).find() && matches(HUMAN_QUESTION, message)) {
            score -= 2; signals.add("DIRECT_HUMAN_STYLE");
        }

        boolean directSupport = score >= 3 && !delivery && !promotion && !broadcast && !telecom
                && matches(HUMAN_QUESTION, message);
        boolean company = score >= 5;
        if (!company) {
            return new DetectionResult(false, false, false, false, false, false,
                    false, SenderCategory.PERSONAL_HUMAN, MessageIntent.UNKNOWN,
                    Math.max(0.05d, Math.min(0.49d, 0.25d + score * 0.04d)), signals);
        }

        SenderCategory category = delivery ? SenderCategory.DELIVERY_SERVICE
                : promotion ? SenderCategory.PROMOTIONAL_SENDER
                : telecom ? SenderCategory.TELECOM
                : verified ? SenderCategory.VERIFIED_BUSINESS
                : broadcast ? SenderCategory.AUTOMATED_SYSTEM
                : SenderCategory.UNKNOWN_BUSINESS;
        MessageIntent intent = delivery ? MessageIntent.DELIVERY_UPDATE
                : promotion ? MessageIntent.COMPANY_PROMOTION
                : telecom ? MessageIntent.SERVICE_NOTIFICATION
                : verified && broadcast ? MessageIntent.VERIFIED_BUSINESS_BROADCAST
                : broadcast ? MessageIntent.AUTOMATED_NOTIFICATION
                : MessageIntent.SERVICE_NOTIFICATION;
        return new DetectionResult(true, broadcast, promotion, delivery,
                broadcast, false, directSupport, category, intent,
                Math.min(0.98d, 0.55d + score * 0.045d), signals);
    }

    private static DetectionResult strong(SenderCategory category, MessageIntent intent,
                                          boolean automated, boolean transactional,
                                          boolean sensitive, String signal) {
        return new DetectionResult(true, automated, false, transactional, true, sensitive,
                false, category, intent, 0.99d, Collections.singletonList(signal));
    }

    private static boolean matches(Pattern pattern, String text) {
        return pattern.matcher(text).find();
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) return true;
        }
        return false;
    }

    private static Pattern p(String regex) {
        // Some Android regex runtimes expose the Java constant but reject it at runtime.
        // UNICODE_CASE keeps Hindi/Hinglish literal matching without crashing class init.
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
