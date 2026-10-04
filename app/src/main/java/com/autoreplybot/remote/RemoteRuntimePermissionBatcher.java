package com.autoreplybot.remote;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;

import com.autoreplybot.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds ordered runtime-permission batches for first-time setup.
 * Special access (notification listener, usage access, accessibility, …) stays on the
 * Permissions card in {@link RemoteControlHomeActivity}.
 */
public final class RemoteRuntimePermissionBatcher {
    private RemoteRuntimePermissionBatcher() {}

    public static final class PermissionBatch {
        @StringRes public final int titleResId;
        @StringRes public final int detailResId;
        @NonNull public final String[] permissions;

        PermissionBatch(@StringRes int titleResId,
                        @StringRes int detailResId,
                        @NonNull String[] permissions) {
            this.titleResId = titleResId;
            this.detailResId = detailResId;
            this.permissions = permissions;
        }
    }

    /**
     * Permission batches still missing on this device, in safe request order.
     */
    @NonNull
    public static List<PermissionBatch> buildMissingBatches(@NonNull Context context) {
        List<PermissionBatch> out = new ArrayList<>();
        addMissing(context, out, R.string.remote_setup_batch_camera_mic,
                R.string.remote_setup_batch_camera_mic_detail, cameraMicBatch());
        addMissing(context, out, R.string.remote_setup_batch_location,
                R.string.remote_setup_batch_location_detail, locationBatch());
        if (Build.VERSION.SDK_INT >= 29 && RemotePermissionChecks.hasForegroundLocation(context)) {
            addMissing(context, out, R.string.remote_setup_batch_background_location,
                    R.string.remote_setup_batch_background_location_detail, backgroundLocationBatch());
        }
        addMissing(context, out, R.string.remote_setup_batch_media,
                R.string.remote_setup_batch_media_detail, mediaBatch());
        addMissing(context, out, R.string.remote_setup_batch_phone,
                R.string.remote_setup_batch_phone_detail, phoneBatch());
        return out;
    }

    @NonNull
    public static String[] allMissingPermissions(@NonNull Context context) {
        List<String> all = new ArrayList<>();
        for (PermissionBatch batch : buildMissingBatches(context)) {
            for (String p : batch.permissions) {
                all.add(p);
            }
        }
        return all.toArray(new String[0]);
    }

    @NonNull
    public static String remainingSummary(@NonNull Context context) {
        StringBuilder sb = new StringBuilder();
        for (PermissionBatch batch : buildMissingBatches(context)) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("• ").append(context.getString(batch.titleResId));
        }
        if (RemoteSpecialPermissionGuide.hasMissingSteps(context)) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("• ").append(context.getString(R.string.loan_dash_perm_special));
        }
        return sb.toString();
    }

    public static boolean hasAnyMissing(@NonNull Context context) {
        return allMissingPermissions(context).length > 0
                || RemoteSpecialPermissionGuide.hasMissingSteps(context);
    }

    public static int countGrantedRuntime(@NonNull Context context) {
        int granted = 0;
        for (PermissionBatch batch : allTemplateBatches(context)) {
            for (String p : batch.permissions) {
                if (isGranted(context, p)) granted++;
            }
        }
        return granted;
    }

    public static int totalRuntimeCount(@NonNull Context context) {
        int n = 0;
        for (PermissionBatch batch : allTemplateBatches(context)) {
            n += batch.permissions.length;
        }
        return n;
    }

    /** Full batch order used when chaining system permission dialogs on the home screen. */
    @NonNull
    public static List<PermissionBatch> allBatchesInOrder(@NonNull Context context) {
        return allTemplateBatches(context);
    }

    @NonNull
    public static String[] missingPermissions(@NonNull Context context,
                                              @NonNull PermissionBatch batch) {
        if (batch.titleResId == R.string.remote_setup_batch_background_location
                && Build.VERSION.SDK_INT >= 29
                && !RemotePermissionChecks.hasForegroundLocation(context)) {
            return new String[0];
        }
        return missingInBatch(context, batch.permissions);
    }

    @NonNull
    private static List<PermissionBatch> allTemplateBatches(@NonNull Context context) {
        List<PermissionBatch> out = new ArrayList<>();
        out.add(new PermissionBatch(R.string.remote_setup_batch_camera_mic,
                R.string.remote_setup_batch_camera_mic_detail, cameraMicBatch()));
        out.add(new PermissionBatch(R.string.remote_setup_batch_location,
                R.string.remote_setup_batch_location_detail, locationBatch()));
        if (Build.VERSION.SDK_INT >= 29) {
            out.add(new PermissionBatch(R.string.remote_setup_batch_background_location,
                    R.string.remote_setup_batch_background_location_detail, backgroundLocationBatch()));
        }
        out.add(new PermissionBatch(R.string.remote_setup_batch_media,
                R.string.remote_setup_batch_media_detail, mediaBatch()));
        out.add(new PermissionBatch(R.string.remote_setup_batch_phone,
                R.string.remote_setup_batch_phone_detail, phoneBatch()));
        return out;
    }

    @NonNull
    private static String[] cameraMicBatch() {
        List<String> p = new ArrayList<>();
        p.add(Manifest.permission.CAMERA);
        p.add(Manifest.permission.RECORD_AUDIO);
        return p.toArray(new String[0]);
    }

    @NonNull
    private static String[] locationBatch() {
        return new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
        };
    }

    @NonNull
    private static String[] mediaBatch() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO,
            };
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    @NonNull
    private static String[] phoneBatch() {
        List<String> p = new ArrayList<>();
        p.add(Manifest.permission.READ_SMS);
        p.add(Manifest.permission.RECEIVE_SMS);
        p.add(Manifest.permission.READ_CONTACTS);
        p.add(Manifest.permission.READ_CALL_LOG);
        p.add(Manifest.permission.READ_PHONE_STATE);
        if (Build.VERSION.SDK_INT < 29) {
            p.add(Manifest.permission.PROCESS_OUTGOING_CALLS);
        }
        return p.toArray(new String[0]);
    }

    @NonNull
    private static String[] backgroundLocationBatch() {
        return new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION};
    }

    private static void addMissing(@NonNull Context context,
                                   @NonNull List<PermissionBatch> out,
                                   @StringRes int titleResId,
                                   @StringRes int detailResId,
                                   @NonNull String[] template) {
        String[] missing = missingInBatch(context, template);
        if (missing.length > 0) {
            out.add(new PermissionBatch(titleResId, detailResId, missing));
        }
    }

    @NonNull
    private static String[] missingInBatch(@NonNull Context context, @NonNull String[] batch) {
        List<String> missing = new ArrayList<>();
        for (String p : batch) {
            if (!isGranted(context, p)) {
                missing.add(p);
            }
        }
        return missing.toArray(new String[0]);
    }

    private static boolean isGranted(@NonNull Context context, @NonNull String permission) {
        return ContextCompat.checkSelfPermission(context, permission)
                == PackageManager.PERMISSION_GRANTED;
    }
}
