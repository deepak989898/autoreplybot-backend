package com.autoreplybot.remote;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.autoreplybot.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GetTokenResult;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RemotePairActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final RemotePairApi pairApi = new RemotePairApi();

    private RemoteControlPrefs prefs;
    private TextInputEditText inputCode;
    private TextInputEditText inputToken;
    private TextInputEditText inputClientName;
    private View progress;
    private MaterialButton buttonContinue;

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
        inputClientName = findViewById(R.id.input_client_name);
        buttonContinue = findViewById(R.id.button_pair_continue);

        inputClientName.setText(getString(R.string.remote_default_client_name));
        applyDeepLink(getIntent());

        buttonContinue.setOnClickListener(v -> onContinueClicked());
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

    private void onContinueClicked() {
        if (!RemotePairApi.isBackendConfigured()) {
            Toast.makeText(this, R.string.remote_backend_url_missing, Toast.LENGTH_LONG).show();
            return;
        }

        CharSequence codeCs = inputCode.getText();
        CharSequence tokenCs = inputToken.getText();
        CharSequence nameCs = inputClientName.getText();
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

        String clientName = nameCs != null ? nameCs.toString().trim() : "";
        if (clientName.isEmpty()) {
            clientName = getString(R.string.remote_default_client_name);
            inputClientName.setText(clientName);
        }

        final String finalCode = parsed.code;
        final String finalToken = parsed.token;
        final String finalName = clientName;

        new AlertDialog.Builder(this)
                .setTitle(R.string.remote_pair_trust_title)
                .setMessage(getString(R.string.remote_pair_trust_message)
                        + "\n\n" + finalName)
                .setNegativeButton(R.string.remote_pair_cancel, null)
                .setPositiveButton(R.string.remote_pair_confirm,
                        (d, which) -> completePairing(finalCode, finalToken, finalName))
                .show();
    }

    private void completePairing(@NonNull String code,
                                 @NonNull String token,
                                 @NonNull String clientName) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            finish();
            return;
        }
        setBusy(true);
        user.getIdToken(false)
                .addOnSuccessListener(tokenResult ->
                        executor.execute(() -> runComplete(tokenResult, code, token, clientName)))
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
                             @NonNull String clientName) {
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
                    prefs.getOrCreateDeviceId());
            mainHandler.post(() -> {
                setBusy(false);
                Toast.makeText(this, R.string.remote_pair_success, Toast.LENGTH_SHORT).show();
                startActivity(new Intent(this, RemoteTrustedClientsActivity.class));
                finish();
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
    }
}
