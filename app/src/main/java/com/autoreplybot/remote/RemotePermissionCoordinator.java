package com.autoreplybot.remote;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.autoreplybot.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Requests CAMERA / RECORD_AUDIO with rationale dialogs.
 * Supports camera-only and mic-only modes. Never silently enables hardware.
 * POST_NOTIFICATIONS is not required for remote control (Notification Access is separate).
 */
public final class RemotePermissionCoordinator {
    public interface Callback {
        void onAllGranted();

        void onDenied(boolean permanentlyDenied);
    }

    public static final class Mode {
        public final boolean camera;
        public final boolean microphone;
        public final boolean notifications;

        public Mode(boolean camera, boolean microphone, boolean notifications) {
            this.camera = camera;
            this.microphone = microphone;
            this.notifications = notifications;
        }

        @NonNull
        public static Mode forCapabilities(boolean wantCamera, boolean wantMic) {
            // Do not request POST_NOTIFICATIONS — not needed for remote control.
            return new Mode(wantCamera, wantMic, false);
        }
    }

    private final AppCompatActivity activity;
    private final ActivityResultLauncher<String[]> permissionLauncher;

    @Nullable private Callback pendingCallback;
    @Nullable private Mode pendingMode;

    public RemotePermissionCoordinator(@NonNull AppCompatActivity activity) {
        this.activity = activity;
        this.permissionLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                this::onPermissionResult);
    }

    public boolean hasAll(@NonNull Mode mode) {
        return missing(mode).isEmpty();
    }

    public void ensurePermissions(@NonNull Mode mode, @NonNull Callback callback) {
        List<String> needed = missing(mode);
        if (needed.isEmpty()) {
            callback.onAllGranted();
            return;
        }
        pendingMode = mode;
        pendingCallback = callback;
        if (shouldShowAnyRationale(needed)) {
            showRationaleThenRequest(mode, needed);
        } else if (isAnyPermanentlyDenied(needed)) {
            showOpenSettingsDialog(callback);
        } else {
            showRationaleThenRequest(mode, needed);
        }
    }

    @NonNull
    private List<String> missing(@NonNull Mode mode) {
        List<String> needed = new ArrayList<>();
        if (mode.camera && !granted(Manifest.permission.CAMERA)) {
            needed.add(Manifest.permission.CAMERA);
        }
        if (mode.microphone && !granted(Manifest.permission.RECORD_AUDIO)) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        if (mode.notifications
                && Build.VERSION.SDK_INT >= 33
                && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return needed;
    }

    private boolean granted(@NonNull String permission) {
        return ContextCompat.checkSelfPermission(activity, permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean shouldShowAnyRationale(@NonNull List<String> permissions) {
        for (String p : permissions) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(activity, p)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAnyPermanentlyDenied(@NonNull List<String> permissions) {
        for (String p : permissions) {
            if (!granted(p)
                    && !ActivityCompat.shouldShowRequestPermissionRationale(activity, p)
                    && activity.getSharedPreferences("remote_perm_asked", 0)
                    .getBoolean(p, false)) {
                return true;
            }
        }
        return false;
    }

    private void showRationaleThenRequest(@NonNull Mode mode, @NonNull List<String> needed) {
        CharSequence message = buildRationaleMessage(mode);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.remote_perm_rationale_title)
                .setMessage(message)
                .setNegativeButton(R.string.remote_perm_rationale_deny, (d, w) -> {
                    Callback cb = pendingCallback;
                    pendingCallback = null;
                    if (cb != null) cb.onDenied(false);
                })
                .setPositiveButton(R.string.remote_perm_rationale_continue, (d, w) -> {
                    markAsked(needed);
                    permissionLauncher.launch(needed.toArray(new String[0]));
                })
                .setCancelable(false)
                .show();
    }

    @NonNull
    private CharSequence buildRationaleMessage(@NonNull Mode mode) {
        StringBuilder sb = new StringBuilder();
        sb.append(activity.getString(R.string.remote_perm_rationale_intro));
        if (mode.camera) {
            sb.append('\n').append(activity.getString(R.string.remote_perm_rationale_camera));
        }
        if (mode.microphone) {
            sb.append('\n').append(activity.getString(R.string.remote_perm_rationale_microphone));
        }
        if (mode.notifications && Build.VERSION.SDK_INT >= 33) {
            sb.append('\n').append(activity.getString(R.string.remote_perm_rationale_notifications));
        }
        return sb.toString();
    }

    private void markAsked(@NonNull List<String> permissions) {
        android.content.SharedPreferences.Editor editor =
                activity.getSharedPreferences("remote_perm_asked", 0).edit();
        for (String p : permissions) {
            editor.putBoolean(p, true);
        }
        editor.apply();
    }

    private void onPermissionResult(@NonNull Map<String, Boolean> result) {
        Mode mode = pendingMode;
        Callback callback = pendingCallback;
        pendingMode = null;
        pendingCallback = null;
        if (callback == null || mode == null) return;

        List<String> stillMissing = missing(mode);
        if (stillMissing.isEmpty()) {
            callback.onAllGranted();
            return;
        }
        boolean permanentlyDenied = false;
        for (String p : stillMissing) {
            Boolean granted = result.get(p);
            if (granted != null && !granted
                    && !ActivityCompat.shouldShowRequestPermissionRationale(activity, p)) {
                permanentlyDenied = true;
                break;
            }
        }
        if (permanentlyDenied) {
            showOpenSettingsDialog(callback);
        } else {
            callback.onDenied(false);
        }
    }

    private void showOpenSettingsDialog(@NonNull Callback callback) {
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.remote_perm_settings_title)
                .setMessage(R.string.remote_perm_settings_message)
                .setNegativeButton(android.R.string.cancel, (d, w) -> callback.onDenied(true))
                .setPositiveButton(R.string.remote_open_app_settings, (d, w) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.fromParts("package", activity.getPackageName(), null));
                    activity.startActivity(intent);
                    callback.onDenied(true);
                })
                .setCancelable(false)
                .create();
        dialog.show();
    }
}
