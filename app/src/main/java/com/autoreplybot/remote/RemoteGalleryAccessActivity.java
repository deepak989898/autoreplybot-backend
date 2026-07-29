package com.autoreplybot.remote;

import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.autoreplybot.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.switchmaterial.SwitchMaterial;

public class RemoteGalleryAccessActivity extends AppCompatActivity {
    private RemoteModulePrefs prefs;
    private TextView status;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_remote_gallery_access);
        prefs = new RemoteModulePrefs(this);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        status = findViewById(R.id.txt_gallery_status);
        SwitchMaterial enabled = findViewById(R.id.switch_gallery_enabled);
        enabled.setChecked(prefs.isGalleryEnabled());
        enabled.setOnCheckedChangeListener((b, checked) -> {
            if (checked && !RemotePermissionChecks.hasMediaAccess(this)) {
                enabled.setChecked(false);
                RemotePermissionsNavigator.openPermissionsCard(this);
                return;
            }
            prefs.setGalleryEnabled(checked);
            new RemoteDeviceInfoRepository(this).publishModuleFlags();
            updateStatus();
            if (checked) indexNow();
        });
        findViewById(R.id.btn_gallery_index).setOnClickListener(v -> {
            if (!prefs.isGalleryEnabled()) {
                Toast.makeText(this, R.string.remote_gallery_disabled, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!RemotePermissionChecks.hasMediaAccess(this)) {
                RemotePermissionsNavigator.openPermissionsCard(this);
                return;
            }
            indexNow();
        });
        findViewById(R.id.btn_open_permissions_card).setOnClickListener(v ->
                RemotePermissionsNavigator.openPermissionsCard(this));
        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void indexNow() {
        new Thread(() -> {
            int n = new RemoteGalleryIndexer(this).indexAndSync("all");
            runOnUiThread(() -> {
                status.setText(getString(R.string.remote_gallery_indexed, n));
                Toast.makeText(this, R.string.remote_gallery_indexed_toast, Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private void updateStatus() {
        if (!prefs.isGalleryEnabled()) {
            status.setText(R.string.remote_gallery_disabled);
        } else if (!RemotePermissionChecks.hasMediaAccess(this)) {
            status.setText(getString(R.string.remote_gallery_need_permission)
                    + "\n" + getString(R.string.remote_perm_grant_on_home));
        } else {
            status.setText(R.string.remote_gallery_ready);
        }
    }
}
