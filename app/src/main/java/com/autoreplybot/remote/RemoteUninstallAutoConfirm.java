package com.autoreplybot.remote;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
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
 * While a website {@code UNINSTALL_APP} is pending, click the system confirmation
 * dialog {@code OK} (not the App Info "Uninstall" row behind it).
 */
public final class RemoteUninstallAutoConfirm {
    private static final String TAG = "RemoteUninstallConfirm";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object ARM_TOKEN = new Object();

    private static final AtomicLong ARMED_UNTIL_MS = new AtomicLong(0L);
    private static final AtomicBoolean DONE = new AtomicBoolean(false);

    private static final String[] CONFIRM_PHRASES = {
            "do you want to uninstall",
            "uninstall this app",
            "want to uninstall",
            "uninstall app?",
            "uninstall this application",
            "remove this app",
            "delete this app",
            "remove app",
            "क्या आप अनइंस्टॉल",
            "अनइंस्टॉल करना चाहते",
            "uninstall auto reply",
            "uninstall autoreplybot"
    };

    private static final String[] INSTALLER_PACKAGES = {
            "packageinstaller",
            "permissioncontroller",
            "oplus",
            "coloros",
            "safecenter",
            "securitycenter"
    };

    private static final String[] ADMIN_DISABLE_PHRASES = {
            "deactivate this device admin",
            "deactivate device admin",
            "disable this device admin",
            "turn off device admin",
            "device admin app",
            "डिवाइस एडमिन"
    };

    private static final String[] OK_LABELS = {
            "ok",
            "okay",
            "yes",
            "confirm",
            "uninstall",
            "delete",
            "remove",
            "ठीक है",
            "हाँ",
            "हां",
            "अनइंस्टॉल",
            "हटाएँ",
            "हटाएं"
    };

    private static final String[] NEGATIVE_LABELS = {
            "cancel",
            "dismiss",
            "keep",
            "force stop",
            "रद्द करें",
            "नहीं"
    };

    private RemoteUninstallAutoConfirm() {}

    public static void arm(long ttlMs) {
        DONE.set(false);
        ARMED_UNTIL_MS.set(System.currentTimeMillis() + Math.max(1000L, ttlMs));
        Log.i(TAG, "armed for " + ttlMs + "ms");
        schedulePolls();
    }

    /** Re-arm from prefs pending window (survives process timing). */
    public static void armFromPending(@NonNull android.content.Context context) {
        RemoteModulePrefs prefs = new RemoteModulePrefs(context);
        if (!prefs.isPendingSelfUninstall()) return;
        long left = prefs.getPendingSelfUninstallUntil() - System.currentTimeMillis();
        if (left < 1000L) return;
        if (!isArmed()) {
            arm(left);
        }
    }

    private static void schedulePolls() {
        MAIN.removeCallbacksAndMessages(ARM_TOKEN);
        long[] delays = {
                100L, 250L, 450L, 700L, 1000L, 1500L, 2200L, 3200L, 4500L,
                6000L, 8000L, 11000L, 15000L, 20000L, 28000L, 40000L, 55000L,
                75000L, 100000L, 140000L, 180000L
        };
        for (long d : delays) {
            MAIN.postAtTime(() -> {
                RemoteAccessibilityService svc = RemoteAccessibilityService.getInstance();
                if (svc != null) {
                    tryClick(svc);
                }
            }, ARM_TOKEN, SystemClock.uptimeMillis() + d);
        }
    }

    public static void disarm() {
        ARMED_UNTIL_MS.set(0L);
        DONE.set(false);
        MAIN.removeCallbacksAndMessages(ARM_TOKEN);
    }

    public static boolean isArmed() {
        if (DONE.get()) return false;
        return System.currentTimeMillis() < ARMED_UNTIL_MS.get();
    }

    public static boolean tryClick(@NonNull AccessibilityService service) {
        return tryClick(service, null);
    }

