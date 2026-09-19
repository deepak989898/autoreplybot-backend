import { getApps, initializeApp, cert, applicationDefault } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";

function stripWrapQuotes(raw) {
  const s = String(raw || "").trim();
  if (
    (s.startsWith("'") && s.endsWith("'") && s.length >= 2) ||
    (s.startsWith('"') && s.endsWith('"') && s.length >= 2)
  ) {
    return s.slice(1, -1).trim();
  }
  return s;
}

export function parseServiceAccountJson() {
  let raw = stripWrapQuotes(process.env.FIREBASE_SERVICE_ACCOUNT_JSON || "");
  if (!raw) return null;
  if (!raw.startsWith("{")) {
    try {
      const decoded = Buffer.from(raw, "base64").toString("utf8").trim();
      if (decoded.startsWith("{")) raw = decoded;
    } catch {
      /* keep raw */
    }
  }
  if (!raw.startsWith("{")) return null;
  const parsed = JSON.parse(raw);
  if (parsed && typeof parsed.private_key === "string") {
    parsed.private_key = parsed.private_key.replace(/\\n/g, "\n");
  }
  return parsed;
}

function projectIdFromServiceAccount() {
  try {
    const parsed = parseServiceAccountJson();
    return String(parsed?.project_id || "").trim();
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
  let parsed = null;
  try {
    parsed = parseServiceAccountJson();
  } catch {
    const err = new Error(
      "FIREBASE_SERVICE_ACCOUNT_JSON is not valid JSON. In Vercel → Settings → Environment Variables, paste the full service account key file for Production."
    );
    err.code = "ADMIN_FIREBASE_CREDENTIALS";
    throw err;
  }

  if (parsed?.client_email && parsed?.private_key) {
    initializeApp({
      credential: cert(parsed),
      storageBucket,
    });
    return;
  }

  if (process.env.VERCEL) {
    const err = new Error(
      "Firebase Admin is not configured on this deployment. Set FIREBASE_SERVICE_ACCOUNT_JSON on Vercel (Production) to the Firebase service account JSON, then redeploy."
    );
    err.code = "ADMIN_FIREBASE_CREDENTIALS";
    throw err;
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

export function isFirebaseAdminCredentialError(e) {
  const msg = e instanceof Error ? e.message : String(e || "");
  const code = String(e?.code || "");
  return (
    code === "ADMIN_FIREBASE_CREDENTIALS" ||
    /UNAUTHENTICATED|invalid_grant|failed to parse private key|Could not load the default credentials|credential/i.test(
      msg
    )
  );
}
