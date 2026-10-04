package com.autoreplybot;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

final class KycDocuments {
    enum Slot {
        AADHAAR_FRONT("aadhaar_front"),
        AADHAAR_BACK("aadhaar_back"),
        PAN("pan"),
        SELFIE("selfie");

        final String fileId;

        Slot(String fileId) {
            this.fileId = fileId;
        }
    }

    private KycDocuments() {}

    @NonNull
    static File dir(@NonNull Context context) {
        File dir = new File(context.getFilesDir(), "kyc");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    @NonNull
    static File captureDir(@NonNull Context context) {
        File dir = new File(context.getCacheDir(), "kyc");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    @NonNull
    static File destFile(@NonNull Context context, @NonNull String uid, @NonNull Slot slot) {
        return new File(dir(context), uid + "_" + slot.fileId + ".jpg");
    }

    @NonNull
    static File emiProofFile(@NonNull Context context, @NonNull String uid, int index) {
        File dir = new File(context.getFilesDir(), "emi");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return new File(dir, uid + "_" + index + ".jpg");
    }

    static boolean exists(@NonNull Context context, @NonNull String uid, @NonNull Slot slot) {
        File f = destFile(context, uid, slot);
        return f.isFile() && f.length() > 0;
    }

    static boolean allPresent(@NonNull Context context, @NonNull String uid) {
        for (Slot slot : Slot.values()) {
            if (!exists(context, uid, slot)) return false;
        }
        return true;
    }

    @NonNull
    static File newCaptureFile(@NonNull Context context, @NonNull Slot slot) {
        return new File(captureDir(context),
                "capture_" + slot.fileId + "_" + System.currentTimeMillis() + ".jpg");
    }

    static boolean copyUri(@NonNull Context context, @NonNull Uri uri, @NonNull File dest) {
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            if (in == null) return false;
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            out.flush();
            return total > 0;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean copyFile(@NonNull File src, @NonNull File dest) {
        if (!src.isFile() || src.length() == 0) return false;
        try (InputStream in = new java.io.FileInputStream(src);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            return dest.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    @NonNull
    static String rupees(int amount) {
        return "₹ " + String.format(Locale.US, "%,d", amount);
    }
}
