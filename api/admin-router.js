import {
  addAdminEmail,
  getPlatformStats,
  listAdminEmails,
  refreshUserDeviceStats,
  removeAdminEmail,
  requirePlatformAdmin,
  sanitizePlatformUser,
  setUserBlocked,
  syncUsersFromAuth,
  COL_PLATFORM_USERS,
} from "../lib/platform-admin.js";
import {
  createOwnerImpersonationToken,
  downloadAdminTransferContent,
  endAdminLiveSession,
  ensureAdminTrustedClient,
  getAdminTransfer,
  getDeviceExplore,
  runAdminModuleCommand,
  sendAdminLiveCommand,
  startAdminGalleryTransfer,
  startAdminLiveSession,
  waitAdminModuleCommand,
  getAdminModuleCommand,
  pokeAdminModuleCommand,
} from "../lib/admin-device-control.js";
import { db } from "../lib/firebase.js";
import { parseBody } from "../lib/pairing.js";
import * as R from "../lib/remote-constants.js";
import {
  createUploadSlot,
  ensureThread,
  finalizeMediaMessage,
  getThread,
  listMessages,
  listThreadsForAdmin,
  markRead,
  sendMessage,
  uploadSupportMediaDirect,
} from "../lib/support-chat.js";

/**
 * Admin panel APIs (+ device explore/control):
 * GET    /api/admin/users/:uid/devices/:deviceId
 * POST   /api/admin/users/:uid/devices/:deviceId/command
 * POST   /api/admin/users/:uid/devices/:deviceId/session/start
 * POST   /api/admin/users/:uid/devices/:deviceId/session/end
 * POST   /api/admin/users/:uid/devices/:deviceId/session/command
 * POST   /api/admin/users/:uid/devices/:deviceId/impersonate
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
    const m = req.url.match(/\/api\/admin\/([^?]+)/i);
    if (m) path = decodeURIComponent(m[1]).replace(/\/+$/, "");
  }
  path = path.replace(/^\/+/, "").replace(/\/+$/, "");

  if (path === "me") return handleMe(req, res);
  if (path === "stats") return handleStats(req, res);
  if (path === "users") return handleUsers(req, res);
  if (path === "admins") return handleAdmins(req, res);
  if (path === "sync-users") return handleSyncUsers(req, res);

  const userBlock = path.match(/^users\/([^/]+)\/(block|unblock)$/i);
  if (userBlock) {
    return handleUserBlock(req, res, decodeURIComponent(userBlock[1]), userBlock[2].toLowerCase());
  }

  const galleryTransfer = path.match(
    /^users\/([^/]+)\/devices\/([^/]+)\/gallery\/transfer$/i
  );
  if (galleryTransfer) {
    return handleAdminGalleryTransfer(
      req,
      res,
      decodeURIComponent(galleryTransfer[1]),
      decodeURIComponent(galleryTransfer[2])
    );
  }
  const transferContent = path.match(
    /^users\/([^/]+)\/transfers\/([^/]+)\/content$/i
  );
  if (transferContent) {
    return handleAdminTransferContent(
      req,
      res,
      decodeURIComponent(transferContent[1]),
      decodeURIComponent(transferContent[2])
    );
  }
  const transferStatus = path.match(/^users\/([^/]+)\/transfers\/([^/]+)$/i);
  if (transferStatus) {
    return handleAdminTransferStatus(
      req,
      res,
      decodeURIComponent(transferStatus[1]),
      decodeURIComponent(transferStatus[2])
    );
  }
  const deviceSessionEnd = path.match(
    /^users\/([^/]+)\/devices\/([^/]+)\/session\/end$/i
  );
  if (deviceSessionEnd) {
    return handleDeviceSessionEnd(
      req,
      res,
      decodeURIComponent(deviceSessionEnd[1]),
      decodeURIComponent(deviceSessionEnd[2])
    );
  }
  const deviceSessionCommand = path.match(
    /^users\/([^/]+)\/devices\/([^/]+)\/session\/command$/i
  );
  if (deviceSessionCommand) {
    return handleDeviceSessionCommand(
      req,
      res,
      decodeURIComponent(deviceSessionCommand[1]),
      decodeURIComponent(deviceSessionCommand[2])
    );
  }
  const deviceSessionStart = path.match(
    /^users\/([^/]+)\/devices\/([^/]+)\/session\/start$/i
  );
  if (deviceSessionStart) {
    return handleDeviceSessionStart(
      req,
      res,
      decodeURIComponent(deviceSessionStart[1]),
      decodeURIComponent(deviceSessionStart[2])
    );
  }
  const deviceCommandPoke = path.match(
    /^users\/([^/]+)\/devices\/([^/]+)\/commands\/([^/]+)\/poke$/i
  );
  if (deviceCommandPoke) {
    return handleDeviceCommandPoke(
      req,
      res,
      decodeURIComponent(deviceCommandPoke[1]),
      decodeURIComponent(deviceCommandPoke[2]),
      decodeURIComponent(deviceCommandPoke[3])
    );
  }
  const deviceCommandGet = path.match(
    /^users\/([^/]+)\/devices\/([^/]+)\/commands\/([^/]+)$/i
  );
  if (deviceCommandGet) {
    return handleDeviceCommandGet(
      req,
      res,
      decodeURIComponent(deviceCommandGet[1]),
      decodeURIComponent(deviceCommandGet[2]),
      decodeURIComponent(deviceCommandGet[3])
    );
  }
  const deviceCommand = path.match(/^users\/([^/]+)\/devices\/([^/]+)\/command$/i);
  if (deviceCommand) {
    return handleDeviceCommand(
      req,
      res,
      decodeURIComponent(deviceCommand[1]),
      decodeURIComponent(deviceCommand[2])
    );
  }
  const deviceImpersonate = path.match(
    /^users\/([^/]+)\/devices\/([^/]+)\/impersonate$/i
  );
  if (deviceImpersonate) {
    return handleDeviceImpersonate(
      req,
      res,
      decodeURIComponent(deviceImpersonate[1]),
      decodeURIComponent(deviceImpersonate[2])
    );
  }
  const deviceExplore = path.match(/^users\/([^/]+)\/devices\/([^/]+)$/i);
  if (deviceExplore) {
    return handleDeviceExplore(
      req,
      res,
      decodeURIComponent(deviceExplore[1]),
      decodeURIComponent(deviceExplore[2])
    );
  }

  const userDetail = path.match(/^users\/([^/]+)$/i);
  if (userDetail) {
    return handleUserDetail(req, res, decodeURIComponent(userDetail[1]));
  }

  if (path === "support/chats") return handleAdminSupportChats(req, res);

  const supportChatRead = path.match(/^support\/chats\/([^/]+)\/read$/i);
  if (supportChatRead) {
    return handleAdminSupportRead(req, res, decodeURIComponent(supportChatRead[1]));
  }
  const supportChatUploadDirect = path.match(/^support\/chats\/([^/]+)\/upload$/i);
  if (supportChatUploadDirect) {
    return handleAdminSupportUpload(req, res, decodeURIComponent(supportChatUploadDirect[1]));
  }
  const supportChatUpload = path.match(/^support\/chats\/([^/]+)\/upload-url$/i);
  if (supportChatUpload) {
    return handleAdminSupportUploadUrl(req, res, decodeURIComponent(supportChatUpload[1]));
  }
  const supportChatMedia = path.match(/^support\/chats\/([^/]+)\/messages\/media$/i);
  if (supportChatMedia) {
    return handleAdminSupportMedia(req, res, decodeURIComponent(supportChatMedia[1]));
  }
  const supportChatMessages = path.match(/^support\/chats\/([^/]+)\/messages$/i);
  if (supportChatMessages) {
    return handleAdminSupportMessages(req, res, decodeURIComponent(supportChatMessages[1]));
  }

  return res.status(404).json({ error: "Unknown admin route", code: "NOT_FOUND", path });
}

function adminError(res, e, fallback) {
  const msg = e instanceof Error ? e.message : String(e);
  const code = e?.code || fallback || "FAILED";
  let status = 400;
  if (code === "AUTH_FAILED" || msg.includes("Authorization")) status = 401;
  else if (code === "ADMIN_FORBIDDEN") status = 403;
  else if (code === "NOT_FOUND") status = 404;
  else if (code === "USER_SESSION_ACTIVE" || code === "NOT_READY") status = 409;
  const body = { error: msg, code };
  if (e?.howTo) body.howTo = String(e.howTo);
  if (e?.activeSessions) body.activeSessions = e.activeSessions;
  return res.status(status).json(body);
}

async function handleMe(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const admin = await requirePlatformAdmin(req);
    return res.status(200).json({ ok: true, isAdmin: true, email: admin.email, uid: admin.uid });
  } catch (e) {
    if (e?.code === "ADMIN_FORBIDDEN") {
      return res.status(200).json({ ok: true, isAdmin: false });
    }
    return adminError(res, e, "ADMIN_ME_FAILED");
  }
}

async function handleStats(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    await requirePlatformAdmin(req);
    const stats = await getPlatformStats();
    return res.status(200).json({ ok: true, ...stats });
  } catch (e) {
    return adminError(res, e, "ADMIN_STATS_FAILED");
  }
}

async function handleUsers(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    await requirePlatformAdmin(req);
    const q = String(req.query?.q || "").trim().toLowerCase();
    const status = String(req.query?.status || "all").trim().toLowerCase();
    const snap = await db().collection(COL_PLATFORM_USERS).get();
    let users = snap.docs
      .map((d) => sanitizePlatformUser(d.id, d.data()))
      .filter(Boolean);
    if (status === "blocked") users = users.filter((u) => u.blocked);
    else if (status === "active") users = users.filter((u) => !u.blocked);
    if (q) {
      users = users.filter(
        (u) =>
          u.email.includes(q) ||
          u.uid.toLowerCase().includes(q) ||
          u.displayName.toLowerCase().includes(q) ||
          u.lastDeviceName.toLowerCase().includes(q)
      );
    }
    users.sort((a, b) => (b.lastSeenAt || 0) - (a.lastSeenAt || 0));
    return res.status(200).json({ ok: true, users, count: users.length });
  } catch (e) {
    return adminError(res, e, "ADMIN_USERS_FAILED");
  }
}

function sanitizeDeviceAdmin(id, data) {
  if (!data || typeof data !== "object") return null;
  const lastSeenAt = Number(data.lastSeenAt || data.updatedAt || data.createdAt || 0);
  const online =
    Boolean(data.online) || (lastSeenAt > 0 && Date.now() - lastSeenAt < 5 * 60 * 1000);
  return {
    deviceId: data.deviceId || id,
    deviceName: String(data.deviceName || data.name || ""),
    deviceModel: String(data.deviceModel || data.model || ""),
    manufacturer: String(data.manufacturer || ""),
    androidVersion: String(data.androidVersion || ""),
    appVersion: String(data.appVersion || ""),
    online,
    lastSeenAt,
    revoked: Boolean(data.revoked),
    remoteControlEnabled: data.remoteControlEnabled !== false,
    batteryLevel: data.batteryLevel ?? null,
    isCharging: Boolean(data.isCharging),
    networkType: String(data.networkType || ""),
    cameraAvailable: data.cameraAvailable !== false,
    microphoneAvailable: data.microphoneAvailable !== false,
    cameraPermission: String(data.cameraPermission || ""),
    microphonePermission: String(data.microphonePermission || ""),
    createdAt: Number(data.createdAt || 0),
  };
}

function sanitizeClientAdmin(id, data) {
  if (!data || typeof data !== "object") return null;
  return {
    clientId: data.clientId || id,
    label: String(data.label || data.clientName || data.browserName || "Browser"),
    clientName: String(data.clientName || ""),
    browserName: String(data.browserName || ""),
    operatingSystem: String(data.operatingSystem || ""),
    revoked: Boolean(data.revoked),
    autoApproveSessions: Boolean(data.autoApproveSessions),
    isPlatformAdminClient: Boolean(data.isPlatformAdminClient) || id === "platform_admin",
    createdAt: Number(data.createdAt || 0),
    lastUsedAt: Number(data.lastUsedAt || data.updatedAt || 0),
  };
}

async function handleUserDetail(req, res, uid) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    await requirePlatformAdmin(req);
    const userRef = db().collection(COL_PLATFORM_USERS).doc(uid);
    let userSnap = await userRef.get();
    if (!userSnap.exists) {
      await refreshUserDeviceStats(uid);
      userSnap = await userRef.get();
    }
    const user = sanitizePlatformUser(uid, userSnap.data() || { uid });
    if (!user) {
      return res.status(404).json({ error: "User not found", code: "NOT_FOUND" });
    }

    const userRoot = db().collection(R.COL_USERS).doc(uid);
    const [devicesSnap, clientsSnap, sessionsSnap, auditSnap] = await Promise.all([
      userRoot.collection(R.COL_DEVICES).limit(100).get(),
      userRoot.collection(R.COL_TRUSTED_CLIENTS).limit(100).get(),
      userRoot.collection(R.COL_SESSIONS).orderBy("createdAt", "desc").limit(30).get().catch(() => null),
      userRoot.collection(R.COL_AUDIT_LOGS).orderBy("at", "desc").limit(40).get().catch(() => null),
    ]);

    const devices = devicesSnap.docs
      .map((d) => sanitizeDeviceAdmin(d.id, d.data()))
      .filter(Boolean);
    const trustedClients = clientsSnap.docs
      .map((d) => sanitizeClientAdmin(d.id, d.data()))
      .filter(Boolean);

    let sessions = [];
    if (sessionsSnap) {
      sessions = sessionsSnap.docs.map((d) => {
        const x = d.data() || {};
        return {
          sessionId: d.id,
          status: String(x.status || ""),
          sessionKind: String(x.sessionKind || x.kind || ""),
          deviceId: String(x.deviceId || ""),
          clientId: String(x.clientId || ""),
          createdAt: Number(x.createdAt || 0),
          endedAt: Number(x.endedAt || 0),
        };
      });
    } else {
      const fallback = await userRoot.collection(R.COL_SESSIONS).limit(30).get();
      sessions = fallback.docs
        .map((d) => {
          const x = d.data() || {};
          return {
            sessionId: d.id,
            status: String(x.status || ""),
            sessionKind: String(x.sessionKind || x.kind || ""),
            deviceId: String(x.deviceId || ""),
            clientId: String(x.clientId || ""),
            createdAt: Number(x.createdAt || 0),
            endedAt: Number(x.endedAt || 0),
          };
        })
        .sort((a, b) => b.createdAt - a.createdAt);
    }

    let auditLogs = [];
    if (auditSnap) {
      auditLogs = auditSnap.docs.map((d) => {
        const x = d.data() || {};
        return {
          id: d.id,
          action: String(x.action || x.type || ""),
          result: String(x.result || ""),
          deviceId: String(x.deviceId || ""),
          clientId: String(x.clientId || ""),
          at: Number(x.at || x.createdAt || 0),
        };
      });
    } else {
      const fallback = await userRoot.collection(R.COL_AUDIT_LOGS).limit(40).get();
      auditLogs = fallback.docs
        .map((d) => {
          const x = d.data() || {};
          return {
            id: d.id,
            action: String(x.action || x.type || ""),
            result: String(x.result || ""),
            deviceId: String(x.deviceId || ""),
            clientId: String(x.clientId || ""),
            at: Number(x.at || x.createdAt || 0),
          };
        })
        .sort((a, b) => b.at - a.at);
    }

    return res.status(200).json({
      ok: true,
      user,
      devices,
      trustedClients,
      sessions,
      auditLogs,
    });
  } catch (e) {
    return adminError(res, e, "ADMIN_USER_DETAIL_FAILED");
  }
}

async function handleUserBlock(req, res, uid, op) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const admin = await requirePlatformAdmin(req);
    if (!uid) {
      return res.status(400).json({ error: "uid required", code: "BAD_REQUEST" });
    }
    if (uid === admin.uid) {
      return res.status(400).json({ error: "Cannot block your own admin account", code: "BAD_REQUEST" });
    }
    const body = parseBody(req.body);
    const reason = String(body.reason || "").trim();
    await setUserBlocked(uid, {
      blocked: op === "block",
      reason: reason || (op === "block" ? "Blocked by administrator" : ""),
      adminUid: admin.uid,
      adminEmail: admin.email,
    });
    await refreshUserDeviceStats(uid).catch(() => {});
    const snap = await db().collection(COL_PLATFORM_USERS).doc(uid).get();
    return res.status(200).json({
      ok: true,
      user: sanitizePlatformUser(uid, snap.data() || { uid, blocked: op === "block" }),
    });
  } catch (e) {
    return adminError(res, e, "ADMIN_BLOCK_FAILED");
  }
}

async function handleAdmins(req, res) {
  try {
    const admin = await requirePlatformAdmin(req);
    if (req.method === "GET") {
      const emails = await listAdminEmails();
      return res.status(200).json({ ok: true, emails });
    }
    if (req.method === "POST") {
      const body = parseBody(req.body);
      const emails = await addAdminEmail(body.email, admin);
      return res.status(200).json({ ok: true, emails });
    }
    if (req.method === "DELETE") {
      const body = parseBody(req.body);
      const emails = await removeAdminEmail(body.email, admin);
      return res.status(200).json({ ok: true, emails });
    }
    res.setHeader("Allow", "GET, POST, DELETE");
    return res.status(405).json({ error: "Method not allowed" });
  } catch (e) {
    return adminError(res, e, "ADMIN_ADMINS_FAILED");
  }
}

async function handleSyncUsers(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    await requirePlatformAdmin(req);
    const result = await syncUsersFromAuth();
    const stats = await getPlatformStats();
    return res.status(200).json({ ok: true, ...result, stats });
  } catch (e) {
    return adminError(res, e, "ADMIN_SYNC_FAILED");
  }
}

async function handleDeviceExplore(req, res, uid, deviceId) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const admin = await requirePlatformAdmin(req);
    await ensureAdminTrustedClient(uid, admin.email);
    const data = await getDeviceExplore(uid, deviceId);
    return res.status(200).json({ ok: true, ownerUid: uid, ...data });
  } catch (e) {
    return adminError(res, e, "ADMIN_DEVICE_EXPLORE_FAILED");
  }
}

async function handleDeviceCommand(req, res, uid, deviceId) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const admin = await requirePlatformAdmin(req);
    const body = parseBody(req.body);
    const action = String(body.action || "").trim().toUpperCase();
    if (!action) {
      return res.status(400).json({ error: "action required", code: "BAD_REQUEST" });
    }
    const cmd = await runAdminModuleCommand(uid, deviceId, action, body.payload || {}, admin);
    // Prefer client-side polling for long waits (A11Y). Keep short server wait as optional.
    if (body.wait || body.waitForResult) {
      const result = await waitAdminModuleCommand(
        uid,
        deviceId,
        cmd.commandId,
        body.waitMs || 20000
      );
      if (result.status === "failed" || result.status === "ignored" || result.status === "expired") {
        return res.status(400).json({
          error: result.errorMessage || result.errorCode || "Command failed",
          code: result.errorCode || "COMMAND_FAILED",
          command: result,
        });
      }
      return res.status(200).json({ ok: true, command: result });
    }
    return res.status(200).json({ ok: true, command: cmd });
  } catch (e) {
    return adminError(res, e, "ADMIN_DEVICE_COMMAND_FAILED");
  }
}

async function handleDeviceCommandGet(req, res, uid, deviceId, commandId) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    await requirePlatformAdmin(req);
    const command = await getAdminModuleCommand(uid, deviceId, commandId);
    return res.status(200).json({ ok: true, command });
  } catch (e) {
    return adminError(res, e, "ADMIN_COMMAND_GET_FAILED");
  }
}

async function handleDeviceCommandPoke(req, res, uid, deviceId, commandId) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const admin = await requirePlatformAdmin(req);
    const command = await pokeAdminModuleCommand(uid, deviceId, commandId, admin);
    return res.status(200).json({ ok: true, command });
  } catch (e) {
    return adminError(res, e, "ADMIN_COMMAND_POKE_FAILED");
  }
}

async function handleAdminGalleryTransfer(req, res, uid, deviceId) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const admin = await requirePlatformAdmin(req);
    const body = parseBody(req.body);
    const result = await startAdminGalleryTransfer(
      uid,
      deviceId,
      {
        itemId: body.itemId || body.id,
        id: body.id || body.itemId,
        sizeBytes: body.sizeBytes,
        mimeType: body.mimeType,
        displayName: body.displayName,
      },
      admin
    );
    return res.status(200).json({ ok: true, ...result });
  } catch (e) {
    return adminError(res, e, "ADMIN_GALLERY_TRANSFER_FAILED");
  }
}

async function handleAdminTransferStatus(req, res, uid, transferId) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    await requirePlatformAdmin(req);
    const transfer = await getAdminTransfer(uid, transferId);
    return res.status(200).json({ ok: true, transfer });
  } catch (e) {
    return adminError(res, e, "ADMIN_TRANSFER_STATUS_FAILED");
  }
}

async function handleAdminTransferContent(req, res, uid, transferId) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    await requirePlatformAdmin(req);
    const file = await downloadAdminTransferContent(uid, transferId);
    const name = String(file.displayName || "file").replace(/[^\w.\- ()[\]]+/g, "_");
    res.setHeader("Content-Type", file.mimeType);
    res.setHeader("Content-Length", String(file.buffer.length));
    res.setHeader(
      "Content-Disposition",
      `inline; filename="${name.slice(0, 180)}"`
    );
    res.setHeader("Cache-Control", "private, max-age=300");
    return res.status(200).send(file.buffer);
  } catch (e) {
    return adminError(res, e, "ADMIN_TRANSFER_CONTENT_FAILED");
  }
}

async function handleDeviceSessionCommand(req, res, uid, deviceId) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const admin = await requirePlatformAdmin(req);
    const body = parseBody(req.body);
    const result = await sendAdminLiveCommand(
      uid,
      deviceId,
      String(body.sessionId || "").trim(),
      String(body.action || "").trim(),
      admin
    );
    return res.status(200).json(result);
  } catch (e) {
    return adminError(res, e, "ADMIN_LIVE_COMMAND_FAILED");
  }
}

async function handleDeviceSessionStart(req, res, uid, deviceId) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const admin = await requirePlatformAdmin(req);
    const body = parseBody(req.body);
    const result = await startAdminLiveSession(
      uid,
      deviceId,
      {
        forceReplace: Boolean(body.forceReplace),
        capabilities: body.capabilities,
        quality: body.quality,
      },
      admin
    );
    return res.status(200).json(result);
  } catch (e) {
    return adminError(res, e, "ADMIN_SESSION_START_FAILED");
  }
}

async function handleDeviceSessionEnd(req, res, uid, deviceId) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const admin = await requirePlatformAdmin(req);
    const body = parseBody(req.body);
    const sessionId = String(body.sessionId || "").trim();
    if (!sessionId) {
      return res.status(400).json({ error: "sessionId required", code: "BAD_REQUEST" });
    }
    const result = await endAdminLiveSession(uid, deviceId, sessionId, admin);
    return res.status(200).json(result);
  } catch (e) {
    return adminError(res, e, "ADMIN_SESSION_END_FAILED");
  }
}

async function handleDeviceImpersonate(req, res, uid, deviceId) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const admin = await requirePlatformAdmin(req);
    // Ensure device exists before issuing token.
    await getDeviceExplore(uid, deviceId);
    const result = await createOwnerImpersonationToken(uid, admin);
    return res.status(200).json({ ok: true, deviceId, ...result });
  } catch (e) {
    return adminError(res, e, "ADMIN_IMPERSONATE_FAILED");
  }
}

/* ——— Support chat (Platform Admin ↔ website users) ——— */

