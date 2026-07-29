package com.autoreplybot.remote;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;

import com.autoreplybot.AppConstants;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Persists SAF folder grants and publishes them to Firestore. */
public final class RemoteFolderGrantHelper {
    private RemoteFolderGrantHelper() {}

    /** Take persistable URI permission, store locally, enable file manager, sync to cloud. */
    public static void grantFolder(@NonNull Context context, @NonNull Uri uri) {
        final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        context.getContentResolver().takePersistableUriPermission(uri, takeFlags);

        DocumentFile root = DocumentFile.fromTreeUri(context, uri);
        String label = root != null && root.getName() != null ? root.getName() : "Folder";
        String grantId = UUID.randomUUID().toString().replace("-", "");

        RemoteModulePrefs modulePrefs = new RemoteModulePrefs(context);
        modulePrefs.putFolderUri(grantId, uri.toString());
        modulePrefs.setFileManagerEnabled(true);
        new RemoteDeviceInfoRepository(context).publishModuleFlags();

        publishFolderGrant(context, grantId, label, RemoteFileManagerHelper.uriHash(uri.toString()));
    }

    private static void publishFolderGrant(@NonNull Context context,
                                           @NonNull String grantId,
                                           @NonNull String label,
                                           @NonNull String uriHash) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        String deviceId = new RemoteControlPrefs(context).getOrCreateDeviceId();
        Map<String, Object> doc = new HashMap<>();
        doc.put("ownerUid", user.getUid());
        doc.put("deviceId", deviceId);
        doc.put("grantId", grantId);
        doc.put("displayName", label);
        doc.put("accessMode", "read_write");
        doc.put("uriHash", uriHash);
        doc.put("connected", true);
        doc.put("updatedAt", System.currentTimeMillis());
        FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(user.getUid())
                .collection(AppConstants.FIRESTORE_DEVICES)
                .document(deviceId)
                .collection(AppConstants.FIRESTORE_FOLDER_GRANTS)
                .document(grantId)
                .set(doc);
    }
}
