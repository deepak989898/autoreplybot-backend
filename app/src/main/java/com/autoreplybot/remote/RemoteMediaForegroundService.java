package com.autoreplybot.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.autoreplybot.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service for an approved remote media session.
 * Owns {@link CameraXRemoteMediaEngine} (local preview/photo/record) and
 * {@link RemoteWebRtcPublisher} (live stream to browser after Approve).
 * Camera/mic only start after Phase 3 Approve — never silently.
 */
public class RemoteMediaForegroundService extends Service {
    private static final String TAG = "RemoteMediaFgs";

    public static final String ACTION_START_SESSION =
            "com.autoreplybot.remote.action.START_SESSION";
    public static final String ACTION_STOP_SESSION =
            "com.autoreplybot.remote.action.STOP_SESSION";
    public static final String ACTION_SESSION_STATE_CHANGED =
            "com.autoreplybot.remote.action.SESSION_STATE_CHANGED";
    public static final String ACTION_MEDIA_EVENT =
            "com.autoreplybot.remote.action.MEDIA_EVENT";

    public static final String EXTRA_SESSION_ID = "sessionId";
    public static final String EXTRA_REQUEST_ID = "requestId";
    public static final String EXTRA_CLIENT_ID = "clientId";
    public static final String EXTRA_CLIENT_NAME = "clientName";
    public static final String EXTRA_CAMERA_ENABLED = "cameraEnabled";
    public static final String EXTRA_MICROPHONE_ENABLED = "microphoneEnabled";
    public static final String EXTRA_ACTIVE = "active";
    public static final String EXTRA_EVENT_TYPE = "eventType";
    public static final String EXTRA_EVENT_MESSAGE = "eventMessage";
    public static final String EXTRA_RECORDING = "recording";
    public static final String EXTRA_RECORDING_KIND = "recordingKind";

    public static final String EVENT_ERROR = "error";
    public static final String EVENT_STATUS = "status";
    public static final String EVENT_PHOTO = "photo";
    public static final String EVENT_RECORDING = "recording";
    public static final String EVENT_CAMERA_READY = "camera_ready";

    public static final String CHANNEL_ID = "remote_media_share";
    private static final int NOTIFICATION_ID = 73001;

    private static final Object LOCK = new Object();
    @Nullable private static volatile SessionSnapshot activeSnapshot;
    @Nullable private static volatile RemoteMediaForegroundService instance;

    private final LocalBinder binder = new LocalBinder();
    @Nullable private RemoteMediaEngine mediaEngine;
    @Nullable private RemoteWebRtcPublisher webRtcPublisher;
    @Nullable private RemoteCommandListener commandListener;
    @Nullable private ListenerRegistration sessionStatusRegistration;
    @Nullable private PowerManager.WakeLock wakeLock;
    @Nullable private SessionSnapshot session;
    private boolean recordingActive;
    @NonNull private String recordingKind = "";
    private boolean stopping;
    private final ExecutorService uploadExecutor = Executors.newSingleThreadExecutor();

    public final class LocalBinder extends Binder {
        @NonNull
        public RemoteMediaForegroundService getService() {
            return RemoteMediaForegroundService.this;
        }
    }

    public static final class SessionSnapshot {
        @NonNull public final String sessionId;
        @NonNull public final String requestId;
        @NonNull public final String clientId;
        @NonNull public final String clientName;
        public final boolean cameraEnabled;
        public final boolean microphoneEnabled;
        public final long startedAtElapsedRealtime;

        SessionSnapshot(@NonNull String sessionId,
                        @NonNull String requestId,
                        @NonNull String clientId,
                        @NonNull String clientName,
                        boolean cameraEnabled,
                        boolean microphoneEnabled,
                        long startedAtElapsedRealtime) {
            this.sessionId = sessionId;
            this.requestId = requestId;
            this.clientId = clientId;
            this.clientName = clientName;
            this.cameraEnabled = cameraEnabled;
            this.microphoneEnabled = microphoneEnabled;
            this.startedAtElapsedRealtime = startedAtElapsedRealtime;
        }
    }

    public static boolean isSessionActive() {
        return activeSnapshot != null;
    }

    /** True when a live remote session is streaming microphone to the website. */
    public static boolean ownsLiveMicrophone() {
        SessionSnapshot snap = activeSnapshot;
        return snap != null && snap.microphoneEnabled;
    }

