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
import { createModuleCommand, createTransfer } from "../lib/module-commands.js";
import { getStorage } from "firebase-admin/storage";
import { bucket as storageBucket } from "../lib/firebase.js";

const REQUEST_TTL_MS = 2 * 60 * 1000;
const SIGNATURE_SKEW_MS = 2 * 60 * 1000;
const ID_RE = /^[A-Za-z0-9_-]{1,128}$/;

/**
 * Single Hobby-friendly catch-all for:
 * GET  /api/device/list
 * GET  /api/device/sessions
 * GET  /api/device/media
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
    // Vercel :path* may arrive as "location/request" or "location%2Frequest".
    path = decodeURIComponent(String(slug).trim()).replace(/%2F/gi, "/");
  } else if (typeof req.url === "string") {
    const m = req.url.match(/\/api\/device\/([^?]+)/i);
    if (m) path = decodeURIComponent(m[1]).replace(/\/+$/, "");
  }
  path = path.replace(/^\/+/, "").replace(/\/+$/, "");

  if (path === "list") return handleList(req, res);
  if (path === "sessions") return handleSessions(req, res);
  if (path === "media") return handleMediaList(req, res);
  if (path === "ice-servers") return handleIceServers(req, res);
  if (path === "session/request") return handleSessionRequest(req, res);
  if (path === "session/end") return handleSessionEnd(req, res);
  if (path === "summary") return handleSummary(req, res);
  if (path === "info") return handleDeviceInfo(req, res);
  if (path === "info/refresh") return handleInfoRefresh(req, res);
  if (path === "location") return handleLocationGet(req, res);
  if (path === "location/request") return handleLocationRequest(req, res);
  if (path === "location/live") return handleLocationLive(req, res);
  if (path === "location/stop") return handleLocationStop(req, res);
  if (path === "gallery") return handleGalleryList(req, res);
  if (path === "gallery/index") return handleGalleryIndex(req, res);
  if (path === "gallery/transfer") return handleGalleryTransfer(req, res);
  if (path === "notifications") return handleNotificationsList(req, res);
  if (path === "notifications/sync") return handleNotificationsSync(req, res);
  if (path === "messages") return handleMessagesList(req, res);
  if (path === "messages/sync") return handleMessagesSync(req, res);
  if (path === "call-logs") return handleCallLogsList(req, res);
  if (path === "call-logs/sync") return handleCallLogsSync(req, res);
  if (path === "call-logs/recording") return handleCallLogRecordingContent(req, res);
  if (path === "contacts") return handleContactsList(req, res);
  if (path === "contacts/sync") return handleContactsSync(req, res);
  if (path === "apps") return handleAppsList(req, res);
  if (path === "apps/sync") return handleAppsSync(req, res);
  if (path === "apps/detail") return handleAppDetail(req, res);
  if (path === "apps/blocks") return handleAppsBlocks(req, res);
  if (path === "apps/control") return handleAppsControl(req, res);
  if (path === "uninstall-policy") return handleUninstallPolicy(req, res);
  if (path === "recordings") return handleRecordingsList(req, res);
  if (path === "recordings/command") return handleRecordingsCommand(req, res);
  if (path === "files") return handleFilesList(req, res);
  if (path === "files/command") return handleFilesCommand(req, res);
  if (path === "transfers") return handleTransfersList(req, res);
  if (path === "transfers/content") return handleTransferContent(req, res);
  if (path === "transfers/cancel") return handleTransferCancel(req, res);
  if (path === "command") return handleModuleCommand(req, res);
  if (path === "capability-secret") return handleCapabilitySecret(req, res);
  if (path === "phone-capabilities") return handlePhoneCapabilities(req, res);
  if (path === "app-download") return handleAppDownload(req, res);
  if (path === "export-inventory") return handleExportInventory(req, res);
  if (path === "bulk") return handleBulk(req, res);

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
    locationPermission: String(data.locationPermission || "unknown"),
    locationSharingEnabled: Boolean(data.locationSharingEnabled),
    locationSharingMode: String(data.locationSharingMode || "disabled"),
    galleryAccessEnabled: Boolean(data.galleryAccessEnabled),
    notificationMirrorEnabled: Boolean(data.notificationMirrorEnabled),
    messagesSharingEnabled: Boolean(data.messagesSharingEnabled),
    screenMirrorEnabled: Boolean(data.screenMirrorEnabled),
    screenRecordEnabled: Boolean(data.screenRecordEnabled),
    installedAppsSharingEnabled: Boolean(data.installedAppsSharingEnabled),
    appControlEnabled: Boolean(data.appControlEnabled),
    allowUninstall: Boolean(data.allowUninstall),
    uninstallProtected: !Boolean(data.allowUninstall),
    deviceAdminReady: Boolean(data.deviceAdminReady),
    fileManagerEnabled: Boolean(data.fileManagerEnabled),
    storageUsedBytes: Number(data.storageUsedBytes || 0),
    storageTotalBytes: Number(data.storageTotalBytes || 0),
    lowBattery: Boolean(data.lowBattery) || Number(data.batteryLevel || 100) <= 15,
    permissionAttention: Boolean(data.permissionAttention),
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
    sessionKind: String(data.sessionKind || "camera"),
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
    } else if (c === "screenmirror" || c === "screen_mirror" || c === "screen") {
      out.push("screenMirror");
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

function sanitizeMedia(id, data) {
  if (!data || typeof data !== "object") return null;
  if (data.revoked === true) return null;
  return {
    mediaId: data.mediaId || id,
    kind: String(data.kind || ""),
    fileName: String(data.fileName || ""),
    contentType: String(data.contentType || ""),
    downloadUrl: String(data.downloadUrl || ""),
    storagePath: String(data.storagePath || ""),
    sizeBytes: Number(data.sizeBytes || 0),
    createdAt: Number(data.createdAt || 0),
    deviceId: String(data.deviceId || ""),
    sessionId: String(data.sessionId || ""),
    clientId: String(data.clientId || ""),
  };
}

async function handleMediaList(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const { uid } = await verifyFirebaseIdToken(req.headers.authorization);
    const limitRaw = Number(req.query?.limit || 80);
    const limit = Number.isFinite(limitRaw)
      ? Math.min(200, Math.max(1, Math.floor(limitRaw)))
      : 80;
    const snap = await db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_REMOTE_MEDIA)
      .get();
    const items = [];
    snap.forEach((doc) => {
      const item = sanitizeMedia(doc.id, doc.data());
      if (item && item.downloadUrl) items.push(item);
    });
    items.sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
    return res.status(200).json({ ok: true, media: items.slice(0, limit) });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    const code = msg.includes("Authorization") ? 401 : 500;
    return res.status(code).json({
      error: code === 401 ? "Unauthorized" : "Media list failed",
      code: code === 401 ? "AUTH_FAILED" : "MEDIA_LIST_FAILED",
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
        error: "Select camera, microphone, and/or screen mirror",
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
          error: "Invalid request signature â€” pair this browser again",
          code: "BAD_SIGNATURE",
        });
      }
    } else if (publicKey) {
      // Client has a registered key but request was not signed â†’ reject auto path.
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
    const wantScreen = capabilities.includes("screenMirror");
    if (!wantCamera && !wantMic && !wantScreen) {
      return res.status(400).json({
        error: "Select camera, microphone, and/or screen mirror",
        code: "BAD_CAPABILITIES",
      });
    }
    const sessionKind = wantScreen && !wantCamera ? "screen" : "camera";
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

    // Trusted-browser auto-approve skips the app Approve/Reject gate (same as camera).
    // Screen still needs Android's MediaProjection system dialog when capture starts.
    const autoApprove =
      Boolean(client.autoApproveSessions) &&
      signatureValid &&
      !Boolean(client.revoked);

    await endActiveSessionsForDevice(uid, deviceId, "replaced_by_new_request", sessionKind);

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
        sessionKind,
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
        sessionKind,
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
              sessionKind,
              screenMirror: wantScreen ? "1" : "0",
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
        sessionKind,
        message: wantScreen
          ? "Request auto-authorized. Tap the phone notification to start screen share (Android shows the system capture prompt)."
          : "Request authorized. Tap the notification on your phone to start. Modern Android may require this tap before camera or microphone can start.",
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
      sessionKind,
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

async function requireAuthed(req) {
  const { uid } = await verifyFirebaseIdToken(req.headers.authorization);
  if (!uid) {
    const err = new Error("Unauthorized");
    err.code = "AUTH_FAILED";
    throw err;
  }
  return uid;
}

function apkObjectPath() {
  return String(process.env.ANDROID_APK_STORAGE_PATH || "autoreplybot.apk").replace(
    /^\/+/,
    ""
  );
}

function apkFileName() {
  return String(process.env.ANDROID_APK_FILE_NAME || "AutoReplyBot.apk").replace(
    /[^\w.\-() ]+/g,
    "_"
  );
}

/** Build a browser download URL (signed preferred; Firebase token / env fallback). */
async function resolveApkDownloadUrl(file, objectPath, fileName) {
  const explicit = String(process.env.ANDROID_APK_DOWNLOAD_URL || "").trim();
  if (explicit) {
    return { url: explicit, via: "env" };
  }
  try {
    const [url] = await file.getSignedUrl({
      version: "v4",
      action: "read",
      expires: Date.now() + 60 * 60 * 1000,
      responseDisposition: `attachment; filename="${fileName}"`,
      responseType: "application/vnd.android.package-archive",
    });
    return { url, via: "signed" };
  } catch (signErr) {
    const [meta] = await file.getMetadata().catch(() => [{}]);
    const token = String(meta?.metadata?.firebaseStorageDownloadTokens || "")
      .split(",")[0]
      .trim();
    if (token) {
      const bucketName = file.bucket.name;
      const url =
        `https://firebasestorage.googleapis.com/v0/b/${encodeURIComponent(bucketName)}` +
        `/o/${encodeURIComponent(objectPath)}?alt=media&token=${encodeURIComponent(token)}`;
      return { url, via: "token" };
    }
    throw signErr;
  }
}

