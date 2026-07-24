import assert from "node:assert/strict";
import { parseCommandAction, validateRemoteCommand } from "./remote-commands.js";
import { checkRateLimit, resetRateLimits } from "./rate-limit.js";

assert.equal(parseCommandAction("switch_camera"), "SWITCH_CAMERA");
assert.equal(parseCommandAction("TOTALLY_EVIL"), null);
assert.equal(parseCommandAction(""), null);

const base = {
  status: "pending",
  action: "TORCH_ON",
  sessionId: "sess1",
  deviceId: "dev1",
  expiresAt: 10_000,
};

assert.deepEqual(
  validateRemoteCommand(base, { sessionId: "sess1", deviceId: "dev1", now: 1000 }),
  { ok: true, status: "pending", action: "TORCH_ON" }
);

assert.equal(
  validateRemoteCommand(
    { ...base, action: "rm -rf /" },
    { sessionId: "sess1", deviceId: "dev1", now: 1000 }
  ).errorCode,
  "UNKNOWN_ACTION"
);

assert.equal(
  validateRemoteCommand(
    { ...base, sessionId: "other" },
    { sessionId: "sess1", deviceId: "dev1", now: 1000 }
  ).errorCode,
  "SESSION_MISMATCH"
);

assert.equal(
  validateRemoteCommand(base, { sessionId: "sess1", deviceId: "dev1", now: 10_000 })
    .errorCode,
  "EXPIRED"
);

assert.equal(
  validateRemoteCommand(
    { ...base, status: "acked" },
    { sessionId: "sess1", deviceId: "dev1", now: 1000 }
  ).errorCode,
  "ALREADY_HANDLED"
);

resetRateLimits();
for (let i = 0; i < 10; i += 1) {
  assert.equal(checkRateLimit("u1", 10, 3600_000, 1000 + i).allowed, true);
}
assert.equal(checkRateLimit("u1", 10, 3600_000, 2000).allowed, false);

console.log("remote-commands.test.js OK");
