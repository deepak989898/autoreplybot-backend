package com.autoreplybot.remote;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CameraX-backed remote media engine: preview, photo, video (+ optional audio),
 * torch, facing switch, and mic-only MediaRecorder fallback when camera is denied.
 */
public final class CameraXRemoteMediaEngine implements RemoteMediaEngine, LifecycleOwner {
    private static final String TAG = "CameraXRemoteMedia";
    /** Max video / audio recording length. */
    public static final long MAX_RECORDING_MS = 10L * 60L * 1000L;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Executor mainExecutor;
    private final LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Nullable private SessionConfig config;
    @Nullable private Listener listener;

    @Nullable private ProcessCameraProvider cameraProvider;
    @Nullable private Preview preview;
    @Nullable private ImageCapture imageCapture;
    @Nullable private VideoCapture<Recorder> videoCapture;
    @Nullable private Camera camera;
    @Nullable private PreviewView previewView;
    @Nullable private Recording activeVideoRecording;
    @Nullable private MediaRecorder audioRecorder;
    @Nullable private File audioOutputFile;

    private boolean preferFront;
    private boolean torchEnabled;
    private boolean microphoneMuted;
    private boolean cameraBound;
    private boolean cameraDeniedFallback;
    private int preferredHeight = RemoteVideoQualityHelper.HEIGHT_720;

    private long recordingStartedElapsed;
    private boolean videoRecording;
    private boolean audioRecording;

    private final Runnable maxDurationRunnable = () -> {
        if (videoRecording) {
            stopVideoRecording();
            notifyStatus("Max recording length reached (10 min)");
        } else if (audioRecording) {
            stopAudioRecording();
            notifyStatus("Max recording length reached (10 min)");
        }
    };

    public CameraXRemoteMediaEngine(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.mainExecutor = ContextCompat.getMainExecutor(appContext);
        lifecycleRegistry.setCurrentState(Lifecycle.State.INITIALIZED);
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Override
    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    @Override
    public synchronized void start(@NonNull SessionConfig config) {
        if (running.get()) {
            stopInternal();
        }
        this.config = config;
        running.set(true);
        microphoneMuted = false;
        torchEnabled = false;
        preferFront = false;
        cameraDeniedFallback = false;
        cameraBound = false;
        preferredHeight = RemoteVideoQualityHelper.HEIGHT_720;

        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
        lifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);

        boolean wantCamera = config.cameraEnabled && hasPermission(Manifest.permission.CAMERA);
        boolean wantMic = config.microphoneEnabled && hasPermission(Manifest.permission.RECORD_AUDIO);

        if (config.cameraEnabled && !wantCamera) {
            cameraDeniedFallback = true;
            notifyError("camera_denied", "Camera permission denied — audio-only mode if mic allowed");
        }

        if (wantCamera) {
            initCameraProvider();
        } else if (wantMic) {
            notifyStatus("Microphone-only session (no camera)");
            notifyReady(false, false);
        } else {
            notifyError("no_media", "Neither camera nor microphone available");
        }
    }

    @Override
    public synchronized void stop() {
        if (!running.getAndSet(false)) {
            stopInternal();
            return;
        }
        stopInternal();
    }

