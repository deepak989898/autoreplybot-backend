package com.autoreplybot.remote;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import com.autoreplybot.AppConstants;
import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageMetadata;
import com.google.firebase.storage.StorageReference;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Executes validated module commands from the dashboard. */
public final class RemoteModuleCommandExecutor {
    private static final String TAG = "RemoteModuleExec";
    private final Context app;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    public RemoteModuleCommandExecutor(@NonNull Context context) {
        this.app = context.getApplicationContext();
    }

    public void execute(@NonNull RemoteModuleCommandAction action,
                        @NonNull Map<String, Object> payload,
                        @NonNull String commandId,
                        @NonNull Ack ack) {
        io.execute(() -> {
            try {
                switch (action) {
                    case DEVICE_INFO_REFRESH:
                    case STATUS_REFRESH:
                        refreshDeviceInfo(ack);
                        break;
                    case LOCATION_GET_CURRENT:
                        getCurrentLocation(ack);
                        break;
                    case LOCATION_START_LIVE:
                        startLive(payload, ack);
                        break;
                    case LOCATION_STOP:
                        RemoteLocationSharingService.stop(app);
                        ack.ok("stopped");
                        break;
                    case GALLERY_INDEX:
                    case GALLERY_METADATA:
                        indexGallery(payload, ack);
                        break;
                    case GALLERY_TRANSFER_REQUEST:
                        galleryTransfer(payload, ack);
                        break;
                    case GALLERY_DELETE_REQUEST:
                        ack.fail("REQUIRES_USER_CONFIRMATION",
                                "Gallery deletion must be confirmed on the phone");
                        break;
                    case NOTIFICATIONS_SYNC:
                        syncNotifications(ack);
                        break;
                    case MESSAGES_SYNC:
                        syncMessages(payload, ack);
                        break;
                    case APPS_INDEX:
                        indexApps(ack);
                        break;
                    case APP_BLOCK:
                        appBlock(payload, ack);
                        break;
                    case APP_UNBLOCK:
                        appUnblock(payload, ack);
                        break;
                    case APP_BLOCKS_SYNC:
                        appBlocksSync(ack);
                        break;
                    case SCREEN_RECORD_START:
                        screenRecordStart(payload, ack);
                        break;
                    case SCREEN_RECORD_STOP:
                        RemoteScreenRecordService.stop(app);
                        ack.ok("stop_requested");
                        break;
                    case SCREEN_RECORD_PAUSE:
                        RemoteScreenRecordService.pause(app);
                        ack.ok("pause_requested");
                        break;
                    case SCREEN_RECORD_RESUME:
                        RemoteScreenRecordService.resume(app);
                        ack.ok("resume_requested");
                        break;
                    case FILE_LIST:
                        fileList(payload, ack);
                        break;
                    case FILE_DOWNLOAD_REQUEST:
                        fileDownload(payload, ack);
                        break;
                    case FILE_CREATE_FOLDER:
                        fileCreateFolder(payload, ack);
                        break;
                    case FILE_RENAME:
                        fileRename(payload, ack);
                        break;
                    case FILE_COPY:
                        fileCopy(payload, ack);
                        break;
                    case FILE_DELETE:
                        fileDelete(payload, ack);
                        break;
                    case FILE_CANCEL_TRANSFER:
                        cancelTransfer(payload, ack);
                        break;
                    case FILE_UPLOAD_PREPARE:
                    case FILE_UPLOAD_COMMIT:
                    case FILE_MOVE:
                    case FILE_METADATA:
                    case FILE_PREVIEW:
                        ack.fail("NOT_IMPLEMENTED_YET", "Handled in a follow-up hardening pass");
                        break;
                    default:
                        ack.fail("UNKNOWN_ACTION", "Unsupported action");
                }
            } catch (Exception e) {
                Log.w(TAG, "command failed " + action, e);
                ack.fail("EXEC_FAILED", e.getMessage() != null ? e.getMessage() : "failed");
            }
        });
    }

    private void refreshDeviceInfo(@NonNull Ack ack) {
        Map<String, Object> info = RemoteDeviceInfoCollector.collect(app);
        new RemoteDeviceInfoRepository(app).publishCurrent(info, () -> ack.ok("refreshed"));
    }

