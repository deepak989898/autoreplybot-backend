package com.autoreplybot;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.Locale;
import java.util.Map;

/** Tracks KYC + loan onboarding for newly created accounts only. */
final class OnboardingStore {
    private static final String PREF = "loan_onboarding";

    private OnboardingStore() {}

    static boolean needsLoanFlow(@NonNull Context context, @Nullable String uid) {
        if (uid == null || uid.isEmpty()) return false;
        SharedPreferences p = prefs(context);
        return p.getBoolean(pendingKey(uid), false) && !p.getBoolean(doneKey(uid), false);
    }

    @NonNull
    static Class<?> nextActivity(@NonNull Context context, @NonNull String uid) {
        if (!isKycDone(context, uid)) return KycActivity.class;
        if (!isLoanSaved(context, uid)) return LoanApplyActivity.class;
        return LoanReviewActivity.class;
    }

    static boolean isKycDone(@NonNull Context context, @NonNull String uid) {
        return prefs(context).getBoolean(kycKey(uid), false)
                || KycDocuments.allPresent(context, uid);
    }

    static boolean isLoanSaved(@NonNull Context context, @NonNull String uid) {
        return prefs(context).getBoolean(loanSavedKey(uid), false);
    }

    static void markKycDone(@NonNull Context context, @NonNull String uid) {
        prefs(context).edit().putBoolean(kycKey(uid), true).apply();
    }