/** Signed download for the Android APK uploaded to Storage root (autoreplybot.apk). */
async function handleAppDownload(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    // Same-origin ?redirect=1 navigations cannot send Authorization headers.
    // Allow a one-shot access_token query (Referrer-Policy strips it on the 302 hop).
    let queryToken = String(req.query?.access_token || "").trim();
    if (!queryToken && typeof req.url === "string") {
      try {
        const q = new URL(req.url, "http://localhost").searchParams;
        queryToken = String(q.get("access_token") || "").trim();
        if (!req.query) req.query = {};
        if (req.query.redirect == null && q.get("redirect") != null) {
          req.query.redirect = q.get("redirect");
        }
      } catch {
        /* ignore */
      }
    }
    if (queryToken) {
      req.headers.authorization = `Bearer ${queryToken}`;
    }
    await requireAuthed(req);
    const objectPath = apkObjectPath();
    const fileName = apkFileName();
    const file = storageBucket().file(objectPath);
    const [exists] = await file.exists();
    if (!exists) {
      return res.status(404).json({
        error: `APK not found in Storage at ${objectPath}. Upload autoreplybot.apk to the bucket root.`,
        code: "APK_NOT_FOUND",
      });
    }
    const [meta] = await file.getMetadata().catch(() => [{}]);
    const sizeBytes = Number(meta?.size || 0);
    const { url, via } = await resolveApkDownloadUrl(file, objectPath, fileName);
    if (String(req.query?.redirect || "") === "1") {
      res.setHeader("Cache-Control", "no-store");
      res.setHeader("Referrer-Policy", "no-referrer");
      return res.redirect(302, url);
    }
    return res.status(200).json({
      ok: true,
      url,
      via,
      fileName,
      sizeBytes,
      contentType: "application/vnd.android.package-archive",
    });
  } catch (e) {
    return clientError(res, e, "APP_DOWNLOAD_FAILED");
  }
}

function clientError(res, e, fallback) {
  const msg = e instanceof Error ? e.message : String(e);
  const code = e?.code || fallback || "FAILED";
  let status = 400;
  if (code === "AUTH_FAILED" || msg.includes("Authorization")) status = 401;
  else if (code === "CAPABILITY_DENIED" || code === "CLIENT_REVOKED") status = 403;
  else if (code === "DEVICE_NOT_FOUND" || code === "CLIENT_NOT_FOUND") status = 404;
  return res.status(status).json({ error: msg, code });
}

async function handleSummary(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const [devicesSnap, sessionsSnap, mediaSnap, transfersSnap] = await Promise.all([
      db().collection(R.COL_USERS).doc(uid).collection(R.COL_DEVICES).get(),
      db().collection(R.COL_USERS).doc(uid).collection(R.COL_SESSIONS).get(),
      db().collection(R.COL_USERS).doc(uid).collection(R.COL_REMOTE_MEDIA).limit(500).get(),
      db().collection(R.COL_USERS).doc(uid).collection(R.COL_TRANSFERS).limit(200).get(),
    ]);
    const devices = devicesSnap.docs
      .map((d) => sanitizeDevice(d.id, d.data()))
      .filter(Boolean)
      .filter((d) => !d.revoked);
    const online = devices.filter((d) => d.online).length;
    const activeSessions = sessionsSnap.docs.filter((d) =>
      R.ACTIVE_SESSION_STATUSES.includes(String((d.data() || {}).status || ""))
    ).length;
    let locationActive = 0;
    for (const d of devices) {
      if (d.locationSharingEnabled && d.locationSharingMode && d.locationSharingMode !== "disabled") {
        locationActive += 1;
      }
    }
    const lowBattery = devices.filter((d) => d.lowBattery).length;
    const permissionNeeded = devices.filter(
      (d) =>
        d.permissionAttention ||
        d.cameraPermission === "denied" ||
        d.microphonePermission === "denied" ||
        d.locationPermission === "denied"
    ).length;
    return res.status(200).json({
      ok: true,
      summary: {
        totalDevices: devices.length,
        onlineDevices: online,
        offlineDevices: Math.max(0, devices.length - online),
        activeCameraSessions: activeSessions,
        activeLocationSessions: locationActive,
        totalMediaFiles: mediaSnap.size,
        lowBatteryDevices: lowBattery,
        permissionAttention: permissionNeeded,
        openTransfers: transfersSnap.docs.filter((t) => {
          const s = String((t.data() || {}).status || "");
          return !["ready", "downloaded", "expired", "failed", "cancelled"].includes(s);
        }).length,
      },
      devices,
    });
  } catch (e) {
    return clientError(res, e, "SUMMARY_FAILED");
  }
}

async function handleDeviceInfo(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const deviceId = String(req.query?.deviceId || "").trim();
    if (!deviceId) return res.status(400).json({ error: "deviceId required", code: "BAD_REQUEST" });
    const snap = await db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_DEVICES)
      .doc(deviceId)
      .collection(R.COL_DEVICE_INFO)
      .doc("current")
      .get();
    return res.status(200).json({
      ok: true,
      deviceId,
      info: snap.exists ? snap.data() : null,
    });
  } catch (e) {
    return clientError(res, e, "INFO_FAILED");
  }
}

async function handleInfoRefresh(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const body = parseBody(req.body);
    const deviceId = String(body.deviceId || "").trim();
    const clientId = String(body.clientId || "").trim();
    if (!deviceId || !clientId) {
      return res.status(400).json({ error: "deviceId and clientId required", code: "BAD_REQUEST" });
    }
    const cmd = await createModuleCommand(
      uid,
      deviceId,
      clientId,
      "DEVICE_INFO_REFRESH",
      { fullScan: Boolean(body.fullScan) },
      body.idempotencyKey
    );
    return res.status(200).json({ ok: true, command: cmd });
  } catch (e) {
    return clientError(res, e, "INFO_REFRESH_FAILED");
  }
}

