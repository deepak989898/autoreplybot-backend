package com.autoreplybot.remote;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.Map;

/** Validates FCM data payloads for remote session requests (no media start). */
public final class RemoteFcmPayload {
    public static final String TYPE_SESSION_REQUEST = "session_request";

    public final boolean valid;
    @NonNull public final String type;
    @NonNull public final String requestId;
    @NonNull public final String deviceId;
    @NonNull public final String clientId;
    @NonNull public final String clientName;
    public final long expiresAt;

    private RemoteFcmPayload(boolean valid,
                             @NonNull String type,
                             @NonNull String requestId,
                             @NonNull String deviceId,
                             @NonNull String clientId,
                             @NonNull String clientName,
                             long expiresAt) {
        this.valid = valid;
        this.type = type;
        this.requestId = requestId;
        this.deviceId = deviceId;
        this.clientId = clientId;
        this.clientName = clientName;
        this.expiresAt = expiresAt;
    }

    @NonNull
    public static RemoteFcmPayload parse(@Nullable Map<String, String> data) {
        if (data == null || data.isEmpty()) {
            return invalid();
        }
        String type = safe(data.get("type")).toLowerCase(Locale.US);
        if (!TYPE_SESSION_REQUEST.equals(type)) {
            return invalid();
        }
        String requestId = safe(data.get("requestId"));
        String deviceId = safe(data.get("deviceId"));
        String clientId = safe(data.get("clientId"));
        if (!isRemoteId(requestId) || !isRemoteId(clientId)) {
            return invalid();
        }
        if (!deviceId.isEmpty() && !isRemoteId(deviceId)) {
            return invalid();
        }
        String clientName = safe(data.get("clientName"));
        long expiresAt = parseLong(data.get("expiresAt"), 0L);
        return new RemoteFcmPayload(true, type, requestId, deviceId, clientId, clientName, expiresAt);
    }

    @NonNull
    private static RemoteFcmPayload invalid() {
        return new RemoteFcmPayload(false, "", "", "", "", "", 0L);
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

    private static boolean isRemoteId(@NonNull String id) {
        return id.matches("[A-Za-z0-9_-]{1,128}");
    }
}
