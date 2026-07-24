import { getAuth } from "firebase-admin/auth";
import { db } from "./firebase.js";

/**
 * @param {string | undefined} authorizationHeader - "Bearer &lt;Firebase ID token&gt;"
 * @returns {Promise<{ uid: string }>}
 */
export async function verifyFirebaseIdToken(authorizationHeader) {
  db();
  if (!authorizationHeader || !authorizationHeader.startsWith("Bearer ")) {
    throw new Error("Missing Authorization Bearer token");
  }
  const idToken = authorizationHeader.slice("Bearer ".length).trim();
  const decoded = await getAuth().verifyIdToken(idToken);
  return { uid: decoded.uid };
}