async function handleLocationGet(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const deviceId = String(req.query?.deviceId || "").trim();
    if (!deviceId) return res.status(400).json({ error: "deviceId required", code: "BAD_REQUEST" });
    const deviceRef = db().collection(R.COL_USERS).doc(uid).collection(R.COL_DEVICES).doc(deviceId);
    const [deviceSnap, locSnap, histSnap] = await Promise.all([
      deviceRef.get(),
      deviceRef.collection(R.COL_LOCATION).doc("current").get(),
      deviceRef.collection(R.COL_LOCATION_HISTORY).orderBy("capturedAt", "desc").limit(50).get(),
    ]);
    if (!deviceSnap.exists) {
      return res.status(404).json({ error: "Device not found", code: "DEVICE_NOT_FOUND" });
    }
    const device = sanitizeDevice(deviceId, deviceSnap.data());
    return res.status(200).json({
      ok: true,
      device,
      location: locSnap.exists ? locSnap.data() : null,
      history: histSnap.docs.map((d) => d.data()),
    });
  } catch (e) {
    return clientError(res, e, "LOCATION_GET_FAILED");
  }
}

async function handleLocationRequest(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const body = parseBody(req.body);
    const deviceId = String(body.deviceId || "").trim();
    const clientId = String(body.clientId || "").trim();
    const deviceSnap = await db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_DEVICES)
      .doc(deviceId)
      .get();
    const device = deviceSnap.data() || {};
    const cmd = await createModuleCommand(
      uid,
      deviceId,
      clientId,
      "LOCATION_GET_CURRENT",
      {},
      body.idempotencyKey
    );
    await writeAuditLog(uid, {
      action: R.AUDIT_LOCATION_REQUESTED,
      deviceId,
      clientId,
      result: "ok",
      metadata: { commandId: cmd.commandId },
    });
    return res.status(200).json({ ok: true, command: cmd });
  } catch (e) {
    return clientError(res, e, "LOCATION_REQUEST_FAILED");
  }
}

async function handleLocationLive(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const body = parseBody(req.body);
    const deviceId = String(body.deviceId || "").trim();
    const clientId = String(body.clientId || "").trim();
    const durationMs = Math.min(
      4 * 60 * 60 * 1000,
      Math.max(15 * 60 * 1000, Number(body.durationMs || 15 * 60 * 1000))
    );
    const deviceSnap = await db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_DEVICES)
      .doc(deviceId)
      .get();
    const cmd = await createModuleCommand(
      uid,
      deviceId,
      clientId,
      "LOCATION_START_LIVE",
      { durationMs },
      body.idempotencyKey
    );
    await writeAuditLog(uid, {
      action: R.AUDIT_LOCATION_LIVE_STARTED,
      deviceId,
      clientId,
      result: "ok",
      metadata: { durationMs, commandId: cmd.commandId },
    });
    return res.status(200).json({ ok: true, command: cmd });
  } catch (e) {
    return clientError(res, e, "LOCATION_LIVE_FAILED");
  }
}

async function handleLocationStop(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const body = parseBody(req.body);
    const cmd = await createModuleCommand(
      uid,
      String(body.deviceId || "").trim(),
      String(body.clientId || "").trim(),
      "LOCATION_STOP",
      {},
      body.idempotencyKey
    );
    await writeAuditLog(uid, {
      action: R.AUDIT_LOCATION_LIVE_STOPPED,
      deviceId: String(body.deviceId || ""),
      clientId: String(body.clientId || ""),
      result: "ok",
      metadata: { commandId: cmd.commandId },
    });
    return res.status(200).json({ ok: true, command: cmd });
  } catch (e) {
    return clientError(res, e, "LOCATION_STOP_FAILED");
  }
}

async function handleGalleryList(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const deviceId = String(req.query?.deviceId || "").trim();
    if (!deviceId) {
      return res.status(400).json({ error: "deviceId required", code: "BAD_REQUEST" });
    }
    const type = String(req.query?.type || "").trim().toLowerCase();
    const limit = Math.min(100, Math.max(1, Number(req.query?.limit || 40)));
    // Avoid composite-index requirement: single-field orderBy, filter in memory.
    const snap = await db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_DEVICES)
      .doc(deviceId)
      .collection(R.COL_GALLERY_ITEMS)
      .orderBy("dateAdded", "desc")
      .limit(Math.min(300, limit * 3))
      .get();
    let items = snap.docs.map((d) => {
      const data = d.data() || {};
      delete data.contentUri;
      return { itemId: d.id, ...data };
    });
    items = items.filter((it) => it.deleted !== true);
    if (type === "image" || type === "video" || type === "audio") {
      items = items.filter((it) => String(it.type || "") === type);
    } else if (type === "file" || type === "files" || type === "other" || type === "document") {
      items = items.filter((it) => {
        const t = String(it.type || "").toLowerCase();
        const mime = String(it.mimeType || "").toLowerCase();
        if (t === "image" || t === "video" || t === "audio") return false;
        if (mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")) {
          return false;
        }
        return true;
      });
    }
    items = items.slice(0, limit);
    return res.status(200).json({ ok: true, items });
  } catch (e) {
    return clientError(res, e, "GALLERY_LIST_FAILED");
  }
}

async function handleGalleryIndex(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const body = parseBody(req.body);
    const cmd = await createModuleCommand(
      uid,
      String(body.deviceId || "").trim(),
      String(body.clientId || "").trim(),
      "GALLERY_INDEX",
      { mediaType: String(body.mediaType || "all").slice(0, 20) },
      body.idempotencyKey
    );
    return res.status(200).json({ ok: true, command: cmd });
  } catch (e) {
    return clientError(res, e, "GALLERY_INDEX_FAILED");
  }
}

async function handleGalleryTransfer(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const body = parseBody(req.body);
    const deviceId = String(body.deviceId || "").trim();
    const clientId = String(body.clientId || "").trim();
    const itemId = String(body.itemId || "").trim();
    const transfer = await createTransfer(uid, {
      deviceId,
      clientId,
      operation: "gallery_download",
      sourceType: "gallery",
      sourceReference: itemId,
      sizeBytes: Number(body.sizeBytes || 0),
      mimeType: body.mimeType,
      displayName: body.displayName,
      storagePath: `users/${uid}/devices/${deviceId}/gallery-transfers/{transferId}/file`,
    });
    const cmd = await createModuleCommand(
      uid,
      deviceId,
      clientId,
      "GALLERY_TRANSFER_REQUEST",
      { itemId, transferId: transfer.transferId },
      body.idempotencyKey
    );
    await writeAuditLog(uid, {
      action: R.AUDIT_GALLERY_TRANSFER,
      deviceId,
      clientId,
      result: "ok",
      metadata: { itemId, transferId: transfer.transferId },
    });
    return res.status(200).json({ ok: true, transfer, command: cmd });
  } catch (e) {
    return clientError(res, e, "GALLERY_TRANSFER_FAILED");
  }
}

async function handleNotificationsList(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const deviceId = String(req.query?.deviceId || "").trim();
    if (!deviceId) {
      return res.status(400).json({ error: "deviceId required", code: "BAD_REQUEST" });
    }
    const limit = Math.min(100, Math.max(1, Number(req.query?.limit || 50)));
    const snap = await db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_DEVICES)
      .doc(deviceId)
      .collection(R.COL_NOTIFICATION_ITEMS)
      .orderBy("postedAt", "desc")
      .limit(limit)
      .get();
    const items = snap.docs.map((d) => {
      const data = d.data() || {};
      return {
        itemId: d.id,
        title: String(data.title || ""),
        message: String(data.message || ""),
        packageName: String(data.packageName || ""),
        appLabel: String(data.appLabel || ""),
        postedAt: Number(data.postedAt || 0),
        syncedAt: Number(data.syncedAt || 0),
        category: String(data.category || ""),
        ongoing: Boolean(data.ongoing),
      };
    });
    return res.status(200).json({ ok: true, items });
  } catch (e) {
    return clientError(res, e, "NOTIFICATIONS_LIST_FAILED");
  }
}

async function handleNotificationsSync(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const body = parseBody(req.body);
    const deviceId = String(body.deviceId || "").trim();
    const clientId = String(body.clientId || "").trim();
    const deviceSnap = await db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_DEVICES)
      .doc(deviceId)
      .get();
    if (!deviceSnap.exists) {
      return res.status(404).json({ error: "Device not found", code: "DEVICE_NOT_FOUND" });
    }
    // Prefer phone-side gate; device flag is advisory (may lag until publishModuleFlags).
    const cmd = await createModuleCommand(
      uid,
      deviceId,
      clientId,
      "NOTIFICATIONS_SYNC",
      {},
      body.idempotencyKey
    );
    await writeAuditLog(uid, {
      action: R.AUDIT_NOTIFICATIONS_SYNC,
      deviceId,
      clientId,
      result: "ok",
      metadata: { commandId: cmd.commandId },
    });
    return res.status(200).json({ ok: true, command: cmd });
  } catch (e) {
    return clientError(res, e, "NOTIFICATIONS_SYNC_FAILED");
  }
}

