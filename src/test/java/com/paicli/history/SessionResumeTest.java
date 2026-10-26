package com.paicli.history;

import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionResumeTest {

    @Test
    void unfinishedLastTurnIsDropped(@TempDir Path tempDir) throws Exception {
        ConversationLedger ledger = ConversationLedger.open(tempDir, "unfinished");
        LlmClient.ToolCall call = new LlmClient.ToolCall(
                "call-1", new LlmClient.ToolCall.Function("read_file", "{}"));
        react(ledger, "user_input", LlmClient.Message.user("第一问"));
        react(ledger, "llm_response", LlmClient.Message.assistant("思考", "第一答"));
        react(ledger, "user_input", LlmClient.Message.user("第二问"));
        react(ledger, "llm_response", LlmClient.Message.assistant("思考", "", List.of(call)));

        SessionResume.RestoredConversation restored =
                SessionResume.rebuild("unfinished", ledger.readAll());

        assertEquals(2, restored.messages().size());
        assertEquals(2, restored.droppedTrailingMessages());
        assertEquals("第一答", restored.messages().get(1).content());
        assertNull(restored.messages().get(1).reasoningContent());
        assertEquals("第二问", restored.lastQuestion());
    }

    @Test
    void clearAndCheckpointReplaceEarlierMessages(@TempDir Path tempDir) throws Exception {
        ConversationLedger ledger = ConversationLedger.open(tempDir, "clear");
        react(ledger, "user_input", LlmClient.Message.user("清空前"));
        react(ledger, "llm_response", LlmClient.Message.assistant("旧回答"));
        ledger.appendEvent(ConversationLedger.HISTORY_CLEAR_EVENT, "react", "agent", "slash_clear", Map.of());
        react(ledger, "history_reset", LlmClient.Message.system("system prompt"));
        react(ledger, "user_input", LlmClient.Message.user("清空后"));
        react(ledger, "llm_response", LlmClient.Message.assistant("新回答"));

        List<LlmClient.Message> afterClear = SessionResume.rebuild("clear", ledger.readAll()).messages();
        assertEquals(List.of(LlmClient.Message.user("清空后"), LlmClient.Message.assistant("新回答")), afterClear);

        ledger.appendEvent(ConversationLedger.VIEW_CHECKPOINT_EVENT, "react", "agent", "manual_compaction",
                Map.of("messages", List.of(LlmClient.Message.user("摘要"))));
        react(ledger, "user_input", LlmClient.Message.user("压缩后"));
        react(ledger, "llm_response", LlmClient.Message.assistant("压缩后回答"));
        // 其他模式写进同一文件的消息不属于 ReAct 对话
        ledger.appendMessage("plan", "plan-agent", "user_input", LlmClient.Message.user("计划任务"));

        assertEquals(List.of(
                        LlmClient.Message.user("摘要"),
                        LlmClient.Message.user("压缩后"),
                        LlmClient.Message.assistant("压缩后回答")),
                SessionResume.rebuild("clear", ledger.readAll()).messages());
    }

    @Test
    void halfWrittenLineDoesNotBlockResume(@TempDir Path tempDir) throws Exception {
        ConversationLedger ledger = ConversationLedger.open(tempDir, "broken");
        SessionResume.recordSessionStart(ledger, tempDir.toString());
        react(ledger, "user_input", LlmClient.Message.user("问题"));
        react(ledger, "llm_response", LlmClient.Message.assistant("回答"));
        Files.writeString(ledger.file(), "{\"schemaVersion\":1,\"sess", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        List<SessionResume.SessionSummary> sessions =
                SessionResume.listSessions(ledger.file().getParent(), tempDir.toString(), "current", 10);

        assertEquals(1, sessions.size());
        assertEquals(2, sessions.get(0).messageCount());
        assertTrue(SessionResume.load(ledger.file().getParent(), tempDir.toString(), "current", "1").isPresent());
    }

    private static void react(ConversationLedger ledger, String source, LlmClient.Message message) {
        ledger.appendMessage("react", "agent", source, message);
    }
}
