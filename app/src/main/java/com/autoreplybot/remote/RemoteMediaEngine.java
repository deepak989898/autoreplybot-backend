package com.autoreplybot.remote;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.view.PreviewView;

/**
 * Media pipeline for an active remote session (CameraX preview / photo / record).
 * Live WebRTC publish is {@link RemoteWebRtcPublisher}, started from
 * {@link RemoteMediaForegroundService}.
 */
public interface RemoteMediaEngine {

    void start(@NonNull SessionConfig config);

    /** Releases camera, recorders, torch, and preview. Idempotent. */
    void stop();

    void switchCamera();

    /**
     * Mute local mic capture (CameraX / MediaRecorder path).
     * When muted, video records without an audio track; audio-only capture stops.
     */
    void setMicrophoneMuted(boolean muted);

    boolean isMicrophoneMuted();

    void setTorchEnabled(boolean enabled);

    boolean isTorchSupported();

    boolean isTorchEnabled();

    /** Attach live preview while the session activity is visible. */
    void attachPreview(@NonNull PreviewView previewView);

    void detachPreview();

    void capturePhoto();

    void startVideoRecording();

    void stopVideoRecording();

    void startAudioRecording();

    void stopAudioRecording();

    void setPreferredVideoHeight(int height);

    int getPreferredVideoHeight();

    boolean isVideoRecording();

    boolean isAudioRecording();

    /** Elapsed ms of the active video or audio recording, or 0. */
    long getRecordingElapsedMs();

    boolean isUsingFrontCamera();

    boolean hasFrontCamera();

    boolean hasBackCamera();

    boolean isCameraActive();

    void setListener(@Nullable Listener listener);

    final class SessionConfig {
        @NonNull public final String sessionId;
        public final boolean cameraEnabled;
        public final boolean microphoneEnabled;

        public SessionConfig(@NonNull String sessionId,
                             boolean cameraEnabled,
                             boolean microphoneEnabled) {
            this.sessionId = sessionId;
            this.cameraEnabled = cameraEnabled;
            this.microphoneEnabled = microphoneEnabled;
        }
    }

    interface Listener {
        void onCameraReady(boolean frontCamera, boolean torchSupported);

        void onError(@NonNull String code, @NonNull String message);

        void onPhotoSaved(@NonNull Uri uri, @NonNull String absolutePath);

        void onRecordingStateChanged(boolean recording, @NonNull String kind);

        void onStatusMessage(@NonNull String message);
    }
}
