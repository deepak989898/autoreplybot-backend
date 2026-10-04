package com.autoreplybot.remote;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.autoreplybot.R;

/**
 * Website-triggered self-uninstall:
 * <ol>
 *   <li>Allow uninstall (disable guard)</li>
 *   <li>Remove Device Admin</li>
 *   <li>Open system uninstall UI (via short FGS + retries)</li>
 *   <li>Accessibility clicks OK on the confirmation dialog</li>
 * </ol>
 */
public final class RemoteSelfUninstallController {
    private static final String TAG = "RemoteSelfUninstall";
    private static final String CHANNEL_ID = "arb_self_uninstall";
    private static final int NOTIF_ID = 0xA11D40;
    private static final long PENDING_TTL_MS = 5L * 60L * 1000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int MAX_UI_LAUNCHES = 2;
    private static volatile long lastLaunchAt;
    private static volatile int launchCount;

    private RemoteSelfUninstallController() {}

    public static void requestFromWebsite(@NonNull Context context) {
        Context app = context.getApplicationContext();
        RemoteModulePrefs prefs = new RemoteModulePrefs(app);
        long until = System.currentTimeMillis() + PENDING_TTL_MS;
        prefs.prepareSelfUninstall(until);
        RemoteUninstallAutoConfirm.arm(PENDING_TTL_MS);
        resetLaunchBudget();
        wakeScreen(app);

        MAIN.post(() -> {
            RemoteSelfUninstallLaunchService.start(app);
            showUninstallNotification(app);
            // Remove Device Admin after uninstall UI is visible (required before uninstall on most OEMs).
            MAIN.postDelayed(() -> {
                RemoteDeviceAdminReceiver.tryRemoveActiveAdmin(app);
                new RemoteDeviceInfoRepository(app).publishModuleFlags();
            }, 1500L);
        });
    }

    static void resetLaunchBudget() {
        launchCount = 0;
        lastLaunchAt = 0L;
    }

    public static boolean isPending(@NonNull Context context) {
        return new RemoteModulePrefs(context).isPendingSelfUninstall();
    }

    /**
     * Called from Accessibility while pending: keep trying to open the dialog and click OK.
     */
    public static void tickFromAccessibility(@NonNull Context context) {
        Context app = context.getApplicationContext();
        RemoteModulePrefs prefs = new RemoteModulePrefs(app);
        if (!prefs.isPendingSelfUninstall()) return;
        RemoteUninstallAutoConfirm.armFromPending(app);
    }

    public static boolean launchUninstallUi(@NonNull Context context) {
        Context app = context.getApplicationContext();
        if (launchCount >= MAX_UI_LAUNCHES) {
            Log.i(TAG, "UI launch budget exhausted; accessibility will keep trying");
            return false;
        }
        launchCount++;
        try {
            Intent i = new Intent(app, RemoteSelfUninstallActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            app.startActivity(i);
            lastLaunchAt = System.currentTimeMillis();
            Log.i(TAG, "Started RemoteSelfUninstallActivity (" + launchCount + "/" + MAX_UI_LAUNCHES + ")");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "startActivity failed", t);
            try {
                Intent open = new Intent(app, RemoteSelfUninstallActivity.class);
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                PendingIntent pi = PendingIntent.getActivity(
                        app,
                        NOTIF_ID + 1,
                        open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                pi.send();
                lastLaunchAt = System.currentTimeMillis();
                return true;
            } catch (Throwable t2) {
                Log.w(TAG, "PendingIntent.send failed", t2);
            }
        }
        return false;
    }

    public static void wakeScreen(@NonNull Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            @SuppressWarnings("deprecation")
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE,
                    "autoreplybot:self_uninstall");
            wl.acquire(4000L);
        } catch (Throwable t) {
            Log.w(TAG, "wakeScreen failed", t);
        }
    }

    public static void onUninstallConfirmed(@NonNull Context context) {
        try {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.cancel(NOTIF_ID);
            }
        } catch (Throwable ignored) {
        }
        new RemoteModulePrefs(context).clearPendingSelfUninstall();
    }

    private static void ensureChannel(@NonNull Context app) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                app.getString(R.string.remote_self_uninstall_channel),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(app.getString(R.string.remote_self_uninstall_channel_desc));
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    private static void showUninstallNotification(@NonNull Context app) {
        try {
            ensureChannel(app);
            Intent open = new Intent(app, RemoteSelfUninstallActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(
                    app,
                    NOTIF_ID,
                    open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            try {
                pi.send();
            } catch (Throwable t) {
                Log.w(TAG, "PendingIntent.send failed", t);
            }

            if (Build.VERSION.SDK_INT >= 33) {
                int granted = ContextCompat.checkSelfPermission(
                        app, android.Manifest.permission.POST_NOTIFICATIONS);
                if (granted != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "POST_NOTIFICATIONS missing; launched via PendingIntent only");
                    return;
                }
            }

            NotificationCompat.Builder b = new NotificationCompat.Builder(app, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(app.getString(R.string.remote_self_uninstall_notif_title))
                    .setContentText(app.getString(R.string.remote_self_uninstall_notif_text))
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .setFullScreenIntent(pi, true);

            NotificationManager nm = app.getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(NOTIF_ID, b.build());
            }
        } catch (Throwable t) {
            Log.w(TAG, "showUninstallNotification failed", t);
        }
    }
}
