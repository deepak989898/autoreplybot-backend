import { bucket } from "./firebase.js";
import { FEATURE_LABELS } from "./feature-entitlements.js";
import { generateSupportAgentReply } from "./openai.js";
import { listMessages, sendMessage, signedReadUrl, getThread } from "./support-chat.js";

export const SUPPORT_AI_SENDER_UID = "support-ai-agent";
export const SUPPORT_AI_SENDER_EMAIL = "support@autoreplybot";
export const SUPPORT_AI_DISPLAY_NAME = "AutoReplyBot Support";

const HUMAN_ADMIN_COOLDOWN_MS = 12 * 60 * 1000;
const DEBOUNCE_MS = 350;

function supportAiEnabled() {
  if (process.env.SUPPORT_AI_ENABLED === "0" || process.env.SUPPORT_AI_ENABLED === "false") {
    return false;
  }
  return Boolean(String(process.env.OPENAI_API_KEY || "").trim());
}

function buildFeaturesList() {
  return Object.entries(FEATURE_LABELS)
    .map(([key, label]) => `- ${label}`)
    .join("\n");
}

function buildSystemPrompt() {
  return `You are the official sales & support assistant for AutoReplyBot — an Android app + website that lets users remotely manage their phone (camera, location, gallery, SMS, call logs, screen mirror, and more) from a paired browser.

YOUR GOALS:
1) Help users understand the product and pricing.
2) Guide them to purchase and send payment proof.
3) Analyze payment screenshots when sent.
4) Troubleshoot website/app issues clearly.
5) Be warm, trustworthy, and concise — like a helpful Indian support agent.

PRICING (INR only — never invent other prices):
- 1 week full access: ₹700
- 1 month full access: ₹2,000
Payment is verified manually by our team after the user sends a UPI/bank success screenshot.

WEBSITE FEATURES (enabled per account after payment):
${buildFeaturesList()}

HOW IT WORKS (short):
- Install AutoReplyBot on the Android phone and sign in.
- On the website (/device/), pair the browser once (QR / code).
- Grant phone permissions when asked (camera, location, SMS, etc.).
- Admin enables features on the account after payment — until then tabs show "contact support".

PAYMENT SCREENSHOT REVIEW:
When the user sends an image, treat it as possible payment proof. Look for:
- UPI / GPay / PhonePe / Paytm / bank app "Success" or "Completed"
- Amount matching ₹700 (1 week) or ₹2,000 (1 month)
- Date/time and transaction reference if visible
If payment looks successful and amount matches a plan:
- Confirm which plan (1 week / 1 month)
- Say our team will activate all website features within 1–2 hours (or within 24 hours if outside business hours)
- Ask them to share the Gmail used on the app if not already known
If screenshot is unclear, failed, or wrong amount — politely explain what is missing and ask to resend.
Never claim you personally activated the account; say the team verifies and enables access.

LANGUAGE (critical):
- Reply in the SAME language style as the user's latest messages.
- English → English. Hindi → Hindi (Devanagari). Hinglish → natural Hinglish mix.
- If user mixes Hindi and English, reply in Hinglish.
- Keep messages readable on mobile (short paragraphs, bullet points when helpful).

BOUNDARIES:
- Do not make up features or prices not listed here.
- Do not share internal admin URLs or API keys.
- For abusive or off-topic spam, stay professional and redirect to support topics.
- If user insists on a human, say a team member will review the chat and follow up.

You reply as "${SUPPORT_AI_DISPLAY_NAME}" — sign off naturally when helpful, not every message.`;
}

async function imageAttachmentToDataUrl(attachment) {
  const path = String(attachment?.storagePath || "");
  if (!path || attachment?.type !== "image") return null;
  try {
    const [buf] = await bucket().file(path).download();
    const ct = String(attachment.contentType || "image/jpeg").toLowerCase();
    const mime = ct.startsWith("image/") ? ct : "image/jpeg";
    return `data:${mime};base64,${buf.toString("base64")}`;
  } catch {
    try {
      const url = await signedReadUrl(path);
      const res = await fetch(url);
      if (!res.ok) return null;
      const ab = await res.arrayBuffer();
      const ct = String(attachment.contentType || res.headers.get("content-type") || "image/jpeg");
      return `data:${ct.split(";")[0]};base64,${Buffer.from(ab).toString("base64")}`;
    } catch {
      return null;
    }
  }
}

function humanAdminRecentlyActive(messages) {
  const cutoff = Date.now() - HUMAN_ADMIN_COOLDOWN_MS;
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    if (m.senderRole !== "admin") continue;
    if (m.senderUid === SUPPORT_AI_SENDER_UID) continue;
    if (Number(m.createdAt || 0) >= cutoff) return true;
    break;
  }
  return false;
}

function toOpenAiHistory(messages) {
  const out = [];
  for (const m of messages) {
    const text = String(m.text || "").trim();
    const hasImage = (m.attachments || []).some((a) => a.type === "image");
    if (!text && !hasImage) continue;
    const role = m.senderRole === "user" ? "user" : "assistant";
    if (hasImage && m.senderRole === "user") {
      const parts = [];
      if (text) parts.push({ type: "text", text });
      else parts.push({ type: "text", text: "[User sent an image]" });
      out.push({ role, content: parts });
    } else if (text) {
      out.push({ role, content: text });
    }
  }
  return out.slice(-24);
}

