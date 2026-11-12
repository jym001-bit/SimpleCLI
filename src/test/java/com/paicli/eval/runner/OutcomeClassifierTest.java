package com.paicli.eval.runner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutcomeClassifierTest {

    @Test
    void passedRunIsPassed() {
        assertEquals(OutcomeClassifier.PASSED, OutcomeClassifier.classify(true, true, "completed"));
    }

    @Test
    void completedButVerificationFailedIsModelFailure() {
        assertEquals(OutcomeClassifier.MODEL_FAILED, OutcomeClassifier.classify(false, true, "completed"));
    }

    @Test
    void timeoutIsItsOwnOutcome() {
        assertEquals(OutcomeClassifier.TIMEOUT, OutcomeClassifier.classify(false, true, "timeout"));
    }

    @Test
    void otherUnfinishedRunsAreRunFailures() {
        assertEquals(OutcomeClassifier.RUN_FAILED, OutcomeClassifier.classify(false, true, "budget_exhausted"));
        assertEquals(OutcomeClassifier.RUN_FAILED, OutcomeClassifier.classify(false, true, "stopped"));
        assertEquals(OutcomeClassifier.RUN_FAILED, OutcomeClassifier.classify(false, true, "cancelled"));
    }

    @Test
    void invalidRunsAreMarkedInvalid() {
        assertEquals(OutcomeClassifier.INVALID, OutcomeClassifier.classify(false, false, "error"));
        assertEquals(OutcomeClassifier.INVALID, OutcomeClassifier.classify(false, false, "no_result"));
    }
}
