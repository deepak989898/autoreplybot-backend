package com.autoreplybot.remote;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;

/**
 * Legacy entry point — forwards to the home screen. Runtime permissions are requested
 * inline on {@link RemoteControlHomeActivity} without a separate setup screen.
 */
@Deprecated
public class RemotePermissionSetupActivity extends AppCompatActivity {
    public static final String EXTRA_CONTINUE_ENABLE = "continueEnable";
    public static final String EXTRA_OPEN_PERMISSIONS = "open_permissions";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish();
            return;
        }
        Intent home = new Intent(this, RemoteControlHomeActivity.class);
        home.putExtra(RemoteControlHomeActivity.EXTRA_FOCUS_PERMISSIONS,
                getIntent() != null && getIntent().getBooleanExtra(EXTRA_OPEN_PERMISSIONS, false));
        home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(home);
        finish();
    }
}
