package com.autoreplybot.remote;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Append-only audit event types for remote camera & voice. */
public enum RemoteAuditAction {
    DEVICE_REGISTERED,
    DEVICE_UPDATED,
    REMOTE_CONTROL_ENABLED,
    REMOTE_CONTROL_DISABLED,
    PAIRING_CREATED,
    PAIRING_REJECTED,
    BROWSER_TRUSTED,
    BROWSER_REVOKED,
    SESSION_REQUESTED,
    SESSION_APPROVED,
    SESSION_REJECTED,
    SESSION_STARTED,
    SESSION_ENDED,
    CAMERA_SWITCHED,
    PHOTO_CAPTURED,
    RECORDING_STARTED,
    RECORDING_STOPPED,
    UNAUTHORIZED_ATTEMPT,
    PERMISSION_DENIED;

    @NonNull
    public static RemoteAuditAction fromValue(@Nullable Object value) {
        if (value == null) return DEVICE_UPDATED;
        try {
            return valueOf(String.valueOf(value).trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return DEVICE_UPDATED;
        }
    }
}
