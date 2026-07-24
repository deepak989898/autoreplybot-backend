import { verifyFirebaseIdToken } from "../../lib/auth.js";
import {
  endActiveSessionsForClient,
  pairingErrorResponse,
  parseBody,
  sanitizeTrustedClient,
  trustedClientsRef,
  writeAuditLog,
} from "../../lib/pairing.js";
import * as R from "../../lib/remote-constants.js";

export default async function handler(req, res) {
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