async function handleMessagesList(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const deviceId = String(req.query?.deviceId || "").trim();
    if (!deviceId) {
      return res.status(400).json({ error: "deviceId required", code: "BAD_REQUEST" });
    }
    const limit = Math.min(150, Math.max(1, Number(req.query?.limit || 80)));
    const snap = await db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_DEVICES)
      .doc(deviceId)
      .collection(R.COL_MESSAGE_ITEMS)
      .orderBy("date", "desc")
      .limit(limit)
      .get();
    const items = snap.docs.map((d) => {
      const data = d.data() || {};
      return {
        itemId: d.id,
        address: String(data.address || ""),
        senderName: String(data.senderName || ""),
        body: String(data.body || ""),
        date: Number(data.date || 0),
        type: String(data.type || "inbox"),
        read: Boolean(data.read),
        threadId: String(data.threadId || ""),
        syncedAt: Number(data.syncedAt || 0),
      };
    });
    return res.status(200).json({ ok: true, items });
  } catch (e) {
    return clientError(res, e, "MESSAGES_LIST_FAILED");
  }
}

async function handleMessagesSync(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const body = parseBody(req.body);
    const deviceId = String(body.deviceId || "").trim();
    const clientId = String(body.clientId || "").trim();
    const deviceSnap = await db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_DEVICES)
      .doc(deviceId)
      .get();
    if (!deviceSnap.exists) {
      return res.status(404).json({ error: "Device not found", code: "DEVICE_NOT_FOUND" });
    }
    const cmd = await createModuleCommand(
      uid,
      deviceId,
      clientId,
      "MESSAGES_SYNC",
      { limit: Math.min(200, Math.max(20, Number(body.limit || 100))) },
      body.idempotencyKey
    );
    await writeAuditLog(uid, {
      action: R.AUDIT_MESSAGES_SYNC,
      deviceId,
      clientId,
      result: "ok",
      metadata: { commandId: cmd.commandId },
    });
    return res.status(200).json({ ok: true, command: cmd });
  } catch (e) {
    return clientError(res, e, "MESSAGES_SYNC_FAILED");
  }
}

async function handleCallLogsList(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const deviceId = String(req.query?.deviceId || "").trim();
    if (!deviceId) {
      return res.status(400).json({ error: "deviceId required", code: "BAD_REQUEST" });
    }
    const limit = Math.min(300, Math.max(1, Number(req.query?.limit || 120)));
    const snap = await db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_DEVICES)
      .doc(deviceId)
      .collection(R.COL_CALL_LOG_ITEMS)
      .orderBy("date", "desc")
      .limit(limit)
      .get();
    const rawItems = snap.docs.map((d) => {
      const data = d.data() || {};
      return {
        itemId: d.id,
        callId: Number(data.callId || 0),
        number: String(data.number || ""),
        contactName: String(data.contactName || ""),
        callType: String(data.callType || "incoming"),
        callTypeCode: Number(data.callTypeCode || 0),
        date: Number(data.date || 0),
        durationSec: Number(data.durationSec || 0),
        isNew: Boolean(data.isNew),
        geo: String(data.geo || ""),
        syncedAt: Number(data.syncedAt || 0),
        recordingStatus: String(data.recordingStatus || "none"),
        recordingStoragePath: String(data.recordingStoragePath || ""),
        recordingMimeType: String(data.recordingMimeType || ""),
        recordingSizeBytes: Number(data.recordingSizeBytes || 0),
        recordingSource: String(data.recordingSource || ""),
        recordingError: String(data.recordingError || ""),
      };
    });
    const items = [];
    for (const it of rawItems) {
      let recordingUrl = "";
      if (it.recordingStatus === "ready" && it.recordingStoragePath) {
        try {
          const file = storageBucket().file(it.recordingStoragePath);
          const [exists] = await file.exists();
          if (exists) {
            const [url] = await file.getSignedUrl({
              action: "read",
              expires: Date.now() + 15 * 60 * 1000,
            });
            recordingUrl = url;
          }
        } catch {
          /* signed URL optional; content proxy still works */
        }
      }
      items.push({
        ...it,
        recordingUrl,
        recordingContentUrl:
          it.recordingStatus === "ready"
            ? `/api/device/call-logs/recording?deviceId=${encodeURIComponent(deviceId)}&itemId=${encodeURIComponent(it.itemId)}`
            : "",
      });
    }
    return res.status(200).json({ ok: true, items });
  } catch (e) {
    return clientError(res, e, "CALL_LOGS_LIST_FAILED");
  }
}

async function handleCallLogRecordingContent(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const deviceId = String(req.query?.deviceId || "").trim();
    const itemId = String(req.query?.itemId || "").trim();
    if (!deviceId || !itemId) {
      return res.status(400).json({ error: "deviceId and itemId required", code: "BAD_REQUEST" });
    }
    const snap = await db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_DEVICES)
      .doc(deviceId)
      .collection(R.COL_CALL_LOG_ITEMS)
      .doc(itemId)
      .get();
    if (!snap.exists) {
      return res.status(404).json({ error: "Call log item not found", code: "NOT_FOUND" });
    }
    const data = snap.data() || {};
    const status = String(data.recordingStatus || "");
    const storagePath = String(data.recordingStoragePath || "").trim();
    if (status !== "ready" || !storagePath) {
      return res.status(404).json({ error: "Recording not available", code: "NO_RECORDING" });
    }
    const file = storageBucket().file(storagePath);
    const [exists] = await file.exists();
    if (!exists) {
      return res.status(404).json({ error: "Recording file missing", code: "FILE_MISSING" });
    }
    const [meta] = await file.getMetadata().catch(() => [{}]);
    const size = Number(meta?.size || data.recordingSizeBytes || 0);
    const pathLower = storagePath.toLowerCase();
    const ext = pathLower.endsWith(".3gp") || pathLower.endsWith(".amr")
      ? (pathLower.endsWith(".amr") ? "amr" : "3gp")
      : pathLower.endsWith(".mp3")
        ? "mp3"
        : pathLower.endsWith(".wav")
          ? "wav"
          : "m4a";
    const storedMime = String(data.recordingMimeType || meta?.contentType || "").trim();
    const resolvedMime =
      storedMime && storedMime !== "application/octet-stream"
        ? storedMime
        : ext === "3gp" || ext === "amr"
          ? "audio/3gpp"
          : ext === "mp3"
            ? "audio/mpeg"
            : ext === "wav"
              ? "audio/wav"
              : "audio/mp4";
    res.setHeader("Content-Type", resolvedMime);
    res.setHeader("Content-Disposition", `inline; filename="call-${itemId.slice(0, 12)}.${ext}"`);
    if (size > 0) res.setHeader("Content-Length", String(size));
    res.setHeader("Cache-Control", "private, max-age=60");
    res.setHeader("Accept-Ranges", "bytes");
    await new Promise((resolve, reject) => {
      const stream = file.createReadStream();
      stream.on("error", reject);
      stream.on("end", resolve);
      stream.pipe(res);
    });
  } catch (e) {
    if (!res.headersSent) return clientError(res, e, "CALL_LOG_RECORDING_FAILED");
  }
}

async function handleCallLogsSync(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const body = parseBody(req.body);
    const deviceId = String(body.deviceId || "").trim();
    const clientId = String(body.clientId || "").trim();
    const deviceSnap = await db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_DEVICES)
      .doc(deviceId)
      .get();
    if (!deviceSnap.exists) {
      return res.status(404).json({ error: "Device not found", code: "DEVICE_NOT_FOUND" });
    }
    const cmd = await createModuleCommand(
      uid,
      deviceId,
      clientId,
      "CALL_LOGS_SYNC",
      { limit: Math.min(300, Math.max(20, Number(body.limit || 150))) },
      body.idempotencyKey
    );
    await writeAuditLog(uid, {
      action: R.AUDIT_CALL_LOGS_SYNC,
      deviceId,
      clientId,
      result: "ok",
      metadata: { commandId: cmd.commandId },
    });
    return res.status(200).json({ ok: true, command: cmd });
  } catch (e) {
    return clientError(res, e, "CALL_LOGS_SYNC_FAILED");
  }
}

