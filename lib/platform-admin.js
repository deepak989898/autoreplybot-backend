import { getAuth } from "firebase-admin/auth";
import { FieldValue } from "firebase-admin/firestore";
import { verifyFirebaseIdToken } from "./auth.js";
import { db } from "./firebase.js";
import * as R from "./remote-constants.js";

export const COL_PLATFORM = "platform";
export const DOC_ADMINS = "admins";
export const COL_PLATFORM_USERS = "platformUsers";
export const DOC_ADMIN_AUDIT = "adminAudit";

const ONLINE_MS = 5 * 60 * 1000;

function normalizeEmail(email) {
  return String(email || "")
    .trim()
    .toLowerCase();
}

function envAdminEmails() {
  return String(process.env.ADMIN_EMAILS || "")
    .split(/[,;\s]+/)
    .map(normalizeEmail)
    .filter(Boolean);
}

function adminsRef() {
  return db().collection(COL_PLATFORM).doc(DOC_ADMINS);
}

function platformUserRef(uid) {
  return db().collection(COL_PLATFORM_USERS).doc(uid);
}

function adminAuditCol() {
  return db().collection(COL_PLATFORM).doc(DOC_ADMIN_AUDIT).collection("events");
}

/**
 * Ensure platform/admins exists; seed from ADMIN_EMAILS when empty.
 * @returns {Promise<string[]>}
 */
export async function ensureAdminEmails() {
  const ref = adminsRef();
  const snap = await ref.get();
  const existing = Array.isArray(snap.data()?.emails)
    ? snap.data().emails.map(normalizeEmail).filter(Boolean)
    : [];
  if (existing.length > 0) return [...new Set(existing)];

  const seeded = [...new Set(envAdminEmails())];
  if (seeded.length === 0) {
    return [];
  }
  await ref.set(
    {
      emails: seeded,
      updatedAt: Date.now(),
      updatedBy: "bootstrap:ADMIN_EMAILS",
      seededAt: Date.now(),
    },
    { merge: true }
  );
  return seeded;
}

export async function listAdminEmails() {
  return ensureAdminEmails();
}

export async function isPlatformAdminEmail(email) {
  const needle = normalizeEmail(email);
  if (!needle) return false;
  const emails = await ensureAdminEmails();
  return emails.includes(needle);
}

/**
 * @param {import('http').IncomingMessage} req
 * @returns {Promise<{ uid: string, email: string }>}
 */
export async function requirePlatformAdmin(req) {
  const { uid, email } = await verifyFirebaseIdToken(req.headers.authorization);
  const normalized = normalizeEmail(email);
  if (!normalized) {
    const err = new Error("Admin account must have an email address");
    err.code = "AUTH_FAILED";
    throw err;
  }
  if (!(await isPlatformAdminEmail(normalized))) {
    const err = new Error("Admin access denied");
    err.code = "ADMIN_FORBIDDEN";
    throw err;
  }
  return { uid, email: normalized };
}

/**
 * Throws ACCOUNT_BLOCKED when the user is blocked (admins are never blocked).
 * @param {string} uid
 * @param {string} [email]
 */
export async function assertUserNotBlocked(uid, email) {
  if (!uid) return;
  if (email && (await isPlatformAdminEmail(email))) return;
  const snap = await platformUserRef(uid).get();
  if (!snap.exists) return;
  const data = snap.data() || {};
  if (!data.blocked) return;
  const reason = String(data.blockedReason || "Your account has been disabled by an administrator.");
  const err = new Error(reason);
  err.code = "ACCOUNT_BLOCKED";
  throw err;
}

/**
 * Auth + block check for normal device/pair APIs.
 * @returns {Promise<{ uid: string, email: string }>}
 */
export async function requireAuthedUser(req) {
  const { uid, email, name, blocked } = await verifyFirebaseIdToken(req.headers.authorization);
  if (!uid) {
    const err = new Error("Unauthorized");
    err.code = "AUTH_FAILED";
    throw err;
  }
  const normalized = normalizeEmail(email);
  if (blocked && !(await isPlatformAdminEmail(normalized))) {
    const err = new Error("Your account has been disabled by an administrator.");
    err.code = "ACCOUNT_BLOCKED";
    throw err;
  }
  await assertUserNotBlocked(uid, normalized);
  return { uid, email: normalized, name: String(name || "") };
}

