package com.autoreplybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Immutable, app-neutral view of a notification that may represent a chat message.
 */
public final class ParsedNotification {
    public enum Direction { INCOMING, OUTGOING, UNKNOWN }

    @NonNull private final String packageName;
    @NonNull private final String stableContactIdentity;
    @NonNull private final String senderName;
    @NonNull private final String conversationTitle;
    @NonNull private final String messageText;
    @Nullable private final String groupName;
    private final long timestamp;
    private final Direction direction;
    private final boolean whatsapp;
    private final boolean groupConversation;
    private final boolean groupSummary;
    private final boolean mediaOnly;
    private final boolean deletedMessage;
    private final boolean typingOrPresence;
    private final boolean statusOrAppNotification;
    private final boolean replyActionAvailable;
    @NonNull private final String notificationCategory;
    @NonNull private final String channelId;
    private final boolean broadcastOrChannel;
    private final boolean verifiedBusiness;

    ParsedNotification(@NonNull String packageName,
                       @NonNull String stableContactIdentity,
                       @NonNull String senderName,
                       @NonNull String conversationTitle,
                       @NonNull String messageText,
                       @Nullable String groupName,
                       long timestamp,
                       @NonNull Direction direction,
                       boolean whatsapp,
                       boolean groupConversation,
                       boolean groupSummary,
                       boolean mediaOnly,
                       boolean deletedMessage,
                       boolean typingOrPresence,
                       boolean statusOrAppNotification,
                       boolean replyActionAvailable) {
        this(packageName, stableContactIdentity, senderName, conversationTitle, messageText,
                groupName, timestamp, direction, whatsapp, groupConversation, groupSummary,
                mediaOnly, deletedMessage, typingOrPresence, statusOrAppNotification,
                replyActionAvailable, "", "", false, false);
    }

    ParsedNotification(@NonNull String packageName,
                       @NonNull String stableContactIdentity,
                       @NonNull String senderName,
                       @NonNull String conversationTitle,
                       @NonNull String messageText,
                       @Nullable String groupName,
                       long timestamp,
                       @NonNull Direction direction,
                       boolean whatsapp,
                       boolean groupConversation,
                       boolean groupSummary,
                       boolean mediaOnly,
                       boolean deletedMessage,
                       boolean typingOrPresence,
                       boolean statusOrAppNotification,
                       boolean replyActionAvailable,
                       @NonNull String notificationCategory,
                       @NonNull String channelId,
                       boolean broadcastOrChannel,
                       boolean verifiedBusiness) {
        this.packageName = packageName;
        this.stableContactIdentity = stableContactIdentity;
        this.senderName = senderName;
        this.conversationTitle = conversationTitle;
        this.messageText = messageText;
        this.groupName = groupName;
        this.timestamp = timestamp;
        this.direction = direction;
        this.whatsapp = whatsapp;
        this.groupConversation = groupConversation;
        this.groupSummary = groupSummary;
        this.mediaOnly = mediaOnly;
        this.deletedMessage = deletedMessage;
        this.typingOrPresence = typingOrPresence;
        this.statusOrAppNotification = statusOrAppNotification;
        this.replyActionAvailable = replyActionAvailable;
        this.notificationCategory = notificationCategory;
        this.channelId = channelId;
        this.broadcastOrChannel = broadcastOrChannel;
        this.verifiedBusiness = verifiedBusiness;
    }

    @NonNull public String getPackageName() { return packageName; }
    @NonNull public String getStableContactIdentity() { return stableContactIdentity; }
    @NonNull public String getSenderName() { return senderName; }
    @NonNull public String getConversationTitle() { return conversationTitle; }
    @NonNull public String getMessageText() { return messageText; }
    @Nullable public String getGroupName() { return groupName; }
    public long getTimestamp() { return timestamp; }
    @NonNull public Direction getDirection() { return direction; }
    public boolean isOutgoing() { return direction == Direction.OUTGOING; }
    public boolean isWhatsapp() { return whatsapp; }
    public boolean isGroupConversation() { return groupConversation; }
    public boolean isGroupSummary() { return groupSummary; }
    public boolean isMediaOnly() { return mediaOnly; }
    public boolean isDeletedMessage() { return deletedMessage; }
    public boolean isTypingOrPresence() { return typingOrPresence; }
    public boolean isStatusOrAppNotification() { return statusOrAppNotification; }
    public boolean isReplyActionAvailable() { return replyActionAvailable; }
    @NonNull public String getNotificationCategory() { return notificationCategory; }
    @NonNull public String getChannelId() { return channelId; }
    public boolean isBroadcastOrChannel() { return broadcastOrChannel; }
    public boolean isVerifiedBusiness() { return verifiedBusiness; }

    public boolean hasUsableIncomingText() {
        return hasMeaningfulText(messageText)
                && !isOutgoing()
                && !groupSummary
                && !mediaOnly
                && !deletedMessage
                && !typingOrPresence
                && !statusOrAppNotification
                && replyActionAvailable;
    }

    @NonNull
    ReplyAction eligibilityAction(@NonNull UserSettings settings) {
        return hasUsableIncomingText()
                && (!groupConversation || settings.isGroupAutoReplyEnabled())
                ? ReplyAction.SEND_REPLY : ReplyAction.NO_REPLY;
    }

    static boolean hasMeaningfulText(@NonNull String text) {
        for (int i = 0; i < text.length();) {
            int codePoint = text.codePointAt(i);
            if (Character.isLetterOrDigit(codePoint)) return true;
            i += Character.charCount(codePoint);
        }
        return false;
    }
}
