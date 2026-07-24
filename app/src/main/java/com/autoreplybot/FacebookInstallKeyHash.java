package com.autoreplybot;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Logs the install key hash Meta requires for Android Facebook Login (Logcat tag {@code FB_KEY_HASH}).
 * Add this exact string to Meta Developer → Your app → Settings → Basic → Android → Key hashes.
 */
public final class FacebookInstallKeyHash {

    private static final String TAG = "FB_KEY_HASH";

    private FacebookInstallKeyHash() {}

    /** Call once from a debug screen; copy the line from Logcat into Meta dashboard. */
    public static void logIfDebug(@NonNull Context context) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        try {
            String hash = computeSha1KeyHash(context.getApplicationContext());
            Log.i(TAG, "Paste this Key hash into Meta Developer App (Android): " + hash);
        } catch (Exception e) {
            Log.w(TAG, "Could not compute key hash", e);
        }
    }

    @NonNull
    private static String computeSha1KeyHash(@NonNull Context context)
            throws PackageManager.NameNotFoundException, NoSuchAlgorithmException {
        PackageManager pm = context.getPackageManager();
        String pkg = context.getPackageName();
        PackageInfo info;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
            if (info.signingInfo == null) {
                throw new IllegalStateException("No signing info");
            }
            Signature[] sigs = info.signingInfo.getApkContentsSigners();
            return hashSignatures(sigs);
        } else {
            @SuppressWarnings("deprecation")
            PackageInfo legacy = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
            @SuppressWarnings("deprecation")
            Signature[] sigs = legacy.signatures;
            return hashSignatures(sigs);
        }
    }

    @NonNull
    private static String hashSignatures(@NonNull Signature[] sigs)
            throws NoSuchAlgorithmException {
        String last = "";
        for (Signature sig : sigs) {
            MessageDigest md = MessageDigest.getInstance("SHA");
            md.update(sig.toByteArray());
            last = Base64.encodeToString(md.digest(), Base64.DEFAULT).trim();
        }
        return last;
    }
}
