package com.autoreplybot;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.autoreplybot.remote.RemoteControlHomeActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class LoanProfileFragment extends Fragment {
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_loan_profile, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        String uid = user != null ? user.getUid() : "";
        String name = OnboardingStore.name(requireContext(), uid);
        if (name.isEmpty() && user != null && user.getDisplayName() != null) {
            name = user.getDisplayName();
        }
        ((TextView) view.findViewById(R.id.profile_name)).setText(name);
        ((TextView) view.findViewById(R.id.profile_phone)).setText(OnboardingStore.phone(requireContext(), uid));
        bindProfilePhoto(view.findViewById(R.id.profile_photo), uid);

        view.findViewById(R.id.row_personal).setOnClickListener(v ->
                showPersonalDialog(user, uid));
        view.findViewById(R.id.row_kyc).setOnClickListener(v -> {
            boolean ok = uid.length() > 0 && KycDocuments.allPresent(requireContext(), uid);
            Toast.makeText(requireContext(),
                    ok ? R.string.loan_profile_kyc_ok : R.string.loan_profile_kyc_missing,
                    Toast.LENGTH_SHORT).show();
        });
        view.findViewById(R.id.row_history).setOnClickListener(v ->
                ((LoanHomeActivity) requireActivity()).selectTab(R.id.nav_loan_history));
        view.findViewById(R.id.row_payment).setOnClickListener(v ->
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.loan_profile_payment)
                        .setMessage(R.string.loan_profile_payment_body)
                        .setPositiveButton(android.R.string.ok, null)
                        .show());
        view.findViewById(R.id.row_notifications).setOnClickListener(v ->
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.loan_dash_notifications)
                        .setMessage(R.string.loan_dash_no_notifications)
                        .setPositiveButton(android.R.string.ok, null)
                        .show());
        view.findViewById(R.id.row_help).setOnClickListener(v ->
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.loan_help_title)
                        .setMessage(R.string.loan_help_body)
                        .setPositiveButton(android.R.string.ok, null)
                        .show());
        view.findViewById(R.id.row_settings).setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), RemoteControlHomeActivity.class);
            intent.putExtra(RemoteControlHomeActivity.EXTRA_MANAGE_ONLY, true);
            startActivity(intent);
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        View view = getView();
        if (view == null) return;
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        String uid = user != null ? user.getUid() : "";
        bindProfilePhoto(view.findViewById(R.id.profile_photo), uid);
    }

    private void bindProfilePhoto(@Nullable ImageView photo, @NonNull String uid) {
        if (photo == null) return;
        File selfie = uid.isEmpty()
                ? null
                : KycDocuments.destFile(requireContext(), uid, KycDocuments.Slot.SELFIE);
        if (selfie != null && selfie.isFile() && selfie.length() > 0) {
            photo.setPadding(0, 0, 0, 0);
            photo.setImageURI(null);
            photo.setImageURI(Uri.fromFile(selfie));
        } else {
            int pad = Math.round(12 * getResources().getDisplayMetrics().density);
            photo.setPadding(pad, pad, pad, pad);
            photo.setImageResource(R.drawable.ic_loan_nav_profile);
        }
    }

    private void showPersonalDialog(@Nullable FirebaseUser user, @NonNull String uid) {
        View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_personal_info, null, false);
        String name = OnboardingStore.name(requireContext(), uid);
        if (name.isEmpty() && user != null && user.getDisplayName() != null) {
            name = user.getDisplayName();
        }
        String phone = OnboardingStore.phone(requireContext(), uid);
        String email = user != null && user.getEmail() != null ? user.getEmail() : "";
        String displayName = displayOrPlaceholder(name);
        ((TextView) content.findViewById(R.id.personal_initial)).setText(initial(displayName));
        ((TextView) content.findViewById(R.id.personal_name)).setText(displayName);
        ((TextView) content.findViewById(R.id.personal_phone)).setText(displayOrPlaceholder(phone));
        ((TextView) content.findViewById(R.id.personal_email)).setText(displayOrPlaceholder(email));

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(content)
                .create();
        content.findViewById(R.id.personal_close).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    @NonNull
    private String displayOrPlaceholder(@Nullable String value) {
        if (value == null) return getString(R.string.loan_profile_not_set);
        String trimmed = value.trim();
        return trimmed.isEmpty() ? getString(R.string.loan_profile_not_set) : trimmed;
    }

    @NonNull
    private String initial(@NonNull String name) {
        if (name.isEmpty() || name.equals(getString(R.string.loan_profile_not_set))) return "U";
        return name.substring(0, 1).toUpperCase();
    }
}
