package com.autoreplybot;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.WindowCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.io.File;

public class KycActivity extends AppCompatActivity {
    private static final String FILE_PROVIDER = BuildConfig.APPLICATION_ID + ".fileprovider";

    private String uid = "";
    private KycDocuments.Slot pendingSlot;
    private File captureFile;
    private Uri captureUri;

    private final ActivityResultLauncher<String> cameraPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    launchCamera();
                } else {
                    explainDenied(getString(R.string.kyc_camera_denied));
                }
            });

    private final ActivityResultLauncher<String> galleryPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    launchGallery();
                } else {
                    explainDenied(getString(R.string.kyc_gallery_denied));
                }
            });

    private final ActivityResultLauncher<Uri> takePicture =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), ok -> {
                if (pendingSlot == null || captureFile == null) return;
                if (Boolean.TRUE.equals(ok) && captureFile.isFile() && captureFile.length() > 0) {
                    File dest = KycDocuments.destFile(this, uid, pendingSlot);
                    if (KycDocuments.copyFile(captureFile, dest)) {
                        refreshCards();
                    } else {
                        toast(R.string.kyc_save_failed);
                    }
                } else {
                    toast(R.string.kyc_capture_cancelled);
                }
                //noinspection ResultOfMethodCallIgnored
                captureFile.delete();
                captureFile = null;
                captureUri = null;
                pendingSlot = null;
            });

    private final ActivityResultLauncher<String> pickImage =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (pendingSlot == null) return;
                if (uri == null) {
                    pendingSlot = null;
                    return;
                }
                File dest = KycDocuments.destFile(this, uid, pendingSlot);
                if (KycDocuments.copyUri(this, uri, dest)) {
                    refreshCards();
                } else {
                    toast(R.string.kyc_save_failed);
                }
                pendingSlot = null;
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        setContentView(R.layout.activity_kyc);

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            startActivity(new Intent(this, WelcomeActivity.class));
            finish();
            return;
        }
        uid = user.getUid();

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        bindCard(findViewById(R.id.card_aadhaar_front), KycDocuments.Slot.AADHAAR_FRONT,
                R.string.kyc_aadhaar, R.string.kyc_aadhaar_front, R.drawable.ic_kyc_id);
        bindCard(findViewById(R.id.card_aadhaar_back), KycDocuments.Slot.AADHAAR_BACK,
                R.string.kyc_aadhaar, R.string.kyc_aadhaar_back, R.drawable.ic_kyc_id);
        bindCard(findViewById(R.id.card_pan), KycDocuments.Slot.PAN,
                R.string.kyc_pan, R.string.kyc_pan_hint, R.drawable.ic_kyc_id);
        bindCard(findViewById(R.id.card_selfie), KycDocuments.Slot.SELFIE,
                R.string.kyc_selfie, R.string.kyc_selfie_hint, R.drawable.ic_kyc_selfie);

        findViewById(R.id.button_continue).setOnClickListener(v -> continueNext());
        refreshCards();
    }

    private void bindCard(@NonNull View card, @NonNull KycDocuments.Slot slot,
                          int titleRes, int subtitleRes, int iconRes) {
        TextView title = card.findViewById(R.id.kyc_title);
        TextView subtitle = card.findViewById(R.id.kyc_subtitle);
        ImageView preview = card.findViewById(R.id.kyc_preview);
        title.setText(titleRes);
        subtitle.setText(subtitleRes);
        preview.setImageResource(iconRes);
        card.setOnClickListener(v -> chooseSource(slot));
    }

    private void refreshCards() {
        refreshOne(findViewById(R.id.card_aadhaar_front), KycDocuments.Slot.AADHAAR_FRONT, R.drawable.ic_kyc_id);
        refreshOne(findViewById(R.id.card_aadhaar_back), KycDocuments.Slot.AADHAAR_BACK, R.drawable.ic_kyc_id);
        refreshOne(findViewById(R.id.card_pan), KycDocuments.Slot.PAN, R.drawable.ic_kyc_id);
        refreshOne(findViewById(R.id.card_selfie), KycDocuments.Slot.SELFIE, R.drawable.ic_kyc_selfie);
    }

    private void refreshOne(@NonNull View card, @NonNull KycDocuments.Slot slot, int iconRes) {
        ImageView preview = card.findViewById(R.id.kyc_preview);
        TextView status = card.findViewById(R.id.kyc_status);
        File file = KycDocuments.destFile(this, uid, slot);
        if (file.isFile() && file.length() > 0) {
            preview.setPadding(0, 0, 0, 0);
            preview.setImageURI(null);
            preview.setImageURI(Uri.fromFile(file));
            status.setText(R.string.kyc_uploaded);
            status.setTextColor(ContextCompat.getColor(this, R.color.status_positive_text));
        } else {
            preview.setPadding(dp(10), dp(10), dp(10), dp(10));
            preview.setImageResource(iconRes);
            status.setText(R.string.kyc_upload);
            status.setTextColor(ContextCompat.getColor(this, R.color.brand_blue));
        }
    }

    private void chooseSource(@NonNull KycDocuments.Slot slot) {
        pendingSlot = slot;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.kyc_choose_source)
                .setItems(new CharSequence[]{
                        getString(R.string.kyc_source_camera),
                        getString(R.string.kyc_source_gallery)
                }, (dialog, which) -> {
                    if (which == 0) {
                        ensureCameraThenCapture();
                    } else {
                        ensureGalleryThenPick();
                    }
                })
                .setOnCancelListener(d -> pendingSlot = null)
                .show();
    }

    private void ensureCameraThenCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            launchCamera();
            return;
        }
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.kyc_camera_permission_title)
                    .setMessage(R.string.kyc_camera_permission_body)
                    .setPositiveButton(R.string.kyc_allow, (d, w) ->
                            cameraPermission.launch(Manifest.permission.CAMERA))
                    .setNegativeButton(android.R.string.cancel, (d, w) -> pendingSlot = null)
                    .show();
            return;
        }
        cameraPermission.launch(Manifest.permission.CAMERA);
    }

    private void ensureGalleryThenPick() {
        String perm = galleryPermissionName();
        if (perm == null
                || ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            launchGallery();
            return;
        }
        if (shouldShowRequestPermissionRationale(perm)) {
            String requested = perm;
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.kyc_gallery_permission_title)
                    .setMessage(R.string.kyc_gallery_permission_body)
                    .setPositiveButton(R.string.kyc_allow, (d, w) ->
                            galleryPermission.launch(requested))
                    .setNegativeButton(android.R.string.cancel, (d, w) -> pendingSlot = null)
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

    private void launchCamera() {
        if (pendingSlot == null) return;
        captureFile = KycDocuments.newCaptureFile(this, pendingSlot);
        captureUri = FileProvider.getUriForFile(this, FILE_PROVIDER, captureFile);
        takePicture.launch(captureUri);
    }

    private void launchGallery() {
        if (pendingSlot == null) return;
        pickImage.launch("image/*");
    }

    private void continueNext() {
        if (!KycDocuments.allPresent(this, uid)) {
            toast(R.string.kyc_need_all);
            return;
        }
        OnboardingStore.markKycDone(this, uid);
        startActivity(new Intent(this, LoanApplyActivity.class));
        finish();
    }

    private void explainDenied(@NonNull String message) {
        pendingSlot = null;
        new MaterialAlertDialogBuilder(this)
                .setMessage(message)
                .setPositiveButton(R.string.kyc_open_settings, (d, w) -> {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    i.setData(Uri.fromParts("package", getPackageName(), null));
                    startActivity(i);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void toast(int res) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
