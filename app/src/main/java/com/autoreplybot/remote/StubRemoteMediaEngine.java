package com.autoreplybot.remote;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.view.PreviewView;

/**
 * No-op engine kept for tests / offline builds. Production uses {@link CameraXRemoteMediaEngine}.
 */
public final class StubRemoteMediaEngine implements RemoteMediaEngine {
    private static final String TAG = "StubRemoteMediaEngine";

    private boolean running;
    private boolean muted;
    @Nullable private SessionConfig config;
    @Nullable private Listener listener;

    @Override
    public void start(@NonNull SessionConfig config) {
        this.config = config;
        this.running = true;
        muted = false;
        Log.i(TAG, "start stub sessionId=" + config.sessionId
                + " camera=" + config.cameraEnabled
                + " mic=" + config.microphoneEnabled);
        if (listener != null) {
            listener.onCameraReady(false, false);
        }
    }

    @Override
    public void stop() {
        if (!running) return;
        running = false;
        Log.i(TAG, "stop stub sessionId="
                + (config != null ? config.sessionId : ""));
        config = null;
    }

    @Override
    public void switchCamera() {
        Log.i(TAG, "switchCamera no-op");
    }

    @Override
    public void setMicrophoneMuted(boolean muted) {
        this.muted = muted;
        Log.i(TAG, "setMicrophoneMuted muted=" + muted);
    }

    @Override
    public boolean isMicrophoneMuted() {
        return muted;
    }

    @Override
    public void setTorchEnabled(boolean enabled) {
        Log.i(TAG, "setTorchEnabled no-op enabled=" + enabled);
    }

    @Override
    public boolean isTorchSupported() {
        return false;
    }

    @Override
    public boolean isTorchEnabled() {
        return false;
    }

    @Override
    public void attachPreview(@NonNull PreviewView previewView) {
        Log.i(TAG, "attachPreview no-op");
    }

    @Override
    public void detachPreview() {
        Log.i(TAG, "detachPreview no-op");
    }

    @Override
    public void capturePhoto() {
        Log.i(TAG, "capturePhoto no-op");
    }

    @Override
    public void startVideoRecording() {
        Log.i(TAG, "startVideoRecording no-op");
    }

    @Override
    public void stopVideoRecording() {
        Log.i(TAG, "stopVideoRecording no-op");
    }

    @Override
    public void startAudioRecording() {
        Log.i(TAG, "startAudioRecording no-op");
    }

    @Override
    public void stopAudioRecording() {
        Log.i(TAG, "stopAudioRecording no-op");
    }

    @Override
    public void setPreferredVideoHeight(int height) {
        Log.i(TAG, "setPreferredVideoHeight " + height);
    }

    @Override
    public int getPreferredVideoHeight() {
        return RemoteVideoQualityHelper.HEIGHT_720;
    }

    @Override
    public boolean isVideoRecording() {
        return false;
    }

    @Override
    public boolean isAudioRecording() {
        return false;
    }

    @Override
    public long getRecordingElapsedMs() {
        return 0L;
    }

    @Override
    public boolean isUsingFrontCamera() {
        return false;
    }

    @Override
    public boolean hasFrontCamera() {
        return false;
    }

    @Override
    public boolean hasBackCamera() {
        return false;
    }

    @Override
    public boolean isCameraActive() {
        return false;
    }

    @Override
    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }
}
