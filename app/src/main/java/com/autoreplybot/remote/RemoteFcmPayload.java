package com.autoreplybot.remote;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.Map;

/**
 * Validates FCM data payloads for remote session requests / auto-start.
 * Does not start camera or microphone by itself.
 */
public final class RemoteFcmPayload {
    public static final String TYPE_SESSION_REQUEST = "session_request";
    public static final String TYPE_SESSION_AUTO_START = "session_auto_start";

    public final boolean valid;
    @NonNull public final String type;
    @NonNull public final String requestId;
    @NonNull public final String sessionId;
    @NonNull public final String deviceId;
    @NonNull public final String clientId;
    @NonNull public final String clientName;
    public final long expiresAt;
    public final boolean cameraEnabled;
    public final boolean microphoneEnabled;
    @NonNull public final String sessionKind;
    public final boolean screenMirror;

    private RemoteFcmPayload(boolean valid,
                             @NonNull String type,
                             @NonNull String requestId,
                             @NonNull String sessionId,
                             @NonNull String deviceId,
                             @NonNull String clientId,
                             @NonNull String clientName,
                             long expiresAt,
                             boolean cameraEnabled,
                             boolean microphoneEnabled,
                             @NonNull String sessionKind,
                             boolean screenMirror) {
        this.valid = valid;
        this.type = type;
        this.requestId = requestId;
        this.sessionId = sessionId;
        this.deviceId = deviceId;
        this.clientId = clientId;
        this.clientName = clientName;
        this.expiresAt = expiresAt;
        this.cameraEnabled = cameraEnabled;
        this.microphoneEnabled = microphoneEnabled;
        this.sessionKind = sessionKind;
        this.screenMirror = screenMirror;
    }

    public boolean isAutoStart() {
        return TYPE_SESSION_AUTO_START.equals(type);
    }

    public boolean isScreenSession() {
        return screenMirror || "screen".equalsIgnoreCase(sessionKind);
    }

    @NonNull
    public static RemoteFcmPayload parse(@Nullable Map<String, String> data) {
        if (data == null || data.isEmpty()) {
            return invalid();
        }
        String type = safe(data.get("type")).toLowerCase(Locale.US);
        if (!TYPE_SESSION_REQUEST.equals(type) && !TYPE_SESSION_AUTO_START.equals(type)) {
            return invalid();
        }
        String requestId = safe(data.get("requestId"));
        String deviceId = safe(data.get("deviceId"));
        String clientId = safe(data.get("clientId"));
        String sessionId = safe(data.get("sessionId"));
        if (!isRemoteId(requestId) || !isRemoteId(clientId)) {
            return invalid();
        }
        if (!deviceId.isEmpty() && !isRemoteId(deviceId)) {
            return invalid();
        }
        if (TYPE_SESSION_AUTO_START.equals(type) && !isRemoteId(sessionId)) {
            return invalid();
        }
        String clientName = safe(data.get("clientName"));
        long expiresAt = parseLong(data.get("expiresAt"), 0L);
        boolean camera = parseFlag(data.get("cameraEnabled"), true);
        boolean mic = parseFlag(data.get("microphoneEnabled"), true);
        boolean screen = parseFlag(data.get("screenMirror"), false);
        String kind = safe(data.get("sessionKind")).toLowerCase(Locale.US);
        if (kind.isEmpty()) {
            kind = screen ? "screen" : "camera";
        }
        if ("screen".equals(kind)) screen = true;
        return new RemoteFcmPayload(
                true, type, requestId, sessionId, deviceId, clientId, clientName,
                expiresAt, camera, mic, kind, screen);
    }

    @NonNull
    private static RemoteFcmPayload invalid() {
        return new RemoteFcmPayload(false, "", "", "", "", "", "", 0L, false, false, "camera", false);
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private static long parseLong(@Nullable String value, long fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean parseFlag(@Nullable String value, boolean fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        String v = value.trim().toLowerCase(Locale.US);
        if ("1".equals(v) || "true".equals(v) || "yes".equals(v)) return true;
        if ("0".equals(v) || "false".equals(v) || "no".equals(v)) return false;
        return fallback;
    }

    private static boolean isRemoteId(@NonNull String id) {
        return id.matches("[A-Za-z0-9_-]{1,128}");
    }
}