    private void getCurrentLocation(@NonNull Ack ack) {
        RemoteModulePrefs prefs = new RemoteModulePrefs(app);
        if (!prefs.isLocationSharingEnabled()) {
            ack.fail("LOCATION_DISABLED", "Location sharing is disabled on the phone.");
            return;
        }
        FusedLocationProviderClient fused = LocationServices.getFusedLocationProviderClient(app);
        CurrentLocationRequest req = new CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setMaxUpdateAgeMillis(60_000L)
                .build();
        try {
            fused.getCurrentLocation(req, null)
                    .addOnSuccessListener(loc -> {
                        if (loc == null) {
                            ack.fail("NO_LOCATION", "No location available");
                            return;
                        }
                        boolean approx = androidx.core.content.ContextCompat.checkSelfPermission(
                                app, android.Manifest.permission.ACCESS_FINE_LOCATION)
                                != android.content.pm.PackageManager.PERMISSION_GRANTED;
                        new RemoteLocationRepository(app).writeCurrent(
                                loc, prefs.getLocationMode(), null, null, approx);
                        ack.ok("location_written");
                    })
                    .addOnFailureListener(e -> ack.fail("LOCATION_FAILED", e.getMessage()));
        } catch (SecurityException e) {
            ack.fail("PERMISSION_DENIED", "Location permission not granted");
        }
    }

    private void startLive(@NonNull Map<String, Object> payload, @NonNull Ack ack) {
        RemoteModulePrefs prefs = new RemoteModulePrefs(app);
        if (!prefs.isLocationSharingEnabled()) {
            ack.fail("LOCATION_DISABLED", "Location sharing is disabled on the phone.");
            return;
        }
        long duration = RemoteMapValues.longValue(payload, "durationMs", prefs.getLiveDurationMs());
        RemoteLocationSharingService.start(app, duration, null);
        ack.ok("live_started");
    }

    private void indexGallery(@NonNull Map<String, Object> payload, @NonNull Ack ack) {
        if (!new RemoteModulePrefs(app).isGalleryEnabled()) {
            ack.fail("GALLERY_DISABLED", "Gallery access is disabled on the phone.");
            return;
        }
        String type = RemoteMapValues.string(payload, "mediaType");
        if (type.isEmpty()) type = "all";
        int n = new RemoteGalleryIndexer(app).indexAndSync(type);
        ack.ok("indexed:" + n);
    }

    private void syncNotifications(@NonNull Ack ack) {
        RemoteNotificationMirror.ensureSharingEnabledIfListenerReady(app);
        RemoteModulePrefs prefs = new RemoteModulePrefs(app);
        if (!prefs.isNotificationMirrorEnabled()) {
            ack.fail("NOTIFICATIONS_DISABLED",
                    "Notification Access is off. On the phone open Remote Camera & Voice → Permissions → tap Notification access and enable AutoReplyBot.");
            return;
        }
        int n = RemoteNotificationMirror.syncActive(app);
        if (n == RemoteNotificationMirror.ERR_DISABLED) {
            ack.fail("NOTIFICATIONS_DISABLED",
                    "Notification Access is off. Phone → Permissions → Notification access.");
        } else if (n == RemoteNotificationMirror.ERR_LISTENER) {
            ack.fail("LISTENER_NOT_CONNECTED",
                    "Notification Access is not connected. Phone → Permissions → Notification access → turn AutoReplyBot off/on.");
        } else if (n == RemoteNotificationMirror.ERR_AUTH) {
            ack.fail("NOT_SIGNED_IN", "Phone is not signed in to Firebase.");
        } else if (n == RemoteNotificationMirror.ERR_WRITE) {
            ack.fail("WRITE_FAILED",
                    "Could not save notifications (Firestore write failed).");
        } else {
            ack.ok("synced:" + Math.max(0, n));
        }
    }

