package com.paicli.eval.runner;

/**
 * 单次运行的评测记录（写入结果 JSON / CSV）。
 */
public record RunResult(
        String taskId,
        String type,
        int run,
        boolean passed,
        String outcome,
        int headlessExitCode,
        String exitReason,
        int requests,
        long inputTokens,
        long outputTokens,
        double elapsedSeconds,
        int toolCalls,
        String answer,
        String error,
        boolean verifyOk,
        String verifyDetail,
        String failureReason
) {
}
