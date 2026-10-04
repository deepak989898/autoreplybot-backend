package com.autoreplybot.remote;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Options chosen on the phone during trusted-browser pairing. */
public final class RemotePairTrustOptions {
    public final boolean trustBrowser;
    public final boolean persistentPairing;
    public final boolean autoApproveSessions;
    public final boolean requirePhoneUnlock;
    @NonNull
    private final Map<String, Boolean> allowedCapabilities;

    public RemotePairTrustOptions(boolean trustBrowser,
                                  boolean persistentPairing,
                                  boolean autoApproveSessions,
                                  boolean requirePhoneUnlock,
                                  @Nullable Map<String, Boolean> allowedCapabilities) {
        this.trustBrowser = trustBrowser;
        this.persistentPairing = persistentPairing;
        this.autoApproveSessions = autoApproveSessions && trustBrowser;
        this.requirePhoneUnlock = requirePhoneUnlock;
        Map<String, Boolean> caps = allCapabilities(false);
        if (allowedCapabilities != null) {
            for (Map.Entry<String, Boolean> e : allowedCapabilities.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    caps.put(e.getKey(), e.getValue());
                }
            }
        }
        this.allowedCapabilities = Collections.unmodifiableMap(caps);
    }

    @NonNull
    public Map<String, Boolean> getAllowedCapabilities() {
        return allowedCapabilities;
    }

    /** Legacy field accessors used by older call sites / tests. */
    public boolean isAllowCamera() {
        return Boolean.TRUE.equals(allowedCapabilities.get("camera"));
    }

    public boolean isAllowMicrophone() {
        return Boolean.TRUE.equals(allowedCapabilities.get("microphone"));
    }

    public boolean isAllowPhotoCapture() {
        return Boolean.TRUE.equals(allowedCapabilities.get("photoCapture"));
    }

    public boolean isAllowVideoRecording() {
        return Boolean.TRUE.equals(allowedCapabilities.get("videoRecording"));
    }

    public boolean isAllowAudioRecording() {
        return Boolean.TRUE.equals(allowedCapabilities.get("audioRecording"));
    }

    public boolean isAllowTorch() {
        return Boolean.TRUE.equals(allowedCapabilities.get("torch"));
    }

    @NonNull
    public static RemotePairTrustOptions defaults() {
        return new RemotePairTrustOptions(true, true, true, false, allCapabilities(true));
    }

    /** Full capability map; when {@code on} is true every key is granted. */
    @NonNull
    public static Map<String, Boolean> allCapabilities(boolean on) {
        Map<String, Boolean> map = new LinkedHashMap<>();
        for (String key : RemoteCapabilityKeys.KEYS) {
            map.put(key, on);
        }
        return map;
    }
}
