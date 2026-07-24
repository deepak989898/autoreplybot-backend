import { db } from "../../lib/firebase.js";
import * as C from "../../lib/constants.js";
import { runScheduledPostForUser } from "../../lib/pipeline.js";

function authorizeCron(req) {
  const secret = process.env.CRON_SECRET;
  if (!secret) return false;
  const auth = req.headers.authorization || "";
  return auth === `Bearer ${secret}`;
}

/**
 * Secured by CRON_SECRET (set the same value in Vercel → Cron → or invoke manually with curl).
 * Iterates users with schedule enabled and attempts posting when the 15-minute slot matches.
 */
export default async function handler(req, res) {
  if (req.method !== "GET" && req.method !== "POST") {
    res.setHeader("Allow", "GET, POST");
    return res.status(405).json({ error: "Method not allowed" });
  }

  if (!authorizeCron(req)) {
    return res.status(401).json({ error: "Unauthorized" });
  }

  try {
    const snap = await db()
      .collectionGroup(C.COL_SETTINGS)
      .where(C.FIELD_SCHEDULE_ENABLED, "==", true)
      .get();

    const results = [];
    for (const doc of snap.docs) {
      if (doc.id !== C.DOC_FACEBOOK_SCHEDULE) continue;
      const uid = doc.ref.parent.parent?.id;
      if (!uid) continue;

      const r = await runScheduledPostForUser(uid);
      results.push({ ok: r.ok, reason: r.reason || (r.ok ? "POSTED" : "SKIPPED") });
    }

    return res.status(200).json({ ok: true, processed: results.length, results });
  } catch {
    return res.status(500).json({
      error: "Scheduled posting failed",
      code: "CRON_POSTING_FAILED",
    });
  }
}
