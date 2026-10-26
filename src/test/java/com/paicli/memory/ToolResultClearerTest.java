package com.paicli.memory;

import com.paicli.context.ContextProfile;
import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultClearerTest {
    private static final String BIG = "x".repeat(2_000);

    @Test
    void clearsOlderToolResultsAndKeepsMostRecentOnes() {
        List<LlmClient.Message> history = historyWithToolRounds(5, "read_file");
        ToolResultClearer clearer = new ToolResultClearer(new ToolResultClearer.Config(true, 0, 3, Set.of()));
        int sizeBefore = history.size();

        ToolResultClearer.Result result = clearer.clearNow(history);

        assertEquals(2, result.clearedCount());
        assertTrue(result.afterTokens() < result.beforeTokens());
        assertEquals(sizeBefore, history.size(), "messages are replaced in place, never removed");
        List<LlmClient.Message> tools = history.stream().filter(m -> "tool".equals(m.role())).toList();
        assertTrue(tools.get(0).content().startsWith(ToolResultClearer.PLACEHOLDER_PREFIX));
        assertTrue(tools.get(0).content().contains("read_file"));
        assertTrue(tools.get(1).content().startsWith(ToolResultClearer.PLACEHOLDER_PREFIX));
        for (int i = 2; i < 5; i++) {
            assertFalse(tools.get(i).content().startsWith(ToolResultClearer.PLACEHOLDER_PREFIX));
        }
        assertToolPairsIntact(history);
    }

    @Test
    void placeholderPointsToOffloadFileWhenOutputWasOffloaded() {
        String offloaded = "<tool_result tool=\"execute_command\" trust=\"untrusted-data\">\n"
                + "[工具输出过大，完整内容已卸载到会话文件]\n"
                + "tool: execute_command\n"
                + "文件: .paicli/tool-outputs/session-1/001-execute_command.txt\n"
                + "--- 开头预览 ---\n" + BIG + "\n</tool_result>";
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("system"));
        history.add(LlmClient.Message.user("run"));
        addToolRound(history, "c1", "execute_command", offloaded);
        addToolRound(history, "c2", "read_file", BIG);

        new ToolResultClearer(new ToolResultClearer.Config(true, 0, 1, Set.of())).clearNow(history);

        String placeholder = history.get(3).content();
        assertTrue(placeholder.startsWith(ToolResultClearer.PLACEHOLDER_PREFIX), placeholder);
        assertTrue(placeholder.contains("execute_command"));
        assertTrue(placeholder.contains(".paicli/tool-outputs/session-1/001-execute_command.txt"), placeholder);
        assertTrue(placeholder.contains("重新调用"));
    }

    @Test
    void skipsExcludedShortAndAlreadyClearedResults() {
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("system"));
        history.add(LlmClient.Message.user("go"));
        addToolRound(history, "a", "load_skill", BIG);
        addToolRound(history, "b", "list_dir", "short");
        addToolRound(history, "c", "grep_code", BIG);
        addToolRound(history, "d", "read_file", BIG);
        ToolResultClearer clearer = new ToolResultClearer(
                new ToolResultClearer.Config(true, 0, 1, Set.of("LOAD_SKILL")));

        assertEquals(1, clearer.clearNow(history).clearedCount());
        assertEquals(BIG, history.get(3).content(), "excluded tool is kept");
        assertEquals("short", history.get(5).content(), "short result is not worth clearing");
        assertTrue(history.get(7).content().startsWith(ToolResultClearer.PLACEHOLDER_PREFIX));
        assertEquals(0, clearer.clearNow(history).clearedCount(), "clearing is idempotent");
    }

    @Test
    void doesNothingBelowTriggerOrWhenDisabled() {
        List<LlmClient.Message> history = historyWithToolRounds(6, "read_file");
        List<LlmClient.Message> snapshot = List.copyOf(history);
        int tokens = TokenBudget.estimateMessagesTokens(history);

        ToolResultClearer enabled = new ToolResultClearer(new ToolResultClearer.Config(true, tokens + 1, 3, Set.of()));
        assertFalse(enabled.clearIfNeeded(history, tokens * 10).cleared());
        ToolResultClearer disabled = new ToolResultClearer(new ToolResultClearer.Config(false, 1, 3, Set.of()));
        assertFalse(disabled.clearIfNeeded(history, tokens * 10).cleared());
        assertEquals(snapshot, history);

        ToolResultClearer lowTrigger = new ToolResultClearer(new ToolResultClearer.Config(true, 1, 3, Set.of()));
        assertEquals(3, lowTrigger.clearIfNeeded(history, tokens * 10).clearedCount());
    }

    @Test
    void triggerFollowsAnthropicDefaultAndStaysBelowSummaryThreshold() {
        ToolResultClearer clearer = new ToolResultClearer(ToolResultClearer.Config.defaults());
        int window200k = ContextProfile.custom(200_000).compressionTriggerTokens();
        int window1m = ContextProfile.custom(1_000_000).compressionTriggerTokens();
        int window32k = ContextProfile.custom(32_000).compressionTriggerTokens();

        assertEquals(100_000, clearer.triggerTokens(window200k));
        assertEquals(100_000, clearer.triggerTokens(window1m));
        assertEquals((int) Math.floor(window32k * 0.6), clearer.triggerTokens(window32k));
        assertTrue(clearer.triggerTokens(window32k) < window32k);

        ToolResultClearer override = new ToolResultClearer(new ToolResultClearer.Config(true, 500_000, 3, Set.of()));
        assertEquals(window200k - 1, override.triggerTokens(window200k), "never at or above the summary threshold");
    }

    @Test
    void configurationIsReadFromSystemProperties() {
        String[] keys = {ToolResultClearer.ENABLED_PROPERTY, ToolResultClearer.TRIGGER_PROPERTY,
                ToolResultClearer.KEEP_PROPERTY, ToolResultClearer.EXCLUDE_PROPERTY};
        String[] previous = new String[keys.length];
        for (int i = 0; i < keys.length; i++) previous[i] = System.getProperty(keys[i]);
        try {
            System.setProperty(ToolResultClearer.ENABLED_PROPERTY, "false");
            System.setProperty(ToolResultClearer.TRIGGER_PROPERTY, "50000");
            System.setProperty(ToolResultClearer.KEEP_PROPERTY, "5");
            System.setProperty(ToolResultClearer.EXCLUDE_PROPERTY, "read_file, web_fetch,read_file");

            ToolResultClearer.Config config = ToolResultClearer.fromConfiguration().config();

            assertFalse(config.enabled());
            assertEquals(50_000, config.triggerTokensOverride());
            assertEquals(5, config.keepRecent());
            assertEquals(Set.of("read_file", "web_fetch"), config.excludedTools());
        } finally {
            for (int i = 0; i < keys.length; i++) {
                if (previous[i] == null) System.clearProperty(keys[i]);
                else System.setProperty(keys[i], previous[i]);
            }
        }
    }

    static List<LlmClient.Message> historyWithToolRounds(int rounds, String toolName) {
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("system"));
        history.add(LlmClient.Message.user("inspect the project"));
        for (int i = 0; i < rounds; i++) {
            addToolRound(history, "call-" + i, toolName,
                    "<tool_result tool=\"" + toolName + "\" trust=\"untrusted-data\">\n" + BIG + i + "\n</tool_result>");
        }
        history.add(LlmClient.Message.assistant("done"));
        return history;
    }

    private static void addToolRound(List<LlmClient.Message> history, String id, String toolName, String content) {
        history.add(LlmClient.Message.assistant(null, List.of(
                new LlmClient.ToolCall(id, new LlmClient.ToolCall.Function(toolName, "{}")))));
        history.add(LlmClient.Message.tool(id, content));
    }

    private static void assertToolPairsIntact(List<LlmClient.Message> history) {
        Set<String> callIds = new HashSet<>();
        for (LlmClient.Message message : history) {
            if (message.toolCalls() != null) {
                message.toolCalls().forEach(call -> callIds.add(call.id()));
            }
            if ("tool".equals(message.role())) {
                assertTrue(callIds.contains(message.toolCallId()), "orphan tool result " + message.toolCallId());
            }
        }
    }
}
