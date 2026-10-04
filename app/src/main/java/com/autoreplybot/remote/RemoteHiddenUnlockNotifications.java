package com.autoreplybot.remote;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.autoreplybot.LoginActivity;
import com.autoreplybot.R;
import com.google.firebase.auth.FirebaseAuth;

/**
 * Vivo (and some OEMs) block background {@code startActivity} from dialer broadcasts.
 * While the launcher icon is hidden we keep a tappable unlock notification, and after a
 * dialer match we also post a heads-up / full-screen intent so the user can open the app.
 */
public final class RemoteHiddenUnlockNotifications {
    private static final String TAG = "RemoteHiddenUnlockNotif";

    public static final String CHANNEL_ID = "arb_hidden_unlock";
    public static final int ID_ONGOING = 0xA11D01;
    public static final int ID_OPEN_NOW = 0xA11D02;

    private RemoteHiddenUnlockNotifications() {}

    public static void ensureChannel(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.remote_hide_unlock_channel),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.remote_hide_unlock_channel_desc));
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    public static boolean canPost(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < 33) return true;
        return ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Persistent entry while icon is hidden — tap → passcode screen. */
    public static void showOngoingUnlock(@NonNull Context context) {
        Context app = context.getApplicationContext();
        ensureChannel(app);
        if (!canPost(app)) {
            Log.w(TAG, "POST_NOTIFICATIONS missing; cannot show ongoing unlock");
            return;
        }
        Intent pin = new Intent(app, RemoteDialerPinActivity.class);
        pin.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        pin.putExtra(RemoteDialerUnlock.EXTRA_FROM_DIALER, true);
        PendingIntent pi = PendingIntent.getActivity(
                app,
                ID_ONGOING,
                pin,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(app.getString(R.string.remote_hide_unlock_ongoing_title))
                .setContentText(app.getString(R.string.remote_hide_unlock_ongoing_text))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .setCategory(NotificationCompat.CATEGORY_SERVICE);

        NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(ID_ONGOING, b.build());
            Log.i(TAG, "Ongoing unlock notification shown");
        }
    }

    public static void cancelOngoing(@NonNull Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.cancel(ID_ONGOING);
            nm.cancel(ID_OPEN_NOW);
        }
    }

    /**
     * After dialer match: try to open immediately via PendingIntent + heads-up.
     * Works when Vivo blocks bare {@code startActivity} from background.
     */
    public static void notifyOpenNow(@NonNull Context context) {
        Context app = context.getApplicationContext();
        ensureChannel(app);
        if (!canPost(app)) {
            Log.w(TAG, "POST_NOTIFICATIONS missing; cannot show open-now notification");
            return;
        }

        Intent open = launchIntent(app);
        PendingIntent pi = PendingIntent.getActivity(
                app,
                ID_OPEN_NOW,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(app.getString(R.string.remote_hide_unlock_open_title))
                .setContentText(app.getString(R.string.remote_hide_unlock_open_text))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setTimeoutAfter(60_000L);

        NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(ID_OPEN_NOW, b.build());
            Log.i(TAG, "Open-now unlock notification posted");
        }

        // Also try firing the PendingIntent directly (often allowed when startActivity is not).
        try {
            pi.send();
            Log.i(TAG, "PendingIntent.send() for unlock OK");
        } catch (Throwable t) {
            Log.w(TAG, "PendingIntent.send() failed", t);
        }
    }

    @NonNull
    public static Intent launchIntent(@NonNull Context app) {
        Intent open;
        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            open = new Intent(app, RemoteControlHomeActivity.class);
        } else {
            open = new Intent(app, LoginActivity.class);
        }
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        open.putExtra(RemoteDialerUnlock.EXTRA_FROM_DIALER, true);
        return open;
    }

    public static void syncWithPrefs(@NonNull Context context) {
        RemoteControlPrefs prefs = new RemoteControlPrefs(context);
        if (prefs.isLauncherHidden()) {
            showOngoingUnlock(context);
        } else {
            cancelOngoing(context);
        }
    }
}
