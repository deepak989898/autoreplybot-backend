package com.autoreplybot.remote;

import org.junit.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class RemoteSessionRequestStatusTest {
    @Test
    public void toMapWritesLowercaseStatusForFirestoreRules() {
        RemoteSessionRequest request = new RemoteSessionRequest(
                "req1", "dev1", "client1",
                Collections.singletonList("camera"),
                RemoteSessionRequest.Status.PENDING,
                1000L, 2000L, 0L, 0L, "uid1");
        Map<String, Object> map = request.toMap();
        assertEquals("pending", map.get("status"));

        RemoteSessionRequest restored = RemoteSessionRequest.fromMap("req1", map);
        assertEquals(RemoteSessionRequest.Status.PENDING, restored.status);
    }

    @Test
    public void sessionStatusWireAndActiveAlias() {
        assertEquals("connected", RemoteSession.Status.CONNECTED.wireValue());
        assertEquals(RemoteSession.Status.CONNECTED, RemoteSession.Status.fromValue("active"));
        assertEquals(RemoteSession.Status.ENDED, RemoteSession.Status.fromValue("ended"));
    }
}
