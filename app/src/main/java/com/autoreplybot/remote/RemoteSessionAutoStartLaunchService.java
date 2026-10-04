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
 * Short foreground service so Android allows launching {@link RemoteAutoStartActivity}
 * from an FCM background handler (normal user + admin session_auto_start).
 */
public final class RemoteSessionAutoStartLaunchService extends Service {
    private static final String TAG = "RemoteSessionLaunch";
    private static final String CHANNEL_ID = "arb_session_autostart_fg";
    private static final String CHANNEL_ID_SILENT = "arb_session_autostart_silent";
    private static final int NOTIF_ID = 0xA11C91;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile long lastScreenLaunchAtMs;
    private static volatile String lastLaunchKey = "";
    private static volatile long lastLaunchAtMs;

    public static final String EXTRA_SCREEN_MIRROR_FLOW = "screenMirrorFlow";

    public static final String EXTRA_REQUEST_ID = RemoteAutoStartActivity.EXTRA_REQUEST_ID;
    public static final String EXTRA_SESSION_ID = RemoteAutoStartActivity.EXTRA_SESSION_ID;
    public static final String EXTRA_CLIENT_ID = RemoteAutoStartActivity.EXTRA_CLIENT_ID;
    public static final String EXTRA_CLIENT_NAME = RemoteAutoStartActivity.EXTRA_CLIENT_NAME;
    public static final String EXTRA_CAMERA_ENABLED = RemoteAutoStartActivity.EXTRA_CAMERA_ENABLED;
    public static final String EXTRA_MICROPHONE_ENABLED =
            RemoteAutoStartActivity.EXTRA_MICROPHONE_ENABLED;
    public static final String EXTRA_SESSION_KIND = RemoteAutoStartActivity.EXTRA_SESSION_KIND;
    public static final String EXTRA_SCREEN_MIRROR = RemoteAutoStartActivity.EXTRA_SCREEN_MIRROR;

    public static void start(@NonNull Context context, @NonNull Intent payload) {
        start(context, payload, false);
    }

    public static void start(@NonNull Context context,
                             @NonNull Intent payload,
                             boolean screenMirrorFlow) {
        String req = payload.getStringExtra(EXTRA_REQUEST_ID);
        String sess = payload.getStringExtra(EXTRA_SESSION_ID);
        String key = String.valueOf(req) + "|" + String.valueOf(sess);
        long now = System.currentTimeMillis();
        if (!key.equals("|") && key.equals(lastLaunchKey) && now - lastLaunchAtMs < 12000L) {
            Log.i(TAG, "skip duplicate session launch");
            return;
        }
        lastLaunchKey = key;
        lastLaunchAtMs = now;
        Intent i = new Intent(context, RemoteSessionAutoStartLaunchService.class);
        i.putExtras(payload);
        i.putExtra(EXTRA_SCREEN_MIRROR_FLOW, screenMirrorFlow);
        try {
            ContextCompat.startForegroundService(context.getApplicationContext(), i);
        } catch (Throwable t) {
            Log.w(TAG, "startForegroundService failed; launching activity directly", t);
            launchActivity(context.getApplicationContext(), payload);
        }
    }

    private static void launchActivity(@NonNull Context context, @Nullable Intent extras) {
        if (extras != null && extras.getBooleanExtra(EXTRA_SCREEN_MIRROR, false)) {
            long now = System.currentTimeMillis();
            if (now - lastScreenLaunchAtMs < 8000L) {
                Log.i(TAG, "skip duplicate screen mirror launch");
                return;
            }
            lastScreenLaunchAtMs = now;
        }
        Intent open = new Intent(context, RemoteAutoStartActivity.class);
        if (extras != null) open.putExtras(extras);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            context.startActivity(open);
        } catch (Throwable t) {
            Log.w(TAG, "startActivity RemoteAutoStartActivity failed", t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannels();
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID_SILENT)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(getString(R.string.remote_screen_mirror_starting_title))
                .setContentText(getString(R.string.remote_screen_mirror_starting_text))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
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
        final Intent extras = intent != null ? new Intent().putExtras(intent) : new Intent();
        RemoteSessionNotifAutoClick.disarm();
        launchActivity(this, extras);
        MAIN.postDelayed(() -> {
            try {
                stopForeground(true);
            } catch (Throwable ignored) {
            }
            stopSelf();
        }, 3000L);
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel silent = new NotificationChannel(
                CHANNEL_ID_SILENT,
                "Session start",
                NotificationManager.IMPORTANCE_MIN);
        silent.setDescription("Brief helper to open a remote session");
        silent.setShowBadge(false);
        nm.createNotificationChannel(silent);
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                "Session start",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Brief helper to open a remote session");
        nm.createNotificationChannel(ch);
    }
}
