import { randomBytes } from "crypto";
import { getMessaging } from "firebase-admin/messaging";
import { verifyFirebaseIdToken } from "../../../lib/auth.js";
import { db } from "../../../lib/firebase.js";
import {
  endActiveSessionsForDevice,
  parseBody,
  writeAuditLog,
} from "../../../lib/pairing.js";
import * as R from "../../../lib/remote-constants.js";

const REQUEST_TTL_MS = 2 * 60 * 1000;
const ID_RE = /^[A-Za-z0-9_-]{1,128}$/;

/**
 * @param {unknown} caps
 * @returns {string[]}
 */
function normalizeCapabilities(caps) {
  if (!Array.isArray(caps) || caps.length === 0) {
    return ["camera", "microphone"];
  }
  const out = [];
  for (const raw of caps) {
    const c = String(raw || "")
      .trim()
      .toLowerCase();
    if (c === "camera" || c === "video" || c === "cam") out.push("camera");
    else if (c === "microphone" || c === "mic" || c === "audio" || c === "voice") {
      out.push("microphone");
    }
  }
  return [...new Set(out)];
}

export default async function handler(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const { uid } = await verifyFirebaseIdToken(req.headers.authorization);
    const body = parseBody(req.body);
    const deviceId = String(body.deviceId || "").trim();
    let clientId = String(body.clientId || "").trim();
    const quality = String(body.quality || "auto").trim().slice(0, 40) || "auto";
    const capabilities = normalizeCapabilities(body.capabilities);

    if (!deviceId || !ID_RE.test(deviceId)) {
      return res.status(400).json({ error: "Invalid deviceId", code: "BAD_DEVICE" });
    }
    if (capabilities.length === 0) {
      return res.status(400).json({
        error: "Select camera and/or microphone",
        code: "BAD_CAPABILITIES",
      });
    }

    const deviceSnap = await db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_DEVICES)
      .doc(deviceId)
      .get();
    if (!deviceSnap.exists) {
      return res.status(404).json({ error: "Device not found", code: "DEVICE_NOT_FOUND" });
    }
    const device = deviceSnap.data() || {};
    if (device.revoked === true) {
      return res.status(400).json({ error: "Device revoked", code: "DEVICE_REVOKED" });
    }
    if (device.remoteControlEnabled === false) {
      return res.status(400).json({
        error: "Remote control disabled on device",
        code: "REMOTE_DISABLED",
      });
    }

    if (!clientId) {
      const clientsSnap = await db()
        .collection(R.COL_USERS)
        .doc(uid)
        .collection(R.COL_TRUSTED_CLIENTS)
        .where("revoked", "==", false)
        .get();
      let best = null;
      clientsSnap.forEach((doc) => {
        const data = doc.data() || {};
        if (!best || Number(data.createdAt || 0) > Number(best.data().createdAt || 0)) {
          best = doc;
        }
      });
      if (!best) {
        return res.status(400).json({
          error: "Pair this browser first (no trusted clients)",
          code: "NO_TRUSTED_CLIENT",
        });
      }
      clientId = best.id;
    }
    if (!ID_RE.test(clientId)) {
      return res.status(400).json({ error: "Invalid clientId", code: "BAD_CLIENT" });
    }

    const clientSnap = await db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_TRUSTED_CLIENTS)
      .doc(clientId)
      .get();
    if (!clientSnap.exists || clientSnap.data()?.revoked === true) {
      return res.status(400).json({
        error: "Trusted client missing or revoked",
        code: "CLIENT_REVOKED",
      });
    }
    const clientName = String(clientSnap.data()?.clientName || "Trusted browser");

    // One active session per device: end any existing live sessions before requesting.
    await endActiveSessionsForDevice(uid, deviceId, "replaced_by_new_request");

    const requestId = randomBytes(16).toString("hex");
    const now = Date.now();
    const expiresAt = now + REQUEST_TTL_MS;
    const requestDoc = {
      requestId,
      deviceId,
      clientId,
      requestedCapabilities: capabilities,
      status: "pending",
      createdAt: now,
      expiresAt,
      approvedAt: 0,
      rejectedAt: 0,
      ownerUid: uid,
      preferredQuality: quality,
      sessionId: "",
    };
    await db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_SESSION_REQUESTS)
      .doc(requestId)
      .set(requestDoc);

    await writeAuditLog(uid, {
      action: R.AUDIT_SESSION_REQUESTED,
      deviceId,
      clientId,
      result: "ok",
      metadata: { requestId, capabilities, quality },
    });

    const fcmToken = String(device.fcmToken || "").trim();
    let pushSent = false;
    if (fcmToken) {
      try {
        await getMessaging().send({
          token: fcmToken,
          data: {
            type: "session_request",
            requestId,
            deviceId,
            clientId,
            clientName,
            expiresAt: String(expiresAt),
          },
          android: {
            priority: "high",
          },
        });
        pushSent = true;
      } catch (pushErr) {
        // Request is still valid; phone can poll / open deep link if push fails.
        console.warn("FCM send failed", pushErr);
      }
    }

    return res.status(200).json({
      ok: true,
      requestId,
      deviceId,
      clientId,
      expiresAt,
      capabilities,
      quality,
      pushSent,
    });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    const code = msg.includes("Authorization") ? 401 : 500;
    return res.status(code).json({
      error: code === 401 ? "Unauthorized" : "Session request failed",
      code: code === 401 ? "AUTH_FAILED" : "SESSION_REQUEST_FAILED",
    });
  }
}
