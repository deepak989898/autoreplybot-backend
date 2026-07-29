package com.autoreplybot.remote;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.autoreplybot.AppConstants;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;

public final class RemoteDeviceInfoRepository {
    private static final String TAG = "RemoteDeviceInfoRepo";
    private final Context app;

    public RemoteDeviceInfoRepository(@NonNull Context context) {
        this.app = context.getApplicationContext();
    }

    public void publishCurrent(@NonNull Map<String, Object> info, @NonNull Runnable onDone) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            onDone.run();
            return;
        }
        RemoteControlPrefs prefs = new RemoteControlPrefs(app);
        String deviceId = prefs.getOrCreateDeviceId();
        Map<String, Object> doc = new HashMap<>(info);
        doc.put("ownerUid", user.getUid());
        doc.put("deviceId", deviceId);
        FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(user.getUid())
                .collection(AppConstants.FIRESTORE_DEVICES)
                .document(deviceId)
                .collection(AppConstants.FIRESTORE_DEVICE_INFO)
                .document("current")
                .set(doc)
                .addOnSuccessListener(v -> {
                    patchDeviceSummary(user.getUid(), deviceId, info);
                    onDone.run();
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "deviceInfo write failed", e);
                    onDone.run();
                });
    }

    @SuppressWarnings("unchecked")
    private void patchDeviceSummary(@NonNull String uid,
                                    @NonNull String deviceId,
                                    @NonNull Map<String, Object> info) {
        Map<String, Object> patch = new HashMap<>();
        patch.put("updatedAt", System.currentTimeMillis());
        Object battery = info.get("battery");
        if (battery instanceof Map) {
            Object pct = ((Map<String, Object>) battery).get("percentage");
            if (pct instanceof Number) {
                int p = ((Number) pct).intValue();
                patch.put("batteryLevel", p);
                patch.put("lowBattery", p <= 15);
            }
            Object charging = ((Map<String, Object>) battery).get("charging");
            if (charging instanceof Boolean) patch.put("isCharging", charging);
        }
        Object network = info.get("network");
        if (network instanceof Map) {
            Object type = ((Map<String, Object>) network).get("networkType");
            if (type != null) patch.put("networkType", String.valueOf(type));
        }
        Object storage = info.get("storage");
        if (storage instanceof Map) {
            Object used = ((Map<String, Object>) storage).get("usedBytes");
            Object total = ((Map<String, Object>) storage).get("totalBytes");
            if (used instanceof Number) patch.put("storageUsedBytes", ((Number) used).longValue());
            if (total instanceof Number) patch.put("storageTotalBytes", ((Number) total).longValue());
        }
        Object perms = info.get("permissions");
        if (perms instanceof Map) {
            Map<String, Object> p = (Map<String, Object>) perms;
            if (p.get("camera") != null) patch.put("cameraPermission", String.valueOf(p.get("camera")));
            if (p.get("microphone") != null) patch.put("microphonePermission", String.valueOf(p.get("microphone")));
            Object fine = p.get("fineLocation");
            Object coarse = p.get("coarseLocation");
            String loc = "denied";
            if ("granted".equals(String.valueOf(fine)) || "granted".equals(String.valueOf(coarse))) {
                loc = "granted";
            }
            patch.put("locationPermission", loc);
        }
        RemoteModulePrefs modules = new RemoteModulePrefs(app);
        patch.put("locationSharingEnabled", modules.isLocationSharingEnabled());
        patch.put("locationSharingMode", modules.getLocationMode());
        patch.put("galleryAccessEnabled", modules.isGalleryEnabled());
        patch.put("notificationMirrorEnabled", modules.isNotificationMirrorEnabled());
        patch.put("messagesSharingEnabled", modules.isMessagesSharingEnabled());
        patch.put("screenMirrorEnabled", modules.isScreenMirrorEnabled());
        patch.put("screenRecordEnabled", modules.isScreenRecordEnabled());
        patch.put("installedAppsSharingEnabled", modules.isInstalledAppsSharingEnabled());
        patch.put("appControlEnabled", modules.isAppControlEnabled());
        patch.put("fileManagerEnabled", modules.isFileManagerEnabled());
        FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(uid)
                .collection(AppConstants.FIRESTORE_DEVICES)
                .document(deviceId)
                .set(patch, SetOptions.merge());
    }

    /** Lightweight patch when a module toggle changes (no full device-info collect). */
    public void publishModuleFlags() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        String deviceId = new RemoteControlPrefs(app).getOrCreateDeviceId();
        RemoteModulePrefs modules = new RemoteModulePrefs(app);
        Map<String, Object> patch = new HashMap<>();
        patch.put("updatedAt", System.currentTimeMillis());
        patch.put("locationSharingEnabled", modules.isLocationSharingEnabled());
        patch.put("locationSharingMode", modules.getLocationMode());
        patch.put("galleryAccessEnabled", modules.isGalleryEnabled());
        patch.put("notificationMirrorEnabled", modules.isNotificationMirrorEnabled());
        patch.put("messagesSharingEnabled", modules.isMessagesSharingEnabled());
        patch.put("screenMirrorEnabled", modules.isScreenMirrorEnabled());
        patch.put("screenRecordEnabled", modules.isScreenRecordEnabled());
        patch.put("installedAppsSharingEnabled", modules.isInstalledAppsSharingEnabled());
        patch.put("appControlEnabled", modules.isAppControlEnabled());
        patch.put("fileManagerEnabled", modules.isFileManagerEnabled());
        FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(user.getUid())
                .collection(AppConstants.FIRESTORE_DEVICES)
                .document(deviceId)
                .set(patch, SetOptions.merge());
    }
}