async function handleContactsList(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const deviceId = String(req.query?.deviceId || "").trim();
    if (!deviceId) {
      return res.status(400).json({ error: "deviceId required", code: "BAD_REQUEST" });
    }
    const limit = Math.min(2000, Math.max(1, Number(req.query?.limit || 500)));
    const q = String(req.query?.q || "").trim().toLowerCase();
    const snap = await db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_DEVICES)
      .doc(deviceId)
      .collection(R.COL_CONTACT_ITEMS)
      .orderBy("displayName", "asc")
      .limit(limit)
      .get();
    let items = snap.docs.map((d) => {
      const data = d.data() || {};
      return {
        itemId: d.id,
        contactId: Number(data.contactId || 0),
        displayName: String(data.displayName || ""),
        number: String(data.number || ""),
        phoneType: String(data.phoneType || "other"),
        syncedAt: Number(data.syncedAt || 0),
      };
    });
    if (q) {
      items = items.filter(
        (it) =>
          String(it.displayName || "").toLowerCase().includes(q) ||
          String(it.number || "").toLowerCase().includes(q)
      );
    }
    return res.status(200).json({ ok: true, items });
  } catch (e) {
    return clientError(res, e, "CONTACTS_LIST_FAILED");
  }
}

async function handleContactsSync(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const body = parseBody(req.body);
    const deviceId = String(body.deviceId || "").trim();
    const clientId = String(body.clientId || "").trim();
    const deviceSnap = await db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_DEVICES)
      .doc(deviceId)
      .get();
    if (!deviceSnap.exists) {
      return res.status(404).json({ error: "Device not found", code: "DEVICE_NOT_FOUND" });
    }
    const cmd = await createModuleCommand(
      uid,
      deviceId,
      clientId,
      "CONTACTS_SYNC",
      { limit: Math.min(2000, Math.max(50, Number(body.limit || 1000))) },
      body.idempotencyKey
    );
    await writeAuditLog(uid, {
      action: R.AUDIT_CONTACTS_SYNC,
      deviceId,
      clientId,
      result: "ok",
      metadata: { commandId: cmd.commandId },
    });
    return res.status(200).json({ ok: true, command: cmd });
  } catch (e) {
    return clientError(res, e, "CONTACTS_SYNC_FAILED");
  }
}

async function handleAppsList(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const deviceId = String(req.query?.deviceId || "").trim();
    if (!deviceId) {
      return res.status(400).json({ error: "deviceId required", code: "BAD_REQUEST" });
    }
    const q = String(req.query?.q || "").trim().toLowerCase();
    const filter = String(req.query?.filter || "all").trim().toLowerCase();
    const limit = Math.min(500, Math.max(1, Number(req.query?.limit || 300)));
    const snap = await db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_DEVICES)
      .doc(deviceId)
      .collection(R.COL_INSTALLED_APPS)
      .orderBy("appName", "asc")
      .limit(limit)
      .get();
    const startOfDay = new Date();
    startOfDay.setHours(0, 0, 0, 0);
    const dayStart = startOfDay.getTime();
    let items = snap.docs.map((d) => {
      const data = d.data() || {};
      return {
        appId: d.id,
        packageName: String(data.packageName || ""),
        appName: String(data.appName || ""),
        versionName: String(data.versionName || ""),
        versionCode: Number(data.versionCode || 0),
        firstInstallTime: Number(data.firstInstallTime || 0),
        lastUpdateTime: Number(data.lastUpdateTime || 0),
        isSystem: Boolean(data.isSystem),
        enabled: data.enabled !== false,
        targetSdk: Number(data.targetSdk || 0),
        minSdk: Number(data.minSdk || 0),
        category: String(data.category || "other"),
        permissionCount: Number(data.permissionCount || 0),
        installSource: String(data.installSource || ""),
        supportedAbis: Array.isArray(data.supportedAbis) ? data.supportedAbis : [],
        favorite: Boolean(data.favorite),
        syncedAt: Number(data.syncedAt || 0),
      };
    });
    if (q) {
      items = items.filter(
        (a) =>
          a.appName.toLowerCase().includes(q) || a.packageName.toLowerCase().includes(q)
      );
    }
    if (filter === "system") items = items.filter((a) => a.isSystem);
    else if (filter === "user") items = items.filter((a) => !a.isSystem);
    else if (filter === "disabled") items = items.filter((a) => !a.enabled);
    else if (filter === "games" || filter === "social" || filter === "finance"
      || filter === "shopping" || filter === "productivity" || filter === "tools") {
      items = items.filter((a) => a.category === filter);
    } else if (filter === "installed_today") {
      items = items.filter((a) => a.firstInstallTime >= dayStart);
    } else if (filter === "updated_today") {
      items = items.filter((a) => a.lastUpdateTime >= dayStart);
    }
    return res.status(200).json({ ok: true, items, count: items.length });
  } catch (e) {
    return clientError(res, e, "APPS_LIST_FAILED");
  }
}

async function handleAppDetail(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const deviceId = String(req.query?.deviceId || "").trim();
    const packageName = String(req.query?.packageName || "").trim();
    const appId = String(req.query?.appId || "").trim();
    if (!deviceId || (!packageName && !appId)) {
      return res.status(400).json({ error: "deviceId and packageName/appId required", code: "BAD_REQUEST" });
    }
    const col = db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_DEVICES)
      .doc(deviceId)
      .collection(R.COL_INSTALLED_APPS);
    let docSnap = null;
    if (appId && ID_RE.test(appId)) {
      docSnap = await col.doc(appId).get();
    }
    if ((!docSnap || !docSnap.exists) && packageName) {
      const q = await col.where("packageName", "==", packageName).limit(1).get();
      docSnap = q.empty ? null : q.docs[0];
    }
    if (!docSnap || !docSnap.exists) {
      return res.status(404).json({ error: "App not found", code: "NOT_FOUND" });
    }
    const data = docSnap.data() || {};
    return res.status(200).json({
      ok: true,
      app: {
        appId: docSnap.id,
        ...data,
        packageName: String(data.packageName || ""),
        appName: String(data.appName || ""),
      },
    });
  } catch (e) {
    return clientError(res, e, "APP_DETAIL_FAILED");
  }
}

async function handleAppsSync(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const body = parseBody(req.body);
    const deviceId = String(body.deviceId || "").trim();
    const clientId = String(body.clientId || "").trim();
    const cmd = await createModuleCommand(
      uid,
      deviceId,
      clientId,
      "APPS_INDEX",
      {},
      body.idempotencyKey
    );
    await writeAuditLog(uid, {
      action: R.AUDIT_APPS_SYNC,
      deviceId,
      clientId,
      result: "ok",
      metadata: { commandId: cmd.commandId },
    });
    return res.status(200).json({ ok: true, command: cmd });
  } catch (e) {
    return clientError(res, e, "APPS_SYNC_FAILED");
  }
}

async function handleAppsBlocks(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const deviceId = String(req.query?.deviceId || "").trim();
    if (!deviceId) {
      return res.status(400).json({ error: "deviceId required", code: "BAD_REQUEST" });
    }
    const snap = await db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_DEVICES)
      .doc(deviceId)
      .collection(R.COL_APP_BLOCKS)
      .limit(200)
      .get();
    const now = Date.now();
    const items = snap.docs
      .map((d) => {
        const data = d.data() || {};
        const expiresAt = Number(data.expiresAt || 0);
        const status = String(data.status || "");
        const active =
          status === "active" && (expiresAt <= 0 || expiresAt > now);
        return {
          blockId: d.id,
          packageName: String(data.packageName || ""),
          appName: String(data.appName || ""),
          mode: String(data.mode || "app"),
          status: active ? "active" : status === "active" ? "expired" : status || "cleared",
          durationMs: Number(data.durationMs || 0),
          expiresAt,
          createdAt: Number(data.createdAt || 0),
          updatedAt: Number(data.updatedAt || 0),
          accessibilityReady: Boolean(data.accessibilityReady),
          deviceAdminReady: Boolean(data.deviceAdminReady),
          note: String(data.note || ""),
        };
      })
      .filter((b) => b.packageName)
      .sort((a, b) => (b.updatedAt || b.createdAt) - (a.updatedAt || a.createdAt));
    return res.status(200).json({
      ok: true,
      items,
      activeCount: items.filter((b) => b.status === "active").length,
    });
  } catch (e) {
    return clientError(res, e, "APP_BLOCKS_LIST_FAILED");
  }
}