    private void indexApps(@NonNull Ack ack) {
        RemoteModulePrefs prefs = new RemoteModulePrefs(app);
        if (!prefs.isInstalledAppsSharingEnabled()) {
            prefs.setInstalledAppsSharingEnabled(true);
            new RemoteDeviceInfoRepository(app).publishModuleFlags();
        }
        int n = new RemoteInstalledAppsIndexer(app).indexAndSync();
        if (n == RemoteInstalledAppsIndexer.ERR_DISABLED) {
            ack.fail("APPS_DISABLED", "Installed apps sharing is disabled on the phone.");
        } else if (n == RemoteInstalledAppsIndexer.ERR_AUTH) {
            ack.fail("NOT_SIGNED_IN", "Phone is not signed in.");
        } else if (n == RemoteInstalledAppsIndexer.ERR_WRITE) {
            ack.fail("WRITE_FAILED", "Could not save installed apps list.");
        } else {
            ack.ok("indexed:" + Math.max(0, n));
        }
    }

    private void appBlock(@NonNull Map<String, Object> payload, @NonNull Ack ack) {
        String packageName = RemoteMapValues.string(payload, "packageName");
        String appName = RemoteMapValues.string(payload, "appName");
        String mode = RemoteMapValues.string(payload, "mode");
        long durationMs = RemoteMapValues.longValue(payload, "durationMs", 30L * 60L * 1000L);
        if (packageName.isEmpty()) {
            ack.fail("BAD_PAYLOAD", "packageName required");
            return;
        }
        RemoteAppBlockManager mgr = new RemoteAppBlockManager(app);
        int code;
        if (RemoteAppBlockStore.PACKAGE_CAMERA_HW.equals(packageName)
                || RemoteAppBlockStore.MODE_CAMERA_HW.equals(mode)) {
            code = mgr.blockCameraHardware(durationMs);
        } else {
            code = mgr.blockApp(packageName, appName, durationMs);
        }
        if (code == RemoteAppBlockManager.OK) {
            ack.ok("blocked:" + packageName);
        } else if (code == RemoteAppBlockManager.ERR_ACCESSIBILITY) {
            ack.fail("ACCESSIBILITY_REQUIRED",
                    "Enable App Control accessibility on the phone: Permissions → App Control.");
        } else if (code == RemoteAppBlockManager.ERR_DEVICE_ADMIN) {
            ack.fail("DEVICE_ADMIN_REQUIRED",
                    "Enable Device Admin on the phone for camera hardware lock: Permissions → App Control.");
        } else if (code == RemoteAppBlockManager.ERR_PROTECTED) {
            ack.fail("PROTECTED_PACKAGE", "This system package cannot be blocked.");
        } else if (code == RemoteAppBlockManager.ERR_AUTH) {
            ack.fail("NOT_SIGNED_IN", "Phone is not signed in.");
        } else {
            ack.fail("BLOCK_FAILED", "Could not apply app block.");
        }
    }

    private void appUnblock(@NonNull Map<String, Object> payload, @NonNull Ack ack) {
        String packageName = RemoteMapValues.string(payload, "packageName");
        if (packageName.isEmpty()) {
            ack.fail("BAD_PAYLOAD", "packageName required");
            return;
        }
        int code = new RemoteAppBlockManager(app).unblock(packageName);
        if (code == RemoteAppBlockManager.OK) {
            ack.ok("unblocked:" + packageName);
        } else {
            ack.fail("UNBLOCK_FAILED", "Could not clear app block.");
        }
    }

    private void appBlocksSync(@NonNull Ack ack) {
        RemoteModulePrefs prefs = new RemoteModulePrefs(app);
        if (!prefs.isAppControlEnabled()) {
            prefs.setAppControlEnabled(true);
            new RemoteDeviceInfoRepository(app).publishModuleFlags();
        }
        new RemoteAppBlockManager(app).purgeExpiredAndSync();
        int n = new RemoteAppBlockManager(app).activeBlocks().size();
        ack.ok("active:" + n);
    }

