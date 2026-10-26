package com.paicli.agent;

import com.paicli.tool.ToolResultBoundary;
import com.paicli.history.ConversationLedger;
import com.paicli.llm.LlmClient;
import com.paicli.memory.MemoryManager;
import com.paicli.memory.LongTermMemory;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConversationLedgerTest {

    @Test
    void reactLedgerPreservesToolProtocolAndFinalReasoning(@TempDir Path tempDir) throws Exception {
        LlmClient.ToolCall toolCall = new LlmClient.ToolCall(
                "call-1",
                new LlmClient.ToolCall.Function("read_file", "{\"path\":\"README.md\"}"));
        QueueClient llm = new QueueClient(List.of(
                new LlmClient.ChatResponse(
                        "assistant", "先读取文件", "tool reasoning", List.of(toolCall), 10, 3),
                new LlmClient.ChatResponse(
                        "assistant", "最终答案", "final reasoning", null, 12, 4)
        ));
        StubToolRegistry registry = new StubToolRegistry();
        registry.setProjectPath(tempDir.toString());
        ConversationLedger ledger =
                ConversationLedger.open(tempDir.resolve("history"), "react-session");
        Agent agent = new Agent(llm, registry);
        agent.setConversationLedger(ledger);

        assertTrue(agent.run("读取 README").contains("最终答案"));

        List<ConversationLedger.Entry> entries = ledger.readAll();
        ConversationLedger.Entry callEntry = entries.stream()
                .filter(entry -> "tool_call".equals(entry.event()))
                .findFirst()
                .orElseThrow();
        ConversationLedger.Entry resultEntry = entries.stream()
                .filter(entry -> "tool_result".equals(entry.event()))
                .findFirst()
                .orElseThrow();
        ConversationLedger.Entry finalEntry = entries.stream()
                .filter(entry -> "assistant".equals(entry.event())
                        && "最终答案".equals(entry.message().content()))
                .findFirst()
                .orElseThrow();

        assertEquals("tool reasoning", callEntry.message().reasoningContent());
        assertEquals("call-1", callEntry.message().toolCalls().get(0).id());
        assertEquals("call-1", resultEntry.message().toolCallId());
        assertEquals(ToolResultBoundary.wrap("read_file", "raw tool result"), resultEntry.message().content());
        assertEquals("final reasoning", finalEntry.message().reasoningContent());
        assertEquals("react", finalEntry.mode());
        assertEquals("agent", finalEntry.actor());
    }

    @Test
    void clearAndCompactionOnlyMutateDeliveryView(@TempDir Path tempDir) throws Exception {
        String summary = """
                ## 当前目标与成功条件
                继续当前会话。
                ## 用户要求与已确认决定
                保留用户要求。
                ## 已完成工作及证据
                已完成第一轮。
                ## 未解决问题与下一步
                继续回答。
                """;
        QueueClient llm = new QueueClient(List.of(
                new LlmClient.ChatResponse("assistant", "answer-1", "reason-1", null, 10, 2),
                new LlmClient.ChatResponse("assistant", "answer-2", "reason-2", null, 10, 2),
                new LlmClient.ChatResponse("assistant", summary, null, null, 10, 2),
                new LlmClient.ChatResponse("assistant", "answer-3", null, null, 10, 2)
        ));
        StubToolRegistry registry = new StubToolRegistry();
        registry.setProjectPath(tempDir.toString());
        ConversationLedger ledger =
                ConversationLedger.open(tempDir.resolve("history"), "view-mutations");
        Agent agent = new Agent(llm, registry);
        agent.setConversationLedger(ledger);

        agent.run("turn one " + "x".repeat(2_000));
        agent.run("turn two");
        byte[] beforeCompaction = Files.readAllBytes(ledger.file());

        Agent.CompactionResult result = agent.compactHistoryNow();
        assertTrue(result.compacted());
        byte[] afterCompaction = Files.readAllBytes(ledger.file());
        assertArrayEquals(
                beforeCompaction,
                Arrays.copyOf(afterCompaction, beforeCompaction.length));
        assertTrue(ledger.readAll().stream()
                .anyMatch(entry -> "compaction".equals(entry.event())));

        agent.run("turn three");
        List<LlmClient.Message> nextRequest = llm.requests.get(3);
        assertTrue(nextRequest.get(1).content().contains(summary.trim()));
        assertTrue(nextRequest.stream().anyMatch(message -> "turn three".equals(message.content())));

        byte[] beforeClear = Files.readAllBytes(ledger.file());
        agent.clearHistory();
        byte[] afterClear = Files.readAllBytes(ledger.file());
        assertArrayEquals(beforeClear, Arrays.copyOf(afterClear, beforeClear.length));
        assertTrue(ledger.readAll().stream()
                .anyMatch(entry -> "history_clear".equals(entry.event())));
        assertEquals(1, agent.getConversationHistory().size());
        assertEquals("system", agent.getConversationHistory().get(0).role());
    }

    @Test
    void planModeWritesIntoTheSharedLedgerWithTaskActor(@TempDir Path tempDir)
            throws Exception {
        QueueClient llm = new QueueClient(List.of(
                new LlmClient.ChatResponse(
                        "assistant", "目录读取完成", "plan task reasoning", null, 10, 2)
        ));
        StubToolRegistry registry = new StubToolRegistry();
        registry.setProjectPath(tempDir.toString());
        ConversationLedger ledger =
                ConversationLedger.open(tempDir.resolve("history"), "plan-session");
        PrintStream output = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llm,
                registry,
                new MemoryManager(llm),
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                output);
        agent.setConversationLedger(ledger);

        assertTrue(agent.run("列出当前目录").contains("计划执行完成"));

        List<ConversationLedger.Entry> entries = ledger.readAll();
        assertTrue(entries.stream().anyMatch(entry ->
                "plan".equals(entry.mode())
                        && "plan-agent".equals(entry.actor())
                        && "user_input".equals(entry.source())));
        ConversationLedger.Entry taskResponse = entries.stream()
                .filter(entry -> "task:task_1".equals(entry.actor())
                        && "assistant".equals(entry.event()))
                .findFirst()
                .orElseThrow();
        assertEquals("plan task reasoning", taskResponse.message().reasoningContent());
    }

    @Test
    void teamModeAttributesEntriesToPlannerWorkerReviewerAndOrchestrator(@TempDir Path tempDir)
            throws Exception {
        QueueClient llm = new QueueClient(List.of(
                new LlmClient.ChatResponse("assistant", """
                        {"summary":"one step","steps":[
                          {"id":"s1","description":"执行检查","type":"ANALYSIS","dependencies":[]}
                        ]}
                        """, "planner reasoning", null, 10, 2),
                new LlmClient.ChatResponse(
                        "assistant", "检查完成", "worker reasoning", null, 10, 2),
                new LlmClient.ChatResponse(
                        "assistant",
                        "{\"approved\":true,\"summary\":\"通过\",\"issues\":[]}",
                        "reviewer reasoning",
                        null,
                        10,
                        2)
        ));
        StubToolRegistry registry = new StubToolRegistry();
        registry.setProjectPath(tempDir.toString());
        ConversationLedger ledger =
                ConversationLedger.open(tempDir.resolve("history"), "team-session");
        PrintStream output = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                llm,
                registry,
                new MemoryManager(llm),
                output);
        orchestrator.setConversationLedger(ledger);

        assertTrue(orchestrator.run("完成一次检查").contains("检查完成"));

        List<ConversationLedger.Entry> entries = ledger.readAll();
        assertTrue(entries.stream().anyMatch(entry -> "orchestrator".equals(entry.actor())));
        assertTrue(entries.stream().anyMatch(entry -> "planner".equals(entry.actor())));
        assertTrue(entries.stream().anyMatch(entry -> "worker-1".equals(entry.actor())));
        assertTrue(entries.stream().anyMatch(entry -> "reviewer".equals(entry.actor())));
        assertTrue(entries.stream()
                .filter(entry -> "assistant".equals(entry.event()))
                .anyMatch(entry -> "reviewer reasoning".equals(entry.message().reasoningContent())));
    }

    @Test
    void completedTeamRunExtractsSubmittedUserFact(@TempDir Path tempDir) {
        QueueClient llm = new QueueClient(List.of(
                new LlmClient.ChatResponse("assistant", """
                        {"summary":"one step","steps":[
                          {"id":"s1","description":"执行检查","type":"ANALYSIS","dependencies":[]}
                        ]}
                        """, null, null, 10, 2),
                new LlmClient.ChatResponse("assistant", "检查完成", null, null, 10, 2),
                new LlmClient.ChatResponse("assistant",
                        "{\"approved\":true,\"summary\":\"通过\",\"issues\":[]}", null, null, 10, 2),
                new LlmClient.ChatResponse("assistant",
                        "{\"facts\":[{\"quote\":\"我偏好中文回答\"}]}", null, null, 10, 2)
        ));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        LongTermMemory store = new LongTermMemory(tempDir.resolve("memory").toFile());
        MemoryManager manager = new MemoryManager(llm, 4096, 128000, store);
        manager.setAutoFactExtractionEnabled(true);
        AgentOrchestrator team = new AgentOrchestrator(llm, registry, manager,
                new PrintStream(new ByteArrayOutputStream()));

        assertTrue(team.run("我偏好中文回答。完成一次检查。").contains("检查完成"));
        assertEquals(1, store.size());
        assertEquals("我偏好中文回答", store.getAll().get(0).getContent());
        assertEquals(4, llm.requests.size());
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
