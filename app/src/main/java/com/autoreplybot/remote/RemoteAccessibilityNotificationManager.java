package com.autoreplybot.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.autoreplybot.R;

/** Foreground notification while a remote accessibility session is active. */
public final class RemoteAccessibilityNotificationManager {
    public static final String CHANNEL_ID = "remote_accessibility_control";
    public static final int NOTIFICATION_ID = 0xA11C01;
    public static final String ACTION_STOP = "com.autoreplybot.remote.A11Y_STOP_SESSION";

    private RemoteAccessibilityNotificationManager() {}

    public static void ensureChannel(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Remote accessibility",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows when your phone is being remotely navigated from a paired browser.");
        nm.createNotificationChannel(channel);
    }

    public static void showSessionActive(@NonNull Context context,
                                           @NonNull String sessionId,
                                           long expiresAt) {
        ensureChannel(context);
        Context app = context.getApplicationContext();
        Intent stop = new Intent(app, RemoteAccessibilityNotifReceiver.class);
        stop.setAction(ACTION_STOP);
        stop.putExtra("sessionId", sessionId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent stopPi = PendingIntent.getBroadcast(app, 1, stop, flags);

        Intent open = new Intent(app, RemoteAccessibilitySetupActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(app, 2, open, flags);

        long remainingMs = Math.max(0L, expiresAt - System.currentTimeMillis());
        int remainingMin = (int) Math.ceil(remainingMs / 60_000.0);

        Notification notification = new NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle("Remote navigation active")
                .setContentText("Session ends in about " + remainingMin + " min. Tap to manage.")
                .setContentIntent(contentPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, "Stop session", stopPi)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, notification);
        }
    }

    public static void cancel(@NonNull Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.cancel(NOTIFICATION_ID);
        }
    }
}
