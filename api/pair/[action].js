import {
  buildQrPayload,
  endActiveSessionsForClient,
  generatePairingSecrets,
  hashPairingValue,
  isPairingDocUsable,
  pairingCodesRef,
  pairingErrorResponse,
  parseBody,
  requirePairingSecret,
  sanitizeTrustedClient,
  trustedClientsRef,
  writeAuditLog,
} from "../../lib/pairing.js";
import { verifyFirebaseIdToken } from "../../lib/auth.js";
import { checkRateLimit } from "../../lib/rate-limit.js";
import * as R from "../../lib/remote-constants.js";
import { randomBytes } from "crypto";

const PAIR_CREATE_LIMIT = 10;
const PAIR_CREATE_WINDOW_MS = 60 * 60 * 1000;

/**
 * Single Hobby-friendly function for:
 * POST /api/pair/create
 * POST /api/pair/complete
 * POST /api/pair/revoke
 * GET  /api/pair/clients
 */
export default async function handler(req, res) {
  const action = String(req.query?.action || "")
    .trim()
    .toLowerCase();

  if (action === "create") return handleCreate(req, res);
  if (action === "complete") return handleComplete(req, res);
  if (action === "revoke") return handleRevoke(req, res);
  if (action === "clients") return handleClients(req, res);

  return res.status(404).json({ error: "Unknown pair action", code: "NOT_FOUND" });
}

async function handleCreate(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    requirePairingSecret();
    const { uid } = await verifyFirebaseIdToken(req.headers.authorization);

    const rl = checkRateLimit(`pair-create:${uid}`, PAIR_CREATE_LIMIT, PAIR_CREATE_WINDOW_MS);
    if (!rl.allowed) {
      res.setHeader("Retry-After", String(Math.ceil(rl.retryAfterMs / 1000) || 3600));
      return res.status(429).json({
        error: "Too many pairing codes created. Try again later.",
        code: "PAIR_RATE_LIMIT",
        retryAfterMs: rl.retryAfterMs,
      });
    }

    const { token, code, codeId } = generatePairingSecrets();
    const now = Date.now();
    const expiresAt = now + R.PAIRING_TTL_MS;

    await pairingCodesRef(uid)
      .doc(codeId)
      .set({
        codeId,
        codeHash: hashPairingValue(code),
        tokenHash: hashPairingValue(token),
        ownerUid: uid,
        createdAt: now,
        expiresAt,
        used: false,
        usedAt: null,
        clientId: null,
      });

    const qrPayload = buildQrPayload(uid, { code, token });

    await writeAuditLog(uid, {
      action: R.AUDIT_PAIRING_CREATED,
      result: "ok",
      metadata: { codeId, expiresAt },
    });

    return res.status(200).json({
      ok: true,
      code,
      token,
      expiresAt,
      qrPayload,
      codeId,
    });
  } catch (e) {
    const mapped = pairingErrorResponse(e);
    return res.status(mapped.status).json({
      error: mapped.error,
      code: mapped.code,
    });
  }
}

/**
 * @param {string} uid
 * @param {"code" | "token"} field
 * @param {string} hash
 */
async function findUsablePairing(uid, field, hash) {
  const snap = await pairingCodesRef(uid).where(field, "==", hash).limit(5).get();
  /** @type {import("firebase-admin/firestore").QueryDocumentSnapshot | null} */
  let match = null;
  snap.forEach((doc) => {
    if (!isPairingDocUsable(doc)) return;
    if (!match || Number(doc.data().createdAt || 0) > Number(match.data().createdAt || 0)) {
      match = doc;
    }
  });
  return match;
}

