package com.autoreplybot;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.io.File;

public class LoanRepaymentFragment extends Fragment {
    private String uid = "";
    private LinearLayout schedule;
    private TextView amountView;
    private TextView emiLine;
    private int pendingIndex = -1;
    private boolean screenshotReady;
    private ImageView screenshotPreview;
    private TextInputEditText inputUtr;

    private final ActivityResultLauncher<String> galleryPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    launchGallery();
                } else {
                    Toast.makeText(requireContext(),
                            R.string.loan_repay_gallery_permission_body,
                            Toast.LENGTH_LONG).show();
                }
            });

    private final ActivityResultLauncher<String> pickImage =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null || pendingIndex < 0 || !isAdded()) return;
                File dest = KycDocuments.emiProofFile(requireContext(), uid, pendingIndex);
                if (KycDocuments.copyUri(requireContext(), uri, dest)) {
                    screenshotReady = true;
                    if (screenshotPreview != null) {
                        screenshotPreview.setVisibility(View.VISIBLE);
                        screenshotPreview.setImageURI(null);
                        screenshotPreview.setImageURI(Uri.fromFile(dest));
                    }
                    Toast.makeText(requireContext(), R.string.loan_repay_screenshot_ok, Toast.LENGTH_SHORT)
                            .show();
                } else {
                    Toast.makeText(requireContext(), R.string.kyc_save_failed, Toast.LENGTH_SHORT).show();
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_loan_repayment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        uid = user != null ? user.getUid() : "";
        amountView = view.findViewById(R.id.repay_amount);
        emiLine = view.findViewById(R.id.repay_emi_line);
        schedule = view.findViewById(R.id.repay_schedule);
        view.findViewById(R.id.button_make_payment).setOnClickListener(v -> payNext());
        bind();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (schedule != null) bind();
    }

    private void bind() {
        int appStatus = OnboardingStore.applicationStatus(requireContext(), uid);
        if (appStatus != OnboardingStore.APP_DISBURSED && appStatus != OnboardingStore.APP_CLOSED) {
            amountView.setText(KycDocuments.rupees(0));
            if (appStatus == OnboardingStore.APP_REVIEW) {
                emiLine.setText(R.string.loan_repay_app_review);
            } else if (appStatus == OnboardingStore.APP_REJECTED) {
                emiLine.setText(R.string.loan_repay_app_rejected);
            } else if (appStatus == OnboardingStore.APP_APPROVED) {
                emiLine.setText(R.string.loan_repay_app_approved);
            } else if (appStatus == OnboardingStore.APP_AWAITING_DISBURSE) {
                emiLine.setText(R.string.loan_repay_app_awaiting);
            } else {
                emiLine.setText(R.string.loan_repay_no_loan);
            }
            schedule.removeAllViews();
            return;
        }
        int amount = OnboardingStore.amount(requireContext(), uid);
        LoanQuote quote = OnboardingStore.quote(requireContext(), uid);
        int tenure = quote.months;
        long approved = OnboardingStore.approvedAt(requireContext(), uid);
        amountView.setText(KycDocuments.rupees(amount));
        long firstDue = LoanDates.addMonths(approved, 1);
        emiLine.setText(getString(R.string.loan_repay_emi_due,
                KycDocuments.rupees(quote.monthlyEmi), LoanDates.medium(firstDue)));
        schedule.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        boolean pendingAssigned = false;
        for (int i = 0; i < tenure; i++) {
            View row = inflater.inflate(R.layout.item_loan_emi, schedule, false);
            TextView date = row.findViewById(R.id.emi_date);
            TextView amt = row.findViewById(R.id.emi_amount);
            TextView status = row.findViewById(R.id.emi_status);
            date.setText(LoanDates.medium(LoanDates.addMonths(approved, i + 1)));
            amt.setText(KycDocuments.rupees(quote.emiForInstallment(i)));
            int st = OnboardingStore.emiStatus(requireContext(), uid, i);
            if (st == OnboardingStore.EMI_PAID) {
                status.setText(R.string.loan_repay_paid);
                status.setTextColor(ContextCompat.getColor(requireContext(), R.color.loan_status_active));
            } else if (st == OnboardingStore.EMI_REVIEW) {
                status.setText(R.string.loan_repay_in_review);
                status.setTextColor(ContextCompat.getColor(requireContext(), R.color.loan_status_review));
            } else if (!pendingAssigned) {
                pendingAssigned = true;
                status.setText(R.string.loan_repay_pending);
                status.setTextColor(ContextCompat.getColor(requireContext(), R.color.loan_status_pending));
            } else {
                status.setText(R.string.loan_repay_upcoming);
                status.setTextColor(ContextCompat.getColor(requireContext(), R.color.loan_status_closed));
            }
            schedule.addView(row);
        }
    }

    private void payNext() {
        if (!OnboardingStore.hasActiveLoan(requireContext(), uid)) {
            Toast.makeText(requireContext(),
                    OnboardingStore.applicationStatus(requireContext(), uid) == OnboardingStore.APP_REVIEW
                            ? R.string.loan_repay_app_review
                            : R.string.loan_repay_no_loan,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        int tenure = OnboardingStore.tenure(requireContext(), uid);
        int index = OnboardingStore.nextPayableIndex(requireContext(), uid, tenure);
        if (index < 0) {
            Toast.makeText(requireContext(), R.string.loan_repay_none, Toast.LENGTH_SHORT).show();
            return;
        }
        if (OnboardingStore.emiStatus(requireContext(), uid, index) == OnboardingStore.EMI_REVIEW) {
            Toast.makeText(requireContext(), R.string.loan_repay_already_review, Toast.LENGTH_SHORT).show();
            return;
        }
        String emi = KycDocuments.rupees(OnboardingStore.quote(requireContext(), uid)
                .emiForInstallment(index));
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(getString(R.string.loan_repay_confirm, emi))
                .setPositiveButton(R.string.loan_repay_pay, (d, w) -> showBankDetails(index))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showBankDetails(int index) {
        pendingIndex = index;
        screenshotReady = KycDocuments.emiProofFile(requireContext(), uid, index).isFile();
        View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_emi_bank, null, false);
        screenshotPreview = content.findViewById(R.id.image_screenshot);
        inputUtr = content.findViewById(R.id.input_utr);
        File existing = KycDocuments.emiProofFile(requireContext(), uid, index);
        if (existing.isFile() && existing.length() > 0) {
            screenshotPreview.setVisibility(View.VISIBLE);
            screenshotPreview.setImageURI(Uri.fromFile(existing));
        }
        content.findViewById(R.id.button_upload_screenshot).setOnClickListener(v ->
                ensureGalleryThenPick());
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.loan_repay_bank_title)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.loan_repay_submit, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    if (submitProof(index)) {
                        dialog.dismiss();
                    }
                }));
        dialog.show();
    }

    private boolean submitProof(int index) {
        String utr = "";
        if (inputUtr != null && inputUtr.getText() != null) {
            utr = inputUtr.getText().toString().trim();
        }
        File proof = KycDocuments.emiProofFile(requireContext(), uid, index);
        boolean hasShot = screenshotReady || (proof.isFile() && proof.length() > 0);
        if (!hasShot && utr.isEmpty()) {
            Toast.makeText(requireContext(), R.string.loan_repay_need_proof, Toast.LENGTH_SHORT).show();
            return false;
        }
        if (!utr.isEmpty()) {
            OnboardingStore.setEmiUtr(requireContext(), uid, index, utr);
        }
        OnboardingStore.setEmiStatus(requireContext(), uid, index, OnboardingStore.EMI_REVIEW);
        bind();
        Toast.makeText(requireContext(), R.string.loan_repay_submitted, Toast.LENGTH_SHORT).show();
        return true;
    }

    private void ensureGalleryThenPick() {
        String perm = galleryPermissionName();
        if (perm == null
                || ContextCompat.checkSelfPermission(requireContext(), perm)
                == PackageManager.PERMISSION_GRANTED) {
            launchGallery();
            return;
        }
        if (shouldShowRequestPermissionRationale(perm)) {
            String requested = perm;
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.loan_repay_gallery_permission_title)
                    .setMessage(R.string.loan_repay_gallery_permission_body)
                    .setPositiveButton(R.string.kyc_allow, (d, w) ->
                            galleryPermission.launch(requested))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        galleryPermission.launch(perm);
    }

    @Nullable
    private static String galleryPermissionName() {
        if (Build.VERSION.SDK_INT >= 33) {
            return Manifest.permission.READ_MEDIA_IMAGES;
        }
        return Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private void launchGallery() {
        pickImage.launch("image/*");
    }
}
