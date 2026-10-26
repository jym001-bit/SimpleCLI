package com.paicli.render;

/**
 * 渲染器状态栏数据载体。
 *
 * <p>InlineRenderer 把这些字段格式化到底部常驻状态栏；
 * LanternaRenderer 写入 StatusPane；PlainRenderer 直接忽略。
 */
public record StatusInfo(
        String model,
        long totalTokens,
        long contextWindow,
        long inputTokens,
        long outputTokens,
        long cachedInputTokens,
        String estimatedCost,
        boolean hitlEnabled,
        long elapsedMillis,
        String phase,
        String mcpSummary,
        String skillSummary,
        String sessionMode
) {
    public StatusInfo(String model, long totalTokens, long contextWindow, long inputTokens, long outputTokens,
                      long cachedInputTokens, String estimatedCost, boolean hitlEnabled, long elapsedMillis,
                      String phase, String mcpSummary, String skillSummary) {
        this(model, totalTokens, contextWindow, inputTokens, outputTokens, cachedInputTokens, estimatedCost,
                hitlEnabled, elapsedMillis, phase, mcpSummary, skillSummary, null);
    }

    public StatusInfo(String model, long totalTokens, long contextWindow, boolean hitlEnabled, long elapsedMillis) {
        this(model, totalTokens, contextWindow, 0L, 0L, 0L, null, hitlEnabled, elapsedMillis,
                (totalTokens > 0 || elapsedMillis > 0) ? "running" : "idle", null, null);
    }

    public StatusInfo(String model,
                      long totalTokens,
                      long contextWindow,
                      long inputTokens,
                      long outputTokens,
                      long cachedInputTokens,
                      String estimatedCost,
                      boolean hitlEnabled,
                      long elapsedMillis,
                      String phase) {
        this(model, totalTokens, contextWindow, inputTokens, outputTokens, cachedInputTokens,
                estimatedCost, hitlEnabled, elapsedMillis, phase, null, null);
    }

    public static StatusInfo idle(String model, long contextWindow, boolean hitlEnabled) {
        return new StatusInfo(model, 0L, contextWindow, 0L, 0L, 0L, null, hitlEnabled, 0L, "idle");
    }

    public static StatusInfo idle(String model, long contextWindow, long contextTokens, boolean hitlEnabled) {
        return new StatusInfo(model, Math.max(0L, contextTokens), contextWindow, 0L, 0L, 0L, null,
                hitlEnabled, 0L, "idle");
    }

    public static StatusInfo active(String model, long contextWindow, boolean hitlEnabled, String phase) {
        return new StatusInfo(model, 0L, contextWindow, 0L, 0L, 0L, null, hitlEnabled, 0L, phase);
    }

    public static StatusInfo active(String model, long contextWindow, long contextTokens,
                                    boolean hitlEnabled, String phase) {
        return new StatusInfo(model, Math.max(0L, contextTokens), contextWindow, 0L, 0L, 0L, null,
                hitlEnabled, 0L, phase);
    }

    public static StatusInfo tokens(String model,
                                    long contextWindow,
                                    long contextTokens,
                                    long inputTokens,
                                    long outputTokens,
                                    long cachedInputTokens,
                                    String estimatedCost,
                                    boolean hitlEnabled,
                                    long elapsedMillis,
                                    String phase) {
        return new StatusInfo(
                model,
                Math.max(0L, contextTokens),
                contextWindow,
                Math.max(0L, inputTokens),
                Math.max(0L, outputTokens),
                Math.max(0L, cachedInputTokens),
                estimatedCost,
                hitlEnabled,
                elapsedMillis,
                phase == null || phase.isBlank() ? "running" : phase
        );
    }

    public StatusInfo withEnvironment(String mcpSummary, String skillSummary) {
        return new StatusInfo(
                model,
                totalTokens,
                contextWindow,
                inputTokens,
                outputTokens,
                cachedInputTokens,
                estimatedCost,
                hitlEnabled,
                elapsedMillis,
                phase,
                normalizeSummary(mcpSummary),
                normalizeSummary(skillSummary),
                sessionMode
        );
    }

    /** 状态栏左侧显示的会话模式文案（如 {@code AUTO shift+tab to cycle}）；null 时按 hitlEnabled 显示。 */
    public StatusInfo withSessionMode(String sessionMode) {
        return new StatusInfo(model, totalTokens, contextWindow, inputTokens, outputTokens, cachedInputTokens,
                estimatedCost, hitlEnabled, elapsedMillis, phase, mcpSummary, skillSummary,
                sessionMode == null || sessionMode.isBlank() ? null : sessionMode);
    }

    private static String normalizeSummary(String summary) {
        return summary == null || summary.isBlank() ? null : summary.trim();
    }
}
