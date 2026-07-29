package com.autoreplybot.remote;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

/** Device admin used only for optional camera hardware lock. */
public final class RemoteDeviceAdminReceiver extends DeviceAdminReceiver {
    @Override
    public void onEnabled(@NonNull Context context, @NonNull Intent intent) {
        new RemoteAppBlockManager(context).syncStatusToCloud();
    }

    @Override
    public void onDisabled(@NonNull Context context, @NonNull Intent intent) {
        new RemoteAppBlockManager(context).clearCameraHardwareQuietly();
        new RemoteAppBlockManager(context).syncStatusToCloud();
    }
}
