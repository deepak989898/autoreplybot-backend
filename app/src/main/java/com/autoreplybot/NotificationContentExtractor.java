package com.autoreplybot;

import android.app.Notification;
import android.app.Person;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * WhatsApp and other messaging apps often use {@link Notification.MessagingStyle} ({@code android.messages})
 * instead of {@link Notification#EXTRA_TEXT}. This class reads the best available body text.
 */
public final class NotificationContentExtractor {

    private static final String TAG = "NotificationRelay";

    private static final Pattern WS = Pattern.compile("\\s+");

    private NotificationContentExtractor() {}

    /** Metadata from one MessagingStyle entry, when the source app supplied it. */
    public static final class MessagingMessage {
        @NonNull public final String text;
        @NonNull public final String senderName;
        @Nullable public final String senderKey;
        @Nullable public final String senderUri;
        public final long timestamp;
        public final boolean senderMetadataPresent;

        MessagingMessage(@NonNull String text, @NonNull String senderName,
                         @Nullable String senderKey, @Nullable String senderUri,
                         long timestamp, boolean senderMetadataPresent) {
            this.text = text;
            this.senderName = senderName;
            this.senderKey = senderKey;
            this.senderUri = senderUri;
            this.timestamp = timestamp;
            this.senderMetadataPresent = senderMetadataPresent;
        }
    }

    /**
     * Normalizes for comparing notification text to our stored last reply (whitespace-insensitive).
     */
    @NonNull
    public static String normalizeText(@Nullable String s) {
        if (s == null) return "";
        return WS.matcher(s.trim()).replaceAll(" ");
    }

    /**
     * Canonical form for deduplicating “same” customer text across WhatsApp notification refreshes
     * (formatting markers, punctuation noise, unicode variants).
     */
    @NonNull
    public static String normalizeForIncomingDedup(@Nullable String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s.trim(), Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '*' || c == '_' || c == '~' || c == '`') {
                continue;
            }
            sb.append(c);
        }
        t = WS.matcher(sb.toString()).replaceAll(" ").trim();
        while (t.length() > 0 && isTrimmableTrailingNoise(t.charAt(t.length() - 1))) {
            t = t.substring(0, t.length() - 1).trim();
        }
        if (t.length() > 800) {
            t = t.substring(0, 800);
        }
        return t;
    }

    private static boolean isTrimmableTrailingNoise(char c) {
        return c == '.' || c == ',' || c == ';' || c == ':' || c == '!' || c == '?'
                || c == '•' || c == '·' || c == '|';
    }

    /**
     * Whether a notification bubble is our last auto-reply (possibly truncated or stripped of markup).
     */
    static boolean bubbleMatchesLastAutoReply(@NonNull String bubbleDedup,
                                              @NonNull String lastReplyDedup) {
        if (lastReplyDedup.isEmpty()) {
            return false;
        }
        if (bubbleDedup.equals(lastReplyDedup)) {
            return true;
        }
        // Shade often truncates long outgoing bubbles — require a long enough prefix to avoid confusing
        // a short customer message with the start of our reply.
        if (bubbleDedup.length() >= 16
                && lastReplyDedup.length() > bubbleDedup.length() + 8
                && lastReplyDedup.startsWith(bubbleDedup)) {
            return true;
        }
        return false;
    }

    /**
     * Canonical string for hashing incoming preview text (reduces noisy diff between refreshes).
     */
    @NonNull
    public static String fingerprintSource(@Nullable String titleStr, @Nullable String bodyStr) {
        String nt = normalizeText(titleStr);
        String nb = normalizeText(bodyStr);
        if (nb.length() > 800) {
            nb = nb.substring(0, 800);
        }
        return nt + "\u0001" + nb;
    }

    @Nullable
    public static CharSequence readConversationTitle(@NonNull Notification n) {
        Bundle extras = n.extras;
        if (extras == null) return null;
        CharSequence t = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
        if (!TextUtils.isEmpty(t)) return t;
        t = extras.getCharSequence(Notification.EXTRA_TITLE);
        if (!TextUtils.isEmpty(t)) return t;
        t = extras.getCharSequence("android.title");
        return t;
    }

    /**
     * Text we should treat as “what the other person sent” for auto-reply.
     * <p>
     * After we post a reply, WhatsApp usually appends our message to {@code android.messages}; the latest
     * bubble is then <b>ours</b>, which must not trigger another AI round. We walk bubbles from newest to
     * oldest and skip any whose text equals {@code lastAutoReplySent} (normalized).
     */
    @Nullable
    public static String effectiveIncomingBody(@NonNull Notification n,
                                               @Nullable String lastAutoReplySent) {
        Bundle extras = n.extras;
        if (extras == null) return null;

        List<Parcelable> combined = extractMessagesList(extras);
        String skipDedup = normalizeForIncomingDedup(lastAutoReplySent);
        boolean skipOur = !skipDedup.isEmpty();

        if (!combined.isEmpty()) {
            for (int i = combined.size() - 1; i >= 0; i--) {
                Parcelable p = combined.get(i);
                if (!(p instanceof Bundle)) continue;
                Bundle msg = (Bundle) p;
                CharSequence t = msg.getCharSequence("text");
                if (TextUtils.isEmpty(t)) continue;
                String tnDedup = normalizeForIncomingDedup(t.toString());
                if (skipOur && bubbleMatchesLastAutoReply(tnDedup, skipDedup)) {
                    continue;
                }
                return t.toString();
            }
            return null;
        }

        CharSequence fallback = readMessageBody(n);
        if (fallback == null) return null;
        String fb = fallback.toString();
        if (skipOur && bubbleMatchesLastAutoReply(normalizeForIncomingDedup(fb), skipDedup)) {
            return null;
        }
        return fb;
    }

    /** Returns the newest non-empty MessagingStyle message, including Person identity when available. */
    @Nullable
    public static MessagingMessage readLatestMessagingMessage(@NonNull Notification n) {
        Bundle extras = n.extras;
        if (extras == null) return null;
        List<Parcelable> messages = extractMessagesList(extras);
        for (int i = messages.size() - 1; i >= 0; i--) {
            Parcelable value = messages.get(i);
            if (!(value instanceof Bundle)) continue;
            Bundle message = (Bundle) value;
            CharSequence text = message.getCharSequence("text");
            if (TextUtils.isEmpty(text)) continue;

            String senderName = "";
            String senderKey = null;
            String senderUri = null;
            boolean senderPresent = message.containsKey("sender")
                    || message.containsKey("sender_person");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Parcelable rawPerson = message.getParcelable("sender_person");
                if (rawPerson instanceof Person) {
                    Person person = (Person) rawPerson;
                    CharSequence name = person.getName();
                    senderName = name != null ? name.toString().trim() : "";
                    senderKey = person.getKey();
                    senderUri = person.getUri();
                }
            }
            if (senderName.isEmpty()) {
                CharSequence legacySender = message.getCharSequence("sender");
                senderName = legacySender != null ? legacySender.toString().trim() : "";
            }
            return new MessagingMessage(text.toString(), senderName, senderKey, senderUri,
                    message.getLong("time", 0L), senderPresent);
        }
        return null;
    }

    /** MessagingStyle's local user identity, used only for positive outgoing-message matches. */
    @Nullable
    public static MessagingMessage readMessagingUser(@NonNull Notification n) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || n.extras == null) return null;
        Parcelable raw = n.extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON);
        if (!(raw instanceof Person)) return null;
        Person person = (Person) raw;
        CharSequence name = person.getName();
        return new MessagingMessage("", name != null ? name.toString().trim() : "",
                person.getKey(), person.getUri(), 0L, true);
    }

    /**
     * Message body suitable for prompting the model (sender name often comes from title, not here).
     */
    @Nullable
    public static CharSequence readMessageBody(@NonNull Notification n) {
        Bundle extras = n.extras;
        if (extras == null) return null;

        CharSequence messaging = readMessagingStyleLatestText(extras);
        if (!TextUtils.isEmpty(messaging)) return messaging;

        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
        if (!TextUtils.isEmpty(text)) return text;

        CharSequence big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (!TextUtils.isEmpty(big)) return big;

        CharSequence lines = joinTextLines(extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES));
        if (!TextUtils.isEmpty(lines)) return lines;

        CharSequence sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
        if (!TextUtils.isEmpty(sub)) return sub;

        CharSequence summary = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT);
        if (!TextUtils.isEmpty(summary)) return summary;

        text = extras.getCharSequence("android.text");
        if (!TextUtils.isEmpty(text)) return text;

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "No message body found. Extra keys: " + extras.keySet());
        }
        return null;
    }

    @NonNull
    private static List<Parcelable> extractMessagesList(@NonNull Bundle extras) {
        List<Parcelable> combined = new ArrayList<>();
        Object raw = extras.get(Notification.EXTRA_MESSAGES);
        if (raw instanceof Parcelable[]) {
            for (Parcelable p : (Parcelable[]) raw) {
                combined.add(p);
            }
        } else if (raw instanceof ArrayList) {
            @SuppressWarnings("unchecked")
            ArrayList<Parcelable> list = (ArrayList<Parcelable>) raw;
            combined.addAll(list);
        }
        return combined;
    }

    /**
     * Latest text from MessagingStyle bundles ({@link Notification#EXTRA_MESSAGES}).
     * WhatsApp / some OEMs store messages as {@code Parcelable[]} only; calling
     * {@link Bundle#getParcelableArrayList} in that case throws internally and breaks reads.
     */
    @Nullable
    private static CharSequence readMessagingStyleLatestText(@NonNull Bundle extras) {
        List<Parcelable> combined = extractMessagesList(extras);
        if (combined.isEmpty()) return null;

        for (int i = combined.size() - 1; i >= 0; i--) {
            Parcelable p = combined.get(i);
            if (!(p instanceof Bundle)) continue;
            Bundle msg = (Bundle) p;
            CharSequence text = msg.getCharSequence("text");
            if (!TextUtils.isEmpty(text)) return text;
        }
        return null;
    }

    @Nullable
    private static CharSequence joinTextLines(@Nullable CharSequence[] lines) {
        if (lines == null || lines.length == 0) return null;
        StringBuilder sb = new StringBuilder();
        for (CharSequence line : lines) {
            if (!TextUtils.isEmpty(line)) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
        }
        return sb.length() > 0 ? sb : null;
    }
}
