import { randomBytes } from "crypto";
import { getMessaging } from "firebase-admin/messaging";
import { verifyFirebaseIdToken } from "../lib/auth.js";
import { db } from "../lib/firebase.js";
import { buildIceServers } from "../lib/ice-servers.js";
import {
  canonicalSessionRequest,
  capabilitiesAllowed,
  consumeNonce,
  normalizeAllowedCapabilities,
  verifyEcdsaP256Sha256,
} from "../lib/browser-identity.js";
import {
  endActiveSessionsForDevice,
  parseBody,
  writeAuditLog,
} from "../lib/pairing.js";
import * as R from "../lib/remote-constants.js";

const REQUEST_TTL_MS = 2 * 60 * 1000;
const SIGNATURE_SKEW_MS = 2 * 60 * 1000;
const ID_RE = /^[A-Za-z0-9_-]{1,128}$/;

/**
 * Single Hobby-friendly catch-all for:
 * GET  /api/device/list
 * GET  /api/device/sessions
 * GET  /api/device/ice-servers
 * POST /api/device/session/request
 * POST /api/device/session/end
 */
export default async function handler(req, res) {
  let path = "";
  const slug = req.query?.slug;
  if (Array.isArray(slug)) {
    path = slug.map((s) => String(s)).join("/");
  } else if (slug != null && String(slug).trim()) {
    path = String(slug).trim();
  } else if (typeof req.url === "string") {
    const m = req.url.match(/\/api\/device\/([^?]+)/i);
    if (m) path = decodeURIComponent(m[1]).replace(/\/+$/, "");
  }
  path = path.replace(/^\/+/, "").replace(/\/+$/, "");

  if (path === "list") return handleList(req, res);
  if (path === "sessions") return handleSessions(req, res);
  if (path === "ice-servers") return handleIceServers(req, res);
  if (path === "session/request") return handleSessionRequest(req, res);
  if (path === "session/end") return handleSessionEnd(req, res);

  return res.status(404).json({ error: "Unknown device route", code: "NOT_FOUND", path });
}

function sanitizeDevice(id, data) {
  if (!data || typeof data !== "object") return null;
  return {
    deviceId: data.deviceId || id,
    deviceName: String(data.deviceName || ""),
    deviceModel: String(data.deviceModel || ""),
    manufacturer: String(data.manufacturer || ""),
    androidVersion: String(data.androidVersion || ""),
    appVersion: String(data.appVersion || ""),
    createdAt: Number(data.createdAt || 0),
    lastSeenAt: Number(data.lastSeenAt || 0),
    online: Boolean(data.online),
    batteryLevel: Number(data.batteryLevel || 0),
    isCharging: Boolean(data.isCharging),
    networkType: String(data.networkType || ""),
    cameraAvailable: Boolean(data.cameraAvailable),
    microphoneAvailable: Boolean(data.microphoneAvailable),
    flashlightAvailable: Boolean(data.flashlightAvailable),
    revoked: Boolean(data.revoked),
    remoteControlEnabled: Boolean(data.remoteControlEnabled),
    persistentRegistration: data.persistentRegistration !== false,
    cameraPermission: String(data.cameraPermission || (data.cameraAvailable ? "granted" : "unknown")),
    microphonePermission: String(
      data.microphonePermission || (data.microphoneAvailable ? "granted" : "unknown")
    ),
    notificationPermission: String(data.notificationPermission || "unknown"),
    foregroundServiceReady: Boolean(data.foregroundServiceReady),
    pairedClientCount: Number(data.pairedClientCount || 0),
  };
}

function sanitizeSession(id, data) {
  if (!data || typeof data !== "object") return null;
  return {
    sessionId: data.sessionId || id,
    deviceId: String(data.deviceId || ""),
    clientId: String(data.clientId || ""),
    status: String(data.status || ""),
    startedAt: Number(data.startedAt || 0),
    endedAt: Number(data.endedAt || 0),
    selectedCamera: String(data.selectedCamera || ""),
    microphoneEnabled: Boolean(data.microphoneEnabled),
    flashlightEnabled: Boolean(data.flashlightEnabled),
    quality: String(data.quality || ""),
    terminationReason: String(data.terminationReason || data.endReason || ""),
    androidStartupState: String(data.androidStartupState || ""),
    autoApproved: Boolean(data.autoApproved),
  };
}

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

async function handleList(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const { uid } = await verifyFirebaseIdToken(req.headers.authorization);
    const snap = await db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_DEVICES)
      .get();
    const devices = [];
    snap.forEach((doc) => {
      const item = sanitizeDevice(doc.id, doc.data());
      if (item && !item.revoked) devices.push(item);
    });
    devices.sort((a, b) => (b.lastSeenAt || 0) - (a.lastSeenAt || 0));
    return res.status(200).json({ ok: true, devices });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    const code = msg.includes("Authorization") ? 401 : 500;
    return res.status(code).json({
      error: code === 401 ? "Unauthorized" : "Device list failed",
      code: code === 401 ? "AUTH_FAILED" : "DEVICE_LIST_FAILED",
    });
  }
}

