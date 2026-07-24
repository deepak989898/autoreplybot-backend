import { FieldValue } from "firebase-admin/firestore";
import { db, bucket } from "./firebase.js";
import * as C from "./constants.js";
import { parseTopicBlocks } from "./topics.js";
import { captionInstruction, imageLocaleCue } from "./language.js";
import {
  generateFacebookPostCaption,
  generateDallePngBase64,
  buildFacebookImagePrompt,
} from "./openai.js";
import {
  publishFacebookPhoto,
  publishFacebookPhotoFromPng,
  publishInstagramImage,
} from "./meta-graph.js";
import { shouldPostThisCronWindow } from "./schedule-window.js";

function brandFromSchedule(data) {
  return {
    brandName: data[C.FIELD_PAGE_BRAND] || "",
    tagline: data[C.FIELD_BUSINESS_TAGLINE] || "",
    logoUrl: data[C.FIELD_LOGO_URL] || "",
    logoDescription: data[C.FIELD_LOGO_DESCRIPTION] || "",
    primaryColorHex: data[C.FIELD_BRAND_PRIMARY_COLOR] || "",
    accentColorHex: data[C.FIELD_BRAND_ACCENT_COLOR] || "",
    visualStyle: data[C.FIELD_VISUAL_STYLE] || "",
    postRequirements: data[C.FIELD_POST_REQUIREMENTS] || "",
    imageRequirements: data[C.FIELD_IMAGE_REQUIREMENTS] || "",
    captionTone: data[C.FIELD_CAPTION_TONE] || "",
  };
}

/**
 * @param {string} uid
 * @returns {Promise<{ ok: boolean, detail?: string }>}
 */
export async function runScheduledPostForUser(uid) {
  return runPostForUser(uid, { enforceWindow: true, requireScheduleEnabled: true });
}

/**
 * Manual trigger from dashboard/web app: ignores posting window and "already posted today" lock,
 * but still enforces destination toggles + token completeness.
 * @param {string} uid
 */
export async function runPostNowForUser(uid) {
  return runPostForUser(uid, { enforceWindow: false, requireScheduleEnabled: false });
}

/**
 * @param {string} uid
 * @param {{ enforceWindow: boolean, requireScheduleEnabled: boolean }} options
 */
async function runPostForUser(uid, options) {
  const apiKey = process.env.OPENAI_API_KEY;
  if (!apiKey) {
    return { ok: false, detail: "OPENAI_API_KEY not set on server" };
  }

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
  if (!schedSnap.exists) {
    return { ok: false, detail: "No facebookSchedule document" };
  }

  const s = schedSnap.data() || {};
  if (options.requireScheduleEnabled && !s[C.FIELD_SCHEDULE_ENABLED]) {
    return { ok: false, detail: "Schedule disabled" };
  }

  const fbOn = s[C.FIELD_FB_AUTO_POST_ENABLED] !== false;
  const igOn = s[C.FIELD_IG_AUTO_POST_ENABLED] === true;
  if (!fbOn && !igOn) {
    return { ok: false, detail: "Both Facebook and Instagram posting disabled" };
  }

  const hour = Number(s[C.FIELD_HOUR] ?? 21);
  const minute = Number(s[C.FIELD_MINUTE] ?? 0);
  const tz = s[C.FIELD_SCHEDULE_TIMEZONE] || process.env.DEFAULT_SCHEDULE_TIMEZONE || "UTC";
  const lastDay = s[C.FIELD_LAST_AUTO_POST_DAY] || "";

  const { run, todayStr } = shouldPostThisCronWindow(tz, hour, minute, lastDay);
  if (options.enforceWindow && !run) {
    return { ok: false, detail: "Not in this user's posting window or already posted today" };
  }

  if (!metaSnap.exists) {
    return { ok: false, detail: "No integrations/facebookMeta — call POST /api/meta/sync-accounts first" };
  }
  const m = metaSnap.data() || {};
  const pageId = m[C.FIELD_PAGE_ID] || "";
  const pageToken = m[C.FIELD_PAGE_ACCESS_TOKEN] || "";
  const igId = m[C.FIELD_INSTAGRAM_USER_ID] || "";

  if (fbOn && (!pageId || !pageToken)) {
    return { ok: false, detail: "Facebook enabled but missing pageId/pageAccessToken in integrations" };
  }
  if (igOn && (!igId || !pageToken)) {
    return { ok: false, detail: "Instagram enabled but missing instagramUserId or pageAccessToken" };
  }

  let blocks = parseTopicBlocks(s[C.FIELD_TOPIC_BLOCKS]);
  if (blocks.length === 0) {
    const legacy = (s[C.FIELD_TOPIC_HINT] || "").trim();
    if (legacy) blocks = [legacy];
  }
  if (blocks.length === 0) {
    blocks = ["Engaging daily content for followers."];
  }

  const n = blocks.length;
  let idx = Number(s[C.FIELD_TOPIC_ROTATION_INDEX] ?? 0) % n;
  const currentTopic = blocks[idx];

  const langIdx = Number(s[C.FIELD_POST_LANGUAGE_INDEX] ?? 0);
  const customLang = s[C.FIELD_CUSTOM_LANGUAGE] || "";
  const langInstr = captionInstruction(langIdx, customLang);
  const brand = brandFromSchedule(s);

  try {
    const caption = await generateFacebookPostCaption(apiKey, langInstr, brand, currentTopic);
    const cue = imageLocaleCue(langIdx, brand.brandName, customLang);
    const imgPrompt = buildFacebookImagePrompt(caption, currentTopic, brand, cue);

    const b64 = await generateDallePngBase64(apiKey, imgPrompt);
    const png = Buffer.from(b64, "base64");

    /** Public HTTPS URL for Instagram (Graph requires image_url). Facebook can use multipart and skip Storage when IG is off. */
    let imageUrl = "";

    if (igOn) {
      const filePath = `facebook_posts/${uid}/srv_${Date.now()}.png`;
      const file = bucket().file(filePath);
      await file.save(png, {
        metadata: { contentType: "image/png", cacheControl: "public, max-age=3600" },
        resumable: false,
      });
      try {
        const [signed] = await file.getSignedUrl({
          action: "read",
          expires: Date.now() + 7 * 24 * 60 * 60 * 1000,
        });
        imageUrl = signed;
      } catch {
        return {
          ok: false,
          detail: "Firebase Storage signed URL failed",
          reason: "STORAGE_SIGNED_URL_FAILED",
        };
      }
    }

    if (fbOn) {
      if (igOn) {
        await publishFacebookPhoto(pageId, pageToken, imageUrl, caption);
      } else {
        await publishFacebookPhotoFromPng(pageId, pageToken, png, caption);
      }
    }
    if (igOn) {
      await publishInstagramImage(igId, pageToken, imageUrl, caption);
    }

    const nextIdx = (idx + 1) % n;
    const update = {
      [C.FIELD_TOPIC_ROTATION_INDEX]: nextIdx,
      [C.FIELD_UPDATED_AT]: FieldValue.serverTimestamp(),
    };
    if (options.enforceWindow) {
      update[C.FIELD_LAST_AUTO_POST_DAY] = todayStr;
    }
    await schedRef.set(update, { merge: true });

    return { ok: true, detail: "Posted" };
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    const reason = msg.startsWith("OPENAI_")
      ? msg
      : msg.startsWith("GRAPH_API_ERROR")
        ? msg
        : "POSTING_UPSTREAM_FAILED";
    return { ok: false, detail: "Posting failed", reason };
  }
}
