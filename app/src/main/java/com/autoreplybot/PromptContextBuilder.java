package com.autoreplybot;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds a bounded, same-contact prompt context with explicit trust boundaries. */
public final class PromptContextBuilder {
    private static final int MAX_FIELD_CHARS = 3000;

    @NonNull
    public String buildClassifierContext(@NonNull String latestIncoming,
                                         boolean groupConversation) {
        return "<TRUSTED_POLICY name=\"classification_scope\">"
                + "Classify only the message payload. groupConversation=" + groupConversation
                + ".</TRUSTED_POLICY>\n<UNTRUSTED_MESSAGE>"
                + untrusted(latestIncoming) + "</UNTRUSTED_MESSAGE>";
    }

    @NonNull
    public String buildGeneratorContext(@NonNull ContactProfile profile,
                                        @NonNull ConversationState state,
                                        @NonNull List<ConversationMessage> sameContactMessages,
                                        @NonNull List<ReplyEvent> sameContactReplyHistory,
                                        @NonNull MessageClassificationResult classification,
                                        @NonNull UserSettings settings,
                                        @NonNull String sourcePackage,
                                        @NonNull String latestIncoming) {
        int recentLimit = Math.max(8, Math.min(12, settings.getMaxRecentMessages()));
        StringBuilder out = new StringBuilder();
        out.append("<TRUSTED_POLICY name=\"decision_context\">\n")
                .append("detectedIntent=").append(classification.intent.name()).append('\n')
                .append("classifierConfidence=").append(classification.confidence).append('\n')
                .append("language=").append(trusted(classification.language)).append('\n')
                .append("relationship=").append(profile.relationshipType.name()).append('\n')
                .append("replyMode=").append(profile.replyMode.name()).append('\n')
                .append("allowedContextClasses=SAME_CONTACT_CONVERSATION");

        boolean businessAllowed = classification.intent.name().startsWith("BUSINESS_")
                && classification.businessContextRequired && profile.allowBusinessContext;
        boolean personalAllowed = classification.personalContextRequired
                && profile.allowPersonalContext;
        if (businessAllowed) out.append(",PUBLIC_BUSINESS");
        if (personalAllowed) out.append(",CONTACT_SPECIFIC_PROFILE");
        out.append("\n</TRUSTED_POLICY>\n");

        if (!state.conversationSummary.trim().isEmpty()) {
            out.append("<TRUSTED_PROFILE name=\"same_contact_summary\">")
                    .append(trusted(limit(state.conversationSummary)))
                    .append("</TRUSTED_PROFILE>\n");
        }

        if ((businessAllowed || personalAllowed) && !profile.customNotes.trim().isEmpty()) {
            String safeNotes = allowedNotes(profile.customNotes);
            if (!safeNotes.isEmpty()) {
                out.append("<TRUSTED_PROFILE name=\"allowed_contact_notes\">")
                        .append(trusted(safeNotes)).append("</TRUSTED_PROFILE>\n");
            }
        }

        if (businessAllowed) {
            String business = allowedNotes(settings.getInstructionsForPackage(sourcePackage));
            if (!business.isEmpty()) {
                out.append("<TRUSTED_BUSINESS>")
                        .append(trusted(business)).append("</TRUSTED_BUSINESS>\n");
            }
        }

        List<ConversationMessage> bounded = tailForContact(
                sameContactMessages, profile.contactId, recentLimit);
        out.append("<UNTRUSTED_HISTORY>\n");
        for (ConversationMessage message : bounded) {
            out.append(message.direction == ConversationMessage.Direction.OUTGOING
                    ? "assistant: " : "contact: ");
            String text = message.direction == ConversationMessage.Direction.OUTGOING
                    && !message.replyText.isEmpty() ? message.replyText : message.messageText;
            out.append(untrusted(limit(text))).append('\n');
        }
        out.append("</UNTRUSTED_HISTORY>\n");

        List<String> recentBotReplies = lastBotReplies(
                sameContactMessages, sameContactReplyHistory, profile.contactId, 5);
        out.append("<UNTRUSTED_RECENT_BOT_REPLIES>\n");
        for (String reply : recentBotReplies) {
            out.append("- ").append(untrusted(limit(reply))).append('\n');
        }
        out.append("</UNTRUSTED_RECENT_BOT_REPLIES>\n")
                .append("<UNTRUSTED_MESSAGE>")
                .append(untrusted(limit(latestIncoming)))
                .append("</UNTRUSTED_MESSAGE>");
        return out.toString();
    }

    @NonNull
    public List<String> recentAssistantReplies(@NonNull String contactId,
                                               @NonNull List<ConversationMessage> messages,
                                               @NonNull List<ReplyEvent> events) {
        return lastBotReplies(messages, events, contactId, 5);
    }

    @NonNull
    private static List<ConversationMessage> tailForContact(
            @NonNull List<ConversationMessage> messages, @NonNull String contactId, int limit) {
        List<ConversationMessage> same = new ArrayList<>();
        for (ConversationMessage message : messages) {
            if (contactId.equals(message.contactId)) same.add(message);
        }
        return new ArrayList<>(same.subList(Math.max(0, same.size() - limit), same.size()));
    }

    @NonNull
    private static List<String> lastBotReplies(@NonNull List<ConversationMessage> messages,
                                               @NonNull List<ReplyEvent> events,
                                               @NonNull String contactId, int limit) {
        List<String> result = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0 && result.size() < limit; i--) {
            ConversationMessage message = messages.get(i);
            if (!contactId.equals(message.contactId)
                    || message.direction != ConversationMessage.Direction.OUTGOING) continue;
            String text = !message.replyText.isEmpty() ? message.replyText : message.messageText;
            addUnique(result, text);
        }
        for (ReplyEvent event : events) {
            if (result.size() >= limit) break;
            if (contactId.equals(event.contactId) && event.action == ReplyAction.SEND_REPLY) {
                addUnique(result, event.replyText);
            }
        }
        return result;
    }

    private static void addUnique(@NonNull List<String> values, String candidate) {
        if (candidate == null || candidate.trim().isEmpty()) return;
        String normalized = candidate.trim();
        for (String existing : values) {
            if (existing.equalsIgnoreCase(normalized)) return;
        }
        values.add(normalized);
    }

    @NonNull
    private static String allowedNotes(@NonNull String notes) {
        StringBuilder safe = new StringBuilder();
        for (String line : notes.split("\\r?\\n")) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("password") || lower.contains("passwd")
                    || lower.contains("api key") || lower.contains("apikey")
                    || lower.contains("secret") || lower.contains("credential")
                    || lower.contains("otp") || lower.contains("token=")
                    || lower.contains("bearer ")) continue;
            if (safe.length() > 0) safe.append('\n');
            safe.append(line);
            if (safe.length() >= MAX_FIELD_CHARS) break;
        }
        return limit(safe.toString()).trim();
    }

    @NonNull
    private static String limit(@NonNull String value) {
        return value.length() <= MAX_FIELD_CHARS ? value : value.substring(0, MAX_FIELD_CHARS);
    }

    @NonNull
    private static String trusted(@NonNull String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @NonNull
    private static String untrusted(@NonNull String value) {
        return trusted(value).replace("\"", "&quot;");
    }
}
