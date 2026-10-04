package com.autoreplybot.remote;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.autoreplybot.AppConstants;
import com.autoreplybot.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageMetadata;
import com.google.firebase.storage.StorageReference;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** MediaProjection + MediaRecorder screen recording with private Storage upload. */
public class RemoteScreenRecordService extends Service {
    private static final String TAG = "RemoteScreenRecord";
    public static final String ACTION_START = "com.autoreplybot.remote.action.START_SCREEN_RECORD";
    public static final String ACTION_STOP = "com.autoreplybot.remote.action.STOP_SCREEN_RECORD";
    public static final String ACTION_PAUSE = "com.autoreplybot.remote.action.PAUSE_SCREEN_RECORD";
    public static final String ACTION_RESUME = "com.autoreplybot.remote.action.RESUME_SCREEN_RECORD";
    public static final String EXTRA_TRANSFER_ID = "transferId";
    public static final String EXTRA_RECORDING_ID = "recordingId";
    public static final String EXTRA_WITH_MIC = "withMic";
    public static final String EXTRA_QUALITY = "quality";
    public static final String EXTRA_FPS = "fps";
    public static final String CHANNEL_ID = "remote_screen_record";
    private static final int NOTIFICATION_ID = 73012;

    private static final AtomicBoolean ACTIVE = new AtomicBoolean(false);
    /** Direct handle so STOP/PAUSE work even when startService is blocked in background. */
    @Nullable private static volatile RemoteScreenRecordService instance;
    @Nullable private static volatile String pendingRecordingId;
    @Nullable private static volatile String pendingTransferId;

    public static void setPending(@NonNull String recordingId, @NonNull String transferId) {
        pendingRecordingId = recordingId;
        pendingTransferId = transferId;
    }

    public static void clearPending() {
        pendingRecordingId = null;
        pendingTransferId = null;
    }

    public static void cancelPending(@NonNull Context context) {
        String rid = pendingRecordingId;
        String tid = pendingTransferId;
        clearPending();
        RemoteMediaProjectionAutoApprove.disarm();
        if (rid != null && !rid.isEmpty()) {
            markFailed(context, rid, tid, "Cancelled");
        }
    }

    public static void markWaitingForPermission(@NonNull Context context,
                                                @NonNull String recordingId,
                                                @Nullable String transferId,
                                                boolean withMic,
                                                @NonNull String quality,
                                                int fps) {
        writeRecordingRow(context, recordingId, transferId, "Waiting for Permission", 0L, 0L,
                withMic, quality, fps, null, System.currentTimeMillis());
        if (transferId != null && !transferId.isEmpty()) {
            updateTransferRow(context, transferId, "pending", 0, null, null, null);
        }
    }

    public static void markFailed(@NonNull Context context,
                                  @NonNull String recordingId,
                                  @Nullable String transferId,
                                  @NonNull String message) {
        writeRecordingRow(context, recordingId, transferId, "Failed", 0L, 0L,
                false, "720p", 30, message, System.currentTimeMillis());
        if (transferId != null && !transferId.isEmpty()) {
            updateTransferRow(context, transferId, "failed", 0, null, "RECORD_FAILED", message);
        }
        if (recordingId.equals(pendingRecordingId)) {
            clearPending();
        }
    }

