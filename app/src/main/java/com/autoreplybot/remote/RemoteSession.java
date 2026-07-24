package com.autoreplybot.remote;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Model for users/{uid}/sessions/{sessionId}. */
public final class RemoteSession {
    public enum Status {
        REQUESTING, CONNECTING, CONNECTED, RECONNECTING, ENDED, FAILED;

        @NonNull
        public String wireValue() {
            return name().toLowerCase(Locale.US);
        }

        @NonNull
        public static Status fromValue(@Nullable Object value) {
            if (value == null) return ENDED;
            String raw = String.valueOf(value).trim().toUpperCase(Locale.US);
            if ("ACTIVE".equals(raw)) return CONNECTED;
            try {
                return valueOf(raw);
            } catch (IllegalArgumentException ignored) {
                return ENDED;
            }
        }
    }

    @NonNull public final String sessionId;
    @NonNull public final String deviceId;
    @NonNull public final String clientId;
    @NonNull public final Status status;
    public final long startedAt;
    public final long endedAt;
    @NonNull public final String selectedCamera;
    public final boolean microphoneEnabled;
    public final boolean flashlightEnabled;
    @NonNull public final String quality;
    @NonNull public final String terminationReason;
    @NonNull public final String ownerUid;

    public RemoteSession(@NonNull String sessionId,
                         @NonNull String deviceId,
                         @NonNull String clientId,
                         @NonNull Status status,
                         long startedAt,
                         long endedAt,
                         @NonNull String selectedCamera,
                         boolean microphoneEnabled,
                         boolean flashlightEnabled,
                         @NonNull String quality,
                         @NonNull String terminationReason,
                         @NonNull String ownerUid) {
        this.sessionId = sessionId;
        this.deviceId = deviceId;
        this.clientId = clientId;
        this.status = status;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.selectedCamera = selectedCamera;
        this.microphoneEnabled = microphoneEnabled;
        this.flashlightEnabled = flashlightEnabled;
        this.quality = quality;
        this.terminationReason = terminationReason;
        this.ownerUid = ownerUid;
    }

    @NonNull
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("sessionId", sessionId);
        map.put("deviceId", deviceId);
        map.put("clientId", clientId);
        map.put("status", status.wireValue());
        map.put("startedAt", startedAt);
        map.put("endedAt", endedAt);
        map.put("selectedCamera", selectedCamera);
        map.put("microphoneEnabled", microphoneEnabled);
        map.put("flashlightEnabled", flashlightEnabled);
        map.put("quality", quality);
        map.put("terminationReason", terminationReason);
        map.put("ownerUid", ownerUid);
        return map;
    }

    @NonNull
    public static RemoteSession fromMap(@NonNull Map<String, Object> map) {
        return fromMap(RemoteMapValues.string(map, "sessionId"), map);
    }

    @NonNull
    public static RemoteSession fromMap(@NonNull String expectedSessionId,
                                        @NonNull Map<String, Object> map) {
        String id = RemoteMapValues.string(map, "sessionId");
        if (id.isEmpty()) id = expectedSessionId;
        return new RemoteSession(
                id,
                RemoteMapValues.string(map, "deviceId"),
                RemoteMapValues.string(map, "clientId"),
                Status.fromValue(map.get("status")),
                RemoteMapValues.longValue(map, "startedAt", 0L),
                RemoteMapValues.longValue(map, "endedAt", 0L),
                RemoteMapValues.string(map, "selectedCamera"),
                RemoteMapValues.bool(map, "microphoneEnabled", false),
                RemoteMapValues.bool(map, "flashlightEnabled", false),
                RemoteMapValues.string(map, "quality"),
                RemoteMapValues.string(map, "terminationReason"),
                RemoteMapValues.string(map, "ownerUid"));
    }
}
