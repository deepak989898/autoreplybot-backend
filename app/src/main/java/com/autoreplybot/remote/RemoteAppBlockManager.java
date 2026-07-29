package com.autoreplybot.remote;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.autoreplybot.AppConstants;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Applies timed app blocks and optional camera hardware lock. */
public final class RemoteAppBlockManager {
    private static final String TAG = "RemoteAppBlock";
    public static final int OK = 0;
    public static final int ERR_AUTH = -1;
    public static final int ERR_PROTECTED = -2;
    public static final int ERR_ACCESSIBILITY = -3;
    public static final int ERR_DEVICE_ADMIN = -4;
    public static final int ERR_WRITE = -5;
    public static final int ERR_DISABLED = -6;

    private final Context app;
    private final RemoteAppBlockStore store;

    public RemoteAppBlockManager(@NonNull Context context) {
        this.app = context.getApplicationContext();
        this.store = new RemoteAppBlockStore(app);
    }

    public static boolean isAccessibilityEnabled(@NonNull Context context) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        String needle = context.getPackageName() + "/"
                + RemoteAppBlockAccessibilityService.class.getName();
        String shortNeedle = context.getPackageName() + "/.remote.RemoteAppBlockAccessibilityService";
        for (String part : enabled.split(":")) {
            if (needle.equalsIgnoreCase(part) || shortNeedle.equalsIgnoreCase(part)) return true;
            if (part != null && part.startsWith(context.getPackageName())
                    && part.contains("RemoteAppBlockAccessibilityService")) {
                return true;
            }
        }
        return false;
    }

    public static boolean isDeviceAdminActive(@NonNull Context context) {
        DevicePolicyManager dpm = (DevicePolicyManager)
                context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) return false;
        return dpm.isAdminActive(adminComponent(context));
    }

    @NonNull
    public static ComponentName adminComponent(@NonNull Context context) {
        return new ComponentName(context.getApplicationContext(), RemoteDeviceAdminReceiver.class);
    }

    public static void openAccessibilitySettings(@NonNull Context context) {
        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
    }

    /**
     * Opens the system Device Admin activation screen.
     * @return true if an activity was started
     */
    public static boolean requestDeviceAdmin(@NonNull Context context) {
        try {
            Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(context));
            i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    context.getString(com.autoreplybot.R.string.remote_device_admin_explain));
            // NEW_TASK from an Activity can fail silently on some OEMs (ColorOS/OnePlus).
            if (!(context instanceof android.app.Activity)) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            if (i.resolveActivity(context.getPackageManager()) == null) {
                Intent fallback = new Intent(Settings.ACTION_SECURITY_SETTINGS);
                if (!(context instanceof android.app.Activity)) {
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                context.startActivity(fallback);
                return true;
            }
            context.startActivity(i);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "requestDeviceAdmin failed", e);
            try {
                Intent fallback = new Intent(Settings.ACTION_SECURITY_SETTINGS);
                if (!(context instanceof android.app.Activity)) {
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                context.startActivity(fallback);
                return true;
            } catch (Exception e2) {
                Log.w(TAG, "security settings fallback failed", e2);
                return false;
            }
        }
    }

    public int blockApp(@NonNull String packageName,
                        @NonNull String appName,
                        long durationMs) {
        RemoteModulePrefs prefs = new RemoteModulePrefs(app);
        if (!prefs.isAppControlEnabled()) {
            prefs.setAppControlEnabled(true);
            new RemoteDeviceInfoRepository(app).publishModuleFlags();
        }
        if (RemoteAppBlockStore.protectedPackages(app).contains(packageName)) {
            return ERR_PROTECTED;
        }
        if (!isAccessibilityEnabled(app)) {
            return ERR_ACCESSIBILITY;
        }
        long now = System.currentTimeMillis();
        long expiresAt = durationMs > 0 ? now + durationMs : 0L;
        RemoteAppBlockStore.Block block = new RemoteAppBlockStore.Block(
                packageName,
                appName.isEmpty() ? packageName : appName,
                RemoteAppBlockStore.MODE_APP,
                "active",
                durationMs,
                expiresAt,
                now,
                now);
        store.upsert(block);
        scheduleExpiry(packageName, expiresAt);
        int write = writeBlockCloud(block);
        return write == OK ? OK : write;
    }

    public int blockCameraHardware(long durationMs) {
        RemoteModulePrefs prefs = new RemoteModulePrefs(app);
        if (!prefs.isAppControlEnabled()) {
            prefs.setAppControlEnabled(true);
            new RemoteDeviceInfoRepository(app).publishModuleFlags();
        }
        if (!isDeviceAdminActive(app)) {
            return ERR_DEVICE_ADMIN;
        }
        DevicePolicyManager dpm = (DevicePolicyManager) app.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) return ERR_DEVICE_ADMIN;
        try {
            dpm.setCameraDisabled(adminComponent(app), true);
        } catch (SecurityException e) {
            Log.w(TAG, "camera disable failed", e);
            return ERR_DEVICE_ADMIN;
        }
        long now = System.currentTimeMillis();
        long expiresAt = durationMs > 0 ? now + durationMs : 0L;
        RemoteAppBlockStore.Block block = new RemoteAppBlockStore.Block(
                RemoteAppBlockStore.PACKAGE_CAMERA_HW,
                "Camera hardware",
                RemoteAppBlockStore.MODE_CAMERA_HW,
                "active",
                durationMs,
                expiresAt,
                now,
                now);
        store.upsert(block);
        scheduleExpiry(RemoteAppBlockStore.PACKAGE_CAMERA_HW, expiresAt);
        return writeBlockCloud(block);
    }

    public int unblock(@NonNull String packageName) {
        if (RemoteAppBlockStore.PACKAGE_CAMERA_HW.equals(packageName)
                || (store.get(packageName) != null
                && RemoteAppBlockStore.MODE_CAMERA_HW.equals(store.get(packageName).mode))) {
            return unblockCameraHardware();
        }
        cancelExpiry(packageName);
        RemoteAppBlockStore.Block existing = store.get(packageName);
        long now = System.currentTimeMillis();
        if (existing != null) {
            existing.status = "cleared";
            existing.updatedAt = now;
            store.upsert(existing);
            writeBlockCloud(existing);
            store.remove(packageName);
        } else {
            writeClearedStub(packageName, "App", RemoteAppBlockStore.MODE_APP);
        }
        return OK;
    }

    public int unblockCameraHardware() {
        clearCameraHardwareQuietly();
        cancelExpiry(RemoteAppBlockStore.PACKAGE_CAMERA_HW);
        long now = System.currentTimeMillis();
        RemoteAppBlockStore.Block existing = store.get(RemoteAppBlockStore.PACKAGE_CAMERA_HW);
        if (existing != null) {
            existing.status = "cleared";
            existing.updatedAt = now;
            writeBlockCloud(existing);
            store.remove(RemoteAppBlockStore.PACKAGE_CAMERA_HW);
        } else {
            writeClearedStub(RemoteAppBlockStore.PACKAGE_CAMERA_HW, "Camera hardware",
                    RemoteAppBlockStore.MODE_CAMERA_HW);
        }
        return OK;
    }

    void clearCameraHardwareQuietly() {
        if (!isDeviceAdminActive(app)) return;
        DevicePolicyManager dpm = (DevicePolicyManager) app.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) return;
        try {
            dpm.setCameraDisabled(adminComponent(app), false);
        } catch (SecurityException e) {
            Log.w(TAG, "camera enable failed", e);
        }
    }

    public void expireIfDue(@NonNull String packageName) {
        RemoteAppBlockStore.Block b = store.get(packageName);
        long now = System.currentTimeMillis();
        if (b == null) return;
        if (b.expiresAt > 0 && b.expiresAt <= now) {
            if (RemoteAppBlockStore.MODE_CAMERA_HW.equals(b.mode)) {
                clearCameraHardwareQuietly();
            }
            b.status = "expired";
            b.updatedAt = now;
            writeBlockCloud(b);
            store.remove(packageName);
        }
    }

    public void purgeExpiredAndSync() {
        long now = System.currentTimeMillis();
        for (RemoteAppBlockStore.Block b : store.listAll()) {
            if ("active".equals(b.status) && b.expiresAt > 0 && b.expiresAt <= now) {
                expireIfDue(b.packageName);
            }
        }
        // Restore camera hw if still active after reboot
        if (store.isCameraHardwareLocked(now) && isDeviceAdminActive(app)) {
            DevicePolicyManager dpm = (DevicePolicyManager) app.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm != null) {
                try {
                    dpm.setCameraDisabled(adminComponent(app), true);
                } catch (SecurityException ignored) {
                }
            }
        }
        syncStatusToCloud();
    }

    public int syncStatusToCloud() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return ERR_AUTH;
        long now = System.currentTimeMillis();
        int last = OK;
        for (RemoteAppBlockStore.Block b : store.listAll()) {
            if ("active".equals(b.status) && b.expiresAt > 0 && b.expiresAt <= now) {
                expireIfDue(b.packageName);
                continue;
            }
            int w = writeBlockCloud(b);
            if (w != OK) last = w;
        }
        return last;
    }

    public boolean shouldBlockPackage(@Nullable String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        if (packageName.equals(app.getPackageName())) return false;
        // Local-only: never touch Firestore from the accessibility event thread.
        purgeSoftExpiredLocal();
        return store.isPackageBlocked(packageName, System.currentTimeMillis());
    }

    /** Expire locally without blocking; cloud sync is best-effort async. */
    private void purgeSoftExpiredLocal() {
        long now = System.currentTimeMillis();
        for (RemoteAppBlockStore.Block b : store.listAll()) {
            if (!"active".equals(b.status) || b.expiresAt <= 0 || b.expiresAt > now) continue;
            if (RemoteAppBlockStore.MODE_CAMERA_HW.equals(b.mode)) {
                clearCameraHardwareQuietly();
            }
            b.status = "expired";
            b.updatedAt = now;
            store.remove(b.packageName);
            writeBlockCloudAsync(b);
        }
    }

    private void scheduleExpiry(@NonNull String packageName, long expiresAt) {
        cancelExpiry(packageName);
        if (expiresAt <= 0) return;
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = expiryIntent(packageName);
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, expiresAt, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, expiresAt, pi);
            }
        } catch (SecurityException e) {
            am.set(AlarmManager.RTC_WAKEUP, expiresAt, pi);
        }
    }

    private void cancelExpiry(@NonNull String packageName) {
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(expiryIntent(packageName));
    }

    @NonNull
    private PendingIntent expiryIntent(@NonNull String packageName) {
        Intent i = new Intent(app, RemoteAppBlockExpiryReceiver.class);
        i.setAction(RemoteAppBlockExpiryReceiver.ACTION_EXPIRE);
        i.putExtra(RemoteAppBlockExpiryReceiver.EXTRA_PACKAGE, packageName);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(app, packageName.hashCode(), i, flags);
    }

    private int writeBlockCloud(@NonNull RemoteAppBlockStore.Block block) {
        // Never block the main / accessibility thread — that disables the service on ColorOS.
        if (Looper.getMainLooper().isCurrentThread()) {
            writeBlockCloudAsync(block);
            return OK;
        }
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return ERR_AUTH;
        try {
            Tasks.await(writeBlockCloudTask(user, block), 12, TimeUnit.SECONDS);
            return OK;
        } catch (Exception e) {
            Log.w(TAG, "cloud write failed", e);
            return ERR_WRITE;
        }
    }

    private void writeBlockCloudAsync(@NonNull RemoteAppBlockStore.Block block) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        writeBlockCloudTask(user, block)
                .addOnFailureListener(e -> Log.w(TAG, "cloud write async failed", e));
    }

    @NonNull
    private com.google.android.gms.tasks.Task<Void> writeBlockCloudTask(
            @NonNull FirebaseUser user, @NonNull RemoteAppBlockStore.Block block) {
        String deviceId = new RemoteControlPrefs(app).getOrCreateDeviceId();
        String blockId = docId(block.packageName);
        Map<String, Object> row = new HashMap<>();
        row.put("ownerUid", user.getUid());
        row.put("deviceId", deviceId);
        row.put("blockId", blockId);
        row.put("packageName", block.packageName);
        row.put("appName", block.appName);
        row.put("mode", block.mode);
        row.put("status", block.status);
        row.put("durationMs", block.durationMs);
        row.put("expiresAt", block.expiresAt);
        row.put("createdAt", block.createdAt);
        row.put("updatedAt", block.updatedAt > 0 ? block.updatedAt : System.currentTimeMillis());
        row.put("accessibilityReady", isAccessibilityEnabled(app));
        row.put("deviceAdminReady", isDeviceAdminActive(app));
        return FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(user.getUid())
                .collection(AppConstants.FIRESTORE_DEVICES)
                .document(deviceId)
                .collection(AppConstants.FIRESTORE_APP_BLOCKS)
                .document(blockId)
                .set(row, SetOptions.merge());
    }

    private void writeClearedStub(@NonNull String packageName,
                                  @NonNull String appName,
                                  @NonNull String mode) {
        long now = System.currentTimeMillis();
        RemoteAppBlockStore.Block stub = new RemoteAppBlockStore.Block(
                packageName, appName, mode, "cleared", 0, 0, now, now);
        writeBlockCloud(stub);
    }

    @NonNull
    static String docId(@NonNull String packageName) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(packageName.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(40);
            for (int i = 0; i < 20; i++) {
                sb.append(String.format(Locale.US, "%02x", dig[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "b_" + Integer.toHexString(packageName.hashCode());
        }
    }

    @NonNull
    public List<RemoteAppBlockStore.Block> activeBlocks() {
        return store.listActive(System.currentTimeMillis());
    }
}
