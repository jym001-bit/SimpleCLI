package com.paicli.eval.headless;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadlessOptionsTest {

    @Test
    void parsesAllOptions() {
        HeadlessOptions options = HeadlessOptions.parse(new String[]{
                "--prompt", "do something",
                "--mode", "plan",
                "--max-requests", "7",
                "--timeout", "9",
                "--approval", "deny-dangerous",
                "--out", "result.json",
                "--stub",
                "--stub-answer", "fixed answer",
                "--stub-delay-ms", "5",
                "--stub-loop"
        });

        assertEquals("do something", options.resolvePrompt());
        assertEquals(HeadlessOptions.Mode.PLAN, options.mode);
        assertEquals(7, options.maxRequests);
        assertEquals(9, options.timeoutSeconds);
        assertEquals(HeadlessOptions.Approval.DENY_DANGEROUS, options.approval);
        assertTrue(options.stub);
        assertEquals("fixed answer", options.stubAnswer);
        assertEquals(5L, options.stubDelayMs);
        assertTrue(options.stubLoop);
    }

    @Test
    void rejectsUnknownMode() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> HeadlessOptions.parse(new String[]{"--prompt", "x", "--mode", "turbo"}));
        assertTrue(error.getMessage().contains("--mode"));
    }

    @Test
    void requiresPrompt() {
        assertThrows(IllegalArgumentException.class, () -> HeadlessOptions.parse(new String[]{}));
    }
}
