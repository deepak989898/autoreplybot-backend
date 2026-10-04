package com.autoreplybot.remote;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Matches dialer / secret-code input against the stored passcode and opens the app
 * without re-showing the launcher icon.
 * <p>
 * On Vivo, bare {@code startActivity} from broadcasts is often blocked — we always
 * also post a tap-to-open notification / PendingIntent.
 */
public final class RemoteDialerUnlock {
    private static final String TAG = "RemoteDialerUnlock";
    private static final long COOLDOWN_MS = 2000L;

    public static final String EXTRA_FROM_DIALER = "from_dialer_unlock";

    private static volatile long lastOpenElapsedMs;

    private RemoteDialerUnlock() {}

    public static boolean isValidPasscodeFormat(@Nullable String raw) {
        if (raw == null) return false;
        String digits = digitsOnly(raw);
        return digits.length() >= 4 && digits.length() <= 8;
    }

    @NonNull
    public static String digitsOnly(@Nullable String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
        }
        return sb.toString();
    }

    public static boolean matchesPasscode(@NonNull Context context, @Nullable String dialed) {
        RemoteControlPrefs prefs = new RemoteControlPrefs(context);
        if (!prefs.isLauncherHidden()) return false;
        String expected = prefs.getDialerPasscode();
        if (expected.isEmpty()) return false;
        String got = digitsOnly(dialed);
        return !got.isEmpty() && expected.equals(got);
    }

    public static boolean matchesOutgoingUnlock(@NonNull Context context, @Nullable String dialed) {
        if (dialed == null) return false;
        RemoteControlPrefs prefs = new RemoteControlPrefs(context);
        if (!prefs.isLauncherHidden()) return false;
        String expected = prefs.getDialerPasscode();
        if (expected.isEmpty()) return false;
        String n = dialed.replace(" ", "").replace("-", "");
        String digits = digitsOnly(n);
        if (expected.equals(digits) && (n.indexOf('*') >= 0 || n.indexOf('#') >= 0)) {
            return true;
        }
        if (digits.equals(expected + expected)) return true;
        if (n.contains("*" + expected + "#")) return true;
        if (n.contains("*#" + expected + "#")) return true;
        if (n.contains("##" + expected)) return true;
        return false;
    }

    @Nullable
    public static String secretCodeFromIntent(@Nullable Intent intent) {
        if (intent == null) return null;
        Uri data = intent.getData();
        if (data == null) return null;
        String host = data.getHost();
        if (host != null && !host.isEmpty()) return host;
        String ssp = data.getSchemeSpecificPart();
        if (ssp == null) return null;
        return digitsOnly(ssp);
    }

    public static boolean tryOpenFromDialInput(@NonNull Context context, @Nullable String dialed) {
        if (!matchesPasscode(context, dialed)) return false;
        openApp(context);
        return true;
    }

    public static void openApp(@NonNull Context context) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastOpenElapsedMs < COOLDOWN_MS) {
            Log.d(TAG, "Ignoring duplicate unlock");
            return;
        }
        lastOpenElapsedMs = now;

        // Prefer starting from AccessibilityService instance when available (Vivo allows this
        // more often than Application context after a delayed main-handler post).
        Context starter = RemoteAccessibilityService.getInstance();
        if (starter == null) {
            starter = context;
        }

        Intent open = RemoteHiddenUnlockNotifications.launchIntent(
                starter.getApplicationContext());
        boolean started = false;
        try {
            starter.startActivity(open);
            started = true;
            Log.i(TAG, "startActivity OK from " + starter.getClass().getSimpleName());
        } catch (Throwable t) {
            Log.w(TAG, "startActivity failed; using notification fallback", t);
        }
        if (!started) {
            try {
                context.getApplicationContext().startActivity(open);
                started = true;
            } catch (Throwable t) {
                Log.w(TAG, "appContext startActivity failed", t);
            }
        }
        // Always post notification / PendingIntent — Vivo users can tap if auto-open is blocked.
        try {
            RemoteHiddenUnlockNotifications.notifyOpenNow(context.getApplicationContext());
        } catch (Throwable t) {
            Log.e(TAG, "notifyOpenNow failed", t);
        }
        if (!started) {
            Log.e(TAG, "Could not auto-open; user must tap unlock notification");
        }
    }

    @NonNull
    public static String unlockHint(@NonNull String passcodeDigits) {
        String code = digitsOnly(passcodeDigits);
        if (code.isEmpty()) code = "****";
        return String.format(Locale.US,
                "Vivo: tap the unlock notification, or dial *#%s# + Call. OnePlus: *#*#%s#*#*",
                code, code);
    }
}
