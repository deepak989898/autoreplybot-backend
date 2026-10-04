package com.autoreplybot.remote;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.autoreplybot.AppConstants;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.WriteBatch;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Syncs call log entries to Firestore when Call Logs sharing is enabled. */
public final class RemoteCallLogMirror {
    private static final String TAG = "RemoteCallLogMirror";

    public static final int ERR_DISABLED = -1;
    public static final int ERR_PERMISSION = -2;
    public static final int ERR_AUTH = -3;
    public static final int ERR_WRITE = -4;

    private RemoteCallLogMirror() {}

    public static boolean hasCallLogPermission(@NonNull Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** @return count written or negative ERR_* */
    public static int syncRecent(@NonNull Context context, int limit) {
        Context app = context.getApplicationContext();
        if (!new RemoteModulePrefs(app).isCallLogsSharingEnabled()) return ERR_DISABLED;
        if (!hasCallLogPermission(app)) return ERR_PERMISSION;
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return ERR_AUTH;

        int max = Math.min(300, Math.max(20, limit));
        String deviceId = new RemoteControlPrefs(app).getOrCreateDeviceId();
        WriteBatch batch = FirebaseFirestore.getInstance().batch();
        int n = 0;
        ContentResolver cr = app.getContentResolver();
        String[] projection = new String[]{
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.NEW,
                CallLog.Calls.GEOCODED_LOCATION
        };
        try (Cursor c = cr.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                CallLog.Calls.DATE + " DESC")) {
            if (c == null) return 0;
            while (c.moveToNext() && n < max) {
                long callId = c.getLong(0);
                String number = c.isNull(1) ? "" : c.getString(1);
                String cachedName = c.isNull(2) ? "" : c.getString(2);
                int type = c.isNull(3) ? CallLog.Calls.INCOMING_TYPE : c.getInt(3);
                long date = c.isNull(4) ? 0L : c.getLong(4);
                long durationSec = c.isNull(5) ? 0L : c.getLong(5);
                boolean isNew = !c.isNull(6) && c.getInt(6) == 1;
                String geo = c.isNull(7) ? "" : c.getString(7);
                if (TextUtils.isEmpty(number) && TextUtils.isEmpty(cachedName)) continue;

                String contactName = !TextUtils.isEmpty(cachedName)
                        ? cachedName.trim()
                        : lookupContactName(app, number);
                String itemId = sha256("call|" + callId).substring(0, 40);
                Map<String, Object> item = new HashMap<>();
                item.put("itemId", itemId);
                item.put("callId", callId);
                item.put("number", number != null ? number.trim() : "");
                item.put("contactName", trim(contactName, 120));
                item.put("callType", typeLabel(type));
                item.put("callTypeCode", type);
                item.put("date", date);
                item.put("durationSec", durationSec);
                item.put("isNew", isNew);
                item.put("geo", trim(geo, 80));
                item.put("ownerUid", user.getUid());
                item.put("deviceId", deviceId);
                item.put("syncedAt", System.currentTimeMillis());
                batch.set(
                        FirebaseFirestore.getInstance()
                                .collection(AppConstants.FIRESTORE_USERS)
                                .document(user.getUid())
                                .collection(AppConstants.FIRESTORE_DEVICES)
                                .document(deviceId)
                                .collection(AppConstants.FIRESTORE_CALL_LOG_ITEMS)
                                .document(itemId),
                        item,
                        SetOptions.merge());
                n++;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "call log permission denied", e);
            return ERR_PERMISSION;
        } catch (Exception e) {
            Log.w(TAG, "call log query failed", e);
            return ERR_WRITE;
        }

        if (n == 0) return 0;
        int ok = commitBatch(batch);
        return ok < 0 ? ok : n;
    }

    @NonNull
    private static String typeLabel(int type) {
        switch (type) {
            case CallLog.Calls.OUTGOING_TYPE:
                return "outgoing";
            case CallLog.Calls.MISSED_TYPE:
                return "missed";
            case CallLog.Calls.VOICEMAIL_TYPE:
                return "voicemail";
            case CallLog.Calls.REJECTED_TYPE:
                return "rejected";
            case CallLog.Calls.BLOCKED_TYPE:
                return "blocked";
            case CallLog.Calls.ANSWERED_EXTERNALLY_TYPE:
                return "answered_externally";
            case CallLog.Calls.INCOMING_TYPE:
            default:
                return "incoming";
        }
    }

    @NonNull
    private static String lookupContactName(@NonNull Context app, @Nullable String number) {
        if (TextUtils.isEmpty(number)) return "";
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            return "";
        }
        try {
            android.net.Uri uri = android.net.Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    android.net.Uri.encode(number));
            try (Cursor c = app.getContentResolver().query(
                    uri,
                    new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME},
                    null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    String name = c.getString(0);
                    return name != null ? trim(name, 120) : "";
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static int commitBatch(@NonNull WriteBatch batch) {
        AtomicReference<Exception> fail = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        batch.commit()
                .addOnSuccessListener(v -> done.countDown())
                .addOnFailureListener(e -> {
                    fail.set(e);
                    Log.w(TAG, "call log sync write failed", e);
                    done.countDown();
                });
        try {
            if (!done.await(25, TimeUnit.SECONDS)) return ERR_WRITE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ERR_WRITE;
        }
        return fail.get() != null ? ERR_WRITE : 1;
    }

    @NonNull
    private static String trim(@Nullable String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    @NonNull
    private static String sha256(@NonNull String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(Math.abs(input.hashCode()));
        }
    }
}
