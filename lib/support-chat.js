import { randomBytes } from "crypto";
import { COL_PLATFORM } from "./platform-admin.js";
import { bucket, db } from "./firebase.js";

/** Firestore: platform/supportChats/threads/{uid}/messages/{messageId} */
export const DOC_SUPPORT_CHATS = "supportChats";
export const COL_SUPPORT_THREADS = "threads";
export const COL_SUPPORT_MESSAGES = "messages";

const TEXT_MAX = 4000;
const IMAGE_MAX = 10 * 1024 * 1024;
const VIDEO_MAX = 50 * 1024 * 1024;
const IMAGE_TYPES = new Set(["image/jpeg", "image/png", "image/webp"]);
const VIDEO_TYPES = new Set(["video/mp4", "video/webm"]);
const READ_URL_TTL_MS = 60 * 60 * 1000;
const WRITE_URL_TTL_MS = 15 * 60 * 1000;

function chatsCol() {
  return db().collection(COL_PLATFORM).doc(DOC_SUPPORT_CHATS).collection(COL_SUPPORT_THREADS);
}

function threadRef(uid) {
  return chatsCol().doc(String(uid));
}

function messagesCol(uid) {
  return threadRef(uid).collection(COL_SUPPORT_MESSAGES);
}

function safeFileName(name) {
  const base = String(name || "file")
    .replace(/[^a-zA-Z0-9._-]+/g, "_")
    .slice(0, 120);
  return base || "file";
}

function mediaKind(contentType) {
  const ct = String(contentType || "").toLowerCase();
  if (IMAGE_TYPES.has(ct)) return "image";
  if (VIDEO_TYPES.has(ct)) return "video";
  return null;
}

function maxBytesForType(contentType) {
  const kind = mediaKind(contentType);
  if (kind === "image") return IMAGE_MAX;
  if (kind === "video") return VIDEO_MAX;
  return 0;
}

function previewText(text, attachments) {
  const t = String(text || "").trim();
  if (t) return t.slice(0, 200);
  const a = Array.isArray(attachments) ? attachments[0] : null;
  if (a?.type === "image") return "[Image]";
  if (a?.type === "video") return "[Video]";
  return "";
}

function sanitizeAttachment(a) {
  if (!a || typeof a !== "object") return null;
  const type = a.type === "video" ? "video" : a.type === "image" ? "image" : null;
  const storagePath = String(a.storagePath || "");
  if (!type || !storagePath.startsWith("support_chat/")) return null;
  return {
    type,
    storagePath,
    contentType: String(a.contentType || "").slice(0, 120),
    sizeBytes: Number(a.sizeBytes || 0),
    fileName: String(a.fileName || "").slice(0, 200),
  };
}

export async function signedReadUrl(storagePath) {
  const path = String(storagePath || "");
  if (!path.startsWith("support_chat/")) {
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
      const url = await signedReadUrl(a.storagePath);
      out.push({ ...a, url });
    } catch {
      out.push({ ...a, url: "" });
    }
  }
  return out;
}

export function sanitizeThread(id, data) {
  if (!data || typeof data !== "object") return null;
  return {
    userUid: data.userUid || id,
    userEmail: String(data.userEmail || ""),
    userDisplayName: String(data.userDisplayName || ""),
    lastMessageText: String(data.lastMessageText || ""),
    lastMessageAt: Number(data.lastMessageAt || 0),
    lastSenderRole: data.lastSenderRole === "admin" ? "admin" : data.lastSenderRole === "user" ? "user" : "",
    unreadForUser: Number(data.unreadForUser || 0),
    unreadForAdmin: Number(data.unreadForAdmin || 0),
    status: data.status === "closed" ? "closed" : "open",
    createdAt: Number(data.createdAt || 0),
    updatedAt: Number(data.updatedAt || 0),
  };
}

async function sanitizeMessage(id, data) {
  const attachments = await enrichAttachments(data.attachments);
  return {
    messageId: data.messageId || id,
    senderRole: data.senderRole === "admin" ? "admin" : "user",
    senderUid: String(data.senderUid || ""),
    senderEmail: String(data.senderEmail || ""),
    text: String(data.text || ""),
    createdAt: Number(data.createdAt || 0),
    attachments,
  };
}

/**
 * @param {string} uid
 * @param {{ email?: string, displayName?: string }} [profile]
 */
