package com.paicli.memory;

import com.paicli.llm.LlmClient;
import com.paicli.context.ContextProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Memory 管理器 - Memory 系统的门面类
 *
 * 统一管理跨会话长期记忆、检索和 token 统计。
 *
 * <p>当前会话的短期上下文由各 Agent 自己的 conversationHistory 维护，并由
 * {@link AutoCompactionManager} 直接压缩；这里不再复制保存第二份消息列表。</p>
 */
public class MemoryManager {
    private static final Logger log = LoggerFactory.getLogger(MemoryManager.class);
    private final LongTermMemory longTermMemory;
    private final MemoryRetriever retriever;
    private final AutoFactExtractor autoFactExtractor;
    private boolean autoFactExtractionEnabled;
    private TokenBudget tokenBudget;
    private ContextProfile contextProfile;
    private String currentProject;
    private ExternalContextTracker externalContextTracker = new ExternalContextTracker(false);

    public MemoryManager(LlmClient llmClient) {
        this(llmClient, ContextProfile.from(llmClient), null);
    }

    /** 兼容旧调用签名；短期上下文不再由 MemoryManager 保存。 */
    public MemoryManager(LlmClient llmClient, int ignoredShortTermBudget, int contextWindow) {
        this(llmClient, ignoredShortTermBudget, contextWindow, null);
    }

    /** 兼容旧调用签名；第二个参数不再参与预算计算。 */
    public MemoryManager(LlmClient llmClient, int ignoredShortTermBudget, int contextWindow,
                         LongTermMemory longTermMemory) {
        this(llmClient, ContextProfile.custom(contextWindow), longTermMemory);
    }

    private MemoryManager(LlmClient llmClient, ContextProfile contextProfile, LongTermMemory longTermMemory) {
        this.contextProfile = contextProfile;
        this.longTermMemory = longTermMemory != null ? longTermMemory : new LongTermMemory();
        this.retriever = new MemoryRetriever(this.longTermMemory);
        this.autoFactExtractor = new AutoFactExtractor(llmClient);
        this.tokenBudget = new TokenBudget(contextProfile.maxContextWindow());
        this.currentProject = defaultProjectKey();
    }

    public void setLlmClient(LlmClient llmClient) {
        applyContextProfile(ContextProfile.from(llmClient));
        autoFactExtractor.setLlmClient(llmClient);
    }

    public void setAutoFactExtractionEnabled(boolean enabled) {
        this.autoFactExtractionEnabled = enabled;
    }

    public boolean isAutoFactExtractionEnabled() {
        return autoFactExtractionEnabled;
    }

    /**
     * After a completed top-level turn, extract only source-backed facts from the submitted
     * user text. No assistant, tool, expanded resource, or compressed-summary text is used.
     */
    public List<MemoryEntry> extractFactsFromUserTurn(String submittedUserInput) {
        if (!autoFactExtractionEnabled || blocksAutomaticMemory()) return List.of();
        AutoFactExtractor.Result extraction;
        try {
            extraction = autoFactExtractor.extractFacts(submittedUserInput);
        } catch (Exception e) {
            log.warn("automatic fact extraction failed; conversation result remains available ({})",
                    e.getClass().getSimpleName());
            return List.of();
        }
        if (extraction.called()) {
            tokenBudget.recordUsage(extraction.inputTokens(), extraction.outputTokens(),
                    extraction.cachedInputTokens());
        }
        if (blocksAutomaticMemory() || com.paicli.runtime.CancellationContext.isCancelled()) return List.of();
        List<MemoryEntry> saved = new ArrayList<>();
        for (String fact : extraction.facts()) {
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("source", "auto_user_fact");
            metadata.put("scope", "project");
            metadata.put("project", currentProject);
            metadata.put("verification_pending", "true");
            MemoryEntry entry = new MemoryEntry(
                    "fact-" + UUID.randomUUID().toString().substring(0, 8),
                    fact, MemoryEntry.MemoryType.FACT, metadata,
                    MemoryEntry.estimateTokens(fact));
            if (longTermMemory.writeAutomatic(entry)) saved.add(entry);
        }
        return List.copyOf(saved);
    }

    public void applyContextProfile(ContextProfile contextProfile) {
        this.contextProfile = contextProfile;
        this.tokenBudget = new TokenBudget(contextProfile.maxContextWindow());
    }

    /**
     * 绑定会话级外部内容标记（通常来自共享的 ToolRegistry）。显式保存的记忆会据此在 metadata
     * 中记录 {@code external_context=true} 及来源工具；自动写记忆的路径据此跳过。
     */
    public void setExternalContextTracker(ExternalContextTracker externalContextTracker) {
        if (externalContextTracker != null) {
            this.externalContextTracker = externalContextTracker;
        }
    }

    public ExternalContextTracker getExternalContextTracker() {
        return externalContextTracker;
    }

    /** 自动写记忆路径（非用户显式保存）是否必须跳过：防护开启且会话接触过外部内容。 */
    public boolean blocksAutomaticMemory() {
        return externalContextTracker.blocksAutomaticMemory();
    }

