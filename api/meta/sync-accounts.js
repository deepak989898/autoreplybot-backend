import { verifyFirebaseIdToken } from "../../lib/auth.js";
import { db } from "../../lib/firebase.js";
import * as C from "../../lib/constants.js";
import { fetchManagedPages } from "../../lib/meta-graph.js";
import { FieldValue } from "firebase-admin/firestore";

/**
 * POST JSON body:
 * {
 *   "userAccessToken": "EAAG…",     // short-lived user token from Facebook Login in the app
 *   "pageId": "123…"                // Facebook Page to use (must appear in /me/accounts)
 * }
 *
 * Saves Page access token + linked Instagram business account into
 * users/{uid}/integrations/facebookMeta (server-side only in production — lock with Firestore rules).
 */
export default async function handler(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }

  try {
    const { uid } = await verifyFirebaseIdToken(req.headers.authorization);
    const body = typeof req.body === "string" ? JSON.parse(req.body || "{}") : req.body || {};
    const userAccessToken = (body.userAccessToken || "").trim();
    const pageId = (body.pageId || "").trim();

    if (!userAccessToken) {
      return res.status(400).json({ error: "userAccessToken required" });
    }
    if (!pageId) {
      return res.status(400).json({ error: "pageId required" });
    }

    const pages = await fetchManagedPages(userAccessToken);
    const page = pages.find((p) => p.id === pageId);
    if (!page) {
      return res.status(400).json({
        error: "Page not found for this user token. Ensure pages_show_list and a valid Page role.",
      });
    }

    const metaRef = db()
      .collection(C.COL_USERS)
      .doc(uid)
      .collection(C.COL_INTEGRATIONS)
      .doc(C.DOC_META_FACEBOOK);

    await metaRef.set(
      {
        [C.FIELD_PAGE_ID]: page.id,
        [C.FIELD_PAGE_ACCESS_TOKEN]: page.pageAccessToken,
        [C.FIELD_PAGE_DISPLAY_NAME]: page.name,
        [C.FIELD_INSTAGRAM_USER_ID]: page.instagramUserId || "",
        [C.FIELD_INSTAGRAM_USERNAME]: page.instagramUsername || "",
        [C.FIELD_UPDATED_AT]: FieldValue.serverTimestamp(),
      },
      { merge: true }
    );

    return res.status(200).json({
      ok: true,
      pageId: page.id,
      pageName: page.name,
      instagramUserId: page.instagramUserId || "",
      instagramUsername: page.instagramUsername || "",
    });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    const code = msg.includes("Authorization") ? 401 : 400;
    return res.status(code).json({
      error: code === 401 ? "Unauthorized" : "Meta account sync failed",
      code: code === 401 ? "AUTH_FAILED" : "META_SYNC_FAILED",
    });
  }
}
