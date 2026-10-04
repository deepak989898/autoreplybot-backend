package com.autoreplybot.remote;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.annotation.NonNull;

/** Opens app settings where the user (or accessibility) can choose Allow all the time. */
public final class RemoteBackgroundLocationHelper {
    private RemoteBackgroundLocationHelper() {}

    public static void openAppDetailsSettings(@NonNull Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", context.getPackageName(), null));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ignored) {
        }
    }
}
