package com.autoreplybot.remote;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.autoreplybot.AppConstants;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageMetadata;
import com.google.firebase.storage.StorageReference;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Uploads remote-session captures to Firebase Storage and indexes them under
 * users/{uid}/remoteMedia/{mediaId} for the website Media Files gallery.
 */
public final class RemoteMediaCloudUploader {
    private static final String TAG = "RemoteMediaUpload";

    private RemoteMediaCloudUploader() {}

    public static final class Result {
        @NonNull public final String mediaId;
        @NonNull public final String downloadUrl;
        @NonNull public final String storagePath;

        Result(@NonNull String mediaId, @NonNull String downloadUrl, @NonNull String storagePath) {
            this.mediaId = mediaId;
            this.downloadUrl = downloadUrl;
            this.storagePath = storagePath;
        }
    }

    @WorkerThread
    @Nullable
    public static Result uploadFile(@NonNull File file,
                                    @NonNull String kind,
                                    @NonNull String contentType,
                                    @Nullable String deviceId,
                                    @Nullable String sessionId,
                                    @Nullable String clientId) {
        if (!file.exists() || file.length() <= 0) {
            Log.w(TAG, "Skip upload — missing file " + file.getAbsolutePath());
            return null;
        }
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.w(TAG, "Skip upload — not signed in");
            return null;
        }
        String uid = user.getUid();
        String ext = extensionFor(kind, contentType, file.getName());
        String mediaId = UUID.randomUUID().toString().replace("-", "");
        String storagePath = "remote_media/" + uid + "/" + mediaId + ext;
        StorageReference ref = FirebaseStorage.getInstance().getReference().child(storagePath);
        StorageMetadata metadata = new StorageMetadata.Builder()
                .setContentType(contentType)
                .setCustomMetadata("kind", kind)
                .setCustomMetadata("deviceId", deviceId != null ? deviceId : "")
                .setCustomMetadata("sessionId", sessionId != null ? sessionId : "")
                .build();
        try {
            Tasks.await(ref.putFile(Uri.fromFile(file), metadata), 5, TimeUnit.MINUTES);
            Uri download = Tasks.await(ref.getDownloadUrl(), 60, TimeUnit.SECONDS);
            String downloadUrl = download != null ? download.toString() : "";
            long now = System.currentTimeMillis();
            Map<String, Object> doc = new HashMap<>();
            doc.put("mediaId", mediaId);
            doc.put("ownerUid", uid);
            doc.put("deviceId", deviceId != null ? deviceId : "");
            doc.put("sessionId", sessionId != null ? sessionId : "");
            doc.put("clientId", clientId != null ? clientId : "");
            doc.put("kind", kind);
            doc.put("fileName", file.getName());
            doc.put("contentType", contentType);
            doc.put("storagePath", storagePath);
            doc.put("downloadUrl", downloadUrl);
            doc.put("sizeBytes", file.length());
            doc.put("createdAt", now);
            doc.put("updatedAt", now);
            doc.put("revoked", false);
            Tasks.await(FirebaseFirestore.getInstance()
                    .collection(AppConstants.FIRESTORE_USERS)
                    .document(uid)
                    .collection(AppConstants.FIRESTORE_REMOTE_MEDIA)
                    .document(mediaId)
                    .set(doc, SetOptions.merge()), 60, TimeUnit.SECONDS);
            Log.i(TAG, "Uploaded " + kind + " mediaId=" + mediaId);
            return new Result(mediaId, downloadUrl, storagePath);
        } catch (Exception e) {
            Log.e(TAG, "Upload failed for " + file.getAbsolutePath(), e);
            return null;
        }
    }

    @NonNull
    private static String extensionFor(@NonNull String kind,
                                       @NonNull String contentType,
                                       @NonNull String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return ".jpg";
        if (lower.endsWith(".png")) return ".png";
        if (lower.endsWith(".mp4")) return ".mp4";
        if (lower.endsWith(".m4a")) return ".m4a";
        if (lower.endsWith(".aac")) return ".aac";
        if (contentType.startsWith("image/")) return ".jpg";
        if (contentType.startsWith("video/")) return ".mp4";
        if (contentType.startsWith("audio/")) return ".m4a";
        if ("photo".equals(kind)) return ".jpg";
        if ("video".equals(kind)) return ".mp4";
        return ".bin";
    }
}
