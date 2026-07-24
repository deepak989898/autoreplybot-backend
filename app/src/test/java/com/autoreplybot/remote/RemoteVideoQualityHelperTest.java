package com.autoreplybot.remote;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RemoteVideoQualityHelperTest {
    @Test
    public void normalizePreferredSnapsToLadder() {
        assertEquals(RemoteVideoQualityHelper.HEIGHT_720,
                RemoteVideoQualityHelper.normalizePreferred(0));
        assertEquals(RemoteVideoQualityHelper.HEIGHT_360,
                RemoteVideoQualityHelper.normalizePreferred(300));
        assertEquals(RemoteVideoQualityHelper.HEIGHT_480,
                RemoteVideoQualityHelper.normalizePreferred(500));
        assertEquals(RemoteVideoQualityHelper.HEIGHT_720,
                RemoteVideoQualityHelper.normalizePreferred(700));
        assertEquals(RemoteVideoQualityHelper.HEIGHT_1080,
                RemoteVideoQualityHelper.normalizePreferred(1100));
    }

    @Test
    public void fallbackHeightsStartsAtPreferredThenStepsDown() {
        List<Integer> from720 = RemoteVideoQualityHelper.fallbackHeights(720);
        assertEquals(Arrays.asList(720, 480, 360, 1080), from720);

        List<Integer> from1080 = RemoteVideoQualityHelper.fallbackHeights(1080);
        assertEquals(Arrays.asList(1080, 720, 480, 360), from1080);

        List<Integer> from360 = RemoteVideoQualityHelper.fallbackHeights(360);
        assertEquals(Arrays.asList(360, 1080, 720, 480), from360);
    }

    @Test
    public void supportedHeights() {
        assertTrue(RemoteVideoQualityHelper.isSupportedHeight(720));
        assertFalse(RemoteVideoQualityHelper.isSupportedHeight(540));
    }
}