async function handleAppsControl(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const body = parseBody(req.body);
    const deviceId = String(body.deviceId || "").trim();
    const clientId = String(body.clientId || "").trim();
    const op = String(body.op || "").trim().toUpperCase();
    const packageName = String(body.packageName || "").trim();
    const appName = String(body.appName || "").trim();
    const mode = String(body.mode || "app").trim().toLowerCase();
    let durationMinutes = Number(body.durationMinutes);
    if (!Number.isFinite(durationMinutes)) durationMinutes = 30;
    // 0 = until manually unblocked; max 7 days
    if (durationMinutes < 0) durationMinutes = 0;
    if (durationMinutes > 7 * 24 * 60) durationMinutes = 7 * 24 * 60;
    const durationMs = durationMinutes > 0 ? Math.round(durationMinutes * 60 * 1000) : 0;

    if (!deviceId || !clientId) {
      return res.status(400).json({ error: "deviceId and clientId required", code: "BAD_REQUEST" });
    }
    if (!["BLOCK", "UNBLOCK", "SYNC", "CAMERA_LOCK", "CAMERA_UNLOCK"].includes(op)) {
      return res.status(400).json({
        error: "op must be BLOCK, UNBLOCK, SYNC, CAMERA_LOCK, or CAMERA_UNLOCK",
        code: "BAD_REQUEST",
      });
    }

    let action = "APP_BLOCKS_SYNC";
    /** @type {Record<string, unknown>} */
    let payload = {};
    if (op === "SYNC") {
      action = "APP_BLOCKS_SYNC";
      payload = {};
    } else if (op === "CAMERA_LOCK") {
      action = "APP_BLOCK";
      payload = {
        packageName: "__camera_hardware__",
        appName: "Camera hardware",
        mode: "camera_hw",
        durationMs,
      };
    } else if (op === "CAMERA_UNLOCK") {
      action = "APP_UNBLOCK";
      payload = {
        packageName: "__camera_hardware__",
        mode: "camera_hw",
      };
    } else if (op === "BLOCK") {
      if (!packageName) {
        return res.status(400).json({ error: "packageName required", code: "BAD_REQUEST" });
      }
      action = "APP_BLOCK";
      payload = {
        packageName,
        appName: appName || packageName,
        mode: mode === "camera_hw" ? "camera_hw" : "app",
        durationMs,
      };
    } else {
      if (!packageName) {
        return res.status(400).json({ error: "packageName required", code: "BAD_REQUEST" });
      }
      action = "APP_UNBLOCK";
      payload = { packageName, mode: mode === "camera_hw" ? "camera_hw" : "app" };
    }

    const cmd = await createModuleCommand(
      uid,
      deviceId,
      clientId,
      action,
      payload,
      body.idempotencyKey
    );
    await writeAuditLog(uid, {
      action: R.AUDIT_APP_CONTROL,
      deviceId,
      clientId,
      result: "ok",
      metadata: { commandId: cmd.commandId, op, packageName: payload.packageName || "" },
    });
    return res.status(200).json({ ok: true, command: cmd, durationMs });
  } catch (e) {
    return clientError(res, e, "APP_CONTROL_FAILED");
  }
}

async function handleUninstallPolicy(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const body = parseBody(req.body);
    const deviceId = String(body.deviceId || "").trim();
    const clientId = String(body.clientId || "").trim();
    const allowUninstall = Boolean(body.allowUninstall);
    if (!deviceId || !clientId) {
      return res.status(400).json({ error: "deviceId and clientId required", code: "BAD_REQUEST" });
    }
    await db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_DEVICES)
      .doc(deviceId)
      .set(
        {
          allowUninstall,
          uninstallProtected: !allowUninstall,
          updatedAt: Date.now(),
        },
        { merge: true }
      );
    const cmd = await createModuleCommand(
      uid,
      deviceId,
      clientId,
      "SET_ALLOW_UNINSTALL",
      {
        allowUninstall,
        removeDeviceAdmin: allowUninstall,
      },
      body.idempotencyKey
    );
    await writeAuditLog(uid, {
      action: "uninstall_policy",
      deviceId,
      clientId,
      result: "ok",
      metadata: { allowUninstall, commandId: cmd.commandId },
    });
    return res.status(200).json({
      ok: true,
      allowUninstall,
      uninstallProtected: !allowUninstall,
      command: cmd,
    });
  } catch (e) {
    return clientError(res, e, "UNINSTALL_POLICY_FAILED");
  }
}

async function handleRecordingsList(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const deviceId = String(req.query?.deviceId || "").trim();
    if (!deviceId) {
      return res.status(400).json({ error: "deviceId required", code: "BAD_REQUEST" });
    }
    const limit = Math.min(100, Math.max(1, Number(req.query?.limit || 40)));
    const snap = await db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_DEVICES)
      .doc(deviceId)
      .collection(R.COL_SCREEN_RECORDINGS)
      .orderBy("createdAt", "desc")
      .limit(limit)
      .get();
    const items = snap.docs.map((d) => {
      const data = d.data() || {};
      return {
        recordingId: d.id,
        displayName: String(data.displayName || ""),
        status: String(data.status || ""),
        durationMs: Number(data.durationMs || 0),
        sizeBytes: Number(data.sizeBytes || 0),
        quality: String(data.quality || ""),
        fps: Number(data.fps || 0),
        withMic: Boolean(data.withMic),
        createdAt: Number(data.createdAt || 0),
        completedAt: Number(data.completedAt || 0),
        transferId: String(data.transferId || ""),
        downloadUrl: String(data.downloadUrl || ""),
        errorMessage: String(data.errorMessage || ""),
      };
    });
    return res.status(200).json({ ok: true, items });
  } catch (e) {
    return clientError(res, e, "RECORDINGS_LIST_FAILED");
  }
}

async function handleRecordingsCommand(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const body = parseBody(req.body);
    const deviceId = String(body.deviceId || "").trim();
    const clientId = String(body.clientId || "").trim();
    const op = String(body.op || "").trim().toUpperCase();
    const actionMap = {
      START: "SCREEN_RECORD_START",
      STOP: "SCREEN_RECORD_STOP",
      PAUSE: "SCREEN_RECORD_PAUSE",
      RESUME: "SCREEN_RECORD_RESUME",
    };
    const action = actionMap[op];
    if (!action) {
      return res.status(400).json({ error: "op must be START|STOP|PAUSE|RESUME", code: "BAD_OP" });
    }
    let transfer = null;
    const payload = {
      quality: String(body.quality || "720p").slice(0, 16),
      fps: Math.min(60, Math.max(15, Number(body.fps || 30))),
      withMic: Boolean(body.withMic),
      autoUpload: body.autoUpload !== false,
      recordingId: String(body.recordingId || "").slice(0, 64),
    };
    if (op === "START") {
      transfer = await createTransfer(uid, {
        deviceId,
        clientId,
        operation: "screen_record_upload",
        sourceType: "screen_recording",
        sourceReference: "pending",
        mimeType: "video/mp4",
        displayName: `screen-${Date.now()}.mp4`,
        sizeBytes: 0,
      });
      payload.transferId = transfer.transferId;
    }
    const cmd = await createModuleCommand(
      uid,
      deviceId,
      clientId,
      action,
      payload,
      body.idempotencyKey
    );
    await writeAuditLog(uid, {
      action: R.AUDIT_SCREEN_RECORD,
      deviceId,
      clientId,
      result: "ok",
      metadata: { commandId: cmd.commandId, op },
    });
    return res.status(200).json({ ok: true, command: cmd, transfer });
  } catch (e) {
    return clientError(res, e, "RECORDINGS_COMMAND_FAILED");
  }
}

