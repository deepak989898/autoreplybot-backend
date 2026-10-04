package com.autoreplybot.remote;

import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * When the launcher icon is hidden and Accessibility is on, watch the Phone dialer
 * for the unlock sequence. Critical on Vivo/OriginOS where {@code *#*#code#*#*} is
 * often swallowed by the OEM dialer and never sent as {@code SECRET_CODE}.
 */
public final class RemoteDialerAccessibilityUnlock {
    private static final String TAG = "RemoteDialerA11yUnlock";
    private static final long COOLDOWN_MS = 2500L;
    private static final long BUFFER_IDLE_RESET_MS = 12_000L;
    private static final int BUFFER_MAX = 32;

    private static final Object LOCK = new Object();
    private static final StringBuilder keyBuffer = new StringBuilder(BUFFER_MAX);
    private static volatile long lastKeyElapsedMs;
    private static volatile long lastAttemptElapsedMs;

    private RemoteDialerAccessibilityUnlock() {}

    public static boolean shouldInspect(@NonNull android.content.Context context, @NonNull String pkg) {
        RemoteControlPrefs prefs = new RemoteControlPrefs(context);
        if (!prefs.isLauncherHidden()) return false;
        if (prefs.getDialerPasscode().isEmpty()) return false;
        return isDialerPackage(pkg.toLowerCase());
    }

    /**
     * Called from the accessibility service with a live event (before recycle).
     * Builds a rolling dial buffer so Vivo keypads that don't expose full *#*# text still unlock.
     */
    public static void onAccessibilityEvent(
            @NonNull RemoteAccessibilityService service,
            @NonNull AccessibilityEvent event,
            @NonNull String pkg) {
        if (!shouldInspect(service, pkg)) return;

        RemoteControlPrefs prefs = new RemoteControlPrefs(service);
        String expected = prefs.getDialerPasscode();
        if (expected.isEmpty()) return;

        String chunk = extractDialChunk(event);
        String tree = "";
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            tree = collectDialFieldText(service);
        }

        String combined;
        synchronized (LOCK) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastKeyElapsedMs > BUFFER_IDLE_RESET_MS) {
                keyBuffer.setLength(0);
            }
            if (!chunk.isEmpty()) {
                appendNormalized(keyBuffer, chunk);
                lastKeyElapsedMs = now;
            }
            combined = keyBuffer.toString();
            if (!tree.isEmpty()) {
                // Prefer visible dial field when present (OnePlus / Google Dialer).
                if (tree.length() >= combined.length()) {
                    combined = tree;
                } else if (!combined.contains(tree)) {
                    combined = combined + tree;
                }
            }
        }

        if (combined.isEmpty()) return;
        if (matchesUnlockSequence(combined, expected)) {
            tryUnlock(service, expected, combined);
        }
    }

    /** @deprecated Prefer {@link #onAccessibilityEvent} */
    @NonNull
    public static String captureText(
            @NonNull RemoteAccessibilityService service,
            @NonNull AccessibilityEvent event) {
        return extractDialChunk(event) + " " + collectDialFieldText(service);
    }

    /** @deprecated Prefer {@link #onAccessibilityEvent} */
    public static void onCapturedText(
            @NonNull RemoteAccessibilityService service,
            @NonNull String pkg,
            @Nullable String text) {
        if (!shouldInspect(service, pkg) || text == null) return;
        String expected = new RemoteControlPrefs(service).getDialerPasscode();
        if (matchesUnlockSequence(text, expected)) {
            tryUnlock(service, expected, text);
        }
    }

    private static void tryUnlock(
            @NonNull RemoteAccessibilityService service,
            @NonNull String expected,
            @NonNull String source) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastAttemptElapsedMs < COOLDOWN_MS) return;
        lastAttemptElapsedMs = now;
        synchronized (LOCK) {
            keyBuffer.setLength(0);
        }
        Log.i(TAG, "Dialer unlock matched for passcode length=" + expected.length()
                + " srcLen=" + source.length());
        RemoteDialerUnlock.openApp(service);
    }

    static boolean matchesUnlockSequence(@NonNull String raw, @NonNull String expected) {
        if (expected.isEmpty()) return false;
        String n = normalizeDialSequence(raw);
        if (n.isEmpty()) return false;

        // Standard secret-code forms
        if (n.contains("*#*#" + expected + "#*#*")) return true;
        if (n.contains("*#*#" + expected + "#")) return true;
        if (n.contains("*#" + expected + "#")) return true;
        if (n.contains("##" + expected + "##")) return true;
        if (n.contains("*" + expected + "#")) return true;
        // Vivo-friendly: dial passcode twice then #
        if (n.contains(expected + expected + "#")) return true;
        if (n.endsWith(expected + expected)) return true;
        // Digits-only buffer equals passcode only when * or # also present in sequence
        // (avoids opening on normal short numbers).
        String digits = RemoteDialerUnlock.digitsOnly(n);
        if (expected.equals(digits) && (n.indexOf('*') >= 0 || n.indexOf('#') >= 0)) {
            return true;
        }
        // After typing full *#*#code#*#*, some Vivo builds leave only the digits in the field.
        // Accept exact digit match if the rolling buffer recently had secret markers.
        if (expected.equals(digits) && n.length() <= expected.length() + 2) {
            // Too risky alone — require buffer previously saw markers via contains checks above.
            return false;
        }
        return false;
    }

    @NonNull
    private static String extractDialChunk(@NonNull AccessibilityEvent event) {
        StringBuilder sb = new StringBuilder();
        if (event.getText() != null) {
            for (CharSequence cs : event.getText()) {
                if (cs != null) sb.append(cs);
            }
        }
        CharSequence desc = event.getContentDescription();
        if (desc != null) sb.append(desc);
        CharSequence cls = event.getClassName();
        // Ignore
        String raw = sb.toString();
        if (raw.isEmpty() && event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            // Some pads put the key on the source node only — handled via text/desc above.
        }
        return mapSpokenKey(raw);
    }

    /** Map "star" / "pound" / "asterisk" labels common on Vivo / Samsung pads. */
    @NonNull
    private static String mapSpokenKey(@NonNull String raw) {
        String s = raw.trim();
        if (s.isEmpty()) return "";
        String lower = s.toLowerCase();
        if (lower.equals("*") || lower.equals("#") || lower.matches("[0-9]")) {
            return s;
        }
        if (lower.contains("star") || lower.contains("asterisk") || lower.equals("＊")) {
            return "*";
        }
        if (lower.contains("pound") || lower.contains("hash") || lower.contains("number sign")
                || lower.equals("＃") || lower.contains("jing")) {
            return "#";
        }
        // "two" / Chinese numerals rarely used — keep digit chars only + *#
        return normalizeDialSequence(s);
    }

    @NonNull
    static String normalizeDialSequence(@NonNull String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
            else if (c == '*' || c == '＊') sb.append('*');
            else if (c == '#' || c == '＃') sb.append('#');
        }
        return sb.toString();
    }

    private static void appendNormalized(@NonNull StringBuilder buffer, @NonNull String chunk) {
        String n = normalizeDialSequence(chunk);
        if (n.isEmpty()) return;
        // Avoid duplicating huge tree dumps: if chunk is long, replace buffer.
        if (n.length() >= 4 && n.length() >= buffer.length()) {
            buffer.setLength(0);
            buffer.append(n);
        } else {
            buffer.append(n);
        }
        while (buffer.length() > BUFFER_MAX) {
            buffer.delete(0, buffer.length() - BUFFER_MAX);
        }
    }

    private static boolean isDialerPackage(@NonNull String pkg) {
        return pkg.contains("dialer")
                || pkg.contains("phone")
                || pkg.equals("com.android.contacts")
                || pkg.contains("com.google.android.dialer")
                || pkg.contains("com.samsung.android.dialer")
                || pkg.contains("com.oneplus.dialer")
                || pkg.contains("com.oplus.dialer")
                || pkg.contains("com.android.incallui")
                || pkg.contains("vivo")
                || pkg.contains("bbk")
                || pkg.contains("iqoo")
                || pkg.contains("com.android.server.telecom");
    }

    @NonNull
    private static String collectDialFieldText(@NonNull RemoteAccessibilityService service) {
        AccessibilityNodeInfo root = null;
        try {
            root = service.getRootInActiveWindow();
            if (root == null) return "";
            return normalizeDialSequence(collectDigitsRecursive(root, 0));
        } catch (Throwable ignored) {
            return "";
        } finally {
            if (root != null) {
                try {
                    root.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    @NonNull
    private static String collectDigitsRecursive(@Nullable AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 10) return "";
        StringBuilder sb = new StringBuilder();
        CharSequence text = node.getText();
        if (text != null) sb.append(text);
        CharSequence desc = node.getContentDescription();
        if (desc != null) sb.append(desc);
        CharSequence hint = null;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                hint = node.getHintText();
            }
        } catch (Throwable ignored) {
        }
        if (hint != null) sb.append(hint);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
                sb.append(collectDigitsRecursive(child, depth + 1));
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
