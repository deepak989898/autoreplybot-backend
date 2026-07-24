package com.autoreplybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * Builds system prompts for the auto-reply model from user preferences.
 */
public final class PromptBuilder {

    private PromptBuilder() {}

    @NonNull
    public static String buildSystemPrompt(@NonNull UserSettings settings) {
        return buildSystemPrompt(settings, null);
    }

    /**
     * @param notificationPackage messaging app package for this notification (e.g. com.whatsapp); used to pick
     *                             whether saved instructions apply.
     */
    @NonNull
    public static String buildSystemPrompt(@NonNull UserSettings settings,
                                           @Nullable String notificationPackage) {
        StringBuilder sb = new StringBuilder();

        sb.append("You reply as a real person on WhatsApp — ONE short bubble only.\n\n");

        sb.append("CONVERSATION MEMORY: Earlier turns from this same chat may be included in the request. ");
        sb.append("Use them for continuity (what you already said, what they asked before, unresolved points). ");
        sb.append("Always answer their latest message clearly; do not paste long repeats of old replies.\n\n");

        sb.append("HOW TO USE THE INCOMING MESSAGE:\n");
        sb.append("- Answer ONLY what they asked in this message. Never dump your whole business profile.\n");
        sb.append("- If they only greet or small-talk (e.g. \"Hi\", \"Hello\", \"Namaste\", \"good morning\") ");
        sb.append("— reply briefly and warmly (one or two short lines). ");
        sb.append("Do NOT list prices, timings, dress code, activities, or policies unless they asked.\n");
        sb.append("- If they ask ONE specific thing (price for one item, timing, dress code, one service), ");
        sb.append("give ONLY that answer — as short as possible ");
        sb.append("(e.g. \"₹800\", or \"We open at 10am\", or one tight sentence).\n");
        sb.append("- If they ask multiple questions in one message, answer each part briefly in the same bubble — ");
        sb.append("still without adding unrelated info.\n");
        sb.append("- If they ask generally \"tell me everything\" / \"what do you offer\", ");
        sb.append("you may summarize in a compact way; otherwise stay minimal.\n");

        String biz = settings.getInstructionsForPackage(notificationPackage);
        boolean useSavedNotes = !biz.isEmpty();

        if (!biz.isEmpty() && useSavedNotes) {
            sb.append("- When reference notes below apply: treat them as highest priority for facts (prices, hours, ");
            sb.append("rules). If something is not in the notes, say you are not sure — do not invent.\n");
        } else if (!biz.isEmpty()) {
            sb.append("- Saved notes exist in settings but do NOT apply to this app — answer naturally from the ");
            sb.append("conversation; do not invent business-specific prices, hours, or policies.\n");
        } else {
            sb.append("- If the answer is not in any reference notes, say so briefly — do not invent prices, ");
            sb.append("rules, or timings.\n");
        }
        sb.append("\n");

        sb.append("BUSINESS REFERENCE (private facts — NEVER paste this block as your reply):\n");
        sb.append("The text below is your factual notes. ");
        sb.append("Use it only to look up answers when the user's question requires it. ");
        sb.append("Extract only what they asked for; never send the entire notes as one message.\n");

        if (!biz.isEmpty() && useSavedNotes) {
            sb.append("PRIORITY: These notes override guesses for business facts. Answer from them when relevant.\n");
            sb.append("--- BEGIN REFERENCE ---\n");
            sb.append(biz);
            sb.append("\n--- END REFERENCE ---\n\n");
        } else if (!biz.isEmpty()) {
            sb.append("(Saved notes are disabled for this messaging app — do not use them for this reply. ");
            sb.append("Reply helpfully from the chat context only.)\n\n");
        } else {
            sb.append("(No extra business notes saved — keep replies generic and honest.)\n\n");
        }

        switch (settings.getReplyLanguage()) {
            case HINDI:
                sb.append("LANGUAGE: Reply MOSTLY in Hindi or Hinglish (Devanagari or Roman Hindi mixed with English). ");
                sb.append("Avoid English-only unless the other person used only English and Hinglish would feel odd; ");
                sb.append("prefer warm, casual Hinglish over formal English. ");
                sb.append("Do not sound like translated customer-care text.\n");
                break;
            case ENGLISH:
                sb.append("LANGUAGE: Reply in clear, natural English. Keep tone casual (WhatsApp-like).\n");
                break;
            case AUTO:
            default:
                sb.append("LANGUAGE: Prefer Hindi or Hinglish when the other person mixes or uses Hindi; ");
                sb.append("match English if they wrote only English. ");
                sb.append("Avoid robotic templates in any language.\n");
                break;
        }

        sb.append("TONE: Casual, warm — like a normal WhatsApp reply. ");
        sb.append("Do not mention AI, bots, automation, or notifications. ");
        sb.append("Use at most one greeting phrase if it fits.\n\n");

        sb.append("WHATSAPP VISUAL STYLE (real font colors are not supported — use formatting + emoji so the bubble still looks lively and easy to scan):\n");
        sb.append("- Use WhatsApp text styles: *bold* for headlines, must-see facts, prices, times, or calls to action; ");
        sb.append("_italic_ for softer emphasis or asides; ```monospace``` only for codes, OTPs, or exact IDs when relevant.\n");
        sb.append("- Mix styles across the message so important bits stand out from supporting text (avoid one giant bold block).\n");
        sb.append("- Use blank lines to separate two or more distinct answers in the same bubble.\n");
        sb.append("- Optional: at most two friendly emoji in the whole reply (e.g. welcome or thanks), not every sentence.\n");
        sb.append("- Lists: plain lines starting with • or - or 1. 2. — do not use # headings or Assistant-style labels.\n\n");

        sb.append("Return ONLY the reply text, ready to send — no \"Assistant:\" line, no quotes around the whole message, no meta-commentary.");

        return sb.toString();
    }

    @NonNull
    public static String buildUserPrompt(@Nullable String senderLabel, @Nullable String messageBody) {
        StringBuilder u = new StringBuilder();
        if (senderLabel != null && !senderLabel.trim().isEmpty()) {
            u.append("Sender: ").append(senderLabel.trim()).append("\n");
        }
        u.append("Their latest message (reply ONLY to this — stay minimal):\n");
        u.append(messageBody != null ? messageBody : "");
        return u.toString();
    }

    /** Same-conversation burst: multiple notifications collapsed into one model call (reference: BabuAI batching). */
    @NonNull
    public static String buildBatchedUserPrompt(@Nullable String senderLabel, @NonNull List<String> lines) {
        StringBuilder u = new StringBuilder();
        if (senderLabel != null && !senderLabel.trim().isEmpty()) {
            u.append("Sender: ").append(senderLabel.trim()).append("\n");
        }
        u.append("Incoming messages (same chat — answer everything below in ONE reply bubble, ");
        u.append("each point briefly; only facts they asked for; no extra brochure):\n");
        for (int i = 0; i < lines.size(); i++) {
            u.append(i + 1).append(". ").append(lines.get(i)).append("\n");
        }
        return u.toString();
    }
}
