package com.paicli.eval.runner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunValidityTest {

    @Test
    void infrastructureFailuresAreInvalid() {
        assertFalse(RunValidity.isValid("error"));
        assertFalse(RunValidity.isValid("no_result"));
        assertFalse(RunValidity.isValid(null));
        assertFalse(RunValidity.isValid(" "));
    }

    @Test
    void timeoutAndExternalCancelAreInvalid() {
        assertFalse(RunValidity.isValid("timeout"));
        assertFalse(RunValidity.isValid("cancelled"));
    }

    @Test
    void agentBehaviourStaysValid() {
        assertTrue(RunValidity.isValid("completed"));
        assertTrue(RunValidity.isValid("budget_exhausted"));
        assertTrue(RunValidity.isValid("stopped"));
    }
}
