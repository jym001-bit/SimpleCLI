package com.paicli.llm;

import com.paicli.agent.ExecutionOutcome;
import com.paicli.agent.FailureRecoveryPolicy;
import com.paicli.runtime.CancellationContext;
import com.paicli.runtime.CancellationToken;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;

import static org.junit.jupiter.api.Assertions.*;

class PlanFailureRecoveryPolicyTest {
    private final ExecutionOutcome failed = new ExecutionOutcome(ExecutionOutcome.Reason.EXECUTION_ERROR, null);

    @Test void unmanagedTransientFailureCanRecoverOnlyWithinTheExistingLimit() {
        assertEquals(FailureRecoveryPolicy.Decision.REPLAN, decide(new ConnectException(), false, true, true, 0));
        assertEquals(FailureRecoveryPolicy.Decision.REPLAN_LIMIT, decide(new ConnectException(), false, true, true, 2));
    }

    @Test void providerAndProfileWrapperOwnRetriesAndMustNotBeRetriedByPlan() {
        GLMClient client = new GLMClient("unused-test-key");
        assertTrue(client.managesRequestRetries());
        assertTrue(new ProfiledLlmClient(client, "test", null).managesRequestRetries());
        assertEquals(FailureRecoveryPolicy.Decision.CLIENT_OWNS_RETRIES,
                decide(new LlmHttpException(503, null, "unavailable"), true, true, true, 0));
    }

    @Test void permanentUnknownAndDeterministicFailuresCannotReplan() {
        for (Exception error : new Exception[]{new LlmHttpException(401, null, "auth"),
                new LlmHttpException(403, null, "denied"), new LlmHttpException(400, null, "input"),
                new IllegalArgumentException("input"), new SecurityException("denied"), new IOException("unknown")}) {
            assertEquals(FailureRecoveryPolicy.Decision.NON_RETRYABLE_FAILURE,
                    decide(error, false, true, true, 0));
        }
    }

    @Test void uncertainToolEffectsAndExhaustedBudgetsBlockReplay() {
        assertEquals(FailureRecoveryPolicy.Decision.UNSAFE_TASK_REPLAY,
                decide(new ConnectException(), false, false, true, 0));
        assertEquals(FailureRecoveryPolicy.Decision.BUDGET_EXHAUSTED,
                decide(new ConnectException(), false, true, false, 0));
        assertEquals(FailureRecoveryPolicy.Decision.BUDGET_EXHAUSTED,
                FailureRecoveryPolicy.decide(new ExecutionOutcome(ExecutionOutcome.Reason.TOKEN_BUDGET_EXCEEDED, "partial"),
                        new ConnectException(), false, true, true, 0, 2));
    }

    @Test void cancellationAlwaysStopsRecovery() {
        CancellationToken token = CancellationContext.startRun();
        try {
            token.cancel();
            assertEquals(FailureRecoveryPolicy.Decision.CANCELLED,
                    decide(new ConnectException(), false, true, true, 0));
        } finally { CancellationContext.clear(token); }
    }

    private FailureRecoveryPolicy.Decision decide(Exception error, boolean owns, boolean safe, boolean budget, int used) {
        return FailureRecoveryPolicy.decide(failed, error, owns, safe, budget, used, 2);
    }
}
