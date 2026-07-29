package com.autoreplybot.remote;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.autoreplybot.R;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Closes blocked apps when they reach the foreground. Must never block the a11y thread. */
public final class RemoteAppBlockAccessibilityService extends AccessibilityService {
    private static final String TAG = "RemoteAppBlockA11y";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private long lastToastAt;
    private long lastHomeAt;
    @Nullable private String lastForcedPackage;
    @Nullable private RemoteAppBlockManager manager;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            if (event == null) return;
            // Only react to window switches — content-changed floods can get the service killed.
            int type = event.getEventType();
            if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                return;
            }
            CharSequence pkgCs = event.getPackageName();
            if (pkgCs == null) return;
            String pkg = pkgCs.toString();
            if (pkg.isEmpty()) return;
            RemoteAppBlockManager mgr = manager;
            if (mgr == null) {
                mgr = new RemoteAppBlockManager(this);
                manager = mgr;
            }
            if (!mgr.shouldBlockPackage(pkg)) return;

            long now = System.currentTimeMillis();
            if (pkg.equals(lastForcedPackage) && now - lastHomeAt < 700) {
                performGlobalAction(GLOBAL_ACTION_HOME);
                return;
            }
            lastForcedPackage = pkg;
            lastHomeAt = now;
            performGlobalAction(GLOBAL_ACTION_HOME);
            if (now - lastToastAt > 2500) {
                lastToastAt = now;
                final String toastPkg = pkg;
                main.post(() -> {
                    try {
                        Toast.makeText(
                                getApplicationContext(),
                                getString(R.string.remote_app_blocked_toast, toastPkg),
                                Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {
                    }
                });
            }
        } catch (Throwable t) {
            // Never let exceptions crash the service — ColorOS/Android will turn it Off.
            Log.e(TAG, "onAccessibilityEvent failed", t);
        }
    }

    @Override
    public void onInterrupt() {
        // no-op
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        try {
            manager = new RemoteAppBlockManager(this);
            // Firestore sync must not run on the accessibility binder/main thread.
            io.execute(() -> {
                try {
                    new RemoteAppBlockManager(getApplicationContext()).syncStatusToCloud();
                } catch (Throwable t) {
                    Log.w(TAG, "sync on connect failed", t);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "onServiceConnected failed", t);
        }
    }

    @Override
    public void onDestroy() {
        try {
            io.shutdownNow();
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