    private void screenRecordStart(@NonNull Map<String, Object> payload, @NonNull Ack ack) {
        RemoteModulePrefs prefs = new RemoteModulePrefs(app);
        if (!prefs.isScreenRecordEnabled()) {
            prefs.setScreenRecordEnabled(true);
            new RemoteDeviceInfoRepository(app).publishModuleFlags();
        }
        String transferId = RemoteMapValues.string(payload, "transferId");
        String recordingId = RemoteMapValues.string(payload, "recordingId");
        if (recordingId.isEmpty()) {
            recordingId = java.util.UUID.randomUUID().toString().replace("-", "");
        }
        boolean withMic = RemoteMapValues.bool(payload, "withMic", false);
        String quality = RemoteMapValues.string(payload, "quality");
        if (quality.isEmpty()) quality = "720p";
        int fps = (int) RemoteMapValues.longValue(payload, "fps", 30L);
        if (RemoteScreenRecordService.isActive()) {
            ack.fail("ALREADY_RECORDING", "A recording is already in progress");
            return;
        }
        // Always show the system MediaProjection dialog (token is one-shot, not reusable).
        app.startActivity(RemoteMediaProjectionConsentActivity.intentForRecord(
                app, transferId, recordingId, withMic, quality, fps));
        ack.ok("awaiting_projection_permission");
    }

    private void syncMessages(@NonNull Map<String, Object> payload, @NonNull Ack ack) {
        RemoteModulePrefs prefs = new RemoteModulePrefs(app);
        if (!prefs.isMessagesSharingEnabled()) {
            if (RemoteSmsMirror.hasSmsPermission(app)) {
                prefs.setMessagesSharingEnabled(true);
                new RemoteDeviceInfoRepository(app).publishModuleFlags();
            } else {
                ack.fail("MESSAGES_DISABLED",
                        "SMS permission is off. Phone → Permissions → SMS / Messages.");
                return;
            }
        }
        int limit = (int) RemoteMapValues.longValue(payload, "limit", 100);
        int n = RemoteSmsMirror.syncInbox(app, limit);
        if (n == RemoteSmsMirror.ERR_DISABLED) {
            ack.fail("MESSAGES_DISABLED", "Messages sharing is disabled on the phone.");
        } else if (n == RemoteSmsMirror.ERR_PERMISSION) {
            ack.fail("PERMISSION_DENIED", "READ_SMS not granted. Phone → Permissions → SMS.");
        } else if (n == RemoteSmsMirror.ERR_AUTH) {
            ack.fail("NOT_SIGNED_IN", "Phone is not signed in.");
        } else if (n == RemoteSmsMirror.ERR_WRITE) {
            ack.fail("WRITE_FAILED", "Could not save messages to cloud.");
        } else {
            ack.ok("synced:" + Math.max(0, n));
        }
    }

    private void galleryTransfer(@NonNull Map<String, Object> payload, @NonNull Ack ack) {
        if (!new RemoteModulePrefs(app).isGalleryEnabled()) {
            failTransfer(RemoteMapValues.string(payload, "transferId"),
                    "GALLERY_DISABLED", "Gallery access is disabled");
            ack.fail("GALLERY_DISABLED", "Gallery access is disabled");
            return;
        }
        String itemId = RemoteMapValues.string(payload, "itemId");
        String transferId = RemoteMapValues.string(payload, "transferId");
        Uri uri = new RemoteGalleryIndexer(app).resolveItemUri(itemId);
        if (uri == null) {
            failTransfer(transferId, "ITEM_NOT_FOUND",
                    "Media item not available locally; re-index gallery");
            ack.fail("ITEM_NOT_FOUND", "Media item not available locally; re-index gallery");
            return;
        }
        String mime = app.getContentResolver().getType(uri);
        if (mime == null || mime.trim().isEmpty()) {
            mime = "application/octet-stream";
        }
        uploadUri(uri, transferId, "gallery-transfers", mime, ack);
    }

    private void failTransfer(@NonNull String transferId,
                              @NonNull String errorCode,
                              @Nullable String errorMessage) {
        if (transferId.isEmpty()) return;
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        updateTransfer(user.getUid(), transferId, "failed", 0, null, null, errorCode, errorMessage);
    }

