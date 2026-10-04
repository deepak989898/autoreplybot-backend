package com.autoreplybot;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;

/** Syncs the loan application to Firestore so the website owner can approve their own account. */
final class LoanCloudStore {
    private static final String TAG = "LoanCloud";

    private LoanCloudStore() {}

    static void publishSubmitted(@NonNull Context context, @NonNull String uid, @Nullable String email) {
        LoanQuote quote = OnboardingStore.quote(context, uid);
        Map<String, Object> row = base(uid);
        row.put("status", "REVIEW");
        row.put("stage", "review");
        row.put("requestedAmount", quote.principal);
        row.put("approvedAmount", 0);
        row.put("tenureMonths", quote.months);
        row.put("monthlyEmi", quote.monthlyEmi);
        row.put("annualPercent", quote.annualPercent);
        row.put("totalInterest", quote.totalInterest);
        row.put("totalPayable", quote.totalPayable);
        row.put("applicantName", OnboardingStore.name(context, uid));
        row.put("phone", OnboardingStore.phone(context, uid));
        row.put("email", email == null ? "" : email);
        row.put("submittedAt", System.currentTimeMillis());
        row.put("disbursementUtr", "");
        merge(uid, row);
    }

    static void publishBank(@NonNull Context context, @NonNull String uid) {
        Map<String, Object> row = base(uid);
        row.put("stage", "bank");
        row.put("bankHolderName", OnboardingStore.bankHolder(context, uid));
        row.put("bankName", OnboardingStore.bankName(context, uid));
        row.put("bankAccount", OnboardingStore.bankAccount(context, uid));
        row.put("bankIfsc", OnboardingStore.bankIfsc(context, uid));
        merge(uid, row);
    }

    static void publishAgreement(@NonNull Context context, @NonNull String uid) {
        Map<String, Object> row = base(uid);
        row.put("status", "AWAITING_DISBURSE");
        row.put("stage", "awaiting_disburse");
        row.put("agreementAcceptedAt", OnboardingStore.agreementAt(context, uid));
        row.put("bankHolderName", OnboardingStore.bankHolder(context, uid));
        row.put("bankName", OnboardingStore.bankName(context, uid));
        row.put("bankAccount", OnboardingStore.bankAccount(context, uid));
        row.put("bankIfsc", OnboardingStore.bankIfsc(context, uid));
        merge(uid, row);
    }

    @NonNull
    static Task<Boolean> pull(@NonNull Context context, @NonNull String uid) {
        Context app = context.getApplicationContext();
        return FirebaseFirestore.getInstance()
                .collection("users").document(uid)
                .collection("loan").document("application")
                .get()
                .continueWith(task -> {
                    if (!task.isSuccessful()) {
                        Exception e = task.getException();
                        Log.w(TAG, "pull failed", e);
                        throw e != null ? e : new RuntimeException("Status refresh failed");
                    }
                    DocumentSnapshot snap = task.getResult();
                    if (snap == null || !snap.exists()) {
                        if (OnboardingStore.applicationStatus(app, uid) == OnboardingStore.APP_REVIEW) {
                            publishSubmitted(app, uid, "");
                        }
                        return false;
                    }
                    return OnboardingStore.applyCloud(app, uid, snap.getData());
                });
    }

    static void pullQuiet(@NonNull Context context, @NonNull String uid) {
        pull(context, uid).addOnFailureListener(e -> Log.w(TAG, "quiet pull failed", e));
    }

    private static void merge(@NonNull String uid, @NonNull Map<String, Object> row) {
        FirebaseFirestore.getInstance()
                .collection("users").document(uid)
                .collection("loan").document("application")
                .set(row, SetOptions.merge())
                .addOnFailureListener(e -> Log.w(TAG, "publish failed", e));
    }

    @NonNull
    private static Map<String, Object> base(@NonNull String uid) {
        Map<String, Object> row = new HashMap<>();
        row.put("ownerUid", uid);
        row.put("updatedAt", System.currentTimeMillis());
        return row;
    }

    @NonNull
    static Task<Void> awaitOk(@NonNull Task<Boolean> task) {
        return task.continueWithTask(t -> {
            if (!t.isSuccessful()) return Tasks.forException(t.getException());
            return Tasks.forResult(null);
        });
    }
}
