package com.paicli.eval.runner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(3, 1);

    @Test
    void infrastructureErrorsUseTheirOwnBudget() {
        assertTrue(policy.canRetry("error", 0, 1));
        assertTrue(policy.canRetry("error", 2, 1));
        assertFalse(policy.canRetry("error", 3, 0));
        assertTrue(policy.canRetry("no_result", 0, 1));
    }

    @Test
    void timeoutUsesASeparateAndSmallerBudget() {
        assertTrue(policy.canRetry("timeout", 3, 0));
        assertFalse(policy.canRetry("timeout", 0, 1));
    }

    @Test
    void zeroMeansNoRetry() {
        RetryPolicy none = new RetryPolicy(0, 0);
        assertFalse(none.canRetry("error", 0, 0));
        assertFalse(none.canRetry("timeout", 0, 0));
    }
}