    @Nullable
    public static SessionSnapshot getActiveSnapshot() {
        return activeSnapshot;
    }

    /** Re-enable live WebRTC mic after telephony/call-recording contention. */
    public static void ensureLiveMicrophoneEnabled() {
        RemoteMediaForegroundService svc = instance;
        if (svc == null || !ownsLiveMicrophone()) return;
        if (svc.webRtcPublisher != null) {
            svc.webRtcPublisher.setMicrophoneMuted(false);
        }
    }

    public static void startSession(@NonNull Context context,
                                    @NonNull String sessionId,
                                    @NonNull String requestId,
                                    @NonNull String clientId,
                                    @NonNull String clientName,
                                    boolean cameraEnabled,
                                    boolean microphoneEnabled) {
        Intent intent = new Intent(context, RemoteMediaForegroundService.class);
        intent.setAction(ACTION_START_SESSION);
        intent.putExtra(EXTRA_SESSION_ID, sessionId);
        intent.putExtra(EXTRA_REQUEST_ID, requestId);
        intent.putExtra(EXTRA_CLIENT_ID, clientId);
        intent.putExtra(EXTRA_CLIENT_NAME, clientName);
        intent.putExtra(EXTRA_CAMERA_ENABLED, cameraEnabled);
        intent.putExtra(EXTRA_MICROPHONE_ENABLED, microphoneEnabled);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void stopSession(@NonNull Context context) {
        Intent intent = new Intent(context, RemoteMediaForegroundService.class);
        intent.setAction(ACTION_STOP_SESSION);
        context.startService(intent);
    }

    @Nullable
    public RemoteMediaEngine getMediaEngine() {
        return mediaEngine;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        ensureChannel();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent == null) {
            stopFully("null_intent");
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP_SESSION.equals(action)) {
            stopFully("disconnect");
            return START_NOT_STICKY;
        }
        if (ACTION_START_SESSION.equals(action)) {
            handleStart(intent);
            return START_STICKY;
        }
        stopFully("unknown_action");
        return START_NOT_STICKY;
    }

    private void handleStart(@NonNull Intent intent) {
        String sessionId = safe(intent.getStringExtra(EXTRA_SESSION_ID));
        String requestId = safe(intent.getStringExtra(EXTRA_REQUEST_ID));
        String clientId = safe(intent.getStringExtra(EXTRA_CLIENT_ID));
        String clientName = safe(intent.getStringExtra(EXTRA_CLIENT_NAME));
        boolean camera = intent.getBooleanExtra(EXTRA_CAMERA_ENABLED, false);
        boolean mic = intent.getBooleanExtra(EXTRA_MICROPHONE_ENABLED, false);
        if (sessionId.isEmpty() || (!camera && !mic)) {
            Log.w(TAG, "START_SESSION rejected: missing sessionId or capabilities");
            stopFully("invalid_start");
            return;
        }
        if (clientName.isEmpty()) {
            clientName = getString(R.string.remote_default_client_name);
        }

        synchronized (LOCK) {
            if (session != null && sessionId.equals(session.sessionId)) {
                promoteForeground(session);
                return;
            }
            if (session != null) {
                releaseEngineAndWakeLock();
            }
            session = new SessionSnapshot(
                    sessionId,
                    requestId,
                    clientId,
                    clientName,
                    camera,
                    mic,
                    android.os.SystemClock.elapsedRealtime());
            activeSnapshot = session;
        }

        recordingActive = false;
        recordingKind = "";
        stopping = false;
        // Live WebRTC mic must keep exclusive capture — call recording would mute website audio.
        RemoteCallRecordingService.stop(this);
        promoteForeground(session);
        acquireWakeLock();

        // WebRTC must own the camera on many OEMs (Oppo/Realme/etc.). Opening CameraX
        // at the same time often yields a Connected peer connection with black frames.
        boolean webrtcOwnsCamera = camera;
        CameraXRemoteMediaEngine engine = new CameraXRemoteMediaEngine(this);
        engine.setListener(engineListener);
        mediaEngine = engine;
        mediaEngine.start(new RemoteMediaEngine.SessionConfig(
                sessionId,
                camera && !webrtcOwnsCamera,
                mic));

        RemoteWebRtcPublisher publisher = new RemoteWebRtcPublisher(this);
        publisher.setListener(webRtcListener);
        webRtcPublisher = publisher;
        int qualityHeight = RemoteVideoQualityHelper.HEIGHT_720;
        publisher.start(sessionId, camera, mic, qualityHeight);

        startCommandListener(sessionId);
        startSessionStatusWatch(sessionId);

        broadcastState(true);
        Log.i(TAG, "Session started id=" + sessionId
                + " webrtcOwnsCamera=" + webrtcOwnsCamera);
    }

