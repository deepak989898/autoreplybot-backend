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
        assertFalse(payload.isAutoStart());
    }

    @Test
    public void parseValidAutoStart() {
        Map<String, String> data = new HashMap<>();
        data.put("type", "session_auto_start");
        data.put("requestId", "req_auto1");
        data.put("sessionId", "sess_auto1");
        data.put("deviceId", "dev_xyz");
        data.put("clientId", "client_1");
        data.put("clientName", "Chrome on Windows");
        data.put("expiresAt", "1700000000000");
        data.put("cameraEnabled", "1");
        data.put("microphoneEnabled", "0");
        data.put("sessionKind", "camera");

        RemoteFcmPayload payload = RemoteFcmPayload.parse(data);
        assertTrue(payload.valid);
        assertTrue(payload.isAutoStart());
        assertEquals("sess_auto1", payload.sessionId);
        assertTrue(payload.cameraEnabled);
        assertFalse(payload.microphoneEnabled);
        assertFalse(payload.isScreenSession());
    }

    @Test
    public void parseScreenAutoStart() {
        Map<String, String> data = new HashMap<>();
        data.put("type", "session_auto_start");
        data.put("requestId", "req_screen1");
        data.put("sessionId", "sess_screen1");
        data.put("deviceId", "dev_xyz");
        data.put("clientId", "client_1");
        data.put("clientName", "Chrome");
        data.put("expiresAt", "1700000000000");
        data.put("cameraEnabled", "0");
        data.put("microphoneEnabled", "0");
        data.put("sessionKind", "screen");
        data.put("screenMirror", "1");

        RemoteFcmPayload payload = RemoteFcmPayload.parse(data);
        assertTrue(payload.valid);
        assertTrue(payload.isScreenSession());
        assertEquals("screen", payload.sessionKind);
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
