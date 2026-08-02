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
import { db } from "../lib/firebase.js";
import { parseBody } from "../lib/pairing.js";
import * as R from "../lib/remote-constants.js";

/**
 * Admin panel APIs:
 * GET    /api/admin/me
 * GET    /api/admin/stats
 * GET    /api/admin/users
 * GET    /api/admin/users/:uid
 * POST   /api/admin/users/:uid/block
 * POST   /api/admin/users/:uid/unblock
 * GET    /api/admin/admins
 * POST   /api/admin/admins
 * DELETE /api/admin/admins
 * POST   /api/admin/sync-users
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
  const userDetail = path.match(/^users\/([^/]+)$/i);
  if (userDetail) {
    return handleUserDetail(req, res, decodeURIComponent(userDetail[1]));
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
  return res.status(status).json({ error: msg, code });
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
    remoteControlEnabled: Boolean(data.remoteControlEnabled),
    createdAt: Number(data.createdAt || 0),
  };
}

function sanitizeClientAdmin(id, data) {
  if (!data || typeof data !== "object") return null;
  return {
    clientId: data.clientId || id,
    label: String(data.label || data.browserName || "Browser"),
    browserName: String(data.browserName || ""),
    operatingSystem: String(data.operatingSystem || ""),
    revoked: Boolean(data.revoked),
    autoApproveSessions: Boolean(data.autoApproveSessions),
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
