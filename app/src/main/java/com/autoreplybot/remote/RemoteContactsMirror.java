package com.autoreplybot.remote;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
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
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Syncs phone contacts (name + number) to Firestore when Contacts sharing is enabled. */
public final class RemoteContactsMirror {
    private static final String TAG = "RemoteContactsMirror";

    public static final int ERR_DISABLED = -1;
    public static final int ERR_PERMISSION = -2;
    public static final int ERR_AUTH = -3;
    public static final int ERR_WRITE = -4;

    private RemoteContactsMirror() {}

    public static boolean hasContactsPermission(@NonNull Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** @return count written or negative ERR_* */
    public static int syncAll(@NonNull Context context, int limit) {
        Context app = context.getApplicationContext();
        if (!new RemoteModulePrefs(app).isContactsSharingEnabled()) return ERR_DISABLED;
        if (!hasContactsPermission(app)) return ERR_PERMISSION;
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return ERR_AUTH;

        int max = Math.min(2000, Math.max(50, limit));
        String deviceId = new RemoteControlPrefs(app).getOrCreateDeviceId();
        WriteBatch batch = FirebaseFirestore.getInstance().batch();
        int n = 0;
        int ops = 0;
        Set<String> seen = new HashSet<>();
        ContentResolver cr = app.getContentResolver();
        String[] projection = new String[]{
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL
        };
        try (Cursor c = cr.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC")) {
            if (c == null) return 0;
            while (c.moveToNext() && n < max) {
                long contactId = c.isNull(0) ? 0L : c.getLong(0);
                String name = c.isNull(1) ? "" : c.getString(1);
                String number = c.isNull(2) ? "" : c.getString(2);
                int type = c.isNull(3) ? ContactsContract.CommonDataKinds.Phone.TYPE_OTHER : c.getInt(3);
                String label = c.isNull(4) ? "" : c.getString(4);
                String num = number != null ? number.trim() : "";
                if (TextUtils.isEmpty(num)) continue;
                String dedupe = (name != null ? name.trim() : "") + "|" + normalizeNumber(num);
                if (!seen.add(dedupe)) continue;

                String itemId = sha256("contact|" + contactId + "|" + normalizeNumber(num))
                        .substring(0, 40);
                Map<String, Object> item = new HashMap<>();
                item.put("itemId", itemId);
                item.put("contactId", contactId);
                item.put("displayName", trim(name, 120));
                item.put("number", num);
                item.put("phoneType", phoneTypeLabel(type, label));
                item.put("ownerUid", user.getUid());
                item.put("deviceId", deviceId);
                item.put("syncedAt", System.currentTimeMillis());
                batch.set(
                        FirebaseFirestore.getInstance()
                                .collection(AppConstants.FIRESTORE_USERS)
                                .document(user.getUid())
                                .collection(AppConstants.FIRESTORE_DEVICES)
                                .document(deviceId)
                                .collection(AppConstants.FIRESTORE_CONTACT_ITEMS)
                                .document(itemId),
                        item,
                        SetOptions.merge());
                n++;
                ops++;
                // Firestore batch limit is 500.
                if (ops >= 450) {
                    int ok = commitBatch(batch);
                    if (ok < 0) return ok;
                    batch = FirebaseFirestore.getInstance().batch();
                    ops = 0;
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "contacts permission denied", e);
            return ERR_PERMISSION;
        } catch (Exception e) {
            Log.w(TAG, "contacts query failed", e);
            return ERR_WRITE;
        }

        if (ops > 0) {
            int ok = commitBatch(batch);
            if (ok < 0) return ok;
        }
        return n;
    }

    @NonNull
    private static String phoneTypeLabel(int type, @Nullable String customLabel) {
        switch (type) {
            case ContactsContract.CommonDataKinds.Phone.TYPE_HOME:
                return "home";
            case ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE:
                return "mobile";
            case ContactsContract.CommonDataKinds.Phone.TYPE_WORK:
                return "work";
            case ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK:
                return "fax_work";
            case ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME:
                return "fax_home";
            case ContactsContract.CommonDataKinds.Phone.TYPE_PAGER:
                return "pager";
            case ContactsContract.CommonDataKinds.Phone.TYPE_MAIN:
                return "main";
            case ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM:
                return !TextUtils.isEmpty(customLabel) ? trim(customLabel, 40) : "custom";
            default:
                return "other";
        }
    }

    @NonNull
    private static String normalizeNumber(@NonNull String number) {
        return number.replaceAll("[^0-9+]", "");
    }

    private static int commitBatch(@NonNull WriteBatch batch) {
        AtomicReference<Exception> fail = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        batch.commit()
                .addOnSuccessListener(v -> done.countDown())
                .addOnFailureListener(e -> {
                    fail.set(e);
                    Log.w(TAG, "contacts sync write failed", e);
                    done.countDown();
                });
        try {
            if (!done.await(30, TimeUnit.SECONDS)) return ERR_WRITE;
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
