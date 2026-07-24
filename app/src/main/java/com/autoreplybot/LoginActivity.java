package com.autoreplybot;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;

public class LoginActivity extends AppCompatActivity {

    private TextInputEditText inputEmail;
    private TextInputEditText inputPassword;
    private ProgressBar progress;
    private MaterialButton buttonSignIn;
    private MaterialButton buttonRegister;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            goMain();
            return;
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        setContentView(R.layout.activity_login);
        inputEmail = findViewById(R.id.input_email);
        inputPassword = findViewById(R.id.input_password);
        progress = findViewById(R.id.progress);
        buttonSignIn = findViewById(R.id.button_sign_in);
        buttonRegister = findViewById(R.id.button_register);

        buttonSignIn.setOnClickListener(v -> submit(false));
        buttonRegister.setOnClickListener(v -> submit(true));
    }

    private void submit(boolean register) {
        String email = text(inputEmail);
        String password = text(inputPassword);
        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, R.string.error_fill_fields, Toast.LENGTH_SHORT).show();
            return;
        }
        setBusy(true);
        FirebaseAuth auth = FirebaseAuth.getInstance();
        if (register) {
            auth.createUserWithEmailAndPassword(email, password)
                    .addOnCompleteListener(this, task -> {
                        setBusy(false);
                        if (task.isSuccessful()) {
                            goMain();
                        } else {
                            Toast.makeText(this,
                                    getString(R.string.error_auth, message(task)),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
        } else {
            auth.signInWithEmailAndPassword(email, password)
                    .addOnCompleteListener(this, task -> {
                        setBusy(false);
                        if (task.isSuccessful()) {
                            goMain();
                        } else {
                            Toast.makeText(this,
                                    getString(R.string.error_auth, message(task)),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
        }
    }

    @NonNull
    private static String message(@NonNull com.google.android.gms.tasks.Task<?> task) {
        Exception e = task.getException();
        return e != null && e.getLocalizedMessage() != null ? e.getLocalizedMessage() : "unknown";
    }

    private void goMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        buttonSignIn.setEnabled(!busy);
        buttonRegister.setEnabled(!busy);
    }

    @NonNull
    private static String text(@Nullable TextInputEditText e) {
        if (e == null || e.getText() == null) return "";
        return e.getText().toString().trim();
    }
}
