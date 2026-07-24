import { createHash, randomBytes } from "crypto";
import { db } from "./firebase.js";
import * as R from "./remote-constants.js";
import { normalizeAllowedCapabilities } from "./capability-model.js";

/**
 * @returns {string}
 */
export function requirePairingSecret() {
  const secret = (process.env.PAIRING_TOKEN_SECRET || "").trim();
  if (!secret) {
    throw new Error("PAIRING_TOKEN_SECRET not configured");
  }
  return secret;
}

/**
 * Hash pairing secrets with server pepper. Never store plaintext code/token.
 * @param {string} plain
 * @returns {string}
 */
export function hashPairingValue(plain) {
  const secret = requirePairingSecret();
  return createHash("sha256")
    .update(`${secret}:${String(plain)}`, "utf8")
    .digest("hex");
}

/**
 * @returns {{ token: string, code: string, codeId: string }}
 */
export function generatePairingSecrets() {
  const token = randomBytes(32).toString("base64url");
  const code = String(randomBytes(4).readUInt32BE(0) % 1_000_000).padStart(6, "0");
  const codeId = randomBytes(16).toString("hex");
  return { token, code, codeId };
}

/**
 * @param {string} uid
 * @param {{ code: string, token: string }} parts
 * @returns {string}
 */
export function buildQrPayload(uid, { code, token }) {
  const params = new URLSearchParams({
    uid: String(uid),
    code: String(code),
    token: String(token),
  });
  return `autoreplybot://pair?${params.toString()}`;
}

/**
 * @param {unknown} raw
 * @returns {Record<string, unknown>}
 */
export function parseBody(raw) {
  if (typeof raw === "string") {
    try {
      const parsed = JSON.parse(raw || "{}");
      return parsed && typeof parsed === "object" ? parsed : {};
    } catch {
      return {};
    }
  }
  return raw && typeof raw === "object" ? /** @type {Record<string, unknown>} */ (raw) : {};
}

/**
 * @param {string} uid
 */
export function pairingCodesRef(uid) {
  return db().collection(R.COL_USERS).doc(uid).collection(R.COL_PAIRING_CODES);
}

/**
 * @param {string} uid
 */
export function trustedClientsRef(uid) {
  return db().collection(R.COL_USERS).doc(uid).collection(R.COL_TRUSTED_CLIENTS);
}

/**
 * @param {string} uid
 */
export function sessionsRef(uid) {
  return db().collection(R.COL_USERS).doc(uid).collection(R.COL_SESSIONS);
}

/**
 * @param {string} uid
 * @param {{
 *   action: string,
 *   deviceId?: string,
 *   clientId?: string,
 *   sessionId?: string,
 *   result?: string,
 *   metadata?: Record<string, unknown>,
 * }} entry
 */
export async function writeAuditLog(uid, entry) {
  const logId = randomBytes(16).toString("hex");
  await db()
    .collection(R.COL_USERS)
    .doc(uid)
    .collection(R.COL_AUDIT_LOGS)
    .doc(logId)
    .set({
      logId,
      action: entry.action,
      deviceId: entry.deviceId || "",
      clientId: entry.clientId || "",
      sessionId: entry.sessionId || "",
      timestamp: Date.now(),
      result: entry.result || "ok",
      metadataWithoutSensitiveMedia: entry.metadata || {},
      ownerUid: uid,
    });
}

/**
 * @param {string} id
 * @param {FirebaseFirestore.DocumentData | undefined} data
 */
