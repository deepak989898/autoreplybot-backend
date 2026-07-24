package com.autoreplybot.remote;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.autoreplybot.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.messaging.FirebaseMessaging;

import java.util.Collections;

public class RemoteControlHomeActivity extends AppCompatActivity {
    private RemoteControlPrefs prefs;
    private RemoteDeviceRepository deviceRepository;
    private RemoteAuditRepository auditRepository;
    private SwitchMaterial switchEnabled;
    private TextInputEditText inputDeviceName;
    private Chip chipOnline;
    private Chip chipCamera;
    private Chip chipMicrophone;
    private Chip chipNotifications;
    private View cardActiveSession;
    private android.widget.TextView textActiveSession;
    private View progress;
    private boolean bindingUi;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_remote_control_home);

        prefs = new RemoteControlPrefs(this);
        deviceRepository = new RemoteDeviceRepository(this);
        auditRepository = new RemoteAuditRepository(this);

        ((MaterialToolbar) findViewById(R.id.toolbar))
                .setNavigationOnClickListener(v -> finish());
        progress = findViewById(R.id.progress);
        switchEnabled = findViewById(R.id.switch_remote_enabled);
        inputDeviceName = findViewById(R.id.input_device_name);
        chipOnline = findViewById(R.id.chip_online);
        chipCamera = findViewById(R.id.chip_camera);
        chipMicrophone = findViewById(R.id.chip_microphone);
        chipNotifications = findViewById(R.id.chip_notifications);
        cardActiveSession = findViewById(R.id.card_active_session);
        textActiveSession = findViewById(R.id.text_active_session);

        bindDeviceId();

        findViewById(R.id.button_save_name).setOnClickListener(v -> saveName());
        findViewById(R.id.button_open_app_settings).setOnClickListener(v -> openAppSettings());
        findViewById(R.id.button_open_notification_settings).setOnClickListener(v ->
                openNotificationSettings());
        findViewById(R.id.button_open_active_session).setOnClickListener(v -> {
            Intent open = new Intent(this, RemoteActiveSessionActivity.class);
            startActivity(open);
        });

        MaterialButton pair = findViewById(R.id.button_pair_website);
        MaterialButton trusted = findViewById(R.id.button_trusted_browsers);
        MaterialButton history = findViewById(R.id.button_session_history);
        pair.setOnClickListener(v ->
                startActivity(new Intent(this, RemotePairActivity.class)));
        trusted.setOnClickListener(v ->
                startActivity(new Intent(this, RemoteTrustedClientsActivity.class)));
        history.setOnClickListener(v ->
                Toast.makeText(this, R.string.remote_coming_later, Toast.LENGTH_SHORT).show());

        switchEnabled.setOnCheckedChangeListener((button, checked) -> {
            if (!bindingUi) onEnableToggled(checked);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        bindUi();
        if (prefs.isRemoteControlEnabled()) {
            deviceRepository.heartbeat(false);
            refreshFcmToken();
        }
    }

    private void bindDeviceId() {
        android.widget.TextView textDeviceId = findViewById(R.id.text_device_id);
        String id = prefs.getOrCreateDeviceId();
        textDeviceId.setText(truncateDeviceId(id));
    }

    private void bindUi() {
        bindingUi = true;
        switchEnabled.setChecked(prefs.isRemoteControlEnabled());
        inputDeviceName.setText(prefs.getDeviceDisplayName());
        applyStatusChip(chipOnline, prefs.isRemoteControlEnabled(),
                getString(prefs.isRemoteControlEnabled()
                        ? R.string.remote_status_online
                        : R.string.remote_status_offline));
        refreshPermissionChips();
        bindActiveSessionCard();
        bindingUi = false;
    }

    private void bindActiveSessionCard() {
        RemoteMediaForegroundService.SessionSnapshot snap =
                RemoteMediaForegroundService.getActiveSnapshot();
        if (snap != null && RemoteMediaForegroundService.isSessionActive()) {
            cardActiveSession.setVisibility(View.VISIBLE);
            textActiveSession.setText(getString(
                    R.string.remote_active_session_running, snap.clientName));
        } else {
            cardActiveSession.setVisibility(View.GONE);
            textActiveSession.setText(R.string.remote_active_session_none);
        }
    }

    private void refreshPermissionChips() {
        boolean cameraGranted = isPermissionGranted(Manifest.permission.CAMERA);
        boolean micGranted = isPermissionGranted(Manifest.permission.RECORD_AUDIO);
        boolean notificationsGranted = Build.VERSION.SDK_INT < 33
                || isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS);
        applyStatusChip(chipCamera, cameraGranted,
                getString(cameraGranted ? R.string.status_enabled : R.string.status_disabled));
        applyStatusChip(chipMicrophone, micGranted,
                getString(micGranted ? R.string.status_enabled : R.string.status_disabled));
        applyStatusChip(chipNotifications, notificationsGranted,
                getString(notificationsGranted ? R.string.status_enabled : R.string.status_disabled));
    }

    private boolean isPermissionGranted(@NonNull String permission) {
        return ContextCompat.checkSelfPermission(this, permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void onEnableToggled(boolean enabled) {
        progress.setVisibility(View.VISIBLE);
        prefs.setRemoteControlEnabled(enabled);
        if (enabled) {
            refreshFcmToken();
            deviceRepository.registerOrUpdateDevice()
                    .addOnSuccessListener(ignored -> {
                        auditRepository.append(
                                RemoteAuditAction.REMOTE_CONTROL_ENABLED,
                                prefs.getOrCreateDeviceId(),
                                null, null, "ok",
                                Collections.emptyMap());
                        deviceRepository.heartbeat(true);
                        progress.setVisibility(View.GONE);
                        bindUi();
                        Toast.makeText(this, R.string.remote_device_registered, Toast.LENGTH_SHORT)
                                .show();
                    })
                    .addOnFailureListener(error -> {
                        prefs.setRemoteControlEnabled(false);
                        progress.setVisibility(View.GONE);
                        bindUi();
                        Toast.makeText(this,
                                getString(R.string.remote_error, error.getMessage()),
                                Toast.LENGTH_LONG).show();
                    });
        } else {
            deviceRepository.setOnline(false)
                    .addOnCompleteListener(task -> {
                        auditRepository.append(
                                RemoteAuditAction.REMOTE_CONTROL_DISABLED,
                                prefs.getOrCreateDeviceId(),
                                null, null,
                                task.isSuccessful() ? "ok" : "partial",
                                Collections.emptyMap());
                        progress.setVisibility(View.GONE);
                        bindUi();
                        if (!task.isSuccessful() && task.getException() != null) {
                            Toast.makeText(this,
                                    getString(R.string.remote_error,
                                            task.getException().getMessage()),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
        }
    }

    private void saveName() {
        CharSequence raw = inputDeviceName.getText();
        String name = raw != null ? raw.toString().trim() : "";
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.remote_name_required, Toast.LENGTH_SHORT).show();
            return;
        }
        progress.setVisibility(View.VISIBLE);
        deviceRepository.updateDisplayName(name)
                .addOnCompleteListener(task -> {
                    progress.setVisibility(View.GONE);
                    bindUi();
                    if (task.isSuccessful()) {
                        Toast.makeText(this, R.string.remote_name_saved, Toast.LENGTH_SHORT).show();
                    } else {
                        Exception error = task.getException();
                        Toast.makeText(this,
                                getString(R.string.remote_error,
                                        error != null ? error.getMessage() : "unknown"),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void refreshFcmToken() {
        FirebaseMessaging.getInstance().getToken()
                .addOnSuccessListener(token -> {
                    if (token != null && !token.trim().isEmpty()) {
                        deviceRepository.updateFcmToken(token);
                    }
                });
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", getPackageName(), null));
        startActivity(intent);
    }

    private void openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } else {
            openAppSettings();
        }
    }

    @NonNull
    private static String truncateDeviceId(@NonNull String id) {
        if (id.length() <= 16) return id;
        return id.substring(0, 8) + "…" + id.substring(id.length() - 4);
    }

    private void applyStatusChip(@NonNull Chip chip, boolean positive, @NonNull String label) {
        chip.setText(label);
        int bg = ContextCompat.getColor(this,
                positive ? R.color.status_positive_container : R.color.status_negative_container);
        int fg = ContextCompat.getColor(this,
                positive ? R.color.status_positive_text : R.color.status_negative_text);
        chip.setChipBackgroundColor(ColorStateList.valueOf(bg));
        chip.setTextColor(fg);
    }
}
