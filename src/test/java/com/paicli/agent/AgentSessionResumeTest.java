package com.paicli.agent;

import com.paicli.history.ConversationLedger;
import com.paicli.history.SessionResume;
import com.paicli.llm.LlmClient;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSessionResumeTest {

    private static final String SUMMARY = """
            ## 当前目标与成功条件
            继续当前会话。
            ## 用户要求与已确认决定
            保留用户要求。
            ## 已完成工作及证据
            已读取 README。
            ## 未解决问题与下一步
            继续回答。
            """;

    @Test
    void restoredConversationMatchesWhatTheModelSawAfterCompaction(@TempDir Path tempDir) throws Exception {
        Path history = tempDir.resolve("history");
        Path project = tempDir.resolve("project");
        LlmClient.ToolCall toolCall = new LlmClient.ToolCall(
                "call-1",
                new LlmClient.ToolCall.Function("read_file", "{\"path\":\"README.md\"}"));
        QueueClient firstLlm = new QueueClient(List.of(
                new LlmClient.ChatResponse("assistant", "先读文件", "tool reasoning", List.of(toolCall), 10, 2),
                new LlmClient.ChatResponse("assistant", "answer-1", "final reasoning", null, 10, 2),
                new LlmClient.ChatResponse("assistant", "answer-2", null, null, 10, 2),
                new LlmClient.ChatResponse("assistant", SUMMARY, null, null, 10, 2),
                new LlmClient.ChatResponse("assistant", "answer-3", null, null, 10, 2)
        ));
        ConversationLedger first = ConversationLedger.open(history, "first-session");
        Agent original = newAgent(firstLlm, project, first);

        original.run("turn one " + "x".repeat(2_000));
        original.run("turn two");
        assertTrue(original.compactHistoryNow().compacted());
        original.run("turn three");
        List<LlmClient.Message> expected = withoutSystem(original.getConversationHistory());

        Path raw = first.file().getParent();
        List<SessionResume.SessionSummary> sessions =
                SessionResume.listSessions(raw, project.toString(), "current-session", 10);
        assertEquals(1, sessions.size());
        assertEquals("first-session", sessions.get(0).sessionId());
        assertEquals("turn three", sessions.get(0).lastQuestion());

        SessionResume.RestoredConversation restored =
                SessionResume.load(raw, project.toString(), "current-session", "1").orElseThrow();
        assertEquals(expected, restored.messages());
        assertEquals(0, restored.droppedTrailingMessages());
        assertTrue(restored.messages().get(0).content().contains(SUMMARY.trim()));

        QueueClient secondLlm = new QueueClient(List.of(
                new LlmClient.ChatResponse("assistant", "answer-4", null, null, 10, 2)));
        ConversationLedger second = ConversationLedger.open(history, "second-session");
        Agent resumed = newAgent(secondLlm, project, second);
        resumed.resumeConversation(restored.sessionId(), restored.messages());
        resumed.run("turn four");

        List<LlmClient.Message> request = secondLlm.requests.get(0);
        assertEquals("system", request.get(0).role());
        assertEquals(expected, request.subList(1, request.size() - 1));
        assertEquals("turn four", request.get(request.size() - 1).content());

        // 恢复出来的会话自己也能再被恢复，并包含恢复之后的新问答
        SessionResume.RestoredConversation again =
                SessionResume.load(raw, project.toString(), "current-session", "second-session").orElseThrow();
        assertEquals(withoutSystem(resumed.getConversationHistory()), again.messages());
        assertEquals("turn four", again.lastQuestion());
    }

    @Test
    void sessionsFromOtherProjectsOrWithoutCliStartAreNotListed(@TempDir Path tempDir) throws Exception {
        Path history = tempDir.resolve("history");
        Path project = tempDir.resolve("project");
        Path otherProject = tempDir.resolve("other");

        newAgent(oneAnswer(), otherProject, ConversationLedger.open(history, "other-project"))
                .run("hello");
        Agent headless = new Agent(oneAnswer(), registry(project));
        headless.setConversationLedger(ConversationLedger.open(history, "headless-task"));
        headless.run("hello");
        ConversationLedger current = ConversationLedger.open(history, "current-session");
        newAgent(oneAnswer(), project, current).run("hello");

        Path raw = current.file().getParent();
        assertTrue(SessionResume.listSessions(raw, project.toString(), "current-session", 10).isEmpty());
        assertTrue(SessionResume.load(raw, project.toString(), "current-session", "other-project").isEmpty());
        assertTrue(SessionResume.load(raw, project.toString(), "current-session", "current-session").isEmpty());
        assertTrue(SessionResume.load(raw, project.toString(), "current-session", "../other-project").isEmpty());
    }

    @Test
    void resumingWebResultsPausesAutomaticMemory(@TempDir Path tempDir) {
        LlmClient.ToolCall fetch = new LlmClient.ToolCall(
                "call-1",
                new LlmClient.ToolCall.Function("execute_command", "{\"command\":\"curl -s https://example.com\"}"));
        Agent agent = newAgent(oneAnswer(), tempDir, ConversationLedger.disabled());
        agent.resumeConversation("old", List.of(
                LlmClient.Message.user("抓一下页面"),
                LlmClient.Message.assistant(null, "", List.of(fetch)),
                LlmClient.Message.tool("call-1", "<html>"),
                LlmClient.Message.assistant("抓好了")));

        assertTrue(agent.getMemoryManager().getExternalContextTracker().hasExternalContext());

        agent.resumeConversation("old", List.of(
                LlmClient.Message.user("你好"),
                LlmClient.Message.assistant("你好")));
        assertFalse(agent.getMemoryManager().getExternalContextTracker().hasExternalContext());
    }

    private static Agent newAgent(LlmClient llm, Path project, ConversationLedger ledger) {
        Agent agent = new Agent(llm, registry(project));
        agent.setConversationLedger(ledger);
        SessionResume.recordSessionStart(ledger, project.toString());
        return agent;
    }

    private static ToolRegistry registry(Path project) {
        StubToolRegistry registry = new StubToolRegistry();
        registry.setProjectPath(project.toString());
        return registry;
    }

    private static QueueClient oneAnswer() {
        return new QueueClient(List.of(new LlmClient.ChatResponse("assistant", "hi", null, null, 10, 2)));
    }

    private static List<LlmClient.Message> withoutSystem(List<LlmClient.Message> history) {
        return history.subList(1, history.size());
    }

    private static final class StubToolRegistry extends ToolRegistry {
        @Override
        public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
            ToolInvocation invocation = invocations.get(0);
            return List.of(new ToolExecutionResult(
                    invocation.id(),
                    invocation.name(),
                    invocation.argumentsJson(),
                    "raw tool result",
                    1L,
                    false,
                    List.of()));
        }
    }

    private static final class QueueClient implements LlmClient {
        private final Queue<ChatResponse> responses;
        private final List<List<Message>> requests = new ArrayList<>();

        private QueueClient(List<ChatResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                 StreamListener listener) throws IOException {
            requests.add(List.copyOf(messages));
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("missing response");
            }
            return response;
        }

        @Override
        public String getModelName() {
            return "test-model";
        }

        @Override
        public String getProviderName() {
            return "test-provider";
        }
    }
}
