import {
  buildQrPayload,
  generatePairingSecrets,
  hashPairingValue,
  pairingCodesRef,
  pairingErrorResponse,
  requirePairingSecret,
  writeAuditLog,
} from "../../lib/pairing.js";
import { verifyFirebaseIdToken } from "../../lib/auth.js";
import { checkRateLimit } from "../../lib/rate-limit.js";
import * as R from "../../lib/remote-constants.js";

const PAIR_CREATE_LIMIT = 10;
const PAIR_CREATE_WINDOW_MS = 60 * 60 * 1000;

export default async function handler(req, res) {
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
