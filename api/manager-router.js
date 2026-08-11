import {
  COL_PLATFORM_USERS,
  ensureAdminEmails,
  isPlatformAdminEmail,
  listManagerEmails,
  requirePlatformManager,
  sanitizePlatformUser,
  writeAdminAudit,
} from "../lib/platform-admin.js";
import {
  FEATURE_KEYS,
  FEATURE_LABELS,
  entitlementsPublicView,
  loadUserEntitlements,
  setUserWebsiteFeatures,
} from "../lib/feature-entitlements.js";
import { db } from "../lib/firebase.js";
import { parseBody } from "../lib/pairing.js";
import {
  createUploadSlot,
  ensureThread,
  finalizeMediaMessage,
  getThread,
  listMessages,
  listThreadsForAdmin,
  markRead,
  sendMessage,
  setThreadAiAgent,
  uploadSupportMediaDirect,
} from "../lib/support-chat.js";
import {
  generateSupportReplySuggestion,
  isSupportAiConfigured,
} from "../lib/support-ai-agent.js";
import { verifyFirebaseIdToken } from "../lib/auth.js";

/**
 * Manager panel APIs — feature access + support chat only.
 */
export default async function handler(req, res) {
  res.setHeader("Cache-Control", "no-store");
  let path = "";
  const slug = req.query?.slug;
  if (Array.isArray(slug)) {
    path = slug.map((s) => String(s)).join("/");
  } else if (slug != null && String(slug).trim()) {
    path = decodeURIComponent(String(slug).trim()).replace(/%2F/gi, "/");
  } else if (typeof req.url === "string") {
    const m = req.url.match(/\/api\/manager\/([^?]+)/i);
    if (m) path = decodeURIComponent(m[1]).replace(/\/+$/, "");
  }
  path = path.replace(/^\/+/, "").replace(/\/+$/, "");

  if (path === "me") return handleManagerMe(req, res);
  if (path === "users") return handleManagerUsers(req, res);

  const userFeatures = path.match(/^users\/([^/]+)\/features$/i);
  if (userFeatures) {
    return handleManagerUserFeatures(req, res, decodeURIComponent(userFeatures[1]));
  }
  const userDetail = path.match(/^users\/([^/]+)$/i);
  if (userDetail) {
    return handleManagerUserDetail(req, res, decodeURIComponent(userDetail[1]));
  }

  if (path === "support/chats") return handleManagerSupportChats(req, res);

  const supportChatRead = path.match(/^support\/chats\/([^/]+)\/read$/i);
  if (supportChatRead) {
    return handleManagerSupportRead(req, res, decodeURIComponent(supportChatRead[1]));
  }
  const supportChatAi = path.match(/^support\/chats\/([^/]+)\/ai$/i);
  if (supportChatAi) {
    return handleManagerSupportAi(req, res, decodeURIComponent(supportChatAi[1]));
  }
  const supportChatSuggest = path.match(/^support\/chats\/([^/]+)\/suggest-reply$/i);
  if (supportChatSuggest) {
    return handleManagerSupportSuggest(req, res, decodeURIComponent(supportChatSuggest[1]));
  }
  const supportChatUploadDirect = path.match(/^support\/chats\/([^/]+)\/upload$/i);
  if (supportChatUploadDirect) {
    return handleManagerSupportUpload(req, res, decodeURIComponent(supportChatUploadDirect[1]));
  }
  const supportChatUpload = path.match(/^support\/chats\/([^/]+)\/upload-url$/i);
  if (supportChatUpload) {
    return handleManagerSupportUploadUrl(req, res, decodeURIComponent(supportChatUpload[1]));
  }
  const supportChatMedia = path.match(/^support\/chats\/([^/]+)\/messages\/media$/i);
  if (supportChatMedia) {
    return handleManagerSupportMedia(req, res, decodeURIComponent(supportChatMedia[1]));
  }
  const supportChatMessages = path.match(/^support\/chats\/([^/]+)\/messages$/i);
  if (supportChatMessages) {
    return handleManagerSupportMessages(req, res, decodeURIComponent(supportChatMessages[1]));
  }

  return res.status(404).json({ error: "Unknown manager route", code: "NOT_FOUND", path });
}

function managerError(res, e, fallback) {
  const msg = e instanceof Error ? e.message : String(e);
  const code = e?.code || fallback || "FAILED";
  let status = 400;
  if (code === "AUTH_FAILED" || msg.includes("Authorization")) status = 401;
  else if (code === "MANAGER_FORBIDDEN") status = 403;
  else if (code === "NOT_FOUND") status = 404;
  return res.status(status).json({ error: msg, code });
}

async function staffEmailSet() {
  const [adminEmails, managerEmails] = await Promise.all([
    ensureAdminEmails(),
    listManagerEmails(),
  ]);
  return new Set([...adminEmails, ...managerEmails].map((e) => String(e).toLowerCase()));
}

