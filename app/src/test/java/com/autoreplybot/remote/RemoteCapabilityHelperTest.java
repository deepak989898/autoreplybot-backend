package com.autoreplybot.remote;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RemoteCapabilityHelperTest {
    @Test
    public void emptyCapabilitiesDefaultToCameraAndMic() {
        assertTrue(RemoteCapabilityHelper.wantsCamera(Collections.emptyList()));
        assertTrue(RemoteCapabilityHelper.wantsMicrophone(null));
        assertFalse(RemoteCapabilityHelper.isCameraOnly(Collections.emptyList()));
        assertFalse(RemoteCapabilityHelper.isMicrophoneOnly(Collections.emptyList()));
        assertEquals("camera_and_microphone",
                RemoteCapabilityHelper.describeCapabilities(Collections.emptyList()));
    }

    @Test
    public void cameraOnlyMode() {
        assertTrue(RemoteCapabilityHelper.wantsCamera(Collections.singletonList("camera")));
        assertFalse(RemoteCapabilityHelper.wantsMicrophone(Collections.singletonList("camera")));
        assertTrue(RemoteCapabilityHelper.isCameraOnly(Collections.singletonList("CAMERA")));
        assertEquals("camera_only",
                RemoteCapabilityHelper.describeCapabilities(Collections.singletonList("video")));
    }

    @Test
    public void microphoneOnlyMode() {
        assertTrue(RemoteCapabilityHelper.wantsMicrophone(Arrays.asList("mic", "audio")));
        assertFalse(RemoteCapabilityHelper.wantsCamera(Collections.singletonList("microphone")));
        assertTrue(RemoteCapabilityHelper.isMicrophoneOnly(Collections.singletonList("voice")));
        assertEquals("microphone_only",
                RemoteCapabilityHelper.describeCapabilities(Collections.singletonList("mic")));
    }

    @Test
    public void expiryUsesExpiresAtBoundary() {
        assertFalse(RemoteCapabilityHelper.isExpired(0L, 1000L));
        assertFalse(RemoteCapabilityHelper.isExpired(2000L, 1999L));
        assertTrue(RemoteCapabilityHelper.isExpired(2000L, 2000L));
        assertTrue(RemoteCapabilityHelper.isExpired(2000L, 2001L));
    }
}