    @Nullable private MediaProjection projection;
    @Nullable private VirtualDisplay virtualDisplay;
    @Nullable private MediaRecorder recorder;
    @Nullable private File outputFile;
    @Nullable private String transferId;
    @Nullable private String recordingId;
    private boolean withMic;
    private String quality = "720p";
    private int fps = 30;
    private long startedAt;
    private long pausedAccumulatedMs;
    private long pauseStartedAt;
    private boolean paused;
    private final AtomicBoolean finishing = new AtomicBoolean(false);
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            if (!ACTIVE.get() || finishing.get()) return;
            writeRecordingStatus(paused ? "Paused" : "Recording", null);
            progressHandler.postDelayed(this, 1000L);
        }
    };

    public static boolean isActive() {
        return ACTIVE.get();
    }

    public static void start(@NonNull Context context,
                             @NonNull String transferId,
                             @NonNull String recordingId,
                             boolean withMic,
                             @NonNull String quality,
                             int fps) {
        Intent i = new Intent(context, RemoteScreenRecordService.class);
        i.setAction(ACTION_START);
        i.putExtra(EXTRA_TRANSFER_ID, transferId);
        i.putExtra(EXTRA_RECORDING_ID, recordingId);
        i.putExtra(EXTRA_WITH_MIC, withMic);
        i.putExtra(EXTRA_QUALITY, quality);
        i.putExtra(EXTRA_FPS, fps);
        ContextCompat.startForegroundService(context, i);
    }

    public static void stop(@NonNull Context context) {
        RemoteScreenRecordService svc = instance;
        if (svc != null) {
            svc.progressHandler.post(() -> svc.finishRecording(true));
            return;
        }
        if (pendingRecordingId != null) {
            cancelPending(context);
            return;
        }
        dispatchControl(context, ACTION_STOP);
    }

    public static void pause(@NonNull Context context) {
        RemoteScreenRecordService svc = instance;
        if (svc != null) {
            svc.progressHandler.post(svc::pauseRecording);
            return;
        }
        dispatchControl(context, ACTION_PAUSE);
    }

    public static void resume(@NonNull Context context) {
        RemoteScreenRecordService svc = instance;
        if (svc != null) {
            svc.progressHandler.post(svc::resumeRecording);
            return;
        }
        dispatchControl(context, ACTION_RESUME);
    }

    private static void dispatchControl(@NonNull Context context, @NonNull String action) {
        try {
            Intent i = new Intent(context, RemoteScreenRecordService.class);
            i.setAction(action);
            // Prefer startForegroundService so background command delivery is allowed.
            ContextCompat.startForegroundService(context, i);
        } catch (Exception e) {
            Log.w(TAG, "dispatch " + action + " failed", e);
            try {
                Intent i = new Intent(context, RemoteScreenRecordService.class);
                i.setAction(action);
                context.startService(i);
            } catch (Exception e2) {
                Log.e(TAG, "fallback startService " + action + " failed", e2);
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            if (!ACTIVE.get()) {
                cancelPending(this);
                stopSelf();
                return START_NOT_STICKY;
            }
            // Ensure we stay a valid FGS if Android delivered via startForegroundService.
            try {
                startAsForeground();
            } catch (Exception ignored) {
            }
            finishRecording(true);
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(action)) {
            if (ACTIVE.get()) {
                try {
                    startAsForeground();
                } catch (Exception ignored) {
                }
            }
            pauseRecording();
            return START_STICKY;
        }
        if (ACTION_RESUME.equals(action)) {
            if (ACTIVE.get()) {
                try {
                    startAsForeground();
                } catch (Exception ignored) {
                }
            }
            resumeRecording();
            return START_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTIVE.get()) {
            Log.w(TAG, "Already recording");
            return START_STICKY;
        }
        transferId = intent.getStringExtra(EXTRA_TRANSFER_ID);
        recordingId = intent.getStringExtra(EXTRA_RECORDING_ID);
        if (recordingId == null || recordingId.isEmpty()) {
            recordingId = UUID.randomUUID().toString().replace("-", "");
        }
        withMic = intent.getBooleanExtra(EXTRA_WITH_MIC, false);
        quality = intent.getStringExtra(EXTRA_QUALITY);
        if (quality == null || quality.isEmpty()) quality = "720p";
        fps = intent.getIntExtra(EXTRA_FPS, 30);
        RemoteMediaProjectionHolder.Consent consent = RemoteMediaProjectionHolder.take();
        if (consent == null || consent.resultData == null) {
            markFailed(this, recordingId != null ? recordingId : "",
                    transferId, "MediaProjection consent required — approve the system dialog");
            stopSelf();
            return START_NOT_STICKY;
        }
        clearPending();
        finishing.set(false);
        pausedAccumulatedMs = 0L;
        pauseStartedAt = 0L;
        startAsForeground();
        try {
            beginCapture(consent);
            ACTIVE.set(true);
            writeRecordingStatus("Recording", null);
            progressHandler.removeCallbacks(progressTick);
            progressHandler.postDelayed(progressTick, 1000L);
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
            ACTIVE.set(false);
            String msg = e.getMessage() != null ? e.getMessage() : "start failed";
            if (msg.toLowerCase(Locale.US).contains("re-use")
                    || msg.toLowerCase(Locale.US).contains("reuse")
                    || msg.toLowerCase(Locale.US).contains("timed out")) {
                msg = "Screen capture permission expired. Tap Record Screen again and approve Cast.";
            }
            writeRecordingStatus("Failed", msg);
            RemoteMediaProjectionHolder.clear();
            releaseCaptureOnly();
            stopForeground(true);
            stopSelf();
        }
        return START_STICKY;
    }

    /** Release capture objects without upload (used on failed start). */
    private void releaseCaptureOnly() {
        progressHandler.removeCallbacks(progressTick);
        try {
            if (recorder != null) {
                try {
                    recorder.reset();
                } catch (Exception ignored) {
                }
                try {
                    recorder.release();
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        recorder = null;
        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (RuntimeException ignored) {
            }
            virtualDisplay = null;
        }
        MediaProjection proj = projection;
        projection = null;
        if (proj != null) {
            try {
                proj.stop();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void beginCapture(@NonNull RemoteMediaProjectionHolder.Consent consent) throws Exception {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm == null) throw new IllegalStateException("MediaProjectionManager unavailable");
        Intent data = consent.resultData;
        if (data == null) throw new IllegalStateException("No consent data");
        // One-shot: resultData must not be reused after this call.
        projection = mpm.getMediaProjection(
                consent.resultCode != 0 ? consent.resultCode : Activity.RESULT_OK, data);
        if (projection == null) throw new IllegalStateException("getMediaProjection failed");
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                // Only fail if user revoked capture mid-record (not our own stop).
                if (ACTIVE.get() && !finishing.get()) {
                    finishRecording(false);
                }
            }
        }, null);

        int[] size = sizeForQuality(quality);
        int width = size[0];
        int height = size[1];
        int density = densityDpi();
        outputFile = new File(getCacheDir(), "screen_" + recordingId + ".mp4");
        if (outputFile.exists()) //noinspection ResultOfMethodCallIgnored
            outputFile.delete();

        recorder = new MediaRecorder();
        if (withMic) {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        }
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setOutputFile(outputFile.getAbsolutePath());
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        if (withMic) {
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(128_000);
            recorder.setAudioSamplingRate(44100);
        }
        recorder.setVideoSize(width, height);
        recorder.setVideoFrameRate(Math.min(60, Math.max(15, fps)));
        recorder.setVideoEncodingBitRate(bitrateFor(width, height));
        recorder.prepare();

        virtualDisplay = projection.createVirtualDisplay(
                "RemoteScreenRecord",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.getSurface(),
                null,
                null);
        recorder.start();
        startedAt = System.currentTimeMillis();
        paused = false;
        pausedAccumulatedMs = 0L;
        pauseStartedAt = 0L;
    }

    private long elapsedMs() {
        if (startedAt <= 0) return 0L;
        long now = System.currentTimeMillis();
        long pauseExtra = paused && pauseStartedAt > 0 ? (now - pauseStartedAt) : 0L;
        return Math.max(0L, now - startedAt - pausedAccumulatedMs - pauseExtra);
    }

    private void pauseRecording() {
        if (!ACTIVE.get() || recorder == null || paused || finishing.get()) return;
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                recorder.pause();
                paused = true;
                pauseStartedAt = System.currentTimeMillis();
                writeRecordingStatus("Paused", null);
            } else {
                writeRecordingStatus("Recording", "Pause not supported on this Android version");
            }
        } catch (Exception e) {
            Log.w(TAG, "pause failed", e);
        }
    }

    private void resumeRecording() {
        if (!ACTIVE.get() || recorder == null || !paused || finishing.get()) return;
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                recorder.resume();
                if (pauseStartedAt > 0) {
                    pausedAccumulatedMs += System.currentTimeMillis() - pauseStartedAt;
                }
                pauseStartedAt = 0L;
                paused = false;
                writeRecordingStatus("Recording", null);
            }
        } catch (Exception e) {
            Log.w(TAG, "resume failed", e);
        }
    }

    private void finishRecording(boolean upload) {
        if (!finishing.compareAndSet(false, true)) {
            return;
        }
        progressHandler.removeCallbacks(progressTick);
        ACTIVE.set(false);
        if (paused && pauseStartedAt > 0) {
            pausedAccumulatedMs += System.currentTimeMillis() - pauseStartedAt;
            pauseStartedAt = 0L;
            paused = false;
        }
        long duration = elapsedMs();
        try {
            if (recorder != null) {
                try {
                    recorder.stop();
                } catch (RuntimeException e) {
                    Log.w(TAG, "recorder stop", e);
                }
                try {
                    recorder.reset();
                    recorder.release();
                } catch (RuntimeException e) {
                    Log.w(TAG, "release recorder", e);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "release recorder", e);
        }
        recorder = null;
        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (RuntimeException ignored) {
            }
            virtualDisplay = null;
        }
        MediaProjection proj = projection;
        projection = null;
        if (proj != null) {
            try {
                proj.stop();
            } catch (RuntimeException ignored) {
            }
        }
        writeRecordingStatus("Encoding", null);
        File file = outputFile;
        if (upload && file != null && file.exists() && file.length() > 0) {
            uploadFile(file, duration);
        } else {
            String reason = (file == null || !file.exists())
                    ? "No output file (record a few seconds before Stop)"
                    : (file.length() <= 0
                            ? "Empty recording file"
                            : "Upload skipped");
            writeRecordingMeta("Failed", duration, file != null ? file.length() : 0L, reason);
            updateTransferSafe("failed", 0, null, "NO_FILE", reason);
            stopForeground(true);
            stopSelf();
        }
    }

    private void uploadFile(@NonNull File file, long durationMs) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || transferId == null || transferId.isEmpty()) {
            writeRecordingMeta("Failed", durationMs, file.length(), "Not signed in / missing transfer");
            stopForeground(true);
            stopSelf();
            return;
        }
        writeRecordingStatus("Uploading", null);
        String deviceId = new RemoteControlPrefs(this).getOrCreateDeviceId();
        String path = "users/" + user.getUid() + "/devices/" + deviceId
                + "/screen-recordings/" + transferId + "/recording.mp4";
        updateTransfer(user.getUid(), transferId, "uploading", 20, path, null, null);
        // putFile keeps the file open for the whole upload (putStream + early close caused Failed).
        StorageMetadata meta = new StorageMetadata.Builder()
                .setContentType("video/mp4")
                .build();
        StorageReference ref = FirebaseStorage.getInstance().getReference(path);
        ref.putFile(Uri.fromFile(file), meta)
                .addOnSuccessListener(t -> {
                    updateTransfer(user.getUid(), transferId, "ready", 100, path, null, null);
                    writeRecordingMeta("Completed", durationMs, file.length(), null);
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                    stopForeground(true);
                    stopSelf();
                })
                .addOnFailureListener(e -> {
                    String msg = e.getMessage() != null ? e.getMessage() : "upload failed";
                    Log.e(TAG, "upload failed", e);
                    updateTransfer(user.getUid(), transferId, "failed", 0, path,
                            "UPLOAD_FAILED", msg);
                    writeRecordingMeta("Failed", durationMs, file.length(), msg);
                    stopForeground(true);
                    stopSelf();
                });
    }

    private void updateTransferSafe(@NonNull String status,
                                    int progress,
                                    @Nullable String storagePath,
                                    @Nullable String errorCode,
                                    @Nullable String errorMessage) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || transferId == null || transferId.isEmpty()) return;
        updateTransfer(user.getUid(), transferId, status, progress, storagePath, errorCode, errorMessage);
    }

    private void writeRecordingStatus(@NonNull String status, @Nullable String error) {
        writeRecordingMeta(status, elapsedMs(),
                outputFile != null && outputFile.exists() ? outputFile.length() : 0L, error);
    }

    private void writeRecordingMeta(@NonNull String status,
                                    long durationMs,
                                    long sizeBytes,
                                    @Nullable String error) {
        if (recordingId == null) return;
        long createdAt = startedAt > 0 ? startedAt : System.currentTimeMillis();
        writeRecordingRow(this, recordingId, transferId, status, durationMs, sizeBytes,
                withMic, quality, fps, error, createdAt);
    }

    private void updateTransfer(@NonNull String uid,
                                @NonNull String tid,
                                @NonNull String status,
                                int progress,
                                @Nullable String storagePath,
                                @Nullable String errorCode,
                                @Nullable String errorMessage) {
        updateTransferRow(uid, tid, status, progress, storagePath, errorCode, errorMessage);
    }

    private static void writeRecordingRow(@NonNull Context context,
                                          @NonNull String recordingId,
                                          @Nullable String transferId,
                                          @NonNull String status,
                                          long durationMs,
                                          long sizeBytes,
                                          boolean withMic,
                                          @NonNull String quality,
                                          int fps,
                                          @Nullable String error,
                                          long createdAtMs) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || recordingId.isEmpty()) return;
        String deviceId = new RemoteControlPrefs(context).getOrCreateDeviceId();
        Map<String, Object> row = new HashMap<>();
        row.put("ownerUid", user.getUid());
        row.put("deviceId", deviceId);
        row.put("recordingId", recordingId);
        row.put("displayName", "screen-" + recordingId.substring(0, Math.min(8, recordingId.length()))
                + ".mp4");
        row.put("status", status);
        row.put("durationMs", durationMs);
        row.put("sizeBytes", sizeBytes);
        row.put("quality", quality);
        row.put("fps", fps);
        row.put("withMic", withMic);
        row.put("transferId", transferId != null ? transferId : "");
        if (createdAtMs > 0) row.put("createdAt", createdAtMs);
        if ("Completed".equals(status) || "Failed".equals(status)) {
            row.put("completedAt", System.currentTimeMillis());
        }
        if (error != null) row.put("errorMessage", error);
        FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(user.getUid())
                .collection(AppConstants.FIRESTORE_DEVICES)
                .document(deviceId)
                .collection(AppConstants.FIRESTORE_SCREEN_RECORDINGS)
                .document(recordingId)
                .set(row, SetOptions.merge());
    }

    private static void updateTransferRow(@NonNull Context context,
                                          @NonNull String tid,
                                          @NonNull String status,
                                          int progress,
                                          @Nullable String storagePath,
                                          @Nullable String errorCode,
                                          @Nullable String errorMessage) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || tid.isEmpty()) return;
        updateTransferRow(user.getUid(), tid, status, progress, storagePath, errorCode, errorMessage);
    }

    private static void updateTransferRow(@NonNull String uid,
                                          @NonNull String tid,
                                          @NonNull String status,
                                          int progress,
                                          @Nullable String storagePath,
                                          @Nullable String errorCode,
                                          @Nullable String errorMessage) {
        Map<String, Object> patch = new HashMap<>();
        patch.put("status", status);
        patch.put("progress", progress);
        if (storagePath != null) patch.put("storagePath", storagePath);
        if (errorCode != null) patch.put("errorCode", errorCode);
        if (errorMessage != null) patch.put("errorMessage", errorMessage);
        if ("ready".equals(status) || "failed".equals(status)) {
            patch.put("completedAt", System.currentTimeMillis());
        }
        FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(uid)
                .collection(AppConstants.FIRESTORE_TRANSFERS)
                .document(tid)
                .set(patch, SetOptions.merge());
    }

    private void startAsForeground() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.remote_screen_record_channel),
                        NotificationManager.IMPORTANCE_LOW));
            }
        }
        Intent stop = new Intent(this, RemoteScreenRecordService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 2, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(getString(R.string.remote_screen_record_notif_title))
                .setContentText(getString(R.string.remote_screen_record_notif_text))
                .addAction(0, getString(R.string.remote_stop_sharing), stopPi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if (withMic) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(NOTIFICATION_ID, n, type);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    @NonNull
    private static int[] sizeForQuality(@NonNull String quality) {
        String q = quality.toLowerCase(Locale.US);
        if (q.contains("1080")) return new int[]{1920, 1080};
        if (q.contains("480")) return new int[]{854, 480};
        return new int[]{1280, 720};
    }

    private static int bitrateFor(int w, int h) {
        if (h >= 1000) return 8_000_000;
        if (h >= 700) return 5_000_000;
        return 2_500_000;
    }

    private int densityDpi() {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm != null) {
            wm.getDefaultDisplay().getMetrics(metrics);
            return metrics.densityDpi;
        }
        return 320;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        if (ACTIVE.get() && !finishing.get()) finishRecording(false);
        super.onDestroy();
    }
}
