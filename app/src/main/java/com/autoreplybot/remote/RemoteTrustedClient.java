package com.autoreplybot.remote;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/** Model for users/{uid}/trustedClients/{clientId}. */
public final class RemoteTrustedClient {
    @NonNull public final String clientId;
    @NonNull public final String clientName;
    @NonNull public final String browser;
    @NonNull public final String browserName;
    @NonNull public final String platform;
    @NonNull public final String operatingSystem;
    @NonNull public final String browserFingerprintHash;
    public final long createdAt;
    public final long pairedAt;
    public final long lastUsedAt;
    public final long lastSeenAt;
    public final boolean revoked;
    public final boolean persistentPairing;
    public final boolean autoApproveSessions;
    public final boolean requirePhoneUnlock;
    public final boolean allowCamera;
    public final boolean allowMicrophone;
    public final boolean allowPhotoCapture;
    public final boolean allowVideoRecording;
    public final boolean allowAudioRecording;
    public final boolean allowTorch;
    /** Extended module caps from allowedCapabilities (location/gallery/files…). */
    @NonNull public final Map<String, Object> extraCapabilities;
    @Nullable public final Long expiresAt;
    @NonNull public final String pairingMetadata;
    @NonNull public final String ownerUid;

    public RemoteTrustedClient(@NonNull String clientId,
                               @NonNull String clientName,
                               @NonNull String browser,
                               @NonNull String browserName,
                               @NonNull String platform,
                               @NonNull String operatingSystem,
                               @NonNull String browserFingerprintHash,
                               long createdAt,
                               long pairedAt,
                               long lastUsedAt,
                               long lastSeenAt,
                               boolean revoked,
                               boolean persistentPairing,
                               boolean autoApproveSessions,
                               boolean requirePhoneUnlock,
                               boolean allowCamera,
                               boolean allowMicrophone,
                               boolean allowPhotoCapture,
                               boolean allowVideoRecording,
                               boolean allowAudioRecording,
                               boolean allowTorch,
                               @Nullable Map<String, Object> extraCapabilities,
                               @Nullable Long expiresAt,
                               @NonNull String pairingMetadata,
                               @NonNull String ownerUid) {
        this.clientId = clientId;
        this.clientName = clientName;
        this.browser = browser;
        this.browserName = browserName;
        this.platform = platform;
        this.operatingSystem = operatingSystem;
        this.browserFingerprintHash = browserFingerprintHash;
        this.createdAt = createdAt;
        this.pairedAt = pairedAt;
        this.lastUsedAt = lastUsedAt;
        this.lastSeenAt = lastSeenAt;
        this.revoked = revoked;
        this.persistentPairing = persistentPairing;
        this.autoApproveSessions = autoApproveSessions;
        this.requirePhoneUnlock = requirePhoneUnlock;
        this.allowCamera = allowCamera;
        this.allowMicrophone = allowMicrophone;
        this.allowPhotoCapture = allowPhotoCapture;
        this.allowVideoRecording = allowVideoRecording;
        this.allowAudioRecording = allowAudioRecording;
        this.allowTorch = allowTorch;
        this.extraCapabilities = extraCapabilities != null
                ? new HashMap<>(extraCapabilities)
                : new HashMap<>();
        this.expiresAt = expiresAt;
        this.pairingMetadata = pairingMetadata;
        this.ownerUid = ownerUid;
    }

