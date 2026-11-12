package com.paicli.eval.runner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutcomeClassifierTest {

    @Test
    void passedRunIsPassed() {
        assertEquals(OutcomeClassifier.PASSED, OutcomeClassifier.classify(true, "completed"));
        assertEquals(OutcomeClassifier.PASSED, OutcomeClassifier.classify(true, "timeout"));
    }

    @Test
    void completedButVerificationFailedIsModelFailure() {
        assertEquals(OutcomeClassifier.MODEL_FAILED,
                OutcomeClassifier.classify(false, "completed"));
    }

    @Test
    void unfinishedRunsAreRunFailures() {
        assertEquals(OutcomeClassifier.RUN_FAILED, OutcomeClassifier.classify(false, "llm_error"));
        assertEquals(OutcomeClassifier.RUN_FAILED, OutcomeClassifier.classify(false, "timeout"));
        assertEquals(OutcomeClassifier.RUN_FAILED, OutcomeClassifier.classify(false, "budget_exhausted"));
        assertEquals(OutcomeClassifier.RUN_FAILED, OutcomeClassifier.classify(false, "no_result"));
        assertEquals(OutcomeClassifier.RUN_FAILED, OutcomeClassifier.classify(false, null));
    }
}
