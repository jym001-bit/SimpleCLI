package com.paicli.memory;

import com.paicli.llm.LlmClient;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * 自动压缩协调器，按代价从小到大执行：
 * <ol>
 *   <li>超大工具输出在回灌时已由 {@code ToolResultOffloader} 卸载（不在本类）；</li>
 *   <li>达到较低的清理阈值时，{@link ToolResultClearer} 把旧工具结果换成占位说明（可恢复）；</li>
 *   <li>仍达到摘要阈值时，优先使用实验性的会话记忆摘要，失败后回退到完整对话摘要（有损）。</li>
 * </ol>
 */
public class AutoCompactionManager {
    public static final String SESSION_MEMORY_PROPERTY = "paicli.compaction.session-memory.enabled";
    public static final String SESSION_MEMORY_ENV = "PAICLI_SESSION_MEMORY_COMPACTION_ENABLED";

    private final ConversationHistoryCompactor fullCompactor;
    private final SessionMemoryCompactor sessionMemoryCompactor;
    private final ToolResultClearer toolResultClearer;

    public AutoCompactionManager(LlmClient llmClient) {
        this(llmClient, sessionMemoryEnabledByConfiguration());
    }

    AutoCompactionManager(LlmClient llmClient, boolean sessionMemoryEnabled) {
        this(new ConversationHistoryCompactor(llmClient),
                new SessionMemoryCompactor(llmClient, sessionMemoryEnabled),
                ToolResultClearer.fromConfiguration());
    }

    /** 测试入口：不启用旧工具结果清理，只验证摘要路径。 */
    AutoCompactionManager(
            ConversationHistoryCompactor fullCompactor,
            SessionMemoryCompactor sessionMemoryCompactor) {
        this(fullCompactor, sessionMemoryCompactor,
                new ToolResultClearer(new ToolResultClearer.Config(false, 0, 0, java.util.Set.of())));
    }

    AutoCompactionManager(
            ConversationHistoryCompactor fullCompactor,
            SessionMemoryCompactor sessionMemoryCompactor,
            ToolResultClearer toolResultClearer) {
        this.fullCompactor = fullCompactor;
        this.sessionMemoryCompactor = sessionMemoryCompactor;
        this.toolResultClearer = toolResultClearer;
    }

    public void setLlmClient(LlmClient llmClient) {
        fullCompactor.setLlmClient(llmClient);
        sessionMemoryCompactor.setLlmClient(llmClient);
    }

    /**
     * @param triggerTokens 摘要阈值；旧工具结果清理阈值由 {@link ToolResultClearer#triggerTokens(int)}
     *                      从它派生，始终更低，所以清理先于摘要发生
     */
    public Result compactIfNeeded(List<LlmClient.Message> history, int triggerTokens) {
        if (triggerTokens <= 0) return Result.none();
        int cleared = toolResultClearer.clearIfNeeded(history, triggerTokens).clearedCount();
        sessionMemoryCompactor.prepareIfNeeded(history, triggerTokens);
        if (TokenBudget.estimateMessagesTokens(history) < triggerTokens) {
            return cleared > 0 ? new Result(false, Strategy.TOOL_RESULT_CLEARING, cleared) : Result.none();
        }
        if (sessionMemoryCompactor.compactIfReady(history, triggerTokens)) {
            return new Result(true, Strategy.SESSION_MEMORY, cleared);
        }
        boolean compacted = fullCompactor.compactIfNeeded(history, triggerTokens);
        if (compacted) {
            sessionMemoryCompactor.clear(history);
            return new Result(true, Strategy.FULL_SUMMARY, cleared);
        }
        return cleared > 0 ? new Result(false, Strategy.TOOL_RESULT_CLEARING, cleared) : Result.none();
    }

    /** 当前模型下旧工具结果清理的触发阈值；未启用时返回 0。 */
    public int toolResultClearTriggerTokens(int compactionTriggerTokens) {
        return toolResultClearer.config().enabled()
                ? toolResultClearer.triggerTokens(compactionTriggerTokens)
                : 0;
    }

    public ToolResultClearer.Config toolResultClearingConfig() {
        return toolResultClearer.config();
    }

    /** 手动 /compact 始终使用稳定的完整摘要路径。 */
    public Result compactNow(List<LlmClient.Message> history) {
        sessionMemoryCompactor.clear(history);
        boolean compacted = fullCompactor.compactNow(history);
        return compacted
                ? new Result(true, Strategy.FULL_SUMMARY)
                : Result.none();
    }

    public void clear(List<LlmClient.Message> history) {
        sessionMemoryCompactor.clear(history);
    }

    public boolean isSessionMemoryEnabled() {
        return sessionMemoryCompactor.isEnabled();
    }

    public enum Strategy {
        NONE,
        /** 只清理了旧工具结果，没有做摘要。 */
        TOOL_RESULT_CLEARING,
        SESSION_MEMORY,
        FULL_SUMMARY
    }

    /**
     * @param compacted          是否做了摘要压缩（会话记忆或完整摘要）
     * @param clearedToolResults 本次在摘要之前清理的旧工具结果条数
     */
    public record Result(boolean compacted, Strategy strategy, int clearedToolResults) {
        public Result(boolean compacted, Strategy strategy) {
            this(compacted, strategy, 0);
        }

        static Result none() {
            return new Result(false, Strategy.NONE, 0);
        }
    }

    static boolean sessionMemoryEnabledByConfiguration() {
        String property = System.getProperty(SESSION_MEMORY_PROPERTY);
        if (property != null) return truthy(property);
        String env = System.getenv(SESSION_MEMORY_ENV);
        if (env != null) return truthy(env);
        String projectDotEnv = readDotEnv(new File(".env"), SESSION_MEMORY_ENV);
        if (projectDotEnv != null) return truthy(projectDotEnv);
        return truthy(readDotEnv(new File(System.getProperty("user.home"), ".env"), SESSION_MEMORY_ENV));
    }

    private static boolean truthy(String value) {
        if (value == null) return false;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "1".equals(normalized) || "true".equals(normalized)
                || "yes".equals(normalized) || "on".equals(normalized);
    }

    private static String readDotEnv(File file, String key) {
        if (file == null || !file.isFile()) return null;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                if (trimmed.startsWith(key + "=")) {
                    return trimmed.substring(key.length() + 1).trim();
                }
            }
        } catch (IOException ignored) {
            // 配置读取失败时保持默认关闭，完整摘要路径仍然可用。
        }
        return null;
    }
}