async function handleManagerMe(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const manager = await requirePlatformManager(req);
    return res.status(200).json({
      ok: true,
      isManager: true,
      email: manager.email,
      uid: manager.uid,
    });
  } catch (e) {
    if (e?.code === "MANAGER_FORBIDDEN") {
      try {
        const { email, uid } = await verifyFirebaseIdToken(req.headers.authorization);
        const normalized = String(email || "").trim().toLowerCase();
        if (normalized && (await isPlatformAdminEmail(normalized))) {
          return res.status(200).json({
            ok: true,
            isManager: false,
            isAdmin: true,
            hint: "Use the Admin panel at /device/admin/",
          });
        }
      } catch {
        /* fall through */
      }
      return res.status(200).json({ ok: true, isManager: false });
    }
    return managerError(res, e, "MANAGER_ME_FAILED");
  }
}

async function handleManagerUsers(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    await requirePlatformManager(req);
    const q = String(req.query?.q || "")
      .trim()
      .toLowerCase();
    const status = String(req.query?.status || "all").toLowerCase();
    const staff = await staffEmailSet();
    const snap = await db().collection(COL_PLATFORM_USERS).get();
    let users = snap.docs
      .map((d) => sanitizePlatformUser(d.id, d.data()))
      .filter(Boolean)
      .filter((u) => !staff.has(String(u.email || "").toLowerCase()));
    if (status === "blocked") users = users.filter((u) => u.blocked);
    else if (status === "active") users = users.filter((u) => !u.blocked);
    if (q) {
      users = users.filter(
        (u) =>
          u.email.includes(q) ||
          u.uid.toLowerCase().includes(q) ||
          u.displayName.toLowerCase().includes(q)
      );
    }
    users.sort((a, b) => (b.lastSeenAt || 0) - (a.lastSeenAt || 0));
    return res.status(200).json({ ok: true, users, count: users.length });
  } catch (e) {
    return managerError(res, e, "MANAGER_USERS_FAILED");
  }
}

async function handleManagerUserDetail(req, res, uid) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    await requirePlatformManager(req);
    const snap = await db().collection(COL_PLATFORM_USERS).doc(uid).get();
    if (!snap.exists) {
      return res.status(404).json({ error: "User not found", code: "NOT_FOUND" });
    }
    const user = sanitizePlatformUser(uid, snap.data() || { uid });
    if (!user) {
      return res.status(404).json({ error: "User not found", code: "NOT_FOUND" });
    }
    const staff = await staffEmailSet();
    if (staff.has(String(user.email || "").toLowerCase())) {
      return res.status(403).json({ error: "Cannot manage staff accounts", code: "FORBIDDEN" });
    }
    return res.status(200).json({ ok: true, user });
  } catch (e) {
    return managerError(res, e, "MANAGER_USER_DETAIL_FAILED");
  }
}

async function handleManagerUserFeatures(req, res, uid) {
  if (req.method === "GET") {
    try {
      await requirePlatformManager(req);
      const ent = await loadUserEntitlements(uid);
      return res.status(200).json({
        ok: true,
        uid,
        keys: FEATURE_KEYS,
        labels: FEATURE_LABELS,
        entitlements: entitlementsPublicView(ent),
        configuredFeatures: ent.configuredFeatures,
      });
    } catch (e) {
      return managerError(res, e, "MANAGER_FEATURES_GET_FAILED");
    }
  }
  if (req.method === "PUT" || req.method === "POST") {
    try {
      const manager = await requirePlatformManager(req);
      const snap = await db().collection(COL_PLATFORM_USERS).doc(uid).get();
      if (!snap.exists) {
        return res.status(404).json({ error: "User not found", code: "NOT_FOUND" });
      }
      const userEmail = String(snap.data()?.email || "").toLowerCase();
      const staff = await staffEmailSet();
      if (staff.has(userEmail)) {
        return res.status(403).json({ error: "Cannot change features for staff accounts", code: "FORBIDDEN" });
      }
      const body = parseBody(req.body);
      const result = await setUserWebsiteFeatures(
        uid,
        { features: body.features, durationDays: body.durationDays },
        manager
      );
      if (result._audit) {
        await writeAdminAudit({ ...result._audit, actorRole: "manager" });
        delete result._audit;
      }
      const next = await db().collection(COL_PLATFORM_USERS).doc(uid).get();
      return res.status(200).json({
        ok: true,
        entitlements: entitlementsPublicView(result),
        user: sanitizePlatformUser(uid, next.data() || { uid }),
      });
    } catch (e) {
      return managerError(res, e, "MANAGER_FEATURES_SET_FAILED");
    }
  }
  res.setHeader("Allow", "GET, PUT, POST");
  return res.status(405).json({ error: "Method not allowed" });
}

