package com.autoreplybot.remote;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.autoreplybot.R;

/**
 * Official MediaProjection consent UI. When Remote Accessibility is connected,
 * {@link RemoteMediaProjectionAutoApprove} clicks Cast/Start on the system dialog.
 */
public class RemoteMediaProjectionConsentActivity extends AppCompatActivity {
    public static final String EXTRA_CONTINUE_ACTION = "continueAction";
    public static final String ACTION_MIRROR = "mirror";
    public static final String ACTION_RECORD = "record";
    public static final String ACTION_STORE_ONLY = "store_only";

    public static final String EXTRA_SESSION_ID = "sessionId";
    public static final String EXTRA_REQUEST_ID = "requestId";
    public static final String EXTRA_CLIENT_ID = "clientId";
    public static final String EXTRA_CLIENT_NAME = "clientName";
    public static final String EXTRA_WITH_MIC = "withMic";
    public static final String EXTRA_QUALITY = "quality";
    public static final String EXTRA_FPS = "fps";
    public static final String EXTRA_TRANSFER_ID = "transferId";
    public static final String EXTRA_RECORDING_ID = "recordingId";

    private static final int REQ_PROJECTION = 9101;
    private boolean projectionRequested;

    public static boolean isMirrorConsentInProgress(@NonNull String sessionId) {
        return RemoteScreenMirrorConsentGate.isInFlight();
    }

    public static void clearMirrorConsentGuard() {
        RemoteScreenMirrorConsentGate.leave();
    }

    @NonNull
    public static Intent intentForMirror(@NonNull Context context,
                                         @NonNull String sessionId,
                                         @NonNull String requestId,
                                         @NonNull String clientId,
                                         @NonNull String clientName,
                                         boolean withMic,
                                         @NonNull String quality,
                                         int fps) {
        Intent i = new Intent(context, RemoteMediaProjectionConsentActivity.class);
        i.putExtra(EXTRA_CONTINUE_ACTION, ACTION_MIRROR);
        i.putExtra(EXTRA_SESSION_ID, sessionId);
        i.putExtra(EXTRA_REQUEST_ID, requestId);
        i.putExtra(EXTRA_CLIENT_ID, clientId);
        i.putExtra(EXTRA_CLIENT_NAME, clientName);
        i.putExtra(EXTRA_WITH_MIC, withMic);
        i.putExtra(EXTRA_QUALITY, quality);
        i.putExtra(EXTRA_FPS, fps);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (!(context instanceof Activity)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return i;
    }

    @NonNull
    public static Intent intentForRecord(@NonNull Context context,
                                         @NonNull String transferId,
                                         @NonNull String recordingId,
                                         boolean withMic,
                                         @NonNull String quality,
                                         int fps) {
        Intent i = new Intent(context, RemoteMediaProjectionConsentActivity.class);
        i.putExtra(EXTRA_CONTINUE_ACTION, ACTION_RECORD);
        i.putExtra(EXTRA_TRANSFER_ID, transferId);
        i.putExtra(EXTRA_RECORDING_ID, recordingId);
        i.putExtra(EXTRA_WITH_MIC, withMic);
        i.putExtra(EXTRA_QUALITY, quality);
        i.putExtra(EXTRA_FPS, fps);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (!(context instanceof Activity)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return i;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent src = getIntent();
        String action = src != null ? String.valueOf(src.getStringExtra(EXTRA_CONTINUE_ACTION)) : "";
        String sessionId = safe(src != null ? src.getStringExtra(EXTRA_SESSION_ID) : null);
        if (ACTION_MIRROR.equals(action) && !sessionId.isEmpty()) {
            if (!RemoteScreenMirrorConsentGate.tryEnter(sessionId)) {
                finish();
                return;
            }
        }
        if (projectionRequested || (savedInstanceState != null
                && savedInstanceState.getBoolean("projectionRequested", false))) {
            finish();
            return;
        }
        requestProjectionConsent();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Ignore duplicate mirror requests while the cast dialog is already open.
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("projectionRequested", projectionRequested);
    }

    private void requestProjectionConsent() {
        RemoteSessionNotifAutoClick.disarm();
        RemoteMediaProjectionHolder.clear();
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            RemoteScreenMirrorConsentGate.leave();
            Toast.makeText(this, R.string.remote_screen_projection_unavailable, Toast.LENGTH_LONG)
                    .show();
            finish();
            return;
        }
        Intent captureIntent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            captureIntent = mpm.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay());
        } else {
            captureIntent = mpm.createScreenCaptureIntent();
        }
        projectionRequested = true;
        RemoteMediaProjectionAutoApprove.arm(45_000L);
        startActivityForResult(captureIntent, REQ_PROJECTION);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PROJECTION) {
            RemoteMediaProjectionAutoApprove.disarm();
            RemoteScreenMirrorConsentGate.leave();
            finish();
            return;
        }
        RemoteMediaProjectionAutoApprove.disarm();
        if (resultCode != Activity.RESULT_OK || data == null) {
            RemoteScreenMirrorConsentGate.leave();
            Intent src = getIntent();
            String action = src != null ? String.valueOf(src.getStringExtra(EXTRA_CONTINUE_ACTION)) : "";
            if (ACTION_RECORD.equals(action)) {
                RemoteScreenRecordService.markFailed(
                        this,
                        safe(src != null ? src.getStringExtra(EXTRA_RECORDING_ID) : null),
                        safe(src != null ? src.getStringExtra(EXTRA_TRANSFER_ID) : null),
                        "Screen capture permission denied");
            }
            Toast.makeText(this, R.string.remote_screen_permission_denied, Toast.LENGTH_LONG).show();
            RemoteMediaProjectionHolder.clear();
            finish();
            return;
        }
        RemoteMediaProjectionHolder.store(resultCode, data);
        new RemoteModulePrefs(this).setScreenMirrorEnabled(true);
        new RemoteModulePrefs(this).setScreenRecordEnabled(true);
        new RemoteDeviceInfoRepository(this).publishModuleFlags();
        continueWithStored();
    }

    private void continueWithStored() {
        Intent src = getIntent();
        String action = src != null ? String.valueOf(src.getStringExtra(EXTRA_CONTINUE_ACTION)) : "";
        if (ACTION_MIRROR.equals(action)) {
            RemoteScreenMirrorService.start(
                    this,
                    safe(src.getStringExtra(EXTRA_SESSION_ID)),
                    safe(src.getStringExtra(EXTRA_REQUEST_ID)),
                    safe(src.getStringExtra(EXTRA_CLIENT_ID)),
                    safe(src.getStringExtra(EXTRA_CLIENT_NAME)),
                    src.getBooleanExtra(EXTRA_WITH_MIC, false),
                    safe(src.getStringExtra(EXTRA_QUALITY)),
                    src.getIntExtra(EXTRA_FPS, 30));
        } else if (ACTION_RECORD.equals(action)) {
            RemoteScreenRecordService.start(
                    this,
                    safe(src.getStringExtra(EXTRA_TRANSFER_ID)),
                    safe(src.getStringExtra(EXTRA_RECORDING_ID)),
                    src.getBooleanExtra(EXTRA_WITH_MIC, false),
                    safe(src.getStringExtra(EXTRA_QUALITY)),
                    src.getIntExtra(EXTRA_FPS, 30));
            RemoteScreenMirrorConsentGate.leave();
        }
        finish();
    }

    @NonNull
    private static String safe(@Nullable String v) {
        return v == null ? "" : v.trim();
    }
}
