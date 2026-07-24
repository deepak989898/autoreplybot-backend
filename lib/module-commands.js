import { randomBytes } from "crypto";
import { getMessaging } from "firebase-admin/messaging";
import { db } from "./firebase.js";
import * as R from "./remote-constants.js";
import { normalizeAllowedCapabilities, requireCapability } from "./capability-model.js";
import { writeAuditLog } from "./pairing.js";

export const MODULE_COMMAND_TTL_MS = 90 * 1000;
export const TRANSFER_TTL_MS = 60 * 60 * 1000;
export const MAX_TRANSFER_BYTES = 100 * 1024 * 1024;

export const MODULE_ACTIONS = new Set([
  "DEVICE_INFO_REFRESH",
  "LOCATION_GET_CURRENT",
  "LOCATION_START_LIVE",
  "LOCATION_STOP",
  "GALLERY_INDEX",
  "GALLERY_METADATA",
  "GALLERY_TRANSFER_REQUEST",
  "GALLERY_DELETE_REQUEST",
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
  "STATUS_REFRESH",
]);

const ACTION_CAPABILITY = {
  DEVICE_INFO_REFRESH: "deviceInfoRead",
  LOCATION_GET_CURRENT: "locationCurrent",
  LOCATION_START_LIVE: "locationLive",
  LOCATION_STOP: "locationLive",
  GALLERY_INDEX: "galleryList",
  GALLERY_METADATA: "galleryList",
  GALLERY_TRANSFER_REQUEST: "galleryDownload",
  GALLERY_DELETE_REQUEST: "galleryDelete",
  FILE_LIST: "filesList",
  FILE_METADATA: "filesList",
  FILE_DOWNLOAD_REQUEST: "filesDownload",
  FILE_UPLOAD_PREPARE: "filesUpload",
  FILE_UPLOAD_COMMIT: "filesUpload",
  FILE_CREATE_FOLDER: "filesUpload",
  FILE_RENAME: "filesRename",
  FILE_MOVE: "filesMove",
  FILE_COPY: "filesCopy",
  FILE_DELETE: "filesDelete",
  FILE_PREVIEW: "filesPreview",
  FILE_CANCEL_TRANSFER: "filesList",
  STATUS_REFRESH: "deviceInfoRead",
};

function devicesCol(uid) {
  return db().collection(R.COL_USERS).doc(uid).collection(R.COL_DEVICES);
}

function trustedCol(uid) {
  return db().collection(R.COL_USERS).doc(uid).collection(R.COL_TRUSTED_CLIENTS);
}

function transfersCol(uid) {
  return db().collection(R.COL_USERS).doc(uid).collection(R.COL_TRANSFERS);
}

/**
 * @param {string} uid
 * @param {string} deviceId
 * @param {string} clientId
 * @param {string} action
 * @param {object} payload
 * @param {string} [idempotencyKey]
 */
export async function createModuleCommand(uid, deviceId, clientId, action, payload, idempotencyKey) {
  if (!MODULE_ACTIONS.has(action)) {
    const err = new Error("Unknown module action");
    err.code = "UNKNOWN_ACTION";
    throw err;
  }

  const deviceSnap = await devicesCol(uid).doc(deviceId).get();
  if (!deviceSnap.exists) {
    const err = new Error("Device not found");
    err.code = "DEVICE_NOT_FOUND";
    throw err;
  }
  const device = deviceSnap.data() || {};
  if (device.revoked === true || device.ownerUid !== uid) {
    const err = new Error("Device unavailable");
    err.code = "DEVICE_UNAVAILABLE";
    throw err;
  }

  const clientSnap = await trustedCol(uid).doc(clientId).get();
  if (!clientSnap.exists) {
    const err = new Error("Trusted browser not found");
    err.code = "CLIENT_NOT_FOUND";
    throw err;
  }
  const client = clientSnap.data() || {};
  if (client.revoked === true) {
    const err = new Error("Browser revoked");
    err.code = "CLIENT_REVOKED";
    throw err;
  }
  const caps = normalizeAllowedCapabilities(client.allowedCapabilities);
  const needed = ACTION_CAPABILITY[action];
  if (needed && !requireCapability(caps, needed)) {
    const err = new Error(`Browser lacks capability: ${needed}`);
    err.code = "CAPABILITY_DENIED";
    throw err;
  }

  if (idempotencyKey) {
    const existing = await devicesCol(uid)
      .doc(deviceId)
      .collection(R.COL_MODULE_COMMANDS)
      .where("idempotencyKey", "==", String(idempotencyKey).slice(0, 128))
      .limit(1)
      .get();
    if (!existing.empty) {
      const doc = existing.docs[0];
      return { commandId: doc.id, ...(doc.data() || {}), replay: true };
    }
  }

  const commandId = randomBytes(16).toString("hex");
  const now = Date.now();
  const doc = {
    commandId,
    ownerUid: uid,
    deviceId,
    clientId,
    action,
    payload: payload && typeof payload === "object" ? payload : {},
    createdAt: now,
    expiresAt: now + MODULE_COMMAND_TTL_MS,
    status: "pending",
    nonce: randomBytes(12).toString("hex"),
    idempotencyKey: idempotencyKey ? String(idempotencyKey).slice(0, 128) : null,
  };

  await devicesCol(uid).doc(deviceId).collection(R.COL_MODULE_COMMANDS).doc(commandId).set(doc);

  const fcmToken = String(device.fcmToken || "");
  if (fcmToken) {
    try {
      await getMessaging().send({
        token: fcmToken,
        data: {
          type: "module_command",
          deviceId,
          commandId,
          action,
          expiresAt: String(doc.expiresAt),
        },
        android: { priority: "high" },
      });
    } catch {
      // Device may pick up via Firestore listener.
    }
  }

  await writeAuditLog(uid, {
    action: `MODULE_${action}`,
    deviceId,
    clientId,
    result: "ok",
    metadata: { commandId },
  });

  return doc;
}

/**
 * @param {string} uid
 * @param {object} params
 */
export async function createTransfer(uid, params) {
  const transferId = randomBytes(16).toString("hex");
  const now = Date.now();
  const sizeBytes = Number(params.sizeBytes || 0);
  if (sizeBytes > MAX_TRANSFER_BYTES) {
    const err = new Error("File too large");
    err.code = "FILE_TOO_LARGE";
    throw err;
  }
  const doc = {
    transferId,
    ownerUid: uid,
    deviceId: String(params.deviceId || ""),
    requestedByClientId: String(params.clientId || ""),
    operation: String(params.operation || ""),
    sourceType: String(params.sourceType || ""),
    sourceReference: String(params.sourceReference || ""),
    status: "requested",
    progress: 0,
    sizeBytes,
    createdAt: now,
    expiresAt: now + TRANSFER_TTL_MS,
    completedAt: null,
    storagePath: String(params.storagePath || ""),
    errorCode: null,
    errorMessage: null,
    mimeType: String(params.mimeType || "").slice(0, 120),
    displayName: String(params.displayName || "").slice(0, 240),
  };
  await transfersCol(uid).doc(transferId).set(doc);
  return doc;
}
