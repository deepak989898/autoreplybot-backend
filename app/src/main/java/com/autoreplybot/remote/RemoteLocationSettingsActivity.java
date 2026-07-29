package com.autoreplybot.remote;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.autoreplybot.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.switchmaterial.SwitchMaterial;

public class RemoteLocationSettingsActivity extends AppCompatActivity {
    private RemoteModulePrefs prefs;
    private TextView permissionStatus;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_remote_location_settings);
        prefs = new RemoteModulePrefs(this);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        permissionStatus = findViewById(R.id.txt_permission_status);
        SwitchMaterial enabled = findViewById(R.id.switch_location_enabled);
        Spinner mode = findViewById(R.id.spinner_location_mode);
        Spinner duration = findViewById(R.id.spinner_live_duration);
        Spinner retention = findViewById(R.id.spinner_history_retention);
        SwitchMaterial wifiOnly = findViewById(R.id.switch_wifi_only);

        String[] modes = new String[]{
                RemoteModulePrefs.MODE_DISABLED,
                RemoteModulePrefs.MODE_CURRENT_ONLY,
                RemoteModulePrefs.MODE_WHILE_OPEN,
                RemoteModulePrefs.MODE_DURING_SESSION,
                RemoteModulePrefs.MODE_TEMPORARY_LIVE,
                RemoteModulePrefs.MODE_BACKGROUND
        };
        mode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, modes));
        String[] durations = new String[]{"15m", "30m", "1h", "4h", "manual"};
        duration.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, durations));
        String[] retentions = new String[]{"none", "1d", "7d", "30d"};
        retention.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, retentions));

        enabled.setChecked(prefs.isLocationSharingEnabled());
        wifiOnly.setChecked(prefs.isLocationWifiOnly());
        select(mode, prefs.getLocationMode());
        selectDuration(duration, prefs.getLiveDurationMs());
        selectRetention(retention, prefs.getLocationHistoryRetentionDays());
        updatePermissionStatus();

        findViewById(R.id.btn_open_permissions_card).setOnClickListener(v ->
                RemotePermissionsNavigator.openPermissionsCard(this));
        findViewById(R.id.btn_stop_sharing).setOnClickListener(v -> {
            RemoteLocationSharingService.stop(this);
            Toast.makeText(this, R.string.remote_location_stopped, Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btn_clear_history).setOnClickListener(v ->
                new RemoteLocationRepository(this).clearHistory(() ->
                        runOnUiThread(() -> Toast.makeText(this, R.string.remote_location_history_cleared,
                                Toast.LENGTH_SHORT).show())));
        findViewById(R.id.btn_save_location).setOnClickListener(v -> {
            boolean on = enabled.isChecked();
            if (on && !RemotePermissionChecks.hasForegroundLocation(this)) {
                enabled.setChecked(false);
                RemotePermissionsNavigator.openPermissionsCard(this);
                return;
            }
            prefs.setLocationSharingEnabled(on);
            String selectedMode = String.valueOf(mode.getSelectedItem());
            if (!on) selectedMode = RemoteModulePrefs.MODE_DISABLED;
            prefs.setLocationMode(selectedMode);
            prefs.setLocationWifiOnly(wifiOnly.isChecked());
            prefs.setLiveDurationMs(durationToMs(String.valueOf(duration.getSelectedItem())));
            prefs.setLocationHistoryRetentionDays(retentionToDays(String.valueOf(retention.getSelectedItem())));
            new RemoteDeviceInfoRepository(this).publishCurrent(
                    RemoteDeviceInfoCollector.collect(this), () -> {});
            Toast.makeText(this, R.string.remote_saved, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();
    }

    private void updatePermissionStatus() {
        String text;
        if (RemotePermissionChecks.hasFineLocation(this)) {
            text = getString(R.string.remote_location_perm_precise);
        } else if (RemotePermissionChecks.hasCoarseLocation(this)) {
            text = getString(R.string.remote_location_perm_approx);
        } else {
            text = getString(R.string.remote_location_perm_denied)
                    + "\n" + getString(R.string.remote_perm_grant_on_home);
        }
        if (RemotePermissionChecks.hasBackgroundLocation(this)) {
            text = text + " · background OK";
        }
        permissionStatus.setText(text);
    }

    private static void select(Spinner spinner, String value) {
        ArrayAdapter<?> adapter = (ArrayAdapter<?>) spinner.getAdapter();
        for (int i = 0; i < adapter.getCount(); i++) {
            if (value.equals(String.valueOf(adapter.getItem(i)))) {
                spinner.setSelection(i);
                return;
            }
        }
    }

    private static void selectDuration(Spinner spinner, long ms) {
        String label = "15m";
        if (ms >= 4L * 60 * 60 * 1000) label = "4h";
        else if (ms >= 60L * 60 * 1000) label = "1h";
        else if (ms >= 30L * 60 * 1000) label = "30m";
        else if (ms <= 0) label = "manual";
        select(spinner, label);
    }

    private static void selectRetention(Spinner spinner, int days) {
        String label = "none";
        if (days == 1) label = "1d";
        else if (days == 7) label = "7d";
        else if (days == 30) label = "30d";
        select(spinner, label);
    }

    private static long durationToMs(String label) {
        switch (label) {
            case "30m": return 30L * 60 * 1000;
            case "1h": return 60L * 60 * 1000;
            case "4h": return 4L * 60 * 60 * 1000;
            case "manual": return 24L * 60 * 60 * 1000;
            default: return 15L * 60 * 1000;
        }
    }

    private static int retentionToDays(String label) {
        switch (label) {
            case "1d": return 1;
            case "7d": return 7;
            case "30d": return 30;
            default: return 0;
        }
    }
}
