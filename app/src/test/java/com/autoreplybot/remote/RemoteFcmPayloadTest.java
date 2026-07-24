package com.autoreplybot.remote;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RemoteFcmPayloadTest {
    @Test
    public void parseValidSessionRequest() {
        Map<String, String> data = new HashMap<>();
        data.put("type", "session_request");
        data.put("requestId", "req_abc123");
        data.put("deviceId", "dev_xyz");
        data.put("clientId", "client_1");
        data.put("clientName", "Chrome");
        data.put("expiresAt", "1700000000000");

        RemoteFcmPayload payload = RemoteFcmPayload.parse(data);
        assertTrue(payload.valid);
        assertEquals("req_abc123", payload.requestId);
        assertEquals("dev_xyz", payload.deviceId);
        assertEquals("client_1", payload.clientId);
        assertEquals("Chrome", payload.clientName);
        assertEquals(1700000000000L, payload.expiresAt);
    }

    @Test
    public void rejectUnknownTypeAndBadIds() {
        Map<String, String> badType = new HashMap<>();
        badType.put("type", "pair_code");
        badType.put("requestId", "req_1");
        badType.put("clientId", "client_1");
        assertFalse(RemoteFcmPayload.parse(badType).valid);

        Map<String, String> badId = new HashMap<>();
        badId.put("type", "session_request");
        badId.put("requestId", "bad id!");
        badId.put("clientId", "client_1");
        assertFalse(RemoteFcmPayload.parse(badId).valid);

        assertFalse(RemoteFcmPayload.parse(null).valid);
    }
}
