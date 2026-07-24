export default function handler(req, res) {
  const stunRaw = (process.env.STUN_URLS || "stun:stun.l.google.com:19302").trim();
  const stunUrls = stunRaw
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
  // Public ICE: STUN only. TURN credentials require authenticated GET /api/device/ice-servers.
  const iceServers = (stunUrls.length ? stunUrls : ["stun:stun.l.google.com:19302"]).map(
    (urls) => ({ urls })
  );

  res.status(200).json({
    ok: true,
    firebase: {
      apiKey: process.env.FIREBASE_WEB_API_KEY || "",
      authDomain: process.env.FIREBASE_WEB_AUTH_DOMAIN || "",
      projectId: process.env.FIREBASE_WEB_PROJECT_ID || "",
      storageBucket: process.env.FIREBASE_WEB_STORAGE_BUCKET || "",
      messagingSenderId: process.env.FIREBASE_WEB_MESSAGING_SENDER_ID || "",
      appId: process.env.FIREBASE_WEB_APP_ID || "",
    },
    facebook: {
      appId: process.env.FACEBOOK_APP_ID || "",
    },
    iceServers,
  });
}
