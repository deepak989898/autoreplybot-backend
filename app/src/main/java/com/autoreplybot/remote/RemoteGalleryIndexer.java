package com.autoreplybot.remote;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** MediaStore metadata indexer. Never stores content URIs in Firestore. */
public final class RemoteGalleryIndexer {
    private static final String TAG = "RemoteGalleryIndexer";
    private static final ConcurrentHashMap<String, Uri> LOCAL_URI_BY_ITEM = new ConcurrentHashMap<>();

    private final Context app;

    public RemoteGalleryIndexer(@NonNull Context context) {
        this.app = context.getApplicationContext();
    }

    @Nullable
    public static Uri localUriForItem(@NonNull String itemId) {
        return LOCAL_URI_BY_ITEM.get(itemId);
    }

    /**
     * Resolve a gallery item URI, rebuilding the in-memory MediaStore map if the
     * process was restarted after the last index (cache is not persisted).
     */
    @Nullable
    public Uri resolveItemUri(@NonNull String itemId) {
        if (itemId.isEmpty()) return null;
        Uri cached = LOCAL_URI_BY_ITEM.get(itemId);
        if (cached != null) return cached;
        rebuildLocalUriCache();
        return LOCAL_URI_BY_ITEM.get(itemId);
    }

    /** Scan MediaStore and refill {@link #LOCAL_URI_BY_ITEM} without writing Firestore. */
    public void rebuildLocalUriCache() {
        for (Map<String, Object> item : query("all")) {
            String id = String.valueOf(item.get("itemId"));
            Object uriObj = item.get("_localUri");
            if (uriObj instanceof Uri) {
                LOCAL_URI_BY_ITEM.put(id, (Uri) uriObj);
            }
        }
    }

    public int indexAndSync(@NonNull String mediaTypeFilter) {
        List<Map<String, Object>> items = query(mediaTypeFilter);
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return items.size();
        String deviceId = new RemoteControlPrefs(app).getOrCreateDeviceId();
        WriteBatch batch = FirebaseFirestore.getInstance().batch();
        int n = 0;
        for (Map<String, Object> item : items) {
            String itemId = String.valueOf(item.get("itemId"));
            Object uriObj = item.remove("_localUri");
            if (uriObj instanceof Uri) {
                LOCAL_URI_BY_ITEM.put(itemId, (Uri) uriObj);
            }
            item.put("ownerUid", user.getUid());
            item.put("deviceId", deviceId);
            item.put("indexedAt", System.currentTimeMillis());
            item.put("deleted", false);
            batch.set(
                    FirebaseFirestore.getInstance()
                            .collection(AppConstants.FIRESTORE_USERS)
                            .document(user.getUid())
                            .collection(AppConstants.FIRESTORE_DEVICES)
                            .document(deviceId)
                            .collection(AppConstants.FIRESTORE_GALLERY_ITEMS)
                            .document(itemId),
                    item,
                    SetOptions.merge());
            if (++n >= 400) break;
        }
        if (n > 0) {
            batch.commit().addOnFailureListener(e -> Log.w(TAG, "gallery sync failed", e));
        }
        return n;
    }

    @NonNull
    private List<Map<String, Object>> query(@NonNull String mediaTypeFilter) {
        List<Map<String, Object>> out = new ArrayList<>();
        String filter = mediaTypeFilter.toLowerCase(Locale.US);
        if ("all".equals(filter) || "image".equals(filter) || filter.isEmpty()) {
            out.addAll(queryCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image"));
        }
        if ("all".equals(filter) || "video".equals(filter) || filter.isEmpty()) {
            out.addAll(queryCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video"));
        }
        if ("all".equals(filter) || "audio".equals(filter) || filter.isEmpty()) {
            out.addAll(queryCollection(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio"));
        }
        return out;
    }

    @NonNull
    private List<Map<String, Object>> queryCollection(@NonNull Uri collection, @NonNull String type) {
        List<Map<String, Object>> out = new ArrayList<>();
        String[] projection = new String[]{
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                MediaStore.MediaColumns.DURATION
        };
        try (Cursor c = app.getContentResolver().query(
                collection, projection, null, null,
                MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            if (c == null) return out;
            int limit = 200;
            while (c.moveToNext() && out.size() < limit) {
                long id = c.getLong(0);
                Uri contentUri = ContentUris.withAppendedId(collection, id);
                String hash = sha256(type + ":" + id);
                Map<String, Object> item = new HashMap<>();
                item.put("itemId", hash);
                item.put("mediaStoreIdHash", hash);
                item.put("type", type);
                item.put("displayName", c.getString(1) != null ? c.getString(1) : "");
                item.put("mimeType", c.getString(2) != null ? c.getString(2) : "");
                item.put("sizeBytes", c.isNull(3) ? 0L : c.getLong(3));
                item.put("width", c.isNull(4) ? 0 : c.getInt(4));
                item.put("height", c.isNull(5) ? 0 : c.getInt(5));
                item.put("dateAdded", c.isNull(6) ? 0L : c.getLong(6) * 1000L);
                item.put("dateModified", c.isNull(7) ? 0L : c.getLong(7) * 1000L);
                item.put("bucketName", c.getString(8) != null ? c.getString(8) : "");
                item.put("durationMs", c.isNull(9) ? 0L : c.getLong(9));
                item.put("orientation", 0);
                item.put("thumbnailStatus", "none");
                item.put("appFavorite", false);
                item.put("appTags", new ArrayList<String>());
                item.put("_localUri", contentUri);
                out.add(item);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "MediaStore permission denied", e);
        } catch (Exception e) {
            Log.w(TAG, "MediaStore query failed", e);
        }
        return out;
    }

    @NonNull
    static String sha256(@NonNull String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
