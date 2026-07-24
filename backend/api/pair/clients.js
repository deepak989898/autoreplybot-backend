import { verifyFirebaseIdToken } from "../../lib/auth.js";
import {
  pairingErrorResponse,
  sanitizeTrustedClient,
  trustedClientsRef,
} from "../../lib/pairing.js";

export default async function handler(req, res) {
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