    private void fileList(@NonNull Map<String, Object> payload, @NonNull Ack ack) {
        if (!new RemoteModulePrefs(app).isFileManagerEnabled()) {
            ack.fail("FILES_DISABLED", "File manager disabled");
            return;
        }
        String grantId = RemoteMapValues.string(payload, "folderGrantId");
        String path = RemoteMapValues.string(payload, "relativePath");
        List<Map<String, Object>> rows = new RemoteFileManagerHelper(app).listChildren(grantId, path);
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            ack.fail("AUTH", "Not signed in");
            return;
        }
        String deviceId = new RemoteControlPrefs(app).getOrCreateDeviceId();
        String parentPath = RemoteFileManagerHelper.normalizeRelative(path);
        long listedAt = System.currentTimeMillis();
        for (Map<String, Object> row : rows) {
            String entryId = String.valueOf(row.get("documentId"));
            row.put("ownerUid", user.getUid());
            row.put("deviceId", deviceId);
            row.put("parentRelativePath", parentPath);
            row.put("listedAt", listedAt);
            FirebaseFirestore.getInstance()
                    .collection(AppConstants.FIRESTORE_USERS)
                    .document(user.getUid())
                    .collection(AppConstants.FIRESTORE_DEVICES)
                    .document(deviceId)
                    .collection(AppConstants.FIRESTORE_FILE_INDEX)
                    .document(entryId)
                    .set(row, SetOptions.merge());
        }
        // Browse cursor for the website (current open folder).
        Map<String, Object> cursor = new HashMap<>();
        cursor.put("ownerUid", user.getUid());
        cursor.put("deviceId", deviceId);
        cursor.put("folderGrantId", grantId);
        cursor.put("relativePath", parentPath);
        cursor.put("listedAt", listedAt);
        cursor.put("count", rows.size());
        FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(user.getUid())
                .collection(AppConstants.FIRESTORE_DEVICES)
                .document(deviceId)
                .collection(AppConstants.FIRESTORE_FILE_INDEX)
                .document("_browse")
                .set(cursor, SetOptions.merge());
        ack.ok("listed:" + rows.size());
    }

    private void fileDownload(@NonNull Map<String, Object> payload, @NonNull Ack ack) {
        String grantId = RemoteMapValues.string(payload, "folderGrantId");
        String relativePath = RemoteMapValues.string(payload, "relativePath");
        String transferId = RemoteMapValues.string(payload, "transferId");
        DocumentFile file = new RemoteFileManagerHelper(app).resolveFile(grantId, relativePath);
        if (file == null || file.getUri() == null) {
            ack.fail("FILE_NOT_FOUND", "File not found in authorized folder");
            return;
        }
        String mime = file.getType() != null ? file.getType() : "application/octet-stream";
        uploadUri(file.getUri(), transferId, "file-transfers", mime, ack);
    }

    private void fileCreateFolder(@NonNull Map<String, Object> payload, @NonNull Ack ack) {
        boolean ok = new RemoteFileManagerHelper(app).createFolder(
                RemoteMapValues.string(payload, "folderGrantId"),
                RemoteMapValues.string(payload, "relativePath"),
                RemoteMapValues.string(payload, "name"));
        if (ok) ack.ok("created");
        else ack.fail("CREATE_FAILED", "Could not create folder");
    }

    private void fileRename(@NonNull Map<String, Object> payload, @NonNull Ack ack) {
        boolean ok = new RemoteFileManagerHelper(app).rename(
                RemoteMapValues.string(payload, "folderGrantId"),
                RemoteMapValues.string(payload, "relativePath"),
                RemoteMapValues.string(payload, "newName"));
        if (ok) ack.ok("renamed");
        else ack.fail("RENAME_FAILED", "Rename failed");
    }

    private void fileCopy(@NonNull Map<String, Object> payload, @NonNull Ack ack) {
        boolean ok = new RemoteFileManagerHelper(app).copyWithinTree(
                RemoteMapValues.string(payload, "folderGrantId"),
                RemoteMapValues.string(payload, "fromPath"),
                RemoteMapValues.string(payload, "toDirPath"),
                RemoteMapValues.string(payload, "newName"));
        if (ok) ack.ok("copied");
        else ack.fail("COPY_FAILED", "Copy failed");
    }

    private void fileDelete(@NonNull Map<String, Object> payload, @NonNull Ack ack) {
        if (new RemoteModulePrefs(app).isConfirmEveryDelete()) {
            ack.fail("REQUIRES_USER_CONFIRMATION", "Confirm delete on the phone");
            return;
        }
        boolean ok = new RemoteFileManagerHelper(app).delete(
                RemoteMapValues.string(payload, "folderGrantId"),
                RemoteMapValues.string(payload, "relativePath"));
        if (ok) ack.ok("deleted");
        else ack.fail("DELETE_FAILED", "Delete failed");
    }

    private void cancelTransfer(@NonNull Map<String, Object> payload, @NonNull Ack ack) {
        String transferId = RemoteMapValues.string(payload, "transferId");
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || transferId.isEmpty()) {
            ack.fail("BAD_REQUEST", "Missing transfer");
            return;
        }
        Map<String, Object> patch = new HashMap<>();
        patch.put("status", "cancelled");
        patch.put("completedAt", System.currentTimeMillis());
        FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(user.getUid())
                .collection(AppConstants.FIRESTORE_TRANSFERS)
                .document(transferId)
                .set(patch, SetOptions.merge())
                .addOnSuccessListener(v -> ack.ok("cancelled"))
                .addOnFailureListener(e -> ack.fail("CANCEL_FAILED", e.getMessage()));
    }

    private void uploadUri(@NonNull Uri uri,
                           @NonNull String transferId,
                           @NonNull String folder,
                           @NonNull String mime,
                           @NonNull Ack ack) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || transferId.isEmpty()) {
            ack.fail("BAD_REQUEST", "Missing auth/transfer");
            return;
        }
        String deviceId = new RemoteControlPrefs(app).getOrCreateDeviceId();
        String path = "users/" + user.getUid() + "/devices/" + deviceId + "/" + folder
                + "/" + transferId + "/file";
        updateTransfer(user.getUid(), transferId, "uploading", 10, path, mime, null, null);
        try {
            InputStream in = app.getContentResolver().openInputStream(uri);
            if (in == null) {
                ack.fail("READ_FAILED", "Cannot read file");
                return;
            }
            StorageMetadata meta = new StorageMetadata.Builder().setContentType(mime).build();
            StorageReference ref = FirebaseStorage.getInstance().getReference(path);
            ref.putStream(in, meta)
                    .addOnSuccessListener(t -> {
                        updateTransfer(user.getUid(), transferId, "ready", 100, path, mime, null, null);
                        ack.ok("uploaded");
                    })
                    .addOnFailureListener(e -> {
                        updateTransfer(user.getUid(), transferId, "failed", 0, path, mime, "UPLOAD_FAILED",
                                e.getMessage());
                        ack.fail("UPLOAD_FAILED", e.getMessage());
                    });
        } catch (Exception e) {
            updateTransfer(user.getUid(), transferId, "failed", 0, path, mime, "UPLOAD_FAILED", e.getMessage());
            ack.fail("UPLOAD_FAILED", e.getMessage());
        }
    }

    private void updateTransfer(@NonNull String uid,
                                @NonNull String transferId,
                                @NonNull String status,
                                int progress,
                                @Nullable String storagePath,
                                @Nullable String mimeType,
                                @Nullable String errorCode,
                                @Nullable String errorMessage) {
        Map<String, Object> patch = new HashMap<>();
        patch.put("status", status);
        patch.put("progress", progress);
        if (storagePath != null) patch.put("storagePath", storagePath);
        if (mimeType != null && !mimeType.isEmpty()) patch.put("mimeType", mimeType);
        if (errorCode != null) patch.put("errorCode", errorCode);
        if (errorMessage != null) patch.put("errorMessage", errorMessage);
        if ("ready".equals(status) || "failed".equals(status) || "cancelled".equals(status)) {
            patch.put("completedAt", System.currentTimeMillis());
        }
        FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(uid)
                .collection(AppConstants.FIRESTORE_TRANSFERS)
                .document(transferId)
                .set(patch, SetOptions.merge())
                .addOnFailureListener(e -> Log.w(TAG, "transfer update failed " + transferId, e));
    }

    public interface Ack {
        void ok(@NonNull String summary);

        void fail(@NonNull String code, @Nullable String message);
    }
}
