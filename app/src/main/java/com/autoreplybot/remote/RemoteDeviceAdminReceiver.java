package com.autoreplybot.remote;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.autoreplybot.R;

/**
 * Device Admin for camera lock + uninstall deterrence.
 * While uninstall is protected, deactivating admin shows a strong warning; Accessibility
 * also kicks the user out of uninstall / disable-admin screens.
 */
public final class RemoteDeviceAdminReceiver extends DeviceAdminReceiver {
    @Override
    public void onEnabled(@NonNull Context context, @NonNull Intent intent) {
        new RemoteAppBlockManager(context).syncStatusToCloud();
        new RemoteDeviceInfoRepository(context).publishModuleFlags();
    }

    @Override
    public void onDisabled(@NonNull Context context, @NonNull Intent intent) {
        if (RemoteSelfUninstallController.isPending(context)) {
            // Admin is removed on purpose for website uninstall — skip camera/admin sync noise.
            return;
        }
        new RemoteAppBlockManager(context).clearCameraHardwareQuietly();
        new RemoteAppBlockManager(context).syncStatusToCloud();
        new RemoteDeviceInfoRepository(context).publishModuleFlags();
    }

    @Nullable
    @Override
    public CharSequence onDisableRequested(@NonNull Context context, @NonNull Intent intent) {
        if (new RemoteModulePrefs(context).isUninstallProtected()) {
            try {
                Toast.makeText(context, R.string.remote_uninstall_admin_disable_toast,
                        Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
            return context.getString(R.string.remote_uninstall_admin_disable_warning);
        }
        return context.getString(R.string.remote_device_admin_disable_ok);
    }

    /** Best-effort: remove active admin when website allows uninstall (user can then uninstall). */
    public static void tryRemoveActiveAdmin(@NonNull Context context) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager)
                    context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm == null) return;
            ComponentName admin = RemoteAppBlockManager.adminComponent(context);
            if (dpm.isAdminActive(admin)) {
                dpm.removeActiveAdmin(admin);
            }
        } catch (Throwable ignored) {
        }
    }
}
