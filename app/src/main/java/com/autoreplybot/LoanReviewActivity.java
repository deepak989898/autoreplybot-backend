package com.autoreplybot;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class LoanReviewActivity extends AppCompatActivity {
    public static final String EXTRA_VIEW_ONLY = "view_only";

    private View refreshLoading;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        setContentView(R.layout.activity_loan_review);

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            startActivity(new Intent(this, WelcomeActivity.class));
            finish();
            return;
        }
        String uid = user.getUid();
        int status = OnboardingStore.applicationStatus(this, uid);
        boolean viewOnly = (getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_VIEW_ONLY, false))
                || status == OnboardingStore.APP_REVIEW
                || status == OnboardingStore.APP_APPROVED
                || status == OnboardingStore.APP_AWAITING_DISBURSE
                || status == OnboardingStore.APP_DISBURSED
                || status == OnboardingStore.APP_CLOSED
                || status == OnboardingStore.APP_REJECTED;

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        boolean fromHome = getIntent() != null
                && getIntent().getBooleanExtra(LoanHomeActivity.EXTRA_FROM_HOME, false);
        toolbar.setNavigationOnClickListener(v -> {
            if (viewOnly || fromHome) {
                finish();
                return;
            }
            Intent apply = new Intent(this, LoanApplyActivity.class);
            apply.putExtra(LoanHomeActivity.EXTRA_FROM_HOME, fromHome);
            startActivity(apply);
            finish();
        });

        LoanQuote quote = OnboardingStore.quote(this, uid);
        ((TextView) findViewById(R.id.review_amount))
                .setText(KycDocuments.rupees(quote.principal));
        ((TextView) findViewById(R.id.review_tenure))
                .setText(getString(R.string.loan_tenure_value, quote.months));
        ((TextView) findViewById(R.id.review_emi))
                .setText(KycDocuments.rupees(quote.monthlyEmi));
        ((TextView) findViewById(R.id.review_interest_label))
                .setText(getString(R.string.loan_interest_rate, quote.annualPercent));
        ((TextView) findViewById(R.id.review_interest))
                .setText(KycDocuments.rupees(quote.totalInterest));
        ((TextView) findViewById(R.id.review_total))
                .setText(KycDocuments.rupees(quote.totalPayable));

        String name = OnboardingStore.name(this, uid);
        if (name.isEmpty() && user.getDisplayName() != null) {
            name = user.getDisplayName();
        }
        ((TextView) findViewById(R.id.review_name)).setText(name);
        String phone = OnboardingStore.phone(this, uid);
        ((TextView) findViewById(R.id.review_mobile)).setText(
                phone.isEmpty() ? "" : getString(R.string.loan_review_mobile, phone));
        String email = user.getEmail() != null ? user.getEmail() : "";
        ((TextView) findViewById(R.id.review_email)).setText(email);

        CheckBox terms = findViewById(R.id.check_terms);
        View submit = findViewById(R.id.button_submit);
        View refresh = findViewById(R.id.button_refresh_status);
        refreshLoading = findViewById(R.id.review_refresh_loading);
        if (viewOnly) {
            ((TextView) findViewById(R.id.review_heading)).setText(R.string.loan_review_submitted_heading);
            ((TextView) findViewById(R.id.review_subtitle)).setText(R.string.loan_review_submitted_subtitle);
            terms.setChecked(true);
            terms.setEnabled(false);
            terms.setVisibility(View.GONE);
            submit.setVisibility(View.GONE);
            refresh.setVisibility(View.VISIBLE);
            refresh.setOnClickListener(v -> refreshStatus());
            return;
        }
        refresh.setVisibility(View.GONE);
        submit.setOnClickListener(v -> {
            if (!terms.isChecked()) {
                Toast.makeText(this, R.string.loan_review_terms_required, Toast.LENGTH_SHORT).show();
                return;
            }
            OnboardingStore.markSubmitted(this, uid);
            LoanCloudStore.publishSubmitted(this, uid, email);
            AuthNavigator.goHome(this);
        });
    }

    private void refreshStatus() {
        if (refreshLoading == null || refreshLoading.getVisibility() == View.VISIBLE) return;
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        String uid = user.getUid();
        refreshLoading.setVisibility(View.VISIBLE);
        LoanCloudStore.pull(this, uid).addOnCompleteListener(task -> {
            if (isFinishing()) return;
            refreshLoading.setVisibility(View.GONE);
            if (!task.isSuccessful()) {
                Toast.makeText(this, R.string.loan_status_refresh_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            int status = OnboardingStore.applicationStatus(this, uid);
            if (status == OnboardingStore.APP_APPROVED
                    || status == OnboardingStore.APP_AWAITING_DISBURSE
                    || status == OnboardingStore.APP_DISBURSED) {
                Toast.makeText(this, R.string.loan_review_refresh_approved, Toast.LENGTH_LONG).show();
                startActivity(new Intent(this, LoanDisbursementActivity.class));
                finish();
                return;
            }
            if (status == OnboardingStore.APP_REJECTED) {
                Toast.makeText(this, R.string.loan_review_refresh_rejected, Toast.LENGTH_LONG).show();
                AuthNavigator.goHome(this);
                return;
            }
            Toast.makeText(this, R.string.loan_review_refresh_done, Toast.LENGTH_SHORT).show();
        });
    }
}
