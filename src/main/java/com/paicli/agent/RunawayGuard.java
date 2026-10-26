package com.paicli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.memory.InternalUserMessages;
import com.paicli.tool.ToolRegistry.ToolExecutionResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 单次任务内的重复检测：同一动作或同一类错误连续出现到阈值时，给模型注入一次换思路的提醒。
 *
 * <p>参考 MiniMax Code 的 runaway guard，只移植会触发提醒的两类信号：
 * 同一动作连续重复（工具名 + 参数完全相同），以及同一类错误连续出现。
 * 一个“步”是一次 LLM 回复触发的全部工具结果；某个键只要在某一步里没出现，计数就归零。</p>
 *
 * <p>它只提醒、不拦截工具，每次任务最多提醒一次。真正的停止由 {@link AgentBudget}
 * 的停滞兜底负责，默认窗口比这里的阈值大，模型被提醒后仍原样重复才会停。</p>
 */
public final class RunawayGuard {

    static final int DEFAULT_REMIND_AFTER = 3;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_ERROR_TEXT_CHARS = 4096;
    private static final int MAX_ERROR_FAMILY_CHARS = 1024;
    private static final List<ErrorCategory> ERROR_CATEGORIES = List.of(
            new ErrorCategory("timeout", "timeout|timed out|deadline exceeded|超时"),
            new ErrorCategory("rate_limit", "rate.?limit|too many requests|\\b429\\b|限流"),
            new ErrorCategory("network", "network|econn|socket|dns|connection reset|网络"),
            new ErrorCategory("auth", "unauth|invalid api key|\\b401\\b|鉴权|认证失败"),
            new ErrorCategory("permission", "permission|forbidden|access denied|\\b403\\b|权限|越界"),
            new ErrorCategory("not_found", "not found|enoent|\\b404\\b|不存在|未找到|找不到"),
            new ErrorCategory("invalid_argument", "invalid argument|validation failed|bad request|\\b400\\b|参数"),
            new ErrorCategory("process_exit", "exit code|non-zero|process failed|退出码"));
    private static final String TURN_ONLY_SUFFIX = "这是只对本次任务有效的临时运行提醒，不是用户偏好，也不是长期规则；"
            + "不要保存它，也不要把它总结进长期记忆、Skill 或其他持久化指令。";

    enum Signal { EXACT_ACTION_REPEAT, SAME_ERROR_FAMILY }

    private final int remindAfter;
    private Map<String, Integer> actionStreaks = Map.of();
    private Map<String, Integer> errorStreaks = Map.of();
    private boolean reminderAttempted;

    public RunawayGuard() {
        this(DEFAULT_REMIND_AFTER);
    }

    RunawayGuard(int remindAfter) {
        if (remindAfter < DEFAULT_REMIND_AFTER) {
            throw new IllegalArgumentException("remindAfter must be >= " + DEFAULT_REMIND_AFTER);
        }
        this.remindAfter = remindAfter;
    }

    /**
     * 观察一步的工具结果。某类重复刚好跨过阈值、且本次任务还没提醒过时，返回提醒文本。
     */
    public Optional<String> observe(List<ToolExecutionResult> results) {
        Map<String, Integer> actionCounts = new HashMap<>();
        Map<String, Integer> errorCounts = new HashMap<>();
        for (ToolExecutionResult result : results == null ? List.<ToolExecutionResult>of() : results) {
            // 被策略或用户拒绝的调用没有真正执行，不算重复，也会打断连续计数
            if (result == null || result.name() == null || isBlocked(result.result())) {
                continue;
            }
            String actionKey = actionKey(result.name(), result.argumentsJson());
            actionCounts.merge(actionKey, 1, Integer::sum);
            if (!result.successful()) {
                errorFamily(result.result()).ifPresent(family ->
                        errorCounts.merge(digest(family + "\0" + actionKey), 1, Integer::sum));
            }
        }
        boolean actionCrossed = false;
        boolean errorCrossed = false;
        Map<String, Integer> nextActions = new HashMap<>();
        for (Map.Entry<String, Integer> entry : actionCounts.entrySet()) {
            actionCrossed |= advance(actionStreaks, nextActions, entry.getKey(), entry.getValue());
        }
        Map<String, Integer> nextErrors = new HashMap<>();
        for (Map.Entry<String, Integer> entry : errorCounts.entrySet()) {
            errorCrossed |= advance(errorStreaks, nextErrors, entry.getKey(), entry.getValue());
        }
        actionStreaks = Map.copyOf(nextActions);
        errorStreaks = Map.copyOf(nextErrors);

        if (reminderAttempted || (!actionCrossed && !errorCrossed)) {
            return Optional.empty();
        }
        reminderAttempted = true;
        return Optional.of(reminder(errorCrossed ? Signal.SAME_ERROR_FAMILY : Signal.EXACT_ACTION_REPEAT));
    }

