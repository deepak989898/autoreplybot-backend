import { verifyFirebaseIdToken } from "../../lib/auth.js";
import { db } from "../../lib/firebase.js";
import * as R from "../../lib/remote-constants.js";

/**
 * @param {string} id
 * @param {FirebaseFirestore.DocumentData | undefined} data
 */
function sanitizeSession(id, data) {
  if (!data || typeof data !== "object") return null;
  return {
    sessionId: data.sessionId || id,
    deviceId: String(data.deviceId || ""),
    clientId: String(data.clientId || ""),
    status: String(data.status || ""),
    startedAt: Number(data.startedAt || 0),
    endedAt: Number(data.endedAt || 0),
    selectedCamera: String(data.selectedCamera || ""),
    microphoneEnabled: Boolean(data.microphoneEnabled),
    flashlightEnabled: Boolean(data.flashlightEnabled),
    quality: String(data.quality || ""),
    terminationReason: String(data.terminationReason || data.endReason || ""),
  };
}

/**
 * GET /api/device/sessions — recent remote sessions for the signed-in owner.
 */
export default async function handler(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const { uid } = await verifyFirebaseIdToken(req.headers.authorization);
    const limitRaw = Number(req.query?.limit || 40);
    const limit = Number.isFinite(limitRaw)
      ? Math.min(100, Math.max(1, Math.floor(limitRaw)))
      : 40;

    const snap = await db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_SESSIONS)
      .get();

    const sessions = [];
    snap.forEach((doc) => {
      const item = sanitizeSession(doc.id, doc.data());
      if (item) sessions.push(item);
    });
    sessions.sort((a, b) => (b.startedAt || 0) - (a.startedAt || 0));
    return res.status(200).json({
      ok: true,
      sessions: sessions.slice(0, limit),
    });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    const code = msg.includes("Authorization") ? 401 : 500;
    return res.status(code).json({
      error: code === 401 ? "Unauthorized" : "Session list failed",
      code: code === 401 ? "AUTH_FAILED" : "SESSION_LIST_FAILED",
    });
  }
}