    private void startCommandListener(@NonNull String sessionId) {
        stopCommandListener();
        String deviceId = new RemoteControlPrefs(this).getOrCreateDeviceId();
        commandListener = new RemoteCommandListener(sessionId, deviceId, commandExecutor);
        commandListener.start();
    }

    private void stopCommandListener() {
        if (commandListener != null) {
            commandListener.stop();
            commandListener = null;
        }
    }

    private void startSessionStatusWatch(@NonNull String sessionId) {
        stopSessionStatusWatch();
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        sessionStatusRegistration = FirebaseFirestore.getInstance()
                .collection(com.autoreplybot.AppConstants.FIRESTORE_USERS)
                .document(user.getUid())
                .collection(com.autoreplybot.AppConstants.FIRESTORE_SESSIONS)
                .document(sessionId)
                .addSnapshotListener((snap, error) -> {
                    if (error != null || snap == null || !snap.exists()) return;
                    String status = RemoteMapValues.string(snap.getData(), "status");
                    if ("ended".equals(status) || "failed".equals(status)) {
                        stopFully("remote_" + status);
                    }
                });
    }

    private void stopSessionStatusWatch() {
        if (sessionStatusRegistration != null) {
            sessionStatusRegistration.remove();
            sessionStatusRegistration = null;
        }
    }

    private final RemoteCommandListener.Executor commandExecutor =
            new RemoteCommandListener.Executor() {
                @Override
                public void execute(@NonNull RemoteCommandAction action,
                                    @Nullable Map<String, Object> payload) {
                    applyRemoteCommand(action, payload);
                }

                @Override
                public void onCommandAudit(@NonNull RemoteCommandAction action,
                                           @NonNull String result) {
                    auditForCommand(action, result);
                }
            };

    private void applyRemoteCommand(@NonNull RemoteCommandAction action,
                                    @Nullable Map<String, Object> payload) {
        switch (action) {
            case END_SESSION:
                stopFully("remote_command");
                break;
            case SET_CAMERA_FRONT:
            case SET_CAMERA_BACK:
            case SWITCH_CAMERA:
                commandSwitchCamera();
                break;
            case TORCH_ON:
                commandSetTorch(true);
                break;
            case TORCH_OFF:
                commandSetTorch(false);
                break;
            case MIC_MUTE:
                commandSetMicMuted(true);
                break;
            case MIC_UNMUTE:
                commandSetMicMuted(false);
                break;
            case CAPTURE_PHOTO:
                commandCapturePhoto();
                break;
            case START_VIDEO_RECORDING:
                commandStartVideoRecording();
                break;
            case STOP_VIDEO_RECORDING:
                commandStopVideoRecording();
                break;
            case START_AUDIO_RECORDING:
                commandStartAudioRecording();
                break;
            case STOP_AUDIO_RECORDING:
                commandStopAudioRecording();
                break;
            case SET_QUALITY:
                if (payload != null) {
                    Object h = payload.get("height");
                    if (h instanceof Number) {
                        commandSetQuality(((Number) h).intValue());
                    } else if (h != null) {
                        try {
                            commandSetQuality(Integer.parseInt(String.valueOf(h)));
                        } catch (NumberFormatException ignored) {
                            // ignore bad payload
                        }
                    }
                }
                break;
            case SET_ZOOM:
                // Zoom not exposed on RemoteMediaEngine yet; ack as no-op.
                broadcastMediaEvent(EVENT_STATUS, "zoom_noop", recordingActive, recordingKind);
                break;
            case PING_DEVICE:
                broadcastMediaEvent(EVENT_STATUS, "pong", recordingActive, recordingKind);
                break;
            default:
                throw new IllegalArgumentException("Unsupported action " + action);
        }
    }

