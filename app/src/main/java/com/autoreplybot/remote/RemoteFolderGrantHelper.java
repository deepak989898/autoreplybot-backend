package com.autoreplybot.remote;

import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import com.autoreplybot.AppConstants;
import com.autoreplybot.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persists SAF folder grants and publishes them to Firestore. */
public final class RemoteFolderGrantHelper {
    public static final String EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents";
    public static final String PRIMARY_DOC_ID = "primary:";
    public static final String DEFAULT_INTERNAL_GRANT_ID = "internal_primary";

    private RemoteFolderGrantHelper() {}

    /** SAF tree URI for the device internal shared storage root. */
    @NonNull
    public static Uri getDefaultInternalStorageTreeUri() {
        return DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_AUTHORITY, PRIMARY_DOC_ID);
    }

    public static boolean isPrimaryStorageTree(@Nullable Uri uri) {
        if (uri == null) return false;
        String s = uri.toString().toLowerCase();
        return s.contains("com.android.externalstorage.documents")
                && (s.contains("primary%3a") || s.contains("primary:"));
    }

    /** Arms accessibility auto-confirm before opening the internal-storage folder picker. */
    public static void prepareDefaultInternalStoragePicker() {
        RemoteFolderGrantAutoApprove.arm(35_000L);
    }

    /**
     * Re-links a previously granted primary-storage SAF tree from system persisted
     * permissions into local prefs when missing.
     */
    public static boolean restoreDefaultGrantIfPersisted(@NonNull Context context) {
        if (hasValidFolderAccess(context)) return true;
        for (UriPermission perm : context.getContentResolver().getPersistedUriPermissions()) {
            if (!perm.isReadPermission()) continue;
            Uri uri = perm.getUri();
            if (!isPrimaryStorageTree(uri)) continue;
            storeGrant(context, uri, DEFAULT_INTERNAL_GRANT_ID,
                    context.getString(R.string.remote_files_internal_storage), false);
            return hasValidFolderAccess(context);
        }
        return false;
    }

    /** True when at least one stored folder grant is readable via SAF. */
    public static boolean hasValidFolderAccess(@NonNull Context context) {
        RemoteModulePrefs prefs = new RemoteModulePrefs(context);
        RemoteFileManagerHelper helper = new RemoteFileManagerHelper(context);
        for (String grantId : prefs.listFolderGrantIds()) {
            if (helper.rootForGrant(grantId) != null) return true;
        }
        return false;
    }

    /** Take persistable URI permission, store locally, enable file manager, sync to cloud. */
    public static void grantFolder(@NonNull Context context, @NonNull Uri uri) {
        String grantId = isPrimaryStorageTree(uri)
                ? DEFAULT_INTERNAL_GRANT_ID
                : UUID.randomUUID().toString().replace("-", "");
        DocumentFile root = DocumentFile.fromTreeUri(context, uri);
        String defaultLabel = context.getString(R.string.remote_files_internal_storage);
        String label = root != null && root.getName() != null && !root.getName().isEmpty()
                ? root.getName()
                : defaultLabel;
        if (isPrimaryStorageTree(uri)) {
            label = defaultLabel;
        }
        storeGrant(context, uri, grantId, label, true);
    }

    private static void storeGrant(@NonNull Context context,
                                   @NonNull Uri uri,
                                   @NonNull String grantId,
                                   @NonNull String label,
                                   boolean takePersistable) {
        if (takePersistable) {
            final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            try {
                context.getContentResolver().takePersistableUriPermission(uri, takeFlags);
            } catch (SecurityException ignored) {
                // Already persisted or picker did not return persistable flags.
            }
        }

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
