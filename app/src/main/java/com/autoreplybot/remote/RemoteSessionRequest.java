package com.autoreplybot.remote;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Model for users/{uid}/sessionRequests/{requestId}. */
public final class RemoteSessionRequest {
    public enum Status {
        PENDING, APPROVED, REJECTED, EXPIRED, CANCELLED;

        @NonNull
        public String wireValue() {
            return name().toLowerCase(Locale.US);
        }

        @NonNull
        public static Status fromValue(@Nullable Object value) {
            if (value == null) return PENDING;
            try {
                return valueOf(String.valueOf(value).trim().toUpperCase(Locale.US));
            } catch (IllegalArgumentException ignored) {
                return PENDING;
            }
        }
    }

    @NonNull public final String requestId;
    @NonNull public final String deviceId;
    @NonNull public final String clientId;
    @NonNull public final List<String> requestedCapabilities;
    @NonNull public final Status status;
    public final long createdAt;
    public final long expiresAt;
    public final long approvedAt;
    public final long rejectedAt;
    @NonNull public final String ownerUid;

    public RemoteSessionRequest(@NonNull String requestId,
                                @NonNull String deviceId,
                                @NonNull String clientId,
                                @NonNull List<String> requestedCapabilities,
                                @NonNull Status status,
                                long createdAt,
                                long expiresAt,
                                long approvedAt,
                                long rejectedAt,
                                @NonNull String ownerUid) {
        this.requestId = requestId;
        this.deviceId = deviceId;
        this.clientId = clientId;
        this.requestedCapabilities = new ArrayList<>(requestedCapabilities);
        this.status = status;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.approvedAt = approvedAt;
        this.rejectedAt = rejectedAt;
        this.ownerUid = ownerUid;
    }

    @NonNull
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("requestId", requestId);
        map.put("deviceId", deviceId);
        map.put("clientId", clientId);
        map.put("requestedCapabilities", new ArrayList<>(requestedCapabilities));
        map.put("status", status.wireValue());
        map.put("createdAt", createdAt);
        map.put("expiresAt", expiresAt);
        map.put("approvedAt", approvedAt);
        map.put("rejectedAt", rejectedAt);
        map.put("ownerUid", ownerUid);
        return map;
    }

    @NonNull
    public static RemoteSessionRequest fromMap(@NonNull Map<String, Object> map) {
        return fromMap(RemoteMapValues.string(map, "requestId"), map);
    }

    @NonNull
    public static RemoteSessionRequest fromMap(@NonNull String expectedRequestId,
                                               @NonNull Map<String, Object> map) {
        String id = RemoteMapValues.string(map, "requestId");
        if (id.isEmpty()) id = expectedRequestId;
        return new RemoteSessionRequest(
                id,
                RemoteMapValues.string(map, "deviceId"),
                RemoteMapValues.string(map, "clientId"),
                RemoteMapValues.strings(map.get("requestedCapabilities")),
                Status.fromValue(map.get("status")),
                RemoteMapValues.longValue(map, "createdAt", 0L),
                RemoteMapValues.longValue(map, "expiresAt", 0L),
                RemoteMapValues.longValue(map, "approvedAt", 0L),
                RemoteMapValues.longValue(map, "rejectedAt", 0L),
                RemoteMapValues.string(map, "ownerUid"));
    }
}
