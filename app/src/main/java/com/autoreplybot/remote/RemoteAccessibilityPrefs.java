package com.autoreplybot.remote;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.autoreplybot.AppConstants;

import java.io.IOException;
import java.security.GeneralSecurityException;

/** Local prefs for remote accessibility control (defaults: disabled). */
public final class RemoteAccessibilityPrefs {
    public static final long DEFAULT_SESSION_DURATION_MS = 15L * 60L * 1000L;
    public static final long MAX_SESSION_DURATION_MS = 60L * 60L * 1000L;
    public static final long MIN_SESSION_DURATION_MS = 60_000L;

    private static final String PREFS_NAME = AppConstants.PREFS_REMOTE_MODULES + "_accessibility";
    private static final String KEY_ENABLED = "a11y_control_enabled";
    private static final String KEY_SESSION_DURATION_MS = "a11y_session_duration_ms";
    private static final String KEY_REQUIRE_UNLOCK = "a11y_require_unlock";
    private static final String KEY_ACTIVE_SESSION_ID = "a11y_active_session_id";
    private static final String KEY_ACTIVE_SESSION_EXPIRES = "a11y_active_session_expires";
    private static final String KEY_LAST_STATUS_PUBLISH = "a11y_last_status_publish";

    private final SharedPreferences prefs;

    public RemoteAccessibilityPrefs(@NonNull Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences p;
        try {
            MasterKey masterKey = new MasterKey.Builder(app)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            p = EncryptedSharedPreferences.create(
                    app,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (GeneralSecurityException | IOException e) {
            p = app.getSharedPreferences(PREFS_NAME + "_fallback", Context.MODE_PRIVATE);
        }
        this.prefs = p;
    }

    public boolean isAccessibilityControlEnabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    public void setAccessibilityControlEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
        if (!enabled) {
            clearActiveSession();
        }
    }

    public long getSessionDurationMs() {
        return prefs.getLong(KEY_SESSION_DURATION_MS, DEFAULT_SESSION_DURATION_MS);
    }

    public void setSessionDurationMs(long ms) {
        long clamped = Math.max(MIN_SESSION_DURATION_MS, Math.min(MAX_SESSION_DURATION_MS, ms));
        prefs.edit().putLong(KEY_SESSION_DURATION_MS, clamped).apply();
    }

    public boolean isRequireUnlock() {
        return prefs.getBoolean(KEY_REQUIRE_UNLOCK, true);
    }

    public void setRequireUnlock(boolean require) {
        prefs.edit().putBoolean(KEY_REQUIRE_UNLOCK, require).apply();
    }

    @NonNull
    public String getActiveSessionId() {
        String id = prefs.getString(KEY_ACTIVE_SESSION_ID, null);
        return id != null ? id : "";
    }

    public long getActiveSessionExpiresAt() {
        return prefs.getLong(KEY_ACTIVE_SESSION_EXPIRES, 0L);
    }

    public void setActiveSession(@NonNull String sessionId, long expiresAtMs) {
        prefs.edit()
                .putString(KEY_ACTIVE_SESSION_ID, sessionId)
                .putLong(KEY_ACTIVE_SESSION_EXPIRES, expiresAtMs)
                .apply();
    }

    public void clearActiveSession() {
        prefs.edit()
                .remove(KEY_ACTIVE_SESSION_ID)
                .remove(KEY_ACTIVE_SESSION_EXPIRES)
                .apply();
    }

    public boolean hasActiveSession() {
        String id = prefs.getString(KEY_ACTIVE_SESSION_ID, null);
        if (id == null || id.isEmpty()) return false;
        long expires = prefs.getLong(KEY_ACTIVE_SESSION_EXPIRES, 0L);
        return expires <= 0 || System.currentTimeMillis() < expires;
    }

    public long getLastStatusPublishAt() {
        return prefs.getLong(KEY_LAST_STATUS_PUBLISH, 0L);
    }

    public void setLastStatusPublishAt(long at) {
        prefs.edit().putLong(KEY_LAST_STATUS_PUBLISH, at).apply();
    }
}
