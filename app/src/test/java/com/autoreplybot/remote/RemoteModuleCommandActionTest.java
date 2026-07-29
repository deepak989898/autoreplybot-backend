package com.autoreplybot.remote;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class RemoteModuleCommandActionTest {
    @Test
    public void tryParseUnknownReturnsNull() {
        assertNull(RemoteModuleCommandAction.tryParse("SHELL_EXEC"));
        assertNull(RemoteModuleCommandAction.tryParse(""));
        assertNull(RemoteModuleCommandAction.tryParse(null));
    }

    @Test
    public void tryParseKnown() {
        assertEquals(RemoteModuleCommandAction.LOCATION_GET_CURRENT,
                RemoteModuleCommandAction.tryParse("location_get_current"));
    }
}