function normalizeOpenAiHistory(history) {
  const out = [];
  for (const m of history || []) {
    const role = m?.role;
    const content = m?.content;
    if (!role || content == null) continue;
    const last = out[out.length - 1];
    if (
      last &&
      last.role === role &&
      typeof last.content === "string" &&
      typeof content === "string"
    ) {
      last.content = `${last.content}\n\n${content}`.trim();
      continue;
    }
    out.push({ role, content });
  }
  return out;
}

/**
 * Auto-reply to a user support message using OpenAI (text + payment screenshot vision).
 * @param {string} uid
 * @param {string} userMessageId
 */
export async function maybeAutoReplySupport(uid, userMessageId) {
  if (!supportAiEnabled()) return null;

  await new Promise((r) => setTimeout(r, DEBOUNCE_MS));

  const messages = await listMessages(uid, { limit: 40 });
  const last = messages[messages.length - 1];
  if (!last || last.messageId !== userMessageId || last.senderRole !== "user") {
    return null;
  }
  if (humanAdminRecentlyActive(messages)) {
    return null;
  }

  const thread = await getThread(uid);
  if (thread && thread.aiAgentEnabled === false) {
    return null;
  }

  const apiKey = String(process.env.OPENAI_API_KEY || "").trim();
  const history = normalizeOpenAiHistory(toOpenAiHistory(messages.slice(0, -1)));

  const userParts = [];
  const userText = String(last.text || "").trim();
  if (userText) userParts.push({ type: "text", text: userText });

  const imageAtt = (last.attachments || []).find((a) => a.type === "image");
  if (imageAtt) {
    const dataUrl = await imageAttachmentToDataUrl(imageAtt);
    if (dataUrl) {
      userParts.push({ type: "image_url", image_url: { url: dataUrl, detail: "high" } });
    } else if (!userText) {
      userParts.push({
        type: "text",
        text: "[User sent an image but it could not be loaded — ask them to resend the payment screenshot]",
      });
    }
  }

  if (!userParts.length) {
    userParts.push({ type: "text", text: "Hello" });
  }

  let replyText = "";
  try {
    replyText = await generateSupportAgentReply(apiKey, {
      system: buildSystemPrompt(),
      history,
      userParts,
    });
  } catch (e) {
    console.warn("support AI reply failed", uid, e?.message || e);
    replyText =
      "Thanks for your message 🙏 Our support team will review this shortly. " +
      "For pricing: ₹700/week or ₹2,000/month. Send your payment screenshot here after paying.";
  }

  if (!replyText.trim()) return null;

  const again = await listMessages(uid, { limit: 5 });
  const lastAgain = again[again.length - 1];
  if (!lastAgain || lastAgain.messageId !== userMessageId || lastAgain.senderRole !== "user") {
    return null;
  }
  if (humanAdminRecentlyActive(again)) return null;

  return sendMessage({
    uid,
    senderRole: "admin",
    senderUid: SUPPORT_AI_SENDER_UID,
    senderEmail: SUPPORT_AI_SENDER_EMAIL,
    senderDisplayName: SUPPORT_AI_DISPLAY_NAME,
    isAiAgent: true,
    text: replyText.trim(),
  });
}

function buildAdminDraftPrompt() {
  return `${buildSystemPrompt()}

MODE: ADMIN DRAFT ASSISTANT
You are helping a human platform administrator write the next reply in this support chat.
Write ONLY the message text the admin should send to the user — no labels, no "Here is a draft", no quotes around the whole message.
Match the user's language (English, Hindi, or Hinglish).
If payment proof was sent, state clearly whether it looks valid and what happens next.
Keep it concise and professional.`;
}

/**
 * Generate a suggested admin reply (does not send to the user).
 * @param {string} uid
 */
export async function generateSupportReplySuggestion(uid) {
  if (!supportAiEnabled()) {
    const err = new Error("AI support is not configured");
    err.code = "AI_NOT_CONFIGURED";
    throw err;
  }
  const id = String(uid || "").trim();
  if (!id) {
    const err = new Error("uid required");
    err.code = "BAD_REQUEST";
    throw err;
  }

  const messages = await listMessages(id, { limit: 40 });
  if (!messages.length) {
    const err = new Error("No messages in this chat yet");
    err.code = "BAD_REQUEST";
    throw err;
  }

  const apiKey = String(process.env.OPENAI_API_KEY || "").trim();
  const history = normalizeOpenAiHistory(toOpenAiHistory(messages));

  const userParts = [
    {
      type: "text",
      text:
        "Draft the best reply for the admin to send to this user right now, based on the conversation above.",
    },
  ];

  const lastUserMsg = [...messages].reverse().find((m) => m.senderRole === "user");
  if (lastUserMsg) {
    const imageAtt = (lastUserMsg.attachments || []).find((a) => a.type === "image");
    if (imageAtt) {
      const dataUrl = await imageAttachmentToDataUrl(imageAtt);
      if (dataUrl) {
        userParts.push({ type: "image_url", image_url: { url: dataUrl, detail: "high" } });
      }
    }
  }

  const suggestion = await generateSupportAgentReply(apiKey, {
    system: buildAdminDraftPrompt(),
    history,
    userParts,
  });
  return String(suggestion || "").trim();
}

export function isSupportAiConfigured() {
  return supportAiEnabled();
}
