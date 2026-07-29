package com.autoreplybot.remote;

import android.Manifest;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.autoreplybot.AppConstants;
import com.autoreplybot.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.messaging.FirebaseMessaging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RemoteControlHomeActivity extends AppCompatActivity {
    public static final String EXTRA_FOCUS_PERMISSIONS = "focus_permissions";
    public static final String EXTRA_FOCUS_MANAGEMENT = "focus_management";
    public static final String EXTRA_OPEN_FOLDER_PICKER = "open_folder_picker";

    private static final int LOCATION_HISTORY_DAYS = 30;

    private RemoteControlPrefs prefs;
    private RemoteModulePrefs modulePrefs;
    private RemoteDeviceRepository deviceRepository;
    private RemoteAuditRepository auditRepository;
    private SwitchMaterial switchEnabled;
    private TextInputEditText inputDeviceName;
    private Chip chipOnline;
    private Chip chipCamera;
    private Chip chipMicrophone;
    private Chip chipNotifications;
    private Chip chipLocation;
    private Chip chipLocationBg;
    private Chip chipGallery;
    private Chip chipNotificationAccess;
    private Chip chipFiles;
    private Chip chipSms;
    private Chip chipScreen;
    private Chip chipApps;
    private Chip chipAppControl;
    private View cardActiveSession;
    private View cardPermissions;
    private View cardManagement;
    private View bodyPermissions;
    private View bodyManagement;
    private TextView chevronPermissions;
    private TextView chevronManagement;
    private android.widget.TextView textActiveSession;
    private View progress;
    private boolean bindingUi;
    private boolean bindingManagement;

    private SwitchMaterial switchMgmtLocation;
    private SwitchMaterial switchMgmtGallery;
    private SwitchMaterial switchMgmtNotifications;
    private SwitchMaterial switchMgmtFiles;
    private RadioGroup radioLocationMode;
    private RadioButton radioModeCurrent;
    private RadioButton radioModeBackground;
    private LinearLayout mgmtFolderList;

    private final ActivityResultLauncher<String[]> runtimePermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        refreshPermissionChips();
                        refreshManagementUi();
                        if (Boolean.TRUE.equals(result.get(Manifest.permission.READ_SMS))) {
                            modulePrefs.setMessagesSharingEnabled(true);
                            new RemoteDeviceInfoRepository(this).publishModuleFlags();
                            new Thread(() -> RemoteSmsMirror.syncInbox(this, 100)).start();
                        }
                    });

    private final ActivityResultLauncher<String> backgroundLocationLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        refreshPermissionChips();
                        refreshManagementUi();
                    });

    private final ActivityResultLauncher<Uri> folderLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) return;
                RemoteFolderGrantHelper.grantFolder(this, uri);
                bindingManagement = true;
                if (switchMgmtFiles != null) switchMgmtFiles.setChecked(true);
                bindingManagement = false;
                Toast.makeText(this, R.string.remote_perm_folder_added, Toast.LENGTH_SHORT).show();
                refreshPermissionChips();
                reloadMgmtFolders();
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_remote_control_home);

        prefs = new RemoteControlPrefs(this);
        modulePrefs = new RemoteModulePrefs(this);
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
        chipLocation = findViewById(R.id.chip_location);
        chipLocationBg = findViewById(R.id.chip_location_bg);
        chipGallery = findViewById(R.id.chip_gallery);
        chipNotificationAccess = findViewById(R.id.chip_notification_access);
        chipFiles = findViewById(R.id.chip_files);
        chipSms = findViewById(R.id.chip_sms);
        chipScreen = findViewById(R.id.chip_screen);
        chipApps = findViewById(R.id.chip_apps);
        chipAppControl = findViewById(R.id.chip_app_control);
        cardActiveSession = findViewById(R.id.card_active_session);
        cardPermissions = findViewById(R.id.card_permissions);
        cardManagement = findViewById(R.id.card_management);
        bodyPermissions = findViewById(R.id.body_permissions);
        bodyManagement = findViewById(R.id.body_management);
        chevronPermissions = findViewById(R.id.chevron_permissions);
        chevronManagement = findViewById(R.id.chevron_management);
        textActiveSession = findViewById(R.id.text_active_session);

        wireAccordionHeaders();
        bindDeviceId();
        wirePermissionRows();
        wireManagementCard();

        findViewById(R.id.button_save_name).setOnClickListener(v -> saveName());
        findViewById(R.id.button_enable_missing_permissions).setOnClickListener(v ->
                enableMissingPermissions());
        findViewById(R.id.button_open_app_settings).setOnClickListener(v -> openAppSettings());
        findViewById(R.id.button_open_active_session).setOnClickListener(v -> {
            Intent open = new Intent(this, RemoteActiveSessionActivity.class);
            startActivity(open);
        });

        MaterialButton pair = findViewById(R.id.button_pair_website);
        MaterialButton trusted = findViewById(R.id.button_trusted_browsers);
        pair.setOnClickListener(v ->
                startActivity(new Intent(this, RemotePairActivity.class)));
        trusted.setOnClickListener(v ->
                startActivity(new Intent(this, RemoteTrustedClientsActivity.class)));

        switchEnabled.setOnCheckedChangeListener((button, checked) -> {
            if (!bindingUi) onEnableToggled(checked);
        });

        handleFocusExtras(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleFocusExtras(intent);
    }

    private void handleFocusExtras(@Nullable Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra(EXTRA_FOCUS_PERMISSIONS, false)) {
            expandSection(bodyPermissions, chevronPermissions);
            focusCard(cardPermissions);
        }
        if (intent.getBooleanExtra(EXTRA_FOCUS_MANAGEMENT, false)) {
            expandSection(bodyManagement, chevronManagement);
            focusCard(cardManagement);
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_FOLDER_PICKER, false)) {
            expandSection(bodyManagement, chevronManagement);
            focusCard(cardManagement);
            if (cardManagement != null) {
                cardManagement.post(() -> folderLauncher.launch(null));
            }
            intent.removeExtra(EXTRA_OPEN_FOLDER_PICKER);
        }
    }

    private void wireAccordionHeaders() {
        View headerPerm = findViewById(R.id.header_permissions);
        View headerMgmt = findViewById(R.id.header_management);
        if (headerPerm != null) {
            headerPerm.setOnClickListener(v ->
                    toggleSection(bodyPermissions, chevronPermissions, bodyManagement, chevronManagement));
        }
        if (headerMgmt != null) {
            headerMgmt.setOnClickListener(v ->
                    toggleSection(bodyManagement, chevronManagement, bodyPermissions, chevronPermissions));
        }
    }

    private void toggleSection(@Nullable View body,
                               @Nullable TextView chevron,
                               @Nullable View otherBody,
                               @Nullable TextView otherChevron) {
        if (body == null) return;
        boolean opening = body.getVisibility() != View.VISIBLE;
        setCollapsed(otherBody, otherChevron);
        if (opening) {
            body.setVisibility(View.VISIBLE);
            if (chevron != null) chevron.setText("▲");
        } else {
            setCollapsed(body, chevron);
        }
    }

    private void expandSection(@Nullable View body, @Nullable TextView chevron) {
        if (body == bodyPermissions) {
            setCollapsed(bodyManagement, chevronManagement);
        } else if (body == bodyManagement) {
            setCollapsed(bodyPermissions, chevronPermissions);
        }
        if (body != null) body.setVisibility(View.VISIBLE);
        if (chevron != null) chevron.setText("▲");
    }

    private static void setCollapsed(@Nullable View body, @Nullable TextView chevron) {
        if (body != null) body.setVisibility(View.GONE);
        if (chevron != null) chevron.setText("▼");
    }

    private void focusCard(@Nullable View card) {
        if (card == null) return;
        card.post(() -> {
            View scroll = findViewById(R.id.scroll_root);
            if (scroll instanceof androidx.core.widget.NestedScrollView) {
                ((androidx.core.widget.NestedScrollView) scroll)
                        .smoothScrollTo(0, Math.max(0, card.getTop() - 24));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        bindUi();
        refreshManagementUi();
        reloadMgmtFolders();
        if (prefs.isRemoteControlEnabled()) {
            deviceRepository.heartbeat(false);
            refreshFcmToken();
            RemoteModuleRuntime.start(this);
        }
    }

    private void wireManagementCard() {
        switchMgmtLocation = findViewById(R.id.switch_mgmt_location);
        switchMgmtGallery = findViewById(R.id.switch_mgmt_gallery);
        switchMgmtNotifications = findViewById(R.id.switch_mgmt_notifications);
        switchMgmtFiles = findViewById(R.id.switch_mgmt_files);
        radioLocationMode = findViewById(R.id.radio_mgmt_location_mode);
        radioModeCurrent = findViewById(R.id.radio_mgmt_mode_current);
        radioModeBackground = findViewById(R.id.radio_mgmt_mode_background);
        mgmtFolderList = findViewById(R.id.mgmt_folder_list);

        switchMgmtLocation.setOnCheckedChangeListener((b, checked) -> {
            if (bindingManagement) return;
            onLocationSharingToggled(checked);
        });
        radioLocationMode.setOnCheckedChangeListener((group, checkedId) -> {
            if (bindingManagement) return;
            if (!switchMgmtLocation.isChecked()) return;
            applyLocationModeFromUi(true);
        });
        switchMgmtGallery.setOnCheckedChangeListener((b, checked) -> {
            if (bindingManagement) return;
            onGalleryToggled(checked);
        });
        switchMgmtNotifications.setOnCheckedChangeListener((b, checked) -> {
            if (bindingManagement) return;
            onNotificationsToggled(checked);
        });
        switchMgmtFiles.setOnCheckedChangeListener((b, checked) -> {
            if (bindingManagement) return;
            onFilesToggled(checked);
        });
        findViewById(R.id.button_mgmt_choose_folder).setOnClickListener(v ->
                folderLauncher.launch(null));
        findViewById(R.id.button_mgmt_notif_sync).setOnClickListener(v -> syncNotificationsNow());
        refreshManagementUi();
    }

    private void refreshManagementUi() {
        if (switchMgmtLocation == null) return;
        bindingManagement = true;
        switchMgmtLocation.setChecked(modulePrefs.isLocationSharingEnabled());
        boolean background = RemoteModulePrefs.MODE_BACKGROUND.equals(modulePrefs.getLocationMode());
        if (background) {
            radioModeBackground.setChecked(true);
        } else {
            radioModeCurrent.setChecked(true);
        }
        switchMgmtGallery.setChecked(modulePrefs.isGalleryEnabled());
        switchMgmtNotifications.setChecked(modulePrefs.isNotificationMirrorEnabled());
        switchMgmtFiles.setChecked(modulePrefs.isFileManagerEnabled());
        bindingManagement = false;
    }

    private void onLocationSharingToggled(boolean enabled) {
        if (enabled && !RemotePermissionChecks.hasForegroundLocation(this)) {
            bindingManagement = true;
            switchMgmtLocation.setChecked(false);
            bindingManagement = false;
            Toast.makeText(this, R.string.remote_location_need_permission, Toast.LENGTH_LONG).show();
            expandSection(bodyPermissions, chevronPermissions);
            focusCard(cardPermissions);
            return;
        }
        if (enabled) {
            modulePrefs.setLocationSharingEnabled(true);
            modulePrefs.setLocationHistoryRetentionDays(LOCATION_HISTORY_DAYS);
            applyLocationModeFromUi(false);
            Toast.makeText(this, R.string.remote_location_sharing_on, Toast.LENGTH_SHORT).show();
        } else {
            modulePrefs.setLocationSharingEnabled(false);
            modulePrefs.setLocationMode(RemoteModulePrefs.MODE_DISABLED);
            RemoteLocationSharingService.stop(this);
            Toast.makeText(this, R.string.remote_location_sharing_off, Toast.LENGTH_SHORT).show();
            new RemoteDeviceInfoRepository(this).publishCurrent(
                    RemoteDeviceInfoCollector.collect(this), () -> {});
        }
        refreshPermissionChips();
    }

    private void applyLocationModeFromUi(boolean announce) {
        boolean wantBackground = radioModeBackground != null && radioModeBackground.isChecked();
        if (wantBackground) {
            if (!RemotePermissionChecks.hasBackgroundLocation(this)) {
                bindingManagement = true;
                radioModeCurrent.setChecked(true);
                bindingManagement = false;
                Toast.makeText(this, R.string.remote_location_need_background, Toast.LENGTH_LONG)
                        .show();
                expandSection(bodyPermissions, chevronPermissions);
                focusCard(cardPermissions);
                if (Build.VERSION.SDK_INT >= 29
                        && RemotePermissionChecks.hasForegroundLocation(this)) {
                    backgroundLocationLauncher.launch(
                            Manifest.permission.ACCESS_BACKGROUND_LOCATION);
                }
                wantBackground = false;
            }
        }
        String mode = wantBackground
                ? RemoteModulePrefs.MODE_BACKGROUND
                : RemoteModulePrefs.MODE_CURRENT_ONLY;
        modulePrefs.setLocationMode(mode);
        modulePrefs.setLocationHistoryRetentionDays(LOCATION_HISTORY_DAYS);
        if (modulePrefs.isLocationSharingEnabled()) {
            new RemoteDeviceInfoRepository(this).publishCurrent(
                    RemoteDeviceInfoCollector.collect(this), () -> {});
        }
        if (announce) {
            Toast.makeText(this, R.string.remote_saved, Toast.LENGTH_SHORT).show();
        }
    }

    private void onGalleryToggled(boolean enabled) {
        if (enabled && !RemotePermissionChecks.hasMediaAccess(this)) {
            bindingManagement = true;
            switchMgmtGallery.setChecked(false);
            bindingManagement = false;
            Toast.makeText(this, R.string.remote_gallery_need_permission, Toast.LENGTH_LONG).show();
            expandSection(bodyPermissions, chevronPermissions);
            focusCard(cardPermissions);
            return;
        }
        modulePrefs.setGalleryEnabled(enabled);
        new RemoteDeviceInfoRepository(this).publishModuleFlags();
        if (enabled) {
            new Thread(() -> {
                int n = new RemoteGalleryIndexer(this).indexAndSync("all");
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.remote_gallery_indexed, n),
                        Toast.LENGTH_SHORT).show());
            }).start();
        }
        refreshPermissionChips();
    }

    private void onNotificationsToggled(boolean enabled) {
        if (enabled && !RemotePermissionChecks.hasNotificationListener(this)) {
            bindingManagement = true;
            switchMgmtNotifications.setChecked(false);
            bindingManagement = false;
            Toast.makeText(this, R.string.remote_notif_need_permission, Toast.LENGTH_LONG).show();
            openNotificationListenerSettings();
            return;
        }
        modulePrefs.setNotificationMirrorEnabled(enabled);
        new RemoteDeviceInfoRepository(this).publishModuleFlags();
        if (enabled) {
            new Thread(() -> RemoteNotificationMirror.syncActive(this)).start();
            Toast.makeText(this, R.string.remote_notif_ready, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.remote_notif_disabled, Toast.LENGTH_SHORT).show();
        }
        refreshPermissionChips();
    }

    private void syncNotificationsNow() {
        if (!modulePrefs.isNotificationMirrorEnabled()) {
            Toast.makeText(this, R.string.remote_notif_disabled, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!RemotePermissionChecks.hasNotificationListener(this)) {
            Toast.makeText(this, R.string.remote_notif_need_permission, Toast.LENGTH_LONG).show();
            openNotificationListenerSettings();
            return;
        }
        new Thread(() -> {
            int n = RemoteNotificationMirror.syncActive(this);
            runOnUiThread(() -> Toast.makeText(this,
                    getString(R.string.remote_notif_synced, n),
                    Toast.LENGTH_SHORT).show());
        }).start();
    }

    private void onFilesToggled(boolean enabled) {
        if (enabled && !RemotePermissionChecks.hasFolderAccess(this)) {
            bindingManagement = true;
            switchMgmtFiles.setChecked(false);
            bindingManagement = false;
            folderLauncher.launch(null);
            return;
        }
        modulePrefs.setFileManagerEnabled(enabled);
        new RemoteDeviceInfoRepository(this).publishModuleFlags();
        refreshPermissionChips();
        reloadMgmtFolders();
    }

    private void reloadMgmtFolders() {
        if (mgmtFolderList == null) return;
        mgmtFolderList.removeAllViews();
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        String deviceId = prefs.getOrCreateDeviceId();
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
                            modulePrefs.removeFolderUri(grantId);
                            doc.getReference().delete();
                            reloadMgmtFolders();
                            refreshPermissionChips();
                            return true;
                        });
                        String uri = modulePrefs.getFolderUri(grantId);
                        if (uri.isEmpty()) {
                            Map<String, Object> patch = new HashMap<>();
                            patch.put("connected", false);
                            doc.getReference().update(patch);
                        }
                        mgmtFolderList.addView(row);
                    }
                    if (mgmtFolderList.getChildCount() == 0) {
                        TextView empty = new TextView(this);
                        empty.setText(R.string.remote_files_no_folders);
                        mgmtFolderList.addView(empty);
                    }
                });
    }

    private void wirePermissionRows() {
        findViewById(R.id.row_perm_camera).setOnClickListener(v ->
                requestRuntimeIfNeeded(new String[]{Manifest.permission.CAMERA},
                        RemotePermissionChecks.hasCamera(this)));
        findViewById(R.id.row_perm_microphone).setOnClickListener(v ->
                requestRuntimeIfNeeded(new String[]{Manifest.permission.RECORD_AUDIO},
                        RemotePermissionChecks.hasMicrophone(this)));
        findViewById(R.id.row_perm_notifications).setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT < 33 || RemotePermissionChecks.hasPostNotifications(this)) {
                toastAlreadyOrOpenSettings(RemotePermissionChecks.hasPostNotifications(this));
                return;
            }
            runtimePermissionLauncher.launch(new String[]{Manifest.permission.POST_NOTIFICATIONS});
        });
        findViewById(R.id.row_perm_location).setOnClickListener(v ->
                requestRuntimeIfNeeded(new String[]{
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                        },
                        RemotePermissionChecks.hasForegroundLocation(this)));
        findViewById(R.id.row_perm_location_bg).setOnClickListener(v -> {
            if (RemotePermissionChecks.hasBackgroundLocation(this)) {
                Toast.makeText(this, R.string.remote_perm_already_enabled, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!RemotePermissionChecks.hasForegroundLocation(this)) {
                runtimePermissionLauncher.launch(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                });
                return;
            }
            if (Build.VERSION.SDK_INT >= 29) {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
            } else {
                Toast.makeText(this, R.string.remote_perm_already_enabled, Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.row_perm_gallery).setOnClickListener(v -> {
            if (RemotePermissionChecks.hasMediaAccess(this)) {
                Toast.makeText(this, R.string.remote_perm_already_enabled, Toast.LENGTH_SHORT).show();
                return;
            }
            if (Build.VERSION.SDK_INT >= 33) {
                runtimePermissionLauncher.launch(new String[]{
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO,
                        Manifest.permission.READ_MEDIA_AUDIO
                });
            } else {
                runtimePermissionLauncher.launch(new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE
                });
            }
        });
        findViewById(R.id.row_perm_notification_access).setOnClickListener(v -> {
            if (RemotePermissionChecks.hasNotificationListener(this)) {
                Toast.makeText(this, R.string.remote_perm_already_enabled, Toast.LENGTH_SHORT).show();
            } else {
                openNotificationListenerSettings();
            }
        });
        findViewById(R.id.row_perm_files).setOnClickListener(v -> {
            if (RemotePermissionChecks.hasFolderAccess(this)) {
                Toast.makeText(this, R.string.remote_perm_already_enabled, Toast.LENGTH_SHORT).show();
            } else {
                RemotePermissionsNavigator.openFileManagerAccess(this);
            }
        });
        findViewById(R.id.row_perm_sms).setOnClickListener(v -> {
            if (RemotePermissionChecks.hasSmsAccess(this)) {
                Toast.makeText(this, R.string.remote_perm_already_enabled, Toast.LENGTH_SHORT).show();
                modulePrefs.setMessagesSharingEnabled(true);
                new RemoteDeviceInfoRepository(this).publishModuleFlags();
                return;
            }
            runtimePermissionLauncher.launch(new String[]{
                    Manifest.permission.READ_SMS,
                    Manifest.permission.RECEIVE_SMS
            });
        });
        findViewById(R.id.row_perm_screen).setOnClickListener(v -> {
            if (RemoteMediaProjectionHolder.hasValidResult()) {
                modulePrefs.setScreenMirrorEnabled(true);
                modulePrefs.setScreenRecordEnabled(true);
                new RemoteDeviceInfoRepository(this).publishModuleFlags();
                refreshPermissionChips();
                Toast.makeText(this, R.string.remote_perm_already_enabled, Toast.LENGTH_SHORT).show();
                return;
            }
            Intent consent = new Intent(this, RemoteMediaProjectionConsentActivity.class);
            consent.putExtra(RemoteMediaProjectionConsentActivity.EXTRA_CONTINUE_ACTION,
                    RemoteMediaProjectionConsentActivity.ACTION_STORE_ONLY);
            startActivity(consent);
        });
        findViewById(R.id.row_perm_apps).setOnClickListener(v -> {
            boolean enabled = !modulePrefs.isInstalledAppsSharingEnabled();
            modulePrefs.setInstalledAppsSharingEnabled(enabled);
            new RemoteDeviceInfoRepository(this).publishModuleFlags();
            refreshPermissionChips();
            Toast.makeText(this,
                    enabled ? R.string.status_enabled : R.string.status_disabled,
                    Toast.LENGTH_SHORT).show();
            if (enabled) {
                new Thread(() -> new RemoteInstalledAppsIndexer(this).indexAndSync()).start();
            }
        });
        View.OnClickListener openAppControlAccessibility = v -> {
            boolean a11y = RemoteAppBlockManager.isAccessibilityEnabled(this);
            modulePrefs.setAppControlEnabled(a11y);
            new RemoteDeviceInfoRepository(this).publishModuleFlags();
            refreshPermissionChips();
            // Always open Accessibility settings so user can turn App Control on/off.
            Toast.makeText(this, R.string.remote_app_control_setup_hint, Toast.LENGTH_SHORT).show();
            RemoteAppBlockManager.openAccessibilitySettings(this);
        };
        findViewById(R.id.row_perm_app_control).setOnClickListener(openAppControlAccessibility);
        if (chipAppControl != null) {
            chipAppControl.setOnClickListener(openAppControlAccessibility);
        }
        findViewById(R.id.button_open_device_admin).setOnClickListener(v -> {
            if (RemoteAppBlockManager.isDeviceAdminActive(this)) {
                Toast.makeText(this, R.string.remote_device_admin_already_on, Toast.LENGTH_LONG).show();
                // Still open settings so user can review/disable Device Admin.
                try {
                    startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
                } catch (Exception ignored) {
                }
                return;
            }
            boolean started = RemoteAppBlockManager.requestDeviceAdmin(this);
            if (!started) {
                Toast.makeText(this, R.string.remote_device_admin_open_failed, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, R.string.remote_device_admin_activate_hint, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void requestRuntimeIfNeeded(@NonNull String[] permissions, boolean alreadyGranted) {
        if (alreadyGranted) {
            Toast.makeText(this, R.string.remote_perm_already_enabled, Toast.LENGTH_SHORT).show();
            return;
        }
        runtimePermissionLauncher.launch(permissions);
    }

    private void toastAlreadyOrOpenSettings(boolean already) {
        if (already) {
            Toast.makeText(this, R.string.remote_perm_already_enabled, Toast.LENGTH_SHORT).show();
        } else {
            openAppSettings();
        }
    }

    private void enableMissingPermissions() {
        List<String> missing = new ArrayList<>();
        if (!RemotePermissionChecks.hasCamera(this)) missing.add(Manifest.permission.CAMERA);
        if (!RemotePermissionChecks.hasMicrophone(this)) missing.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && !RemotePermissionChecks.hasPostNotifications(this)) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!RemotePermissionChecks.hasForegroundLocation(this)) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (!RemotePermissionChecks.hasMediaAccess(this)) {
            if (Build.VERSION.SDK_INT >= 33) {
                missing.add(Manifest.permission.READ_MEDIA_IMAGES);
                missing.add(Manifest.permission.READ_MEDIA_VIDEO);
                missing.add(Manifest.permission.READ_MEDIA_AUDIO);
            } else {
                missing.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
        if (!RemotePermissionChecks.hasSmsAccess(this)) {
            missing.add(Manifest.permission.READ_SMS);
            missing.add(Manifest.permission.RECEIVE_SMS);
        }

        // Module toggles (not Android runtime permissions) — these were skipped before,
        // so "Installed apps sharing" stayed Disabled while the button said Already enabled.
        boolean enabledModule = false;
        if (!modulePrefs.isInstalledAppsSharingEnabled()) {
            modulePrefs.setInstalledAppsSharingEnabled(true);
            new RemoteDeviceInfoRepository(this).publishModuleFlags();
            new Thread(() -> new RemoteInstalledAppsIndexer(this).indexAndSync()).start();
            enabledModule = true;
        }
        boolean a11y = RemoteAppBlockManager.isAccessibilityEnabled(this);
        if (a11y && !modulePrefs.isAppControlEnabled()) {
            modulePrefs.setAppControlEnabled(true);
            new RemoteDeviceInfoRepository(this).publishModuleFlags();
            enabledModule = true;
        }
        if (!modulePrefs.isScreenMirrorEnabled() && !modulePrefs.isScreenRecordEnabled()
                && RemoteMediaProjectionHolder.hasValidResult()) {
            modulePrefs.setScreenMirrorEnabled(true);
            modulePrefs.setScreenRecordEnabled(true);
            new RemoteDeviceInfoRepository(this).publishModuleFlags();
            enabledModule = true;
        }

        if (!missing.isEmpty()) {
            runtimePermissionLauncher.launch(missing.toArray(new String[0]));
            refreshPermissionChips();
            return;
        }
        if (!RemotePermissionChecks.hasNotificationListener(this)) {
            openNotificationListenerSettings();
            refreshPermissionChips();
            return;
        }
        if (!RemotePermissionChecks.hasFolderAccess(this)) {
            RemotePermissionsNavigator.openFileManagerAccess(this);
            refreshPermissionChips();
            return;
        }
        if (RemotePermissionChecks.hasForegroundLocation(this)
                && !RemotePermissionChecks.hasBackgroundLocation(this)
                && Build.VERSION.SDK_INT >= 29) {
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
            refreshPermissionChips();
            return;
        }
        if (!a11y) {
            Toast.makeText(this, R.string.remote_app_control_setup_hint, Toast.LENGTH_LONG).show();
            RemoteAppBlockManager.openAccessibilitySettings(this);
            refreshPermissionChips();
            return;
        }
        if (!RemoteMediaProjectionHolder.hasValidResult()
                && !modulePrefs.isScreenMirrorEnabled()
                && !modulePrefs.isScreenRecordEnabled()) {
            Intent consent = new Intent(this, RemoteMediaProjectionConsentActivity.class);
            consent.putExtra(RemoteMediaProjectionConsentActivity.EXTRA_CONTINUE_ACTION,
                    RemoteMediaProjectionConsentActivity.ACTION_STORE_ONLY);
            startActivity(consent);
            refreshPermissionChips();
            return;
        }

        refreshPermissionChips();
        if (enabledModule) {
            Toast.makeText(this, R.string.status_enabled, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.remote_perm_already_enabled, Toast.LENGTH_SHORT).show();
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
        setGrantedChip(chipCamera, RemotePermissionChecks.hasCamera(this));
        setGrantedChip(chipMicrophone, RemotePermissionChecks.hasMicrophone(this));
        setGrantedChip(chipNotifications, RemotePermissionChecks.hasPostNotifications(this));
        setGrantedChip(chipLocation, RemotePermissionChecks.hasForegroundLocation(this));
        setGrantedChip(chipLocationBg, RemotePermissionChecks.hasBackgroundLocation(this));
        setGrantedChip(chipGallery, RemotePermissionChecks.hasMediaAccess(this));
        boolean nls = RemotePermissionChecks.hasNotificationListener(this);
        setGrantedChip(chipNotificationAccess, nls);
        setGrantedChip(chipFiles, RemotePermissionChecks.hasFolderAccess(this));
        boolean sms = RemotePermissionChecks.hasSmsAccess(this);
        setGrantedChip(chipSms, sms);
        setGrantedChip(chipScreen, RemoteMediaProjectionHolder.hasValidResult()
                || modulePrefs.isScreenMirrorEnabled()
                || modulePrefs.isScreenRecordEnabled());
        setGrantedChip(chipApps, modulePrefs.isInstalledAppsSharingEnabled());
        boolean a11y = RemoteAppBlockManager.isAccessibilityEnabled(this);
        setGrantedChip(chipAppControl, a11y);
        if (a11y && !modulePrefs.isAppControlEnabled()) {
            modulePrefs.setAppControlEnabled(true);
            new RemoteDeviceInfoRepository(this).publishModuleFlags();
        } else if (!a11y && modulePrefs.isAppControlEnabled()) {
            modulePrefs.setAppControlEnabled(false);
            new RemoteDeviceInfoRepository(this).publishModuleFlags();
        }
        if (sms && !modulePrefs.isMessagesSharingEnabled()) {
            modulePrefs.setMessagesSharingEnabled(true);
            new RemoteDeviceInfoRepository(this).publishModuleFlags();
            new Thread(() -> RemoteSmsMirror.syncInbox(this, 80)).start();
        }
        // If Notification Access is revoked, turn off website sharing. Enable via Management toggle.
        if (!nls && modulePrefs.isNotificationMirrorEnabled()) {
            modulePrefs.setNotificationMirrorEnabled(false);
            new RemoteDeviceInfoRepository(this).publishModuleFlags();
            refreshManagementUi();
        }
    }

    private void setGrantedChip(@Nullable Chip chip, boolean granted) {
        if (chip == null) return;
        applyStatusChip(chip, granted,
                getString(granted ? R.string.status_enabled : R.string.status_disabled));
    }

    private void onEnableToggled(boolean enabled) {
        progress.setVisibility(View.VISIBLE);
        if (enabled && !prefs.isPermissionSetupCompleted()) {
            progress.setVisibility(View.GONE);
            bindingUi = true;
            switchEnabled.setChecked(false);
            bindingUi = false;
            Intent setup = new Intent(this, RemotePermissionSetupActivity.class);
            setup.putExtra(RemotePermissionSetupActivity.EXTRA_CONTINUE_ENABLE, true);
            startActivity(setup);
            Toast.makeText(this, R.string.remote_setup_title, Toast.LENGTH_SHORT).show();
            return;
        }
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
                        RemoteModuleRuntime.start(RemoteControlHomeActivity.this);
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
            RemoteModuleRuntime.stop();
            RemoteLocationSharingService.stop(this);
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

    private void openNotificationListenerSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Exception e) {
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
