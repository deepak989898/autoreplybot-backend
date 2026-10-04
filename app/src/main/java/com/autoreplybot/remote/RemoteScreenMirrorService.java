package com.autoreplybot.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.autoreplybot.R;

/**
 * Foreground service for live screen mirroring via MediaProjection + WebRTC.
 * Independent from {@link RemoteMediaForegroundService} (camera).
 */
public class RemoteScreenMirrorService extends Service {
    private static final String TAG = "RemoteScreenMirror";
    public static final String ACTION_START = "com.autoreplybot.remote.action.START_SCREEN_MIRROR";
    public static final String ACTION_STOP = "com.autoreplybot.remote.action.STOP_SCREEN_MIRROR";
    public static final String EXTRA_SESSION_ID = "sessionId";
    public static final String EXTRA_REQUEST_ID = "requestId";
    public static final String EXTRA_CLIENT_ID = "clientId";
    public static final String EXTRA_CLIENT_NAME = "clientName";
    public static final String EXTRA_WITH_MIC = "withMic";
    public static final String EXTRA_QUALITY = "quality";
    public static final String EXTRA_FPS = "fps";
    public static final String CHANNEL_ID = "remote_screen_mirror";
    private static final int NOTIFICATION_ID = 73011;

    private static volatile boolean active;
    @Nullable private static volatile String activeSessionId;

    @Nullable private RemoteWebRtcPublisher publisher;
    @Nullable private String sessionId;
    private final RemoteSessionRepository sessionRepository = new RemoteSessionRepository();

    public static boolean isActive() {
        return active;
    }

    @Nullable
    public static String getActiveSessionId() {
        return activeSessionId;
    }

    public static void start(@NonNull Context context,
                             @NonNull String sessionId,
                             @NonNull String requestId,
                             @NonNull String clientId,
                             @NonNull String clientName,
                             boolean withMic,
                             @NonNull String quality,
                             int fps) {
        Intent i = new Intent(context, RemoteScreenMirrorService.class);
        i.setAction(ACTION_START);
        i.putExtra(EXTRA_SESSION_ID, sessionId);
        i.putExtra(EXTRA_REQUEST_ID, requestId);
        i.putExtra(EXTRA_CLIENT_ID, clientId);
        i.putExtra(EXTRA_CLIENT_NAME, clientName);
        i.putExtra(EXTRA_WITH_MIC, withMic);
        i.putExtra(EXTRA_QUALITY, quality);
        i.putExtra(EXTRA_FPS, fps);
        ContextCompat.startForegroundService(context, i);
    }

    public static void stop(@NonNull Context context) {
        Intent i = new Intent(context, RemoteScreenMirrorService.class);
        i.setAction(ACTION_STOP);
        context.startService(i);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            tearDown("stopped_by_user");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID);
        boolean withMic = intent.getBooleanExtra(EXTRA_WITH_MIC, false);
        String quality = intent.getStringExtra(EXTRA_QUALITY);
        int fps = intent.getIntExtra(EXTRA_FPS, 30);
        String clientName = intent.getStringExtra(EXTRA_CLIENT_NAME);
        if (sessionId == null || sessionId.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        RemoteMediaProjectionHolder.Consent consent = RemoteMediaProjectionHolder.take();
        if (consent == null || consent.resultData == null) {
            Log.w(TAG, "No MediaProjection consent");
            RemoteScreenMirrorConsentGate.leave();
            stopSelf();
            return START_NOT_STICKY;
        }
        startAsForeground(clientName != null ? clientName : "Browser");
        active = true;
        activeSessionId = sessionId;
        RemoteMediaProjectionConsentActivity.clearMirrorConsentGuard();
        if (publisher != null) publisher.stop();
        publisher = new RemoteWebRtcPublisher(this);
        publisher.setListener(new RemoteWebRtcPublisher.Listener() {
            @Override
            public void onPublisherState(@NonNull String state, @NonNull String detail) {
                Log.i(TAG, state + " " + detail);
            }

            @Override
            public void onPublisherError(@NonNull String code, @NonNull String message) {
                Log.e(TAG, code + ": " + message);
                if ("projection_revoked".equals(code) || "start_failed".equals(code)) {
                    RemoteMediaProjectionHolder.clear();
                    tearDown("projection_error");
                    stopSelf();
                }
            }
        });
        publisher.startScreen(
                sessionId,
                consent.resultData,
                consent.resultCode,
                withMic,
                heightForQuality(quality),
                fps);
        return START_STICKY;
    }

    private void startAsForeground(@NonNull String clientName) {
        ensureChannel();
        Intent open = new Intent(this, RemoteControlHomeActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, RemoteScreenMirrorService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(getString(R.string.remote_screen_mirror_notif_title))
                .setContentText(getString(R.string.remote_screen_mirror_notif_text, clientName))
                .setContentIntent(pi)
                .addAction(0, getString(R.string.remote_stop_sharing), stopPi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.remote_screen_mirror_channel),
                NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
    }

    private void tearDown(@NonNull String reason) {
        if (publisher != null) {
            publisher.stop();
            publisher = null;
        }
        if (sessionId != null && !sessionId.isEmpty()) {
            sessionRepository.endSession(sessionId, reason);
        }
        active = false;
        activeSessionId = null;
        sessionId = null;
        stopForeground(true);
    }

    private static int heightForQuality(@Nullable String quality) {
        if (quality == null) return 720;
        String q = quality.toLowerCase(java.util.Locale.US);
        if (q.contains("1080") || q.equals("high")) return 1080;
        if (q.contains("480") || q.equals("low")) return 480;
        return 720;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        tearDown("service_destroyed");
        super.onDestroy();
    }
}
