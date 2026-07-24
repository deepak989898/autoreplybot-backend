import { verifyFirebaseIdToken } from "../../lib/auth.js";
import { db } from "../../lib/firebase.js";
import * as C from "../../lib/constants.js";

export default async function handler(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const { uid } = await verifyFirebaseIdToken(req.headers.authorization);
    const schedRef = db()
      .collection(C.COL_USERS)
      .doc(uid)
      .collection(C.COL_SETTINGS)
      .doc(C.DOC_FACEBOOK_SCHEDULE);
    const metaRef = db()
      .collection(C.COL_USERS)
      .doc(uid)
      .collection(C.COL_INTEGRATIONS)
      .doc(C.DOC_META_FACEBOOK);

    const [schedSnap, metaSnap] = await Promise.all([schedRef.get(), metaRef.get()]);
    return res.status(200).json({
      ok: true,
      schedule: schedSnap.exists ? schedSnap.data() : {},
      integration: metaSnap.exists
        ? {
            pageId: metaSnap.get(C.FIELD_PAGE_ID) || "",
            pageDisplayName: metaSnap.get(C.FIELD_PAGE_DISPLAY_NAME) || "",
            instagramUserId: metaSnap.get(C.FIELD_INSTAGRAM_USER_ID) || "",
            instagramUsername: metaSnap.get(C.FIELD_INSTAGRAM_USERNAME) || "",
          }
        : {},
    });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    const code = msg.includes("Authorization") ? 401 : 400;
    return res.status(code).json({
      error: code === 401 ? "Unauthorized" : "Schedule load failed",
      code: code === 401 ? "AUTH_FAILED" : "SCHEDULE_LOAD_FAILED",
    });
  }
}
