package com.paicli.memory;

import com.paicli.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 摘要之前的中间一档：清理旧工具结果（参考 Anthropic context editing 的 {@code clear_tool_uses}）。
 *
 * <p>三档压缩按代价从小到大排列：超大输出先由 {@code ToolResultOffloader} 卸载到会话文件；
 * 上下文继续增长、达到本类阈值时，把较早的 tool 消息正文换成一行占位说明；仍然不够才由
 * {@link ConversationHistoryCompactor} / {@link SessionMemoryCompactor} 做有损摘要。</p>
 *
 * <p>只替换 {@code role=tool} 消息的正文，消息本身和 {@code tool_call_id} 原样保留，所以
 * assistant 的 tool_call 与 tool_result 仍然一一配对。最近 {@code keepRecent} 条工具结果和
 * 排除列表中的工具不清理；已经清理过或本身很短的结果跳过。</p>
 */
public final class ToolResultClearer {
    private static final Logger log = LoggerFactory.getLogger(ToolResultClearer.class);

    public static final String ENABLED_PROPERTY = "paicli.tool.result.clearing.enabled";
    public static final String ENABLED_ENV = "PAICLI_TOOL_RESULT_CLEARING_ENABLED";
    public static final String TRIGGER_PROPERTY = "paicli.tool.result.clearing.trigger.tokens";
    public static final String TRIGGER_ENV = "PAICLI_TOOL_RESULT_CLEARING_TRIGGER_TOKENS";
    public static final String KEEP_PROPERTY = "paicli.tool.result.clearing.keep";
    public static final String KEEP_ENV = "PAICLI_TOOL_RESULT_CLEARING_KEEP";
    public static final String EXCLUDE_PROPERTY = "paicli.tool.result.clearing.exclude.tools";
    public static final String EXCLUDE_ENV = "PAICLI_TOOL_RESULT_CLEARING_EXCLUDE_TOOLS";

    /** 与 Anthropic clear_tool_uses 默认值一致：保留最近 3 次工具结果。 */
    static final int DEFAULT_KEEP_RECENT = 3;
    /** 与 Anthropic clear_tool_uses 默认触发点一致：约 100k input tokens 封顶。 */
    static final int DEFAULT_MAX_TRIGGER_TOKENS = 100_000;
    /** 小窗口按摘要阈值的 60% 触发，保证清理总是先于摘要。 */
    static final double DEFAULT_TRIGGER_RATIO = 0.60;
    /** 比占位说明还短的结果清理了也省不下空间。 */
    static final int MIN_CLEARABLE_CHARS = 400;

    public static final String PLACEHOLDER_PREFIX = "[已清理的旧工具结果]";
    private static final Pattern TOOL_NAME_ATTRIBUTE = Pattern.compile("^<tool_result tool=\"([^\"]+)\"");
    private static final Pattern OFFLOAD_FILE = Pattern.compile("(?m)^文件: (\\.paicli/tool-outputs/\\S+)$");

    private final Config config;

    public ToolResultClearer(Config config) {
        this.config = config == null ? Config.defaults() : config;
    }

    public static ToolResultClearer fromConfiguration() {
        return new ToolResultClearer(Config.fromConfiguration());
    }

    public Config config() {
        return config;
    }

    /** 清理触发阈值：显式配置优先，否则取 min(100k, 摘要阈值 × 0.6)，并始终低于摘要阈值。 */
    public int triggerTokens(int compactionTriggerTokens) {
        if (compactionTriggerTokens <= 0) return 0;
        int derived = config.triggerTokensOverride() > 0
                ? config.triggerTokensOverride()
                : Math.min(DEFAULT_MAX_TRIGGER_TOKENS, (int) Math.floor(compactionTriggerTokens * DEFAULT_TRIGGER_RATIO));
        return Math.max(1, Math.min(derived, compactionTriggerTokens - 1));
    }

    /** 达到清理阈值时原地替换旧工具结果；未启用或未达阈值时不做任何修改。 */
    public Result clearIfNeeded(List<LlmClient.Message> history, int compactionTriggerTokens) {
        if (!config.enabled() || history == null || history.isEmpty()) return Result.none();
        int trigger = triggerTokens(compactionTriggerTokens);
        if (trigger <= 0) return Result.none();
        int before = TokenBudget.estimateMessagesTokens(history);
        if (before < trigger) return Result.none();
        return clear(history, before);
    }

    /** 不看阈值，立即清理最近 keepRecent 条之外的旧工具结果。 */
    public Result clearNow(List<LlmClient.Message> history) {
        if (history == null || history.isEmpty()) return Result.none();
        return clear(history, TokenBudget.estimateMessagesTokens(history));
    }

