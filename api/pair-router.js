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
} from "../lib/pairing.js";
import {
  fingerprintPublicJwk,
  isPublicJwk,
  normalizeAllowedCapabilities,
} from "../lib/browser-identity.js";
import { verifyFirebaseIdToken } from "../lib/auth.js";
import { checkRateLimit } from "../lib/rate-limit.js";
import * as R from "../lib/remote-constants.js";
import { randomBytes } from "crypto";

const PAIR_CREATE_LIMIT = 10;
const PAIR_CREATE_WINDOW_MS = 60 * 60 * 1000;

/**
 * Single Hobby-friendly function for:
 * POST /api/pair/create
 * POST /api/pair/complete
 * POST /api/pair/revoke
 * POST /api/pair/update-client
 * GET  /api/pair/clients
 */
export default async function handler(req, res) {
  let action = String(req.query?.action || "")
    .trim()
    .toLowerCase();
  if (!action && typeof req.url === "string") {
    const m = req.url.match(/\/api\/pair\/([A-Za-z0-9_-]+)/i);
    if (m) action = m[1].toLowerCase();
  }

  if (action === "create") return handleCreate(req, res);
  if (action === "complete") return handleComplete(req, res);
  if (action === "revoke") return handleRevoke(req, res);
  if (action === "update-client" || action === "updateclient") {
    return handleUpdateClient(req, res);
  }
  if (action === "clients") return handleClients(req, res);

  return res.status(404).json({ error: "Unknown pair action", code: "NOT_FOUND" });
}

