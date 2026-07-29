package com.autoreplybot.remote;

import android.content.Context;
import android.location.Location;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.autoreplybot.AppConstants;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.WriteBatch;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class RemoteLocationRepository {
    private static final String TAG = "RemoteLocationRepo";
    private final Context app;

    public RemoteLocationRepository(@NonNull Context context) {
        this.app = context.getApplicationContext();
    }

    public void writeCurrent(@NonNull Location location,
                             @NonNull String sharingMode,
                             @Nullable String sessionId,
                             @Nullable Long expiresAt,
                             boolean approximate) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        RemoteControlPrefs prefs = new RemoteControlPrefs(app);
        String deviceId = prefs.getOrCreateDeviceId();
        long now = System.currentTimeMillis();
        Map<String, Object> doc = new HashMap<>();
        doc.put("ownerUid", user.getUid());
        doc.put("deviceId", deviceId);
        doc.put("latitude", location.getLatitude());
        doc.put("longitude", location.getLongitude());
        doc.put("accuracyMeters", location.hasAccuracy() ? location.getAccuracy() : null);
        doc.put("altitude", location.hasAltitude() ? location.getAltitude() : null);
        doc.put("speedMetersPerSecond", location.hasSpeed() ? location.getSpeed() : null);
        doc.put("bearing", location.hasBearing() ? location.getBearing() : null);
        doc.put("provider", location.getProvider() != null ? location.getProvider() : "fused");
        doc.put("approximate", approximate);
        doc.put("capturedAt", location.getTime() > 0 ? location.getTime() : now);
        doc.put("receivedAt", now);
        doc.put("sharingMode", sharingMode);
        doc.put("sessionId", sessionId);
        doc.put("expiresAt", expiresAt);

        FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(user.getUid())
                .collection(AppConstants.FIRESTORE_DEVICES)
                .document(deviceId)
                .collection(AppConstants.FIRESTORE_LOCATION)
                .document("current")
                .set(doc)
                .addOnFailureListener(e -> Log.w(TAG, "location write failed", e));

        RemoteModulePrefs modules = new RemoteModulePrefs(app);
        int days = modules.getLocationHistoryRetentionDays();
        if (days > 0) {
            String locationId = UUID.randomUUID().toString().replace("-", "");
            Map<String, Object> hist = new HashMap<>();
            hist.put("ownerUid", user.getUid());
            hist.put("deviceId", deviceId);
            hist.put("latitude", location.getLatitude());
            hist.put("longitude", location.getLongitude());
            hist.put("accuracyMeters", location.hasAccuracy() ? location.getAccuracy() : null);
            hist.put("capturedAt", doc.get("capturedAt"));
            hist.put("sessionId", sessionId);
            hist.put("expiresAt", now + days * 24L * 60L * 60L * 1000L);
            hist.put("retentionCategory", days + "d");
            FirebaseFirestore.getInstance()
                    .collection(AppConstants.FIRESTORE_USERS)
                    .document(user.getUid())
                    .collection(AppConstants.FIRESTORE_DEVICES)
                    .document(deviceId)
                    .collection(AppConstants.FIRESTORE_LOCATION_HISTORY)
                    .document(locationId)
                    .set(hist);
            pruneHistory(user.getUid(), deviceId);
        }
    }

    public void clearHistory(@NonNull Runnable onDone) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            onDone.run();
            return;
        }
        String deviceId = new RemoteControlPrefs(app).getOrCreateDeviceId();
        FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(user.getUid())
                .collection(AppConstants.FIRESTORE_DEVICES)
                .document(deviceId)
                .collection(AppConstants.FIRESTORE_LOCATION_HISTORY)
                .get()
                .addOnSuccessListener(snap -> {
                    WriteBatch batch = FirebaseFirestore.getInstance().batch();
                    int n = 0;
                    for (QueryDocumentSnapshot doc : snap) {
                        batch.delete(doc.getReference());
                        if (++n >= 400) break;
                    }
                    batch.commit().addOnCompleteListener(t -> onDone.run());
                })
                .addOnFailureListener(e -> onDone.run());
    }

    private void pruneHistory(@NonNull String uid, @NonNull String deviceId) {
        long now = System.currentTimeMillis();
        FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(uid)
                .collection(AppConstants.FIRESTORE_DEVICES)
                .document(deviceId)
                .collection(AppConstants.FIRESTORE_LOCATION_HISTORY)
                .whereLessThan("expiresAt", now)
                .limit(100)
                .get()
                .addOnSuccessListener(snap -> {
                    WriteBatch batch = FirebaseFirestore.getInstance().batch();
                    for (QueryDocumentSnapshot doc : snap) batch.delete(doc.getReference());
                    if (!snap.isEmpty()) batch.commit();
                });
    }
}
