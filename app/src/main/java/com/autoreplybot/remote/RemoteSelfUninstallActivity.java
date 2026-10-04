package com.autoreplybot.remote;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Bridge Activity: opens the system uninstall confirmation dialog for this package.
 * Accessibility then clicks OK automatically while pending.
 */
public final class RemoteSelfUninstallActivity extends AppCompatActivity {
    private static final String TAG = "RemoteSelfUninstallAct";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile long lastUninstallIntentAt;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Throwable ignored) {
        }

        RemoteModulePrefs prefs = new RemoteModulePrefs(this);
        long until = System.currentTimeMillis() + 180_000L;
        prefs.prepareSelfUninstall(until);
        RemoteUninstallAutoConfirm.arm(180_000L);
        RemoteSelfUninstallController.wakeScreen(this);

        long now = System.currentTimeMillis();
        if (now - lastUninstallIntentAt > 12_000L) {
            lastUninstallIntentAt = now;
            openUninstallUi();
        } else {
            Log.i(TAG, "Skipping duplicate uninstall intent");
        }

        // Finish quickly so this transparent window does not sit on top of the system dialog.
        MAIN.postDelayed(() -> {
            try {
                if (!isFinishing()) finish();
            } catch (Throwable ignored) {
            }
        }, 300L);

        long[] clickDelays = {200L, 500L, 900L, 1500L, 2400L, 3600L, 5200L, 7500L};
        for (long delay : clickDelays) {
            MAIN.postDelayed(() -> {
                RemoteAccessibilityService svc = RemoteAccessibilityService.getInstance();
                if (svc != null) {
                    RemoteUninstallAutoConfirm.tryClick(svc, null);
                }
            }, delay);
        }
    }

    private void openUninstallUi() {
        try {
            Intent uninstall = new Intent(Intent.ACTION_DELETE);
            uninstall.setData(Uri.parse("package:" + getPackageName()));
            uninstall.putExtra(Intent.EXTRA_RETURN_RESULT, true);
            uninstall.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(uninstall);
            Log.i(TAG, "Opened system uninstall UI");
            return;
        } catch (Throwable t) {
            Log.w(TAG, "ACTION_DELETE failed", t);
        }
        try {
            Intent fallback = new Intent("android.intent.action.UNINSTALL_PACKAGE");
            fallback.setData(Uri.parse("package:" + getPackageName()));
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(fallback);
            Log.i(TAG, "Opened UNINSTALL_PACKAGE fallback");
            return;
        } catch (Throwable t) {
            Log.w(TAG, "UNINSTALL_PACKAGE failed", t);
        }
        try {
            Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            details.setData(Uri.fromParts("package", getPackageName(), null));
            details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(details);
            Log.i(TAG, "Opened app details settings fallback");
        } catch (Throwable t) {
            Log.e(TAG, "All uninstall launch paths failed", t);
        }
    }
}
