package com.autoreplybot;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class LoanDashboardFragment extends Fragment {
    private String uid = "";
    private TextView creditLabel;
    private TextView creditValue;
    private MaterialButton applyButton;
    private MaterialButton applyNow;
    private View moreFunds;
    private TextView creditMeta;
    private View creditCard;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_loan_dashboard, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        uid = user != null ? user.getUid() : "";
        String fallback = user != null ? user.getDisplayName() : "";
        TextView hello = view.findViewById(R.id.dash_hello);
        hello.setText(getString(R.string.loan_dash_hello,
                OnboardingStore.firstName(requireContext(), uid, fallback)));
        creditLabel = view.findViewById(R.id.dash_credit_label);
        creditValue = view.findViewById(R.id.dash_credit);
        applyButton = view.findViewById(R.id.dash_apply);
        moreFunds = view.findViewById(R.id.dash_more_funds);
        applyNow = view.findViewById(R.id.dash_apply_now);
        creditMeta = view.findViewById(R.id.dash_credit_meta);
        creditCard = view.findViewById(R.id.dash_credit_card);

        View.OnClickListener apply = v -> openApply();
        applyButton.setOnClickListener(apply);
        applyNow.setOnClickListener(apply);
        creditCard.setOnClickListener(v -> openReviewIfSubmitted());
        view.findViewById(R.id.dash_action_history).setOnClickListener(v ->
                ((LoanHomeActivity) requireActivity()).selectTab(R.id.nav_loan_history));
        view.findViewById(R.id.dash_action_repay).setOnClickListener(v ->
                ((LoanHomeActivity) requireActivity()).selectTab(R.id.nav_loan_repayment));
        view.findViewById(R.id.dash_action_profile).setOnClickListener(v ->
                ((LoanHomeActivity) requireActivity()).selectTab(R.id.nav_loan_profile));
        view.findViewById(R.id.dash_action_help).setOnClickListener(v -> showHelp());
        view.findViewById(R.id.dash_bell).setOnClickListener(v ->
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.loan_dash_notifications)
                        .setMessage(R.string.loan_dash_no_notifications)
                        .setPositiveButton(android.R.string.ok, null)
                        .show());
        bindCreditCard();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (creditLabel != null) {
            bindCreditCard();
        }
        if (uid != null && !uid.isEmpty()) {
            LoanCloudStore.pull(requireContext(), uid).addOnSuccessListener(changed -> {
                if (!isAdded()) return;
                bindCreditCard();
            });
        }
        if (getActivity() instanceof LoanHomeActivity) {
            ((LoanHomeActivity) getActivity()).onDashboardShown();
        }
    }

    private void bindCreditCard() {
        int status = OnboardingStore.applicationStatus(requireContext(), uid);
        int credit = OnboardingStore.availableCredit(requireContext(), uid);
        LoanQuote quote = OnboardingStore.quote(requireContext(), uid);
        creditCard.setBackgroundResource(R.drawable.bg_loan_credit_card);
        moreFunds.setVisibility(View.GONE);
        applyButton.setVisibility(View.VISIBLE);
        switch (status) {
            case OnboardingStore.APP_REVIEW:
                creditLabel.setText(R.string.loan_dash_in_review);
                creditValue.setText(KycDocuments.rupees(quote.principal));
                creditMeta.setVisibility(View.VISIBLE);
                creditMeta.setText(getString(R.string.loan_dash_review_meta,
                        KycDocuments.rupees(quote.monthlyEmi), quote.months));
                applyButton.setText(R.string.loan_dash_view_application);
                applyButton.setOnClickListener(v -> openSubmittedReview());
                break;
            case OnboardingStore.APP_REJECTED:
                creditCard.setBackgroundResource(R.drawable.bg_loan_credit_card_rejected);
                creditLabel.setText(R.string.loan_dash_loan_status);
                creditValue.setText(R.string.loan_dash_rejected);
                creditMeta.setVisibility(View.GONE);
                applyButton.setText(R.string.loan_dash_apply_again);
                applyButton.setOnClickListener(v -> openApply());
                moreFunds.setVisibility(View.VISIBLE);
                applyNow.setText(R.string.loan_dash_apply_again);
                break;
            case OnboardingStore.APP_APPROVED:
                creditCard.setBackgroundResource(R.drawable.bg_loan_credit_card_approved);
                creditLabel.setText(R.string.loan_dash_approved);
                creditValue.setText(KycDocuments.rupees(quote.principal));
                creditMeta.setVisibility(View.VISIBLE);
                creditMeta.setText(getString(R.string.loan_dash_approved_meta,
                        KycDocuments.rupees(quote.principal),
                        KycDocuments.rupees(quote.monthlyEmi), quote.months));
                applyButton.setText(R.string.loan_dash_continue_setup);
                applyButton.setOnClickListener(v -> openDisbursement());
                break;
            case OnboardingStore.APP_AWAITING_DISBURSE:
                creditCard.setBackgroundResource(R.drawable.bg_loan_credit_card_awaiting);
                creditLabel.setText(R.string.loan_dash_awaiting);
                creditValue.setText(KycDocuments.rupees(quote.principal));
                creditMeta.setVisibility(View.VISIBLE);
                creditMeta.setText(getString(R.string.loan_dash_awaiting_meta,
                        KycDocuments.rupees(quote.principal)));
                applyButton.setText(R.string.loan_dash_check_disbursement);
                applyButton.setOnClickListener(v -> openDisbursement());
                break;
            case OnboardingStore.APP_DISBURSED:
                creditCard.setBackgroundResource(R.drawable.bg_loan_credit_card_disbursed);
                creditLabel.setText(R.string.loan_dash_disbursed);
                creditValue.setText(KycDocuments.rupees(quote.principal));
                creditMeta.setVisibility(View.VISIBLE);
                String utr = OnboardingStore.disbursementUtr(requireContext(), uid);
                creditMeta.setText(getString(R.string.loan_dash_disbursed_meta,
                        utr.isEmpty() ? "—" : utr, KycDocuments.rupees(quote.monthlyEmi)));
                applyButton.setText(R.string.loan_dash_view_loan);
                applyButton.setOnClickListener(v -> openDisbursement());
                break;
            case OnboardingStore.APP_CLOSED:
                creditLabel.setText(R.string.loan_dash_available_credit);
                creditValue.setText(KycDocuments.rupees(credit));
                creditMeta.setVisibility(View.GONE);
                applyButton.setText(R.string.loan_dash_apply_again);
                applyButton.setOnClickListener(v -> openApply());
                moreFunds.setVisibility(View.VISIBLE);
                applyNow.setText(R.string.loan_dash_apply_again);
                break;
            default:
                creditLabel.setText(R.string.loan_dash_available_credit);
                creditValue.setText(KycDocuments.rupees(credit));
                creditMeta.setVisibility(View.GONE);
                applyButton.setText(R.string.loan_dash_apply);
                applyButton.setOnClickListener(v -> openApply());
                moreFunds.setVisibility(View.VISIBLE);
                applyNow.setText(R.string.loan_dash_apply_now);
                break;
        }
    }

    private void openReviewIfSubmitted() {
        int status = OnboardingStore.applicationStatus(requireContext(), uid);
        if (status == OnboardingStore.APP_REVIEW || status == OnboardingStore.APP_REJECTED) {
            openSubmittedReview();
        } else if (status == OnboardingStore.APP_APPROVED
                || status == OnboardingStore.APP_AWAITING_DISBURSE
                || status == OnboardingStore.APP_DISBURSED) {
            openDisbursement();
        }
    }

    private void openDisbursement() {
        startActivity(new Intent(requireContext(), LoanDisbursementActivity.class));
    }

    private void openSubmittedReview() {
        Intent intent = new Intent(requireContext(), LoanReviewActivity.class);
        intent.putExtra(LoanHomeActivity.EXTRA_FROM_HOME, true);
        intent.putExtra(LoanReviewActivity.EXTRA_VIEW_ONLY, true);
        startActivity(intent);
    }

    private void openApply() {
        int status = OnboardingStore.applicationStatus(requireContext(), uid);
        if (status == OnboardingStore.APP_REVIEW) {
            Toast.makeText(requireContext(), R.string.loan_dash_apply_blocked, Toast.LENGTH_SHORT).show();
            return;
        }
        if (status == OnboardingStore.APP_APPROVED
                || status == OnboardingStore.APP_AWAITING_DISBURSE
                || status == OnboardingStore.APP_DISBURSED) {
            Toast.makeText(requireContext(), R.string.loan_dash_apply_active, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(requireContext(), LoanApplyActivity.class);
        intent.putExtra(LoanHomeActivity.EXTRA_FROM_HOME, true);
        startActivity(intent);
    }

    private void showHelp() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.loan_help_title)
                .setMessage(R.string.loan_help_body)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
