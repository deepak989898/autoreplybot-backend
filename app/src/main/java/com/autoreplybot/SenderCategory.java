package com.autoreplybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public enum SenderCategory {
    PERSONAL_HUMAN,
    COMPANY,
    BANK,
    ECOMMERCE,
    DELIVERY_SERVICE,
    PAYMENT_SERVICE,
    TELECOM,
    PROMOTIONAL_SENDER,
    AUTOMATED_SYSTEM,
    VERIFIED_BUSINESS,
    OTP_SENDER,
    TRANSACTION_ALERT,
    UNKNOWN_BUSINESS;

    @NonNull
    public static SenderCategory fromValue(@Nullable Object value) {
        if (value == null) return PERSONAL_HUMAN;
        try {
            return valueOf(String.valueOf(value).trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return PERSONAL_HUMAN;
        }
    }
}
