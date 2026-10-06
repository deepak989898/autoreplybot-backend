import { randomBytes } from "crypto";
import { bucket, db } from "./firebase.js";
import { COL_USERS } from "./remote-constants.js";
import { COL_LOAN } from "./loan-applications.js";

export const DOC_SUPPORT = "support";
export const COL_SUPPORT_MESSAGES = "messages";

const TEXT_MAX = 4000;
const IMAGE_MAX = 10 * 1024 * 1024;
export const LOAN_SUPPORT_UPLOAD_MAX = 3 * 1024 * 1024;
const IMAGE_TYPES = new Set(["image/jpeg", "image/png", "image/webp"]);
const READ_URL_TTL_MS = 60 * 60 * 1000;

function threadRef(uid) {
  return db().collection(COL_USERS).doc(String(uid)).collection(COL_LOAN).doc(DOC_SUPPORT);
}

function messagesCol(uid) {
  return threadRef(uid).collection(COL_SUPPORT_MESSAGES);
}

function roleOf(role) {
  return role === "web" ? "web" : "app";
}

function safeFileName(name) {
  const base = String(name || "image")
    .replace(/[^a-zA-Z0-9._-]+/g, "_")
    .slice(0, 120);
  return base || "image.jpg";
}

function sanitizeAttachment(a) {
  if (!a || typeof a !== "object") return null;
  const storagePath = String(a.storagePath || "");
  if (!storagePath.startsWith("loan_support/")) return null;
  return {
    type: "image",
    storagePath,
    contentType: String(a.contentType || "image/jpeg").slice(0, 120),
    sizeBytes: Number(a.sizeBytes || 0),
    fileName: String(a.fileName || "").slice(0, 200),
  };
}

export async function signedLoanSupportReadUrl(storagePath) {
  const path = String(storagePath || "");
  if (!path.startsWith("loan_support/")) {
    const err = new Error("Invalid storage path");
    err.code = "BAD_REQUEST";
    throw err;
  }
  const file = bucket().file(path);
  const [signed] = await file.getSignedUrl({
    action: "read",
    expires: Date.now() + READ_URL_TTL_MS,
  });
  return signed;
}

async function enrichAttachments(attachments) {
  const list = Array.isArray(attachments) ? attachments : [];
  const out = [];
  for (const raw of list) {
    const a = sanitizeAttachment(raw);
    if (!a) continue;
    try {
      out.push({ ...a, url: await signedLoanSupportReadUrl(a.storagePath) });
    } catch {
      out.push({ ...a, url: "" });
    }
  }
  return out;
}

export function sanitizeLoanSupportThread(id, data) {
  if (!data || typeof data !== "object") return null;
  return {
    userUid: data.userUid || id,
    lastMessageText: String(data.lastMessageText || ""),
    lastMessageAt: Number(data.lastMessageAt || 0),
    lastSenderRole: roleOf(data.lastSenderRole),
    unreadForApp: Number(data.unreadForApp || 0),
    unreadForWeb: Number(data.unreadForWeb || 0),
    createdAt: Number(data.createdAt || 0),
    updatedAt: Number(data.updatedAt || 0),
  };
}

async function sanitizeMessage(id, data) {
  return {
    messageId: data.messageId || id,
    senderRole: roleOf(data.senderRole),
    senderUid: String(data.senderUid || ""),
    text: String(data.text || ""),
    createdAt: Number(data.createdAt || 0),
    attachments: await enrichAttachments(data.attachments),
  };
}

export async function ensureLoanSupportThread(uid) {
  const id = String(uid || "").trim();
  if (!id) {
    const err = new Error("uid required");
    err.code = "BAD_REQUEST";
    throw err;
  }
  const ref = threadRef(id);
  const snap = await ref.get();
  const now = Date.now();
  if (!snap.exists) {
    const doc = {
      userUid: id,
      lastMessageText: "",
      lastMessageAt: 0,
      lastSenderRole: "",
      unreadForApp: 0,
      unreadForWeb: 0,
      createdAt: now,
      updatedAt: now,
    };
    await ref.set(doc);
    return sanitizeLoanSupportThread(id, doc);
  }
  return sanitizeLoanSupportThread(id, snap.data() || {});
}

export async function listLoanSupportMessages(uid, opts = {}) {
  await ensureLoanSupportThread(uid);
  const limit = Math.min(Math.max(Number(opts.limit) || 80, 1), 200);
  const after = Number(opts.after) || 0;
  let q = messagesCol(uid).orderBy("createdAt", "asc").limit(limit);
  if (after > 0) {
    q = messagesCol(uid).where("createdAt", ">", after).orderBy("createdAt", "asc").limit(limit);
  }
  const snap = await q.get();
  const messages = [];
  for (const doc of snap.docs) {
    messages.push(await sanitizeMessage(doc.id, doc.data() || {}));
  }
  return messages;
}

