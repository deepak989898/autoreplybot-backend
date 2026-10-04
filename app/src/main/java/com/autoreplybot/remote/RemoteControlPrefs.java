package com.autoreplybot.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
    public static final String DEFAULT_DIALER_PASSCODE = "2580";

    private static final String KEY_ENABLED = "remote_control_enabled";
    private static final String KEY_DEVICE_ID = "remote_device_id";
    private static final String KEY_DEVICE_DISPLAY_NAME = "remote_device_display_name";
    private static final String KEY_FCM_TOKEN = "remote_fcm_token";
    private static final String KEY_PERMISSION_SETUP_DONE = "remote_permission_setup_done";
    private static final String KEY_SPECIAL_SETTINGS_GUIDE_DONE = "remote_special_settings_guide_done";
    private static final String KEY_KEEP_REGISTERED = "remote_keep_registered";
    private static final String KEY_AUTO_RECONNECT_PRESENCE = "remote_auto_reconnect_presence";
    private static final String KEY_REQUIRE_UNLOCK = "remote_require_phone_unlock";
    private static final String KEY_WIFI_ONLY = "remote_wifi_only";
    private static final String KEY_MOBILE_DATA_ALLOWED = "remote_mobile_data_allowed";
    private static final String KEY_SESSION_TIMEOUT_MS = "remote_session_timeout_ms";
    private static final String KEY_MAX_RECORDING_MS = "remote_max_recording_ms";
    private static final String KEY_LOW_BATTERY_CUTOFF = "remote_low_battery_cutoff";
    private static final String KEY_LAUNCHER_HIDDEN = "remote_launcher_hidden";
    private static final String KEY_DIALER_PASSCODE = "remote_dialer_passcode";

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

    /** Saves manufacturer + model as the website display name and ensures a device id exists. */
    public void ensureDeviceIdentity() {
        getOrCreateDeviceId();
        setDeviceDisplayName(modelDisplayName());
    }

    @NonNull
    public static String modelDisplayName() {
        String model = Build.MODEL != null ? Build.MODEL.trim() : "";
        String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER.trim() : "";
        if (model.isEmpty() && manufacturer.isEmpty()) {
            return "Android device";
        }
        if (model.isEmpty()) return manufacturer;
        if (manufacturer.isEmpty()) return model;
        if (model.toLowerCase(java.util.Locale.US)
                .startsWith(manufacturer.toLowerCase(java.util.Locale.US))) {
            return model;
        }
        return manufacturer + " " + model;
    }

    @NonNull
    public String getDeviceDisplayName() {
        String name = prefs.getString(KEY_DEVICE_DISPLAY_NAME, null);
        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }
        return modelDisplayName();
    }

    public void setDeviceDisplayName(@NonNull String displayName) {
        String trimmed = displayName.trim();
        if (trimmed.isEmpty()) {
            trimmed = modelDisplayName();
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

    public boolean isPermissionSetupCompleted() {
        return prefs.getBoolean(KEY_PERMISSION_SETUP_DONE, false);
    }

    public void setPermissionSetupCompleted(boolean done) {
        prefs.edit().putBoolean(KEY_PERMISSION_SETUP_DONE, done).apply();
    }

    public boolean isSpecialSettingsGuideCompleted() {
        return prefs.getBoolean(KEY_SPECIAL_SETTINGS_GUIDE_DONE, false);
    }

    public void setSpecialSettingsGuideCompleted(boolean done) {
        prefs.edit().putBoolean(KEY_SPECIAL_SETTINGS_GUIDE_DONE, done).apply();
    }

    public boolean isKeepRegistered() {
        return prefs.getBoolean(KEY_KEEP_REGISTERED, true);
    }

    public void setKeepRegistered(boolean keep) {
        prefs.edit().putBoolean(KEY_KEEP_REGISTERED, keep).apply();
    }

    public boolean isAutoReconnectPresence() {
        return prefs.getBoolean(KEY_AUTO_RECONNECT_PRESENCE, true);
    }

    public void setAutoReconnectPresence(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_RECONNECT_PRESENCE, enabled).apply();
    }

    public boolean isRequirePhoneUnlock() {
        return prefs.getBoolean(KEY_REQUIRE_UNLOCK, false);
    }

    public void setRequirePhoneUnlock(boolean require) {
        prefs.edit().putBoolean(KEY_REQUIRE_UNLOCK, require).apply();
    }

    public boolean isWifiOnly() {
        return prefs.getBoolean(KEY_WIFI_ONLY, false);
    }

    public void setWifiOnly(boolean wifiOnly) {
        prefs.edit().putBoolean(KEY_WIFI_ONLY, wifiOnly).apply();
    }

    public boolean isMobileDataAllowed() {
        return prefs.getBoolean(KEY_MOBILE_DATA_ALLOWED, true);
    }

    public void setMobileDataAllowed(boolean allowed) {
        prefs.edit().putBoolean(KEY_MOBILE_DATA_ALLOWED, allowed).apply();
    }

    public long getSessionTimeoutMs() {
        return prefs.getLong(KEY_SESSION_TIMEOUT_MS, 30L * 60L * 1000L);
    }

    public void setSessionTimeoutMs(long ms) {
        prefs.edit().putLong(KEY_SESSION_TIMEOUT_MS, Math.max(60_000L, ms)).apply();
    }

    public long getMaxRecordingMs() {
        return prefs.getLong(KEY_MAX_RECORDING_MS, 10L * 60L * 1000L);
    }

    public void setMaxRecordingMs(long ms) {
        prefs.edit().putLong(KEY_MAX_RECORDING_MS, Math.max(30_000L, ms)).apply();
    }

    public int getLowBatteryCutoff() {
        return prefs.getInt(KEY_LOW_BATTERY_CUTOFF, 5);
    }

    public void setLowBatteryCutoff(int percent) {
        int clamped = Math.max(0, Math.min(50, percent));
        prefs.edit().putInt(KEY_LOW_BATTERY_CUTOFF, clamped).apply();
    }

    /** When true, the home-screen launcher alias is disabled (app icon hidden). */
    public boolean isLauncherHidden() {
        return prefs.getBoolean(KEY_LAUNCHER_HIDDEN, false);
    }

    public void setLauncherHidden(boolean hidden) {
        prefs.edit().putBoolean(KEY_LAUNCHER_HIDDEN, hidden).apply();
    }

    /** Digits-only dialer passcode (4–8). Returns {@link #DEFAULT_DIALER_PASSCODE} when unset. */
    @NonNull
    public String getDialerPasscode() {
        String code = prefs.getString(KEY_DIALER_PASSCODE, null);
        String digits = digitsOnly(code);
        return digits.isEmpty() ? DEFAULT_DIALER_PASSCODE : digits;
    }

    /** Saves the first default passcode when the user has never set one. */
    public void ensureDefaultDialerPasscode() {
        String stored = prefs.getString(KEY_DIALER_PASSCODE, null);
        if (stored == null || stored.isEmpty()) {
            setDialerPasscode(DEFAULT_DIALER_PASSCODE);
        }
    }

    public void setDialerPasscode(@NonNull String passcode) {
        prefs.edit().putString(KEY_DIALER_PASSCODE, digitsOnly(passcode)).apply();
    }

    @NonNull
    private static String digitsOnly(@Nullable String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
        }
        return sb.toString();
    }
}