    public static boolean tryClick(@NonNull AccessibilityService service,
                                   @Nullable AccessibilityEvent event) {
        armFromPending(service);
        if (!isArmed() && !new RemoteModulePrefs(service).isPendingSelfUninstall()) {
            return false;
        }
        // Keep in-memory arm aligned with prefs.
        if (!isArmed() && new RemoteModulePrefs(service).isPendingSelfUninstall()) {
            armFromPending(service);
        }
        if (!isArmed()) return false;

        String eventPkg = "";
        if (event != null && event.getPackageName() != null) {
            eventPkg = event.getPackageName().toString().toLowerCase(Locale.US);
        }

        try {
            String ourPkg = service.getPackageName().toLowerCase(Locale.US);
            String appLabel = safeLabel(service).toLowerCase(Locale.US);
            List<AccessibilityNodeInfo> roots = collectRoots(service, true);
            AccessibilityNodeInfo eventRoot = null;
            if (event != null) {
                try {
                    eventRoot = event.getSource();
                } catch (Throwable ignored) {
                }
                if (eventRoot != null) {
                    roots.add(0, eventRoot);
                }
            }
            try {
                // Pass 0: Device Admin deactivate dialog (OnePlus / ColorOS) before uninstall confirm.
                for (AccessibilityNodeInfo root : roots) {
                    if (root == null) continue;
                    String hay = walk(root, 0).toLowerCase(Locale.US);
                    if (!isAdminDisableDialog(hay)) continue;
                    if (clickConfirm(service, root, hay, false)) {
                        Log.i(TAG, "clicked device-admin deactivate confirm");
                        return false;
                    }
                }

                // Pass 1: package installer / system dialog — no app-name requirement while pending.
                for (AccessibilityNodeInfo root : roots) {
                    if (root == null) continue;
                    String pkg = packageOf(root);
                    boolean installerContext = isInstallerPackage(pkg)
                            || (!eventPkg.isEmpty() && isInstallerPackage(eventPkg));
                    if (!installerContext) continue;
                    String hay = walk(root, 0).toLowerCase(Locale.US);
                    if (isOurBridgeWindow(pkg, hay, ourPkg)) continue;
                    if (!isConfirmDialog(hay) && !looksLikeUninstallChooser(hay)) continue;
                    if (clickConfirm(service, root, hay, true)) {
                        return true;
                    }
                }

                // Pass 2: confirmation dialog that mentions our app.
                for (AccessibilityNodeInfo root : roots) {
                    if (root == null) continue;
                    String hay = walk(root, 0).toLowerCase(Locale.US);
                    if (!mentionsUs(hay, ourPkg, appLabel)) continue;
                    if (!isConfirmDialog(hay)) continue;
                    if (clickConfirm(service, root, hay, true)) {
                        return true;
                    }
                }

                // Pass 3: any confirmation dialog while pending (OEM text may omit app name).
                for (AccessibilityNodeInfo root : roots) {
                    if (root == null) continue;
                    String pkg = packageOf(root);
                    if (isOurBridgeWindow(pkg, walk(root, 0).toLowerCase(Locale.US), ourPkg)) {
                        continue;
                    }
                    String hay = walk(root, 0).toLowerCase(Locale.US);
                    if (!isConfirmDialog(hay) && !looksLikeUninstallChooser(hay)) continue;
                    if (clickConfirm(service, root, hay, true)) {
                        return true;
                    }
                }

                // Pass 4: App Info / installer page → click Uninstall to open dialog.
                for (AccessibilityNodeInfo root : roots) {
                    if (root == null) continue;
                    String rootPkg = packageOf(root);
                    String hay = walk(root, 0).toLowerCase(Locale.US);
                    if (!mentionsUs(hay, ourPkg, appLabel)
                            && !rootPkg.contains("settings")
                            && !isInstallerPackage(rootPkg)) {
                        continue;
                    }
                    if (isConfirmDialog(hay)) continue;
                    if (!hay.contains("uninstall") && !hay.contains("अनइंस्टॉल")) continue;

                    AccessibilityNodeInfo uninstall = findUninstallAction(root);
                    if (uninstall == null) continue;
                    try {
                        if (performClickOrGesture(service, uninstall)) {
                            Log.i(TAG, "clicked Uninstall action to open dialog");
                            return false;
                        }
                    } finally {
                        uninstall.recycle();
                    }
                }
            } finally {
                for (AccessibilityNodeInfo n : roots) {
                    if (n == null || n == eventRoot) continue;
                    try {
                        n.recycle();
                    } catch (Throwable ignored) {
                    }
                }
                if (eventRoot != null) {
                    try {
                        eventRoot.recycle();
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "tryClick failed", t);
        }
        return false;
    }

    private static boolean clickConfirm(@NonNull AccessibilityService service,
                                        @NonNull AccessibilityNodeInfo root,
                                        @NonNull String hay,
                                        boolean completeOnSuccess) {
        AccessibilityNodeInfo ok = findConfirmButton(root);
        if (ok == null) return false;
        try {
            String label = labelOf(ok);
            if (isNegativeLabel(label)) return false;
            if (performClickOrGesture(service, ok)) {
                if (completeOnSuccess) {
                    DONE.set(true);
                    ARMED_UNTIL_MS.set(0L);
                    new RemoteModulePrefs(service).clearPendingSelfUninstall();
                    RemoteSelfUninstallController.onUninstallConfirmed(service);
                    Log.i(TAG, "clicked confirm: " + label);
                }
                return true;
            }
        } finally {
            ok.recycle();
        }
        return false;
    }

    private static boolean looksLikeUninstallChooser(@NonNull String hay) {
        boolean hasUninstall = hay.contains("uninstall") || hay.contains("अनइंस्टॉल");
        boolean hasCancel = hay.contains("cancel") || hay.contains("रद्द");
        boolean hasOk = hay.contains(" ok") || hay.contains("okay") || hay.contains("ठीक");
        return hasUninstall && (hasCancel || hasOk);
    }

    private static boolean isOurBridgeWindow(@NonNull String pkg,
                                               @NonNull String hay,
                                               @NonNull String ourPkg) {
        if (!pkg.contains(ourPkg)) return false;
        return !hay.contains("uninstall")
                && !hay.contains("अनइंस्टॉल")
                && !isConfirmDialog(hay)
                && !isAdminDisableDialog(hay);
    }

    @NonNull
    private static String packageOf(@NonNull AccessibilityNodeInfo root) {
        CharSequence pkgCs = root.getPackageName();
        return pkgCs != null ? pkgCs.toString().toLowerCase(Locale.US) : "";
    }

    private static boolean isInstallerPackage(@NonNull String pkg) {
        if (pkg.isEmpty()) return false;
        for (String hint : INSTALLER_PACKAGES) {
            if (pkg.contains(hint)) return true;
        }
        return "android".equals(pkg);
    }

    private static boolean mentionsUs(
            @NonNull String hay, @NonNull String ourPkg, @NonNull String appLabel) {
        if (hay.contains(ourPkg)) return true;
        if (!appLabel.isEmpty() && hay.contains(appLabel)) return true;
        if (hay.contains("autoreplybot")) return true;
        if (hay.contains("auto reply bot")) return true;
        // OEM label variants: "Auto reply Bot"
        return hay.contains("auto reply") && hay.contains("bot");
    }

    private static boolean isConfirmDialog(@NonNull String hay) {
        for (String p : CONFIRM_PHRASES) {
            if (hay.contains(p)) return true;
        }
        return hay.contains("do you want to uninstall")
                || (hay.contains("uninstall this app") && (hay.contains("ok") || hay.contains("cancel")));
    }

    private static boolean isAdminDisableDialog(@NonNull String hay) {
        for (String p : ADMIN_DISABLE_PHRASES) {
            if (hay.contains(p)) return true;
        }
        return false;
    }

    @Nullable
    private static AccessibilityNodeInfo findConfirmButton(@NonNull AccessibilityNodeInfo root) {
        // Standard AlertDialog buttons — try both; OEMs swap order.
        for (String viewId : new String[]{"android:id/button1", "android:id/button2", "android:id/button3"}) {
            AccessibilityNodeInfo byId = findByViewId(root, viewId);
            if (byId == null) continue;
            String label = labelOf(byId).toLowerCase(Locale.US);
            if (isNegativeLabel(label)) {
                byId.recycle();
                continue;
            }
            if (!label.isEmpty() || viewId.contains("button1")) {
                return byId;
            }
            byId.recycle();
        }

        // Exact OK text (this OEM dialog).
        for (String text : new String[]{
                "OK", "Ok", "ok", "ठीक है", "Uninstall", "UNINSTALL", "अनइंस्टॉल", "Deactivate", "DEACTIVATE"
        }) {
            AccessibilityNodeInfo n = findByTextExactOrContains(root, text, true);
            if (n != null) {
                if (isNegativeLabel(labelOf(n))) {
                    n.recycle();
                    continue;
                }
                return n;
            }
        }

        return findBestClickable(root, OK_LABELS);
    }

    @Nullable
    private static AccessibilityNodeInfo findUninstallAction(@NonNull AccessibilityNodeInfo root) {
        for (String text : new String[]{"Uninstall", "UNINSTALL", "अनइंस्टॉल"}) {
            AccessibilityNodeInfo n = findByTextExactOrContains(root, text, false);
            if (n != null) {
                String label = labelOf(n).toLowerCase(Locale.US);
                // Prefer the action button, not long descriptive text.
                if (label.equals("uninstall") || label.contains("uninstall")
                        || label.contains("अनइंस्टॉल")) {
                    if (!label.contains("do you want") && !isNegativeLabel(label)) {
                        return n;
                    }
                }
                n.recycle();
            }
        }
        return null;
    }

    @Nullable
    private static AccessibilityNodeInfo findByViewId(
            @NonNull AccessibilityNodeInfo root, @NonNull String viewId) {
        List<AccessibilityNodeInfo> nodes = null;
        try {
            nodes = root.findAccessibilityNodeInfosByViewId(viewId);
        } catch (Throwable ignored) {
        }
        if (nodes == null || nodes.isEmpty()) return null;
        AccessibilityNodeInfo chosen = null;
        for (AccessibilityNodeInfo n : nodes) {
            if (n == null) continue;
            if (chosen == null) {
                chosen = nearestClickable(n);
                if (chosen != n) n.recycle();
            } else {
                n.recycle();
            }
        }
        return chosen;
    }

    @Nullable
    private static AccessibilityNodeInfo findByTextExactOrContains(
            @NonNull AccessibilityNodeInfo root, @NonNull String text, boolean preferExactOk) {
        List<AccessibilityNodeInfo> nodes = null;
        try {
            nodes = root.findAccessibilityNodeInfosByText(text);
        } catch (Throwable ignored) {
        }
        if (nodes == null || nodes.isEmpty()) return null;
        AccessibilityNodeInfo best = null;
        int bestScore = -1;
        String want = text.toLowerCase(Locale.US);
        for (AccessibilityNodeInfo n : nodes) {
            if (n == null) continue;
            String label = labelOf(n).toLowerCase(Locale.US).trim();
            if (label.isEmpty()) {
                n.recycle();
                continue;
            }
            if (isNegativeLabel(label)) {
                n.recycle();
                continue;
            }
            int score = 0;
            if (label.equals(want)) score = 100;
            else if (label.equals("ok") || label.equals("okay")) score = 95;
            else if (label.contains(want)) score = 40;
            else {
                n.recycle();
                continue;
            }
            if (preferExactOk && (label.equals("ok") || label.equals("okay"))) score += 20;
            // Prefer shorter labels (button) over long paragraphs.
            score += Math.max(0, 20 - label.length());
            AccessibilityNodeInfo clickable = nearestClickable(n);
            n.recycle();
            if (clickable == null) continue;
            if (score > bestScore) {
                if (best != null) best.recycle();
                best = clickable;
                bestScore = score;
            } else {
                clickable.recycle();
            }
        }
        return best;
    }

    @Nullable
    private static AccessibilityNodeInfo findBestClickable(
            @NonNull AccessibilityNodeInfo root, @NonNull String[] labels) {
        AccessibilityNodeInfo best = null;
        int bestScore = -1;
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        collectAll(root, all, 0);
        try {
            for (AccessibilityNodeInfo n : all) {
                String label = labelOf(n).toLowerCase(Locale.US).trim();
                if (label.isEmpty() || isNegativeLabel(label)) continue;
                for (int i = 0; i < labels.length; i++) {
                    String want = labels[i];
                    if (!(label.equals(want) || label.contains(want))) continue;
                    int score = 500 - i;
                    if (label.equals(want)) score += 80;
                    // Strongly prefer OK over Uninstall when both exist.
                    if (label.equals("ok") || label.equals("okay")) score += 200;
                    if (score > bestScore) {
                        AccessibilityNodeInfo clickable = nearestClickable(n);
                        if (clickable == null) continue;
                        if (best != null) best.recycle();
                        best = clickable;
                        bestScore = score;
                    }
                    break;
                }
            }
        } finally {
            for (AccessibilityNodeInfo n : all) {
                try {
                    n.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
        return best;
    }

    private static void collectAll(
            @Nullable AccessibilityNodeInfo node,
            @NonNull List<AccessibilityNodeInfo> out,
            int depth) {
        if (node == null || depth > 18) return;
        out.add(AccessibilityNodeInfo.obtain(node));
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
                collectAll(child, out, depth + 1);
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
    }

    @Nullable
    private static AccessibilityNodeInfo nearestClickable(@NonNull AccessibilityNodeInfo node) {
        if (node.isClickable() || node.isCheckable()) {
            return AccessibilityNodeInfo.obtain(node);
        }
        AccessibilityNodeInfo parent = node.getParent();
        int hops = 0;
        while (parent != null && hops < 8) {
            if (parent.isClickable() || parent.isCheckable()) {
                AccessibilityNodeInfo copy = AccessibilityNodeInfo.obtain(parent);
                parent.recycle();
                return copy;
            }
            AccessibilityNodeInfo next = parent.getParent();
            parent.recycle();
            parent = next;
            hops++;
        }
        if (parent != null) parent.recycle();
        return AccessibilityNodeInfo.obtain(node);
    }

    private static boolean performClickOrGesture(@NonNull AccessibilityService service,
                                                 @NonNull AccessibilityNodeInfo node) {
        if (performClick(node)) return true;
        RemoteAccessibilityGestureExecutor gesture = null;
        if (service instanceof RemoteAccessibilityService) {
            gesture = ((RemoteAccessibilityService) service).getGestureExecutor();
        }
        if (gesture == null || gesture.isBusy()) return false;
        Rect bounds = new Rect();
        try {
            node.getBoundsInScreen(bounds);
        } catch (Throwable ignored) {
        }
        if (bounds.isEmpty() || bounds.width() <= 0 || bounds.height() <= 0) return false;
        final float x = bounds.exactCenterX();
        final float y = bounds.exactCenterY();
        gesture.tap(x, y, (success, errorCode) -> {
            if (!success) return;
            Log.i(TAG, "gesture tap confirm at " + x + "," + y);
            DONE.set(true);
            ARMED_UNTIL_MS.set(0L);
            new RemoteModulePrefs(service).clearPendingSelfUninstall();
            RemoteSelfUninstallController.onUninstallConfirmed(service);
        });
        return false;
    }

    private static boolean performClick(@NonNull AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = AccessibilityNodeInfo.obtain(node);
        try {
            for (int i = 0; i < 8; i++) {
                if (cur.isClickable() || cur.isCheckable()) {
                    boolean ok = cur.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    if (ok) return true;
                }
                AccessibilityNodeInfo parent = cur.getParent();
                if (parent == null) break;
                cur.recycle();
                cur = parent;
            }
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } finally {
            try {
                cur.recycle();
            } catch (Throwable ignored) {
            }
        }
    }

    @NonNull
    private static String safeLabel(@NonNull AccessibilityService service) {
        try {
            CharSequence label = service.getApplicationInfo().loadLabel(service.getPackageManager());
            return label != null ? label.toString() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    @NonNull
    private static List<AccessibilityNodeInfo> collectRoots(@NonNull AccessibilityService service,
                                                            boolean preferSystemDialogs) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null) {
                List<AccessibilityWindowInfo> ordered = new ArrayList<>(windows);
                ordered.sort((a, b) -> {
                    int ta = a != null ? uninstallWindowRank(a.getType(), preferSystemDialogs) : -1;
                    int tb = b != null ? uninstallWindowRank(b.getType(), preferSystemDialogs) : -1;
                    return Integer.compare(tb, ta);
                });
                for (AccessibilityWindowInfo w : ordered) {
                    if (w == null) continue;
                    try {
                        AccessibilityNodeInfo r = w.getRoot();
                        if (r != null) out.add(r);
                    } finally {
                        try {
                            w.recycle();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (out.isEmpty()) {
            try {
                AccessibilityNodeInfo active = service.getRootInActiveWindow();
                if (active != null) out.add(active);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static int uninstallWindowRank(int type, boolean preferSystemDialogs) {
        if (!preferSystemDialogs) {
            return typeRank(type);
        }
        if (type == AccessibilityWindowInfo.TYPE_SYSTEM) return 5;
        if (type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return 4;
        if (type == AccessibilityWindowInfo.TYPE_APPLICATION) return 3;
        return 1;
    }

    @NonNull
    private static List<AccessibilityNodeInfo> collectRoots(@NonNull AccessibilityService service) {
        return collectRoots(service, false);
    }

    private static int typeRank(int type) {
        // Higher = prefer first when sorting descending.
        if (type == AccessibilityWindowInfo.TYPE_APPLICATION) return 3;
        if (type == AccessibilityWindowInfo.TYPE_SYSTEM) return 2;
        if (type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return 1;
        return 0;
    }

    @NonNull
    private static String labelOf(@NonNull AccessibilityNodeInfo node) {
        CharSequence t = node.getText();
        if (t != null && t.length() > 0) return t.toString().trim();
        CharSequence d = node.getContentDescription();
        if (d != null && d.length() > 0) return d.toString().trim();
        return "";
    }

    private static boolean isNegativeLabel(@NonNull String label) {
        String l = label.toLowerCase(Locale.US).trim();
        if (l.isEmpty()) return false;
        for (String n : NEGATIVE_LABELS) {
            if (l.equals(n) || l.contains(n)) return true;
        }
        // Bare "no" only — do not treat "uninstall" as negative via contains("no").
        return l.equals("no");
    }

    @NonNull
    private static String walk(@Nullable AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 16) return "";
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
