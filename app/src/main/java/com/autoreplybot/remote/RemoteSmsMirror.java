package com.autoreplybot.remote;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.Telephony;
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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Syncs SMS inbox/sent to Firestore when Messages Sharing is enabled. */
public final class RemoteSmsMirror {
    private static final String TAG = "RemoteSmsMirror";
    private static final int MAX_BODY = 4000;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    public static final int ERR_DISABLED = -1;
    public static final int ERR_PERMISSION = -2;
    public static final int ERR_AUTH = -3;
    public static final int ERR_WRITE = -4;

    private RemoteSmsMirror() {}

    public static boolean hasSmsPermission(@NonNull Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static void onIncomingSms(@NonNull Context context,
                                     @Nullable String address,
                                     @Nullable String body,
                                     long dateMs) {
        Context app = context.getApplicationContext();
        if (!new RemoteModulePrefs(app).isMessagesSharingEnabled()) return;
        if (!hasSmsPermission(app)) return;
        if (TextUtils.isEmpty(address) && TextUtils.isEmpty(body)) return;
        Map<String, Object> item = new HashMap<>();
        String addr = address != null ? address.trim() : "";
        String text = body != null ? body : "";
        long when = dateMs > 0 ? dateMs : System.currentTimeMillis();
        String itemId = sha256("live|" + addr + "|" + when + "|" + text).substring(0, 40);
        item.put("itemId", itemId);
        item.put("address", addr);
        item.put("senderName", lookupContactName(app, addr));
        item.put("body", trim(text, MAX_BODY));
        item.put("date", when);
        item.put("type", "inbox");
        item.put("read", false);
        item.put("threadId", "");
        item.put("live", true);
        IO.execute(() -> writeItem(app, item));
    }

    /** @return count written or negative ERR_* */
    public static int syncInbox(@NonNull Context context, int limit) {
        Context app = context.getApplicationContext();
        if (!new RemoteModulePrefs(app).isMessagesSharingEnabled()) return ERR_DISABLED;
        if (!hasSmsPermission(app)) return ERR_PERMISSION;
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return ERR_AUTH;

        int max = Math.min(200, Math.max(20, limit));
        String deviceId = new RemoteControlPrefs(app).getOrCreateDeviceId();
        WriteBatch batch = FirebaseFirestore.getInstance().batch();
        int n = 0;
        ContentResolver cr = app.getContentResolver();
        String[] projection = new String[]{
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.READ,
                Telephony.Sms.THREAD_ID
        };
        try (Cursor c = cr.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                null,
                null,
                Telephony.Sms.DATE + " DESC")) {
            if (c == null) return 0;
            while (c.moveToNext() && n < max) {
                long smsId = c.getLong(0);
                String address = c.isNull(1) ? "" : c.getString(1);
                String body = c.isNull(2) ? "" : c.getString(2);
                long date = c.isNull(3) ? 0L : c.getLong(3);
                int type = c.isNull(4) ? Telephony.Sms.MESSAGE_TYPE_INBOX : c.getInt(4);
                boolean read = !c.isNull(5) && c.getInt(5) == 1;
                String threadId = c.isNull(6) ? "" : String.valueOf(c.getLong(6));
                if (TextUtils.isEmpty(address) && TextUtils.isEmpty(body)) continue;

                String itemId = sha256("sms|" + smsId).substring(0, 40);
                Map<String, Object> item = new HashMap<>();
                item.put("itemId", itemId);
                item.put("smsId", smsId);
                item.put("address", address != null ? address : "");
                item.put("senderName", lookupContactName(app, address));
                item.put("body", trim(body, MAX_BODY));
                item.put("date", date);
                item.put("type", typeLabel(type));
                item.put("read", read);
                item.put("threadId", threadId);
                item.put("ownerUid", user.getUid());
                item.put("deviceId", deviceId);
                item.put("syncedAt", System.currentTimeMillis());
                item.put("live", false);
                batch.set(
                        FirebaseFirestore.getInstance()
                                .collection(AppConstants.FIRESTORE_USERS)
                                .document(user.getUid())
                                .collection(AppConstants.FIRESTORE_DEVICES)
                                .document(deviceId)
                                .collection(AppConstants.FIRESTORE_MESSAGE_ITEMS)
                                .document(itemId),
                        item,
                        SetOptions.merge());
                n++;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "SMS permission denied", e);
            return ERR_PERMISSION;
        } catch (Exception e) {
            Log.w(TAG, "SMS query failed", e);
            return ERR_WRITE;
        }

        if (n == 0) return 0;
        AtomicReference<Exception> fail = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        batch.commit()
                .addOnSuccessListener(v -> done.countDown())
                .addOnFailureListener(e -> {
                    fail.set(e);
                    Log.w(TAG, "SMS sync write failed", e);
                    done.countDown();
                });
        try {
            if (!done.await(25, TimeUnit.SECONDS)) return ERR_WRITE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ERR_WRITE;
        }
        return fail.get() != null ? ERR_WRITE : n;
    }

    private static void writeItem(@NonNull Context app, @NonNull Map<String, Object> item) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
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
                .collection(AppConstants.FIRESTORE_MESSAGE_ITEMS)
                .document(itemId)
                .set(item, SetOptions.merge())
                .addOnFailureListener(e -> Log.w(TAG, "live SMS write failed", e));
    }

    @NonNull
    private static String typeLabel(int type) {
        switch (type) {
            case Telephony.Sms.MESSAGE_TYPE_SENT: return "sent";
            case Telephony.Sms.MESSAGE_TYPE_DRAFT: return "draft";
            case Telephony.Sms.MESSAGE_TYPE_OUTBOX: return "outbox";
            case Telephony.Sms.MESSAGE_TYPE_FAILED: return "failed";
            case Telephony.Sms.MESSAGE_TYPE_QUEUED: return "queued";
            default: return "inbox";
        }
    }

    @NonNull
    private static String lookupContactName(@NonNull Context app, @Nullable String address) {
        if (TextUtils.isEmpty(address)) return "";
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            return "";
        }
        try {
            Uri uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(address));
            try (Cursor c = app.getContentResolver().query(
                    uri,
                    new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME},
                    null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    String name = c.getString(0);
                    return name != null ? trim(name, 120) : "";
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "contact lookup failed");
        }
        return "";
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
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(Math.abs(input.hashCode()));
        }
    }
}
