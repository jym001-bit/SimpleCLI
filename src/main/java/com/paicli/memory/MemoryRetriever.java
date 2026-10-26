package com.paicli.memory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 记忆检索器 - 根据查询从长期记忆中检索最相关的信息。
 *
 * 检索策略：
 * 1. 关键词匹配：直接匹配内容中的关键词
 * 2. 类型优先：不同场景优先检索不同类型的记忆
 * 3. 时间衰减：越近的记忆权重越高
 *
 * 注入 system prompt 时每条记忆都带写入时间和最后核实时间；超过
 * {@code paicli.memory.stale.days}（默认 30 天）未核实的条目会标注“可能已过时”。
 */
public class MemoryRetriever {
    static final String STALE_DAYS_PROPERTY = "paicli.memory.stale.days";
    static final String STALE_DAYS_ENV = "PAICLI_MEMORY_STALE_DAYS";
    static final int DEFAULT_STALE_DAYS = 30;
    static final String STALE_LABEL = "可能已过时";

    private final LongTermMemory longTermMemory;
    private final Duration staleAfter;
    private final Clock clock;

    public MemoryRetriever(LongTermMemory longTermMemory) {
        this(longTermMemory, configuredStaleAfter(), Clock.systemDefaultZone());
    }

    /**
     * @param staleAfter 超过多久未核实视为可能过时；null 或非正数表示不标注
     */
    MemoryRetriever(LongTermMemory longTermMemory, Duration staleAfter, Clock clock) {
        this.longTermMemory = longTermMemory;
        this.staleAfter = staleAfter;
        this.clock = clock;
    }

    /**
     * 检索与查询最相关的记忆
     *
     * @param query 查询文本
     * @param limit 返回条数上限
     * @return 按相关度排序的记忆列表
     */
    public List<MemoryEntry> retrieve(String query, int limit) {
        return retrieveLongTerm(query, limit);
    }

    /**
     * 仅从长期记忆中检索稳定事实，用于 system prompt 注入。
     *
     * 当前轮用户输入和短期对话已经在 message history 里，不应再次以"相关记忆"身份
     * 注入给模型，否则容易让模型把当前请求误读成历史事实。
     */
    public List<MemoryEntry> retrieveLongTerm(String query, int limit) {
        return retrieveLongTerm(query, limit, null);
    }

    public List<MemoryEntry> retrieveLongTerm(String query, int limit, String projectKey) {
        return longTermMemory.getAll().stream()
                .filter(entry -> LongTermMemory.isVisibleInProject(entry, projectKey))
                .map(entry -> new ScoredEntry(entry, computeRelevanceScore(entry, query) * 1.2, false))
                .filter(scoredEntry -> scoredEntry.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredEntry::score).reversed())
                .limit(limit)
                .map(ScoredEntry::entry)
                .collect(Collectors.toList());
    }

    /**
     * 构建上下文：将相关记忆组装成文本，用于注入到 LLM 的 system prompt 中
     */
    public String buildContextForQuery(String query, int maxTokens) {
        return buildContextForQuery(query, maxTokens, null);
    }

    public String buildContextForQuery(String query, int maxTokens, String projectKey) {
        List<MemoryEntry> relevant = retrieveLongTerm(query, 10, projectKey);
        if (relevant.isEmpty()) return "";

        StringBuilder context = new StringBuilder();
        context.append("## 相关长期记忆\n\n");
        context.append("以下记忆是线索而不是事实；涉及版本、配置、路径等可能变化的信息，行动前先对照当前文件核实。\n\n");

        int usedTokens = 0;
        for (MemoryEntry entry : relevant) {
            if (usedTokens + entry.getTokenCount() > maxTokens) break;

            context.append(formatEntry(entry)).append("\n");
            usedTokens += entry.getTokenCount();
        }

        context.append("\n");
        return context.toString();
    }

    public boolean isStale(MemoryEntry entry) {
        if (staleAfter == null || staleAfter.isZero() || staleAfter.isNegative()) {
            return false;
        }
        return entry.getLastVerifiedAt().plus(staleAfter).isBefore(clock.instant());
    }

    private String formatEntry(MemoryEntry entry) {
        StringBuilder line = new StringBuilder("- [").append(entry.getType()).append("]");
        if ("true".equals(entry.getMetadata().get("verification_pending"))) {
            line.append("[自动提取，待核实]");
        }
        boolean stale = isStale(entry);
        if (stale) {
            line.append("[").append(STALE_LABEL).append("]");
        }
        boolean pending = "true".equals(entry.getMetadata().get("verification_pending"));
        line.append(" ").append(entry.getContent())
                .append("（写入 ").append(date(entry.getTimestamp()));
        if (pending) {
            line.append("，尚未经用户核实");
        } else {
            line.append("，最后核实 ").append(date(entry.getLastVerifiedAt()));
        }
        if (stale) {
            line.append("，已超过 ").append(staleAfter.toDays()).append(" 天未核实，使用前必须先核实");
        }
        return line.append("）").toString();
    }

    private LocalDate date(Instant instant) {
        return LocalDate.ofInstant(instant, clock.getZone());
    }

    private static Duration configuredStaleAfter() {
        String raw = System.getProperty(STALE_DAYS_PROPERTY);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(STALE_DAYS_ENV);
        }
        if (raw == null || raw.isBlank()) {
            return Duration.ofDays(DEFAULT_STALE_DAYS);
        }
        try {
            return Duration.ofDays(Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            return Duration.ofDays(DEFAULT_STALE_DAYS);
        }
    }

    /**
     * 计算记忆条目与查询的相关度分数
     */
    private double computeRelevanceScore(MemoryEntry entry, String query) {
        String contentLower = entry.getContent().toLowerCase();
        String queryLower = query.toLowerCase();

        // 1. 精确匹配加分
        if (contentLower.contains(queryLower)) {
            return 1.0;
        }

        // 2. 关键词匹配
        Set<String> queryWords = MemoryQueryTokenizer.tokenize(queryLower);
        int matchedWords = 0;
        for (String word : queryWords) {
            if (!word.isEmpty() && contentLower.contains(word)) {
                matchedWords++;
            }
        }

        if (matchedWords == 0) return 0;

        double keywordScore = (double) matchedWords / queryWords.size();

        // 3. 时间衰减（越近分数越高，简单实现）
        long ageMs = System.currentTimeMillis() - entry.getTimestamp().toEpochMilli();
        double ageHours = ageMs / (1000.0 * 60 * 60);
        double timeDecay = Math.max(0.5, 1.0 - ageHours / 24.0); // 24小时内从1.0衰减到0.5

        return keywordScore * timeDecay;
    }

    private record ScoredEntry(MemoryEntry entry, double score, boolean fromShortTerm) {}
}
