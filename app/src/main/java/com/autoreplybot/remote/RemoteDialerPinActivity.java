package com.autoreplybot.remote;

import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.autoreplybot.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

/**
 * Passcode screen used when Vivo blocks dialer secret-codes.
 * Opened from the persistent unlock notification (or backup *#*#456789#*#*).
 */
public final class RemoteDialerPinActivity extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        RemoteControlPrefs prefs = new RemoteControlPrefs(this);
        if (!prefs.isLauncherHidden()) {
            startActivity(RemoteHiddenUnlockNotifications.launchIntent(this));
            finish();
            return;
        }
        setContentView(R.layout.activity_remote_dialer_pin);
        TextInputEditText input = findViewById(R.id.input_dialer_pin);
        MaterialButton unlock = findViewById(R.id.button_dialer_pin_unlock);
        unlock.setOnClickListener(v -> {
            String typed = input != null && input.getText() != null
                    ? input.getText().toString()
                    : "";
            String expected = prefs.getDialerPasscode();
            String digits = RemoteDialerUnlock.digitsOnly(typed);
            if (!expected.isEmpty() && expected.equals(digits)) {
                try {
                    startActivity(RemoteHiddenUnlockNotifications.launchIntent(this));
                } catch (Throwable t) {
                    Toast.makeText(this, R.string.remote_hide_unlock_open_failed, Toast.LENGTH_LONG)
                            .show();
                    return;
                }
                finish();
            } else {
                Toast.makeText(this, R.string.remote_hide_app_pin_wrong, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
