package com.autoreplybot;

import androidx.annotation.NonNull;

/** Reducing-balance EMI using annual interest slabs. */
final class LoanQuote {
    static final int MIN_AMOUNT = 1_000;
    static final int MAX_AMOUNT = 200_000;
    static final int STEP = 1_000;

    final int principal;
    final int months;
    final int annualPercent;
    final int monthlyEmi;
    final int totalPayable;
    final int totalInterest;

    private LoanQuote(int principal, int months, int annualPercent,
                      int monthlyEmi, int totalPayable, int totalInterest) {
        this.principal = principal;
        this.months = months;
        this.annualPercent = annualPercent;
        this.monthlyEmi = monthlyEmi;
        this.totalPayable = totalPayable;
        this.totalInterest = totalInterest;
    }

    @NonNull
    static LoanQuote of(int amount, int tenureMonths) {
        int principal = snapAmount(amount);
        int months = tenureMonths < 1 ? 1 : tenureMonths;
        int annualPercent = annualPercent(principal);
        double monthlyRate = annualPercent / 12.0 / 100.0;
        double emiExact;
        if (monthlyRate <= 0d) {
            emiExact = principal / (double) months;
        } else {
            double factor = Math.pow(1d + monthlyRate, months);
            emiExact = principal * monthlyRate * factor / (factor - 1d);
        }
        int monthlyEmi = Math.max(1, (int) Math.round(emiExact));
        int totalPayable = monthlyEmi * months;
        int totalInterest = Math.max(0, totalPayable - principal);
        return new LoanQuote(principal, months, annualPercent, monthlyEmi, totalPayable, totalInterest);
    }

    int emiForInstallment(int index) {
        if (months <= 1 || index < months - 1) return monthlyEmi;
        return totalPayable - monthlyEmi * (months - 1);
    }

    static int annualPercent(int amount) {
        int principal = snapAmount(amount);
        if (principal <= 50_000) return 14;
        if (principal <= 100_000) return 12;
        return 10;
    }

    static int snapAmount(int amount) {
        int clamped = Math.max(MIN_AMOUNT, Math.min(MAX_AMOUNT, amount));
        int steps = (clamped - MIN_AMOUNT) / STEP;
        return MIN_AMOUNT + steps * STEP;
    }

    static int seekMax() {
        return (MAX_AMOUNT - MIN_AMOUNT) / STEP;
    }

    static int amountFromProgress(int progress) {
        int p = Math.max(0, Math.min(seekMax(), progress));
        return MIN_AMOUNT + p * STEP;
    }

    static int progressFromAmount(int amount) {
        return (snapAmount(amount) - MIN_AMOUNT) / STEP;
    }
}
