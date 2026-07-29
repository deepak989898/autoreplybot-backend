package com.autoreplybot.remote;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.autoreplybot.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;

/**
 * Guided first-time remote-control permission setup.
 * Does not start camera/mic; only requests runtime permissions once when needed.
 */
public class RemotePermissionSetupActivity extends AppCompatActivity {
    public static final String EXTRA_CONTINUE_ENABLE = "continueEnable";

    private RemoteControlPrefs prefs;
    private TextView textStep;
    private TextView textDetail;
    private MaterialButton buttonPrimary;
    private MaterialButton buttonSkip;
    private int step;
    private boolean continueEnable;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> advanceAfterPermission());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_remote_permission_setup);
        prefs = new RemoteControlPrefs(this);
        continueEnable = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_CONTINUE_ENABLE, false);

        ((MaterialToolbar) findViewById(R.id.toolbar))
                .setNavigationOnClickListener(v -> finish());
        textStep = findViewById(R.id.text_setup_step);
        textDetail = findViewById(R.id.text_setup_detail);
        buttonPrimary = findViewById(R.id.button_setup_primary);
        buttonSkip = findViewById(R.id.button_setup_skip);

        buttonPrimary.setOnClickListener(v -> onPrimary());
        buttonSkip.setOnClickListener(v -> nextStep());
        step = 0;
        bindStep();
    }

    private void bindStep() {
        switch (step) {
            case 0:
                textStep.setText(R.string.remote_setup_step_camera);
                textDetail.setText(R.string.remote_setup_detail_camera);
                buttonPrimary.setText(R.string.remote_setup_grant);
                buttonSkip.setVisibility(View.VISIBLE);
                break;
            case 1:
                textStep.setText(R.string.remote_setup_step_mic);
                textDetail.setText(R.string.remote_setup_detail_mic);
                buttonPrimary.setText(R.string.remote_setup_grant);
                buttonSkip.setVisibility(View.VISIBLE);
                break;
            case 2:
                textStep.setText(R.string.remote_setup_step_notifications);
                textDetail.setText(R.string.remote_setup_detail_notifications);
                buttonPrimary.setText(R.string.remote_setup_grant);
                buttonSkip.setVisibility(View.VISIBLE);
                break;
            case 3:
                textStep.setText(R.string.remote_setup_step_service);
                textDetail.setText(R.string.remote_setup_detail_service);
                buttonPrimary.setText(R.string.remote_setup_continue);
                buttonSkip.setVisibility(View.GONE);
                break;
            case 4:
                textStep.setText(R.string.remote_setup_step_battery);
                textDetail.setText(R.string.remote_setup_detail_battery);
                buttonPrimary.setText(R.string.remote_setup_open_battery);
                buttonSkip.setVisibility(View.VISIBLE);
                buttonSkip.setText(R.string.remote_setup_skip);
                break;
            case 5:
                textStep.setText(R.string.remote_setup_step_pair);
                textDetail.setText(R.string.remote_setup_detail_pair);
                buttonPrimary.setText(R.string.remote_setup_open_pair);
                buttonSkip.setVisibility(View.VISIBLE);
                break;
            default:
                prefs.setPermissionSetupCompleted(true);
                if (continueEnable) {
                    setResult(RESULT_OK);
                }
                Toast.makeText(this, R.string.remote_setup_done, Toast.LENGTH_SHORT).show();
                finish();
                break;
        }
    }

    private void onPrimary() {
        switch (step) {
            case 0:
                requestIfNeeded(Manifest.permission.CAMERA);
                break;
            case 1:
                requestIfNeeded(Manifest.permission.RECORD_AUDIO);
                break;
            case 2:
                if (Build.VERSION.SDK_INT >= 33) {
                    requestIfNeeded(Manifest.permission.POST_NOTIFICATIONS);
                } else {
                    nextStep();
                }
                break;
            case 3:
                nextStep();
                break;
            case 4:
                openBatterySettings();
                nextStep();
                break;
            case 5:
                startActivity(new Intent(this, RemotePairActivity.class));
                nextStep();
                break;
            default:
                nextStep();
                break;
        }
    }

    private void requestIfNeeded(@NonNull String permission) {
        if (ContextCompat.checkSelfPermission(this, permission)
                == PackageManager.PERMISSION_GRANTED) {
            nextStep();
            return;
        }
        permissionLauncher.launch(new String[]{permission});
    }

    private void advanceAfterPermission() {
        nextStep();
    }

    private void nextStep() {
        step += 1;
        bindStep();
    }

    private void openBatterySettings() {
        try {
            Intent intent = new Intent();
            String pkg = getPackageName();
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && !pm.isIgnoringBatteryOptimizations(pkg)) {
                intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + pkg));
            } else {
                intent.setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            }
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.remote_setup_battery_failed, Toast.LENGTH_SHORT).show();
        }
    }
}
