package com.autoreplybot.remote;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RemoteDeviceProfileTest {
    @Test
    public void toMapFromMapRoundTripPreservesFields() {
        RemoteDeviceProfile original = new RemoteDeviceProfile(
                "device-abc_123",
                "Living Room Phone",
                "Pixel 8",
                "Google",
                "34",
                "1.0",
                1_700_000_000_000L,
                1_700_000_100_000L,
                true,
                87,
                true,
                "wifi",
                "",
                true,
                true,
                false,
                false,
                "ownerUidExample",
                true);

        Map<String, Object> map = original.toMap();
        RemoteDeviceProfile restored = RemoteDeviceProfile.fromMap(original.deviceId, map);

        assertEquals(original.deviceId, restored.deviceId);
        assertEquals(original.deviceName, restored.deviceName);
        assertEquals(original.deviceModel, restored.deviceModel);
        assertEquals(original.manufacturer, restored.manufacturer);
        assertEquals(original.androidVersion, restored.androidVersion);
        assertEquals(original.appVersion, restored.appVersion);
        assertEquals(original.createdAt, restored.createdAt);
        assertEquals(original.lastSeenAt, restored.lastSeenAt);
        assertTrue(restored.online);
        assertEquals(87, restored.batteryLevel);
        assertTrue(restored.isCharging);
        assertEquals("wifi", restored.networkType);
        assertEquals("", restored.fcmToken);
        assertTrue(restored.cameraAvailable);
        assertTrue(restored.microphoneAvailable);
        assertFalse(restored.flashlightAvailable);
        assertFalse(restored.revoked);
        assertEquals("ownerUidExample", restored.ownerUid);
        assertTrue(restored.remoteControlEnabled);
    }

    @Test
    public void batteryLevelClampedToZeroThroughHundred() {
        RemoteDeviceProfile high = new RemoteDeviceProfile(
                "id", "n", "m", "man", "34", "1", 0L, 0L, false, 150, false, "none", "",
                false, false, false, false, "uid", false);
        RemoteDeviceProfile low = new RemoteDeviceProfile(
                "id", "n", "m", "man", "34", "1", 0L, 0L, false, -5, false, "none", "",
                false, false, false, false, "uid", false);
        assertEquals(100, high.batteryLevel);
        assertEquals(0, low.batteryLevel);
    }
}
