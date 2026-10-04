package com.autoreplybot;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.autoreplybot.remote.RemoteHiddenUnlockNotifications;
import com.autoreplybot.remote.RemoteLauncherVisibility;

/**
 * Applies opaque system bar behavior on every activity (status bar visible with theme colors).
 */
public class AutoReplyBotApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            RemoteLauncherVisibility.applyFromPrefs(this);
            RemoteHiddenUnlockNotifications.syncWithPrefs(this);
        } catch (Throwable ignored) {
        }
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
                SystemBarsHelper.apply(activity);
            }

            @Override
            public void onActivityStarted(@NonNull Activity activity) {
            }

            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                // Run after androidx.activity / Material finish any edge-to-edge setup.
                activity.getWindow().getDecorView().post(() -> SystemBarsHelper.apply(activity));
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
            }

            @Override
            public void onActivityStopped(@NonNull Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {
            }
        });
    }
}
