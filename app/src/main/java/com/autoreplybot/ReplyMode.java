package com.autoreplybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public enum ReplyMode {
    FULL_AUTO, SMART, MANUAL_ONLY;

    @NonNull
    public static ReplyMode fromValue(@Nullable Object value) {
        if (value == null) return SMART;
        try {
            return valueOf(String.valueOf(value).trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return SMART;
        }
    }
}
