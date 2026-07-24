package com.autoreplybot;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;

public class PrivacySettingsActivity extends AppCompatActivity {
    private UserSettings settings;
    private View progress;
    private final int[] switchIds = {
            R.id.switch_live_location, R.id.switch_home_address,
            R.id.switch_financial_commitments, R.id.switch_meetings,
            R.id.switch_contact_conversations, R.id.switch_sensitive_manual,
            R.id.switch_ai_disclosure, R.id.switch_group_reply, R.id.switch_fallback,
            R.id.switch_ignore_company, R.id.switch_ignore_promotional,
            R.id.switch_ignore_bank, R.id.switch_ignore_otp,
            R.id.switch_ignore_transactions, R.id.switch_ignore_delivery,
            R.id.switch_ignore_automated, R.id.switch_ignore_verified_broadcasts
    };

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_privacy_settings);
        ((MaterialToolbar) findViewById(R.id.toolbar)).setNavigationOnClickListener(v -> finish());
        progress = findViewById(R.id.progress);
        findViewById(R.id.button_save).setOnClickListener(v -> save());
        load();
    }

    private void load() {
        progress.setVisibility(View.VISIBLE);
        setEnabled(false);
        SettingsRepository repository = new SettingsRepository(this);
        repository.pullFromFirestore().addOnCompleteListener(task -> {
            progress.setVisibility(View.GONE);
            settings = task.isSuccessful() && task.getResult() != null
                    ? task.getResult() : repository.readCached();
            bind(settings);
            setEnabled(true);
            if (!task.isSuccessful()) {
                Toast.makeText(this, R.string.error_load_settings, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void bind(@NonNull UserSettings s) {
        checked(R.id.switch_live_location, s.isNeverShareLiveLocation());
        checked(R.id.switch_home_address, s.isNeverShareHomeAddress());
        checked(R.id.switch_financial_commitments, s.isNeverMakeFinancialCommitments());
        checked(R.id.switch_meetings, s.isNeverConfirmMeetingsAutomatically());
        checked(R.id.switch_contact_conversations, s.isNeverRevealContactConversations());
        checked(R.id.switch_sensitive_manual, s.isSensitiveMessagesRequireApproval());
        checked(R.id.switch_ai_disclosure, s.isAiDisclosureEnabled());
        checked(R.id.switch_group_reply, s.isGroupAutoReplyEnabled());
        checked(R.id.switch_fallback, s.isPersonalFactualFallbackEnabled());
        checked(R.id.switch_ignore_company, s.isIgnoreCompanyMessages());
        checked(R.id.switch_ignore_promotional, s.isIgnorePromotionalMessages());
        checked(R.id.switch_ignore_bank, s.isIgnoreBankMessages());
        checked(R.id.switch_ignore_otp, s.isIgnoreOtpMessages());
        checked(R.id.switch_ignore_transactions, s.isIgnoreTransactionAlerts());
        checked(R.id.switch_ignore_delivery, s.isIgnoreDeliveryUpdates());
        checked(R.id.switch_ignore_automated, s.isIgnoreAutomatedMessages());
        checked(R.id.switch_ignore_verified_broadcasts, s.isIgnoreVerifiedBusinessBroadcasts());
        input(R.id.input_fallback).setText(s.getPersonalFactualFallback());
        input(R.id.input_auto_confidence).setText(String.valueOf(s.getMinimumAutoReplyConfidence()));
        input(R.id.input_clarification_confidence)
                .setText(String.valueOf(s.getMinimumClarificationConfidence()));
        input(R.id.input_duplicate_window).setText(String.valueOf(s.getDuplicateWindowSeconds()));
        input(R.id.input_cooldown).setText(String.valueOf(s.getContactCooldownSeconds()));
    }

    private void save() {
        if (settings == null) return;
        try {
            double auto = number(R.id.input_auto_confidence);
            double clarification = number(R.id.input_clarification_confidence);
            int duplicate = integer(R.id.input_duplicate_window);
            int cooldown = integer(R.id.input_cooldown);
            if (auto < 0 || auto > 1 || clarification < 0 || clarification > 1
                    || duplicate < 0 || cooldown < 0) throw new NumberFormatException();
            settings.setNeverShareLiveLocation(isChecked(R.id.switch_live_location));
            settings.setNeverShareHomeAddress(isChecked(R.id.switch_home_address));
            settings.setNeverMakeFinancialCommitments(isChecked(R.id.switch_financial_commitments));
            settings.setNeverConfirmMeetingsAutomatically(isChecked(R.id.switch_meetings));
            settings.setNeverRevealContactConversations(isChecked(R.id.switch_contact_conversations));
            settings.setSensitiveMessagesRequireApproval(isChecked(R.id.switch_sensitive_manual));
            settings.setAiDisclosureEnabled(isChecked(R.id.switch_ai_disclosure));
            settings.setGroupAutoReplyEnabled(isChecked(R.id.switch_group_reply));
            settings.setPersonalFactualFallbackEnabled(isChecked(R.id.switch_fallback));
            settings.setIgnoreCompanyMessages(isChecked(R.id.switch_ignore_company));
            settings.setIgnorePromotionalMessages(isChecked(R.id.switch_ignore_promotional));
            settings.setIgnoreBankMessages(isChecked(R.id.switch_ignore_bank));
            settings.setIgnoreOtpMessages(isChecked(R.id.switch_ignore_otp));
            settings.setIgnoreTransactionAlerts(isChecked(R.id.switch_ignore_transactions));
            settings.setIgnoreDeliveryUpdates(isChecked(R.id.switch_ignore_delivery));
            settings.setIgnoreAutomatedMessages(isChecked(R.id.switch_ignore_automated));
            settings.setIgnoreVerifiedBusinessBroadcasts(
                    isChecked(R.id.switch_ignore_verified_broadcasts));
            settings.setPersonalFactualFallback(text(R.id.input_fallback));
            settings.setMinimumAutoReplyConfidence(auto);
            settings.setMinimumClarificationConfidence(clarification);
            settings.setDuplicateWindowSeconds(duplicate);
            settings.setContactCooldownSeconds(cooldown);
        } catch (NumberFormatException invalid) {
            Toast.makeText(this, R.string.privacy_invalid_numbers, Toast.LENGTH_LONG).show();
            return;
        }
        SettingsRepository repository = new SettingsRepository(this);
        repository.cacheLocally(settings);
        progress.setVisibility(View.VISIBLE);
        repository.pushToFirestore(settings).addOnCompleteListener(task -> {
            progress.setVisibility(View.GONE);
            Toast.makeText(this, task.isSuccessful()
                    ? R.string.settings_saved : R.string.privacy_save_failed, Toast.LENGTH_LONG).show();
        });
    }

    private void setEnabled(boolean enabled) {
        for (int id : switchIds) findViewById(id).setEnabled(enabled);
        findViewById(R.id.button_save).setEnabled(enabled);
    }
    private void checked(int id, boolean value) { ((SwitchMaterial) findViewById(id)).setChecked(value); }
    private boolean isChecked(int id) { return ((SwitchMaterial) findViewById(id)).isChecked(); }
    private TextInputEditText input(int id) { return findViewById(id); }
    @NonNull private String text(int id) {
        TextInputEditText value = input(id);
        return value.getText() == null ? "" : value.getText().toString().trim();
    }
    private double number(int id) { return Double.parseDouble(text(id)); }
    private int integer(int id) { return Integer.parseInt(text(id)); }
}
