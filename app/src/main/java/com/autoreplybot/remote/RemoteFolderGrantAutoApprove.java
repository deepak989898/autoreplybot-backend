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
 * Arms accessibility to confirm the system folder picker for internal storage
 * (USE THIS FOLDER / Allow).
 */
public final class RemoteFolderGrantAutoApprove {
    private static final String TAG = "RemoteFolderApprove";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object ARM_TOKEN = new Object();

    private static final AtomicLong ARMED_UNTIL_MS = new AtomicLong(0L);
    private static final AtomicBoolean CLICKED = new AtomicBoolean(false);

    private static final String[] CONFIRM_LABELS = {
            "use this folder",
            "allow",
            "select",
            "open",
            "इस फ़ोल्डर का उपयोग करें",
            "इस फोल्डर का उपयोग करें",
            "अनुमति दें",
            "चुनें",
            "ठीक है",
            "ok"
    };

    private RemoteFolderGrantAutoApprove() {}

    public static void arm(long ttlMs) {
        CLICKED.set(false);
        ARMED_UNTIL_MS.set(System.currentTimeMillis() + Math.max(1000L, ttlMs));
        Log.i(TAG, "armed for " + ttlMs + "ms");
        MAIN.removeCallbacksAndMessages(ARM_TOKEN);
        long[] delays = {100L, 250L, 500L, 900L, 1500L, 2500L, 4000L, 6000L, 9000L, 12000L};
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
                    if (!looksLikeFolderPicker(root)) continue;
                    AccessibilityNodeInfo confirm = findConfirmButton(root);
                    if (confirm == null) continue;
                    try {
                        if (confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            CLICKED.set(true);
                            disarm();
                            Log.i(TAG, "auto-approved folder picker");
                            return true;
                        }
                        Rect bounds = new Rect();
                        confirm.getBoundsInScreen(bounds);
                        if (!bounds.isEmpty()
                                && service instanceof RemoteAccessibilityService) {
                            RemoteAccessibilityGestureExecutor gestures =
                                    ((RemoteAccessibilityService) service).getGestureExecutor();
                            if (gestures != null && !gestures.isBusy()) {
                                gestures.tap(bounds.exactCenterX(), bounds.exactCenterY(),
                                        (success, code) -> {
                                            if (success) {
                                                CLICKED.set(true);
                                                disarm();
                                                Log.i(TAG, "auto-approved folder picker via gesture");
                                            }
                                        });
                                return true;
                            }
                        }
                    } finally {
                        confirm.recycle();
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

    private static boolean looksLikeFolderPicker(@NonNull AccessibilityNodeInfo root) {
        String text = collectText(root).toLowerCase(Locale.US);
        return text.contains("folder")
                || text.contains("storage")
                || text.contains("documents")
                || text.contains("फ़ोल्डर")
                || text.contains("फोल्डर")
                || text.contains("संग्रहण");
    }

    @Nullable
    private static AccessibilityNodeInfo findConfirmButton(@NonNull AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> buttons = new ArrayList<>();
        collectClickable(root, buttons);
        AccessibilityNodeInfo best = null;
        int bestScore = -1;
        for (AccessibilityNodeInfo node : buttons) {
            String label = nodeText(node).toLowerCase(Locale.US);
            if (label.isEmpty()) continue;
            if (label.contains("cancel") || label.contains("रद्द")) continue;
            int score = scoreLabel(label);
            if (score > bestScore) {
                bestScore = score;
                if (best != null) best.recycle();
                best = AccessibilityNodeInfo.obtain(node);
            }
        }
        for (AccessibilityNodeInfo node : buttons) node.recycle();
        return bestScore >= 0 ? best : null;
    }

    private static int scoreLabel(@NonNull String label) {
        int score = 0;
        for (String phrase : CONFIRM_LABELS) {
            if (label.contains(phrase)) score += 10;
        }
        if (label.contains("use this")) score += 20;
        if (label.contains("allow")) score += 8;
        return score;
    }

    private static void collectClickable(@NonNull AccessibilityNodeInfo node,
                                         @NonNull List<AccessibilityNodeInfo> out) {
        if (node.isClickable() && node.isEnabled() && node.isVisibleToUser()) {
            out.add(AccessibilityNodeInfo.obtain(node));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectClickable(child, out);
                child.recycle();
            }
        }
    }

    @NonNull
    private static String collectText(@NonNull AccessibilityNodeInfo node) {
        StringBuilder sb = new StringBuilder();
        appendText(node, sb);
        return sb.toString();
    }

    private static void appendText(@NonNull AccessibilityNodeInfo node, @NonNull StringBuilder sb) {
        CharSequence t = node.getText();
        if (t != null && t.length() > 0) sb.append(t).append(' ');
        CharSequence d = node.getContentDescription();
        if (d != null && d.length() > 0) sb.append(d).append(' ');
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                appendText(child, sb);
                child.recycle();
            }
        }
    }

    @NonNull
    private static String nodeText(@NonNull AccessibilityNodeInfo node) {
        CharSequence t = node.getText();
        if (t != null && t.length() > 0) return t.toString();
        CharSequence d = node.getContentDescription();
        return d != null ? d.toString() : "";
    }

    @NonNull
    private static List<AccessibilityNodeInfo> collectRoots(@NonNull AccessibilityService service) {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null) continue;
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root != null) roots.add(root);
                }
            }
        }
        AccessibilityNodeInfo active = service.getRootInActiveWindow();
        if (active != null) roots.add(active);
        return roots;
    }
}