export async function ensureThread(uid, profile = {}) {
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
      userEmail: String(profile.email || "").trim().toLowerCase().slice(0, 320),
      userDisplayName: String(profile.displayName || "").slice(0, 200),
      lastMessageText: "",
      lastMessageAt: 0,
      lastSenderRole: "",
      unreadForUser: 0,
      unreadForAdmin: 0,
      status: "open",
      createdAt: now,
      updatedAt: now,
    };
    await ref.set(doc);
    return sanitizeThread(id, doc);
  }
  const existing = snap.data() || {};
  const patch = { updatedAt: now };
  if (profile.email) patch.userEmail = String(profile.email).trim().toLowerCase().slice(0, 320);
  if (profile.displayName != null && String(profile.displayName).trim()) {
    patch.userDisplayName = String(profile.displayName).slice(0, 200);
  }
  if (Object.keys(patch).length > 1) await ref.set(patch, { merge: true });
  return sanitizeThread(id, { ...existing, ...patch });
}

/**
 * @param {string} uid
 * @param {{ after?: number, limit?: number }} [opts]
 */
export async function listMessages(uid, opts = {}) {
  await ensureThread(uid);
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

/**
 * @param {{
 *   uid: string,
 *   senderRole: 'user'|'admin',
 *   senderUid: string,
 *   senderEmail: string,
 *   text?: string,
 *   attachments?: object[],
 *   messageId?: string,
 * }} input
 */
export async function sendMessage(input) {
  const uid = String(input.uid || "").trim();
  const senderRole = input.senderRole === "admin" ? "admin" : "user";
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
    const err = new Error("Message text or attachment required");
    err.code = "BAD_REQUEST";
    throw err;
  }

  await ensureThread(uid, {
    email: senderRole === "user" ? input.senderEmail : undefined,
    displayName: undefined,
  });

  const messageId = String(input.messageId || randomBytes(16).toString("hex"));
  const now = Date.now();
  const doc = {
    messageId,
    senderRole,
    senderUid: String(input.senderUid || "").slice(0, 128),
    senderEmail: String(input.senderEmail || "")
      .trim()
      .toLowerCase()
      .slice(0, 320),
    text,
    createdAt: now,
    attachments,
  };

  const tRef = threadRef(uid);
  const mRef = messagesCol(uid).doc(messageId);
  const preview = previewText(text, attachments);

  await db().runTransaction(async (tx) => {
    const tSnap = await tx.get(tRef);
    const prev = tSnap.exists ? tSnap.data() || {} : {};
    const unreadForUser =
      senderRole === "admin"
        ? Number(prev.unreadForUser || 0) + 1
        : Number(prev.unreadForUser || 0);
    const unreadForAdmin =
      senderRole === "user"
        ? Number(prev.unreadForAdmin || 0) + 1
        : Number(prev.unreadForAdmin || 0);
    tx.set(mRef, doc);
    tx.set(
      tRef,
      {
        userUid: uid,
        lastMessageText: preview,
        lastMessageAt: now,
        lastSenderRole: senderRole,
        unreadForUser,
        unreadForAdmin,
        status: "open",
        updatedAt: now,
        ...(tSnap.exists
          ? {}
          : {
              userEmail: String(input.senderEmail || "")
                .trim()
                .toLowerCase()
                .slice(0, 320),
              userDisplayName: "",
              createdAt: now,
            }),
      },
      { merge: true }
    );
  });

  return sanitizeMessage(messageId, doc);
}

/**
 * @param {string} uid
 * @param {'user'|'admin'} role
 */
export async function markRead(uid, role) {
  const id = String(uid || "").trim();
  if (!id) return null;
  const ref = threadRef(id);
  const snap = await ref.get();
  if (!snap.exists) return null;
  const patch =
    role === "admin"
      ? { unreadForAdmin: 0, updatedAt: Date.now() }
      : { unreadForUser: 0, updatedAt: Date.now() };
  await ref.set(patch, { merge: true });
  const next = (await ref.get()).data() || {};
  return sanitizeThread(id, next);
}

/**
 * @param {{
 *   uid: string,
 *   contentType: string,
 *   fileName: string,
 *   sizeBytes: number,
 * }} input
 */
