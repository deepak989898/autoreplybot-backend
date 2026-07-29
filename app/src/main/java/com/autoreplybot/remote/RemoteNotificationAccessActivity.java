package com.autoreplybot.remote;

import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.autoreplybot.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.switchmaterial.SwitchMaterial;

/** Feature toggle for notification mirroring. Permission is granted on the home Permissions card. */
public class RemoteNotificationAccessActivity extends AppCompatActivity {
    private RemoteModulePrefs prefs;
    private TextView status;
    private SwitchMaterial enabled;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_remote_notification_access);
        prefs = new RemoteModulePrefs(this);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        status = findViewById(R.id.txt_notif_status);
        enabled = findViewById(R.id.switch_notif_enabled);
        enabled.setChecked(prefs.isNotificationMirrorEnabled());
        enabled.setOnCheckedChangeListener((b, checked) -> {
            if (checked && !RemotePermissionChecks.hasNotificationListener(this)) {
                enabled.setChecked(false);
                RemotePermissionsNavigator.openPermissionsCard(this);
                return;
            }
            prefs.setNotificationMirrorEnabled(checked);
            new RemoteDeviceInfoRepository(this).publishModuleFlags();
            updateStatus();
            if (checked) {
                new Thread(() -> RemoteNotificationMirror.syncActive(this)).start();
            }
        });
        findViewById(R.id.btn_notif_permission).setOnClickListener(v ->
                RemotePermissionsNavigator.openPermissionsCard(this));
        findViewById(R.id.btn_notif_sync).setOnClickListener(v -> {
            if (!prefs.isNotificationMirrorEnabled()) {
                Toast.makeText(this, R.string.remote_notif_disabled, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!RemotePermissionChecks.hasNotificationListener(this)) {
                RemotePermissionsNavigator.openPermissionsCard(this);
                return;
            }
            new Thread(() -> {
                int n = RemoteNotificationMirror.syncActive(this);
                runOnUiThread(() -> {
                    status.setText(getString(R.string.remote_notif_synced, n));
                    Toast.makeText(this, R.string.remote_notif_synced_toast, Toast.LENGTH_SHORT).show();
                });
            }).start();
        });
        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        if (prefs.isNotificationMirrorEnabled()
                && !RemotePermissionChecks.hasNotificationListener(this)) {
            prefs.setNotificationMirrorEnabled(false);
            enabled.setChecked(false);
            new RemoteDeviceInfoRepository(this).publishModuleFlags();
            updateStatus();
        }
    }

    private void updateStatus() {
        if (!prefs.isNotificationMirrorEnabled()) {
            status.setText(R.string.remote_notif_disabled);
        } else if (!RemotePermissionChecks.hasNotificationListener(this)) {
            status.setText(getString(R.string.remote_notif_need_permission)
                    + "\n" + getString(R.string.remote_perm_grant_on_home));
        } else {
            status.setText(R.string.remote_notif_ready);
        }
    }
}