export async function writeAdminAudit(entry) {
  try {
    await adminAuditCol().add({
      ...entry,
      at: Date.now(),
    });
  } catch {
    /* best-effort */
  }
}

/**
 * Upsert registry row used by the admin panel.
 */
export async function upsertPlatformUser(patch) {
  const uid = String(patch.uid || "").trim();
  if (!uid) return;
  const now = Date.now();
  const ref = platformUserRef(uid);
  const data = {
    uid,
    updatedAt: now,
  };
  if (patch.email != null) data.email = normalizeEmail(patch.email);
  if (patch.displayName != null) data.displayName = String(patch.displayName || "").slice(0, 200);
  if (patch.lastSeenAt != null) data.lastSeenAt = Number(patch.lastSeenAt) || now;
  else data.lastSeenAt = now;
  if (patch.deviceCount != null) data.deviceCount = Number(patch.deviceCount) || 0;
  if (patch.onlineDeviceCount != null) {
    data.onlineDeviceCount = Number(patch.onlineDeviceCount) || 0;
  }
  if (patch.lastDeviceName != null) data.lastDeviceName = String(patch.lastDeviceName || "").slice(0, 120);
  if (patch.lastDeviceModel != null) {
    data.lastDeviceModel = String(patch.lastDeviceModel || "").slice(0, 120);
  }
  if (patch.createdAt != null) data.createdAt = Number(patch.createdAt) || now;

  const existing = await ref.get();
  if (!existing.exists && data.createdAt == null) {
    data.createdAt = now;
  }
  if (existing.exists && data.email === "" && existing.data()?.email) {
    delete data.email;
  }
  await ref.set(data, { merge: true });
}

export async function refreshUserDeviceStats(uid) {
  const devicesSnap = await db()
    .collection(R.COL_USERS)
    .doc(uid)
    .collection(R.COL_DEVICES)
    .get();
  let deviceCount = 0;
  let onlineDeviceCount = 0;
  let lastDeviceName = "";
  let lastDeviceModel = "";
  let latestSeen = 0;
  for (const doc of devicesSnap.docs) {
    const d = doc.data() || {};
    if (d.revoked) continue;
    deviceCount += 1;
    const lastSeen = Number(d.lastSeenAt || d.updatedAt || d.createdAt || 0);
    const online = Boolean(d.online) || (lastSeen > 0 && Date.now() - lastSeen < ONLINE_MS);
    if (online) onlineDeviceCount += 1;
    if (lastSeen >= latestSeen) {
      latestSeen = lastSeen;
      lastDeviceName = String(d.deviceName || d.name || "").slice(0, 120);
      lastDeviceModel = String(d.deviceModel || d.model || "").slice(0, 120);
    }
  }
  await upsertPlatformUser({
    uid,
    deviceCount,
    onlineDeviceCount,
    lastDeviceName,
    lastDeviceModel,
    lastSeenAt: latestSeen || Date.now(),
  });
  return { deviceCount, onlineDeviceCount, lastDeviceName, lastDeviceModel };
}

/**
 * Touch registry after a normal authenticated API call (non-blocking caller should catch).
 */
export async function touchPlatformUserFromAuth(uid, email, displayName) {
  try {
    await upsertPlatformUser({
      uid,
      email,
      displayName: displayName || "",
      lastSeenAt: Date.now(),
    });
  } catch {
    /* ignore */
  }
}

export async function setUserBlocked(uid, { blocked, reason, adminUid, adminEmail }) {
  const ref = platformUserRef(uid);
  const now = Date.now();
  if (blocked) {
    await ref.set(
      {
        uid,
        blocked: true,
        blockedReason: String(reason || "Blocked by administrator").slice(0, 500),
        blockedAt: now,
        blockedBy: adminEmail || adminUid || "",
        updatedAt: now,
      },
      { merge: true }
    );
  } else {
    await ref.set(
      {
        uid,
        blocked: false,
        blockedReason: "",
        blockedAt: FieldValue.delete(),
        blockedBy: "",
        unblockedAt: now,
        unblockedBy: adminEmail || adminUid || "",
        updatedAt: now,
      },
      { merge: true }
    );
  }
  try {
    // Only persist blocked=true. On unblock, clear claims so old tokens are not stuck
    // with blocked after refresh (API still checks platformUsers.blocked).
    await getAuth().setCustomUserClaims(uid, blocked ? { blocked: true } : {});
  } catch (e) {
    console.warn("setCustomUserClaims failed", uid, e?.message || e);
  }
  await writeAdminAudit({
    action: blocked ? "USER_BLOCKED" : "USER_UNBLOCKED",
    targetUid: uid,
    adminUid: adminUid || "",
    adminEmail: adminEmail || "",
    reason: String(reason || ""),
  });
}

