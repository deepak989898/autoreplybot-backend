/**
 * Simple in-memory sliding-window rate limiter (per Vercel isolate).
 * Good enough for pairing abuse protection; not a distributed quota.
 */

/** @type {Map<string, number[]>} */
const buckets = new Map();

/**
 * @param {string} key
 * @param {number} limit
 * @param {number} windowMs
 * @param {number} [now]
 * @returns {{ allowed: boolean, remaining: number, retryAfterMs: number }}
 */
export function checkRateLimit(key, limit, windowMs, now = Date.now()) {
  const k = String(key || "");
  if (!k) {
    return { allowed: false, remaining: 0, retryAfterMs: windowMs };
  }
  const cutoff = now - windowMs;
  const prev = buckets.get(k) || [];
  const recent = prev.filter((t) => t > cutoff);
  if (recent.length >= limit) {
    buckets.set(k, recent);
    const oldest = recent[0] || now;
    return {
      allowed: false,
      remaining: 0,
      retryAfterMs: Math.max(0, oldest + windowMs - now),
    };
  }
  recent.push(now);
  buckets.set(k, recent);
  return {
    allowed: true,
    remaining: Math.max(0, limit - recent.length),
    retryAfterMs: 0,
  };
}

/** Test helper — clears all buckets. */
export function resetRateLimits() {
  buckets.clear();
}
