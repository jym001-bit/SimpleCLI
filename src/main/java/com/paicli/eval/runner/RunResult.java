package com.paicli.eval.runner;

/**
 * 单次尝试的评测记录（写入结果 JSON / CSV）。
 *
 * @param valid 本次运行是否有效（基础设施故障为 false，见 {@link RunValidity}）
 * @param attempt 同一格的第几次尝试（从 1 开始，无效会触发重试）
 */
public record RunResult(
        String taskId,
        String type,
        int run,
        int attempt,
        boolean passed,
        boolean valid,
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
