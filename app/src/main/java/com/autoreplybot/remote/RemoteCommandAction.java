package com.autoreplybot.remote;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Strict remote command actions. Never execute arbitrary text or dynamic code from Firestore.
 */
public enum RemoteCommandAction {
    REQUEST_SESSION,
    END_SESSION,
    SWITCH_CAMERA,
    SET_CAMERA_FRONT,
    SET_CAMERA_BACK,
    TORCH_ON,
    TORCH_OFF,
    MIC_MUTE,
    MIC_UNMUTE,
    CAPTURE_PHOTO,
    START_VIDEO_RECORDING,
    STOP_VIDEO_RECORDING,
    START_AUDIO_RECORDING,
    STOP_AUDIO_RECORDING,
    SET_QUALITY,
    SET_ZOOM,
    PING_DEVICE;

    @NonNull
    public static RemoteCommandAction fromValue(@Nullable Object value) {
        RemoteCommandAction parsed = tryParse(value);
        return parsed != null ? parsed : PING_DEVICE;
    }

    /** Strict parse — returns null for unknown / empty actions (never invents an action). */
    @Nullable
    public static RemoteCommandAction tryParse(@Nullable Object value) {
        if (value == null) return null;
        String raw = String.valueOf(value).trim().toUpperCase();
        if (raw.isEmpty()) return null;
        try {
            return valueOf(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