export function sanitizeTrustedClient(id, data) {
  if (!data || typeof data !== "object") return null;
  const caps =
    data.allowedCapabilities && typeof data.allowedCapabilities === "object"
      ? data.allowedCapabilities
      : {};
  return {
    clientId: data.clientId || id,
    clientName: String(data.clientName || ""),
    browser: String(data.browser || data.browserName || ""),
    browserName: String(data.browserName || data.browser || ""),
    platform: String(data.platform || data.operatingSystem || ""),
    operatingSystem: String(data.operatingSystem || data.platform || ""),
    browserFingerprintHash: String(data.browserFingerprintHash || ""),
    createdAt: Number(data.createdAt || data.pairedAt || 0),
    pairedAt: Number(data.pairedAt || data.createdAt || 0),
    lastUsedAt: Number(data.lastUsedAt || data.lastSeenAt || 0),
    lastSeenAt: Number(data.lastSeenAt || data.lastUsedAt || 0),
    revoked: Boolean(data.revoked),
    persistentPairing: data.persistentPairing !== false,
    autoApproveSessions: Boolean(data.autoApproveSessions),
    requirePhoneUnlock: Boolean(data.requirePhoneUnlock),
    expiresAt: data.expiresAt == null ? null : Number(data.expiresAt),
    allowedCapabilities: normalizeAllowedCapabilities(caps),
    pairingMetadata: String(data.pairingMetadata || ""),
    ownerUid: String(data.ownerUid || ""),
    updatedAt: Number(data.updatedAt || 0),
  };
}

/**
 * @param {import("firebase-admin/firestore").QueryDocumentSnapshot} doc
 * @returns {boolean}
 */
export function isPairingDocUsable(doc) {
  const data = doc.data() || {};
  if (data.used === true) return false;
  const expiresAt = Number(data.expiresAt || 0);
  return Number.isFinite(expiresAt) && expiresAt > Date.now();
}

/**
 * End active remote sessions for a trusted client (Admin path).
 * @param {string} uid
 * @param {string} clientId
 * @returns {Promise<number>}
 */
export async function endActiveSessionsForClient(uid, clientId) {
  const snap = await sessionsRef(uid).where("clientId", "==", clientId).get();
  const now = Date.now();
  const batch = db().batch();
  let count = 0;
  snap.forEach((doc) => {
    const status = String(doc.data()?.status || "");
    if (!R.ACTIVE_SESSION_STATUSES.includes(status)) return;
    batch.update(doc.ref, {
      status: "ended",
      endedAt: now,
      terminationReason: "client_revoked",
      endReason: "client_revoked",
    });
    count += 1;
  });
  if (count > 0) await batch.commit();
  return count;
}

/**
 * End active remote sessions for a device (one-session-per-device preference).
 * @param {string} uid
 * @param {string} deviceId
 * @param {string} [reason]
 * @returns {Promise<number>}
 */
export async function endActiveSessionsForDevice(uid, deviceId, reason = "replaced") {
  const snap = await sessionsRef(uid).where("deviceId", "==", deviceId).get();
  const now = Date.now();
  const batch = db().batch();
  let count = 0;
  snap.forEach((doc) => {
    const status = String(doc.data()?.status || "");
    if (!R.ACTIVE_SESSION_STATUSES.includes(status)) return;
    batch.update(doc.ref, {
      status: "ended",
      endedAt: now,
      terminationReason: reason,
      endReason: reason,
    });
    count += 1;
  });
  if (count > 0) await batch.commit();
  return count;
}

/**
 * Map auth / pairing errors to HTTP status.
 * @param {unknown} e
 * @returns {{ status: number, error: string, code: string }}
 */
export function pairingErrorResponse(e) {
  const msg = e instanceof Error ? e.message : String(e);
  if (msg.includes("Authorization") || msg.includes("Unauthorized")) {
    return { status: 401, error: "Unauthorized", code: "AUTH_FAILED" };
  }
  if (msg.includes("PAIRING_TOKEN_SECRET")) {
    return {
      status: 500,
      error: "Pairing secret not configured",
      code: "PAIRING_MISCONFIGURED",
    };
  }
  if (
    msg.includes("expired") ||
    msg.includes("used") ||
    msg.includes("invalid") ||
    msg.includes("not found") ||
    msg.includes("Missing") ||
    msg.includes("required")
  ) {
    return { status: 400, error: msg, code: "PAIRING_REJECTED" };
  }
  return { status: 500, error: "Pairing request failed", code: "PAIRING_FAILED" };
}
