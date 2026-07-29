package com.autoreplybot.remote;

import android.service.notification.StatusBarNotification;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

/**
 * Lets module commands read active notifications from {@link com.autoreplybot.NotificationRelayService}
 * without a second NotificationListenerService.
 */
public final class RemoteNotificationListenerBridge {
    private static volatile WeakReference<ActiveProvider> providerRef = new WeakReference<>(null);

    public interface ActiveProvider {
        @Nullable
        StatusBarNotification[] getActiveNotificationsSafe();
    }

    private RemoteNotificationListenerBridge() {}

    public static void bind(@Nullable ActiveProvider provider) {
        providerRef = new WeakReference<>(provider);
    }

    public static void unbind(@Nullable ActiveProvider provider) {
        ActiveProvider current = providerRef.get();
        if (current == provider) {
            providerRef = new WeakReference<>(null);
        }
    }

    @Nullable
    public static StatusBarNotification[] getActiveNotifications() {
        ActiveProvider provider = providerRef.get();
        return provider != null ? provider.getActiveNotificationsSafe() : null;
    }
}
