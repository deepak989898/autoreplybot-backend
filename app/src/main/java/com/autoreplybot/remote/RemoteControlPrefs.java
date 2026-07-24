package com.autoreplybot.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.autoreplybot.AppConstants;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.UUID;

/**
 * Local remote-control prefs via EncryptedSharedPreferences, with plaintext fallback
 * (same pattern as FacebookPostingSecureStore).
 */
public final class RemoteControlPrefs {
    private static final String KEY_ENABLED = "remote_control_enabled";
    private static final String KEY_DEVICE_ID = "remote_device_id";
    private static final String KEY_DEVICE_DISPLAY_NAME = "remote_device_display_name";
    private static final String KEY_FCM_TOKEN = "remote_fcm_token";

    private final SharedPreferences prefs;

    public RemoteControlPrefs(@NonNull Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences p;
        try {
            MasterKey masterKey = new MasterKey.Builder(app)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            p = EncryptedSharedPreferences.create(
                    app,
                    AppConstants.PREFS_REMOTE_CONTROL,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (GeneralSecurityException | IOException e) {
            p = app.getSharedPreferences(
                    AppConstants.PREFS_REMOTE_CONTROL + "_fallback", Context.MODE_PRIVATE);
        }
        this.prefs = p;
    }

    public boolean isRemoteControlEnabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    public void setRemoteControlEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /** Stable per-install device id; generated once. */
    @NonNull
    public String getOrCreateDeviceId() {
        String existing = prefs.getString(KEY_DEVICE_ID, null);
        if (existing != null && !existing.trim().isEmpty()) {
            return existing.trim();
        }
        String created = UUID.randomUUID().toString();
        prefs.edit().putString(KEY_DEVICE_ID, created).apply();
        return created;
    }

    @NonNull
    public String getDeviceDisplayName() {
        String name = prefs.getString(KEY_DEVICE_DISPLAY_NAME, null);
        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }
        String fallback = Build.MODEL != null ? Build.MODEL : "Android device";
        return fallback;
    }

    public void setDeviceDisplayName(@NonNull String displayName) {
        String trimmed = displayName.trim();
        if (trimmed.isEmpty()) {
            trimmed = Build.MODEL != null ? Build.MODEL : "Android device";
        }
        prefs.edit().putString(KEY_DEVICE_DISPLAY_NAME, trimmed).apply();
    }

    @NonNull
    public String getFcmToken() {
        String token = prefs.getString(KEY_FCM_TOKEN, null);
        return token != null ? token.trim() : "";
    }

    public void setFcmToken(@NonNull String token) {
        prefs.edit().putString(KEY_FCM_TOKEN, token != null ? token.trim() : "").apply();
    }
}
