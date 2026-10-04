package com.autoreplybot.remote;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.autoreplybot.AppConstants;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;

/**
 * Registers and heartbeats this device under users/{uid}/devices/{deviceId}.
 * Does not touch auto-reply / contacts / Facebook collections.
 */
public final class RemoteDeviceRepository {
    private static final long HEARTBEAT_MIN_INTERVAL_MS = 60_000L;
    private static final long PRESENCE_INTERVAL_MS = 90_000L;
    private static final Handler PRESENCE_HANDLER = new Handler(Looper.getMainLooper());
    @Nullable private static Runnable presenceRunnable;
    @Nullable private static Context presenceApp;

    private final Context app;
    private final RemoteControlPrefs prefs;
    private long lastHeartbeatElapsedMs = -HEARTBEAT_MIN_INTERVAL_MS;

    public RemoteDeviceRepository(@NonNull Context context) {
        this.app = context.getApplicationContext();
        this.prefs = new RemoteControlPrefs(app);
    }

    @NonNull
    public Task<Void> registerOrUpdateDevice() {
        try {
            String uid = requireUid();
            String deviceId = prefs.getOrCreateDeviceId();
            validateId(deviceId);
            long now = System.currentTimeMillis();
            DocumentReference ref = deviceDoc(uid, deviceId);
            return ref.get().continueWithTask(task -> {
                long createdAt = now;
                if (task.isSuccessful() && task.getResult() != null && task.getResult().exists()) {
                    Object existing = task.getResult().get("createdAt");
                    if (existing instanceof Number) {
                        createdAt = ((Number) existing).longValue();
                    }
                }
                RemoteDeviceProfile profile = buildProfile(uid, deviceId, createdAt, now,
                        prefs.isRemoteControlEnabled(), true);
                return ref.set(profile.toMap(), SetOptions.merge());
            });
        } catch (RuntimeException error) {
            return Tasks.forException(error);
        }
    }