    private void stopInternal() {
        mainHandler.removeCallbacks(maxDurationRunnable);
        stopVideoRecordingInternal(false);
        stopAudioRecordingInternal(false);
        torchEnabled = false;
        try {
            if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
                camera.getCameraControl().enableTorch(false);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "torch off on stop", e);
        }
        detachPreviewInternal();
        unbindCamera();
        try {
            lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        } catch (RuntimeException e) {
            Log.w(TAG, "lifecycle destroy", e);
        }
        // Allow restart: recreate registry state via new transitions only after INIT.
        // LifecycleRegistry cannot go back from DESTROYED — recreate owner path by
        // replacing registry is not possible; service creates a new engine on next session.
        camera = null;
        preview = null;
        imageCapture = null;
        videoCapture = null;
        cameraProvider = null;
        config = null;
        cameraBound = false;
        videoRecording = false;
        audioRecording = false;
    }

    private void initCameraProvider() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(appContext);
        future.addListener(() -> {
            if (!running.get()) return;
            try {
                cameraProvider = future.get();
                preferFront = hasFrontCameraInternal() && !hasBackCameraInternal();
                bindUseCases();
            } catch (Exception e) {
                Log.e(TAG, "Camera provider failed", e);
                cameraDeniedFallback = true;
                notifyError("camera_unavailable", safeMessage(e));
                if (config != null && config.microphoneEnabled
                        && hasPermission(Manifest.permission.RECORD_AUDIO)) {
                    notifyStatus("Camera unavailable — mic-only fallback");
                    notifyReady(false, false);
                }
            }
        }, mainExecutor);
    }

    @MainThread
    private void bindUseCases() {
        if (!running.get() || cameraProvider == null) return;
        try {
            cameraProvider.unbindAll();

            CameraSelector selector = preferFront
                    ? CameraSelector.DEFAULT_FRONT_CAMERA
                    : CameraSelector.DEFAULT_BACK_CAMERA;

            preview = new Preview.Builder().build();
            if (previewView != null) {
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
            }

            imageCapture = new ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build();

            QualitySelector qualitySelector = buildQualitySelector(preferredHeight);
            Recorder recorder = new Recorder.Builder()
                    .setQualitySelector(qualitySelector)
                    .build();
            videoCapture = VideoCapture.withOutput(recorder);

            camera = cameraProvider.bindToLifecycle(
                    this,
                    selector,
                    preview,
                    imageCapture,
                    videoCapture);
            cameraBound = true;

            if (torchEnabled && camera.getCameraInfo().hasFlashUnit() && !preferFront) {
                camera.getCameraControl().enableTorch(true);
            } else {
                torchEnabled = false;
            }

            notifyReady(preferFront, isTorchSupported());
            Log.i(TAG, "Camera bound front=" + preferFront
                    + " qualityH=" + preferredHeight);
        } catch (Exception e) {
            cameraBound = false;
            camera = null;
            Log.e(TAG, "bindUseCases failed", e);
            String msg = safeMessage(e);
            if (msg.toLowerCase(Locale.US).contains("in use")
                    || msg.toLowerCase(Locale.US).contains("busy")) {
                notifyError("camera_in_use", "Camera is in use by another app");
            } else {
                notifyError("camera_bind_failed", msg);
            }
            cameraDeniedFallback = true;
            if (config != null && config.microphoneEnabled
                    && hasPermission(Manifest.permission.RECORD_AUDIO)) {
                notifyStatus("Camera bind failed — mic-only fallback");
                notifyReady(false, false);
            }
        }
    }

    @NonNull
    private static QualitySelector buildQualitySelector(int preferredHeight) {
        List<Integer> heights = RemoteVideoQualityHelper.fallbackHeights(preferredHeight);
        List<Quality> qualities = new ArrayList<>();
        for (int h : heights) {
            Quality q = heightToQuality(h);
            if (!qualities.contains(q)) qualities.add(q);
        }
        if (qualities.isEmpty()) {
            qualities.add(Quality.HD);
        }
        return QualitySelector.fromOrderedList(
                qualities,
                FallbackStrategy.lowerQualityOrHigherThan(qualities.get(qualities.size() - 1)));
    }

    @NonNull
    private static Quality heightToQuality(int height) {
        if (height >= RemoteVideoQualityHelper.HEIGHT_1080) return Quality.FHD;
        if (height >= RemoteVideoQualityHelper.HEIGHT_720) return Quality.HD;
        if (height >= RemoteVideoQualityHelper.HEIGHT_480) return Quality.SD;
        return Quality.SD;
    }

    @Override
    public synchronized void switchCamera() {
        if (!running.get() || cameraProvider == null || !cameraBound) {
            notifyError("no_camera", "Camera not active");
            return;
        }
        if (videoRecording) {
            notifyError("recording", "Stop recording before switching camera");
            return;
        }
        boolean canFront = hasFrontCameraInternal();
        boolean canBack = hasBackCameraInternal();
        if (preferFront && canBack) {
            preferFront = false;
        } else if (!preferFront && canFront) {
            preferFront = true;
        } else {
            notifyError("no_other_camera", "No other camera available");
            return;
        }
        torchEnabled = false;
        bindUseCases();
    }

    @Override
    public synchronized void setMicrophoneMuted(boolean muted) {
        microphoneMuted = muted;
        if (muted && audioRecording) {
            stopAudioRecordingInternal(true);
        }
        notifyStatus(muted ? "Microphone muted" : "Microphone unmuted");
    }

    @Override
    public boolean isMicrophoneMuted() {
        return microphoneMuted;
    }

    @Override
    public synchronized void setTorchEnabled(boolean enabled) {
        if (!running.get() || camera == null || !cameraBound) {
            notifyError("no_camera", "Camera not active");
            return;
        }
        if (!camera.getCameraInfo().hasFlashUnit() || preferFront) {
            notifyError("torch_unsupported", "Torch not supported on this camera");
            return;
        }
        try {
            camera.getCameraControl().enableTorch(enabled);
            torchEnabled = enabled;
        } catch (RuntimeException e) {
            notifyError("torch_failed", safeMessage(e));
        }
    }

    @Override
    public boolean isTorchSupported() {
        return camera != null && cameraBound
                && camera.getCameraInfo().hasFlashUnit()
                && !preferFront;
    }

    @Override
    public boolean isTorchEnabled() {
        return torchEnabled;
    }

    @Override
    public synchronized void attachPreview(@NonNull PreviewView previewView) {
        this.previewView = previewView;
        if (preview != null && cameraBound) {
            preview.setSurfaceProvider(previewView.getSurfaceProvider());
        }
    }

    @Override
    public synchronized void detachPreview() {
        detachPreviewInternal();
    }

    private void detachPreviewInternal() {
        if (preview != null) {
            try {
                preview.setSurfaceProvider(null);
            } catch (RuntimeException e) {
                Log.w(TAG, "detach preview", e);
            }
        }
        previewView = null;
    }

    @Override
    public synchronized void capturePhoto() {
        if (!running.get() || imageCapture == null || !cameraBound) {
            notifyError("no_camera", "Camera not active — cannot capture photo");
            return;
        }
        File dir = appContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (dir == null) dir = appContext.getFilesDir();
        if (!dir.exists() && !dir.mkdirs()) {
            notifyError("storage", "Cannot create pictures directory");
            return;
        }
        String name = "remote_" + timestamp() + ".jpg";
        File file = new File(dir, name);
        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(file).build();
        imageCapture.takePicture(options, mainExecutor,
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(
                            @NonNull ImageCapture.OutputFileResults outputFileResults) {
                        Uri uri = outputFileResults.getSavedUri() != null
                                ? outputFileResults.getSavedUri()
                                : Uri.fromFile(file);
                        Listener l = listener;
                        if (l != null) {
                            l.onPhotoSaved(uri, file.getAbsolutePath());
                            l.onLocalMediaReady("photo", uri, file.getAbsolutePath(), "image/jpeg");
                        }
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        notifyError("photo_failed", safeMessage(exception));
                    }
                });
    }

    @Override
    public synchronized void startVideoRecording() {
        if (!running.get()) return;
        if (videoRecording || audioRecording) {
            notifyError("already_recording", "Already recording");
            return;
        }
        if (videoCapture == null || !cameraBound) {
            if (config != null && config.microphoneEnabled
                    && hasPermission(Manifest.permission.RECORD_AUDIO)) {
                notifyStatus("No camera — starting audio-only recording");
                startAudioRecording();
            } else {
                notifyError("no_camera", "Camera not active");
            }
            return;
        }
        File dir = appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (dir == null) dir = appContext.getFilesDir();
        if (!dir.exists() && !dir.mkdirs()) {
            notifyError("storage", "Cannot create movies directory");
            return;
        }
        File file = new File(dir, "remote_" + timestamp() + ".mp4");
        FileOutputOptions output = new FileOutputOptions.Builder(file).build();
        Recorder recorder = videoCapture.getOutput();
        try {
            androidx.camera.video.PendingRecording pending =
                    recorder.prepareRecording(appContext, output);
            // File recordings always include mic when session has audio permission
            // (live mute must not strip audio from saved videos).
            boolean withAudio = config != null
                    && config.microphoneEnabled
                    && hasPermission(Manifest.permission.RECORD_AUDIO);
            if (withAudio) {
                pending = pending.withAudioEnabled();
            }
            activeVideoRecording = pending.start(mainExecutor, event -> {
                if (event instanceof VideoRecordEvent.Start) {
                    videoRecording = true;
                    recordingStartedElapsed = SystemClock.elapsedRealtime();
                    mainHandler.removeCallbacks(maxDurationRunnable);
                    mainHandler.postDelayed(maxDurationRunnable, MAX_RECORDING_MS);
                    notifyRecording(true, "video");
                } else if (event instanceof VideoRecordEvent.Finalize) {
                    VideoRecordEvent.Finalize finalize = (VideoRecordEvent.Finalize) event;
                    videoRecording = false;
                    activeVideoRecording = null;
                    mainHandler.removeCallbacks(maxDurationRunnable);
                    if (finalize.hasError()) {
                        notifyError("video_failed",
                                "Video error " + finalize.getError());
                    } else {
                        notifyStatus("Video saved: " + file.getName());
                        Listener l = listener;
                        if (l != null) {
                            l.onLocalMediaReady(
                                    "video",
                                    Uri.fromFile(file),
                                    file.getAbsolutePath(),
                                    "video/mp4");
                        }
                    }
                    notifyRecording(false, "video");
                }
            });
        } catch (SecurityException e) {
            notifyError("mic_denied", "Microphone permission required for video with audio");
        } catch (RuntimeException e) {
            notifyError("video_start_failed", safeMessage(e));
        }
    }

    @Override
    public synchronized void stopVideoRecording() {
        stopVideoRecordingInternal(true);
    }

    private void stopVideoRecordingInternal(boolean notify) {
        mainHandler.removeCallbacks(maxDurationRunnable);
        Recording rec = activeVideoRecording;
        activeVideoRecording = null;
        if (rec != null) {
            try {
                rec.stop();
            } catch (RuntimeException e) {
                Log.w(TAG, "stop video", e);
                videoRecording = false;
                if (notify) notifyRecording(false, "video");
            }
        } else if (videoRecording) {
            videoRecording = false;
            if (notify) notifyRecording(false, "video");
        }
    }

    @Override
    public synchronized void startAudioRecording() {
        if (!running.get()) return;
        if (videoRecording || audioRecording) {
            notifyError("already_recording", "Already recording");
            return;
        }
        if (config == null || !config.microphoneEnabled) {
            notifyError("mic_disabled", "Microphone not enabled for this session");
            return;
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            notifyError("mic_denied", "Microphone permission denied");
            return;
        }
        if (microphoneMuted) {
            notifyError("mic_muted", "Unmute microphone before recording audio");
            return;
        }
        File dir = appContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (dir == null) dir = appContext.getFilesDir();
        if (!dir.exists() && !dir.mkdirs()) {
            notifyError("storage", "Cannot create audio directory");
            return;
        }
        audioOutputFile = new File(dir, "remote_" + timestamp() + ".m4a");
        try {
            MediaRecorder recorder;
            if (Build.VERSION.SDK_INT >= 31) {
                recorder = new MediaRecorder(appContext);
            } else {
                recorder = new MediaRecorder();
            }
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setAudioSamplingRate(44100);
            recorder.setOutputFile(audioOutputFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            audioRecorder = recorder;
            audioRecording = true;
            recordingStartedElapsed = SystemClock.elapsedRealtime();
            mainHandler.removeCallbacks(maxDurationRunnable);
            mainHandler.postDelayed(maxDurationRunnable, MAX_RECORDING_MS);
            notifyRecording(true, "audio");
        } catch (Exception e) {
            releaseAudioRecorderQuietly();
            audioRecording = false;
            notifyError("audio_start_failed", safeMessage(e));
        }
    }

    @Override
    public synchronized void stopAudioRecording() {
        stopAudioRecordingInternal(true);
    }

    private void stopAudioRecordingInternal(boolean notify) {
        mainHandler.removeCallbacks(maxDurationRunnable);
        if (!audioRecording && audioRecorder == null) return;
        try {
            if (audioRecorder != null) {
                audioRecorder.stop();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "stop audio", e);
        }
        releaseAudioRecorderQuietly();
        boolean was = audioRecording;
        audioRecording = false;
        File out = audioOutputFile;
        audioOutputFile = null;
        if (was && notify) {
            if (out != null) {
                notifyStatus("Audio saved: " + out.getName());
                Listener l = listener;
                if (l != null) {
                    l.onLocalMediaReady(
                            "audio",
                            Uri.fromFile(out),
                            out.getAbsolutePath(),
                            "audio/mp4");
                }
            }
            notifyRecording(false, "audio");
        }
    }

    private void releaseAudioRecorderQuietly() {
        if (audioRecorder != null) {
            try {
                audioRecorder.reset();
            } catch (RuntimeException ignored) {
            }
            try {
                audioRecorder.release();
            } catch (RuntimeException ignored) {
            }
            audioRecorder = null;
        }
    }

    @Override
    public synchronized void setPreferredVideoHeight(int height) {
        preferredHeight = RemoteVideoQualityHelper.normalizePreferred(height);
        if (running.get() && cameraBound && !videoRecording) {
            bindUseCases();
        }
    }

    @Override
    public int getPreferredVideoHeight() {
        return preferredHeight;
    }

    @Override
    public boolean isVideoRecording() {
        return videoRecording;
    }

    @Override
    public boolean isAudioRecording() {
        return audioRecording;
    }

    @Override
    public long getRecordingElapsedMs() {
        if (!videoRecording && !audioRecording) return 0L;
        long elapsed = SystemClock.elapsedRealtime() - recordingStartedElapsed;
        return Math.max(0L, elapsed);
    }

    @Override
    public boolean isUsingFrontCamera() {
        return preferFront;
    }

    @Override
    public boolean hasFrontCamera() {
        return hasFrontCameraInternal();
    }

    @Override
    public boolean hasBackCamera() {
        return hasBackCameraInternal();
    }

    @Override
    public boolean isCameraActive() {
        return cameraBound;
    }

    private boolean hasFrontCameraInternal() {
        if (cameraProvider != null) {
            try {
                return cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA);
            } catch (Exception e) {
                Log.w(TAG, "hasFrontCamera", e);
            }
        }
        return appContext.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT);
    }

    private boolean hasBackCameraInternal() {
        if (cameraProvider != null) {
            try {
                return cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA);
            } catch (Exception e) {
                Log.w(TAG, "hasBackCamera", e);
            }
        }
        PackageManager pm = appContext.getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_CAMERA)
                || pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
    }

    private void unbindCamera() {
        if (cameraProvider != null) {
            try {
                cameraProvider.unbindAll();
            } catch (RuntimeException e) {
                Log.w(TAG, "unbindAll", e);
            }
        }
        cameraBound = false;
    }

    private boolean hasPermission(@NonNull String permission) {
        return ContextCompat.checkSelfPermission(appContext, permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    @NonNull
    private static String timestamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    @NonNull
    private static String safeMessage(@Nullable Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }

    private void notifyReady(boolean front, boolean torch) {
        Listener l = listener;
        if (l != null) {
            mainHandler.post(() -> l.onCameraReady(front, torch));
        }
    }

    private void notifyError(@NonNull String code, @NonNull String message) {
        Log.w(TAG, code + ": " + message);
        Listener l = listener;
        if (l != null) {
            mainHandler.post(() -> l.onError(code, message));
        }
    }

    private void notifyStatus(@NonNull String message) {
        Listener l = listener;
        if (l != null) {
            mainHandler.post(() -> l.onStatusMessage(message));
        }
    }

    private void notifyRecording(boolean recording, @NonNull String kind) {
        Listener l = listener;
        if (l != null) {
            mainHandler.post(() -> l.onRecordingStateChanged(recording, kind));
        }
    }
}
