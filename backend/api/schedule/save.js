import { FieldValue } from "firebase-admin/firestore";
import { verifyFirebaseIdToken } from "../../lib/auth.js";
import { db } from "../../lib/firebase.js";
import * as C from "../../lib/constants.js";

function pickBody(raw) {
  const b = typeof raw === "string" ? JSON.parse(raw || "{}") : raw || {};
  return {
    [C.FIELD_SCHEDULE_ENABLED]: !!b[C.FIELD_SCHEDULE_ENABLED],
    [C.FIELD_HOUR]: Number.isFinite(Number(b[C.FIELD_HOUR])) ? Math.max(0, Math.min(23, Number(b[C.FIELD_HOUR]))) : 21,
    [C.FIELD_MINUTE]: Number.isFinite(Number(b[C.FIELD_MINUTE])) ? Math.max(0, Math.min(59, Number(b[C.FIELD_MINUTE]))) : 0,
    [C.FIELD_TOPIC_BLOCKS]: String(b[C.FIELD_TOPIC_BLOCKS] || ""),
    [C.FIELD_TOPIC_HINT]: String(b[C.FIELD_TOPIC_HINT] || ""),
    [C.FIELD_POST_LANGUAGE_INDEX]: Number.isFinite(Number(b[C.FIELD_POST_LANGUAGE_INDEX])) ? Number(b[C.FIELD_POST_LANGUAGE_INDEX]) : 0,
    [C.FIELD_CUSTOM_LANGUAGE]: String(b[C.FIELD_CUSTOM_LANGUAGE] || ""),
    [C.FIELD_PAGE_BRAND]: String(b[C.FIELD_PAGE_BRAND] || ""),
    [C.FIELD_POST_REQUIREMENTS]: String(b[C.FIELD_POST_REQUIREMENTS] || ""),
    [C.FIELD_BUSINESS_TAGLINE]: String(b[C.FIELD_BUSINESS_TAGLINE] || ""),
    [C.FIELD_LOGO_URL]: String(b[C.FIELD_LOGO_URL] || ""),
    [C.FIELD_LOGO_DESCRIPTION]: String(b[C.FIELD_LOGO_DESCRIPTION] || ""),
    [C.FIELD_BRAND_PRIMARY_COLOR]: String(b[C.FIELD_BRAND_PRIMARY_COLOR] || ""),
    [C.FIELD_BRAND_ACCENT_COLOR]: String(b[C.FIELD_BRAND_ACCENT_COLOR] || ""),
    [C.FIELD_VISUAL_STYLE]: String(b[C.FIELD_VISUAL_STYLE] || ""),
    [C.FIELD_IMAGE_REQUIREMENTS]: String(b[C.FIELD_IMAGE_REQUIREMENTS] || ""),
    [C.FIELD_CAPTION_TONE]: String(b[C.FIELD_CAPTION_TONE] || ""),
    [C.FIELD_FB_AUTO_POST_ENABLED]: b[C.FIELD_FB_AUTO_POST_ENABLED] !== false,
    [C.FIELD_IG_AUTO_POST_ENABLED]: !!b[C.FIELD_IG_AUTO_POST_ENABLED],
    [C.FIELD_SCHEDULE_TIMEZONE]: String(b[C.FIELD_SCHEDULE_TIMEZONE] || ""),
    [C.FIELD_UPDATED_AT]: FieldValue.serverTimestamp(),
  };
}

export default async function handler(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const { uid } = await verifyFirebaseIdToken(req.headers.authorization);
    const patch = pickBody(req.body);
    const schedRef = db()
      .collection(C.COL_USERS)
      .doc(uid)
      .collection(C.COL_SETTINGS)
      .doc(C.DOC_FACEBOOK_SCHEDULE);
    await schedRef.set(patch, { merge: true });
    return res.status(200).json({ ok: true });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    const code = msg.includes("Authorization") ? 401 : 400;
    return res.status(code).json({
      error: code === 401 ? "Unauthorized" : "Schedule save failed",
      code: code === 401 ? "AUTH_FAILED" : "SCHEDULE_SAVE_FAILED",
    });
  }
}
