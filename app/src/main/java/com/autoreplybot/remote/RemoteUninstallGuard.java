package com.autoreplybot.remote;

import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.autoreplybot.R;

/**
 * Soft uninstall / Device-Admin-disable guard while {@link RemoteModulePrefs#isUninstallProtected()}.
 * Complements Device Admin (user must deactivate admin before uninstall on most phones).
 */
public final class RemoteUninstallGuard {
    private static final String TAG = "RemoteUninstallGuard";
    private static final long COOLDOWN_MS = 900L;

    private static volatile long lastHomeAt;

    private RemoteUninstallGuard() {}

    public static boolean isProtected(@NonNull android.content.Context context) {
        return new RemoteModulePrefs(context).isUninstallProtected();
    }

    public static void onAccessibilityEvent(
            @NonNull RemoteAccessibilityService service,
            @NonNull AccessibilityEvent event,
            @NonNull String pkg) {
        if (RemoteSelfUninstallController.isPending(service)
                || RemoteUninstallAutoConfirm.isArmed()) {
            return;
        }
        if (!isProtected(service)) return;
        if (!isSuspiciousPackage(pkg)) return;

        String hay = collectText(service, event).toLowerCase();
        String appLabel = safeLabel(service).toLowerCase();
        String ourPkg = service.getPackageName().toLowerCase();

        boolean mentionsUs = hay.contains(ourPkg)
                || (!appLabel.isEmpty() && hay.contains(appLabel))
                || hay.contains("auto reply bot")
                || hay.contains("autoreplybot");
        if (!mentionsUs) return;

        boolean uninstallUi = containsAny(hay,
                "uninstall", "uninstal", "remove app", "delete app",
                "uninstall updates", "do you want to uninstall",
                "uninstall app", "remove this app");
        boolean adminDisableUi = containsAny(hay,
                "deactivate this device admin",
                "deactivate device admin", "disable this device admin app",
                "turn off device admin", "deactivate admin");

        if (!uninstallUi && !adminDisableUi) return;

        long now = SystemClock.elapsedRealtime();
        if (now - lastHomeAt < COOLDOWN_MS) {
            service.performGlobalAction(RemoteAccessibilityService.GLOBAL_ACTION_HOME);
            return;
        }
        lastHomeAt = now;
        Log.i(TAG, "Blocking uninstall/admin-disable UI for " + pkg);
        service.performGlobalAction(RemoteAccessibilityService.GLOBAL_ACTION_HOME);
        try {
            Toast.makeText(
                    service.getApplicationContext(),
                    R.string.remote_uninstall_blocked_toast,
                    Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    private static boolean isSuspiciousPackage(@NonNull String pkg) {
        String p = pkg.toLowerCase();
        return p.contains("packageinstaller")
                || p.contains("settings")
                || p.contains("security")
                || p.contains("permissioncontroller")
                || p.contains("deviceadmin")
                || p.contains("systemui")
                || p.contains("vivo")
                || p.contains("oplus")
                || p.contains("oneplus")
                || p.contains("coloros")
                || p.contains("bbk");
    }

    private static boolean containsAny(@NonNull String hay, @NonNull String... needles) {
        for (String n : needles) {
            if (hay.contains(n)) return true;
        }
        return false;
    }

    @NonNull
    private static String safeLabel(@NonNull RemoteAccessibilityService service) {
        try {
            CharSequence label = service.getApplicationInfo().loadLabel(service.getPackageManager());
            return label != null ? label.toString() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    @NonNull
    private static String collectText(
            @NonNull RemoteAccessibilityService service,
            @NonNull AccessibilityEvent event) {
        StringBuilder sb = new StringBuilder();
        if (event.getText() != null) {
            for (CharSequence cs : event.getText()) {
                if (cs != null) sb.append(' ').append(cs);
            }
        }
        CharSequence desc = event.getContentDescription();
        if (desc != null) sb.append(' ').append(desc);
        AccessibilityNodeInfo root = null;
        try {
            root = service.getRootInActiveWindow();
            if (root != null) sb.append(' ').append(walk(root, 0));
        } catch (Throwable ignored) {
        } finally {
            if (root != null) {
                try {
                    root.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
        return sb.toString();
    }

    @NonNull
    private static String walk(@Nullable AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 6) return "";
        StringBuilder sb = new StringBuilder();
        CharSequence t = node.getText();
        if (t != null) sb.append(' ').append(t);
        CharSequence d = node.getContentDescription();
        if (d != null) sb.append(' ').append(d);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
                sb.append(walk(child, depth + 1));
            } catch (Throwable ignored) {
            } finally {
                if (child != null) {
                    try {
                        child.recycle();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return sb.toString();
    }
}
