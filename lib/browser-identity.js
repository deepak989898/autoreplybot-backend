import { createPublicKey, createHash, verify } from "crypto";

/**
 * @param {unknown} jwk
 * @returns {boolean}
 */
export function isPublicJwk(jwk) {
  return Boolean(
    jwk &&
      typeof jwk === "object" &&
      jwk.kty === "EC" &&
      jwk.crv === "P-256" &&
      typeof jwk.x === "string" &&
      typeof jwk.y === "string"
  );
}

/**
 * @param {object} jwk
 * @returns {string}
 */
export function fingerprintPublicJwk(jwk) {
  const normalized = JSON.stringify({
    crv: jwk.crv,
    kty: jwk.kty,
    x: jwk.x,
    y: jwk.y,
  });
  return createHash("sha256").update(normalized).digest("hex");
}

/**
 * Verify ECDSA P-256 / SHA-256 signature (IEEE P1363 / WebCrypto raw format).
 * @param {object} publicJwk
 * @param {string} message
 * @param {string} signatureB64
 */
export function verifyEcdsaP256Sha256(publicJwk, message, signatureB64) {
  if (!isPublicJwk(publicJwk)) return false;
  if (!message || !signatureB64) return false;
  try {
    const key = createPublicKey({ key: publicJwk, format: "jwk" });
    const sig = Buffer.from(signatureB64, "base64");
    const data = Buffer.from(String(message), "utf8");
    return verify(
      "sha256",
      data,
      {
        key,
        dsaEncoding: "ieee-p1363",
      },
      sig
    );
  } catch {
    return false;
  }
}

/**
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

/** In-memory short-TTL nonce cache (per serverless instance). */
const usedNonces = new Map();

/**
 * @param {string} uid
 * @param {string} nonce
 * @param {number} [ttlMs]
 */
export function consumeNonce(uid, nonce, ttlMs = 5 * 60 * 1000) {
  const key = `${uid}:${nonce}`;
  const now = Date.now();
  for (const [k, exp] of usedNonces) {
    if (exp <= now) usedNonces.delete(k);
  }
  if (!nonce || nonce.length < 8 || nonce.length > 128) return false;
  if (usedNonces.has(key)) return false;
  usedNonces.set(key, now + ttlMs);
  return true;
}

/**
 * @param {unknown} raw
 * @returns {{ camera: boolean, microphone: boolean, photoCapture: boolean, videoRecording: boolean, audioRecording: boolean, torch: boolean }}
 */
export function normalizeAllowedCapabilities(raw) {
  const src = raw && typeof raw === "object" ? raw : {};
  return {
    camera: src.camera !== false,
    microphone: src.microphone !== false,
    photoCapture: src.photoCapture !== false,
    videoRecording: Boolean(src.videoRecording),
    audioRecording: Boolean(src.audioRecording),
    torch: src.torch !== false,
  };
}

/**
 * @param {string[]} requested
 * @param {ReturnType<typeof normalizeAllowedCapabilities>} allowed
 */
export function capabilitiesAllowed(requested, allowed) {
  for (const c of requested || []) {
    if (c === "camera" && !allowed.camera) return false;
    if (c === "microphone" && !allowed.microphone) return false;
  }
  return true;
}