export async function addAdminEmail(email, actor) {
  const normalized = normalizeEmail(email);
  if (!normalized || !normalized.includes("@")) {
    const err = new Error("Valid email required");
    err.code = "BAD_REQUEST";
    throw err;
  }
  const emails = await ensureAdminEmails();
  if (emails.includes(normalized)) return emails;
  emails.push(normalized);
  await adminsRef().set(
    {
      emails,
      updatedAt: Date.now(),
      updatedBy: actor?.email || actor?.uid || "",
    },
    { merge: true }
  );
  await writeAdminAudit({
    action: "ADMIN_ADDED",
    targetEmail: normalized,
    adminUid: actor?.uid || "",
    adminEmail: actor?.email || "",
  });
  return emails;
}

export async function removeAdminEmail(email, actor) {
  const normalized = normalizeEmail(email);
  let emails = await ensureAdminEmails();
  if (!emails.includes(normalized)) return emails;
  if (emails.length <= 1) {
    const err = new Error("Cannot remove the last admin");
    err.code = "BAD_REQUEST";
    throw err;
  }
  emails = emails.filter((e) => e !== normalized);
  await adminsRef().set(
    {
      emails,
      updatedAt: Date.now(),
      updatedBy: actor?.email || actor?.uid || "",
    },
    { merge: true }
  );
  await writeAdminAudit({
    action: "ADMIN_REMOVED",
    targetEmail: normalized,
    adminUid: actor?.uid || "",
    adminEmail: actor?.email || "",
  });
  return emails;
}

/**
 * Merge Firebase Auth users into platformUsers + refresh device counts.
 */
export async function syncUsersFromAuth() {
  const auth = getAuth();
  let pageToken;
  let synced = 0;
  do {
    const result = await auth.listUsers(1000, pageToken);
    for (const user of result.users) {
      await upsertPlatformUser({
        uid: user.uid,
        email: user.email || "",
        displayName: user.displayName || "",
        createdAt: user.metadata?.creationTime
          ? Date.parse(user.metadata.creationTime) || Date.now()
          : Date.now(),
        lastSeenAt: user.metadata?.lastSignInTime
          ? Date.parse(user.metadata.lastSignInTime) || Date.now()
          : Date.now(),
      });
      await refreshUserDeviceStats(user.uid);
      synced += 1;
    }
    pageToken = result.pageToken;
  } while (pageToken);
  return { synced };
}

export async function getPlatformStats() {
  const snap = await db().collection(COL_PLATFORM_USERS).get();
  let users = 0;
  let blocked = 0;
  let devices = 0;
  let online = 0;
  for (const doc of snap.docs) {
    const d = doc.data() || {};
    users += 1;
    if (d.blocked) blocked += 1;
    devices += Number(d.deviceCount || 0);
    online += Number(d.onlineDeviceCount || 0);
  }
  return { users, blocked, devices, online };
}

export function sanitizePlatformUser(id, data) {
  if (!data || typeof data !== "object") return null;
  return {
    uid: data.uid || id,
    email: String(data.email || ""),
    displayName: String(data.displayName || ""),
    createdAt: Number(data.createdAt || 0),
    lastSeenAt: Number(data.lastSeenAt || 0),
    deviceCount: Number(data.deviceCount || 0),
    onlineDeviceCount: Number(data.onlineDeviceCount || 0),
    blocked: Boolean(data.blocked),
    blockedReason: String(data.blockedReason || ""),
    blockedAt: Number(data.blockedAt || 0),
    blockedBy: String(data.blockedBy || ""),
    lastDeviceName: String(data.lastDeviceName || ""),
    lastDeviceModel: String(data.lastDeviceModel || ""),
  };
}
