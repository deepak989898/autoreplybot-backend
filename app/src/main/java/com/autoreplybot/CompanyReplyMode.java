package com.autoreplybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Company contacts can never be configured for unattended full-auto replies. */
public enum CompanyReplyMode {
    NO_REPLY,
    MANUAL_ONLY,
    SMART_APPROVAL;

    @NonNull
    public static CompanyReplyMode fromValue(@Nullable Object value) {
        if (value == null) return NO_REPLY;
        try {
            return valueOf(String.valueOf(value).trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return NO_REPLY;
        }
    }
}
