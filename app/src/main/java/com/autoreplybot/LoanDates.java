package com.autoreplybot;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

final class LoanDates {
    private LoanDates() {}

    static String medium(long millis) {
        if (millis <= 0L) millis = System.currentTimeMillis();
        return new SimpleDateFormat("d MMM yyyy", Locale.US).format(new Date(millis));
    }

    static long addMonths(long startMs, int months) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(startMs > 0L ? startMs : System.currentTimeMillis());
        cal.add(Calendar.MONTH, months);
        return cal.getTimeInMillis();
    }
}
