package com.autoreplybot.remote;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

/** Firestore document under users/{uid}/devices/{deviceId}. */
public final class RemoteDeviceProfile {
    @NonNull public final String deviceId;
    @NonNull public final String deviceName;
    @NonNull public final String deviceModel;
    @NonNull public final String manufacturer;
    @NonNull public final String androidVersion;
    @NonNull public final String appVersion;
    public final long createdAt;
    public final long lastSeenAt;
    public final boolean online;
    public final int batteryLevel;
    public final boolean isCharging;
    @NonNull public final String networkType;
    @NonNull public final String fcmToken;
    public final boolean cameraAvailable;
    public final boolean microphoneAvailable;
    public final boolean flashlightAvailable;
    public final boolean revoked;
    @NonNull public final String ownerUid;
    public final boolean remoteControlEnabled;

    public RemoteDeviceProfile(@NonNull String deviceId,
                               @NonNull String deviceName,
                               @NonNull String deviceModel,
                               @NonNull String manufacturer,
                               @NonNull String androidVersion,
                               @NonNull String appVersion,
                               long createdAt,
                               long lastSeenAt,
                               boolean online,
                               int batteryLevel,
                               boolean isCharging,
                               @NonNull String networkType,
                               @NonNull String fcmToken,
                               boolean cameraAvailable,
                               boolean microphoneAvailable,
                               boolean flashlightAvailable,
                               boolean revoked,
                               @NonNull String ownerUid,
                               boolean remoteControlEnabled) {
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.deviceModel = deviceModel;
        this.manufacturer = manufacturer;
        this.androidVersion = androidVersion;
        this.appVersion = appVersion;
        this.createdAt = createdAt;
        this.lastSeenAt = lastSeenAt;
        this.online = online;
        this.batteryLevel = Math.max(0, Math.min(100, batteryLevel));
        this.isCharging = isCharging;
        this.networkType = networkType;
        this.fcmToken = fcmToken;
        this.cameraAvailable = cameraAvailable;
        this.microphoneAvailable = microphoneAvailable;
        this.flashlightAvailable = flashlightAvailable;
        this.revoked = revoked;
        this.ownerUid = ownerUid;
        this.remoteControlEnabled = remoteControlEnabled;
    }

    @NonNull
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("deviceId", deviceId);
        map.put("deviceName", deviceName);
        map.put("deviceModel", deviceModel);
        map.put("manufacturer", manufacturer);
        map.put("androidVersion", androidVersion);
        map.put("appVersion", appVersion);
        map.put("createdAt", createdAt);
        map.put("lastSeenAt", lastSeenAt);
        map.put("online", online);
        map.put("batteryLevel", batteryLevel);
        map.put("isCharging", isCharging);
        map.put("networkType", networkType);
        map.put("fcmToken", fcmToken);
        map.put("cameraAvailable", cameraAvailable);
        map.put("microphoneAvailable", microphoneAvailable);
        map.put("flashlightAvailable", flashlightAvailable);
        map.put("revoked", revoked);
        map.put("ownerUid", ownerUid);
        map.put("remoteControlEnabled", remoteControlEnabled);
        return map;
    }

    @NonNull
    public static RemoteDeviceProfile fromMap(@NonNull Map<String, Object> map) {
        return fromMap(RemoteMapValues.string(map, "deviceId"), map);
    }

    @NonNull
    public static RemoteDeviceProfile fromMap(@NonNull String expectedDeviceId,
                                              @NonNull Map<String, Object> map) {
        String id = RemoteMapValues.string(map, "deviceId");
        if (id.isEmpty()) id = expectedDeviceId;
        return new RemoteDeviceProfile(
                id,
                RemoteMapValues.string(map, "deviceName"),
                RemoteMapValues.string(map, "deviceModel"),
                RemoteMapValues.string(map, "manufacturer"),
                RemoteMapValues.string(map, "androidVersion"),
                RemoteMapValues.string(map, "appVersion"),
                RemoteMapValues.longValue(map, "createdAt", 0L),
                RemoteMapValues.longValue(map, "lastSeenAt", 0L),
                RemoteMapValues.bool(map, "online", false),
                RemoteMapValues.intValue(map, "batteryLevel", 0),
                RemoteMapValues.bool(map, "isCharging", false),
                RemoteMapValues.string(map, "networkType"),
                RemoteMapValues.string(map, "fcmToken"),
                RemoteMapValues.bool(map, "cameraAvailable", false),
                RemoteMapValues.bool(map, "microphoneAvailable", false),
                RemoteMapValues.bool(map, "flashlightAvailable", false),
                RemoteMapValues.bool(map, "revoked", false),
                RemoteMapValues.string(map, "ownerUid"),
                RemoteMapValues.bool(map, "remoteControlEnabled", false));
    }
}