    /**
     * Updates lastSeenAt, battery, charging, network, online.
     * Throttled to at most once per 60s unless {@code force} is true.
     */
    @NonNull
    public Task<Void> heartbeat(boolean force) {
        try {
            if (!prefs.isRemoteControlEnabled()) {
                return Tasks.forResult(null);
            }
            long nowElapsed = SystemClock.elapsedRealtime();
            if (!force && nowElapsed - lastHeartbeatElapsedMs < HEARTBEAT_MIN_INTERVAL_MS) {
                return Tasks.forResult(null);
            }
            String uid = requireUid();
            String deviceId = prefs.getOrCreateDeviceId();
            validateId(deviceId);
            Map<String, Object> patch = new HashMap<>();
            long nowWall = System.currentTimeMillis();
            patch.put("lastSeenAt", nowWall);
            patch.put("updatedAt", nowWall);
            patch.put("online", true);
            patch.put("remoteControlEnabled", true);
            patch.put("batteryLevel", readBatteryLevel());
            patch.put("isCharging", readIsCharging());
            patch.put("networkType", readNetworkType());
            PackageManager pm = app.getPackageManager();
            boolean hasCamera = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
                    || pm.hasSystemFeature(PackageManager.FEATURE_CAMERA);
            boolean hasMic = pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE);
            patch.put("cameraAvailable", hasCamera);
            patch.put("microphoneAvailable", hasMic);
            patch.put("cameraPermission",
                    RemotePermissionChecks.hasCamera(app) ? "granted" : "denied");
            patch.put("microphonePermission",
                    RemotePermissionChecks.hasMicrophone(app) ? "granted" : "denied");
            String fcmToken = prefs.getFcmToken();
            if (!fcmToken.isEmpty()) {
                patch.put("fcmToken", fcmToken);
            }
            return deviceDoc(uid, deviceId).set(patch, SetOptions.merge())
                    .addOnSuccessListener(ignored -> lastHeartbeatElapsedMs = nowElapsed);
        } catch (RuntimeException error) {
            return Tasks.forException(error);
        }
    }

    /** Keep lastSeenAt fresh while Remote Control is on so the website Online badge matches the PC clock. */
    public static void startPresenceLoop(@NonNull Context context) {
        Context app = context.getApplicationContext();
        presenceApp = app;
        if (presenceRunnable == null) {
            presenceRunnable = new Runnable() {
                @Override
                public void run() {
                    Context c = presenceApp;
                    if (c == null) return;
                    if (!new RemoteControlPrefs(c).isRemoteControlEnabled()) return;
                    new RemoteDeviceRepository(c).heartbeat(true);
                    PRESENCE_HANDLER.postDelayed(this, PRESENCE_INTERVAL_MS);
                }
            };
        }
        PRESENCE_HANDLER.removeCallbacks(presenceRunnable);
        new RemoteDeviceRepository(app).heartbeat(true);
        PRESENCE_HANDLER.postDelayed(presenceRunnable, PRESENCE_INTERVAL_MS);
    }

    public static void stopPresenceLoop() {
        if (presenceRunnable != null) {
            PRESENCE_HANDLER.removeCallbacks(presenceRunnable);
        }
        Context c = presenceApp;
        presenceApp = null;
        if (c == null) return;
        try {
            new RemoteDeviceRepository(c).setOnline(false);
        } catch (RuntimeException ignored) {
            // Not signed in.
        }
    }

    @NonNull
    public Task<Void> setOnline(boolean online) {
        try {
            String uid = requireUid();
            String deviceId = prefs.getOrCreateDeviceId();
            validateId(deviceId);
            Map<String, Object> patch = new HashMap<>();
            patch.put("online", online);
            patch.put("remoteControlEnabled", prefs.isRemoteControlEnabled());
            if (online) {
                patch.put("lastSeenAt", System.currentTimeMillis());
            }
            return deviceDoc(uid, deviceId).set(patch, SetOptions.merge());
        } catch (RuntimeException error) {
            return Tasks.forException(error);
        }
    }

    /** Persists FCM token locally and merges into the device profile when remote is enabled. */
    @NonNull
    public Task<Void> updateFcmToken(@NonNull String token) {
        try {
            String trimmed = token != null ? token.trim() : "";
            prefs.setFcmToken(trimmed);
            if (trimmed.isEmpty() || !prefs.isRemoteControlEnabled()) {
                return Tasks.forResult(null);
            }
            String uid = requireUid();
            String deviceId = prefs.getOrCreateDeviceId();
            validateId(deviceId);
            Map<String, Object> patch = new HashMap<>();
            patch.put("fcmToken", trimmed);
            patch.put("lastSeenAt", System.currentTimeMillis());
            return deviceDoc(uid, deviceId).set(patch, SetOptions.merge());
        } catch (RuntimeException error) {
            return Tasks.forException(error);
        }
    }

    @NonNull
    public Task<Void> updateDisplayName(@NonNull String displayName) {
        try {
            prefs.setDeviceDisplayName(displayName);
            if (!prefs.isRemoteControlEnabled()) {
                return Tasks.forResult(null);
            }
            String uid = requireUid();
            String deviceId = prefs.getOrCreateDeviceId();
            validateId(deviceId);
            Map<String, Object> patch = new HashMap<>();
            patch.put("deviceName", prefs.getDeviceDisplayName());
            patch.put("lastSeenAt", System.currentTimeMillis());
            return deviceDoc(uid, deviceId).set(patch, SetOptions.merge());
        } catch (RuntimeException error) {
            return Tasks.forException(error);
        }
    }

    @NonNull
    private RemoteDeviceProfile buildProfile(@NonNull String uid,
                                             @NonNull String deviceId,
                                             long createdAt,
                                             long lastSeenAt,
                                             boolean remoteControlEnabled,
                                             boolean online) {
        PackageManager pm = app.getPackageManager();
        return new RemoteDeviceProfile(
                deviceId,
                prefs.getDeviceDisplayName(),
                Build.MODEL != null ? Build.MODEL : "",
                Build.MANUFACTURER != null ? Build.MANUFACTURER : "",
                String.valueOf(Build.VERSION.SDK_INT),
                readAppVersion(),
                createdAt,
                lastSeenAt,
                online,
                readBatteryLevel(),
                readIsCharging(),
                readNetworkType(),
                prefs.getFcmToken(),
                pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
                        || pm.hasSystemFeature(PackageManager.FEATURE_CAMERA),
                pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE),
                pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH),
                false,
                uid,
                remoteControlEnabled);
    }

    @NonNull
    private DocumentReference deviceDoc(@NonNull String uid, @NonNull String deviceId) {
        return FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(uid)
                .collection(AppConstants.FIRESTORE_DEVICES)
                .document(deviceId);
    }

    @NonNull
    private static String requireUid() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) throw new IllegalStateException("Not signed in");
        return user.getUid();
    }

    private static void validateId(@NonNull String id) {
        if (!id.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Invalid document identifier");
        }
    }

    private int readBatteryLevel() {
        Intent battery = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return 0;
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) return 0;
        return Math.max(0, Math.min(100, Math.round(100f * level / scale)));
    }

    private boolean readIsCharging() {
        Intent battery = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return false;
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        return status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
    }

    @NonNull
    private String readNetworkType() {
        ConnectivityManager cm =
                (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return "unknown";
        Network network = cm.getActiveNetwork();
        if (network == null) return "none";
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        if (caps == null) return "unknown";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "cellular";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ethernet";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) return "bluetooth";
        return "other";
    }

    @NonNull
    private String readAppVersion() {
        try {
            String versionName;
            if (Build.VERSION.SDK_INT >= 33) {
                versionName = app.getPackageManager()
                        .getPackageInfo(app.getPackageName(), PackageManager.PackageInfoFlags.of(0))
                        .versionName;
            } else {
                //noinspection deprecation
                versionName = app.getPackageManager()
                        .getPackageInfo(app.getPackageName(), 0).versionName;
            }
            return versionName != null ? versionName : "";
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }
}
