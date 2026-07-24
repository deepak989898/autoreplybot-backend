import { randomBytes } from "crypto";
import { verifyFirebaseIdToken } from "../../lib/auth.js";
import {
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
import * as R from "../../lib/remote-constants.js";

/**
 * @param {string} uid
 * @param {"code" | "token"} field
 * @param {string} hash
 */
async function findUsablePairing(uid, field, hash) {
  const snap = await pairingCodesRef(uid)
    .where(field, "==", hash)
    .limit(5)
    .get();
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

export default async function handler(req, res) {
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
      clientNameRaw.length > 0
        ? clientNameRaw.slice(0, 120)
        : "Trusted browser";

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
