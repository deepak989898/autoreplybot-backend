/**
 * Browser WebCrypto helpers for trusted-client identity (ECDSA P-256).
 * Private key stays in IndexedDB; only public JWK / fingerprint go to the server.
 */

const DB_NAME = "autoreplybot_remote_id";
const STORE = "keys";
const KEY_ID = "browser_signing_key";

/**
 * @returns {Promise<IDBDatabase>}
 */
function openDb() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE)) db.createObjectStore(STORE);
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error || new Error("idb_open_failed"));
  });
}

/**
 * @param {string} key
 * @param {unknown} value
 */
async function idbSet(key, value) {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE, "readwrite");
    tx.objectStore(STORE).put(value, key);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error || new Error("idb_set_failed"));
  });
}

/**
 * @param {string} key
 */
async function idbGet(key) {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE, "readonly");
    const req = tx.objectStore(STORE).get(key);
    req.onsuccess = () => resolve(req.result ?? null);
    req.onerror = () => reject(req.error || new Error("idb_get_failed"));
  });
}

/**
 * @returns {Promise<CryptoKeyPair>}
 */
export async function getOrCreateBrowserKeyPair() {
  const existing = await idbGet(KEY_ID);
  if (existing?.privateKey && existing?.publicKey) {
    return {
      privateKey: existing.privateKey,
      publicKey: existing.publicKey,
    };
  }
  const pair = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["sign", "verify"]
  );
  await idbSet(KEY_ID, pair);
  return pair;
}

/**
 * @param {JsonWebKey} jwk
 */
export async function fingerprintPublicJwk(jwk) {
  const raw = new TextEncoder().encode(JSON.stringify(jwk));
  const digest = await crypto.subtle.digest("SHA-256", raw);
  return [...new Uint8Array(digest)]
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

/**
 * @returns {Promise<{ publicKeyJwk: JsonWebKey, fingerprint: string }>}
 */
export async function exportBrowserPublicKey() {
  const { publicKey } = await getOrCreateBrowserKeyPair();
  const publicKeyJwk = await crypto.subtle.exportKey("jwk", publicKey);
  const fingerprint = await fingerprintPublicJwk(publicKeyJwk);
  return { publicKeyJwk, fingerprint };
}

/**
 * @param {string} message
 */
export async function signMessage(message) {
  const { privateKey } = await getOrCreateBrowserKeyPair();
  const data = new TextEncoder().encode(message);
  const sig = await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    privateKey,
    data
  );
  return btoa(String.fromCharCode(...new Uint8Array(sig)));
}

/**
 * Canonical string for session-request signatures.
 * @param {{ clientId: string, deviceId: string, timestamp: number, nonce: string, capabilities: string[] }} p
 */
export function canonicalSessionRequest(p) {
  const caps = [...(p.capabilities || [])].map(String).sort().join(",");
  return [
    "session_request",
    String(p.clientId || ""),
    String(p.deviceId || ""),
    String(p.timestamp || 0),
    String(p.nonce || ""),
    caps,
  ].join("|");
}
