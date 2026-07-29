package com.autoreplybot.remote;

import android.app.Notification;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.autoreplybot.AppConstants;
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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mirrors status-bar notifications to Firestore when Notification Sharing is enabled.
 * Independent of Auto Reply filtering.
 */
public final class RemoteNotificationMirror {
    private static final String TAG = "RemoteNotifMirror";
    private static final int MAX_TEXT = 2000;
    private static final int MAX_TITLE = 400;
    private static final long MIN_WRITE_GAP_MS = 50L;
    private static final AtomicLong lastWriteAt = new AtomicLong(0);
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    /** Sync result codes (negative = error). */
    public static final int ERR_DISABLED = -1;
    public static final int ERR_LISTENER = -2;
    public static final int ERR_AUTH = -3;
    public static final int ERR_WRITE = -4;

    private RemoteNotificationMirror() {}

    public static void onPosted(@NonNull Context context, @Nullable StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return;
        Context app = context.getApplicationContext();
        ensureSharingEnabledIfListenerReady(app);
        if (!new RemoteModulePrefs(app).isNotificationMirrorEnabled()) return;
        if (shouldSkip(app, sbn)) return;
        Map<String, Object> item = buildItem(app, sbn);
        if (item == null) {
            Log.d(TAG, "skip empty content pkg=" + sbn.getPackageName());
            return;
        }
        IO.execute(() -> writeItem(app, item, false));
    }

    /** If Notification Access is on, ensure website sharing is on (no second toggle needed). */
    public static boolean ensureSharingEnabledIfListenerReady(@NonNull Context context) {
        Context app = context.getApplicationContext();
        RemoteModulePrefs prefs = new RemoteModulePrefs(app);
        if (prefs.isNotificationMirrorEnabled()) return true;
        if (!RemotePermissionChecks.hasNotificationListener(app)) return false;
        prefs.setNotificationMirrorEnabled(true);
        new RemoteDeviceInfoRepository(app).publishModuleFlags();
        Log.i(TAG, "auto-enabled notification sharing (NLS granted)");
        return true;
    }

    /**
     * Upload currently active notifications.
     * @return count written, or negative {@code ERR_*} code
     */
    public static int syncActive(@NonNull Context context) {
        Context app = context.getApplicationContext();
        ensureSharingEnabledIfListenerReady(app);
        if (!new RemoteModulePrefs(app).isNotificationMirrorEnabled()) {
            Log.w(TAG, "sync skipped: sharing disabled and NLS not granted");
            return ERR_DISABLED;
        }
        StatusBarNotification[] active;
        try {
            active = RemoteNotificationListenerBridge.getActiveNotifications();
        } catch (Exception e) {
            Log.w(TAG, "active notifications unavailable", e);
            return ERR_LISTENER;
        }
        if (active == null) {
            Log.w(TAG, "sync skipped: NotificationListener not connected");
            return ERR_LISTENER;
        }
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return ERR_AUTH;

        String deviceId = new RemoteControlPrefs(app).getOrCreateDeviceId();
        List<Map<String, Object>> items = new ArrayList<>();
        for (StatusBarNotification sbn : active) {
            if (sbn == null || shouldSkip(app, sbn)) continue;
            Map<String, Object> item = buildItem(app, sbn);
            if (item == null) continue;
            item.put("ownerUid", user.getUid());
            item.put("deviceId", deviceId);
            item.put("syncedAt", System.currentTimeMillis());
            items.add(item);
            if (items.size() >= 80) break;
        }
        if (items.isEmpty()) {
            Log.i(TAG, "sync found 0 parseable active notifications (total active="
                    + active.length + ")");
            return 0;
        }

        AtomicReference<Exception> fail = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        WriteBatch batch = FirebaseFirestore.getInstance().batch();
        for (Map<String, Object> item : items) {
            String itemId = String.valueOf(item.get("itemId"));
            batch.set(
                    FirebaseFirestore.getInstance()
                            .collection(AppConstants.FIRESTORE_USERS)
                            .document(user.getUid())
                            .collection(AppConstants.FIRESTORE_DEVICES)
                            .document(deviceId)
                            .collection(AppConstants.FIRESTORE_NOTIFICATION_ITEMS)
                            .document(itemId),
                    item,
                    SetOptions.merge());
        }
        batch.commit()
                .addOnSuccessListener(v -> done.countDown())
                .addOnFailureListener(e -> {
                    fail.set(e);
                    Log.w(TAG, "active sync failed", e);
                    done.countDown();
                });
        try {
            if (!done.await(20, TimeUnit.SECONDS)) return ERR_WRITE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ERR_WRITE;
        }
        if (fail.get() != null) return ERR_WRITE;
        Log.i(TAG, "synced " + items.size() + " notifications");
        return items.size();
    }

    private static boolean shouldSkip(@NonNull Context app, @NonNull StatusBarNotification sbn) {
        if (TextUtils.equals(sbn.getPackageName(), app.getPackageName())) return true;
        Notification n = sbn.getNotification();
        if (n == null) return true;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return true;
        return false;
    }