    boolean reminderAttempted() {
        return reminderAttempted;
    }

    private boolean advance(Map<String, Integer> previous, Map<String, Integer> next, String key, int count) {
        int prior = previous.getOrDefault(key, 0);
        int occurrences = prior + count;
        next.put(key, occurrences);
        return prior < remindAfter && occurrences >= remindAfter;
    }

    private String reminder(Signal signal) {
        String guidance = switch (signal) {
            case EXACT_ACTION_REPEAT -> InternalUserMessages.RUNAWAY_REMINDER_PREFIX
                    + " 同一个工具调用（参数完全相同）已经连续出现 " + remindAfter
                    + " 次。不要再原样重复。先看已经拿到的结果，然后换一个策略并说明预期会带来什么具体变化，"
                    + "或者说明卡在了哪里。重复本身不能说明任务已经完成，也不能说明任务无法完成。";
            case SAME_ERROR_FAMILY -> InternalUserMessages.RUNAWAY_REMINDER_PREFIX
                    + " 同一类工具错误已经连续出现 " + remindAfter
                    + " 次。不要原样重试同一条路。先分析原因，改变一个可控的变量，或者换一条路。"
                    + "不要因为这个信号就断定整个任务失败了。";
        };
        return guidance + TURN_ONLY_SUFFIX;
    }

    private static boolean isBlocked(String text) {
        if (text == null) {
            return false;
        }
        String value = text.stripLeading();
        return value.startsWith("🛡️") || value.startsWith("[HITL]") || value.startsWith("[AUTO]")
                || value.startsWith("用户取消了");
    }

    static Optional<String> errorFamily(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String sample = text.length() > MAX_ERROR_TEXT_CHARS ? text.substring(0, MAX_ERROR_TEXT_CHARS) : text;
        String lower = sample.toLowerCase(Locale.ROOT);
        for (ErrorCategory category : ERROR_CATEGORIES) {
            if (category.pattern().matcher(lower).find()) {
                return Optional.of("category:" + category.name());
            }
        }
        String collapsed = lower.replaceAll("\\s+", " ").trim();
        if (collapsed.length() > MAX_ERROR_FAMILY_CHARS) {
            collapsed = collapsed.substring(0, MAX_ERROR_FAMILY_CHARS);
        }
        return Optional.of("text:" + collapsed);
    }

    static String actionKey(String toolName, String argumentsJson) {
        return digest(toolName + "\0" + canonicalArguments(argumentsJson));
    }

    /** 参数按键名排序后再比较，模型换了键的顺序也算同一个动作。 */
    private static String canonicalArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return "";
        }
        try {
            return MAPPER.writeValueAsString(canonicalize(MAPPER.readTree(argumentsJson)));
        } catch (Exception e) {
            return argumentsJson.trim();
        }
    }

    private static Object canonicalize(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> sorted = new TreeMap<>();
            node.fields().forEachRemaining(field -> sorted.put(field.getKey(), canonicalize(field.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            return java.util.stream.StreamSupport.stream(node.spliterator(), false)
                    .map(RunawayGuard::canonicalize)
                    .toList();
        }
        return node;
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private record ErrorCategory(String name, Pattern pattern) {
        private ErrorCategory(String name, String regex) {
            this(name, Pattern.compile(regex));
        }
    }
}
