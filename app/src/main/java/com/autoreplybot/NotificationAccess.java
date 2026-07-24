package com.autoreplybot;

import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.annotation.NonNull;

public final class NotificationAccess {

    private NotificationAccess() {}

    public static boolean isEnabled(@NonNull Context context) {
        String flat = Settings.Secure.getString(
                context.getContentResolver(),
                "enabled_notification_listeners");
        if (TextUtils.isEmpty(flat)) return false;
        String pkg = context.getPackageName();
        String[] parts = flat.split(":");
        for (String part : parts) {
            if (!TextUtils.isEmpty(part) && part.contains(pkg)) {
                return true;
            }
        }
        return false;
    }
}