    @Nullable
    private static Map<String, Object> buildItem(@NonNull Context app,
                                                 @NonNull StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (n == null) return null;
        Bundle extras = n.extras != null ? n.extras : Bundle.EMPTY;

        String title = firstNonEmpty(
                charSeq(extras, Notification.EXTRA_TITLE),
                charSeq(extras, Notification.EXTRA_CONVERSATION_TITLE),
                charSeq(extras, "android.title.big"),
                n.tickerText != null ? n.tickerText.toString() : ""
        );
        String message = firstNonEmpty(
                messagingText(extras),
                charSeq(extras, Notification.EXTRA_BIG_TEXT),
                charSeq(extras, Notification.EXTRA_TEXT),
                textLines(extras),
                charSeq(extras, Notification.EXTRA_SUB_TEXT),
                charSeq(extras, Notification.EXTRA_INFO_TEXT),
                charSeq(extras, Notification.EXTRA_SUMMARY_TEXT)
        );
        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(message)) return null;
        if (TextUtils.isEmpty(title)) title = resolveAppLabel(app, sbn.getPackageName());
        if (TextUtils.isEmpty(message)) message = "(notification)";

        String packageName = sbn.getPackageName() != null ? sbn.getPackageName() : "";
        long postedAt = sbn.getPostTime() > 0 ? sbn.getPostTime() : System.currentTimeMillis();
        // Stable id so updates merge instead of duplicating.
        String keySeed = packageName + "|" + sbn.getId() + "|" + String.valueOf(sbn.getTag());
        String itemId = sha256(keySeed).substring(0, 40);

        Map<String, Object> item = new HashMap<>();
        item.put("itemId", itemId);
        item.put("title", trim(title, MAX_TITLE));
        item.put("message", trim(message, MAX_TEXT));
        item.put("packageName", packageName);
        item.put("appLabel", resolveAppLabel(app, packageName));
        item.put("postedAt", postedAt);
        item.put("category", n.category != null ? n.category : "");
        item.put("ongoing", (n.flags & Notification.FLAG_ONGOING_EVENT) != 0);
        item.put("channelId", n.getChannelId() != null ? n.getChannelId() : "");
        return item;
    }

    @NonNull
    private static String messagingText(@NonNull Bundle extras) {
        try {
            Parcelable[] arr = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (arr == null || arr.length == 0) return "";
            StringBuilder sb = new StringBuilder();
            int start = Math.max(0, arr.length - 5);
            for (int i = start; i < arr.length; i++) {
                if (!(arr[i] instanceof Bundle)) continue;
                Bundle m = (Bundle) arr[i];
                CharSequence text = m.getCharSequence("text");
                CharSequence sender = m.getCharSequence("sender");
                if (TextUtils.isEmpty(text)) continue;
                if (sb.length() > 0) sb.append('\n');
                if (!TextUtils.isEmpty(sender)) {
                    sb.append(sender).append(": ");
                }
                sb.append(text);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    @NonNull
    private static String textLines(@NonNull Bundle extras) {
        try {
            CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines == null || lines.length == 0) return "";
            StringBuilder sb = new StringBuilder();
            for (CharSequence line : lines) {
                if (TextUtils.isEmpty(line)) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    @NonNull
    private static String charSeq(@NonNull Bundle extras, @NonNull String key) {
        CharSequence cs = extras.getCharSequence(key);
        return cs != null ? cs.toString() : "";
    }

    @NonNull
    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }

    private static void writeItem(@NonNull Context app, @NonNull Map<String, Object> item,
                                  boolean await) {
        long now = System.currentTimeMillis();
        long prev = lastWriteAt.get();
        if (now - prev < MIN_WRITE_GAP_MS) {
            try {
                Thread.sleep(MIN_WRITE_GAP_MS - (now - prev));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        lastWriteAt.set(System.currentTimeMillis());

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.w(TAG, "write skipped: not signed in");
            return;
        }
        String deviceId = new RemoteControlPrefs(app).getOrCreateDeviceId();
        item.put("ownerUid", user.getUid());
        item.put("deviceId", deviceId);
        item.put("syncedAt", System.currentTimeMillis());
        String itemId = String.valueOf(item.get("itemId"));
        FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(user.getUid())
                .collection(AppConstants.FIRESTORE_DEVICES)
                .document(deviceId)
                .collection(AppConstants.FIRESTORE_NOTIFICATION_ITEMS)
                .document(itemId)
                .set(item, SetOptions.merge())
                .addOnSuccessListener(v -> Log.d(TAG, "wrote " + itemId))
                .addOnFailureListener(e -> Log.w(TAG, "notif write failed", e));
    }

    @NonNull
    private static String resolveAppLabel(@NonNull Context app, @Nullable String packageName) {
        if (packageName == null || packageName.isEmpty()) return "";
        try {
            PackageManager pm = app.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(info);
            return label != null ? trim(label, 120) : packageName;
        } catch (Exception e) {
            return packageName;
        }
    }

    @NonNull
    private static String trim(@Nullable CharSequence cs, int max) {
        if (cs == null) return "";
        String s = cs.toString().trim();
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }

    @NonNull
    private static String sha256(@NonNull String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(Math.abs(input.hashCode()));
        }
    }
}
