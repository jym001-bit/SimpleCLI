package com.paicli.runtime.task;

import com.paicli.agent.ExecutionOutcome;
import java.time.Instant;

public record DurableTask(
        String id,
        TaskStatus status,
        String prompt,
        String result,
        String error,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        long durationMs,
        ExecutionOutcome.Reason reason,
        TaskCleanupState cleanupState
) {
    public DurableTask(String id, TaskStatus status, String prompt, String result, String error,
                       Instant createdAt, Instant startedAt, Instant finishedAt, long durationMs,
                       ExecutionOutcome.Reason reason) {
        this(id, status, prompt, result, error, createdAt, startedAt, finishedAt, durationMs,
                reason, null);
    }
    /** Old databases/callers have no recorded termination reason. Do not guess it from text. */
    public DurableTask(String id, TaskStatus status, String prompt, String result, String error,
                       Instant createdAt, Instant startedAt, Instant finishedAt, long durationMs) {
        this(id, status, prompt, result, error, createdAt, startedAt, finishedAt, durationMs, null);
    }
    public boolean terminal() {
        return status == TaskStatus.COMPLETED
                || status == TaskStatus.FAILED
                || status == TaskStatus.CANCELED;
    }

    public String shortPrompt() {
        if (prompt == null) {
            return "";
        }
        String normalized = prompt.replace("\r\n", "\n").replace('\r', '\n').replace('\n', ' ').trim();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80) + "...";
    }
}
