package com.autoreplybot.remote;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.autoreplybot.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GetTokenResult;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RemotePairActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final RemotePairApi pairApi = new RemotePairApi();

    private RemoteControlPrefs prefs;
    private TextInputEditText inputCode;
    private TextInputEditText inputToken;
    private View progress;
    private MaterialButton buttonContinue;
    private MaterialButton buttonScanQr;
    private MaterialCheckBox checkTrust;
    private MaterialCheckBox checkPersistent;
    private MaterialCheckBox checkAutoApprove;
    private MaterialCheckBox checkCamera;
    private MaterialCheckBox checkMic;
    private MaterialCheckBox checkPhoto;
    private MaterialCheckBox checkVideo;
    private MaterialCheckBox checkAudio;
    private MaterialCheckBox checkTorch;
    private MaterialCheckBox checkLocation;
    private MaterialCheckBox checkDeviceInfo;
    private MaterialCheckBox checkGallery;
    private MaterialCheckBox checkNotifications;
    private MaterialCheckBox checkMessages;
    private MaterialCheckBox checkCallLogs;
    private MaterialCheckBox checkContacts;
    private MaterialCheckBox checkFiles;
    private MaterialCheckBox checkScreenMirror;
    private MaterialCheckBox checkScreenRecord;
    private MaterialCheckBox checkInstalledApps;
    private MaterialCheckBox checkAppUsage;
    private MaterialCheckBox checkAppControl;
    private MaterialCheckBox checkRemoteA11y;
    private MaterialCheckBox checkDirectTouch;
    private MaterialCheckBox checkSmartElements;
    private MaterialCheckBox checkTextInput;
    private MaterialCheckBox checkAppLaunch;
    private MaterialCheckBox checkGlobalNav;

    private final ActivityResultLauncher<Intent> qrScanLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                String raw = result.getData().getStringExtra(RemoteQrScanActivity.EXTRA_RAW_VALUE);
                if (TextUtils.isEmpty(raw)) return;
                applyScannedPayload(raw);
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_remote_pair);

        prefs = new RemoteControlPrefs(this);
        ((MaterialToolbar) findViewById(R.id.toolbar))
                .setNavigationOnClickListener(v -> finish());
        progress = findViewById(R.id.progress);
        inputCode = findViewById(R.id.input_pair_code);
        inputToken = findViewById(R.id.input_pair_token);
        buttonContinue = findViewById(R.id.button_pair_continue);
        buttonScanQr = findViewById(R.id.button_scan_qr);
        checkTrust = findViewById(R.id.check_trust_browser);
        checkPersistent = findViewById(R.id.check_persistent_pairing);
        checkAutoApprove = findViewById(R.id.check_auto_approve);
        checkCamera = findViewById(R.id.check_allow_camera);
        checkMic = findViewById(R.id.check_allow_microphone);
        checkPhoto = findViewById(R.id.check_allow_photo);
        checkVideo = findViewById(R.id.check_allow_video);
        checkAudio = findViewById(R.id.check_allow_audio);
        checkTorch = findViewById(R.id.check_allow_torch);
        checkLocation = findViewById(R.id.check_allow_location);
        checkDeviceInfo = findViewById(R.id.check_allow_device_info);
        checkGallery = findViewById(R.id.check_allow_gallery);
        checkNotifications = findViewById(R.id.check_allow_notifications);
        checkMessages = findViewById(R.id.check_allow_messages);
        checkCallLogs = findViewById(R.id.check_allow_call_logs);
        checkContacts = findViewById(R.id.check_allow_contacts);
        checkFiles = findViewById(R.id.check_allow_files);
        checkScreenMirror = findViewById(R.id.check_allow_screen_mirror);
        checkScreenRecord = findViewById(R.id.check_allow_screen_record);
        checkInstalledApps = findViewById(R.id.check_allow_installed_apps);
        checkAppUsage = findViewById(R.id.check_allow_app_usage);
        checkAppControl = findViewById(R.id.check_allow_app_control);
        checkRemoteA11y = findViewById(R.id.check_allow_remote_a11y);
        checkDirectTouch = findViewById(R.id.check_allow_direct_touch);
        checkSmartElements = findViewById(R.id.check_allow_smart_elements);
        checkTextInput = findViewById(R.id.check_allow_text_input);
        checkAppLaunch = findViewById(R.id.check_allow_app_launch);
        checkGlobalNav = findViewById(R.id.check_allow_global_nav);

        hidePairTrustOptionsUi();

        applyDeepLink(getIntent());

        buttonContinue.setOnClickListener(v -> onContinueClicked());
        buttonScanQr.setOnClickListener(v ->
                qrScanLauncher.launch(new Intent(this, RemoteQrScanActivity.class)));
    }

    private void hidePairTrustOptionsUi() {
        int gone = View.GONE;
        View editTrusted = findViewById(R.id.button_edit_trusted);
        if (editTrusted != null) editTrusted.setVisibility(gone);
        for (int id : new int[]{
                R.id.header_pair_trust, R.id.body_pair_trust,
                R.id.header_pair_media, R.id.body_pair_media,
                R.id.header_pair_modules, R.id.body_pair_modules
        }) {
            View v = findViewById(id);
            if (v != null) v.setVisibility(gone);
        }
    }

    private void wireAccordion(@Nullable View header,
                               @Nullable View body,
                               @Nullable TextView chevron) {
        if (header == null || body == null) return;
        setCollapsed(body, chevron);
        header.setOnClickListener(v -> toggleAccordion(body, chevron));
    }

    private void toggleAccordion(@NonNull View body, @Nullable TextView chevron) {
        if (body.getVisibility() == View.VISIBLE) {
            setCollapsed(body, chevron);
        } else {
            body.setVisibility(View.VISIBLE);
            if (chevron != null) chevron.setText("▲");
        }
    }

    private static void setCollapsed(@Nullable View body, @Nullable TextView chevron) {
        if (body != null) body.setVisibility(View.GONE);
        if (chevron != null) chevron.setText("▼");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyDeepLink(intent);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void applyDeepLink(@Nullable Intent intent) {
        if (intent == null) return;
        Uri data = intent.getData();
        if (data == null) return;
        if (!"autoreplybot".equals(data.getScheme()) || !"pair".equals(data.getHost())) {
            return;
        }
        String code = data.getQueryParameter("code");
        String token = data.getQueryParameter("token");
        if (!TextUtils.isEmpty(code)) {
            inputCode.setText(code);
        }
        if (!TextUtils.isEmpty(token)) {
            inputToken.setText(token);
        }
    }

    private void applyScannedPayload(@NonNull String raw) {
        RemotePairApi.ParsedPairInput parsed = RemotePairApi.parseUserInput("", raw);
        if (parsed.isEmpty()) {
            // Also accept plain deep link pasted into code-like field.
            parsed = RemotePairApi.parseUserInput(raw, raw);
        }
        if (parsed.isEmpty()) {
            Toast.makeText(this, R.string.remote_pair_code_or_token_required, Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        if (!parsed.code.isEmpty()) {
            inputCode.setText(parsed.code);
        }
        if (!parsed.token.isEmpty()) {
            inputToken.setText(parsed.token);
        } else if (raw.startsWith("autoreplybot://") || raw.contains("://pair")) {
            inputToken.setText(raw);
        }
        Toast.makeText(this, R.string.remote_qr_scanned, Toast.LENGTH_SHORT).show();
        onContinueClicked();
    }

    private void onContinueClicked() {
        if (!RemotePairApi.isBackendConfigured()) {
            Toast.makeText(this, R.string.remote_backend_url_missing, Toast.LENGTH_LONG).show();
            return;
        }

        CharSequence codeCs = inputCode.getText();
        CharSequence tokenCs = inputToken.getText();
        RemotePairApi.ParsedPairInput parsed = RemotePairApi.parseUserInput(
                codeCs != null ? codeCs.toString() : "",
                tokenCs != null ? tokenCs.toString() : "");

        if (parsed.isEmpty()) {
            Toast.makeText(this, R.string.remote_pair_code_or_token_required, Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        if (parsed.token.isEmpty() && !parsed.code.matches("\\d{6}")) {
            Toast.makeText(this, R.string.remote_pair_invalid_code, Toast.LENGTH_SHORT).show();
            return;
        }

        final String clientName = getString(R.string.remote_default_client_name);
        final String finalCode = parsed.code;
        final String finalToken = parsed.token;
        final RemotePairTrustOptions options = RemotePairTrustOptions.defaults();

        new AlertDialog.Builder(this)
                .setTitle(R.string.remote_pair_trust_title)
                .setMessage(getString(R.string.remote_pair_trust_message_simple)
                        + "\n\n" + clientName)
                .setNegativeButton(R.string.remote_pair_cancel, null)
                .setPositiveButton(R.string.remote_pair_confirm,
                        (d, which) -> completePairing(finalCode, finalToken, clientName, options))
                .show();
    }

    private static boolean isChecked(@Nullable MaterialCheckBox box, boolean defaultValue) {
        return box == null ? defaultValue : box.isChecked();
    }

    private void completePairing(@NonNull String code,
                                 @NonNull String token,
                                 @NonNull String clientName,
                                 @NonNull RemotePairTrustOptions options) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            finish();
            return;
        }
        setBusy(true);
        user.getIdToken(false)
                .addOnSuccessListener(tokenResult ->
                        executor.execute(() ->
                                runComplete(tokenResult, code, token, clientName, options)))
                .addOnFailureListener(error -> {
                    setBusy(false);
                    Toast.makeText(this,
                            getString(R.string.remote_error, error.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
    }

    private void runComplete(@NonNull GetTokenResult tokenResult,
                             @NonNull String code,
                             @NonNull String token,
                             @NonNull String clientName,
                             @NonNull RemotePairTrustOptions options) {
        try {
            String idToken = tokenResult.getToken();
            if (TextUtils.isEmpty(idToken)) {
                throw new IllegalStateException("Empty Firebase ID token");
            }
            pairApi.completePairing(
                    idToken,
                    code,
                    token,
                    clientName,
                    prefs.getOrCreateDeviceId(),
                    options);
            mainHandler.post(() -> {
                setBusy(false);
                Toast.makeText(this, R.string.remote_pair_success, Toast.LENGTH_SHORT).show();
                finish();
            });
        } catch (RemoteAccountBlockedException blocked) {
            mainHandler.post(() -> {
                setBusy(false);
                RemoteAccountGate.showBlocked(this, blocked.getServerMessage());
            });
        } catch (Exception error) {
            mainHandler.post(() -> {
                setBusy(false);
                Toast.makeText(this,
                        getString(R.string.remote_error,
                                error.getMessage() != null ? error.getMessage() : "unknown"),
                        Toast.LENGTH_LONG).show();
            });
        }
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        buttonContinue.setEnabled(!busy);
        if (buttonScanQr != null) buttonScanQr.setEnabled(!busy);
    }
}
