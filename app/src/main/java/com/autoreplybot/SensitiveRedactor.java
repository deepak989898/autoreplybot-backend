package com.autoreplybot;

import androidx.annotation.NonNull;

import java.util.regex.Pattern;

/** Produces short metadata previews; callers must never log the original sensitive text. */
public final class SensitiveRedactor {
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?\\d[\\d\\s().-]{7,}\\d)(?!\\d)");
    private static final Pattern OTP = Pattern.compile(
            "(?i)\\b(?:otp|one[ -]?time password|verification code|security code|pin|कोड|ओटीपी)\\D{0,20}\\d{4,8}\\b");
    private static final Pattern ACCOUNT = Pattern.compile(
            "(?i)\\b(?:a/?c|account|card|खाता|कार्ड)\\D{0,12}(?:x{2,}|\\*{2,})?\\d{3,16}\\b");
    private static final Pattern TRANSACTION_ID = Pattern.compile(
            "(?i)\\b(?:txn|transaction|utr|ref(?:erence)?|order)\\s*(?:id|no|#|number)?\\s*[:#-]?\\s*[A-Z0-9-]{5,}\\b");
    private static final Pattern AMOUNT = Pattern.compile(
            "(?i)(?:₹|rs\\.?|inr|usd|\\$)\\s*[,\\d]+(?:\\.\\d{1,2})?|\\b[,\\d]+(?:\\.\\d{1,2})?\\s*(?:रुपये|rupees|credited|debited|balance)\\b");
    private static final Pattern LONG_DIGITS = Pattern.compile("(?<!\\d)\\d{4,}(?!\\d)");

    private SensitiveRedactor() {}

    @NonNull
    public static String redact(@NonNull String input) {
        String out = input;
        out = URL.matcher(out).replaceAll("[URL]");
        out = PHONE.matcher(out).replaceAll("[PHONE]");
        out = OTP.matcher(out).replaceAll("[CODE]");
        out = ACCOUNT.matcher(out).replaceAll("[ACCOUNT]");
        out = TRANSACTION_ID.matcher(out).replaceAll("[REFERENCE]");
        out = AMOUNT.matcher(out).replaceAll("[AMOUNT]");
        out = LONG_DIGITS.matcher(out).replaceAll("[NUMBER]");
        out = out.replaceAll("\\s+", " ").trim();
        return out.length() <= 160 ? out : out.substring(0, 157) + "...";
    }
}