async function handleSessions(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const { uid } = await verifyFirebaseIdToken(req.headers.authorization);
    const limitRaw = Number(req.query?.limit || 40);
    const limit = Number.isFinite(limitRaw)
      ? Math.min(100, Math.max(1, Math.floor(limitRaw)))
      : 40;

    const snap = await db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_SESSIONS)
      .get();

    const sessions = [];
    snap.forEach((doc) => {
      const item = sanitizeSession(doc.id, doc.data());
      if (item) sessions.push(item);
    });
    sessions.sort((a, b) => (b.startedAt || 0) - (a.startedAt || 0));
    return res.status(200).json({
      ok: true,
      sessions: sessions.slice(0, limit),
    });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    const code = msg.includes("Authorization") ? 401 : 500;
    return res.status(code).json({
      error: code === 401 ? "Unauthorized" : "Session list failed",
      code: code === 401 ? "AUTH_FAILED" : "SESSION_LIST_FAILED",
    });
  }
}

async function handleIceServers(req, res) {
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

async function handleSessionRequest(req, res) {
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
    const timestamp = Number(body.timestamp || 0);
    const nonce = String(body.nonce || "").trim();
    const signature = String(body.signature || "").trim();

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
        androidState: R.ANDROID_STATE_PERMISSION_REQUIRED,
      });
    }

    if (!clientId) {
      return res.status(400).json({
        error: "This browser must be paired first.",
        code: "NO_TRUSTED_CLIENT",
        androidState: R.ANDROID_STATE_PERMISSION_REQUIRED,
      });
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
    const client = clientSnap.data() || {};
    const clientName = String(client.clientName || "Trusted browser");
    const allowed = normalizeAllowedCapabilities(client.allowedCapabilities);

    if (!capabilitiesAllowed(capabilities, allowed)) {
      return res.status(403).json({
        error: "Requested capabilities exceed this browser's allowlist",
        code: "CAPABILITY_DENIED",
      });
    }

    const publicKey = client.publicKey;
    let signatureValid = false;
    if (publicKey && signature && nonce && timestamp) {
      const now = Date.now();
      if (Math.abs(now - timestamp) > SIGNATURE_SKEW_MS) {
        await writeAuditLog(uid, {
          action: R.AUDIT_INVALID_SIGNED_REQUEST,
          deviceId,
          clientId,
          result: "rejected",
          metadata: { reason: "timestamp_skew" },
        });
        return res.status(401).json({
          error: "Signed request expired or clock skew too large",
          code: "SIGNATURE_EXPIRED",
        });
      }
      if (!consumeNonce(uid, nonce)) {
        await writeAuditLog(uid, {
          action: R.AUDIT_REPLAY_ATTEMPT,
          deviceId,
          clientId,
          result: "rejected",
          metadata: { nonce },
        });
        return res.status(401).json({
          error: "Replayed or invalid nonce",
          code: "REPLAY",
        });
      }
      const canonical = canonicalSessionRequest({
        clientId,
        deviceId,
        timestamp,
        nonce,
        capabilities,
      });
      signatureValid = verifyEcdsaP256Sha256(publicKey, canonical, signature);
      if (!signatureValid) {
        await writeAuditLog(uid, {
          action: R.AUDIT_INVALID_SIGNED_REQUEST,
          deviceId,
          clientId,
          result: "rejected",
          metadata: { reason: "bad_signature" },
        });
        return res.status(401).json({
          error: "Invalid request signature — pair this browser again",
          code: "BAD_SIGNATURE",
        });
      }
    } else if (publicKey) {
      // Client has a registered key but request was not signed → reject auto path.
      await writeAuditLog(uid, {
        action: R.AUDIT_INVALID_SIGNED_REQUEST,
        deviceId,
        clientId,
        result: "rejected",
        metadata: { reason: "signature_required" },
      });
      return res.status(401).json({
        error: "Signed session request required for this trusted browser",
        code: "SIGNATURE_REQUIRED",
      });
    }

    const wantCamera = capabilities.includes("camera");
    const wantMic = capabilities.includes("microphone");
    if (wantCamera && device.cameraAvailable === false) {
      return res.status(400).json({
        error: "Camera permission must be restored in Android settings.",
        code: "CAMERA_PERMISSION",
        androidState: R.ANDROID_STATE_PERMISSION_REQUIRED,
      });
    }
    if (wantMic && device.microphoneAvailable === false) {
      return res.status(400).json({
        error: "Microphone permission must be restored in Android settings.",
        code: "MIC_PERMISSION",
        androidState: R.ANDROID_STATE_PERMISSION_REQUIRED,
      });
    }

    const autoApprove =
      Boolean(client.autoApproveSessions) && signatureValid && !Boolean(client.revoked);

    await endActiveSessionsForDevice(uid, deviceId, "replaced_by_new_request");

    const requestId = randomBytes(16).toString("hex");
    const now = Date.now();
    const expiresAt = now + REQUEST_TTL_MS;
    const fcmToken = String(device.fcmToken || "").trim();

    if (autoApprove) {
      const sessionId = randomBytes(16).toString("hex");
      const androidState = R.ANDROID_STATE_TAP_REQUIRED;
      const sessionDoc = {
        sessionId,
        deviceId,
        clientId,
        status: "connecting",
        startedAt: now,
        endedAt: 0,
        selectedCamera: wantCamera ? "front" : "none",
        microphoneEnabled: wantMic,
        flashlightEnabled: false,
        quality,
        terminationReason: "",
        ownerUid: uid,
        autoApproved: true,
        androidStartupState: androidState,
        requestId,
      };
      const requestDoc = {
        requestId,
        deviceId,
        clientId,
        requestedCapabilities: capabilities,
        status: "approved",
        createdAt: now,
        expiresAt,
        approvedAt: now,
        rejectedAt: 0,
        ownerUid: uid,
        preferredQuality: quality,
        sessionId,
        autoApproved: true,
        androidStartupState: androidState,
      };

      await db()
        .collection(R.COL_USERS)
        .doc(uid)
        .collection(R.COL_SESSIONS)
        .doc(sessionId)
        .set(sessionDoc);
      await db()
        .collection(R.COL_USERS)
        .doc(uid)
        .collection(R.COL_SESSION_REQUESTS)
        .doc(requestId)
        .set(requestDoc);
      await clientSnap.ref.set(
        { lastUsedAt: now, lastSeenAt: now, updatedAt: now },
        { merge: true }
      );

      await writeAuditLog(uid, {
        action: R.AUDIT_SESSION_AUTO_AUTHORIZED,
        deviceId,
        clientId,
        sessionId,
        result: "ok",
        metadata: { requestId, capabilities, androidState },
      });
      await writeAuditLog(uid, {
        action: "ANDROID_USER_TAP_REQUIRED",
        deviceId,
        clientId,
        sessionId,
        result: "ok",
        metadata: { requestId },
      });

      let pushSent = false;
      if (fcmToken) {
        try {
          await getMessaging().send({
            token: fcmToken,
            data: {
              type: "session_auto_start",
              requestId,
              sessionId,
              deviceId,
              clientId,
              clientName,
              expiresAt: String(expiresAt),
              cameraEnabled: wantCamera ? "1" : "0",
              microphoneEnabled: wantMic ? "1" : "0",
            },
            android: { priority: "high" },
          });
          pushSent = true;
        } catch (pushErr) {
          console.warn("FCM auto-start send failed", pushErr);
        }
      }

      return res.status(200).json({
        ok: true,
        requestId,
        sessionId,
        deviceId,
        clientId,
        expiresAt,
        capabilities,
        quality,
        pushSent,
        autoApproved: true,
        androidState,
        message:
          "Request authorized. Tap the notification on your phone to start. Modern Android may require this tap before camera or microphone can start.",
      });
    }

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
      autoApproved: false,
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
      metadata: { requestId, capabilities, quality, signatureValid },
    });

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
          android: { priority: "high" },
        });
        pushSent = true;
      } catch (pushErr) {
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
      autoApproved: false,
      androidState: null,
      message: "Waiting for Approve on the phone.",
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

async function handleSessionEnd(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const { uid } = await verifyFirebaseIdToken(req.headers.authorization);
    const body = parseBody(req.body);
    const sessionId = String(body.sessionId || "").trim();
    const reason =
      String(body.reason || "client_ended").trim().slice(0, 80) || "client_ended";

    if (!sessionId || !ID_RE.test(sessionId)) {
      return res.status(400).json({ error: "Invalid sessionId", code: "BAD_SESSION" });
    }

    const ref = db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_SESSIONS)
      .doc(sessionId);
    const snap = await ref.get();
    if (!snap.exists) {
      return res.status(404).json({ error: "Session not found", code: "SESSION_NOT_FOUND" });
    }

    const now = Date.now();
    await ref.set(
      {
        status: "ended",
        endedAt: now,
        terminationReason: reason,
      },
      { merge: true }
    );

    try {
      const signals = await ref.collection(R.COL_SIGNALS).limit(400).get();
      const batch = db().batch();
      signals.forEach((doc) => batch.delete(doc.ref));
      if (!signals.empty) await batch.commit();
    } catch {
      // ignore cleanup failures
    }

    const data = snap.data() || {};
    const previousStatus = String(data.status || "");
    if (R.ACTIVE_SESSION_STATUSES.includes(previousStatus) || previousStatus === "") {
      await writeAuditLog(uid, {
        action: R.AUDIT_SESSION_ENDED,
        deviceId: String(data.deviceId || ""),
        clientId: String(data.clientId || ""),
        sessionId,
        result: "ok",
        metadata: { reason },
      });
    }

    return res.status(200).json({ ok: true, sessionId, status: "ended" });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    const code = msg.includes("Authorization") ? 401 : 500;
    return res.status(code).json({
      error: code === 401 ? "Unauthorized" : "Session end failed",
      code: code === 401 ? "AUTH_FAILED" : "SESSION_END_FAILED",
    });
  }
}
