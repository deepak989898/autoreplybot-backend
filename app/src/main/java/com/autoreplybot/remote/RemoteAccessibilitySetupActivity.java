package com.autoreplybot.remote;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.autoreplybot.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.firebase.auth.FirebaseAuth;

/**
 * Setup screen for remote accessibility control.
 * Opens system Accessibility settings manually — never auto-enables the service.
 */
public class RemoteAccessibilitySetupActivity extends AppCompatActivity {
    private RemoteAccessibilityPrefs prefs;
    private SwitchMaterial enabledSwitch;
    private TextView statusText;
    private MaterialButton openSettingsButton;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_remote_accessibility_setup);
        prefs = new RemoteAccessibilityPrefs(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        enabledSwitch = findViewById(R.id.switch_a11y_enabled);
        statusText = findViewById(R.id.text_a11y_status);
        openSettingsButton = findViewById(R.id.button_open_a11y_settings);

        enabledSwitch.setChecked(prefs.isAccessibilityControlEnabled());
        enabledSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (checked && !isRemoteAccessibilityServiceEnabled()) {
                enabledSwitch.setChecked(false);
                Toast.makeText(this,
                        "Enable AutoReplyBot Remote Accessibility in system settings first.",
                        Toast.LENGTH_LONG).show();
                openAccessibilitySettings();
                return;
            }
            prefs.setAccessibilityControlEnabled(checked);
            if (!checked) {
                new RemoteAccessibilitySessionManager(this).stopSession("DISABLED_IN_APP");
            }
            new RemoteDeviceInfoRepository(this).publishModuleFlags();
            refreshStatus();
        });

        openSettingsButton.setOnClickListener(v -> openAccessibilitySettings());

        findViewById(R.id.button_stop_session).setOnClickListener(v -> {
            new RemoteAccessibilitySessionManager(this).stopSession("USER_STOPPED_IN_APP");
            refreshStatus();
            Toast.makeText(this, "Session stopped", Toast.LENGTH_SHORT).show();
        });

        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs.isAccessibilityControlEnabled() && !isRemoteAccessibilityServiceEnabled()) {
            prefs.setAccessibilityControlEnabled(false);
            enabledSwitch.setChecked(false);
            new RemoteDeviceInfoRepository(this).publishModuleFlags();
        } else if (!prefs.isAccessibilityControlEnabled() && isRemoteAccessibilityServiceEnabled()) {
            prefs.setAccessibilityControlEnabled(true);
            enabledSwitch.setChecked(true);
            new RemoteDeviceInfoRepository(this).publishModuleFlags();
        }
        refreshStatus();
    }

    private void refreshStatus() {
        boolean moduleOn = prefs.isAccessibilityControlEnabled();
        boolean serviceOn = isRemoteAccessibilityServiceEnabled();
        boolean sessionActive = prefs.hasActiveSession() && RemoteAccessibilityService.isConnected();

        enabledSwitch.setChecked(moduleOn);
        StringBuilder sb = new StringBuilder();
        sb.append("Module: ").append(moduleOn ? "enabled" : "disabled").append('\n');
        sb.append("Accessibility service: ").append(serviceOn ? "on" : "off").append('\n');
        sb.append("Service connected: ")
                .append(RemoteAccessibilityService.isConnected() ? "yes" : "no")
                .append('\n');
        if (sessionActive) {
            sb.append("Active session: ").append(prefs.getActiveSessionId()).append('\n');
            long expires = prefs.getActiveSessionExpiresAt();
            if (expires > 0) {
                long remainingMin = Math.max(0L, (expires - System.currentTimeMillis()) / 60_000L);
                sb.append("Expires in ~").append(remainingMin).append(" min");
            }
        } else {
            sb.append("No active session");
        }
        statusText.setText(sb.toString());

        openSettingsButton.setEnabled(!serviceOn || !moduleOn);
    }

    private void openAccessibilitySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open Accessibility settings", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isRemoteAccessibilityServiceEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        ComponentName expected = new ComponentName(this, RemoteAccessibilityService.class);
        for (AccessibilityServiceInfo info : am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (info.getResolveInfo() == null || info.getResolveInfo().serviceInfo == null) continue;
            ComponentName cn = new ComponentName(
                    info.getResolveInfo().serviceInfo.packageName,
                    info.getResolveInfo().serviceInfo.name);
            if (expected.equals(cn)) {
                return true;
            }
        }
        String flat = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(flat)) return false;
        String expectedFlat = expected.flattenToString();
        for (String part : flat.split(":")) {
            if (expectedFlat.equalsIgnoreCase(part.trim())) return true;
        }
        return false;
    }
}
