package com.paicli.agent;

import java.util.Objects;

/** Execution termination, independent of rendered text and mode-specific scheduling state. */
public record ExecutionOutcome(Reason reason, String content, String displayContent,
                               TaskAcceptance.Result acceptance) {
    public enum Reason {
        COMPLETED, TOKEN_BUDGET_EXCEEDED, MODEL_REQUEST_BUDGET_EXCEEDED, HARD_ITERATION_LIMIT, STAGNATION_DETECTED,
        CANCELLED, MODEL_ERROR, EXECUTION_ERROR
    }

    public ExecutionOutcome {
        Objects.requireNonNull(reason, "reason");
    }

    /** Legacy callers still report termination only; acceptance is absent until evaluated. */
    public ExecutionOutcome(Reason reason, String content, String displayContent) {
        this(reason, content, displayContent, null);
    }

    /** Most results have identical machine content and text remaining to display. */
    public ExecutionOutcome(Reason reason, String content) {
        this(reason, content, content, null);
    }

    /** Stable root precedence; child records retain their individual reasons. */
    public static Reason aggregateReason(java.util.Collection<Reason> reasons) {
        for (Reason reason : new Reason[]{Reason.CANCELLED, Reason.EXECUTION_ERROR, Reason.MODEL_ERROR,
                Reason.TOKEN_BUDGET_EXCEEDED, Reason.MODEL_REQUEST_BUDGET_EXCEEDED,
                Reason.HARD_ITERATION_LIMIT, Reason.STAGNATION_DETECTED}) {
            if (reasons.contains(reason)) return reason;
        }
        return Reason.COMPLETED;
    }

    public boolean completed() { return reason == Reason.COMPLETED; }

    public static ExecutionOutcome completed(String content) {
        return new ExecutionOutcome(Reason.COMPLETED, content);
    }

    /** A budget summary is deliverable content, never evidence that the task completed. */
    public static ExecutionOutcome budgetExhausted(AgentBudget.ExitReason reason, String content) {
        return new ExecutionOutcome(switch (Objects.requireNonNull(reason, "reason")) {
            case TOKEN_BUDGET_EXCEEDED -> Reason.TOKEN_BUDGET_EXCEEDED;
            case HARD_ITERATION_LIMIT -> Reason.HARD_ITERATION_LIMIT;
            case STAGNATION_DETECTED -> Reason.STAGNATION_DETECTED;
            case WITHIN_BUDGET -> throw new IllegalArgumentException("budget has not been exhausted");
        }, content);
    }
}
