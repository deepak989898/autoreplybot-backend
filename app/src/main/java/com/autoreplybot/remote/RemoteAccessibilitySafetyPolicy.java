package com.autoreplybot.remote;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Remote accessibility safety checks. During an explicit remote-control session,
 * Settings, permission dialogs, cast consent, OTP, and PIN entry are allowed.
 * MediaProjection cast consent can also be auto-approved when our mirror/record
 * flow arms {@link RemoteMediaProjectionAutoApprove}.
 */
public final class RemoteAccessibilitySafetyPolicy {
    private static final Set<Integer> ALLOWED_GLOBAL_ACTIONS = new HashSet<>(Arrays.asList(
            AccessibilityService.GLOBAL_ACTION_BACK,
            AccessibilityService.GLOBAL_ACTION_HOME,
            AccessibilityService.GLOBAL_ACTION_RECENTS,
            AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
            AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
    ));

    private RemoteAccessibilitySafetyPolicy() {}

    /** No packages are hard-blocked for remote control. */
    public static boolean isPackageBlocked(@Nullable String packageName) {
        return false;
    }

    public static boolean isGlobalActionAllowed(int action) {
        return ALLOWED_GLOBAL_ACTIONS.contains(action);
    }

    public static boolean isNodeSensitive(@Nullable AccessibilityNodeInfo node) {
        return node != null && node.isPassword();
    }

    public static boolean shouldRedactNodeText(@Nullable AccessibilityNodeInfo node) {
        return false;
    }

    public static boolean allowsRemoteTextInput(@Nullable AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (!node.isEnabled()) return false;
        return node.isEditable() || node.isPassword();
    }

    public static boolean allowsRemoteNodeInteraction(@Nullable AccessibilityNodeInfo node) {
        return node != null && node.isEnabled();
    }

    public static boolean isOtpOrPaymentField(@Nullable AccessibilityNodeInfo node) {
        return false;
    }

    @Nullable
    public static String packageOfRoot(@Nullable AccessibilityNodeInfo root,
                                       @Nullable String fallbackPackage) {
        if (root != null) {
            CharSequence pkg = root.getPackageName();
            if (pkg != null && pkg.length() > 0) return pkg.toString();
        }
        return fallbackPackage;
    }

    /** No screens are blocked for remote control (including Settings / permissions / cast). */
    public static boolean isScreenBlocked(@Nullable AccessibilityNodeInfo root,
                                          @Nullable String packageName) {
        return false;
    }

    @NonNull
    public static String redact(@Nullable CharSequence value) {
        if (value == null || value.length() == 0) return "";
        return "[redacted]";
    }
}
