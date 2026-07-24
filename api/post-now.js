import { verifyFirebaseIdToken } from "../lib/auth.js";
import { runPostNowForUser } from "../lib/pipeline.js";

export default async function handler(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const { uid } = await verifyFirebaseIdToken(req.headers.authorization);
    const result = await runPostNowForUser(uid);
    const code = result.ok ? 200 : 400;
    return res.status(code).json(result);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    const code = msg.includes("Authorization") ? 401 : 400;
    return res.status(code).json({
      error: code === 401 ? "Unauthorized" : "Post request failed",
      code: code === 401 ? "AUTH_FAILED" : "POST_REQUEST_FAILED",
    });
  }
}
