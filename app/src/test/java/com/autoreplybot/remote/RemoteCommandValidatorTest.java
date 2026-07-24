package com.autoreplybot.remote;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RemoteCommandValidatorTest {

    private static Map<String, Object> baseCommand() {
        Map<String, Object> map = new HashMap<>();
        map.put("commandId", "cmd1");
        map.put("sessionId", "sess1");
        map.put("deviceId", "dev1");
        map.put("action", "TORCH_ON");
        map.put("createdAt", 1000L);
        map.put("expiresAt", 10_000L);
        map.put("status", "pending");
        map.put("ownerUid", "uid1");
        return map;
    }

    @Test
    public void acceptsValidPendingCommand() {
        RemoteCommandValidator.Result r =
                RemoteCommandValidator.validate(baseCommand(), "sess1", "dev1", 2000L);
        assertTrue(r.accepted);
        assertEquals(RemoteCommandAction.TORCH_ON, r.action);
        assertNull(r.errorCode);
    }

    @Test
    public void rejectsUnknownAction() {
        Map<String, Object> map = baseCommand();
        map.put("action", "RM_RF_ROOT");
        RemoteCommandValidator.Result r =
                RemoteCommandValidator.validate(map, "sess1", "dev1", 2000L);
        assertFalse(r.accepted);
        assertEquals("UNKNOWN_ACTION", r.errorCode);
        assertEquals("ignored", r.status);
    }

    @Test
    public void rejectsRequestSessionAction() {
        Map<String, Object> map = baseCommand();
        map.put("action", "REQUEST_SESSION");
        RemoteCommandValidator.Result r =
                RemoteCommandValidator.validate(map, "sess1", "dev1", 2000L);
        assertFalse(r.accepted);
        assertEquals("UNKNOWN_ACTION", r.errorCode);
    }

    @Test
    public void rejectsSessionMismatch() {
        RemoteCommandValidator.Result r =
                RemoteCommandValidator.validate(baseCommand(), "other", "dev1", 2000L);
        assertFalse(r.accepted);
        assertEquals("SESSION_MISMATCH", r.errorCode);
    }

    @Test
    public void rejectsDeviceMismatch() {
        RemoteCommandValidator.Result r =
                RemoteCommandValidator.validate(baseCommand(), "sess1", "other", 2000L);
        assertFalse(r.accepted);
        assertEquals("DEVICE_MISMATCH", r.errorCode);
    }

    @Test
    public void rejectsExpired() {
        RemoteCommandValidator.Result r =
                RemoteCommandValidator.validate(baseCommand(), "sess1", "dev1", 10_000L);
        assertFalse(r.accepted);
        assertEquals("EXPIRED", r.errorCode);
        assertEquals("expired", r.status);
    }

    @Test
    public void idempotentAlreadyAcked() {
        Map<String, Object> map = baseCommand();
        map.put("status", "acked");
        RemoteCommandValidator.Result r =
                RemoteCommandValidator.validate(map, "sess1", "dev1", 2000L);
        assertFalse(r.accepted);
        assertEquals("ALREADY_HANDLED", r.errorCode);
    }

    @Test
    public void tryParseStrict() {
        assertEquals(RemoteCommandAction.END_SESSION, RemoteCommandAction.tryParse("end_session"));
        assertNull(RemoteCommandAction.tryParse("NOT_A_REAL_ACTION"));
        assertNull(RemoteCommandAction.tryParse(""));
        assertNull(RemoteCommandAction.tryParse(null));
    }
}
