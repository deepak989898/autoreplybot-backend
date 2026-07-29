import { getApps, initializeApp, cert, applicationDefault } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";

function projectIdFromServiceAccount() {
  const json = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
  if (!json || !json.trim().startsWith("{")) return "";
  try {
    const parsed = JSON.parse(json);
    return String(parsed.project_id || "").trim();
  } catch {
    return "";
  }
}

function resolveAdminStorageBucket() {
  const explicit =
    (process.env.FIREBASE_STORAGE_BUCKET || "").trim() ||
    (process.env.FIREBASE_WEB_STORAGE_BUCKET || "").trim();
  if (explicit) return explicit;
  const projectId =
    (process.env.FIREBASE_WEB_PROJECT_ID || "").trim() || projectIdFromServiceAccount();
  if (projectId) return `${projectId}.firebasestorage.app`;
  return "";
}

function initAdmin() {
  if (getApps().length > 0) return;

  const storageBucket = resolveAdminStorageBucket() || undefined;
  const json = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
  if (json && json.trim().startsWith("{")) {
    initializeApp({
      credential: cert(JSON.parse(json)),
      storageBucket,
    });
    return;
  }

  initializeApp({
    credential: applicationDefault(),
    storageBucket,
  });
}

export function db() {
  initAdmin();
  return getFirestore();
}

export function bucket() {
  initAdmin();
  const name = resolveAdminStorageBucket();
  if (!name) {
    throw new Error(
      "Firebase Storage bucket not configured. Set FIREBASE_STORAGE_BUCKET or FIREBASE_WEB_STORAGE_BUCKET (e.g. auto-reply-bot-757dc.firebasestorage.app)."
    );
  }
  // Pass name explicitly so downloads work even if Admin was initialized without storageBucket.
  return getStorage().bucket(name);
}