    @NonNull
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("clientId", clientId);
        map.put("clientName", clientName);
        map.put("browser", browser);
        map.put("browserName", browserName);
        map.put("platform", platform);
        map.put("operatingSystem", operatingSystem);
        map.put("browserFingerprintHash", browserFingerprintHash);
        map.put("createdAt", createdAt);
        map.put("pairedAt", pairedAt);
        map.put("lastUsedAt", lastUsedAt);
        map.put("lastSeenAt", lastSeenAt);
        map.put("revoked", revoked);
        map.put("persistentPairing", persistentPairing);
        map.put("autoApproveSessions", autoApproveSessions);
        map.put("requirePhoneUnlock", requirePhoneUnlock);
        Map<String, Object> caps = new HashMap<>();
        caps.put("camera", allowCamera);
        caps.put("microphone", allowMicrophone);
        caps.put("photoCapture", allowPhotoCapture);
        caps.put("videoRecording", allowVideoRecording);
        caps.put("audioRecording", allowAudioRecording);
        caps.put("torch", allowTorch);
        for (Map.Entry<String, Object> e : extraCapabilities.entrySet()) {
            if (!caps.containsKey(e.getKey())) {
                caps.put(e.getKey(), e.getValue());
            }
        }
        map.put("allowedCapabilities", caps);
        if (expiresAt != null) map.put("expiresAt", expiresAt);
        map.put("pairingMetadata", pairingMetadata);
        map.put("ownerUid", ownerUid);
        return map;
    }

    @NonNull
    public static RemoteTrustedClient fromMap(@NonNull Map<String, Object> map) {
        return fromMap(RemoteMapValues.string(map, "clientId"), map);
    }

    @NonNull
    public static RemoteTrustedClient fromMap(@NonNull String expectedClientId,
                                              @NonNull Map<String, Object> map) {
        String id = RemoteMapValues.string(map, "clientId");
        if (id.isEmpty()) id = expectedClientId;

        Map<String, Object> caps = capsMap(map.get("allowedCapabilities"));
        String browserName = RemoteMapValues.string(map, "browserName");
        if (browserName.isEmpty()) browserName = RemoteMapValues.string(map, "browser");
        String os = RemoteMapValues.string(map, "operatingSystem");
        if (os.isEmpty()) os = RemoteMapValues.string(map, "platform");
        long created = RemoteMapValues.longValue(map, "createdAt", 0L);
        long paired = RemoteMapValues.longValue(map, "pairedAt", created);
        long lastUsed = RemoteMapValues.longValue(map, "lastUsedAt", 0L);
        long lastSeen = RemoteMapValues.longValue(map, "lastSeenAt", lastUsed);

        Long expires = null;
        if (map.containsKey("expiresAt") && map.get("expiresAt") instanceof Number) {
            expires = ((Number) map.get("expiresAt")).longValue();
        }

        return new RemoteTrustedClient(
                id,
                RemoteMapValues.string(map, "clientName"),
                RemoteMapValues.string(map, "browser").isEmpty()
                        ? browserName
                        : RemoteMapValues.string(map, "browser"),
                browserName,
                RemoteMapValues.string(map, "platform").isEmpty()
                        ? os
                        : RemoteMapValues.string(map, "platform"),
                os,
                RemoteMapValues.string(map, "browserFingerprintHash"),
                created,
                paired,
                lastUsed,
                lastSeen,
                RemoteMapValues.bool(map, "revoked", false),
                RemoteMapValues.bool(map, "persistentPairing", true),
                RemoteMapValues.bool(map, "autoApproveSessions", false),
                RemoteMapValues.bool(map, "requirePhoneUnlock", false),
                RemoteMapValues.bool(caps, "camera", true),
                RemoteMapValues.bool(caps, "microphone", true),
                RemoteMapValues.bool(caps, "photoCapture", true),
                RemoteMapValues.bool(caps, "videoRecording", false),
                RemoteMapValues.bool(caps, "audioRecording", false),
                RemoteMapValues.bool(caps, "torch", true),
                caps,
                expires,
                RemoteMapValues.string(map, "pairingMetadata"),
                RemoteMapValues.string(map, "ownerUid"));
    }

    @NonNull
    private static Map<String, Object> capsMap(@Nullable Object raw) {
        if (raw instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) raw;
            return map;
        }
        if (raw instanceof JSONObject) {
            JSONObject obj = (JSONObject) raw;
            Map<String, Object> map = new HashMap<>();
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                map.put(key, obj.opt(key));
            }
            return map;
        }
        return new HashMap<>();
    }
}
