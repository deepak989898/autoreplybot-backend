package com.autoreplybot.remote;

import androidx.annotation.NonNull;

/** Options chosen on the phone during trusted-browser pairing. */
public final class RemotePairTrustOptions {
    public final boolean trustBrowser;
    public final boolean persistentPairing;
    public final boolean autoApproveSessions;
    public final boolean requirePhoneUnlock;
    public final boolean allowCamera;
    public final boolean allowMicrophone;
    public final boolean allowPhotoCapture;
    public final boolean allowVideoRecording;
    public final boolean allowAudioRecording;
    public final boolean allowTorch;

    public RemotePairTrustOptions(boolean trustBrowser,
                                  boolean persistentPairing,
                                  boolean autoApproveSessions,
                                  boolean requirePhoneUnlock,
                                  boolean allowCamera,
                                  boolean allowMicrophone,
                                  boolean allowPhotoCapture,
                                  boolean allowVideoRecording,
                                  boolean allowAudioRecording,
                                  boolean allowTorch) {
        this.trustBrowser = trustBrowser;
        this.persistentPairing = persistentPairing;
        this.autoApproveSessions = autoApproveSessions && trustBrowser;
        this.requirePhoneUnlock = requirePhoneUnlock;
        this.allowCamera = allowCamera;
        this.allowMicrophone = allowMicrophone;
        this.allowPhotoCapture = allowPhotoCapture;
        this.allowVideoRecording = allowVideoRecording;
        this.allowAudioRecording = allowAudioRecording;
        this.allowTorch = allowTorch;
    }

    @NonNull
    public static RemotePairTrustOptions defaults() {
        return new RemotePairTrustOptions(
                true, true, true, false,
                true, true, true, false, false, true);
    }
}
