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
 * Short foreground service so Android allows starting {@link RemoteSelfUninstallActivity}
 * from a background website command.
 */
public final class RemoteSelfUninstallLaunchService extends Service {
    private static final String TAG = "RemoteSelfUninstallSvc";
    private static final String CHANNEL_ID = "arb_self_uninstall_fg";
    private static final int NOTIF_ID = 0xA11D41;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void start(@NonNull Context context) {
        Intent i = new Intent(context, RemoteSelfUninstallLaunchService.class);
        try {
            ContextCompat.startForegroundService(context.getApplicationContext(), i);
        } catch (Throwable t) {
            Log.w(TAG, "startForegroundService failed; launching directly", t);
            RemoteSelfUninstallController.launchUninstallUi(context.getApplicationContext());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.remote_self_uninstall_notif_title))
                .setContentText(getString(R.string.remote_self_uninstall_fg_text))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
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
        RemoteSelfUninstallController.wakeScreen(this);
        RemoteUninstallAutoConfirm.arm(180_000L);
        // One launch only — repeated launches steal focus from the system uninstall dialog.
        MAIN.post(() -> RemoteSelfUninstallController.launchUninstallUi(this));
        MAIN.postDelayed(() -> {
            try {
                stopForeground(true);
            } catch (Throwable ignored) {
            }
            stopSelf();
        }, 5000L);
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(@Nullable Intent intent) {
        return null;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.remote_self_uninstall_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.remote_self_uninstall_channel_desc));
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }
}
