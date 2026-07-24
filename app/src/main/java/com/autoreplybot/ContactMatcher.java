package com.autoreplybot;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.HashSet;
import java.util.Set;

/**
 * Best-effort contact matching from notification title/text. WhatsApp often shows a display name, not a number.
 */
public final class ContactMatcher {

    private ContactMatcher() {}

    public static boolean hasContactsPermission(@NonNull Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Nullable
    public static String extractDigits(@Nullable String raw) {
        if (raw == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isDigit(c)) sb.append(c);
        }
        String s = sb.toString();
        return s.length() >= 7 ? s : null;
    }

    public static boolean isInContactsByPhone(@NonNull Context context, @NonNull String normalizedDigits) {
        if (!hasContactsPermission(context)) return false;
        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(normalizedDigits));
        try (Cursor c = context.getContentResolver().query(uri,
                new String[]{ContactsContract.PhoneLookup._ID},
                null, null, null)) {
            return c != null && c.moveToFirst();
        }
    }

    public static boolean isInContactsByName(@NonNull Context context, @NonNull String displayName) {
        if (!hasContactsPermission(context)) return false;
        if (displayName.trim().length() < 2) return false;
        Uri uri = ContactsContract.Contacts.CONTENT_URI;
        String sel = ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " = ?";
        try (Cursor c = context.getContentResolver().query(uri,
                new String[]{ContactsContract.Contacts._ID},
                sel,
                new String[]{displayName.trim()},
                null)) {
            return c != null && c.moveToFirst();
        }
    }

    public static boolean matchesWhitelist(@NonNull String whitelistCsv, @Nullable String senderTitle,
                                           @Nullable String messageText) {
        Set<String> allowed = parseWhitelist(whitelistCsv);
        if (allowed.isEmpty()) return false;
        String fromDigits = extractDigits(senderTitle);
        if (fromDigits != null && allowed.contains(fromDigits)) return true;
        String msgDigits = messageText != null ? extractDigits(messageText) : null;
        if (msgDigits != null && allowed.contains(msgDigits)) return true;
        return false;
    }

    @NonNull
    private static Set<String> parseWhitelist(@NonNull String csv) {
        Set<String> set = new HashSet<>();
        for (String part : csv.split("[,;\\s]+")) {
            String d = extractDigits(part.trim());
            if (d != null) set.add(d);
        }
        return set;
    }

    public enum MatchResult {
        ALLOW,
        SKIP
    }

    @NonNull
    public static MatchResult shouldReply(@NonNull Context context,
                                          @NonNull UserSettings.ContactFilter filter,
                                          @NonNull String whitelistCsv,
                                          @Nullable String notificationTitle,
                                          @Nullable String notificationText) {
        String title = notificationTitle != null ? notificationTitle : "";

        switch (filter) {
            case ALL:
                return MatchResult.ALLOW;
            case WHITELIST:
                return matchesWhitelist(whitelistCsv, title, notificationText) ? MatchResult.ALLOW : MatchResult.SKIP;
            case CONTACTS_ONLY:
            case UNKNOWN_ONLY:
                if (!hasContactsPermission(context)) {
                    return MatchResult.SKIP;
                }
                boolean inContacts = computeInContacts(context, title);
                if (filter == UserSettings.ContactFilter.CONTACTS_ONLY) {
                    return inContacts ? MatchResult.ALLOW : MatchResult.SKIP;
                }
                return !inContacts ? MatchResult.ALLOW : MatchResult.SKIP;
            default:
                return MatchResult.ALLOW;
        }
    }

    /** Requires READ_CONTACTS to be granted; callers must guard. */
    private static boolean computeInContacts(@NonNull Context context, @NonNull String title) {
        String digits = extractDigits(title);
        boolean inContacts = false;
        if (digits != null) {
            inContacts = isInContactsByPhone(context, digits);
        }
        if (!inContacts && title.length() > 1) {
            inContacts = isInContactsByName(context, title);
        }
        return inContacts;
    }
}
