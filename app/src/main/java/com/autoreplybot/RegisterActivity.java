package com.autoreplybot;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.autoreplybot.remote.RemotePairApi;
import com.google.android.gms.tasks.Task;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseAuthWeakPasswordException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RegisterActivity extends AppCompatActivity implements AuthNavigator.Host {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final RemotePairApi pairApi = new RemotePairApi();

    private TextInputEditText inputUsername;
    private TextInputEditText inputMobile;
    private TextInputEditText inputEmail;
    private TextInputEditText inputPassword;
    private TextInputEditText inputReferral;
    private View registerScroll;
    private View registerLoading;
    private MaterialButton buttonCreate;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        setContentView(R.layout.activity_register);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        }

        inputUsername = findViewById(R.id.input_username);
        inputMobile = findViewById(R.id.input_mobile);
        inputEmail = findViewById(R.id.input_email);
        inputPassword = findViewById(R.id.input_password);
        inputReferral = findViewById(R.id.input_referral);
        registerScroll = findViewById(R.id.register_scroll);
        registerLoading = findViewById(R.id.register_loading);
        buttonCreate = findViewById(R.id.button_create);
        buttonCreate.setOnClickListener(v -> submit());
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void submit() {
        String username = text(inputUsername);
        String mobile = text(inputMobile);
        String email = text(inputEmail);
        String password = text(inputPassword);
        String referral = text(inputReferral);

        if (username.length() < 2) {
            showMessage(getString(R.string.error_register_username));
            return;
        }
        String digits = mobile.replaceAll("\\D", "");
        if (digits.length() < 8 || digits.length() > 15) {
            showMessage(getString(R.string.error_register_mobile));
            return;
        }
        if (email.isEmpty() || !email.contains("@")) {
            showMessage(getString(R.string.error_auth_invalid_email));
            return;
        }
        if (password.length() < 6) {
            showMessage(getString(R.string.error_auth_weak_password));
            return;
        }

        setBusy(true);
        FirebaseAuth.getInstance()
                .createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (!task.isSuccessful() || task.getResult() == null
                            || task.getResult().getUser() == null) {
                        setBusy(false);
                        showMessage(friendlyAuthError(task));
                        return;
                    }
                    FirebaseUser user = task.getResult().getUser();
                    OnboardingStore.markNewAccount(this, user.getUid(), username, mobile);
                    UserProfileChangeRequest profile = new UserProfileChangeRequest.Builder()
                            .setDisplayName(username)
                            .build();
                    user.updateProfile(profile)
                            .addOnCompleteListener(ignored -> saveProfileThenEnter(user, username, mobile, referral));
                });
    }

    private void saveProfileThenEnter(@NonNull FirebaseUser user,
                                      @NonNull String username,
                                      @NonNull String mobile,
                                      @NonNull String referral) {
        if (!RemotePairApi.isBackendConfigured()) {
            AuthNavigator.verifyThenEnter(this, user, this, executor, pairApi);
            return;
        }
        user.getIdToken(true)
                .addOnSuccessListener(tr -> executor.execute(() -> {
                    try {
                        String token = tr.getToken();
                        if (token != null && !token.isEmpty()) {
                            pairApi.saveAccountProfile(token, username, mobile, referral);
                        }
                    } catch (Exception ignored) {
                        // Profile save is best-effort; account already exists.
                    }
                    runOnUiThread(() ->
                            AuthNavigator.verifyThenEnter(this, user, this, executor, pairApi));
                }))
                .addOnFailureListener(e ->
                        AuthNavigator.verifyThenEnter(this, user, this, executor, pairApi));
    }

    @Override
    public void showMessage(@NonNull CharSequence message) {
        View root = findViewById(R.id.register_root);
        if (root == null) root = findViewById(android.R.id.content);
        Snackbar.make(root, message, Snackbar.LENGTH_LONG).show();
    }

    @NonNull
    private String friendlyAuthError(@NonNull Task<?> task) {
        Exception e = task.getException();
        if (e == null) return getString(R.string.error_auth);
        if (e instanceof FirebaseNetworkException) {
            return getString(R.string.error_auth_network);
        }
        if (e instanceof FirebaseAuthWeakPasswordException) {
            return getString(R.string.error_auth_weak_password);
        }
        if (e instanceof FirebaseAuthUserCollisionException) {
            return getString(R.string.error_auth_email_in_use);
        }
        if (e instanceof FirebaseAuthInvalidCredentialsException) {
            String code = ((FirebaseAuthInvalidCredentialsException) e).getErrorCode();
            if ("ERROR_INVALID_EMAIL".equals(code)) {
                return getString(R.string.error_auth_invalid_email);
            }
        }
        if (e instanceof FirebaseAuthException) {
            String code = ((FirebaseAuthException) e).getErrorCode();
            @StringRes int res = mapCode(code);
            return getString(res);
        }
        return getString(R.string.error_auth);
    }

    @StringRes
    private static int mapCode(@Nullable String code) {
        if (code == null) return R.string.error_auth;
        switch (code) {
            case "ERROR_EMAIL_ALREADY_IN_USE":
                return R.string.error_auth_email_in_use;
            case "ERROR_WEAK_PASSWORD":
                return R.string.error_auth_weak_password;
            case "ERROR_INVALID_EMAIL":
                return R.string.error_auth_invalid_email;
            case "ERROR_NETWORK_REQUEST_FAILED":
                return R.string.error_auth_network;
            case "ERROR_TOO_MANY_REQUESTS":
                return R.string.error_auth_too_many;
            default:
                return R.string.error_auth;
        }
    }

    @Override
    public void setBusy(boolean busy) {
        if (registerScroll != null) {
            registerScroll.setVisibility(busy ? View.GONE : View.VISIBLE);
        }
        if (registerLoading != null) {
            registerLoading.setVisibility(busy ? View.VISIBLE : View.GONE);
        }
        if (inputUsername != null) inputUsername.setEnabled(!busy);
        if (inputMobile != null) inputMobile.setEnabled(!busy);
        if (inputEmail != null) inputEmail.setEnabled(!busy);
        if (inputPassword != null) inputPassword.setEnabled(!busy);
        if (inputReferral != null) inputReferral.setEnabled(!busy);
        if (buttonCreate != null) buttonCreate.setEnabled(!busy);
    }

    @NonNull
    private static String text(@Nullable TextInputEditText e) {
        if (e == null || e.getText() == null) return "";
        return e.getText().toString().trim();
    }
}
