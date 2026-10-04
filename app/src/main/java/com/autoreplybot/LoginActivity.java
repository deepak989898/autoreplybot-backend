package com.autoreplybot;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.autoreplybot.remote.RemotePairApi;
import com.google.android.gms.tasks.Task;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseAuthWeakPasswordException;
import com.google.firebase.auth.FirebaseUser;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LoginActivity extends AppCompatActivity implements AuthNavigator.Host {
    public static final String EXTRA_BLOCKED_MESSAGE = "blockedMessage";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final RemotePairApi pairApi = new RemotePairApi();

    private TextInputEditText inputEmail;
    private TextInputEditText inputPassword;
    private View loginScroll;
    private View loginLoading;
    private MaterialButton buttonSignIn;
    private MaterialButton buttonRegister;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        setContentView(R.layout.activity_login);
        inputEmail = findViewById(R.id.input_email);
        inputPassword = findViewById(R.id.input_password);
        loginScroll = findViewById(R.id.login_scroll);
        loginLoading = findViewById(R.id.login_loading);
        buttonSignIn = findViewById(R.id.button_sign_in);
        buttonRegister = findViewById(R.id.button_register);

        buttonSignIn.setOnClickListener(v -> submit());
        buttonRegister.setOnClickListener(v ->
                startActivity(new Intent(this, RegisterActivity.class)));

        String blockedMsg = getIntent() != null
                ? getIntent().getStringExtra(EXTRA_BLOCKED_MESSAGE)
                : null;
        if (blockedMsg != null && !blockedMsg.isEmpty()) {
            showMessage(blockedMsg);
        }

        FirebaseUser existing = FirebaseAuth.getInstance().getCurrentUser();
        if (existing != null) {
            setBusy(true);
            AuthNavigator.verifyThenEnter(this, existing, this, executor, pairApi);
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void submit() {
        String email = text(inputEmail);
        String password = text(inputPassword);
        if (email.isEmpty() || password.isEmpty()) {
            showMessage(getString(R.string.error_fill_fields));
            return;
        }
        setBusy(true);
        FirebaseAuth.getInstance()
                .signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful() && task.getResult() != null
                            && task.getResult().getUser() != null) {
                        AuthNavigator.verifyThenEnter(
                                this, task.getResult().getUser(), this, executor, pairApi);
                    } else {
                        setBusy(false);
                        showMessage(friendlyAuthError(task, false));
                    }
                });
    }

    @Override
    public void showMessage(@NonNull CharSequence message) {
        View root = findViewById(R.id.login_root);
        if (root == null) root = findViewById(android.R.id.content);
        Snackbar.make(root, message, Snackbar.LENGTH_LONG).show();
    }

    @NonNull
    private String friendlyAuthError(@NonNull Task<?> task, boolean registering) {
        Exception e = task.getException();
        if (e == null) {
            return getString(R.string.error_auth);
        }
        if (e instanceof FirebaseNetworkException) {
            return getString(R.string.error_auth_network);
        }
        if (e instanceof FirebaseAuthWeakPasswordException) {
            return getString(R.string.error_auth_weak_password);
        }
        if (e instanceof FirebaseAuthUserCollisionException) {
            return getString(R.string.error_auth_email_in_use);
        }
        if (e instanceof FirebaseAuthInvalidUserException) {
            String code = ((FirebaseAuthInvalidUserException) e).getErrorCode();
            if ("ERROR_USER_DISABLED".equals(code)) {
                return getString(R.string.error_auth_account_blocked);
            }
            return getString(R.string.error_auth_account_missing);
        }
        if (e instanceof FirebaseAuthInvalidCredentialsException) {
            String code = ((FirebaseAuthInvalidCredentialsException) e).getErrorCode();
            if ("ERROR_INVALID_EMAIL".equals(code)) {
                return getString(R.string.error_auth_invalid_email);
            }
            if ("ERROR_WRONG_PASSWORD".equals(code)) {
                return getString(R.string.error_auth_wrong_password);
            }
            if (!registering) {
                return getString(R.string.error_auth_account_missing);
            }
            return getString(R.string.error_auth);
        }
        if (e instanceof FirebaseAuthException) {
            String code = ((FirebaseAuthException) e).getErrorCode();
            @StringRes int res = mapAuthErrorCode(code, registering);
            return getString(res);
        }
        return getString(R.string.error_auth);
    }

    @StringRes
    private static int mapAuthErrorCode(@Nullable String code, boolean registering) {
        if (code == null) return R.string.error_auth;
        switch (code) {
            case "ERROR_USER_DISABLED":
                return R.string.error_auth_account_blocked;
            case "ERROR_USER_NOT_FOUND":
            case "ERROR_INVALID_CREDENTIAL":
            case "ERROR_INVALID_LOGIN_CREDENTIALS":
                return registering ? R.string.error_auth : R.string.error_auth_account_missing;
            case "ERROR_WRONG_PASSWORD":
                return R.string.error_auth_wrong_password;
            case "ERROR_INVALID_EMAIL":
                return R.string.error_auth_invalid_email;
            case "ERROR_EMAIL_ALREADY_IN_USE":
                return R.string.error_auth_email_in_use;
            case "ERROR_WEAK_PASSWORD":
                return R.string.error_auth_weak_password;
            case "ERROR_TOO_MANY_REQUESTS":
                return R.string.error_auth_too_many;
            case "ERROR_NETWORK_REQUEST_FAILED":
                return R.string.error_auth_network;
            default:
                return R.string.error_auth;
        }
    }

    @Override
    public void setBusy(boolean busy) {
        if (loginScroll != null) {
            loginScroll.setVisibility(busy ? View.GONE : View.VISIBLE);
        }
        if (loginLoading != null) {
            loginLoading.setVisibility(busy ? View.VISIBLE : View.GONE);
        }
        if (inputEmail != null) inputEmail.setEnabled(!busy);
        if (inputPassword != null) inputPassword.setEnabled(!busy);
        buttonSignIn.setEnabled(!busy);
        buttonRegister.setEnabled(!busy);
    }

    @NonNull
    private static String text(@Nullable TextInputEditText e) {
        if (e == null || e.getText() == null) return "";
        return e.getText().toString().trim();
    }
}
