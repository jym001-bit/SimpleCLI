package com.paicli.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalContextTrackerTest {

    @Test
    void classifiesWebBrowserAndMcpToolsAsExternal() {
        for (String name : List.of("web_search", "web_fetch", "browser_connect",
                "mcp__chrome-devtools__take_snapshot", "mcp__step_search__web_fetch", "mcp__docs__read_resource")) {
            assertTrue(ExternalContextTracker.isExternalContentTool(name), name);
        }
        for (String name : List.of("read_file", "grep_code", "execute_command", "save_memory", "", "search_code")) {
            assertFalse(ExternalContextTracker.isExternalContentTool(name), name);
        }
    }

    @Test
    void blocksAutomaticMemoryOnlyWhenGuardIsOnAndExternalContentWasSeen() {
        ExternalContextTracker guarded = new ExternalContextTracker(true);
        assertFalse(guarded.blocksAutomaticMemory());
        assertFalse(guarded.recordToolResult("read_file"));
        assertTrue(guarded.recordToolResult("web_fetch"));
        assertTrue(guarded.blocksAutomaticMemory());
        assertEquals(List.of("web_fetch"), guarded.sources());

        guarded.reset();
        assertFalse(guarded.blocksAutomaticMemory());

        ExternalContextTracker unguarded = new ExternalContextTracker(false);
        unguarded.record("web_search");
        assertTrue(unguarded.hasExternalContext());
        assertFalse(unguarded.blocksAutomaticMemory());
    }

    @Test
    void guardDefaultsOnAndCanBeDisabled() {
        String previous = System.getProperty(ExternalContextTracker.GUARD_PROPERTY);
        try {
            System.clearProperty(ExternalContextTracker.GUARD_PROPERTY);
            if (System.getenv(ExternalContextTracker.GUARD_ENV) == null) {
                assertTrue(ExternalContextTracker.guardEnabledByConfiguration());
            }
            System.setProperty(ExternalContextTracker.GUARD_PROPERTY, "off");
            assertFalse(ExternalContextTracker.guardEnabledByConfiguration());
            System.setProperty(ExternalContextTracker.GUARD_PROPERTY, "true");
            assertTrue(ExternalContextTracker.guardEnabledByConfiguration());
        } finally {
            if (previous == null) System.clearProperty(ExternalContextTracker.GUARD_PROPERTY);
            else System.setProperty(ExternalContextTracker.GUARD_PROPERTY, previous);
        }
    }
}
