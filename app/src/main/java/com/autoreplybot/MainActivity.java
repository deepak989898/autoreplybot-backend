package com.autoreplybot;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;

import com.autoreplybot.remote.RemoteControlHomeActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class MainActivity extends AppCompatActivity {

    private TextView textEmail;
    private TextView textNotificationHint;
    private Chip chipNotificationAccess;
    private TextView textMessagingAppsValue;
    private SwitchMaterial switchMaster;
    private Spinner spinnerDefaultMode;
    private boolean bindingDashboard;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        setContentView(R.layout.activity_main);

        textEmail = findViewById(R.id.text_email);
        textNotificationHint = findViewById(R.id.text_notification_hint);
        chipNotificationAccess = findViewById(R.id.chip_notification_access);
        textMessagingAppsValue = findViewById(R.id.text_messaging_apps_value);
        switchMaster = findViewById(R.id.switch_master);
        spinnerDefaultMode = findViewById(R.id.spinner_default_mode);

        MaterialButton buttonSettings = findViewById(R.id.button_settings);
        MaterialButton buttonFacebookPosting = findViewById(R.id.button_facebook_posting);
        MaterialButton buttonRemoteControl = findViewById(R.id.button_remote_control);
        MaterialButton buttonSignOut = findViewById(R.id.button_sign_out);

        String emailOrId = user.getEmail() != null ? user.getEmail() : user.getUid();
        textEmail.setText(emailOrId);

        // Status chip opens the same system Notification Listener settings screen.
        chipNotificationAccess.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));

        buttonSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        buttonFacebookPosting.setOnClickListener(v ->
                startActivity(new Intent(this, FacebookPostingActivity.class)));
        buttonRemoteControl.setOnClickListener(v ->
                startActivity(new Intent(this, RemoteControlHomeActivity.class)));
        ArrayAdapter<CharSequence> modeAdapter = ArrayAdapter.createFromResource(this,
                R.array.reply_mode_options, android.R.layout.simple_spinner_item);
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDefaultMode.setAdapter(modeAdapter);
        switchMaster.setOnCheckedChangeListener((button, checked) -> {
            if (!bindingDashboard) updateDashboardSettings(checked, null);
        });
        spinnerDefaultMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!bindingDashboard) updateDashboardSettings(null, ReplyMode.values()[position]);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        buttonSignOut.setOnClickListener(v -> {
            FirebaseAuth.getInstance().signOut();
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        SettingsRepository repo = new SettingsRepository(this);
        repo.pullFromFirestore().addOnCompleteListener(t -> refreshStatus());
    }

    private void refreshStatus() {
        boolean listener = NotificationAccess.isEnabled(this);
        SettingsRepository repo = new SettingsRepository(this);
        UserSettings s = repo.readCached();
        applyStatusChip(chipNotificationAccess, listener,
                getString(listener ? R.string.status_enabled : R.string.status_disabled));
        textNotificationHint.setText(listener
                ? getString(R.string.main_row_notification_hint_on)
                : getString(R.string.main_row_notification_hint_off));

        textMessagingAppsValue.setText(summarizeEnabledApps(s));
        bindingDashboard = true;
        switchMaster.setChecked(s.isMasterEnabled());
        spinnerDefaultMode.setSelection(s.getDefaultReplyMode().ordinal(), false);
        bindingDashboard = false;
    }

    private void updateDashboardSettings(@Nullable Boolean master, @Nullable ReplyMode mode) {
        SettingsRepository repository = new SettingsRepository(this);
        UserSettings settings = repository.readCached();
        if (master != null) settings.setMasterEnabled(master);
        if (mode != null) settings.setDefaultReplyMode(mode);
        repository.cacheLocally(settings);
        repository.pushToFirestore(settings).addOnFailureListener(error ->
                Toast.makeText(this, R.string.dashboard_save_failed, Toast.LENGTH_LONG).show());
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

    @NonNull
    private String summarizeEnabledApps(@NonNull UserSettings s) {
        List<String> pkgs = new ArrayList<>(s.getEnabledPackageNames());
        if (pkgs.isEmpty()) {
            return getString(R.string.status_apps_none);
        }
        Collections.sort(pkgs, Comparator.comparing(this::labelForPackage,
                String.CASE_INSENSITIVE_ORDER));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < pkgs.size(); i++) {
            if (i > 0) {
                b.append(", ");
            }
            b.append(labelForPackage(pkgs.get(i)));
        }
        return b.toString();
    }

    @NonNull
    private String labelForPackage(@NonNull String packageName) {
        try {
            CharSequence label = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(packageName, 0));
            if (label != null && label.length() > 0) {
                return label.toString();
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return packageName;
    }
}