export async function sendLoanSupportMessage(input) {
  const uid = String(input.uid || "").trim();
  const senderRole = roleOf(input.senderRole);
  const text = String(input.text || "").trim().slice(0, TEXT_MAX);
  const attachments = (Array.isArray(input.attachments) ? input.attachments : [])
    .map(sanitizeAttachment)
    .filter(Boolean)
    .slice(0, 4);
  if (!uid) {
    const err = new Error("uid required");
    err.code = "BAD_REQUEST";
    throw err;
  }
  if (!text && attachments.length === 0) {
    const err = new Error("Message text or image required");
    err.code = "BAD_REQUEST";
    throw err;
  }
  await ensureLoanSupportThread(uid);
  const messageId = String(input.messageId || randomBytes(16).toString("hex"));
  const now = Date.now();
  const doc = {
    messageId,
    senderRole,
    senderUid: String(input.senderUid || uid).slice(0, 128),
    text,
    createdAt: now,
    attachments,
  };
  const preview = text
    ? text.slice(0, 200)
    : attachments.length
      ? "[Image]"
      : "";
  const tRef = threadRef(uid);
  const mRef = messagesCol(uid).doc(messageId);
  await db().runTransaction(async (tx) => {
    const tSnap = await tx.get(tRef);
    const prev = tSnap.exists ? tSnap.data() || {} : {};
    tx.set(mRef, doc);
    tx.set(
      tRef,
      {
        userUid: uid,
        lastMessageText: preview,
        lastMessageAt: now,
        lastSenderRole: senderRole,
        unreadForApp: senderRole === "web" ? Number(prev.unreadForApp || 0) + 1 : Number(prev.unreadForApp || 0),
        unreadForWeb: senderRole === "app" ? Number(prev.unreadForWeb || 0) + 1 : Number(prev.unreadForWeb || 0),
        updatedAt: now,
        ...(tSnap.exists ? {} : { createdAt: now }),
      },
      { merge: true }
    );
  });
  return sanitizeMessage(messageId, doc);
}

export async function markLoanSupportRead(uid, role) {
  const id = String(uid || "").trim();
  if (!id) return null;
  const ref = threadRef(id);
  const snap = await ref.get();
  if (!snap.exists) return null;
  const patch =
    roleOf(role) === "web"
      ? { unreadForWeb: 0, updatedAt: Date.now() }
      : { unreadForApp: 0, updatedAt: Date.now() };
  await ref.set(patch, { merge: true });
  return sanitizeLoanSupportThread(id, { ...(snap.data() || {}), ...patch });
}

export async function uploadLoanSupportImage(input) {
  const uid = String(input.uid || "").trim();
  const contentType = String(input.contentType || "image/jpeg").toLowerCase().trim();
  if (!uid || !IMAGE_TYPES.has(contentType)) {
    const err = new Error("Only jpeg, png, or webp images are allowed");
    err.code = "BAD_MEDIA_TYPE";
    throw err;
  }
  const raw = String(input.dataBase64 || "").replace(/^data:[^;]+;base64,/, "");
  if (!raw) {
    const err = new Error("Missing image data");
    err.code = "BAD_REQUEST";
    throw err;
  }
  let buffer;
  try {
    buffer = Buffer.from(raw, "base64");
  } catch {
    const err = new Error("Invalid image data");
    err.code = "BAD_REQUEST";
    throw err;
  }
  if (!buffer.length || buffer.length > LOAN_SUPPORT_UPLOAD_MAX) {
    const err = new Error("Image is too large (max 3MB)");
    err.code = "BAD_MEDIA_SIZE";
    throw err;
  }
  if (buffer.length > IMAGE_MAX) {
    const err = new Error("Image exceeds limit");
    err.code = "BAD_MEDIA_SIZE";
    throw err;
  }
  await ensureLoanSupportThread(uid);
  const messageId = randomBytes(16).toString("hex");
  const fileName = safeFileName(input.fileName || "photo.jpg");
  const storagePath = `loan_support/${uid}/${messageId}/${fileName}`;
  await bucket().file(storagePath).save(buffer, {
    resumable: false,
    metadata: { contentType, cacheControl: "private, max-age=3600" },
  });
  return sendLoanSupportMessage({
    uid,
    messageId,
    senderRole: input.senderRole,
    senderUid: input.senderUid || uid,
    text: input.text || "",
    attachments: [
      {
        type: "image",
        storagePath,
        contentType,
        sizeBytes: buffer.length,
        fileName,
      },
    ],
  });
}

export async function getLoanSupportThread(uid) {
  return ensureLoanSupportThread(uid);
}
