package com.paicli.runtime.task;

import com.paicli.agent.ExecutionOutcome;

public enum TaskStatus {
    ENQUEUED("enqueued"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELED("canceled");

    private final String value;

    TaskStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** Keep existing persisted statuses; the precise budget/error cause travels separately. */
    public static TaskStatus fromOutcome(ExecutionOutcome outcome) {
        return switch (outcome.reason()) {
            case COMPLETED -> COMPLETED;
            case CANCELLED -> CANCELED;
            default -> FAILED;
        };
    }

    public static TaskStatus from(String value) {
        if (value == null) {
            return ENQUEUED;
        }
        for (TaskStatus status : values()) {
            if (status.value.equalsIgnoreCase(value) || status.name().equalsIgnoreCase(value)) {
                return status;
            }
        }
        return ENQUEUED;
    }
}
