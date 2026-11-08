package com.paicli.agent;

import com.paicli.llm.LlmFailureClassifier;
import com.paicli.runtime.CancellationContext;

import java.io.IOException;

/** Conservative admission for Plan recovery; never replays a tool or adds HTTP retries. */
public final class FailureRecoveryPolicy {
    private FailureRecoveryPolicy() {}

    public enum Decision {
        REPLAN, REPLAN_LIMIT, CANCELLED, BUDGET_EXHAUSTED,
        CLIENT_OWNS_RETRIES, NON_RETRYABLE_FAILURE, UNSAFE_TASK_REPLAY
    }

    public static Decision decide(ExecutionOutcome outcome, Exception failure,
                                  boolean clientOwnsRetries, boolean safeToReplay,
                                  boolean budgetAvailable, int replansUsed, int maxReplans) {
        if (CancellationContext.isCancelled() || Thread.currentThread().isInterrupted()
                || outcome.reason() == ExecutionOutcome.Reason.CANCELLED
                || failure instanceof InterruptedException) return Decision.CANCELLED;
        if (!budgetAvailable || switch (outcome.reason()) {
            case TOKEN_BUDGET_EXCEEDED, MODEL_REQUEST_BUDGET_EXCEEDED, HARD_ITERATION_LIMIT, STAGNATION_DETECTED -> true;
            default -> false;
        }) return Decision.BUDGET_EXHAUSTED;
        if (!safeToReplay) return Decision.UNSAFE_TASK_REPLAY;
        // chat has already applied its complete retry/streaming policy; do not multiply it via replan.
        if (clientOwnsRetries && failure instanceof IOException) return Decision.CLIENT_OWNS_RETRIES;
        if (!(failure instanceof IOException io)
                || LlmFailureClassifier.classify(io) != LlmFailureClassifier.Category.TRANSIENT) {
            return Decision.NON_RETRYABLE_FAILURE;
        }
        return replansUsed >= Math.max(0, maxReplans) ? Decision.REPLAN_LIMIT : Decision.REPLAN;
    }
}
