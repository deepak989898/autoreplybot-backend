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
 * When our app opens the system MediaProjection consent dialog (screen mirror /
 * record), arm this helper so {@link RemoteAccessibilityService} can click the
 * positive button automatically.
 */
public final class RemoteMediaProjectionAutoApprove {
    private static final String TAG = "RemoteCastAutoApprove";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object ARM_TOKEN = new Object();

    private static final AtomicLong ARMED_UNTIL_MS = new AtomicLong(0L);
    private static final AtomicBoolean CLICKED = new AtomicBoolean(false);

    private static final String[] CONSENT_PHRASES = {
            "start recording or casting",
            "recording or casting with",
            "cast your screen",
            "start casting with",
            "start recording with",
            "media projection",
            "share your screen with",
            "capture your screen",
            "record or cast",
            "screen capture",
            "casting with",
            "recording with",
            "screen mirroring",
            "mirror your screen",
            "entire screen",
            "auto reply bot"
    };

    private static final String[] POSITIVE_LABELS = {
            "start now",
            "start recording",
            "start casting",
            "share screen",
            "cast",
            "begin",
            "continue",
            "agree",
            "accept",
            "allow access",
            "अभी शुरू करें",
            "शुरू करें",
            "अनुमति दें",
            "allow",
            "ok",
            "ठीक है"
    };

    private static final String[] NEGATIVE_LABELS = {
            "cancel",
            "deny",
            "don't allow",
            "do not allow",
            "no thanks",
            "dismiss",
            "रद्द करें"
    };

    private RemoteMediaProjectionAutoApprove() {}

