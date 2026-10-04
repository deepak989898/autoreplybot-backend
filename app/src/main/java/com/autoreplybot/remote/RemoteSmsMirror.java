package com.autoreplybot.remote;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
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
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.WriteBatch;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        if (isTombstonedLocal(app, itemId, 0L)) return;
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
        Set<String> deletedItemIds = loadDeletedItemIds(app, user.getUid(), deviceId);
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
                if (deletedItemIds.contains(itemId) || isTombstonedLocal(app, itemId, smsId)) {
                    continue;
                }
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

    /**
     * Delete SMS on device (best-effort) + cloud cache + tombstones so sync won't restore them.
     * @param items list of maps with itemId / smsId / address / body / date
     * @return number deleted from provider (or processed), or ERR_*
     */
    public static int deleteMessages(@NonNull Context context, @NonNull List<Object> items) {
        Context app = context.getApplicationContext();
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return ERR_AUTH;
        String deviceId = new RemoteControlPrefs(app).getOrCreateDeviceId();
        ContentResolver cr = app.getContentResolver();
        int deviceDeleted = 0;
        WriteBatch batch = FirebaseFirestore.getInstance().batch();
        int ops = 0;
        long now = System.currentTimeMillis();

        for (Object raw : items) {
            if (!(raw instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) raw;
            String itemId = String.valueOf(row.get("itemId") != null ? row.get("itemId") : "").trim();
            if (itemId.isEmpty()) continue;
            long smsId = RemoteMapValues.longValue(row, "smsId", 0L);
            String address = String.valueOf(row.get("address") != null ? row.get("address") : "");
            String body = String.valueOf(row.get("body") != null ? row.get("body") : "");
            long date = RemoteMapValues.longValue(row, "date", 0L);

            rememberTombstoneLocal(app, itemId, smsId);

            boolean removed = false;
            if (smsId > 0) {
                removed = tryDeleteSmsById(cr, smsId);
            }
            if (!removed && (!TextUtils.isEmpty(address) || !TextUtils.isEmpty(body))) {
                removed = tryDeleteSmsByMatch(cr, address, body, date);
            }
            if (removed) deviceDeleted++;

            Map<String, Object> tomb = new HashMap<>();
            tomb.put("itemId", itemId);
            tomb.put("smsId", smsId > 0 ? smsId : 0L);
            tomb.put("address", address);
            tomb.put("date", date);
            tomb.put("deletedAt", now);
            batch.set(
                    FirebaseFirestore.getInstance()
                            .collection(AppConstants.FIRESTORE_USERS)
                            .document(user.getUid())
                            .collection(AppConstants.FIRESTORE_DEVICES)
                            .document(deviceId)
                            .collection(AppConstants.FIRESTORE_MESSAGE_DELETED)
                            .document(itemId),
                    tomb,
                    SetOptions.merge());
            batch.delete(
                    FirebaseFirestore.getInstance()
                            .collection(AppConstants.FIRESTORE_USERS)
                            .document(user.getUid())
                            .collection(AppConstants.FIRESTORE_DEVICES)
                            .document(deviceId)
                            .collection(AppConstants.FIRESTORE_MESSAGE_ITEMS)
                            .document(itemId));
            ops += 2;
            if (ops >= 400) {
                if (!commitBatch(batch)) return ERR_WRITE;
                batch = FirebaseFirestore.getInstance().batch();
                ops = 0;
            }
        }
        if (ops > 0 && !commitBatch(batch)) return ERR_WRITE;
        return deviceDeleted;
    }

    private static boolean tryDeleteSmsById(@NonNull ContentResolver cr, long smsId) {
        try {
            Uri uri = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, smsId);
            int n = cr.delete(uri, null, null);
            return n > 0;
        } catch (SecurityException e) {
            Log.w(TAG, "SMS delete blocked (need default SMS app on some phones)", e);
            return false;
        } catch (Exception e) {
            Log.w(TAG, "SMS delete by id failed", e);
            return false;
        }
    }

    private static boolean tryDeleteSmsByMatch(@NonNull ContentResolver cr,
                                               @NonNull String address,
                                               @NonNull String body,
                                               long date) {
        try {
            String selection;
            String[] args;
            if (date > 0 && !TextUtils.isEmpty(address)) {
                selection = Telephony.Sms.ADDRESS + "=? AND " + Telephony.Sms.DATE + "=?";
                args = new String[]{address, String.valueOf(date)};
            } else if (!TextUtils.isEmpty(address) && !TextUtils.isEmpty(body)) {
                selection = Telephony.Sms.ADDRESS + "=? AND " + Telephony.Sms.BODY + "=?";
                args = new String[]{address, body};
            } else {
                return false;
            }
            int n = cr.delete(Telephony.Sms.CONTENT_URI, selection, args);
            return n > 0;
        } catch (SecurityException e) {
            Log.w(TAG, "SMS match-delete blocked", e);
            return false;
        } catch (Exception e) {
            Log.w(TAG, "SMS match-delete failed", e);
            return false;
        }
    }

    @NonNull
    private static Set<String> loadDeletedItemIds(@NonNull Context app,
                                                  @NonNull String uid,
                                                  @NonNull String deviceId) {
        Set<String> out = new HashSet<>(loadLocalTombstoneIds(app));
        AtomicReference<QuerySnapshot> snapRef = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(uid)
                .collection(AppConstants.FIRESTORE_DEVICES)
                .document(deviceId)
                .collection(AppConstants.FIRESTORE_MESSAGE_DELETED)
                .limit(500)
                .get()
                .addOnSuccessListener(s -> {
                    snapRef.set(s);
                    done.countDown();
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "load deleted tombstones failed", e);
                    done.countDown();
                });
        try {
            done.await(12, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        QuerySnapshot snap = snapRef.get();
        if (snap != null) {
            for (DocumentSnapshot d : snap.getDocuments()) {
                out.add(d.getId());
            }
        }
        return out;
    }

    private static void rememberTombstoneLocal(@NonNull Context app, @NonNull String itemId, long smsId) {
        android.content.SharedPreferences p =
                app.getSharedPreferences("remote_sms_deleted", Context.MODE_PRIVATE);
        Set<String> ids = new HashSet<>(p.getStringSet("itemIds", new HashSet<>()));
        ids.add(itemId);
        if (ids.size() > 800) {
            List<String> list = new ArrayList<>(ids);
            ids = new HashSet<>(list.subList(list.size() - 600, list.size()));
        }
        android.content.SharedPreferences.Editor ed = p.edit().putStringSet("itemIds", ids);
        if (smsId > 0) {
            Set<String> sms = new HashSet<>(p.getStringSet("smsIds", new HashSet<>()));
            sms.add(String.valueOf(smsId));
            ed.putStringSet("smsIds", sms);
        }
        ed.apply();
    }

    private static boolean isTombstonedLocal(@NonNull Context app, @NonNull String itemId, long smsId) {
        android.content.SharedPreferences p =
                app.getSharedPreferences("remote_sms_deleted", Context.MODE_PRIVATE);
        Set<String> ids = p.getStringSet("itemIds", null);
        if (ids != null && ids.contains(itemId)) return true;
        if (smsId > 0) {
            Set<String> sms = p.getStringSet("smsIds", null);
            return sms != null && sms.contains(String.valueOf(smsId));
        }
        return false;
    }

    @NonNull
    private static Set<String> loadLocalTombstoneIds(@NonNull Context app) {
        android.content.SharedPreferences p =
                app.getSharedPreferences("remote_sms_deleted", Context.MODE_PRIVATE);
        Set<String> ids = p.getStringSet("itemIds", null);
        return ids != null ? new HashSet<>(ids) : new HashSet<>();
    }

    private static boolean commitBatch(@NonNull WriteBatch batch) {
        AtomicReference<Exception> fail = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        batch.commit()
                .addOnSuccessListener(v -> done.countDown())
                .addOnFailureListener(e -> {
                    fail.set(e);
                    done.countDown();
                });
        try {
            if (!done.await(25, TimeUnit.SECONDS)) return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return fail.get() == null;
    }

    private static void writeItem(@NonNull Context app, @NonNull Map<String, Object> item) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        String deviceId = new RemoteControlPrefs(app).getOrCreateDeviceId();
        item.put("ownerUid", user.getUid());
        item.put("deviceId", deviceId);
        item.put("syncedAt", System.currentTimeMillis());
        String itemId = String.valueOf(item.get("itemId"));
        if (isTombstonedLocal(app, itemId, 0L)) return;
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