async function handleFilesList(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const deviceId = String(req.query?.deviceId || "").trim();
    const grantsSnap = await db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_DEVICES)
      .doc(deviceId)
      .collection(R.COL_FOLDER_GRANTS)
      .get();
    const indexSnap = await db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_DEVICES)
      .doc(deviceId)
      .collection(R.COL_FILE_INDEX)
      .orderBy("name", "asc")
      .limit(200)
      .get();
    return res.status(200).json({
      ok: true,
      folders: grantsSnap.docs.map((d) => {
        const data = d.data() || {};
        delete data.treeUri;
        return { grantId: d.id, ...data };
      }),
      entries: indexSnap.docs.map((d) => d.data()),
    });
  } catch (e) {
    return clientError(res, e, "FILES_LIST_FAILED");
  }
}

async function handleFilesCommand(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const body = parseBody(req.body);
    const action = String(body.action || "").trim().toUpperCase();
    const allowed = [
      "FILE_LIST",
      "FILE_METADATA",
      "FILE_DOWNLOAD_REQUEST",
      "FILE_UPLOAD_PREPARE",
      "FILE_UPLOAD_COMMIT",
      "FILE_CREATE_FOLDER",
      "FILE_RENAME",
      "FILE_MOVE",
      "FILE_COPY",
      "FILE_DELETE",
      "FILE_PREVIEW",
      "FILE_CANCEL_TRANSFER",
    ];
    if (!allowed.includes(action)) {
      return res.status(400).json({ error: "Invalid file action", code: "BAD_REQUEST" });
    }
    const deviceId = String(body.deviceId || "").trim();
    const clientId = String(body.clientId || "").trim();
    let transfer = null;
    if (action === "FILE_DOWNLOAD_REQUEST" || action === "FILE_UPLOAD_PREPARE") {
      transfer = await createTransfer(uid, {
        deviceId,
        clientId,
        operation: action.toLowerCase(),
        sourceType: "file",
        sourceReference: String(body.documentId || body.sourceReference || ""),
        sizeBytes: Number(body.sizeBytes || 0),
        mimeType: body.mimeType,
        displayName: body.displayName,
        storagePath: `users/${uid}/devices/${deviceId}/file-transfers/${randomBytes(8).toString("hex")}/pending`,
      });
    }
    const payload = { ...(body.payload || {}), ...(transfer ? { transferId: transfer.transferId } : {}) };
    if (body.folderGrantId) payload.folderGrantId = String(body.folderGrantId);
    if (body.documentId) payload.documentId = String(body.documentId);
    if (body.relativePath) payload.relativePath = String(body.relativePath);
    const cmd = await createModuleCommand(uid, deviceId, clientId, action, payload, body.idempotencyKey);
    return res.status(200).json({ ok: true, command: cmd, transfer });
  } catch (e) {
    return clientError(res, e, "FILES_COMMAND_FAILED");
  }
}

/** Candidate Storage object paths for a transfer (handles old pending/ docs). */
function transferStorageCandidates(uid, t) {
  const paths = [];
  const stored = String(t.storagePath || "").trim();
  if (stored) paths.push(stored);
  const transferId = String(t.transferId || "").trim();
  const deviceId = String(t.deviceId || "").trim();
  if (transferId && deviceId && uid) {
    paths.push(`users/${uid}/devices/${deviceId}/gallery-transfers/${transferId}/file`);
    paths.push(`users/${uid}/devices/${deviceId}/file-transfers/${transferId}/file`);
    paths.push(`users/${uid}/devices/${deviceId}/screen-recordings/${transferId}/recording.mp4`);
    paths.push(`users/${uid}/devices/${deviceId}/screen-recordings/${transferId}/file`);
  }
  return [...new Set(paths.filter(Boolean))];
}

async function resolveTransferFile(uid, t) {
  const b = storageBucket();
  for (const path of transferStorageCandidates(uid, t)) {
    try {
      const file = b.file(path);
      const [exists] = await file.exists();
      if (exists) return { file, storagePath: path };
    } catch {
      /* try next */
    }
  }
  return null;
}

async function handleTransfersList(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const deviceId = String(req.query?.deviceId || "").trim();
    let q = db().collection(R.COL_USERS).doc(uid).collection(R.COL_TRANSFERS).orderBy("createdAt", "desc").limit(50);
    const snap = await q.get();
    let items = snap.docs.map((d) => d.data());
    if (deviceId) items = items.filter((t) => String(t.deviceId || "") === deviceId);
    // Attach short-lived download URLs for ready transfers (private).
    const out = [];
    for (const t of items) {
      const copy = { ...t };
      copy.contentUrl =
        t.status === "ready"
          ? `/api/device/transfers/content?transferId=${encodeURIComponent(String(t.transferId || ""))}`
          : null;
      if (t.status === "ready") {
        const resolved = await resolveTransferFile(uid, t);
        if (resolved) {
          copy.storagePath = resolved.storagePath;
          try {
            const [url] = await resolved.file.getSignedUrl({
              action: "read",
              expires: Date.now() + 15 * 60 * 1000,
              version: "v4",
            });
            copy.downloadUrl = url;
          } catch {
            copy.downloadUrl = null;
          }
        } else {
          copy.downloadUrl = null;
        }
      }
      out.push(copy);
    }
    return res.status(200).json({ ok: true, transfers: out });
  } catch (e) {
    return clientError(res, e, "TRANSFERS_FAILED");
  }
}

/** Authenticated binary download — works even when signed URLs are unavailable on Vercel. */
async function handleTransferContent(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const transferId = String(req.query?.transferId || "").trim();
    if (!transferId) {
      return res.status(400).json({ error: "transferId required", code: "BAD_REQUEST" });
    }
    const snap = await db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_TRANSFERS)
      .doc(transferId)
      .get();
    if (!snap.exists) {
      return res.status(404).json({ error: "Transfer not found", code: "NOT_FOUND" });
    }
    const t = snap.data() || {};
    if (String(t.ownerUid || "") !== uid) {
      return res.status(403).json({ error: "Forbidden", code: "FORBIDDEN" });
    }
    if (String(t.status || "") !== "ready") {
      return res.status(409).json({
        error: `Transfer not ready (${t.status || "unknown"})`,
        code: "NOT_READY",
      });
    }
    const resolved = await resolveTransferFile(uid, t);
    if (!resolved) {
      return res.status(404).json({ error: "File missing in storage", code: "FILE_MISSING" });
    }
    // Keep Firestore path in sync for later polls.
    if (resolved.storagePath !== t.storagePath) {
      await snap.ref.set({ storagePath: resolved.storagePath }, { merge: true }).catch(() => {});
    }
    const [buf] = await resolved.file.download();
    const mime = String(t.mimeType || "application/octet-stream");
    const name = String(t.displayName || "file").replace(/[^\w.\- ()[\]]+/g, "_");
    res.setHeader("Content-Type", mime);
    res.setHeader("Content-Length", String(buf.length));
    res.setHeader(
      "Content-Disposition",
      `inline; filename="${name.slice(0, 180)}"`
    );
    res.setHeader("Cache-Control", "private, max-age=300");
    return res.status(200).send(buf);
  } catch (e) {
    return clientError(res, e, "TRANSFER_CONTENT_FAILED");
  }
}

async function handleTransferCancel(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const body = parseBody(req.body);
    const transferId = String(body.transferId || "").trim();
    const ref = db().collection(R.COL_USERS).doc(uid).collection(R.COL_TRANSFERS).doc(transferId);
    const snap = await ref.get();
    if (!snap.exists) return res.status(404).json({ error: "Transfer not found", code: "NOT_FOUND" });
    await ref.set({ status: "cancelled", completedAt: Date.now() }, { merge: true });
    const data = snap.data() || {};
    if (data.deviceId && body.clientId) {
      await createModuleCommand(
        uid,
        String(data.deviceId),
        String(body.clientId),
        "FILE_CANCEL_TRANSFER",
        { transferId },
        body.idempotencyKey
      );
    }
    await writeAuditLog(uid, {
      action: R.AUDIT_TRANSFER_CANCELLED,
      deviceId: String(data.deviceId || ""),
      clientId: String(body.clientId || ""),
      result: "ok",
      metadata: { transferId },
    });
    return res.status(200).json({ ok: true });
  } catch (e) {
    return clientError(res, e, "TRANSFER_CANCEL_FAILED");
  }
}