function detectBrowserMeta(ua) {
  const raw = String(ua || "");
  let browserName = "Web browser";
  if (/Edg\//i.test(raw)) browserName = "Edge";
  else if (/Chrome\//i.test(raw) && !/Edg\//i.test(raw)) browserName = "Chrome";
  else if (/Firefox\//i.test(raw)) browserName = "Firefox";
  else if (/Safari\//i.test(raw) && !/Chrome\//i.test(raw)) browserName = "Safari";

  let operatingSystem = "Unknown OS";
  if (/Windows/i.test(raw)) operatingSystem = "Windows";
  else if (/Mac OS X|Macintosh/i.test(raw)) operatingSystem = "macOS";
  else if (/Android/i.test(raw)) operatingSystem = "Android";
  else if (/iPhone|iPad|iOS/i.test(raw)) operatingSystem = "iOS";
  else if (/Linux/i.test(raw)) operatingSystem = "Linux";

  return { browserName, operatingSystem };
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

    const body = parseBody(req.body);
    const publicKeyJwk = body.publicKeyJwk;
    if (!isPublicJwk(publicKeyJwk)) {
      return res.status(400).json({
        error: "publicKeyJwk (ECDSA P-256) is required to pair a browser",
        code: "PUBLIC_KEY_REQUIRED",
      });
    }
    const browserFingerprintHash =
      String(body.browserFingerprintHash || "").trim() || fingerprintPublicJwk(publicKeyJwk);
    const uaMeta = detectBrowserMeta(req.headers["user-agent"]);
    const browserName =
      String(body.browserName || "").trim().slice(0, 80) || uaMeta.browserName;
    const operatingSystem =
      String(body.operatingSystem || "").trim().slice(0, 80) || uaMeta.operatingSystem;

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
        pendingPublicKeyJwk: publicKeyJwk,
        pendingBrowserFingerprintHash: browserFingerprintHash,
        pendingBrowserName: browserName,
        pendingOperatingSystem: operatingSystem,
      });

    const qrPayload = buildQrPayload(uid, { code, token });

    await writeAuditLog(uid, {
      action: R.AUDIT_PAIRING_CREATED,
      result: "ok",
      metadata: { codeId, expiresAt, browserFingerprintHash },
    });

    return res.status(200).json({
      ok: true,
      code,
      token,
      expiresAt,
      qrPayload,
      codeId,
      browserFingerprintHash,
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

    const pendingKey = pairingData.pendingPublicKeyJwk;
    if (!isPublicJwk(pendingKey)) {
      await writeAuditLog(uid, {
        action: R.AUDIT_PAIRING_REJECTED,
        deviceId,
        result: "rejected",
        metadata: { reason: "missing_pending_public_key" },
      });
      throw new Error("Pairing code missing browser public key — create a new code from the website");
    }

    const trustBrowser = body.trustBrowser !== false;
    const persistentPairing = body.persistentPairing !== false;
    const autoApproveSessions = Boolean(body.autoApproveSessions) && trustBrowser;
    const requirePhoneUnlock = Boolean(body.requirePhoneUnlock);
    const allowedCapabilities = normalizeAllowedCapabilities(
      body.allowedCapabilities || {
        camera: body.allowCamera !== false,
        microphone: body.allowMicrophone !== false,
        photoCapture: body.allowPhotoCapture !== false,
        videoRecording: Boolean(body.allowVideoRecording),
        audioRecording: Boolean(body.allowAudioRecording),
        torch: body.allowTorch !== false,
      }
    );

    const clientId = randomBytes(16).toString("hex");
    const now = Date.now();
    const browserName = String(
      pairingData.pendingBrowserName || body.browserName || "Web browser"
    ).slice(0, 80);
    const operatingSystem = String(
      pairingData.pendingOperatingSystem || body.operatingSystem || "Browser"
    ).slice(0, 80);
    const browserFingerprintHash = String(
      pairingData.pendingBrowserFingerprintHash || fingerprintPublicJwk(pendingKey)
    );

    const trusted = {
      ownerUid: uid,
      clientId,
      clientName,
      browserName,
      browser: browserName,
      operatingSystem,
      platform: operatingSystem,
      browserFingerprintHash,
      publicKey: pendingKey,
      pairedAt: now,
      createdAt: now,
      lastSeenAt: now,
      lastUsedAt: now,
      updatedAt: now,
      revoked: false,
      persistentPairing,
      autoApproveSessions,
      allowedCapabilities,
      requirePhoneUnlock,
      expiresAt: null,
      pairingMetadata: JSON.stringify({
        pairedVia: token ? "token" : "code",
        deviceId,
        codeId: pairingDoc.id,
        trustBrowser,
      }),
    };

    await trustedClientsRef(uid).doc(clientId).set(trusted);
    await pairingDoc.ref.update({
      used: true,
      usedAt: now,
      clientId,
      pendingPublicKeyJwk: null,
    });

    await writeAuditLog(uid, {
      action: R.AUDIT_BROWSER_TRUSTED,
      deviceId,
      clientId,
      result: "ok",
      metadata: {
        clientName,
        persistentPairing,
        autoApproveSessions,
        allowedCapabilities,
      },
    });
    if (persistentPairing) {
      await writeAuditLog(uid, {
        action: "PERSISTENT_PAIRING_ENABLED",
        deviceId,
        clientId,
        result: "ok",
        metadata: {},
      });
    }
    if (autoApproveSessions) {
      await writeAuditLog(uid, {
        action: R.AUDIT_AUTO_APPROVE_ENABLED,
        deviceId,
        clientId,
        result: "ok",
        metadata: {},
      });
    }

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
        autoApproveSessions: false,
        lastUsedAt: Number(data.lastUsedAt || now),
        updatedAt: now,
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

    const updated = {
      ...data,
      revoked: true,
      autoApproveSessions: false,
      clientId: data.clientId || clientId,
    };
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

/**
 * Website may only decrease privileges (disable auto-approve, shrink capabilities).
 * Enabling auto-approve or expanding capabilities requires phone-side pairing.
 */
async function handleUpdateClient(req, res) {
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
    if (String(data.ownerUid || "") !== uid || data.revoked === true) {
      throw new Error("Trusted client not found");
    }

    const prevCaps = normalizeAllowedCapabilities(data.allowedCapabilities);
    const nextCaps = normalizeAllowedCapabilities(
      body.allowedCapabilities || data.allowedCapabilities
    );
    // Website cannot expand capabilities.
    const mergedCaps = {
      camera: prevCaps.camera && nextCaps.camera,
      microphone: prevCaps.microphone && nextCaps.microphone,
      photoCapture: prevCaps.photoCapture && nextCaps.photoCapture,
      videoRecording: prevCaps.videoRecording && nextCaps.videoRecording,
      audioRecording: prevCaps.audioRecording && nextCaps.audioRecording,
      torch: prevCaps.torch && nextCaps.torch,
    };

    const prevAuto = Boolean(data.autoApproveSessions);
    let nextAuto = prevAuto;
    if (body.autoApproveSessions === false) nextAuto = false;
    if (body.autoApproveSessions === true && !prevAuto) {
      return res.status(403).json({
        error: "Auto-approve can only be enabled from the phone during pairing",
        code: "PHONE_AUTH_REQUIRED",
      });
    }

    const now = Date.now();
    const patch = {
      allowedCapabilities: mergedCaps,
      autoApproveSessions: nextAuto,
      updatedAt: now,
      lastSeenAt: now,
    };
    if (typeof body.clientName === "string" && body.clientName.trim()) {
      patch.clientName = body.clientName.trim().slice(0, 120);
    }

    await ref.set(patch, { merge: true });

    await writeAuditLog(uid, {
      action: R.AUDIT_BROWSER_PERMISSIONS_CHANGED,
      clientId,
      result: "ok",
      metadata: { allowedCapabilities: mergedCaps, autoApproveSessions: nextAuto },
    });
    if (prevAuto && !nextAuto) {
      await writeAuditLog(uid, {
        action: R.AUDIT_AUTO_APPROVE_DISABLED,
        clientId,
        result: "ok",
        metadata: {},
      });
    }

    const updated = { ...data, ...patch, clientId: data.clientId || clientId };
    return res.status(200).json({
      ok: true,
      client: sanitizeTrustedClient(clientId, updated),
    });
  } catch (e) {
    const mapped = pairingErrorResponse(e);
    return res.status(mapped.status).json({
      error: mapped.error,
      code: mapped.code === "PAIRING_FAILED" ? "UPDATE_FAILED" : mapped.code,
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
