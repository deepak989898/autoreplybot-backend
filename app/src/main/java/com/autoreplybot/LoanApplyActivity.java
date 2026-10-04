package com.autoreplybot;

import android.content.Intent;
import android.os.Bundle;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class LoanApplyActivity extends AppCompatActivity {
    private TextView textAmount;
    private TextView textEmi;
    private TextView textInterest;
    private TextView textTotal;
    private SeekBar seekAmount;
    private MaterialButtonToggleGroup tenureGroup;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        setContentView(R.layout.activity_loan_apply);

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            startActivity(new Intent(this, WelcomeActivity.class));
            finish();
            return;
        }

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        boolean fromHome = getIntent() != null
                && getIntent().getBooleanExtra(LoanHomeActivity.EXTRA_FROM_HOME, false);
        toolbar.setNavigationOnClickListener(v -> {
            if (fromHome) {
                finish();
                return;
            }
            startActivity(new Intent(this, KycActivity.class));
            finish();
        });

        if (fromHome && !OnboardingStore.canApply(this, user.getUid())) {
            finish();
            return;
        }

        textAmount = findViewById(R.id.text_amount);
        textEmi = findViewById(R.id.text_emi);
        textInterest = findViewById(R.id.text_interest);
        textTotal = findViewById(R.id.text_total);
        seekAmount = findViewById(R.id.seek_amount);
        tenureGroup = findViewById(R.id.tenure_group);

        seekAmount.setMax(LoanQuote.seekMax());
        int saved = OnboardingStore.amount(this, user.getUid());
        seekAmount.setProgress(LoanQuote.progressFromAmount(saved));
        int savedTenure = OnboardingStore.tenure(this, user.getUid());
        if (savedTenure == 6) {
            tenureGroup.check(R.id.tenure_6);
        } else if (savedTenure == 12) {
            tenureGroup.check(R.id.tenure_12);
        } else {
            tenureGroup.check(R.id.tenure_3);
        }

        seekAmount.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                refreshSummary();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        tenureGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) refreshSummary();
        });

        findViewById(R.id.button_proceed).setOnClickListener(v -> proceed(user.getUid()));
        refreshSummary();
    }

    private int amount() {
        return LoanQuote.amountFromProgress(seekAmount.getProgress());
    }

    private int tenureMonths() {
        int id = tenureGroup.getCheckedButtonId();
        if (id == R.id.tenure_6) return 6;
        if (id == R.id.tenure_12) return 12;
        return 3;
    }

    private void refreshSummary() {
        LoanQuote quote = LoanQuote.of(amount(), tenureMonths());
        textAmount.setText(KycDocuments.rupees(quote.principal));
        textEmi.setText(KycDocuments.rupees(quote.monthlyEmi));
        textInterest.setText(KycDocuments.rupees(quote.totalInterest));
        textTotal.setText(KycDocuments.rupees(quote.totalPayable));
        TextView rate = findViewById(R.id.text_interest_rate);
        if (rate != null) {
            rate.setText(getString(R.string.loan_interest_rate, quote.annualPercent));
        }
    }

    private void proceed(String uid) {
        LoanQuote quote = LoanQuote.of(amount(), tenureMonths());
        OnboardingStore.saveLoan(this, uid, quote.principal, quote.months, quote.monthlyEmi);
        Intent review = new Intent(this, LoanReviewActivity.class);
        boolean fromHome = getIntent() != null
                && getIntent().getBooleanExtra(LoanHomeActivity.EXTRA_FROM_HOME, false);
        review.putExtra(LoanHomeActivity.EXTRA_FROM_HOME, fromHome);
        startActivity(review);
        finish();
    }
}