async function handleComplete(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }

  let uid = "";
  let deviceId = "";
  try {
    requirePairingSecret();
    ({ uid } = await verifyFirebaseIdToken(req.headers.authorization));
    const body = parseBody(req.body);
    const code = String(body.code || "").trim();
    const token = String(body.token || "").trim();
    const clientNameRaw = String(body.clientName || "").trim();
    deviceId = String(body.deviceId || "").trim();

    if (!code && !token) {
      throw new Error("Missing code or token");
    }
    if (!deviceId) {
      throw new Error("deviceId is required");
    }
    if (!/^[A-Za-z0-9_-]{1,128}$/.test(deviceId)) {
      throw new Error("Invalid deviceId");
    }

    const clientName =
      clientNameRaw.length > 0 ? clientNameRaw.slice(0, 120) : "Trusted browser";

    /** @type {import("firebase-admin/firestore").QueryDocumentSnapshot | null} */
    let pairingDoc = null;
    if (token) {
      pairingDoc = await findUsablePairing(uid, "tokenHash", hashPairingValue(token));
    } else {
      if (!/^\d{6}$/.test(code)) {
        throw new Error("Invalid pairing code");
      }
      pairingDoc = await findUsablePairing(uid, "codeHash", hashPairingValue(code));
    }

    if (!pairingDoc) {
      await writeAuditLog(uid, {
        action: R.AUDIT_PAIRING_REJECTED,
        deviceId,
        result: "rejected",
        metadata: { reason: "not_found_or_expired" },
      });
      throw new Error("Pairing code not found, expired, or already used");
    }

    const pairingData = pairingDoc.data() || {};
    if (String(pairingData.ownerUid || "") !== uid) {
      await writeAuditLog(uid, {
        action: R.AUDIT_PAIRING_REJECTED,
        deviceId,
        result: "rejected",
        metadata: { reason: "uid_mismatch" },
      });
      throw new Error("Pairing code not found, expired, or already used");
    }

    const clientId = randomBytes(16).toString("hex");
    const now = Date.now();
    const trusted = {
      clientId,
      clientName,
      browser: "Web browser",
      platform: "Browser",
      createdAt: now,
      lastUsedAt: now,
      revoked: false,
      pairingMetadata: JSON.stringify({
        pairedVia: token ? "token" : "code",
        deviceId,
        codeId: pairingDoc.id,
      }),
      ownerUid: uid,
    };

    await trustedClientsRef(uid).doc(clientId).set(trusted);
    await pairingDoc.ref.update({
      used: true,
      usedAt: now,
      clientId,
    });

    await writeAuditLog(uid, {
      action: R.AUDIT_BROWSER_TRUSTED,
      deviceId,
      clientId,
      result: "ok",
      metadata: { clientName },
    });

    return res.status(200).json({
      ok: true,
      client: sanitizeTrustedClient(clientId, trusted),
    });
  } catch (e) {
    const mapped = pairingErrorResponse(e);
    if (
      uid &&
      mapped.code === "PAIRING_REJECTED" &&
      !(e instanceof Error && e.message.includes("not found"))
    ) {
      try {
        await writeAuditLog(uid, {
          action: R.AUDIT_PAIRING_REJECTED,
          deviceId,
          result: "rejected",
          metadata: { reason: mapped.error },
        });
      } catch {
        // ignore secondary audit failure
      }
    }
    return res.status(mapped.status).json({
      error: mapped.error,
      code: mapped.code,
    });
  }
}

async function handleRevoke(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const { uid } = await verifyFirebaseIdToken(req.headers.authorization);
    const body = parseBody(req.body);
    const clientId = String(body.clientId || "").trim();
    if (!clientId || !/^[A-Za-z0-9_-]{1,128}$/.test(clientId)) {
      throw new Error("clientId is required");
    }

    const ref = trustedClientsRef(uid).doc(clientId);
    const snap = await ref.get();
    if (!snap.exists) {
      throw new Error("Trusted client not found");
    }
    const data = snap.data() || {};
    if (String(data.ownerUid || "") !== uid) {
      throw new Error("Trusted client not found");
    }

    const now = Date.now();
    await ref.set(
      {
        revoked: true,
        lastUsedAt: Number(data.lastUsedAt || now),
      },
      { merge: true }
    );

    const endedSessions = await endActiveSessionsForClient(uid, clientId);

    await writeAuditLog(uid, {
      action: R.AUDIT_BROWSER_REVOKED,
      clientId,
      result: "ok",
      metadata: { endedSessions },
    });

    const updated = { ...data, revoked: true, clientId: data.clientId || clientId };
    return res.status(200).json({
      ok: true,
      client: sanitizeTrustedClient(clientId, updated),
      endedSessions,
    });
  } catch (e) {
    const mapped = pairingErrorResponse(e);
    return res.status(mapped.status).json({
      error: mapped.error,
      code: mapped.code === "PAIRING_FAILED" ? "REVOKE_FAILED" : mapped.code,
    });
  }
}

async function handleClients(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const { uid } = await verifyFirebaseIdToken(req.headers.authorization);
    const snap = await trustedClientsRef(uid).get();
    const clients = [];
    snap.forEach((doc) => {
      const item = sanitizeTrustedClient(doc.id, doc.data());
      if (item) clients.push(item);
    });
    clients.sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
    return res.status(200).json({ ok: true, clients });
  } catch (e) {
    const mapped = pairingErrorResponse(e);
    return res.status(mapped.status).json({
      error: mapped.status === 401 ? "Unauthorized" : "Client list failed",
      code: mapped.status === 401 ? "AUTH_FAILED" : "CLIENT_LIST_FAILED",
    });
  }
}