    public void setProjectPath(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            return;
        }
        this.currentProject = normalizeProjectKey(projectPath);
    }

    /**
     * 存储关键事实到长期记忆
     */
    public MemoryWriteResult storeFact(String fact) {
        return storeFact(fact, "project");
    }

    public MemoryWriteResult storeFact(String fact, String scope) {
        return storeFact(fact, scope, null, false);
    }

    /**
     * 显式保存记忆：等价条目只刷新核实时间，高度相似但不一致的条目不写入并返回冲突说明。
     *
     * @param replaceId     用户选择用新内容替换的旧条目 id，可为 null
     * @param allowConflict 用户明确要求新旧两条都保留
     */
    public MemoryWriteResult storeFact(String fact, String scope, String replaceId, boolean allowConflict) {
        String normalizedScope = normalizeScope(scope);
        Map<String, String> metadata = factMetadata(normalizedScope);
        MemoryEntry entry = new MemoryEntry(
                "fact-" + UUID.randomUUID().toString().substring(0, 8),
                fact,
                MemoryEntry.MemoryType.FACT,
                metadata,
                MemoryEntry.estimateTokens(fact)
        );
        return longTermMemory.write(entry, replaceId, allowConflict, currentProject);
    }

    private Map<String, String> factMetadata(String normalizedScope) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("source", "fact");
        metadata.put("scope", normalizedScope);
        if (!"global".equals(normalizedScope)) {
            metadata.put("project", currentProject);
        }
        List<String> externalSources = externalContextTracker.sources();
        if (!externalSources.isEmpty()) {
            // 显式保存仍然放行，但记录这条记忆写入时会话里已有外部内容，便于审计和清理。
            metadata.put(ExternalContextTracker.METADATA_FLAG, "true");
            metadata.put(ExternalContextTracker.METADATA_SOURCES, String.join(",", externalSources));
        }
        return metadata;
    }

    /** 用户选择用新内容替换某条旧记忆；新条目沿用旧条目的作用域。 */
    public MemoryWriteResult replaceFact(String id, String fact) {
        String scope = longTermMemory.retrieve(id == null ? "" : id.trim())
                .map(LongTermMemory::scopeOf)
                .orElse("project");
        return storeFact(fact, scope, id, false);
    }

    /** 超过配置天数未核实的记忆，/memory list 和检索注入都会标注“可能已过时”。 */
    public boolean isStale(MemoryEntry entry) {
        return retriever.isStale(entry);
    }

    /** 用户确认记忆仍然成立时刷新最后核实时间。 */
    public Optional<MemoryEntry> verifyLongTerm(String id) {
        return longTermMemory.markVerified(id, Instant.now());
    }

    /**
     * 检索与查询最相关的记忆
     */
    public List<MemoryEntry> retrieveRelevant(String query, int limit) {
        return retriever.retrieve(query, limit);
    }

    public List<MemoryEntry> listLongTerm() {
        return longTermMemory.getAll();
    }

    public List<MemoryEntry> searchLongTerm(String query, int limit) {
        return longTermMemory.search(query, limit, currentProject);
    }

    public boolean deleteLongTerm(String id) {
        return longTermMemory.delete(id);
    }

    /**
     * 构建用于 LLM 的记忆上下文
     */
    public String buildContextForQuery(String query, int maxTokens) {
        return retriever.buildContextForQuery(query, maxTokens, currentProject);
    }

    /**
     * 记录 token 使用
     */
    public void recordTokenUsage(int inputTokens, int outputTokens) {
        tokenBudget.recordUsage(inputTokens, outputTokens);
    }

    public void recordTokenUsage(int inputTokens, int outputTokens, int cachedInputTokens) {
        tokenBudget.recordUsage(inputTokens, outputTokens, cachedInputTokens);
    }

    /**
     * 清空长期记忆
     */
    public void clearLongTerm() {
        longTermMemory.clear();
    }

    /**
     * 获取记忆系统的整体状态
     */
    public String getSystemStatus() {
        return "上下文策略: " + contextProfile.summary() + "\n" +
                "短期上下文: 由当前 Agent conversationHistory 维护\n" +
                "自动事实提取: " + (autoFactExtractionEnabled
                        ? (blocksAutomaticMemory() ? "开启（外部内容防护暂停）" : "开启") : "关闭") + "\n" +
                longTermMemory.getStatusSummary() + "\n" +
                tokenBudget.getUsageReport();
    }

    // Getter
    public LongTermMemory getLongTermMemory() { return longTermMemory; }
    public TokenBudget getTokenBudget() { return tokenBudget; }
    public ContextProfile getContextProfile() { return contextProfile; }

    public String getCurrentProject() { return currentProject; }

    private static String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "project";
        }
        String normalized = scope.trim().toLowerCase();
        return "global".equals(normalized) ? "global" : "project";
    }

    private static String defaultProjectKey() {
        return normalizeProjectKey(System.getProperty("user.dir"));
    }

    private static String normalizeProjectKey(String path) {
        try {
            Path candidate = Path.of(path).toAbsolutePath().normalize();
            if (java.nio.file.Files.exists(candidate)) {
                return candidate.toRealPath().toString();
            }
            return candidate.toString();
        } catch (Exception e) {
            return Path.of(path).toAbsolutePath().normalize().toString();
        }
    }
}
