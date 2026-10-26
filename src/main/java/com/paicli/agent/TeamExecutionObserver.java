package com.paicli.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Optional native Team observations; disabled by default and never persisted here.
 * Callbacks execute on the actual role/step thread and may be concurrent. Implementations
 * must be fast and thread-safe. These Candidate-side observations are not trusted grading
 * evidence: provider requests, tool results and artifacts require independent association.
 * Content is fingerprinted, while tool names/call IDs remain provider-controlled metadata.
 * RuntimeException and AssertionError from callbacks are isolated and counted once per run;
 * fatal VM/thread/linkage errors are not swallowed.
 */
@FunctionalInterface
public interface TeamExecutionObserver {
    void onEvent(Event event);

    sealed interface Event permits RunStarted, PlanPrepared, StepEntered, ActivationEvent,
            ReviewEvaluated, StepExited, RunExited {
        String runId();
        long elapsedNanos();
    }

    /** A planner has no step ID. Attempts count the initial worker execution as 1. */
    record ActivationIdentity(String runId, String stepId, int attempt, AgentRole role,
                              String activationId, String actorInstanceId, long historyGeneration) { }

    sealed interface ActivationEvent extends Event permits ActivationEntered, ActivationInputPrepared,
            CompactionScopeUnsupported, HistoryCompacted, BudgetFinalization, ToolBatchReturned, ActivationExited {
        ActivationIdentity activation();
        @Override default String runId() { return activation().runId(); }
    }

    record RunStarted(String runId, long elapsedNanos, TextFingerprint input,
                      TextFingerprint submittedInput, boolean explicitTaskEnvelope,
                      int workerCount, int maxRetriesPerStep) implements Event { }

    /** Describes the actual native normalization, not an independently validated DAG. */
    record PlanPrepared(String runId, long elapsedNanos, String plannerActivationId,
                        TextFingerprint plannerResult, List<StepNode> steps) implements Event {
        public PlanPrepared { steps = List.copyOf(steps); }
    }

    /** Unknown native dependency strings are retained as hashes, not rejected by an observer. */
    record StepNode(String stepId, TextFingerprint description, TextFingerprint type,
                    List<TextFingerprint> dependencies) {
        public StepNode { dependencies = List.copyOf(dependencies); }
    }

    record DependencyInput(String stepId, ProductStatus productStatus, TextFingerprint fullResult,
                           TextFingerprint injectedResult, boolean truncated) { }

    /** Emitted inside a step's runnable, never at queue submission or Future join. */
    record StepEntered(String runId, long elapsedNanos, String stepId, int batchOrdinal,
                       long threadId, TextFingerprint context, List<DependencyInput> dependencies) implements Event {
        public StepEntered { dependencies = List.copyOf(dependencies); }
    }

    record ActivationEntered(ActivationIdentity activation, long elapsedNanos, long threadId,
                             int historyMessagesBefore) implements ActivationEvent { }

    /** Index of the actual appended user message, not an assumed first user in reused history. */
    record ActivationInputPrepared(ActivationIdentity activation, long elapsedNanos,
                                   int userMessageIndex, TextFingerprint userText,
                                   int imagePartCount) implements ActivationEvent { }

    /**
     * Native compaction may issue summary calls that this interface cannot associate precisely.
     * This is a conservative scope warning, not proof that a summary provider call occurred.
     */
    record CompactionScopeUnsupported(ActivationIdentity activation, long elapsedNanos, int iteration,
                                       boolean sessionMemoryEnabled,
                                       boolean fullSummaryThresholdReached) implements ActivationEvent { }

    /** Earlier input indices are no longer asserted valid after native compaction. */
    record HistoryCompacted(ActivationIdentity activation, long elapsedNanos, int iteration,
                            int beforeMessages, int afterMessages,
                            boolean userMessageIndexValid) implements ActivationEvent { }

    record BudgetFinalization(ActivationIdentity activation, long elapsedNanos, int iteration,
                              String exitReason) implements ActivationEvent { }

    /** Ordered, post-policy merged results include denials. Call IDs need not be globally unique. */
    record ToolBatchReturned(ActivationIdentity activation, long elapsedNanos, int iteration,
                              List<ToolResult> results) implements ActivationEvent {
        public ToolBatchReturned { results = List.copyOf(results); }
    }

    record ToolResult(int ordinal, String callId, String name, TextFingerprint arguments,
                      TextFingerprint result, boolean successful, boolean timedOut,
                      int imagePartCount) { }

    enum ExitKind { NORMAL, ERROR, PARTIAL, THREW }

    record ActivationExited(ActivationIdentity activation, long elapsedNanos, ExitKind kind,
                             TextFingerprint result, String exceptionType) implements ActivationEvent { }

    enum ReviewDecision { APPROVED, REJECTED, ERROR }

    record ReviewEvaluated(String runId, long elapsedNanos, String stepId, int attempt,
                           String workerActivationId, String reviewerActivationId,
                           ReviewDecision decision, TextFingerprint reviewResult,
                           TextFingerprint issues) implements Event { }

    enum ProductStatus { PENDING, RUNNING, COMPLETED, FAILED }
    enum StepExitReason { APPROVED, REVIEW_ERROR_RETAINED, RETRIES_EXHAUSTED_RETAINED,
        WORKER_ERROR, EMPTY_RESULT, CANCELLED, THREW, BLOCKED_DEPENDENCY }

    /** Product COMPLETED does not imply reviewer approval; reason and review events remain independent. */
    record StepExited(String runId, long elapsedNanos, String stepId, ProductStatus productStatus,
                       StepExitReason reason, String acceptedWorkerActivationId,
                       String lastReviewerActivationId, TextFingerprint result,
                       String exceptionType) implements Event { }

    /** COMPLETED is the native product summary only, never benchmark success. */
    enum RunExitReason { COMPLETED, INCOMPLETE, PLAN_ERROR, PLAN_EMPTY, PLAN_INVALID, CANCELLED, THREW }

    /**
     * The orchestrator method returned/threw. Native interrupted Future waits do not join all
     * descendants; scoped events may follow this event, and unstarted steps are not fabricated.
     */
    record RunExited(String runId, long elapsedNanos, RunExitReason reason,
                      TextFingerprint result, String exceptionType) implements Event { }

    record TextFingerprint(boolean present, int utf8Bytes, String sha256) {
        public TextFingerprint {
            if (utf8Bytes < 0 || present && (sha256 == null || !sha256.matches("[0-9a-f]{64}"))
                    || !present && (utf8Bytes != 0 || sha256 != null))
                throw new IllegalArgumentException("invalid text fingerprint");
        }
        public static TextFingerprint of(String text) {
            if (text == null) return new TextFingerprint(false, 0, null);
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            try { return new TextFingerprint(true, bytes.length,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))); }
            catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
        }
    }
}
