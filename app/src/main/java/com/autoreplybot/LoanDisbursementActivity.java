package com.autoreplybot;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class LoanDisbursementActivity extends AppCompatActivity {
    private String uid = "";
    private View bankBlock;
    private View agreementBlock;
    private TextView waiting;
    private TextView banner;
    private TextView steps;
    private CheckBox agreementCheck;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        setContentView(R.layout.activity_loan_disbursement);

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            startActivity(new Intent(this, WelcomeActivity.class));
            finish();
            return;
        }
        uid = user.getUid();
        findViewById(R.id.toolbar).setOnClickListener(v -> finish());
        ((MaterialToolbar) findViewById(R.id.toolbar)).setNavigationOnClickListener(v -> finish());

        banner = findViewById(R.id.next_banner);
        steps = findViewById(R.id.next_steps);
        bankBlock = findViewById(R.id.next_bank_block);
        agreementBlock = findViewById(R.id.next_agreement_block);
        waiting = findViewById(R.id.next_waiting);
        agreementCheck = findViewById(R.id.next_agreement_check);
        ((TextView) findViewById(R.id.next_agreement_text)).setText(readAgreement());

        findViewById(R.id.button_next_continue).setOnClickListener(v -> onContinue());
        bind();
        LoanCloudStore.pullQuiet(this, uid);
    }

    @Override
    protected void onResume() {
        super.onResume();
        LoanCloudStore.pull(this, uid).addOnSuccessListener(changed -> bind());
    }

    private void bind() {
        int status = OnboardingStore.applicationStatus(this, uid);
        LoanQuote quote = OnboardingStore.quote(this, uid);
        banner.setText(getString(R.string.loan_approved_banner, KycDocuments.rupees(quote.principal)));
        steps.setText(R.string.loan_approved_steps);
        View continueBtn = findViewById(R.id.button_next_continue);

        if (status == OnboardingStore.APP_DISBURSED) {
            bankBlock.setVisibility(View.GONE);
            agreementBlock.setVisibility(View.GONE);
            waiting.setVisibility(View.VISIBLE);
            String utr = OnboardingStore.disbursementUtr(this, uid);
            waiting.setText(getString(R.string.loan_disbursed_message,
                    KycDocuments.rupees(quote.principal), utr.isEmpty() ? "—" : utr));
            continueBtn.setVisibility(View.GONE);
            return;
        }
        if (status == OnboardingStore.APP_AWAITING_DISBURSE) {
            bankBlock.setVisibility(View.GONE);
            agreementBlock.setVisibility(View.GONE);
            waiting.setVisibility(View.VISIBLE);
            waiting.setText(R.string.loan_awaiting_disburse_message);
            continueBtn.setVisibility(View.VISIBLE);
            ((com.google.android.material.button.MaterialButton) continueBtn)
                    .setText(R.string.loan_review_refresh_status);
            return;
        }
        if (OnboardingStore.hasBank(this, uid)) {
            bankBlock.setVisibility(View.GONE);
            agreementBlock.setVisibility(View.VISIBLE);
            waiting.setVisibility(View.GONE);
            continueBtn.setVisibility(View.VISIBLE);
            ((com.google.android.material.button.MaterialButton) continueBtn)
                    .setText(R.string.loan_agreement_submit);
            return;
        }
        bankBlock.setVisibility(View.VISIBLE);
        agreementBlock.setVisibility(View.GONE);
        waiting.setVisibility(View.GONE);
        continueBtn.setVisibility(View.VISIBLE);
        ((com.google.android.material.button.MaterialButton) continueBtn)
                .setText(R.string.loan_bank_continue);
        ((TextInputEditText) findViewById(R.id.input_bank_holder))
                .setText(OnboardingStore.bankHolder(this, uid));
        ((TextInputEditText) findViewById(R.id.input_bank_name))
                .setText(OnboardingStore.bankName(this, uid));
        ((TextInputEditText) findViewById(R.id.input_bank_account))
                .setText(OnboardingStore.bankAccount(this, uid));
        ((TextInputEditText) findViewById(R.id.input_bank_ifsc))
                .setText(OnboardingStore.bankIfsc(this, uid));
    }

    private void onContinue() {
        int status = OnboardingStore.applicationStatus(this, uid);
        if (status == OnboardingStore.APP_AWAITING_DISBURSE) {
            LoanCloudStore.pull(this, uid).addOnSuccessListener(changed -> {
                bind();
                if (!changed) {
                    Toast.makeText(this, R.string.loan_awaiting_disburse_refresh, Toast.LENGTH_SHORT).show();
                }
            }).addOnFailureListener(e ->
                    Toast.makeText(this, R.string.loan_status_refresh_failed, Toast.LENGTH_SHORT).show());
            return;
        }
        if (!OnboardingStore.hasBank(this, uid)) {
            String holder = text(R.id.input_bank_holder);
            String bank = text(R.id.input_bank_name);
            String account = text(R.id.input_bank_account);
            String ifsc = text(R.id.input_bank_ifsc).toUpperCase(Locale.US);
            if (holder.length() < 3 || bank.length() < 3 || account.length() < 8 || ifsc.length() != 11) {
                Toast.makeText(this, R.string.loan_bank_invalid, Toast.LENGTH_LONG).show();
                return;
            }
            OnboardingStore.saveBank(this, uid, holder, bank, account, ifsc);
            LoanCloudStore.publishBank(this, uid);
            Toast.makeText(this, R.string.loan_bank_saved, Toast.LENGTH_SHORT).show();
            bind();
            return;
        }
        if (!agreementCheck.isChecked()) {
            Toast.makeText(this, R.string.loan_agreement_required, Toast.LENGTH_SHORT).show();
            return;
        }
        OnboardingStore.markAgreementAccepted(this, uid);
        LoanCloudStore.publishAgreement(this, uid);
        Toast.makeText(this, R.string.loan_agreement_saved, Toast.LENGTH_LONG).show();
        bind();
    }

    private String text(int id) {
        TextInputEditText edit = findViewById(id);
        return edit.getText() == null ? "" : edit.getText().toString().trim();
    }

    @NonNull
    private String readAgreement() {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = getResources().openRawResource(R.raw.loan_facility_agreement);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
        } catch (Exception ignored) {
            return getString(R.string.loan_agreement_fallback);
        }
        return sb.toString();
    }
}
