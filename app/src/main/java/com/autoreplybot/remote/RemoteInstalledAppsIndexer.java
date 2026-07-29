package com.autoreplybot.remote;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import com.autoreplybot.AppConstants;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.WriteBatch;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Indexes installed apps via PackageManager into Firestore (metadata only, no icons). */
public final class RemoteInstalledAppsIndexer {
    private static final String TAG = "RemoteAppsIndexer";
    public static final int ERR_DISABLED = -1;
    public static final int ERR_AUTH = -2;
    public static final int ERR_WRITE = -3;

    private final Context app;

    public RemoteInstalledAppsIndexer(@NonNull Context context) {
        this.app = context.getApplicationContext();
    }

    public int indexAndSync() {
        RemoteModulePrefs prefs = new RemoteModulePrefs(app);
        if (!prefs.isInstalledAppsSharingEnabled()) {
            return ERR_DISABLED;
        }
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return ERR_AUTH;
        String deviceId = new RemoteControlPrefs(app).getOrCreateDeviceId();
        PackageManager pm = app.getPackageManager();
        List<ApplicationInfo> apps;
        try {
            apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        } catch (Exception e) {
            Log.w(TAG, "list failed", e);
            return ERR_WRITE;
        }
        long now = System.currentTimeMillis();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ApplicationInfo ai : apps) {
            if (ai == null || ai.packageName == null) continue;
            try {
                rows.add(toRow(pm, ai, deviceId, user.getUid(), now));
            } catch (Exception e) {
                Log.w(TAG, "skip " + ai.packageName, e);
            }
        }
        try {
            writeBatches(user.getUid(), deviceId, rows);
            return rows.size();
        } catch (Exception e) {
            Log.w(TAG, "write failed", e);
            return ERR_WRITE;
        }
    }

    @NonNull
    private Map<String, Object> toRow(@NonNull PackageManager pm,
                                      @NonNull ApplicationInfo ai,
                                      @NonNull String deviceId,
                                      @NonNull String uid,
                                      long now) throws Exception {
        PackageInfo pi = pm.getPackageInfo(ai.packageName, PackageManager.GET_PERMISSIONS);
        CharSequence label = pm.getApplicationLabel(ai);
        boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                || (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        Map<String, Object> row = new HashMap<>();
        row.put("ownerUid", uid);
        row.put("deviceId", deviceId);
        row.put("packageName", ai.packageName);
        row.put("appName", label != null ? label.toString() : ai.packageName);
        row.put("versionName", pi.versionName != null ? pi.versionName : "");
        long versionCode = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        row.put("versionCode", versionCode);
        row.put("firstInstallTime", pi.firstInstallTime);
        row.put("lastUpdateTime", pi.lastUpdateTime);
        row.put("isSystem", system);
        row.put("enabled", ai.enabled);
        row.put("targetSdk", ai.targetSdkVersion);
        row.put("minSdk", Build.VERSION.SDK_INT >= 24 ? ai.minSdkVersion : 0);
        row.put("category", categoryFor(ai));
        int permCount = pi.requestedPermissions != null ? pi.requestedPermissions.length : 0;
        row.put("permissionCount", permCount);
        if (pi.requestedPermissions != null && pi.requestedPermissions.length > 0) {
            int max = Math.min(80, pi.requestedPermissions.length);
            List<String> perms = new ArrayList<>(max);
            for (int i = 0; i < max; i++) perms.add(pi.requestedPermissions[i]);
            row.put("permissions", perms);
        }
        row.put("installSource", installSource(pm, ai.packageName));
        row.put("supportedAbis", supportedAbis());
        row.put("syncedAt", now);
        row.put("appId", docId(ai.packageName));
        return row;
    }

    @NonNull
    private static String categoryFor(@NonNull ApplicationInfo ai) {
        if (Build.VERSION.SDK_INT < 26) return "other";
        switch (ai.category) {
            case ApplicationInfo.CATEGORY_GAME:
                return "games";
            case ApplicationInfo.CATEGORY_SOCIAL:
                return "social";
            case ApplicationInfo.CATEGORY_PRODUCTIVITY:
                return "productivity";
            case ApplicationInfo.CATEGORY_NEWS:
            case ApplicationInfo.CATEGORY_AUDIO:
            case ApplicationInfo.CATEGORY_VIDEO:
            case ApplicationInfo.CATEGORY_IMAGE:
                return "tools";
            default:
                return "other";
        }
    }

    @NonNull
    private static String installSource(@NonNull PackageManager pm, @NonNull String pkg) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                String s = pm.getInstallSourceInfo(pkg).getInstallingPackageName();
                return s != null ? s : "";
            }
            String s = pm.getInstallerPackageName(pkg);
            return s != null ? s : "";
        } catch (Exception e) {
            return "";
        }
    }

    @NonNull
    private static List<String> supportedAbis() {
        List<String> out = new ArrayList<>();
        for (String abi : Build.SUPPORTED_ABIS) {
            if (abi != null && !abi.isEmpty()) out.add(abi);
        }
        return out;
    }

    private void writeBatches(@NonNull String uid,
                              @NonNull String deviceId,
                              @NonNull List<Map<String, Object>> rows) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        WriteBatch batch = db.batch();
        int n = 0;
        for (Map<String, Object> row : rows) {
            String id = String.valueOf(row.get("appId"));
            batch.set(
                    db.collection(AppConstants.FIRESTORE_USERS)
                            .document(uid)
                            .collection(AppConstants.FIRESTORE_DEVICES)
                            .document(deviceId)
                            .collection(AppConstants.FIRESTORE_INSTALLED_APPS)
                            .document(id),
                    row,
                    SetOptions.merge());
            n++;
            if (n >= 400) {
                try {
                    Tasks.await(batch.commit());
                } catch (Exception e) {
                    throw new IllegalStateException("apps batch write failed", e);
                }
                batch = db.batch();
                n = 0;
            }
        }
        if (n > 0) {
            try {
                Tasks.await(batch.commit());
            } catch (Exception e) {
                throw new IllegalStateException("apps batch write failed", e);
            }
        }
    }

    @NonNull
    public static String docId(@NonNull String packageName) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(packageName.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(40);
            for (int i = 0; i < 20; i++) sb.append(String.format(Locale.US, "%02x", dig[i]));
            return sb.toString();
        } catch (Exception e) {
            return packageName.replace('.', '_').replaceAll("[^A-Za-z0-9_-]", "_");
        }
    }
}
