package com.paicli.runtime.task;

/** Confirmation for the root execution thread and operations it awaited, not arbitrary detached work. */
public enum TaskCleanupState {
    NOT_STARTED, RUNNING, CANCEL_REQUESTED, INCOMPLETE, STOPPED
}