    static boolean needsLoanFlowForCurrentUser(@NonNull Context context) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user != null && needsLoanFlow(context, user.getUid());
    }

    static void markNewAccount(
            @NonNull Context context,
            @NonNull String uid,
            @NonNull String name,
            @NonNull String phone) {
        prefs(context).edit()
                .putBoolean(pendingKey(uid), true)
                .putBoolean(doneKey(uid), false)
                .putBoolean(kycKey(uid), false)
                .putBoolean(loanSavedKey(uid), false)
                .putString(nameKey(uid), name)
                .putString(phoneKey(uid), phone)
                .apply();
    }

    static void saveLoan(
            @NonNull Context context,
            @NonNull String uid,
            int amountInr,
            int tenureMonths,
            int emiInr) {
        SharedPreferences.Editor ed = prefs(context).edit()
                .putBoolean(loanSavedKey(uid), true)
                .putInt(appStatusKey(uid), APP_NONE)
                .putInt(amountKey(uid), amountInr)
                .putInt(tenureKey(uid), tenureMonths)
                .putInt(emiKey(uid), emiInr)
                .putInt(paidKey(uid), 0)
                .putLong(approvedKey(uid), 0L)
                .remove(bankHolderKey(uid))
                .remove(bankNameKey(uid))
                .remove(bankAccountKey(uid))
                .remove(bankIfscKey(uid))
                .remove(agreementKey(uid))
                .remove(disburseUtrKey(uid));
        for (int i = 0; i < 24; i++) {
            ed.remove(emiStatusKey(uid, i));
            ed.remove(emiUtrKey(uid, i));
        }
        ed.apply();
    }

    static final int APP_NONE = 0;
    static final int APP_REVIEW = 1;
    static final int APP_APPROVED = 2;
    static final int APP_REJECTED = 3;
    static final int APP_CLOSED = 4;
    static final int APP_AWAITING_DISBURSE = 5;
    static final int APP_DISBURSED = 6;

    static final int EMI_UNPAID = 0;
    static final int EMI_REVIEW = 1;
    static final int EMI_PAID = 2;

    static int applicationStatus(@NonNull Context context, @Nullable String uid) {
        if (uid == null || uid.isEmpty()) return APP_NONE;
        SharedPreferences p = prefs(context);
        int stored = p.getInt(appStatusKey(uid), -1);
        if (stored == APP_DISBURSED && allEmisPaid(context, uid)) return APP_CLOSED;
        if (stored == APP_APPROVED && allEmisPaid(context, uid) && isDisbursed(context, uid)) {
            return APP_CLOSED;
        }
        if (stored == APP_REVIEW
                || stored == APP_REJECTED
                || stored == APP_APPROVED
                || stored == APP_CLOSED
                || stored == APP_AWAITING_DISBURSE
                || stored == APP_DISBURSED) {
            return stored;
        }
        if (stored == APP_NONE) return APP_NONE;
        if (!isLoanSaved(context, uid) || !p.getBoolean(doneKey(uid), false)) return APP_NONE;
        if (allEmisPaid(context, uid)) return APP_CLOSED;
        if (hasEmiProgress(context, uid)) return APP_DISBURSED;
        return APP_REVIEW;
    }

    static boolean hasEmiProgress(@NonNull Context context, @NonNull String uid) {
        int tenure = tenure(context, uid);
        for (int i = 0; i < tenure; i++) {
            int st = emiStatus(context, uid, i);
            if (st == EMI_REVIEW || st == EMI_PAID) return true;
        }
        return false;
    }

    static boolean canApply(@NonNull Context context, @Nullable String uid) {
        int status = applicationStatus(context, uid);
        return status == APP_NONE || status == APP_REJECTED || status == APP_CLOSED;
    }

    static boolean hasActiveLoan(@NonNull Context context, @Nullable String uid) {
        return applicationStatus(context, uid) == APP_DISBURSED;
    }

    static void markSubmitted(@NonNull Context context, @NonNull String uid) {
        prefs(context).edit()
                .putBoolean(pendingKey(uid), false)
                .putBoolean(doneKey(uid), true)
                .putBoolean(loanSavedKey(uid), true)
                .putInt(appStatusKey(uid), APP_REVIEW)
                .putLong(approvedKey(uid), 0L)
                .apply();
    }

    static void markApproved(@NonNull Context context, @NonNull String uid) {
        markApproved(context, uid, amount(context, uid));
    }

    static void markApproved(@NonNull Context context, @NonNull String uid, int approvedAmount) {
        SharedPreferences p = prefs(context);
        long approved = p.getLong(approvedKey(uid), 0L);
        if (approved <= 0L) approved = System.currentTimeMillis();
        int principal = LoanQuote.snapAmount(approvedAmount);
        LoanQuote quote = LoanQuote.of(principal, tenure(context, uid));
        p.edit()
                .putBoolean(pendingKey(uid), false)
                .putBoolean(doneKey(uid), true)
                .putBoolean(loanSavedKey(uid), true)
                .putInt(appStatusKey(uid), APP_APPROVED)
                .putInt(amountKey(uid), quote.principal)
                .putInt(emiKey(uid), quote.monthlyEmi)
                .putLong(approvedKey(uid), approved)
                .apply();
    }

    static void markRejected(@NonNull Context context, @NonNull String uid) {
        prefs(context).edit()
                .putBoolean(pendingKey(uid), false)
                .putBoolean(doneKey(uid), true)
                .putBoolean(loanSavedKey(uid), true)
                .putInt(appStatusKey(uid), APP_REJECTED)
                .putLong(approvedKey(uid), 0L)
                .apply();
    }

    static void prepareNewApplication(@NonNull Context context, @NonNull String uid) {
        SharedPreferences.Editor ed = prefs(context).edit()
                .putBoolean(loanSavedKey(uid), false)
                .putInt(appStatusKey(uid), APP_NONE)
                .putInt(paidKey(uid), 0)
                .putLong(approvedKey(uid), 0L)
                .remove(bankHolderKey(uid))
                .remove(bankNameKey(uid))
                .remove(bankAccountKey(uid))
                .remove(bankIfscKey(uid))
                .remove(agreementKey(uid))
                .remove(disburseUtrKey(uid));
        for (int i = 0; i < 24; i++) {
            ed.remove(emiStatusKey(uid, i));
            ed.remove(emiUtrKey(uid, i));
        }
        ed.apply();
    }

    static boolean allEmisPaid(@NonNull Context context, @Nullable String uid) {
        if (uid == null || !isLoanSaved(context, uid)) return false;
        int tenure = tenure(context, uid);
        if (tenure <= 0) return false;
        for (int i = 0; i < tenure; i++) {
            if (emiStatus(context, uid, i) != EMI_PAID) return false;
        }
        return true;
    }

    static int emiStatus(@NonNull Context context, @Nullable String uid, int index) {
        if (uid == null || index < 0) return EMI_UNPAID;
        int stored = prefs(context).getInt(emiStatusKey(uid, index), -1);
        if (stored >= 0) return stored;
        return index < paidInstallments(context, uid) ? EMI_PAID : EMI_UNPAID;
    }

    static void setEmiStatus(@NonNull Context context, @NonNull String uid, int index, int status) {
        prefs(context).edit().putInt(emiStatusKey(uid, index), status).apply();
    }

    static void setEmiUtr(@NonNull Context context, @NonNull String uid, int index, @NonNull String utr) {
        prefs(context).edit().putString(emiUtrKey(uid, index), utr.trim()).apply();
    }

    static int nextPayableIndex(@NonNull Context context, @NonNull String uid, int tenure) {
        for (int i = 0; i < tenure; i++) {
            int st = emiStatus(context, uid, i);
            if (st == EMI_UNPAID) return i;
            if (st == EMI_REVIEW) return i;
        }
        return -1;
    }

    static void markComplete(@NonNull Context context, @NonNull String uid) {
        markSubmitted(context, uid);
    }

    static long approvedAt(@NonNull Context context, @Nullable String uid) {
        if (uid == null) return 0L;
        return prefs(context).getLong(approvedKey(uid), 0L);
    }

    static int paidInstallments(@NonNull Context context, @Nullable String uid) {
        if (uid == null) return 0;
        return prefs(context).getInt(paidKey(uid), 0);
    }

    static void setPaidInstallments(@NonNull Context context, @NonNull String uid, int count) {
        prefs(context).edit().putInt(paidKey(uid), Math.max(0, count)).apply();
    }

    static int maxCredit() {
        return LoanQuote.MAX_AMOUNT;
    }

    static int availableCredit(@NonNull Context context, @Nullable String uid) {
        int max = maxCredit();
        int status = applicationStatus(context, uid);
        if (status == APP_NONE || status == APP_REJECTED || status == APP_CLOSED) return max;
        return Math.max(0, max - amount(context, uid));
    }

    @NonNull
    static String firstName(@NonNull Context context, @Nullable String uid, @Nullable String fallback) {
        String name = name(context, uid);
        if (name.isEmpty() && fallback != null) name = fallback;
        name = name.trim();
        if (name.isEmpty()) return "there";
        int space = name.indexOf(' ');
        return space > 0 ? name.substring(0, space) : name;
    }

    @NonNull
    static String name(@NonNull Context context, @Nullable String uid) {
        if (uid == null) return "";
        return prefs(context).getString(nameKey(uid), "");
    }

    @NonNull
    static String phone(@NonNull Context context, @Nullable String uid) {
        if (uid == null) return "";
        return prefs(context).getString(phoneKey(uid), "");
    }

    static int amount(@NonNull Context context, @Nullable String uid) {
        if (uid == null) return 5000;
        return prefs(context).getInt(amountKey(uid), 5000);
    }

    static int tenure(@NonNull Context context, @Nullable String uid) {
        if (uid == null) return 3;
        return prefs(context).getInt(tenureKey(uid), 3);
    }

    static int emi(@NonNull Context context, @Nullable String uid) {
        return quote(context, uid).monthlyEmi;
    }

    @NonNull
    static LoanQuote quote(@NonNull Context context, @Nullable String uid) {
        return LoanQuote.of(amount(context, uid), tenure(context, uid));
    }

    static void saveBank(
            @NonNull Context context,
            @NonNull String uid,
            @NonNull String holder,
            @NonNull String bank,
            @NonNull String account,
            @NonNull String ifsc) {
        prefs(context).edit()
                .putString(bankHolderKey(uid), holder.trim())
                .putString(bankNameKey(uid), bank.trim())
                .putString(bankAccountKey(uid), account.trim())
                .putString(bankIfscKey(uid), ifsc.trim().toUpperCase(Locale.US))
                .apply();
    }

    static boolean hasBank(@NonNull Context context, @Nullable String uid) {
        if (uid == null) return false;
        return !bankAccount(context, uid).isEmpty() && !bankIfsc(context, uid).isEmpty();
    }

    @NonNull static String bankHolder(@NonNull Context context, @Nullable String uid) {
        if (uid == null) return "";
        return prefs(context).getString(bankHolderKey(uid), "");
    }

    @NonNull static String bankName(@NonNull Context context, @Nullable String uid) {
        if (uid == null) return "";
        return prefs(context).getString(bankNameKey(uid), "");
    }

    @NonNull static String bankAccount(@NonNull Context context, @Nullable String uid) {
        if (uid == null) return "";
        return prefs(context).getString(bankAccountKey(uid), "");
    }

    @NonNull static String bankIfsc(@NonNull Context context, @Nullable String uid) {
        if (uid == null) return "";
        return prefs(context).getString(bankIfscKey(uid), "");
    }

    static void markAgreementAccepted(@NonNull Context context, @NonNull String uid) {
        prefs(context).edit()
                .putLong(agreementKey(uid), System.currentTimeMillis())
                .putInt(appStatusKey(uid), APP_AWAITING_DISBURSE)
                .apply();
    }

    static boolean hasAgreement(@NonNull Context context, @Nullable String uid) {
        return agreementAt(context, uid) > 0L;
    }

    static long agreementAt(@NonNull Context context, @Nullable String uid) {
        if (uid == null) return 0L;
        return prefs(context).getLong(agreementKey(uid), 0L);
    }

    static boolean isDisbursed(@NonNull Context context, @Nullable String uid) {
        if (uid == null) return false;
        int stored = prefs(context).getInt(appStatusKey(uid), APP_NONE);
        return stored == APP_DISBURSED || stored == APP_CLOSED
                || !disbursementUtr(context, uid).isEmpty();
    }

    @NonNull static String disbursementUtr(@NonNull Context context, @Nullable String uid) {
        if (uid == null) return "";
        return prefs(context).getString(disburseUtrKey(uid), "");
    }

    static void markDisbursed(@NonNull Context context, @NonNull String uid, @NonNull String utr) {
        prefs(context).edit()
                .putInt(appStatusKey(uid), APP_DISBURSED)
                .putString(disburseUtrKey(uid), utr.trim())
                .apply();
    }

    static boolean needsDisbursementSteps(@NonNull Context context, @Nullable String uid) {
        int status = applicationStatus(context, uid);
        return status == APP_APPROVED || status == APP_AWAITING_DISBURSE;
    }

    private static int progressRank(int status) {
        switch (status) {
            case APP_REVIEW: return 1;
            case APP_APPROVED: return 2;
            case APP_AWAITING_DISBURSE: return 3;
            case APP_DISBURSED: return 4;
            case APP_CLOSED: return 5;
            case APP_REJECTED: return 1;
            default: return 0;
        }
    }

    private static int statusFromCloud(@NonNull String status) {
        switch (status) {
            case "REVIEW": return APP_REVIEW;
            case "APPROVED": return APP_APPROVED;
            case "AWAITING_DISBURSE": return APP_AWAITING_DISBURSE;
            case "DISBURSED": return APP_DISBURSED;
            case "CLOSED": return APP_CLOSED;
            case "REJECTED": return APP_REJECTED;
            default: return APP_NONE;
        }
    }

    /** @return true if local status changed */
    static boolean applyCloud(@NonNull Context context, @NonNull String uid,
                              @Nullable Map<String, Object> data) {
        if (data == null) return false;
        int before = applicationStatus(context, uid);
        int stored = prefs(context).getInt(appStatusKey(uid), APP_NONE);
        String status = String.valueOf(data.get("status") == null ? "" : data.get("status"))
                .toUpperCase(Locale.US);
        int incoming = statusFromCloud(status);
        int requested = intVal(data.get("requestedAmount"), amount(context, uid));
        int approvedAmt = intVal(data.get("approvedAmount"), 0);
        int tenureM = intVal(data.get("tenureMonths"), tenure(context, uid));
        boolean mayAdvance = incoming == APP_REJECTED
                ? stored == APP_REVIEW || stored == APP_NONE || stored == APP_REJECTED
                : progressRank(incoming) >= progressRank(stored);
        if (!mayAdvance) {
            return false;
        }
        if ("REJECTED".equals(status)) {
            markRejected(context, uid);
        } else if ("APPROVED".equals(status)) {
            markApproved(context, uid, approvedAmt > 0 ? approvedAmt : requested);
            String holder = strVal(data.get("bankHolderName"));
            if (!holder.isEmpty()) {
                saveBank(context, uid, holder, strVal(data.get("bankName")),
                        strVal(data.get("bankAccount")), strVal(data.get("bankIfsc")));
            }
        } else if ("AWAITING_DISBURSE".equals(status)) {
            markApproved(context, uid, approvedAmt > 0 ? approvedAmt : requested);
            saveBank(context, uid, strVal(data.get("bankHolderName")), strVal(data.get("bankName")),
                    strVal(data.get("bankAccount")), strVal(data.get("bankIfsc")));
            long ag = longVal(data.get("agreementAcceptedAt"));
            prefs(context).edit()
                    .putLong(agreementKey(uid), ag > 0 ? ag : System.currentTimeMillis())
                    .putInt(appStatusKey(uid), APP_AWAITING_DISBURSE)
                    .apply();
        } else if ("DISBURSED".equals(status) || "CLOSED".equals(status)) {
            markApproved(context, uid, approvedAmt > 0 ? approvedAmt : requested);
            markDisbursed(context, uid, strVal(data.get("disbursementUtr")));
            if ("CLOSED".equals(status)) {
                prefs(context).edit().putInt(appStatusKey(uid), APP_CLOSED).apply();
            }
        } else if ("REVIEW".equals(status)) {
            markSubmitted(context, uid);
            if (requested > 0) {
                prefs(context).edit()
                        .putInt(amountKey(uid), LoanQuote.snapAmount(requested))
                        .putInt(tenureKey(uid), Math.max(1, tenureM))
                        .apply();
            }
        }
        return applicationStatus(context, uid) != before;
    }

    private static int intVal(@Nullable Object v, int fallback) {
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return v == null ? fallback : Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long longVal(@Nullable Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return v == null ? 0L : Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @NonNull
    private static String strVal(@Nullable Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static String pendingKey(String uid) { return "pending_" + uid; }
    private static String doneKey(String uid) { return "done_" + uid; }
    private static String kycKey(String uid) { return "kyc_" + uid; }
    private static String loanSavedKey(String uid) { return "loan_saved_" + uid; }
    private static String nameKey(String uid) { return "name_" + uid; }
    private static String phoneKey(String uid) { return "phone_" + uid; }
    private static String amountKey(String uid) { return "amount_" + uid; }
    private static String tenureKey(String uid) { return "tenure_" + uid; }
    private static String emiKey(String uid) { return "emi_" + uid; }
    private static String appStatusKey(String uid) { return "app_status_" + uid; }
    private static String approvedKey(String uid) { return "approved_" + uid; }
    private static String paidKey(String uid) { return "paid_" + uid; }
    private static String emiStatusKey(String uid, int index) { return "emi_st_" + uid + "_" + index; }
    private static String emiUtrKey(String uid, int index) { return "emi_utr_" + uid + "_" + index; }
    private static String bankHolderKey(String uid) { return "bank_holder_" + uid; }
    private static String bankNameKey(String uid) { return "bank_name_" + uid; }
    private static String bankAccountKey(String uid) { return "bank_acct_" + uid; }
    private static String bankIfscKey(String uid) { return "bank_ifsc_" + uid; }
    private static String agreementKey(String uid) { return "loan_ag_" + uid; }
    private static String disburseUtrKey(String uid) { return "loan_utr_" + uid; }
}