    private Result clear(List<LlmClient.Message> history, int beforeTokens) {
        Map<String, String> toolNamesByCallId = toolNamesByCallId(history);
        List<Integer> toolIndices = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            if ("tool".equals(history.get(i).role())) toolIndices.add(i);
        }
        int clearableEnd = Math.max(0, toolIndices.size() - config.keepRecent());
        int cleared = 0;
        for (int n = 0; n < clearableEnd; n++) {
            int index = toolIndices.get(n);
            LlmClient.Message message = history.get(index);
            String toolName = resolveToolName(message, toolNamesByCallId);
            if (!isClearable(message, toolName)) continue;
            history.set(index, LlmClient.Message.tool(message.toolCallId(), placeholder(toolName, message.content())));
            cleared++;
        }
        if (cleared == 0) return Result.none();
        int afterTokens = TokenBudget.estimateMessagesTokens(history);
        log.info("cleared {} old tool result(s): tokens {} -> {}, kept recent {}",
                cleared, beforeTokens, afterTokens, config.keepRecent());
        return new Result(cleared, beforeTokens, afterTokens);
    }

    private boolean isClearable(LlmClient.Message message, String toolName) {
        String content = message.content();
        if (content == null || content.length() < MIN_CLEARABLE_CHARS) return false;
        if (content.startsWith(PLACEHOLDER_PREFIX)) return false;
        return !config.excludedTools().contains(toolName.toLowerCase(Locale.ROOT));
    }

    static String placeholder(String toolName, String originalContent) {
        StringBuilder sb = new StringBuilder(PLACEHOLDER_PREFIX)
                .append(" 工具 ").append(toolName)
                .append(" 的这次输出（约 ").append(originalContent.length())
                .append(" 字符）已从上下文清理以节省空间。需要时重新调用该工具获取最新结果");
        String offloadFile = offloadFile(originalContent);
        if (offloadFile != null) {
            sb.append("；完整输出仍保存在 ").append(offloadFile)
                    .append("，可用 read_file 按 offset/limit 读回");
        }
        return sb.append("。").toString();
    }

    private static String offloadFile(String content) {
        Matcher matcher = OFFLOAD_FILE.matcher(content == null ? "" : content);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static Map<String, String> toolNamesByCallId(List<LlmClient.Message> history) {
        Map<String, String> names = new HashMap<>();
        for (LlmClient.Message message : history) {
            if (message.toolCalls() == null) continue;
            for (LlmClient.ToolCall call : message.toolCalls()) {
                if (call != null && call.id() != null && call.function() != null) {
                    names.put(call.id(), call.function().name());
                }
            }
        }
        return names;
    }

    private static String resolveToolName(LlmClient.Message message, Map<String, String> byCallId) {
        String name = message.toolCallId() == null ? null : byCallId.get(message.toolCallId());
        if (name != null && !name.isBlank()) return name;
        Matcher matcher = TOOL_NAME_ATTRIBUTE.matcher(message.content() == null ? "" : message.content());
        return matcher.find() ? matcher.group(1) : "unknown";
    }

    public record Result(int clearedCount, int beforeTokens, int afterTokens) {
        public static Result none() {
            return new Result(0, 0, 0);
        }

        public boolean cleared() {
            return clearedCount > 0;
        }
    }

    public record Config(boolean enabled, int triggerTokensOverride, int keepRecent, Set<String> excludedTools) {
        public Config {
            keepRecent = Math.max(0, keepRecent);
            Set<String> normalized = new LinkedHashSet<>();
            if (excludedTools != null) {
                excludedTools.stream()
                        .filter(name -> name != null && !name.isBlank())
                        .map(name -> name.trim().toLowerCase(Locale.ROOT))
                        .forEach(normalized::add);
            }
            excludedTools = Set.copyOf(normalized);
        }

        public static Config defaults() {
            return new Config(true, 0, DEFAULT_KEEP_RECENT, Set.of());
        }

        static Config fromConfiguration() {
            String enabled = configValue(ENABLED_PROPERTY, ENABLED_ENV);
            String trigger = configValue(TRIGGER_PROPERTY, TRIGGER_ENV);
            String keep = configValue(KEEP_PROPERTY, KEEP_ENV);
            String exclude = configValue(EXCLUDE_PROPERTY, EXCLUDE_ENV);
            return new Config(
                    enabled == null || !isFalse(enabled),
                    parseInt(trigger, 0),
                    parseInt(keep, DEFAULT_KEEP_RECENT),
                    exclude == null ? Set.of() : new LinkedHashSet<>(List.of(exclude.split("\\s*,\\s*"))));
        }
    }

    private static boolean isFalse(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return "false".equals(value) || "0".equals(value) || "no".equals(value) || "off".equals(value);
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String configValue(String property, String env) {
        String raw = System.getProperty(property);
        if (raw == null || raw.isBlank()) raw = System.getenv(env);
        return raw == null || raw.isBlank() ? null : raw;
    }
}
