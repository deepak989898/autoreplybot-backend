import { getAuth } from "firebase-admin/auth";
import { db, isFirebaseAdminCredentialError } from "./firebase.js";

/**
 * @param {string | undefined} authorizationHeader - "Bearer &lt;Firebase ID token&gt;"
 * @returns {Promise<{ uid: string, email: string, name: string, blocked: boolean }>}
 */
export async function verifyFirebaseIdToken(authorizationHeader) {
  db();
  if (!authorizationHeader || !authorizationHeader.startsWith("Bearer ")) {
    const err = new Error("Missing Authorization Bearer token");
    err.code = "AUTH_FAILED";
    throw err;
  }
  const idToken = authorizationHeader.slice("Bearer ".length).trim();
  let decoded;
  try {
    decoded = await getAuth().verifyIdToken(idToken);
  } catch (e) {
    if (isFirebaseAdminCredentialError(e)) {
      const err = new Error(
        "Admin server cannot verify login. Set FIREBASE_SERVICE_ACCOUNT_JSON on Vercel Production (full service account JSON), then redeploy."
      );
      err.code = "ADMIN_FIREBASE_CREDENTIALS";
      throw err;
    }
    const err = new Error("Invalid or expired sign-in token. Sign in again.");
    err.code = "AUTH_FAILED";
    throw err;
  }
  return {
    uid: decoded.uid,
    email: String(decoded.email || "").trim().toLowerCase(),
    name: String(decoded.name || "").trim(),
    blocked: Boolean(decoded.blocked),
  };
}
