package com.paicli.harness;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BetterHarnessOptionsTest {

    @Test
    void defaultsToNormalDurableReport() {
        BetterHarnessOptions.ParseResult result = BetterHarnessOptions.parse(null);

        assertTrue(result.valid());
        assertEquals(BetterHarnessOptions.Depth.NORMAL, result.options().depth());
        assertFalse(result.options().inline());
    }

    @Test
    void parsesQuickInlineAliases() {
        BetterHarnessOptions.ParseResult result =
                BetterHarnessOptions.parse("quick --no-files");

        assertTrue(result.valid());
        assertEquals(BetterHarnessOptions.Depth.QUICK, result.options().depth());
        assertTrue(result.options().inline());
    }

    @Test
    void rejectsUnknownOptions() {
        BetterHarnessOptions.ParseResult result =
                BetterHarnessOptions.parse("--include-user-home");

        assertFalse(result.valid());
        assertTrue(result.error().contains("未知参数"));
    }
}
