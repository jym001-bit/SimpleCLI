package com.paicli.runtime.task;

import com.paicli.agent.ExecutionOutcome;

@FunctionalInterface
public interface TaskRunner {
    ExecutionOutcome run(String prompt) throws Exception;
}
