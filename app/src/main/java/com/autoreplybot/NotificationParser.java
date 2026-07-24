package com.autoreplybot;

import android.app.Notification;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Parses WhatsApp variants precisely while treating enabled third-party apps conservatively.
 */
public final class NotificationParser {
    private NotificationParser() {}

    @NonNull
    public static ParsedNotification parse(@NonNull StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        String pkg = nonNull(sbn.getPackageName());
        boolean whatsapp = AppConstants.PKG_WHATSAPP.equals(pkg)
                || AppConstants.PKG_WHATSAPP_BUSINESS.equals(pkg);
        if (n == null) {
            return empty(pkg, sbn.getPostTime(), whatsapp);
        }

        Bundle extras = n.extras;
        boolean summary = (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
        boolean replyAvailable = NotificationReplyHelper.hasReplyAction(n);
        String title = chars(NotificationContentExtractor.readConversationTitle(n));
        String conversationTitle = extras != null
                ? chars(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)) : "";
        NotificationContentExtractor.MessagingMessage latest =
                NotificationContentExtractor.readLatestMessagingMessage(n);
        NotificationContentExtractor.MessagingMessage user =
                NotificationContentExtractor.readMessagingUser(n);

        boolean explicitGroup = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && extras != null) {
            explicitGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false);
        }
        boolean group = explicitGroup || (whatsapp && !conversationTitle.isEmpty()
                && latest != null && !latest.senderName.isEmpty()
                && !sameIdentity(conversationTitle, latest.senderName));
        String groupName = group ? (!conversationTitle.isEmpty() ? conversationTitle : title) : null;

        String sender = latest != null ? latest.senderName : "";
        if (sender.isEmpty() && !group) sender = title;
        String body = latest != null ? latest.text : chars(NotificationContentExtractor.readMessageBody(n));
        if (group && sender.isEmpty()) {
            int separator = body.indexOf(':');
            if (separator > 0 && separator < 80) {
                sender = body.substring(0, separator).trim();
                body = body.substring(separator + 1).trim();
            }
        }

        ParsedNotification.Direction direction = determineDirection(latest, user);
        long timestamp = latest != null && latest.timestamp > 0
                ? latest.timestamp : (sbn.getPostTime() > 0 ? sbn.getPostTime() : System.currentTimeMillis());
        String identity = stableIdentity(n, sbn, latest, groupName, sender, title);

        String normalized = normalizeLabel(body);
        boolean deleted = whatsapp && isDeleted(normalized);
        boolean typing = whatsapp && isTypingOrPresence(normalized);
        boolean mediaOnly = TextUtils.isEmpty(body.trim()) || (whatsapp && isMediaPlaceholder(normalized));
        boolean status = isStatusOrAppNotification(whatsapp, n, title, conversationTitle,
                normalized, latest);
        String category = nonNull(n.category);
        String channelId = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? nonNull(n.getChannelId()) : "";
        String metadata = normalizeLabel(title + " " + conversationTitle + " " + channelId);
        boolean broadcastOrChannel = Notification.CATEGORY_PROMO.equals(category)
                || metadata.contains("broadcast") || metadata.contains("channel")
                || metadata.contains("community") || metadata.contains("catalog")
                || metadata.contains("newsletter") || metadata.contains("marketing");
        boolean verifiedBusiness = extras != null
                && (extras.getBoolean("verified_business", false)
                || extras.getBoolean("is_verified_business", false)
                || extras.getBoolean("com.whatsapp.verified_business", false));

        return new ParsedNotification(pkg, identity, sender, title, body.trim(), groupName,
                timestamp, direction, whatsapp, group, summary, mediaOnly, deleted, typing,
                status, replyAvailable, category, channelId, broadcastOrChannel,
                verifiedBusiness);
    }

    @NonNull
    private static ParsedNotification empty(@NonNull String pkg, long timestamp, boolean whatsapp) {
        return new ParsedNotification(pkg, pkg + "|unknown", "", "", "", null, timestamp,
                ParsedNotification.Direction.UNKNOWN, whatsapp, false, false, true,
                false, false, true, false);
    }

    @NonNull
    private static String stableIdentity(@NonNull Notification n,
                                         @NonNull StatusBarNotification sbn,
                                         NotificationContentExtractor.MessagingMessage latest,
                                         String groupName, @NonNull String sender,
                                         @NonNull String title) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !TextUtils.isEmpty(n.getShortcutId())) {
            return "shortcut:" + n.getShortcutId();
        }
        // A conversation label stays stable when MessagingStyle's latest sender changes to the
        // local user after an inline reply. This is especially important for restart-safe loops.
        String conversationLabel = !TextUtils.isEmpty(groupName) ? groupName : title;
        if (!TextUtils.isEmpty(conversationLabel)) {
            return "label:" + normalizeIdentity(conversationLabel);
        }
        if (latest != null) {
            if (!TextUtils.isEmpty(latest.senderUri)) return "uri:" + latest.senderUri;
            if (!TextUtils.isEmpty(latest.senderKey)) return "person:" + latest.senderKey;
        }
        String label = sender;
        if (!label.isEmpty()) return "label:" + normalizeIdentity(label);
        String groupKey = sbn.getGroupKey();
        return !TextUtils.isEmpty(groupKey) ? "group:" + groupKey : "key:" + sbn.getKey();
    }

    @NonNull
    private static ParsedNotification.Direction determineDirection(
            NotificationContentExtractor.MessagingMessage latest,
            NotificationContentExtractor.MessagingMessage user) {
        if (latest == null || user == null) return ParsedNotification.Direction.UNKNOWN;
        if (latest.senderMetadataPresent && latest.senderName.isEmpty()
                && TextUtils.isEmpty(latest.senderKey) && TextUtils.isEmpty(latest.senderUri)) {
            return ParsedNotification.Direction.OUTGOING;
        }
        if (sameNonEmpty(latest.senderKey, user.senderKey)
                || sameNonEmpty(latest.senderUri, user.senderUri)
                || (!latest.senderName.isEmpty() && sameIdentity(latest.senderName, user.senderName))) {
            return ParsedNotification.Direction.OUTGOING;
        }
        return latest.senderMetadataPresent
                ? ParsedNotification.Direction.INCOMING : ParsedNotification.Direction.UNKNOWN;
    }

    private static boolean isStatusOrAppNotification(boolean whatsapp, @NonNull Notification n,
                                                     @NonNull String title,
                                                     @NonNull String conversationTitle,
                                                     @NonNull String body,
                                                     NotificationContentExtractor.MessagingMessage latest) {
        if (whatsapp) {
            String t = normalizeLabel(title);
            String c = normalizeLabel(conversationTitle);
            return "status".equals(t) || "status".equals(c)
                    || "whatsapp".equals(t)
                    || body.contains("status update") || body.contains("status updates")
                    || body.equals("checking for new messages");
        }
        // Do not guess based on arbitrary text from user-enabled apps. Positive non-message
        // category evidence is required when MessagingStyle metadata is absent.
        return latest == null && n.category != null
                && !Notification.CATEGORY_MESSAGE.equals(n.category);
    }

    private static boolean isDeleted(@NonNull String text) {
        return text.equals("this message was deleted")
                || text.equals("you deleted this message")
                || text.equals("message deleted");
    }

    private static boolean isTypingOrPresence(@NonNull String text) {
        return text.equals("typing") || text.equals("typing...")
                || text.equals("recording audio") || text.equals("online")
                || text.endsWith(" is typing") || text.endsWith(" is typing...")
                || text.endsWith(" is recording audio");
    }

    private static boolean isMediaPlaceholder(@NonNull String text) {
        return text.equals("photo") || text.equals("image") || text.equals("video")
                || text.equals("audio") || text.equals("voice message") || text.equals("sticker")
                || text.equals("gif") || text.equals("document") || text.equals("contact")
                || text.equals("location") || text.equals("live location")
                || text.equals("media") || text.equals("photo unavailable");
    }

    private static boolean sameNonEmpty(String a, String b) {
        return !TextUtils.isEmpty(a) && !TextUtils.isEmpty(b) && a.equals(b);
    }

    private static boolean sameIdentity(String a, String b) {
        return !TextUtils.isEmpty(a) && !TextUtils.isEmpty(b)
                && normalizeIdentity(a).equals(normalizeIdentity(b));
    }

    @NonNull
    private static String normalizeIdentity(@NonNull String value) {
        return NotificationContentExtractor.normalizeText(
                Normalizer.normalize(value, Normalizer.Form.NFKC)).toLowerCase(Locale.ROOT);
    }

    @NonNull
    private static String normalizeLabel(@NonNull String value) {
        String normalized = normalizeIdentity(value);
        while (!normalized.isEmpty()) {
            int cp = normalized.codePointAt(0);
            if (Character.isLetterOrDigit(cp)) break;
            normalized = normalized.substring(Character.charCount(cp)).trim();
        }
        return normalized;
    }

    @NonNull
    private static String chars(CharSequence value) {
        return value != null ? value.toString().trim() : "";
    }

    @NonNull
    private static String nonNull(String value) {
        return value != null ? value : "";
    }
}