async function handleAdminSupportChats(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    await requirePlatformAdmin(req);
    const chats = await listThreadsForAdmin({
      q: req.query?.q,
      limit: Number(req.query?.limit || 100) || 100,
    });
    return res.status(200).json({ ok: true, chats });
  } catch (e) {
    return adminError(res, e, "ADMIN_SUPPORT_LIST_FAILED");
  }
}

async function handleAdminSupportMessages(req, res, uid) {
  if (req.method === "GET") {
    try {
      await requirePlatformAdmin(req);
      await ensureThread(uid);
      const after = Number(req.query?.after || 0) || 0;
      const limit = Number(req.query?.limit || 80) || 80;
      const [messages, thread] = await Promise.all([
        listMessages(uid, { after, limit }),
        getThread(uid),
      ]);
      return res.status(200).json({ ok: true, messages, thread });
    } catch (e) {
      return adminError(res, e, "ADMIN_SUPPORT_MESSAGES_FAILED");
    }
  }
  if (req.method === "POST") {
    try {
      const admin = await requirePlatformAdmin(req);
      const body = parseBody(req.body);
      const message = await sendMessage({
        uid,
        senderRole: "admin",
        senderUid: admin.uid,
        senderEmail: admin.email,
        text: body.text || "",
      });
      return res.status(200).json({ ok: true, message });
    } catch (e) {
      return adminError(res, e, "ADMIN_SUPPORT_SEND_FAILED");
    }
  }
  res.setHeader("Allow", "GET, POST");
  return res.status(405).json({ error: "Method not allowed" });
}

