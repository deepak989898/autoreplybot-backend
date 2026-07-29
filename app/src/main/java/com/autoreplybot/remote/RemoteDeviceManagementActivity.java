package com.autoreplybot.remote;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.autoreplybot.AppConstants;
import com.autoreplybot.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.HashMap;
import java.util.Map;

/** Colorful accordion hub for location / gallery / files / etc. (inline, no page jumps). */
public class RemoteDeviceManagementActivity extends AppCompatActivity {
    public static final String EXTRA_FOCUS_FILES = "focus_files";

    private RemoteModulePrefs prefs;
    private View bodyLoc;
    private View bodyGallery;
    private View bodyNotif;
    private View bodyFiles;
    private TextView chevronLoc;
    private TextView chevronGallery;
    private TextView chevronNotif;
    private TextView chevronFiles;

    private TextView permissionStatus;
    private TextView galleryStatus;
    private TextView notifStatus;
    private LinearLayout folderList;
    private SwitchMaterial switchLocation;
    private SwitchMaterial switchWifiOnly;
    private SwitchMaterial switchGallery;
    private SwitchMaterial switchNotif;
    private SwitchMaterial switchFiles;
    private Spinner spinnerMode;
    private Spinner spinnerDuration;
    private Spinner spinnerRetention;

    private final ActivityResultLauncher<Uri> folderLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) return;
                RemoteFolderGrantHelper.grantFolder(this, uri);
                if (switchFiles != null) switchFiles.setChecked(true);
                Toast.makeText(this, R.string.remote_perm_folder_added, Toast.LENGTH_SHORT).show();
                reloadFolders();
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_remote_device_management);
        prefs = new RemoteModulePrefs(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        bodyLoc = findViewById(R.id.body_loc);
        bodyGallery = findViewById(R.id.body_gallery);
        bodyNotif = findViewById(R.id.body_notif);
        bodyFiles = findViewById(R.id.body_files);
        chevronLoc = findViewById(R.id.chevron_loc);
        chevronGallery = findViewById(R.id.chevron_gallery);
        chevronNotif = findViewById(R.id.chevron_notif);
        chevronFiles = findViewById(R.id.chevron_files);

        bindAccordion(findViewById(R.id.header_loc), bodyLoc, chevronLoc);
        bindAccordion(findViewById(R.id.header_gallery), bodyGallery, chevronGallery);
        bindAccordion(findViewById(R.id.header_notif), bodyNotif, chevronNotif);
        bindAccordion(findViewById(R.id.header_files), bodyFiles, chevronFiles);

        setupLocationPanel();
        setupGalleryPanel();
        setupNotifPanel();
        setupFilesPanel();

        if (getIntent().getBooleanExtra(EXTRA_FOCUS_FILES, false)) {
            focusFilesSection();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_FOCUS_FILES, false)) {
            focusFilesSection();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateLocationPermissionStatus();
        updateGalleryStatus();
        updateNotifStatus();
        if (switchNotif != null
                && prefs.isNotificationMirrorEnabled()
                && !RemotePermissionChecks.hasNotificationListener(this)) {
            prefs.setNotificationMirrorEnabled(false);
            switchNotif.setChecked(false);
            new RemoteDeviceInfoRepository(this).publishModuleFlags();
            updateNotifStatus();
        }
        reloadFolders();
    }

    private void focusFilesSection() {
        if (bodyFiles == null || chevronFiles == null) return;
        collapseAll();
        bodyFiles.setVisibility(View.VISIBLE);
        chevronFiles.setText("▲");
        View card = findViewById(R.id.card_files);
        if (card != null) {
            card.post(() -> card.requestFocus());
        }
    }

    private void bindAccordion(@NonNull View header,
                               @NonNull View body,
                               @NonNull TextView chevron) {
        header.setOnClickListener(v -> toggleSection(body, chevron));
    }

    private void toggleSection(@NonNull View body, @NonNull TextView chevron) {
        boolean opening = body.getVisibility() != View.VISIBLE;
        collapseAll();
        if (opening) {
            body.setVisibility(View.VISIBLE);
            chevron.setText("▲");
        }
    }

    private void collapseAll() {
        setCollapsed(bodyLoc, chevronLoc);
        setCollapsed(bodyGallery, chevronGallery);
        setCollapsed(bodyNotif, chevronNotif);
        setCollapsed(bodyFiles, chevronFiles);
    }

    private static void setCollapsed(@Nullable View body, @Nullable TextView chevron) {
        if (body != null) body.setVisibility(View.GONE);
        if (chevron != null) chevron.setText("▼");
    }

    private void setupLocationPanel() {
        permissionStatus = findViewById(R.id.txt_permission_status);
        switchLocation = findViewById(R.id.switch_location_enabled);
        switchWifiOnly = findViewById(R.id.switch_wifi_only);
        spinnerMode = findViewById(R.id.spinner_location_mode);
        spinnerDuration = findViewById(R.id.spinner_live_duration);
        spinnerRetention = findViewById(R.id.spinner_history_retention);

        String[] modes = new String[]{
                RemoteModulePrefs.MODE_DISABLED,
                RemoteModulePrefs.MODE_CURRENT_ONLY,
                RemoteModulePrefs.MODE_WHILE_OPEN,
                RemoteModulePrefs.MODE_DURING_SESSION,
                RemoteModulePrefs.MODE_TEMPORARY_LIVE,
                RemoteModulePrefs.MODE_BACKGROUND
        };
        spinnerMode.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, modes));
        spinnerDuration.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"15m", "30m", "1h", "4h", "manual"}));
        spinnerRetention.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"none", "1d", "7d", "30d"}));

        switchLocation.setChecked(prefs.isLocationSharingEnabled());
        switchWifiOnly.setChecked(prefs.isLocationWifiOnly());
        select(spinnerMode, prefs.getLocationMode());
        selectDuration(spinnerDuration, prefs.getLiveDurationMs());
        selectRetention(spinnerRetention, prefs.getLocationHistoryRetentionDays());
        updateLocationPermissionStatus();

        findViewById(R.id.btn_loc_permissions).setOnClickListener(v ->
                RemotePermissionsNavigator.openPermissionsCard(this));
        findViewById(R.id.btn_stop_sharing).setOnClickListener(v -> {
            RemoteLocationSharingService.stop(this);
            Toast.makeText(this, R.string.remote_location_stopped, Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btn_clear_history).setOnClickListener(v ->
                new RemoteLocationRepository(this).clearHistory(() ->
                        runOnUiThread(() -> Toast.makeText(this,
                                R.string.remote_location_history_cleared,
                                Toast.LENGTH_SHORT).show())));
        findViewById(R.id.btn_save_location).setOnClickListener(v -> {
            boolean on = switchLocation.isChecked();
            if (on && !RemotePermissionChecks.hasForegroundLocation(this)) {
                switchLocation.setChecked(false);
                RemotePermissionsNavigator.openPermissionsCard(this);
                return;
            }
            prefs.setLocationSharingEnabled(on);
            String selectedMode = String.valueOf(spinnerMode.getSelectedItem());
            if (!on) selectedMode = RemoteModulePrefs.MODE_DISABLED;
            prefs.setLocationMode(selectedMode);
            prefs.setLocationWifiOnly(switchWifiOnly.isChecked());
            prefs.setLiveDurationMs(durationToMs(String.valueOf(spinnerDuration.getSelectedItem())));
            prefs.setLocationHistoryRetentionDays(
                    retentionToDays(String.valueOf(spinnerRetention.getSelectedItem())));
            new RemoteDeviceInfoRepository(this).publishCurrent(
                    RemoteDeviceInfoCollector.collect(this), () -> {});
            Toast.makeText(this, R.string.remote_saved, Toast.LENGTH_SHORT).show();
        });
    }

    private void setupGalleryPanel() {
        galleryStatus = findViewById(R.id.txt_gallery_status);
        switchGallery = findViewById(R.id.switch_gallery_enabled);
        switchGallery.setChecked(prefs.isGalleryEnabled());
        switchGallery.setOnCheckedChangeListener((b, checked) -> {
            if (checked && !RemotePermissionChecks.hasMediaAccess(this)) {
                switchGallery.setChecked(false);
                RemotePermissionsNavigator.openPermissionsCard(this);
                return;
            }
            prefs.setGalleryEnabled(checked);
            new RemoteDeviceInfoRepository(this).publishModuleFlags();
            updateGalleryStatus();
            if (checked) indexGalleryNow();
        });
        findViewById(R.id.btn_gallery_permissions).setOnClickListener(v ->
                RemotePermissionsNavigator.openPermissionsCard(this));
        findViewById(R.id.btn_gallery_index).setOnClickListener(v -> {
            if (!prefs.isGalleryEnabled()) {
                Toast.makeText(this, R.string.remote_gallery_disabled, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!RemotePermissionChecks.hasMediaAccess(this)) {
                RemotePermissionsNavigator.openPermissionsCard(this);
                return;
            }
            indexGalleryNow();
        });
        updateGalleryStatus();
    }

    private void indexGalleryNow() {
        new Thread(() -> {
            int n = new RemoteGalleryIndexer(this).indexAndSync("all");
            runOnUiThread(() -> {
                galleryStatus.setText(getString(R.string.remote_gallery_indexed, n));
                Toast.makeText(this, R.string.remote_gallery_indexed_toast, Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private void updateGalleryStatus() {
        if (!prefs.isGalleryEnabled()) {
            galleryStatus.setText(R.string.remote_gallery_disabled);
        } else if (!RemotePermissionChecks.hasMediaAccess(this)) {
            galleryStatus.setText(getString(R.string.remote_gallery_need_permission)
                    + "\n" + getString(R.string.remote_perm_grant_on_home));
        } else {
            galleryStatus.setText(R.string.remote_gallery_ready);
        }
    }

    private void setupNotifPanel() {
        notifStatus = findViewById(R.id.txt_notif_status);
        switchNotif = findViewById(R.id.switch_notif_enabled);
        switchNotif.setChecked(prefs.isNotificationMirrorEnabled());
        switchNotif.setOnCheckedChangeListener((b, checked) -> {
            if (checked && !RemotePermissionChecks.hasNotificationListener(this)) {
                switchNotif.setChecked(false);
                RemotePermissionsNavigator.openPermissionsCard(this);
                return;
            }
            prefs.setNotificationMirrorEnabled(checked);
            new RemoteDeviceInfoRepository(this).publishModuleFlags();
            updateNotifStatus();
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
                    notifStatus.setText(getString(R.string.remote_notif_synced, n));
                    Toast.makeText(this, R.string.remote_notif_synced_toast, Toast.LENGTH_SHORT).show();
                });
            }).start();
        });
        updateNotifStatus();
    }

    private void updateNotifStatus() {
        if (!prefs.isNotificationMirrorEnabled()) {
            notifStatus.setText(R.string.remote_notif_disabled);
        } else if (!RemotePermissionChecks.hasNotificationListener(this)) {
            notifStatus.setText(getString(R.string.remote_notif_need_permission)
                    + "\n" + getString(R.string.remote_perm_grant_on_home));
        } else {
            notifStatus.setText(R.string.remote_notif_ready);
        }
    }

    private void setupFilesPanel() {
        folderList = findViewById(R.id.folder_list);
        switchFiles = findViewById(R.id.switch_files_enabled);
        switchFiles.setChecked(prefs.isFileManagerEnabled());
        switchFiles.setOnCheckedChangeListener((b, checked) -> {
            if (checked && !RemotePermissionChecks.hasFolderAccess(this)) {
                switchFiles.setChecked(false);
                folderLauncher.launch(null);
                return;
            }
            prefs.setFileManagerEnabled(checked);
            new RemoteDeviceInfoRepository(this).publishModuleFlags();
        });
        findViewById(R.id.btn_add_folder).setOnClickListener(v -> folderLauncher.launch(null));
        reloadFolders();
    }

    private void reloadFolders() {
        if (folderList == null) return;
        folderList.removeAllViews();
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        String deviceId = new RemoteControlPrefs(this).getOrCreateDeviceId();
        FirebaseFirestore.getInstance()
                .collection(AppConstants.FIRESTORE_USERS)
                .document(user.getUid())
                .collection(AppConstants.FIRESTORE_DEVICES)
                .document(deviceId)
                .collection(AppConstants.FIRESTORE_FOLDER_GRANTS)
                .get()
                .addOnSuccessListener(snap -> {
                    for (QueryDocumentSnapshot doc : snap) {
                        String grantId = doc.getId();
                        String name = String.valueOf(doc.get("displayName"));
                        TextView row = new TextView(this);
                        String shortId = grantId.substring(0, Math.min(8, grantId.length()));
                        row.setText(name + " (" + shortId + "…)");
                        row.setPadding(0, 16, 0, 16);
                        row.setOnLongClickListener(v -> {
                            prefs.removeFolderUri(grantId);
                            doc.getReference().delete();
                            reloadFolders();
                            return true;
                        });
                        String uri = prefs.getFolderUri(grantId);
                        if (uri.isEmpty()) {
                            Map<String, Object> patch = new HashMap<>();
                            patch.put("connected", false);
                            doc.getReference().update(patch);
                        }
                        folderList.addView(row);
                    }
                    if (folderList.getChildCount() == 0) {
                        TextView empty = new TextView(this);
                        empty.setText(R.string.remote_files_no_folders);
                        folderList.addView(empty);
                    }
                });
    }

    private void updateLocationPermissionStatus() {
        if (permissionStatus == null) return;
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
            case "30m":
                return 30L * 60 * 1000;
            case "1h":
                return 60L * 60 * 1000;
            case "4h":
                return 4L * 60 * 60 * 1000;
            case "manual":
                return 24L * 60 * 60 * 1000;
            default:
                return 15L * 60 * 1000;
        }
    }

    private static int retentionToDays(String label) {
        switch (label) {
            case "1d":
                return 1;
            case "7d":
                return 7;
            case "30d":
                return 30;
            default:
                return 0;
        }
    }
}
