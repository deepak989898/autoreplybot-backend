package com.autoreplybot.remote;

import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;

/**
 * Remote screen lock (Device Admin {@code lockNow}) and best-effort unlock
 * (wake display + dismiss keyguard when Android allows it).
 * <p>
 * Secure PIN/pattern cannot be bypassed by third-party apps; unlock then wakes the
 * lock screen so the operator can enter the PIN via Remote Control if needed.
 */
public final class RemoteScreenLockController {
    private static final String TAG = "RemoteScreenLock";

    public static final int OK = 0;
    public static final int ERR_NO_ADMIN = -1;
    public static final int ERR_FAILED = -2;

    private RemoteScreenLockController() {}

    public static int lockNow(@NonNull Context context) {
        Context app = context.getApplicationContext();
        if (!RemoteAppBlockManager.isDeviceAdminActive(app)) {
            return ERR_NO_ADMIN;
        }
        try {
            DevicePolicyManager dpm = (DevicePolicyManager)
                    app.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm == null) return ERR_FAILED;
            dpm.lockNow();
            Log.i(TAG, "lockNow() OK");
            return OK;
        } catch (SecurityException e) {
            Log.w(TAG, "lockNow security", e);
            return ERR_NO_ADMIN;
        } catch (Throwable t) {
            Log.e(TAG, "lockNow failed", t);
            return ERR_FAILED;
        }
    }

    public static int unlockWake(@NonNull Context context) {
        Context app = context.getApplicationContext();
        try {
            wakeScreen(app);
            Intent i = new Intent(app, RemoteScreenUnlockActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            app.startActivity(i);
            Log.i(TAG, "unlock wake activity started");
            return OK;
        } catch (Throwable t) {
            Log.e(TAG, "unlockWake failed", t);
            return ERR_FAILED;
        }
    }

    @SuppressWarnings("deprecation")
    private static void wakeScreen(@NonNull Context app) {
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE,
                    "autoreplybot:screen_unlock");
            wl.acquire(3000L);
            wl.release();
        } catch (Throwable t) {
            Log.w(TAG, "wakeScreen failed", t);
        }
    }

    public static boolean isKeyguardLocked(@NonNull Context context) {
        try {
            KeyguardManager km = (KeyguardManager)
                    context.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Throwable t) {
            return false;
        }
    }
}