    private void auditForCommand(@NonNull RemoteCommandAction action, @NonNull String result) {
        SessionSnapshot snap = session;
        if (snap == null) return;
        RemoteAuditAction auditAction;
        switch (action) {
            case CAPTURE_PHOTO:
                auditAction = RemoteAuditAction.PHOTO_CAPTURED;
                break;
            case START_VIDEO_RECORDING:
            case START_AUDIO_RECORDING:
                auditAction = RemoteAuditAction.RECORDING_STARTED;
                break;
            case STOP_VIDEO_RECORDING:
            case STOP_AUDIO_RECORDING:
                auditAction = RemoteAuditAction.RECORDING_STOPPED;
                break;
            case SWITCH_CAMERA:
            case SET_CAMERA_FRONT:
            case SET_CAMERA_BACK:
                auditAction = RemoteAuditAction.CAMERA_SWITCHED;
                break;
            case END_SESSION:
                // SESSION_ENDED written in stopFully.
                return;
            default:
                return;
        }
        Map<String, Object> meta = new HashMap<>();
        meta.put("command", action.name());
        try {
            new RemoteAuditRepository(this).append(
                    auditAction,
                    new RemoteControlPrefs(this).getOrCreateDeviceId(),
                    snap.clientId,
                    snap.sessionId,
                    result,
                    meta);
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }

    private final RemoteWebRtcPublisher.Listener webRtcListener =
            new RemoteWebRtcPublisher.Listener() {
                @Override
                public void onPublisherState(@NonNull String state, @NonNull String detail) {
                    broadcastMediaEvent(EVENT_STATUS, "webrtc:" + state + " " + detail,
                            recordingActive, recordingKind);
                }

                @Override
                public void onPublisherError(@NonNull String code, @NonNull String message) {
                    broadcastMediaEvent(EVENT_ERROR, "webrtc:" + code + ": " + message,
                            recordingActive, recordingKind);
                }
            };

    private final RemoteMediaEngine.Listener engineListener = new RemoteMediaEngine.Listener() {
        @Override
        public void onCameraReady(boolean frontCamera, boolean torchSupported) {
            broadcastMediaEvent(EVENT_CAMERA_READY,
                    frontCamera ? "front" : "back",
                    false, "");
            refreshNotification();
        }

        @Override
        public void onError(@NonNull String code, @NonNull String message) {
            broadcastMediaEvent(EVENT_ERROR, code + ": " + message, recordingActive, recordingKind);
        }

        @Override
        public void onPhotoSaved(@NonNull Uri uri, @NonNull String absolutePath) {
            broadcastMediaEvent(EVENT_PHOTO, absolutePath, recordingActive, recordingKind);
        }

        @Override
        public void onLocalMediaReady(@NonNull String kind,
                                      @NonNull Uri uri,
                                      @NonNull String absolutePath,
                                      @NonNull String contentType) {
            enqueueCloudUpload(kind, absolutePath, contentType);
        }

        @Override
        public void onRecordingStateChanged(boolean recording, @NonNull String kind) {
            recordingActive = recording;
            recordingKind = recording ? kind : "";
            refreshNotification();
            broadcastMediaEvent(EVENT_RECORDING,
                    recording ? ("started:" + kind) : ("stopped:" + kind),
                    recording,
                    kind);
        }

        @Override
        public void onStatusMessage(@NonNull String message) {
            broadcastMediaEvent(EVENT_STATUS, message, recordingActive, recordingKind);
        }
    };

    private void promoteForeground(@NonNull SessionSnapshot snap) {
        Notification notification = buildNotification(snap);
        int types = 0;
        if (snap.cameraEnabled) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        }
        if (snap.microphoneEnabled) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        if (types == 0) {
            types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, types);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void refreshNotification() {
        SessionSnapshot snap = session;
        if (snap == null) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(snap));
        }
    }

    @NonNull
    private Notification buildNotification(@NonNull SessionSnapshot snap) {
        // Tap opens Remote Control home (optional controls), not a full-screen share UI.
        Intent open = new Intent(this, RemoteControlHomeActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(
                this,
                snap.sessionId.hashCode(),
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, RemoteMediaForegroundService.class);
        stop.setAction(ACTION_STOP_SESSION);
        PendingIntent disconnect = PendingIntent.getService(
                this,
                snap.sessionId.hashCode() + 1,
                stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String text;
        if (recordingActive && "video".equals(recordingKind)) {
            text = getString(R.string.remote_media_notification_recording_video, snap.clientName);
        } else if (recordingActive && "audio".equals(recordingKind)) {
            text = getString(R.string.remote_media_notification_recording_audio, snap.clientName);
        } else {
            text = getString(R.string.remote_media_notification_text, snap.clientName);
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(getString(R.string.remote_media_notification_title))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(content)
                .addAction(0, getString(R.string.remote_media_disconnect), disconnect)
                .build();
    }

    private void stopFully(@NonNull String reason) {
        if (stopping) return;
        stopping = true;
        Log.i(TAG, "Stopping session reason=" + reason);
        String sessionId = session != null ? session.sessionId : null;
        String clientId = session != null ? session.clientId : null;
        stopCommandListener();
        stopSessionStatusWatch();
        releaseEngineAndWakeLock();
        synchronized (LOCK) {
            session = null;
            activeSnapshot = null;
        }
        recordingActive = false;
        recordingKind = "";
        broadcastState(false);
        if (sessionId != null && !sessionId.isEmpty()) {
            try {
                // Skip Firestore end if remote already ended the session doc.
                if (!reason.startsWith("remote_ended") && !reason.startsWith("remote_failed")) {
                    new RemoteSessionRepository().endSession(sessionId, reason);
                }
                new RemoteSignalRepository().deleteAllSignals(sessionId);
                new RemoteAuditRepository(this).append(
                        RemoteAuditAction.SESSION_ENDED,
                        new RemoteControlPrefs(this).getOrCreateDeviceId(),
                        clientId,
                        sessionId,
                        "ok",
                        Collections.singletonMap("reason", reason));
            } catch (RuntimeException ignored) {
                // Best-effort Firestore cleanup; local stop must still proceed.
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void releaseEngineAndWakeLock() {
        try {
            if (webRtcPublisher != null) {
                webRtcPublisher.setListener(null);
                webRtcPublisher.stop();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "webRtcPublisher.stop failed", e);
        }
        webRtcPublisher = null;
        try {
            if (mediaEngine != null) {
                mediaEngine.setListener(null);
                mediaEngine.stop();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "mediaEngine.stop failed", e);
        }
        mediaEngine = null;
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) wakeLock.release();
            } catch (RuntimeException e) {
                Log.w(TAG, "wakeLock.release failed", e);
            }
            wakeLock = null;
        }
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "autoreplybot:remote_media");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(4 * 60 * 60 * 1000L);
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.remote_media_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.remote_media_channel_description));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private void broadcastState(boolean active) {
        Intent intent = new Intent(ACTION_SESSION_STATE_CHANGED);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_ACTIVE, active);
        if (session != null) {
            intent.putExtra(EXTRA_SESSION_ID, session.sessionId);
            intent.putExtra(EXTRA_CLIENT_NAME, session.clientName);
        }
        sendBroadcast(intent);
    }

    private void broadcastMediaEvent(@NonNull String type,
                                     @NonNull String message,
                                     boolean recording,
                                     @NonNull String kind) {
        Intent intent = new Intent(ACTION_MEDIA_EVENT);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_EVENT_TYPE, type);
        intent.putExtra(EXTRA_EVENT_MESSAGE, message);
        intent.putExtra(EXTRA_RECORDING, recording);
        intent.putExtra(EXTRA_RECORDING_KIND, kind);
        if (session != null) {
            intent.putExtra(EXTRA_SESSION_ID, session.sessionId);
        }
        sendBroadcast(intent);
    }

    public void commandSwitchCamera() {
        if (mediaEngine != null) mediaEngine.switchCamera();
        if (webRtcPublisher != null) webRtcPublisher.switchCamera();
    }

    public void commandSetMicMuted(boolean muted) {
        if (mediaEngine != null) mediaEngine.setMicrophoneMuted(muted);
        if (webRtcPublisher != null) webRtcPublisher.setMicrophoneMuted(muted);
    }

    public void commandSetTorch(boolean enabled) {
        // Live publish uses WebRTC Camera2 — CameraX is started without the camera on many OEMs.
        if (webRtcPublisher != null) {
            webRtcPublisher.setTorchEnabled(enabled);
        }
        if (mediaEngine != null && mediaEngine.isCameraActive()) {
            mediaEngine.setTorchEnabled(enabled);
        }
    }

    public void commandCapturePhoto() {
        if (mediaEngine != null && mediaEngine.isCameraActive()) {
            mediaEngine.capturePhoto();
            return;
        }
        // Live WebRTC owns the camera — grab a still from the publish track.
        if (webRtcPublisher != null) {
            File dir = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES);
            if (dir == null) dir = getFilesDir();
            if (!dir.exists() && !dir.mkdirs()) {
                broadcastMediaEvent(EVENT_ERROR, "photo_storage: cannot create pictures dir",
                        recordingActive, recordingKind);
                return;
            }
            File out = new File(dir, "remote_webrtc_" + System.currentTimeMillis() + ".jpg");
            webRtcPublisher.captureStillJpeg(out, (ok, error) -> {
                if (!ok) {
                    broadcastMediaEvent(EVENT_ERROR,
                            "photo_failed: " + (error != null ? error : "unknown"),
                            recordingActive, recordingKind);
                    return;
                }
                broadcastMediaEvent(EVENT_PHOTO, out.getAbsolutePath(), recordingActive, recordingKind);
                enqueueCloudUpload("photo", out.getAbsolutePath(), "image/jpeg");
            });
            return;
        }
        if (mediaEngine != null) mediaEngine.capturePhoto();
    }

    private void enqueueCloudUpload(@NonNull String kind,
                                    @NonNull String absolutePath,
                                    @NonNull String contentType) {
        SessionSnapshot snap = session;
        String deviceId = new RemoteControlPrefs(this).getOrCreateDeviceId();
        String sessionId = snap != null ? snap.sessionId : "";
        String clientId = snap != null ? snap.clientId : "";
        uploadExecutor.execute(() -> {
            File file = new File(absolutePath);
            RemoteMediaCloudUploader.Result result = RemoteMediaCloudUploader.uploadFile(
                    file, kind, contentType, deviceId, sessionId, clientId);
            if (result != null) {
                // Remove local capture after cloud save — website Media Files is the copy.
                if (file.exists() && !file.delete()) {
                    Log.w(TAG, "Uploaded but could not delete local " + file.getAbsolutePath());
                }
                broadcastMediaEvent(EVENT_STATUS,
                        "Uploaded to cloud: " + kind + " (" + result.mediaId + ")",
                        recordingActive, recordingKind);
            } else {
                broadcastMediaEvent(EVENT_ERROR,
                        "cloud_upload_failed:" + kind,
                        recordingActive, recordingKind);
            }
        });
    }

    public void commandStartVideoRecording() {
        if (mediaEngine != null) mediaEngine.startVideoRecording();
    }

    public void commandStopVideoRecording() {
        if (mediaEngine != null) mediaEngine.stopVideoRecording();
    }

    public void commandStartAudioRecording() {
        // MediaRecorder exclusive MIC access interrupts WebRTC live mic — pause live track first.
        if (webRtcPublisher != null) webRtcPublisher.setMicrophoneMuted(true);
        if (mediaEngine != null) mediaEngine.startAudioRecording();
        broadcastMediaEvent(EVENT_STATUS,
                "Recording audio file on phone (live mic paused). Use Stop audio file to restore live voice.",
                true, "audio");
    }

    public void commandStopAudioRecording() {
        if (mediaEngine != null) mediaEngine.stopAudioRecording();
        if (webRtcPublisher != null) webRtcPublisher.setMicrophoneMuted(false);
        broadcastMediaEvent(EVENT_STATUS, "Audio file recording stopped — live mic restored",
                false, "");
    }

    public void commandSetQuality(int height) {
        if (mediaEngine != null) mediaEngine.setPreferredVideoHeight(height);
        // Live WebRTC capturer is started at session begin; quality changes apply next session.
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        stopCommandListener();
        stopSessionStatusWatch();
        releaseEngineAndWakeLock();
        uploadExecutor.shutdownNow();
        synchronized (LOCK) {
            session = null;
            activeSnapshot = null;
        }
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
