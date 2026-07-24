import { verifyFirebaseIdToken } from "../../lib/auth.js";
import { fetchManagedPages } from "../../lib/meta-graph.js";

export default async function handler(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    await verifyFirebaseIdToken(req.headers.authorization);
    const body = typeof req.body === "string" ? JSON.parse(req.body || "{}") : req.body || {};
    const userAccessToken = (body.userAccessToken || "").trim();
    if (!userAccessToken) {
      return res.status(400).json({ error: "userAccessToken required" });
    }
    const pages = await fetchManagedPages(userAccessToken);
    return res.status(200).json({
      ok: true,
      pages: pages.map(({ id, name, instagramUserId, instagramUsername }) => ({
        id,
        name,
        instagramUserId,
        instagramUsername,
      })),
    });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    const code = msg.includes("Authorization") ? 401 : 400;
    return res.status(code).json({
      error: code === 401 ? "Unauthorized" : "Page lookup failed",
      code: code === 401 ? "AUTH_FAILED" : "META_PAGE_LOOKUP_FAILED",
    });
  }
}