    public static void arm(long ttlMs) {
        CLICKED.set(false);
        ARMED_UNTIL_MS.set(System.currentTimeMillis() + Math.max(1000L, ttlMs));
        Log.i(TAG, "armed for " + ttlMs + "ms (a11y connected="
                + RemoteAccessibilityService.isConnected() + ")");
        MAIN.removeCallbacksAndMessages(ARM_TOKEN);
        long[] delays = {50L, 150L, 300L, 500L, 800L, 1200L, 1800L, 2500L, 3500L, 5000L,
                7000L, 10000L, 14000L, 18000L};
        for (long d : delays) {
            MAIN.postAtTime(() -> {
                RemoteAccessibilityService svc = RemoteAccessibilityService.getInstance();
                if (svc == null) {
                    Log.w(TAG, "waiting for accessibility service…");
                    return;
                }
                tryClick(svc);
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
                    if (!looksLikeConsentDialog(root)) continue;

                    AccessibilityNodeInfo entire = findBestClickable(root, new String[]{
                            "entire screen", "पूरी स्क्रीन", "full screen"
                    });
                    if (entire != null) {
                        try {
                            if (!entire.isChecked()) {
                                performClick(entire);
                            }
                        } finally {
                            entire.recycle();
                        }
                    }

                    AccessibilityNodeInfo positive = findApproveButton(root);
                    if (positive == null) continue;
                    try {
                        if (isNegativeLabel(labelOf(positive))) continue;
                        if (performClick(positive)) {
                            markClicked();
                            Log.i(TAG, "auto-approved MediaProjection via click: "
                                    + labelOf(positive));
                            return true;
                        }
                        tryGestureTap(service, positive);
                    } finally {
                        positive.recycle();
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

    private static void tryGestureTap(@NonNull AccessibilityService service,
                                      @NonNull AccessibilityNodeInfo node) {
        if (!(service instanceof RemoteAccessibilityService)) return;
        RemoteAccessibilityGestureExecutor gestures =
                ((RemoteAccessibilityService) service).getGestureExecutor();
        if (gestures == null || gestures.isBusy()) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) return;
        gestures.tap(bounds.exactCenterX(), bounds.exactCenterY(), (success, code) -> {
            if (success && isArmed()) {
                markClicked();
                Log.i(TAG, "auto-approved MediaProjection via gesture tap");
            } else if (!success) {
                Log.w(TAG, "gesture tap failed: " + code);
            }
        });
    }

    @Nullable
    private static AccessibilityNodeInfo findApproveButton(@NonNull AccessibilityNodeInfo root) {
        AccessibilityNodeInfo byCast = findExactLabel(root, "cast");
        if (byCast != null) return byCast;
        AccessibilityNodeInfo byStart = findExactLabel(root, "start");
        if (byStart != null) return byStart;

        String[] ids = {
                "com.android.systemui:id/start_button",
                "com.android.systemui:id/enable_button",
                "com.android.systemui:id/positive_button",
                "android:id/button2",
                "android:id/button1",
                "android:id/button3"
        };
        for (String id : ids) {
            AccessibilityNodeInfo chosen = firstPositiveByViewId(root, id);
            if (chosen != null) return chosen;
        }

        return findBestClickable(root, POSITIVE_LABELS);
    }

    @Nullable
    private static AccessibilityNodeInfo firstPositiveByViewId(@NonNull AccessibilityNodeInfo root,
                                                                 @NonNull String viewId) {
        List<AccessibilityNodeInfo> nodes = null;
        try {
            nodes = root.findAccessibilityNodeInfosByViewId(viewId);
        } catch (Throwable ignored) {
        }
        if (nodes == null || nodes.isEmpty()) return null;
        AccessibilityNodeInfo chosen = null;
        for (AccessibilityNodeInfo n : nodes) {
            if (n == null) continue;
            String label = labelOf(n).toLowerCase(Locale.US);
            if (isNegativeLabel(label)) {
                n.recycle();
                continue;
            }
            if (chosen == null) {
                chosen = nearestClickable(n);
                if (chosen == null) chosen = AccessibilityNodeInfo.obtain(n);
            }
            n.recycle();
        }
        return chosen;
    }

    @Nullable
    private static AccessibilityNodeInfo findExactLabel(@NonNull AccessibilityNodeInfo root,
                                                        @NonNull String exact) {
        ArrayList<AccessibilityNodeInfo> stack = new ArrayList<>();
        stack.add(root);
        int guard = 0;
        while (!stack.isEmpty() && guard++ < 500) {
            AccessibilityNodeInfo node = stack.remove(stack.size() - 1);
            if (node == null) continue;
            boolean recycleNode = node != root;
            try {
                String label = labelOf(node).toLowerCase(Locale.US).trim();
                if (exact.equals(label) && !isNegativeLabel(label)) {
                    AccessibilityNodeInfo clickable = nearestClickable(node);
                    if (clickable != null) return clickable;
                }
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) stack.add(child);
                }
            } finally {
                if (recycleNode) node.recycle();
            }
        }
        for (AccessibilityNodeInfo left : stack) {
            if (left != null && left != root) left.recycle();
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

    private static boolean looksLikeConsentDialog(@NonNull AccessibilityNodeInfo root) {
        String blob = collectTextBlob(root, 0, new StringBuilder(), 220)
                .toString()
                .toLowerCase(Locale.US);
        if (!blob.isEmpty()) {
            for (String phrase : CONSENT_PHRASES) {
                if (blob.contains(phrase)) return true;
            }
            if (blob.contains("entire screen")
                    && (blob.contains("cast") || blob.contains("cancel"))) {
                return true;
            }
        }
        CharSequence pkgCs = root.getPackageName();
        String pkg = pkgCs != null ? pkgCs.toString() : "";
        boolean systemHost = pkg.contains("systemui")
                || pkg.contains("permissioncontroller")
                || "android".equals(pkg);
        if (!systemHost) return false;
        AccessibilityNodeInfo cast = findExactLabel(root, "cast");
        if (cast != null) {
            cast.recycle();
            return true;
        }
        AccessibilityNodeInfo positive = findBestClickable(root, POSITIVE_LABELS);
        if (positive != null) {
            positive.recycle();
            return true;
        }
        return false;
    }

    @NonNull
    private static StringBuilder collectTextBlob(@NonNull AccessibilityNodeInfo node,
                                                 int depth,
                                                 @NonNull StringBuilder out,
                                                 int maxNodes) {
        if (depth > 18 || maxNodes <= 0) return out;
        append(out, node.getText());
        append(out, node.getContentDescription());
        int remaining = maxNodes - 1;
        int n = node.getChildCount();
        for (int i = 0; i < n && remaining > 0; i++) {
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

    @Nullable
    private static AccessibilityNodeInfo findBestClickable(@NonNull AccessibilityNodeInfo root,
                                                          @NonNull String[] labels) {
        for (String label : labels) {
            AccessibilityNodeInfo found = findClickableMatching(root, label.toLowerCase(Locale.US), 0);
            if (found != null) return found;
        }
        return null;
    }

    @Nullable
    private static AccessibilityNodeInfo findClickableMatching(@NonNull AccessibilityNodeInfo node,
                                                               @NonNull String needle,
                                                               int depth) {
        if (depth > 20) return null;
        String label = labelOf(node).toLowerCase(Locale.US);
        if (labelMatches(label, needle) && !isNegativeLabel(label)) {
            AccessibilityNodeInfo target = nearestClickable(node);
            if (target != null) return target;
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo found = null;
            try {
                found = findClickableMatching(child, needle, depth + 1);
            } finally {
                child.recycle();
            }
            if (found != null) return found;
        }
        return null;
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

    @NonNull
    private static String labelOf(@NonNull AccessibilityNodeInfo node) {
        StringBuilder sb = new StringBuilder();
        append(sb, node.getText());
        append(sb, node.getContentDescription());
        return sb.toString().trim();
    }

    private static boolean labelMatches(@NonNull String label, @NonNull String needle) {
        if (label.isEmpty() || label.length() > 48) return false;
        String l = label.toLowerCase(Locale.US).trim();
        String n = needle.toLowerCase(Locale.US).trim();
        if (l.equals(n)) return true;
        if (n.length() <= 5) return false;
        return l.contains(n);
    }

    private static boolean isNegativeLabel(@NonNull String label) {
        String lower = label.toLowerCase(Locale.US);
        for (String neg : NEGATIVE_LABELS) {
            if (lower.contains(neg)) return true;
        }
        return false;
    }

    private static boolean performClick(@NonNull AccessibilityNodeInfo node) {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
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
}
