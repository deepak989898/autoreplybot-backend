import { getApps, initializeApp, cert, applicationDefault } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";

function initAdmin() {
  if (getApps().length > 0) return;

  const json = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
  if (json && json.trim().startsWith("{")) {
    initializeApp({
      credential: cert(JSON.parse(json)),
      storageBucket: process.env.FIREBASE_STORAGE_BUCKET,
    });
    return;
  }

  initializeApp({
    credential: applicationDefault(),
    storageBucket: process.env.FIREBASE_STORAGE_BUCKET,
  });
}

export function db() {
  initAdmin();
  return getFirestore();
}

export function bucket() {
  initAdmin();
  return getStorage().bucket();
}
