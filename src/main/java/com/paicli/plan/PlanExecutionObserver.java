package com.paicli.plan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Optional, in-process observation of native Plan execution. Nothing is persisted by default.
 * Callbacks run on the executing task thread and may be concurrent; observers must be fast,
 * thread-safe and non-blocking. These Candidate-side events are diagnostic observations, not
 * trusted benchmark evidence on their own. A verifier must independently bind them to provider
 * requests, tool execution and artifacts. Text fingerprints cover UTF-8 text, not image payloads.
 */
@FunctionalInterface
public interface PlanExecutionObserver {
    void onEvent(Event event);

    sealed interface Event permits PlanStarted, TaskEntered, TaskInputPrepared, ToolBatchReturned,
            TaskExited {
        String executionId();
        long elapsedNanos();
    }

    record PlanStarted(String executionId, long elapsedNanos, String planId,
                       TextFingerprint goal, List<TaskNode> tasks, List<String> executionOrder)
            implements Event {
        public PlanStarted {
            tasks = List.copyOf(tasks);
            executionOrder = List.copyOf(executionOrder);
        }
    }

    record TaskNode(String taskId, Task.TaskType type, TextFingerprint description,
                    List<String> dependencies) {
        public TaskNode {
            dependencies = List.copyOf(dependencies);
        }
    }

    /** Emitted inside the task runnable, never at submission/queue time. */
    record TaskEntered(String executionId, long elapsedNanos, String taskId, long threadId)
            implements Event {}

    /** Input availability is observable here; it does not prove semantic consumption by a model. */
    record TaskInputPrepared(String executionId, long elapsedNanos, String taskId,
                             TextFingerprint userText, int imagePartCount,
                             List<DependencyInput> dependencies) implements Event {
        public TaskInputPrepared {
            dependencies = List.copyOf(dependencies);
        }
    }

    record DependencyInput(String taskId, Task.TaskStatus status, TextFingerprint result) {}

    /**
     * Correlate by executionId + taskId + iteration + ordinal, not callId alone: different task
     * conversations may legitimately reuse a provider callId. Results include policy denials.
     */
    record ToolBatchReturned(String executionId, long elapsedNanos, String taskId, int iteration,
                             List<ToolResult> results) implements Event {
        public ToolBatchReturned {
            results = List.copyOf(results);
        }
    }

    record ToolResult(int ordinal, String callId, String name, TextFingerprint arguments,
                      TextFingerprint result, boolean successful, boolean timedOut,
                      int imagePartCount) {}

    /** RETURNED includes normal, partial-budget and cancellation text; it is not task success. */
    enum ExitKind { RETURNED, THREW }

    /** Emitted before the runnable completes, never when its Future is joined. */
    record TaskExited(String executionId, long elapsedNanos, String taskId, ExitKind kind,
                      TextFingerprint result, String exceptionType) implements Event {}

    /** Null and empty text remain distinct. Raw prompts, tool arguments and output are not stored. */
    record TextFingerprint(boolean present, int utf8Bytes, String sha256) {
        public TextFingerprint {
            if (utf8Bytes < 0 || present && (sha256 == null || !sha256.matches("[0-9a-f]{64}"))
                    || !present && (utf8Bytes != 0 || sha256 != null))
                throw new IllegalArgumentException("invalid text fingerprint");
        }
        public static TextFingerprint of(String text) {
            if (text == null) {
                return new TextFingerprint(false, 0, null);
            }
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            try {
                return new TextFingerprint(true, bytes.length, HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(bytes)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 unavailable", e);
            }
        }
    }
}