export async function createUploadSlot(input) {
  const uid = String(input.uid || "").trim();
  const contentType = String(input.contentType || "").toLowerCase().trim();
  const sizeBytes = Number(input.sizeBytes || 0);
  const kind = mediaKind(contentType);
  const max = maxBytesForType(contentType);
  if (!uid) {
    const err = new Error("uid required");
    err.code = "BAD_REQUEST";
    throw err;
  }
  if (!kind) {
    const err = new Error("Only jpeg/png/webp images or mp4/webm videos are allowed");
    err.code = "BAD_MEDIA_TYPE";
    throw err;
  }
  if (!Number.isFinite(sizeBytes) || sizeBytes <= 0 || sizeBytes > max) {
    const err = new Error(
      kind === "image" ? "Image must be 1 byte–10MB" : "Video must be 1 byte–50MB"
    );
    err.code = "BAD_MEDIA_SIZE";
    throw err;
  }

  await ensureThread(uid);
  const messageId = randomBytes(16).toString("hex");
  const fileName = safeFileName(input.fileName);
  const storagePath = `support_chat/${uid}/${messageId}/${fileName}`;
  const file = bucket().file(storagePath);
  const [uploadUrl] = await file.getSignedUrl({
    version: "v4",
    action: "write",
    expires: Date.now() + WRITE_URL_TTL_MS,
    contentType,
  });

  return {
    messageId,
    storagePath,
    uploadUrl,
    contentType,
    sizeBytes,
    fileName,
    type: kind,
    expiresAt: Date.now() + WRITE_URL_TTL_MS,
  };
}

/**
 * After client PUTs the file, create the message row.
 * @param {{
 *   uid: string,
 *   messageId: string,
 *   storagePath: string,
 *   contentType: string,
 *   sizeBytes: number,
 *   fileName: string,
 *   text?: string,
 *   senderRole: 'user'|'admin',
 *   senderUid: string,
 *   senderEmail: string,
 * }} input
 */
export async function finalizeMediaMessage(input) {
  const uid = String(input.uid || "").trim();
  const messageId = String(input.messageId || "").trim();
  const storagePath = String(input.storagePath || "").trim();
  const contentType = String(input.contentType || "").toLowerCase().trim();
  const sizeBytes = Number(input.sizeBytes || 0);
  const kind = mediaKind(contentType);

  if (!uid || !messageId || !storagePath.startsWith(`support_chat/${uid}/`)) {
    const err = new Error("Invalid media message");
    err.code = "BAD_REQUEST";
    throw err;
  }
  if (!kind || sizeBytes <= 0 || sizeBytes > maxBytesForType(contentType)) {
    const err = new Error("Invalid media");
    err.code = "BAD_MEDIA";
    throw err;
  }

  const file = bucket().file(storagePath);
  const [exists] = await file.exists();
  if (!exists) {
    const err = new Error("Upload not found — retry attach");
    err.code = "UPLOAD_MISSING";
    throw err;
  }

  const existing = await messagesCol(uid).doc(messageId).get();
  if (existing.exists) {
    return sanitizeMessage(messageId, existing.data() || {});
  }

  return sendMessage({
    uid,
    messageId,
    senderRole: input.senderRole,
    senderUid: input.senderUid,
    senderEmail: input.senderEmail,
    text: input.text || "",
    attachments: [
      {
        type: kind,
        storagePath,
        contentType,
        sizeBytes,
        fileName: safeFileName(input.fileName),
      },
    ],
  });
}

/**
 * @param {{ q?: string, limit?: number }} [opts]
 */
export async function listThreadsForAdmin(opts = {}) {
  const limit = Math.min(Math.max(Number(opts.limit) || 100, 1), 300);
  const q = String(opts.q || "")
    .trim()
    .toLowerCase();
  const snap = await chatsCol().orderBy("lastMessageAt", "desc").limit(limit).get();
  let threads = snap.docs
    .map((d) => sanitizeThread(d.id, d.data() || {}))
    .filter(Boolean)
    .filter((t) => Number(t.lastMessageAt || 0) > 0 || Number(t.unreadForAdmin || 0) > 0);

  if (q) {
    threads = threads.filter(
      (t) =>
        String(t.userEmail || "").includes(q) ||
        String(t.userDisplayName || "").toLowerCase().includes(q) ||
        String(t.userUid || "").toLowerCase().includes(q) ||
        String(t.lastMessageText || "").toLowerCase().includes(q)
    );
  }
  return threads;
}

export async function getThread(uid) {
  const id = String(uid || "").trim();
  if (!id) return null;
  const snap = await threadRef(id).get();
  if (!snap.exists) return null;
  return sanitizeThread(id, snap.data() || {});
}