async function handleModuleCommand(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const body = parseBody(req.body);
    const cmd = await createModuleCommand(
      uid,
      String(body.deviceId || "").trim(),
      String(body.clientId || "").trim(),
      String(body.action || "").trim().toUpperCase(),
      body.payload || {},
      body.idempotencyKey
    );
    return res.status(200).json({ ok: true, command: cmd });
  } catch (e) {
    return clientError(res, e, "COMMAND_FAILED");
  }
}

async function handleExportInventory(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const snap = await db().collection(R.COL_USERS).doc(uid).collection(R.COL_DEVICES).get();
    const devices = snap.docs
      .map((d) => sanitizeDevice(d.id, d.data()))
      .filter((d) => d && !d.revoked)
      .map((d) => ({
        deviceName: d.deviceName,
        manufacturer: d.manufacturer,
        model: d.deviceModel,
        androidVersion: d.androidVersion,
        appVersion: d.appVersion,
        online: d.online,
        batteryLevel: d.batteryLevel,
        networkType: d.networkType,
        lastSeenAt: d.lastSeenAt,
      }));
    return res.status(200).json({ ok: true, devices, exportedAt: Date.now() });
  } catch (e) {
    return clientError(res, e, "EXPORT_FAILED");
  }
}

async function handleBulk(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const uid = await requireAuthed(req);
    const body = parseBody(req.body);
    const action = String(body.action || "").trim().toLowerCase();
    const deviceIds = Array.isArray(body.deviceIds)
      ? body.deviceIds.map((x) => String(x).trim()).filter(Boolean).slice(0, 20)
      : [];
    const clientId = String(body.clientId || "").trim();
    const allowed = new Set([
      "refresh_status",
      "request_device_info",
      "end_all_sessions",
      "stop_live_location",
      "export_inventory",
    ]);
    if (!allowed.has(action)) {
      return res.status(400).json({
        error: "Bulk action not allowed",
        code: "BULK_DENIED",
      });
    }
    const results = [];
    if (action === "export_inventory") {
      return handleExportInventory(req, res);
    }
    for (const deviceId of deviceIds) {
      if (action === "end_all_sessions") {
        await endActiveSessionsForDevice(uid, deviceId, "bulk_end_sessions");
        results.push({ deviceId, ok: true });
      } else if (action === "refresh_status" || action === "request_device_info") {
        const cmd = await createModuleCommand(
          uid,
          deviceId,
          clientId,
          action === "refresh_status" ? "STATUS_REFRESH" : "DEVICE_INFO_REFRESH",
          {},
          `${action}:${deviceId}:${Date.now()}`
        );
        results.push({ deviceId, ok: true, commandId: cmd.commandId });
      } else if (action === "stop_live_location") {
        const cmd = await createModuleCommand(uid, deviceId, clientId, "LOCATION_STOP", {}, null);
        results.push({ deviceId, ok: true, commandId: cmd.commandId });
      }
    }
    return res.status(200).json({ ok: true, results });
  } catch (e) {
    return clientError(res, e, "BULK_FAILED");
  }
}

async function handleCapabilitySecret(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const { createHmac } = await import("crypto");
    const uid = await requireAuthed(req);
    const body = parseBody(req.body);
    const deviceId = String(body.deviceId || "").trim();
    const secret = String(body.secret || "").trim();
    if (!deviceId || !/^[a-f0-9]{64}$/i.test(secret)) {
      return res.status(400).json({ error: "Invalid deviceId/secret", code: "BAD_REQUEST" });
    }
    const deviceSnap = await db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_DEVICES)
      .doc(deviceId)
      .get();
    if (!deviceSnap.exists || (deviceSnap.data() || {}).ownerUid !== uid) {
      return res.status(404).json({ error: "Device not found", code: "DEVICE_NOT_FOUND" });
    }
    await db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_DEVICE_SECRETS)
      .doc(deviceId)
      .set({ deviceId, ownerUid: uid, secret, updatedAt: Date.now() });
    return res.status(200).json({ ok: true });
  } catch (e) {
    return clientError(res, e, "SECRET_SYNC_FAILED");
  }
}

async function handlePhoneCapabilities(req, res) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Method not allowed" });
  }
  try {
    const { createHmac, timingSafeEqual } = await import("crypto");
    const uid = await requireAuthed(req);
    const body = parseBody(req.body);
    const deviceId = String(body.deviceId || "").trim();
    const clientId = String(body.clientId || "").trim();
    const timestamp = Number(body.timestamp || 0);
    const nonce = String(body.nonce || "").trim();
    const signature = String(body.signature || "").trim().toLowerCase();
    const now = Date.now();
    if (!deviceId || !clientId || !nonce || !signature) {
      return res.status(400).json({ error: "Missing fields", code: "BAD_REQUEST" });
    }
    if (!Number.isFinite(timestamp) || Math.abs(now - timestamp) > 2 * 60 * 1000) {
      return res.status(401).json({ error: "Timestamp skew", code: "REPLAY" });
    }
    const secretSnap = await db()
      .collection(R.COL_USERS)
      .doc(uid)
      .collection(R.COL_DEVICE_SECRETS)
      .doc(deviceId)
      .get();
    if (!secretSnap.exists) {
      return res.status(403).json({ error: "Device secret not registered", code: "SECRET_MISSING" });
    }
    const secret = String((secretSnap.data() || {}).secret || "");
    const caps = normalizeAllowedCapabilities(body.allowedCapabilities);
    // Deterministic key order for cross-platform HMAC (Android mirrors CAPABILITY_KEYS).
    const { CAPABILITY_KEYS } = await import("../lib/capability-model.js");
    const ordered = {};
    for (const key of CAPABILITY_KEYS) ordered[key] = Boolean(caps[key]);
    const stable = JSON.stringify(ordered);
    const payload = `${deviceId}:${clientId}:${timestamp}:${nonce}:${stable}`;
    const expected = createHmac("sha256", secret).update(payload, "utf8").digest("hex");
    const a = Buffer.from(expected, "utf8");
    const b = Buffer.from(signature, "utf8");
    if (a.length !== b.length || !timingSafeEqual(a, b)) {
      await writeAuditLog(uid, {
        action: R.AUDIT_UNAUTHORIZED_ATTEMPT,
        deviceId,
        clientId,
        result: "denied",
        metadata: { reason: "bad_hmac" },
      });
      return res.status(403).json({ error: "Invalid signature", code: "BAD_SIGNATURE" });
    }
    const { consumeNonce } = await import("../lib/browser-identity.js");
    if (!consumeNonce(`cap:${uid}:${deviceId}:${nonce}`, 5 * 60 * 1000)) {
      return res.status(401).json({ error: "Replay", code: "REPLAY" });
    }
    const { sanitizeTrustedClient } = await import("../lib/pairing.js");
    const ref = db().collection(R.COL_USERS).doc(uid).collection(R.COL_TRUSTED_CLIENTS).doc(clientId);
    const snap = await ref.get();
    if (!snap.exists || (snap.data() || {}).revoked === true) {
      return res.status(404).json({ error: "Client not found", code: "CLIENT_NOT_FOUND" });
    }
    const patch = { allowedCapabilities: ordered, updatedAt: now };
    // Phone may enable/disable auto-approve after pairing (HMAC-authenticated).
    if (typeof body.autoApproveSessions === "boolean") {
      patch.autoApproveSessions = body.autoApproveSessions;
    }
    await ref.set(patch, { merge: true });
    await writeAuditLog(uid, {
      action: R.AUDIT_BROWSER_PERMISSIONS_CHANGED,
      deviceId,
      clientId,
      result: "ok",
      metadata: {
        source: "phone_hmac",
        allowedCapabilities: ordered,
        autoApproveSessions:
          typeof body.autoApproveSessions === "boolean"
            ? body.autoApproveSessions
            : Boolean((snap.data() || {}).autoApproveSessions),
      },
    });
    const updated = { ...(snap.data() || {}), ...patch };
    return res.status(200).json({ ok: true, client: sanitizeTrustedClient(clientId, updated) });
  } catch (e) {
    return clientError(res, e, "PHONE_CAPS_FAILED");
  }
}
