package com.paicli.history;

import com.paicli.llm.LlmClient;
import com.paicli.memory.ToolResultClearer;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolResultOffloader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** G5：落盘 + 清理 + checkpoint 后恢复，tool call/result 仍然完整。 */
class CheckpointPairRegressionTest {

    @TempDir
    Path tempDir;

    @Test
    void checkpointAfterOffloadAndClearKeepsToolPair() throws Exception {
        String fact = "FACT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        ToolResultOffloader offloader = new ToolResultOffloader(tempDir, true, 2_000);
        registry.setToolResultOffloader(offloader);

        String large = "prefix\n" + "x".repeat(4_000) + "\n" + fact;
        String summary = offloader.offloadIfOversized("grep_code", "{}", large);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("system"));
        history.add(LlmClient.Message.user("question"));
        history.add(LlmClient.Message.assistant("", List.of(
                new LlmClient.ToolCall("call-1",
                        new LlmClient.ToolCall.Function("grep_code", "{}")))));
        history.add(LlmClient.Message.tool("call-1", summary));
        history.add(LlmClient.Message.assistant("done"));

        new ToolResultClearer(new ToolResultClearer.Config(true, 0, 0, Set.of()))
                .clearNow(history);
        String placeholder = history.get(3).content();
        assertTrue(placeholder.startsWith(ToolResultClearer.PLACEHOLDER_PREFIX));
        Matcher matcher = Pattern.compile("完整输出仍保存在 (.+?)，").matcher(placeholder);
        assertTrue(matcher.find(), placeholder);
        Path offloaded = tempDir.resolve(matcher.group(1).trim());
        assertTrue(Files.readString(offloaded).contains(fact));
        assertTrue(registry.executeTool("read_file", "{\"path\":\""
                + matcher.group(1).trim() + "\",\"offset\":1,\"limit\":10000}").contains(fact));

        Path historyDir = tempDir.resolve("history");
        ConversationLedger ledger = ConversationLedger.open(historyDir, "g5");
        for (LlmClient.Message message : history.subList(1, history.size())) {
            ledger.appendMessage("react", "agent", "test", message);
        }
        ledger.appendEvent(ConversationLedger.VIEW_CHECKPOINT_EVENT, "react", "agent", "test",
                Map.of("messages", history.subList(1, history.size())));

        SessionResume.RestoredConversation restored =
                SessionResume.rebuild("g5", ConversationLedger.readEntriesSkippingBrokenLines(ledger.file()));
        List<LlmClient.Message> messages = restored.messages();
        assertEquals(history.size() - 1, messages.size());
        Set<String> calls = new HashSet<>();
        Set<String> results = new HashSet<>();
        for (LlmClient.Message message : messages) {
            if (message.toolCalls() != null) {
                message.toolCalls().forEach(call -> calls.add(call.id()));
            }
            if ("tool".equals(message.role())) results.add(message.toolCallId());
        }
        assertEquals(Set.of("call-1"), calls);
        assertEquals(Set.of("call-1"), results);
        LlmClient.Message restoredTool = messages.stream()
                .filter(message -> "tool".equals(message.role())).findFirst().orElseThrow();
        assertTrue(restoredTool.content().startsWith(ToolResultClearer.PLACEHOLDER_PREFIX));
        assertFalse(restoredTool.content().contains(fact), "恢复的是占位符，不是原文");
    }
}
