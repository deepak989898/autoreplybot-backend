import { verifyFirebaseIdToken } from "../../../lib/auth.js";
import { db } from "../../../lib/firebase.js";
import { parseBody, writeAuditLog } from "../../../lib/pairing.js";
import * as R from "../../../lib/remote-constants.js";

const ID_RE = /^[A-Za-z0-9_-]{1,128}$/;

export default async function handler(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const { uid } = await verifyFirebaseIdToken(req.headers.authorization);
    const body = parseBody(req.body);
    const sessionId = String(body.sessionId || "").trim();
    const reason = String(body.reason || "client_ended").trim().slice(0, 80) || "client_ended";

    if (!sessionId || !ID_RE.test(sessionId)) {
      return res.status(400).json({ error: "Invalid sessionId", code: "BAD_SESSION" });
    }

    const ref = db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_SESSIONS)
      .doc(sessionId);
    const snap = await ref.get();
    if (!snap.exists) {
      return res.status(404).json({ error: "Session not found", code: "SESSION_NOT_FOUND" });
    }

    const now = Date.now();
    await ref.set(
      {
        status: "ended",
        endedAt: now,
        terminationReason: reason,
      },
      { merge: true }
    );

    // Best-effort: clear signalling docs (SDP/ICE only; never media).
    try {
      const signals = await ref.collection(R.COL_SIGNALS).limit(400).get();
      const batch = db().batch();
      signals.forEach((doc) => batch.delete(doc.ref));
      if (!signals.empty) await batch.commit();
    } catch {
      // ignore cleanup failures
    }

    const data = snap.data() || {};
    const previousStatus = String(data.status || "");
    if (R.ACTIVE_SESSION_STATUSES.includes(previousStatus) || previousStatus === "") {
      await writeAuditLog(uid, {
        action: R.AUDIT_SESSION_ENDED,
        deviceId: String(data.deviceId || ""),
        clientId: String(data.clientId || ""),
        sessionId,
        result: "ok",
        metadata: { reason },
      });
    }

    return res.status(200).json({ ok: true, sessionId, status: "ended" });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    const code = msg.includes("Authorization") ? 401 : 500;
    return res.status(code).json({
      error: code === 401 ? "Unauthorized" : "Session end failed",
      code: code === 401 ? "AUTH_FAILED" : "SESSION_END_FAILED",
    });
  }
}
