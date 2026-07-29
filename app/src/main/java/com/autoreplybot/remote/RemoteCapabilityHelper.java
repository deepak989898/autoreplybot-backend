package com.autoreplybot.remote;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.Locale;

/** Pure helpers for session capability flags and request expiry (unit-testable). */
public final class RemoteCapabilityHelper {
    private RemoteCapabilityHelper() {}

    public static boolean wantsCamera(@Nullable List<String> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return true;
        }
        for (String raw : capabilities) {
            if (raw == null) continue;
            String c = raw.trim().toLowerCase(Locale.US);
            if ("camera".equals(c) || "video".equals(c) || "cam".equals(c)) {
                return true;
            }
        }
        return false;
    }

    public static boolean wantsMicrophone(@Nullable List<String> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return true;
        }
        for (String raw : capabilities) {
            if (raw == null) continue;
            String c = raw.trim().toLowerCase(Locale.US);
            if ("microphone".equals(c) || "mic".equals(c) || "audio".equals(c)
                    || "voice".equals(c)) {
                return true;
            }
        }
        return false;
    }

    public static boolean wantsScreenMirror(@Nullable List<String> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return false;
        }
        for (String raw : capabilities) {
            if (raw == null) continue;
            String c = raw.trim().toLowerCase(Locale.US);
            if ("screenmirror".equals(c) || "screen_mirror".equals(c) || "screen".equals(c)) {
                return true;
            }
        }
        return false;
    }

    /** Camera-only when camera requested and microphone not requested. */
    public static boolean isCameraOnly(@Nullable List<String> capabilities) {
        return wantsCamera(capabilities) && !wantsMicrophone(capabilities);
    }

    /** Mic-only when microphone requested and camera not requested. */
    public static boolean isMicrophoneOnly(@Nullable List<String> capabilities) {
        return wantsMicrophone(capabilities) && !wantsCamera(capabilities);
    }

    public static boolean isExpired(long expiresAtMillis, long nowMillis) {
        return expiresAtMillis > 0L && nowMillis >= expiresAtMillis;
    }

    @NonNull
    public static String describeCapabilities(@Nullable List<String> capabilities) {
        boolean camera = wantsCamera(capabilities);
        boolean mic = wantsMicrophone(capabilities);
        if (camera && mic) return "camera_and_microphone";
        if (camera) return "camera_only";
        if (mic) return "microphone_only";
        return "none";
    }
}
