package com.paicli.eval.headless;

/**
 * headless 单次运行的机器可读结果（写入 --out 指定的 JSON）。
 *
 * @param completed         是否正常结束（只有 exitReason=completed 才为 true）
 * @param exitReason        completed / error / timeout / cancelled / budget_exhausted / stopped
 * @param requests          模型请求次数
 * @param inputTokens       累计输入 token
 * @param outputTokens      累计输出 token
 * @param cachedInputTokens 累计命中缓存的输入 token
 * @param elapsedSeconds    墙钟耗时（秒）
 * @param toolCalls         工具调用次数
 * @param answer            模型最终回答
 * @param error             失败原因（成功时为 null）
 * @param mode              react / plan / team
 * @param model             模型名
 * @param provider          provider 名
 * @param workdir           本次运行的工作目录
 * @param traceId           Langfuse trace id（未启用或未生成时为 null）
 */
public record HeadlessResult(
        boolean completed,
        String exitReason,
        int requests,
        long inputTokens,
        long outputTokens,
        long cachedInputTokens,
        double elapsedSeconds,
        int toolCalls,
        String answer,
        String error,
        String mode,
        String model,
        String provider,
        String workdir,
        String traceId
) {
}
