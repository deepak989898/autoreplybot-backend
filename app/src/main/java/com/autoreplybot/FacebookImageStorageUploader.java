package com.autoreplybot;

import android.net.Uri;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageMetadata;
import com.google.firebase.storage.StorageReference;

import java.io.IOException;

/**
 * Step 3: uploads PNG bytes to Firebase Storage and returns a download URL usable by Facebook's servers.
 */
public final class FacebookImageStorageUploader {

    private FacebookImageStorageUploader() {}

    @NonNull
    public static String uploadPngAndGetPublicDownloadUrl(@NonNull byte[] pngBytes) throws IOException {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            throw new IOException("Sign in required to upload images");
        }
        String uid = user.getUid();
        String name = "fp_" + System.currentTimeMillis() + ".png";
        StorageReference ref = FirebaseStorage.getInstance()
                .getReference()
                .child("facebook_posts")
                .child(uid)
                .child(name);
        StorageMetadata metadata = new StorageMetadata.Builder()
                .setContentType("image/png")
                .build();

        try {
            Tasks.await(ref.putBytes(pngBytes, metadata));
            Uri uri = Tasks.await(ref.getDownloadUrl());
            return uri.toString();
        } catch (Exception e) {
            throw new IOException(e.getMessage() != null ? e.getMessage() : "Upload failed", e);
        }
    }
}
