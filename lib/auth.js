import { getAuth } from "firebase-admin/auth";
import { db } from "./firebase.js";

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
  const decoded = await getAuth().verifyIdToken(idToken);
  return {
    uid: decoded.uid,
    email: String(decoded.email || "").trim().toLowerCase(),
    name: String(decoded.name || "").trim(),
    blocked: Boolean(decoded.blocked),
  };
}