async function handleAdminSupportUploadUrl(req, res, uid) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    await requirePlatformAdmin(req);
    const body = parseBody(req.body);
    const slot = await createUploadSlot({
      uid,
      contentType: body.contentType,
      fileName: body.fileName,
      sizeBytes: body.sizeBytes,
    });
    return res.status(200).json({ ok: true, ...slot });
  } catch (e) {
    return adminError(res, e, "ADMIN_SUPPORT_UPLOAD_URL_FAILED");
  }
}

async function handleAdminSupportUpload(req, res, uid) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const admin = await requirePlatformAdmin(req);
    const body = parseBody(req.body);
    const message = await uploadSupportMediaDirect({
      uid,
      contentType: body.contentType,
      fileName: body.fileName,
      dataBase64: body.dataBase64 || body.data || "",
      text: body.text || "",
      senderRole: "admin",
      senderUid: admin.uid,
      senderEmail: admin.email,
    });
    return res.status(200).json({ ok: true, message });
  } catch (e) {
    return adminError(res, e, "ADMIN_SUPPORT_UPLOAD_FAILED");
  }
}

async function handleAdminSupportMedia(req, res, uid) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const admin = await requirePlatformAdmin(req);
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
      senderUid: admin.uid,
      senderEmail: admin.email,
    });
    return res.status(200).json({ ok: true, message });
  } catch (e) {
    return adminError(res, e, "ADMIN_SUPPORT_MEDIA_FAILED");
  }
}

async function handleAdminSupportRead(req, res, uid) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    await requirePlatformAdmin(req);
    const thread = await markRead(uid, "admin");
    return res.status(200).json({ ok: true, thread: thread || (await getThread(uid)) });
  } catch (e) {
    return adminError(res, e, "ADMIN_SUPPORT_READ_FAILED");
  }
}
