import { verifyFirebaseIdToken } from "../../../lib/auth.js";

/**
 * Authenticated ICE servers for WebRTC.
 * STUN is always returned; TURN credentials only when TURN_* env is configured
 * (static user/pass — do not put TURN secrets on the public /api/config).
 */
export default async function handler(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    await verifyFirebaseIdToken(req.headers.authorization);
    const iceServers = buildIceServers({ includeTurn: true });
    return res.status(200).json({ ok: true, iceServers });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    const code = msg.includes("Authorization") ? 401 : 500;
    return res.status(code).json({
      error: code === 401 ? "Unauthorized" : "ICE config failed",
      code: code === 401 ? "AUTH_FAILED" : "ICE_CONFIG_FAILED",
    });
  }
}

/**
 * @param {{ includeTurn?: boolean }} opts
 * @returns {Array<{ urls: string | string[], username?: string, credential?: string }>}
 */
export function buildIceServers(opts = {}) {
  const includeTurn = opts.includeTurn !== false;
  /** @type {Array<{ urls: string | string[], username?: string, credential?: string }>} */
  const iceServers = [];

  const stunRaw = (process.env.STUN_URLS || "stun:stun.l.google.com:19302").trim();
  const stunUrls = stunRaw
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
  for (const url of stunUrls) {
    iceServers.push({ urls: url });
  }

  if (includeTurn) {
    const turnUrl = (process.env.TURN_URL || "").trim();
    if (turnUrl) {
      /** @type {{ urls: string, username?: string, credential?: string }} */
      const turn = { urls: turnUrl };
      const user = (process.env.TURN_USERNAME || "").trim();
      const cred = (process.env.TURN_CREDENTIAL || "").trim();
      if (user) turn.username = user;
      if (cred) turn.credential = cred;
      iceServers.push(turn);
    }
  }

  if (iceServers.length === 0) {
    iceServers.push({ urls: "stun:stun.l.google.com:19302" });
  }
  return iceServers;
}
