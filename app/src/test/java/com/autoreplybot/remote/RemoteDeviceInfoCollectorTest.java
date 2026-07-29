package com.autoreplybot.remote;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;

public class RemoteDeviceInfoCollectorTest {
    @Test
    public void sanitizeRemovesBannedKeys() {
        Map<String, Object> root = new HashMap<>();
        Map<String, Object> nested = new HashMap<>();
        nested.put("imei", "should-not-remain");
        nested.put("model", "Pixel");
        root.put("basic", nested);
        root.put("serial", "bad");
        RemoteDeviceInfoCollector.sanitize(root);
        assertFalse(root.containsKey("serial"));
        @SuppressWarnings("unchecked")
        Map<String, Object> basic = (Map<String, Object>) root.get("basic");
        assertFalse(basic.containsKey("imei"));
    }
}