async function handleManagerSupportChats(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    await requirePlatformManager(req);
    const chats = await listThreadsForAdmin({
      q: req.query?.q,
      limit: Number(req.query?.limit || 100) || 100,
    });
    return res.status(200).json({
      ok: true,
      chats,
      aiAgentEnabled: isSupportAiConfigured(),
    });
  } catch (e) {
    return managerError(res, e, "MANAGER_SUPPORT_LIST_FAILED");
  }
}

async function handleManagerSupportMessages(req, res, uid) {
  if (req.method === "GET") {
    try {
      await requirePlatformManager(req);
      await ensureThread(uid);
      const after = Number(req.query?.after || 0) || 0;
      const limit = Number(req.query?.limit || 80) || 80;
      const [messages, thread] = await Promise.all([
        listMessages(uid, { after, limit }),
        getThread(uid),
      ]);
      return res.status(200).json({ ok: true, messages, thread });
    } catch (e) {
      return managerError(res, e, "MANAGER_SUPPORT_MESSAGES_FAILED");
    }
  }
  if (req.method === "POST") {
    try {
      const manager = await requirePlatformManager(req);
      const body = parseBody(req.body);
      const message = await sendMessage({
        uid,
        senderRole: "admin",
        senderUid: manager.uid,
        senderEmail: manager.email,
        text: body.text || "",
      });
      return res.status(200).json({ ok: true, message });
    } catch (e) {
      return managerError(res, e, "MANAGER_SUPPORT_SEND_FAILED");
    }
  }
  res.setHeader("Allow", "GET, POST");
  return res.status(405).json({ error: "Method not allowed" });
}

async function handleManagerSupportUploadUrl(req, res, uid) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    await requirePlatformManager(req);
    const body = parseBody(req.body);
    const slot = await createUploadSlot({
      uid,
      contentType: body.contentType,
      fileName: body.fileName,
      sizeBytes: body.sizeBytes,
    });
    return res.status(200).json({ ok: true, ...slot });
  } catch (e) {
    return managerError(res, e, "MANAGER_SUPPORT_UPLOAD_URL_FAILED");
  }
}

async function handleManagerSupportUpload(req, res, uid) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const manager = await requirePlatformManager(req);
    const body = parseBody(req.body);
    const message = await uploadSupportMediaDirect({
      uid,
      contentType: body.contentType,
      fileName: body.fileName,
      dataBase64: body.dataBase64 || body.data || "",
      text: body.text || "",
      senderRole: "admin",
      senderUid: manager.uid,
      senderEmail: manager.email,
    });
    return res.status(200).json({ ok: true, message });
  } catch (e) {
    return managerError(res, e, "MANAGER_SUPPORT_UPLOAD_FAILED");
  }
}

async function handleManagerSupportMedia(req, res, uid) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const manager = await requirePlatformManager(req);
    const body = parseBody(req.body);
    const message = await finalizeMediaMessage({
      uid,
      messageId: body.messageId,
      storagePath: body.storagePath,
      contentType: body.contentType,
      sizeBytes: body.sizeBytes,
      fileName: body.fileName,
      text: body.text || "",
      senderRole: "admin",
      senderUid: manager.uid,
      senderEmail: manager.email,
    });
    return res.status(200).json({ ok: true, message });
  } catch (e) {
    return managerError(res, e, "MANAGER_SUPPORT_MEDIA_FAILED");
  }
}

async function handleManagerSupportRead(req, res, uid) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    await requirePlatformManager(req);
    const thread = await markRead(uid, "admin");
    return res.status(200).json({ ok: true, thread: thread || (await getThread(uid)) });
  } catch (e) {
    return managerError(res, e, "MANAGER_SUPPORT_READ_FAILED");
  }
}

async function handleManagerSupportAi(req, res, uid) {
  if (req.method !== "POST" && req.method !== "PATCH") {
    res.setHeader("Allow", "POST, PATCH");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    await requirePlatformManager(req);
    const body = parseBody(req.body);
    const thread = await setThreadAiAgent(uid, Boolean(body.enabled));
    return res.status(200).json({ ok: true, thread });
  } catch (e) {
    return managerError(res, e, "MANAGER_SUPPORT_AI_TOGGLE_FAILED");
  }
}

async function handleManagerSupportSuggest(req, res, uid) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    await requirePlatformManager(req);
    const suggestion = await generateSupportReplySuggestion(uid);
    if (!suggestion) {
      const err = new Error("Could not generate a suggestion");
      err.code = "AI_EMPTY_REPLY";
      throw err;
    }
    return res.status(200).json({ ok: true, suggestion });
  } catch (e) {
    return managerError(res, e, "MANAGER_SUPPORT_SUGGEST_FAILED");
  }
}
