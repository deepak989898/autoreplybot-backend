package com.autoreplybot.remote;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Append-only audit entry under users/{uid}/auditLogs/{logId}. */
public final class RemoteAuditLog {
    @NonNull public final String logId;
    @NonNull public final RemoteAuditAction action;
    @NonNull public final String deviceId;
    @NonNull public final String clientId;
    @NonNull public final String sessionId;
    public final long timestamp;
    @NonNull public final String result;
    @NonNull public final Map<String, Object> metadataWithoutSensitiveMedia;
    @NonNull public final String ownerUid;

    public RemoteAuditLog(@NonNull String logId,
                          @NonNull RemoteAuditAction action,
                          @NonNull String deviceId,
                          @NonNull String clientId,
                          @NonNull String sessionId,
                          long timestamp,
                          @NonNull String result,
                          @NonNull Map<String, Object> metadataWithoutSensitiveMedia,
                          @NonNull String ownerUid) {
        this.logId = logId;
        this.action = action;
        this.deviceId = deviceId;
        this.clientId = clientId;
        this.sessionId = sessionId;
        this.timestamp = timestamp;
        this.result = result;
        this.metadataWithoutSensitiveMedia =
                Collections.unmodifiableMap(new HashMap<>(metadataWithoutSensitiveMedia));
        this.ownerUid = ownerUid;
    }

    @NonNull
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("logId", logId);
        map.put("action", action.name());
        map.put("deviceId", deviceId);
        map.put("clientId", clientId);
        map.put("sessionId", sessionId);
        map.put("timestamp", timestamp);
        map.put("result", result);
        map.put("metadataWithoutSensitiveMedia", new HashMap<>(metadataWithoutSensitiveMedia));
        map.put("ownerUid", ownerUid);
        return map;
    }

    @NonNull
    public static RemoteAuditLog fromMap(@NonNull Map<String, Object> map) {
        return fromMap(RemoteMapValues.string(map, "logId"), map);
    }

    @NonNull
    public static RemoteAuditLog fromMap(@NonNull String expectedLogId,
                                         @NonNull Map<String, Object> map) {
        String id = RemoteMapValues.string(map, "logId");
        if (id.isEmpty()) id = expectedLogId;
        Map<String, Object> meta = new HashMap<>();
        Object rawMeta = map.get("metadataWithoutSensitiveMedia");
        if (rawMeta instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) rawMeta).entrySet()) {
                if (entry.getKey() != null) {
                    meta.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }
        return new RemoteAuditLog(
                id,
                RemoteAuditAction.fromValue(map.get("action")),
                RemoteMapValues.string(map, "deviceId"),
                RemoteMapValues.string(map, "clientId"),
                RemoteMapValues.string(map, "sessionId"),
                RemoteMapValues.longValue(map, "timestamp", 0L),
                RemoteMapValues.string(map, "result"),
                meta,
                RemoteMapValues.string(map, "ownerUid"));
    }
}
