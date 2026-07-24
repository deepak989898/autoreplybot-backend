package com.autoreplybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public enum RelationshipType {
    BUSINESS_CUSTOMER, BUSINESS_LEAD, FRIEND, FAMILY, PROFESSIONAL, UNKNOWN, BLOCKED, MANUAL_ONLY;

    @NonNull
    public static RelationshipType fromValue(@Nullable Object value) {
        if (value == null) return UNKNOWN;
        try {
            return valueOf(String.valueOf(value).trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }
}
