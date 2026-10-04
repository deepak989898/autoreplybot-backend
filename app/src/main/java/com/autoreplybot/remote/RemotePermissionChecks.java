package com.autoreplybot.remote;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.autoreplybot.NotificationRelayService;

import java.util.Set;

/** Shared permission / access checks for the Remote Control Permissions card. */
public final class RemotePermissionChecks {
    private RemotePermissionChecks() {}

    public static boolean hasCamera(@NonNull Context context) {
        return granted(context, Manifest.permission.CAMERA);
    }

    public static boolean hasMicrophone(@NonNull Context context) {
        return granted(context, Manifest.permission.RECORD_AUDIO);
    }

    /** POST_NOTIFICATIONS is intentionally not used — sessions start without user alerts. */
    public static boolean hasPostNotifications(@NonNull Context context) {
        return true;
    }

    public static boolean hasFineLocation(@NonNull Context context) {
        return granted(context, Manifest.permission.ACCESS_FINE_LOCATION);
    }

    public static boolean hasCoarseLocation(@NonNull Context context) {
        return granted(context, Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    public static boolean hasForegroundLocation(@NonNull Context context) {
        return hasFineLocation(context) || hasCoarseLocation(context);
    }

    public static boolean hasBackgroundLocation(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < 29) return hasForegroundLocation(context);
        return granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION);
    }

    public static boolean hasMediaAccess(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            return granted(context, Manifest.permission.READ_MEDIA_IMAGES)
                    || granted(context, Manifest.permission.READ_MEDIA_VIDEO)
                    || granted(context, Manifest.permission.READ_MEDIA_AUDIO);
        }
        return granted(context, Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    public static boolean hasNotificationListener(@NonNull Context context) {
        Set<String> enabled = NotificationManagerCompat.getEnabledListenerPackages(context);
        if (enabled != null && enabled.contains(context.getPackageName())) {
            return true;
        }
        String flat = Settings.Secure.getString(
                context.getContentResolver(), "enabled_notification_listeners");
        if (TextUtils.isEmpty(flat)) return false;
        ComponentName expected = new ComponentName(context, NotificationRelayService.class);
        for (String raw : flat.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(raw);
            if (expected.equals(cn)) return true;
        }
        return false;
    }

    /** True when internal storage or another SAF folder grant is active. */
    public static boolean hasFolderAccess(@NonNull Context context) {
        RemoteFolderGrantHelper.restoreDefaultGrantIfPersisted(context);
        return RemoteFolderGrantHelper.hasValidFolderAccess(context);
    }

    public static boolean hasSmsAccess(@NonNull Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasCallLogAccess(@NonNull Context context) {
        return granted(context, Manifest.permission.READ_CALL_LOG);
    }

    /** Call log + phone state + mic — required to auto-record answered calls. */
    public static boolean hasCallRecordingReady(@NonNull Context context) {
        return hasCallLogAccess(context)
                && hasMicrophone(context)
                && granted(context, Manifest.permission.READ_PHONE_STATE);
    }

    public static boolean wasAsked(@NonNull Context context, @NonNull String permission) {
        return context.getApplicationContext()
                .getSharedPreferences("remote_perm_asked", Context.MODE_PRIVATE)
                .getBoolean(permission, false);
    }

    public static void markAsked(@NonNull Context context, @NonNull String permission) {
        context.getApplicationContext()
                .getSharedPreferences("remote_perm_asked", Context.MODE_PRIVATE)
                .edit()
                .putBoolean(permission, true)
                .apply();
    }

    public static boolean wasNeverAsked(@NonNull Context context, @NonNull String permission) {
        return !wasAsked(context, permission);
    }

    public static boolean wasDeniedOnce(@NonNull Context context, @NonNull String permission) {
        return wasAsked(context, permission) && !granted(context, permission);
    }

    public static boolean hasContactsAccess(@NonNull Context context) {
        return granted(context, Manifest.permission.READ_CONTACTS);
    }

    private static boolean granted(@NonNull Context context, @NonNull String permission) {
        return ContextCompat.checkSelfPermission(context, permission)
                == PackageManager.PERMISSION_GRANTED;
    }
}
