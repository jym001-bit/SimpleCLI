package com.paicli.eval.runner;

import java.util.List;

/** 一次评测运行的汇总结果。 */
public record RunSummary(
        String tasksFile,
        String jar,
        String startedAt,
        double durationSeconds,
        int totalRuns,
        int passed,
        int failed,
        int invalidRuns,
        List<RunResult> runs
) {
}
