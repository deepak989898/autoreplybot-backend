package com.autoreplybot.remote;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

/** Stub model for users/{uid}/trustedClients/{clientId}. */
public final class RemoteTrustedClient {
    @NonNull public final String clientId;
    @NonNull public final String clientName;
    @NonNull public final String browser;
    @NonNull public final String platform;
    public final long createdAt;
    public final long lastUsedAt;
    public final boolean revoked;
    @NonNull public final String pairingMetadata;
    @NonNull public final String ownerUid;

    public RemoteTrustedClient(@NonNull String clientId,
                               @NonNull String clientName,
                               @NonNull String browser,
                               @NonNull String platform,
                               long createdAt,
                               long lastUsedAt,
                               boolean revoked,
                               @NonNull String pairingMetadata,
                               @NonNull String ownerUid) {
        this.clientId = clientId;
        this.clientName = clientName;
        this.browser = browser;
        this.platform = platform;
        this.createdAt = createdAt;
        this.lastUsedAt = lastUsedAt;
        this.revoked = revoked;
        this.pairingMetadata = pairingMetadata;
        this.ownerUid = ownerUid;
    }

    @NonNull
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("clientId", clientId);
        map.put("clientName", clientName);
        map.put("browser", browser);
        map.put("platform", platform);
        map.put("createdAt", createdAt);
        map.put("lastUsedAt", lastUsedAt);
        map.put("revoked", revoked);
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
        return new RemoteTrustedClient(
                id,
                RemoteMapValues.string(map, "clientName"),
                RemoteMapValues.string(map, "browser"),
                RemoteMapValues.string(map, "platform"),
                RemoteMapValues.longValue(map, "createdAt", 0L),
                RemoteMapValues.longValue(map, "lastUsedAt", 0L),
                RemoteMapValues.bool(map, "revoked", false),
                RemoteMapValues.string(map, "pairingMetadata"),
                RemoteMapValues.string(map, "ownerUid"));
    }
}
