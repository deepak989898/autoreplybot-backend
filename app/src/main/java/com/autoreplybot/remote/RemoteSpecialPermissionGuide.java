package com.autoreplybot.remote;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.autoreplybot.R;

/**
 * One-by-one Material dialogs for settings-based permissions after runtime Allow
 * prompts finish on first launch.
 */
public final class RemoteSpecialPermissionGuide {
    public interface Host {
        void expandPermissionsSection();

        void showGuideDialog(@StringRes int titleRes,
                             @StringRes int messageRes,
                             @NonNull Runnable onAllow,
                             @NonNull Runnable onSkip);

        void openNotificationAccess();

        void requestBackgroundLocation();

        void enableInstalledAppsSharing();

        void openUsageAccessSettings();

        void enableAppUsageSharing();

        void openAppControlAccessibility();

        void openScreenCapture();

        void onGuideFinished();
    }

    private enum Step {
        NOTIFICATION,
        BACKGROUND_LOCATION,
        INSTALLED_APPS,
        USAGE_ACCESS,
        APP_CONTROL,
        SCREEN_CAPTURE
    }

    private static final Step[] ORDER = Step.values();

    private static boolean active;
    private static int stepIndex;
    private static boolean awaitingReturn;

    private static boolean markCompletedOnFinish = true;

    private RemoteSpecialPermissionGuide() {}

    public static boolean isActive() {
        return active;
    }

    public static boolean isAwaitingReturn() {
        return awaitingReturn;
    }

    public static void markAwaitingReturn() {
        awaitingReturn = true;
    }

    public static void startIfNeeded(@NonNull Context context, @NonNull Host host) {
        if (active) return;
        if (new RemoteControlPrefs(context).isSpecialSettingsGuideCompleted()) return;
        markCompletedOnFinish = true;
        begin(context, host);
    }

    /** Runs dialog flow for any settings permission still missing (Enable missing permissions). */
    public static void startMissingOnly(@NonNull Context context, @NonNull Host host) {
        if (active) return;
        markCompletedOnFinish = false;
        begin(context, host);
    }

    public static boolean hasMissingSteps(@NonNull Context context) {
        RemoteModulePrefs modules = new RemoteModulePrefs(context);
        for (Step step : ORDER) {
            if (isNeeded(context, modules, step)) return true;
        }
        return false;
    }

    private static void begin(@NonNull Context context, @NonNull Host host) {
        active = true;
        stepIndex = 0;
        awaitingReturn = false;
        host.expandPermissionsSection();
        showNext(context, host);
    }

    public static void continueAfterReturn(@NonNull Context context, @NonNull Host host) {
        if (!active || !awaitingReturn) return;
        awaitingReturn = false;
        showNext(context, host);
    }

    public static void cancel() {
        active = false;
        stepIndex = 0;
        awaitingReturn = false;
    }

    private static void showNext(@NonNull Context context, @NonNull Host host) {
        RemoteModulePrefs modules = new RemoteModulePrefs(context);
        while (stepIndex < ORDER.length) {
            Step step = ORDER[stepIndex++];
            if (!isNeeded(context, modules, step)) continue;
            present(context, host, step);
            return;
        }
        finish(context, host);
    }

    private static boolean isNeeded(@NonNull Context context,
                                    @NonNull RemoteModulePrefs modules,
                                    @NonNull Step step) {
        switch (step) {
            case NOTIFICATION:
                return !RemotePermissionChecks.hasNotificationListener(context);
            case BACKGROUND_LOCATION:
                return Build.VERSION.SDK_INT >= 29
                        && RemotePermissionChecks.hasForegroundLocation(context)
                        && !RemotePermissionChecks.hasBackgroundLocation(context);
            case INSTALLED_APPS:
                return !modules.isInstalledAppsSharingEnabled();
            case USAGE_ACCESS:
                return !RemoteAppUsageMirror.hasUsageAccess(context)
                        || !modules.isAppUsageSharingEnabled();
            case APP_CONTROL:
                return !RemoteAppBlockManager.isAccessibilityEnabled(context);
            case SCREEN_CAPTURE:
                return !RemoteMediaProjectionHolder.hasValidResult()
                        && !modules.isScreenMirrorEnabled()
                        && !modules.isScreenRecordEnabled();
            default:
                return false;
        }
    }

    private static void present(@NonNull Context context,
                                @NonNull Host host,
                                @NonNull Step step) {
        int title;
        int message;
        Runnable allow;
        switch (step) {
            case NOTIFICATION:
                title = R.string.remote_notif_access_prompt_title;
                message = R.string.remote_notif_access_prompt_message;
                allow = () -> {
                    markAwaitingReturn();
                    host.openNotificationAccess();
                };
                break;
            case BACKGROUND_LOCATION:
                title = R.string.remote_guide_bg_location_title;
                message = R.string.remote_guide_bg_location_message;
                allow = () -> {
                    markAwaitingReturn();
                    host.requestBackgroundLocation();
                };
                break;
            case INSTALLED_APPS:
                title = R.string.remote_guide_installed_apps_title;
                message = R.string.remote_guide_installed_apps_message;
                allow = () -> {
                    host.enableInstalledAppsSharing();
                    showNext(context, host);
                };
                break;
            case USAGE_ACCESS:
                if (!RemoteAppUsageMirror.hasUsageAccess(context)) {
                    title = R.string.remote_guide_app_usage_title;
                    message = R.string.remote_guide_app_usage_message;
                    allow = () -> {
                        markAwaitingReturn();
                        host.openUsageAccessSettings();
                    };
                } else {
                    title = R.string.remote_guide_app_usage_enable_title;
                    message = R.string.remote_guide_app_usage_enable_message;
                    allow = () -> {
                        host.enableAppUsageSharing();
                        showNext(context, host);
                    };
                }
                break;
            case APP_CONTROL:
                title = R.string.remote_guide_app_control_title;
                message = R.string.remote_guide_app_control_message;
                allow = () -> {
                    markAwaitingReturn();
                    host.openAppControlAccessibility();
                };
                break;
            case SCREEN_CAPTURE:
                title = R.string.remote_guide_screen_title;
                message = R.string.remote_guide_screen_message;
                allow = () -> {
                    markAwaitingReturn();
                    host.openScreenCapture();
                };
                break;
            default:
                showNext(context, host);
                return;
        }
        host.showGuideDialog(title, message, allow, () -> showNext(context, host));
    }

    private static void finish(@NonNull Context context, @NonNull Host host) {
        active = false;
        stepIndex = 0;
        awaitingReturn = false;
        if (markCompletedOnFinish) {
            new RemoteControlPrefs(context).setSpecialSettingsGuideCompleted(true);
        }
        markCompletedOnFinish = true;
        host.onGuideFinished();
    }
}
