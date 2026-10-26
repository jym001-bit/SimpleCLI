package com.paicli.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.paicli.history.ConversationLedger;
import com.paicli.llm.GLMClient;
import com.paicli.llm.LlmClient;
import com.paicli.memory.LongTermMemory;
import com.paicli.memory.MemoryManager;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolRegistry.ToolExecutionResult;
import com.paicli.tool.ToolRegistry.ToolInvocation;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class AgentOrchestratorTest {

    @Test
    void shouldShareOneLedgerAcrossPlannerWorkersAndReviewer(@TempDir Path tempDir)
            throws Exception {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        ConversationLedger ledger =
                ConversationLedger.open(tempDir.resolve("history"), "team-session");

        orchestrator.setConversationLedger(ledger);

        assertSame(ledger, readField(orchestrator, "conversationLedger"));
        assertSame(ledger, readField(readField(orchestrator, "planner"), "conversationLedger"));
        @SuppressWarnings("unchecked")
        List<SubAgent> workers = (List<SubAgent>) readField(orchestrator, "workers");
        for (SubAgent worker : workers) {
            assertSame(ledger, readField(worker, "conversationLedger"));
        }
        assertSame(ledger, readField(readField(orchestrator, "reviewer"), "conversationLedger"));
    }

    @Test
    void shouldParseSimplePlan() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        String planJson = """
                {
                    "summary": "读取文件",
                    "steps": [
                        {
                            "id": "step_1",
                            "description": "读取 pom.xml",
                            "type": "FILE_READ",
                            "dependencies": []
                        }
                    ]
                }
                """;

        List<AgentOrchestrator.ExecutionStep> steps = orchestrator.parsePlan(planJson);
        assertEquals(1, steps.size());
        assertEquals("step_1", steps.get(0).id());
        assertEquals("读取 pom.xml", steps.get(0).description());
    }

    @Test
    void shouldParseMultiStepPlanWithDependencies() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        String planJson = """
                {
                    "summary": "创建并验证项目",
                    "steps": [
                        {
                            "id": "s1",
                            "description": "创建项目",
                            "type": "COMMAND",
                            "dependencies": []
                        },
                        {
                            "id": "s2",
                            "description": "读取 pom.xml",
                            "type": "FILE_READ",
                            "dependencies": ["s1"]
                        },
                        {
                            "id": "s3",
                            "description": "验证结构",
                            "type": "VERIFICATION",
                            "dependencies": ["s2"]
                        }
                    ]
                }
                """;

        List<AgentOrchestrator.ExecutionStep> steps = orchestrator.parsePlan(planJson);
        assertEquals(3, steps.size());

        // 验证重编号
        assertEquals("step_1", steps.get(0).id());
        assertEquals("step_2", steps.get(1).id());
        assertEquals("step_3", steps.get(2).id());

        // 验证依赖被正确映射
        assertTrue(steps.get(0).dependencies().isEmpty());
        assertEquals(List.of("step_1"), steps.get(1).dependencies());
        assertEquals(List.of("step_2"), steps.get(2).dependencies());
    }

    @Test
    void shouldParsePlanWithMarkdownCodeBlock() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        String planJson = """
                ```json
                {
                    "summary": "简单任务",
                    "steps": [
                        {
                            "id": "t1",
                            "description": "执行命令",
                            "type": "COMMAND",
                            "dependencies": []
                        }
                    ]
                }
                ```
                """;

        List<AgentOrchestrator.ExecutionStep> steps = orchestrator.parsePlan(planJson);
        assertEquals(1, steps.size());
    }

    @Test
    void shouldParsePlanWithTasksField() {
        // 兼容 "tasks" 字段（Plan-and-Execute 的格式）
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        String planJson = """
                {
                    "summary": "用 tasks 字段",
                    "tasks": [
                        {
                            "id": "task_1",
                            "description": "第一步",
                            "type": "COMMAND",
                            "dependencies": []
                        }
                    ]
                }
                """;

        List<AgentOrchestrator.ExecutionStep> steps = orchestrator.parsePlan(planJson);
        assertEquals(1, steps.size());
        assertEquals("第一步", steps.get(0).description());
    }

    @Test
    void shouldReturnEmptyListForInvalidJson() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));

        assertTrue(orchestrator.parsePlan("").isEmpty());
        assertTrue(orchestrator.parsePlan("not json").isEmpty());
        assertTrue(orchestrator.parsePlan("{}").isEmpty());
        assertTrue(orchestrator.parsePlan("{\"steps\": []}").isEmpty());
    }

    @Test
    void shouldGetExecutableSteps() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));

        // step_1 无依赖，step_2 依赖 step_1
        List<AgentOrchestrator.ExecutionStep> steps = new ArrayList<>(List.of(
                AgentOrchestrator.ExecutionStep.pending("step_1", "创建项目", "COMMAND", List.of()),
                AgentOrchestrator.ExecutionStep.pending("step_2", "验证结构", "VERIFICATION", List.of("step_1"))
        ));

        // 只有 step_1 可执行
        List<AgentOrchestrator.ExecutionStep> executable = orchestrator.getExecutableSteps(steps);
        assertEquals(1, executable.size());
        assertEquals("step_1", executable.get(0).id());

        // 完成 step_1 后 step_2 可执行
        steps.set(0, steps.get(0).withResult("项目已创建"));
        executable = orchestrator.getExecutableSteps(steps);
        assertEquals(1, executable.size());
        assertEquals("step_2", executable.get(0).id());
    }

    @Test
    void shouldGetMultipleExecutableStepsForParallelTasks() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));

        List<AgentOrchestrator.ExecutionStep> steps = List.of(
                AgentOrchestrator.ExecutionStep.pending("step_1", "任务A", "COMMAND", List.of()),
                AgentOrchestrator.ExecutionStep.pending("step_2", "任务B", "COMMAND", List.of()),
                AgentOrchestrator.ExecutionStep.pending("step_3", "汇总", "ANALYSIS", List.of("step_1", "step_2"))
        );

        List<AgentOrchestrator.ExecutionStep> executable = orchestrator.getExecutableSteps(steps);
        assertEquals(2, executable.size());
    }

    @Test
    void shouldRejectPlanWithUnknownDependencyInsteadOfBlockingSilently() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(new GLMClient("test-key"));
        String planJson = """
                {"steps": [
                  {"id": "a", "description": "A", "dependencies": []},
                  {"id": "b", "description": "B", "dependencies": ["missing"]}
                ]}
                """;

        assertTrue(orchestrator.parsePlan(planJson).isEmpty());
    }

    @Test
    void proseReviewIsTreatedAsRejectionAndTriggersRetry(@TempDir Path tempDir) {
        StubGLMClient llmClient = new StubGLMClient(List.of(
                response("""
                        {"summary": "单步任务", "steps": [
                          {"id": "s1", "description": "执行任务", "type": "COMMAND", "dependencies": []}
                        ]}
                        """),
                response("第一次执行结果"),
                // 旧实现会把含“通过”的纯文本判为批准；现在不是 JSON 结论就按未通过处理。
                response("审查通过，代码质量良好"),
                response("第二次执行结果"),
                response("{\"approved\": true, \"summary\": \"通过\", \"issues\": []}")
        ));

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                llmClient,
                new ToolRegistry(),
                new NoOpMemoryManager(tempDir.toFile())
        );

        String finalResult = orchestrator.run("测试审查结论解析");

        assertTrue(finalResult.contains("第二次执行结果"));
        assertFalse(finalResult.contains("第一次执行结果"));
    }

    @Test
    void shouldRetryRejectedStepUntilApproval(@TempDir Path tempDir) {
        StubGLMClient llmClient = new StubGLMClient(List.of(
                response("""
                        {
                          "summary": "单步任务",
                          "steps": [
                            {
                              "id": "s1",
                              "description": "执行任务",
                              "type": "COMMAND",
                              "dependencies": []
                            }
                          ]
                        }
                        """),
                response("第一次执行结果"),
                response("""
                        {"approved": false, "summary": "第一次未通过", "issues": ["需要补充细节"]}
                        """),
                response("第二次执行结果"),
                response("""
                        {"approved": false, "summary": "第二次未通过", "issues": ["还缺最后结论"]}
                        """),
                response("第三次执行结果"),
                response("""
                        {"approved": true, "summary": "通过", "issues": []}
                        """)
        ));

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                llmClient,
                new ToolRegistry(),
                new NoOpMemoryManager(tempDir.toFile())
        );

        String finalResult = orchestrator.run("测试重试逻辑");

        assertTrue(finalResult.contains("第三次执行结果"));
        assertFalse(finalResult.contains("第二次执行结果"));
    }

    @Test
    void shouldRunIndependentStepsInParallel(@TempDir Path tempDir) throws Exception {
        // 两个互相独立的步骤同属一个依赖批次。若并行执行生效，两个 worker 应同时在 chat() 内等待。
        CountDownLatch workersInFlight = new CountDownLatch(2);
        AtomicInteger peakConcurrency = new AtomicInteger();
        AtomicInteger currentConcurrency = new AtomicInteger();

        Function<String, LlmClient.ChatResponse> dispatcher = body -> {
            if (body.contains("请为以下任务制定执行计划")) {
                return response("""
                        {
                          "summary": "并行两步",
                          "steps": [
                            {"id": "a", "description": "任务A", "type": "ANALYSIS", "dependencies": []},
                            {"id": "b", "description": "任务B", "type": "ANALYSIS", "dependencies": []}
                          ]
                        }
                        """);
            }
            if (body.contains("任务A")) {
                return awaitBarrierThenReturn(workersInFlight, currentConcurrency, peakConcurrency,
                        response("任务A 的结果"));
            }
            if (body.contains("任务B")) {
                return awaitBarrierThenReturn(workersInFlight, currentConcurrency, peakConcurrency,
                        response("任务B 的结果"));
            }
            if (body.contains("原始任务")) {
                return response("""
                        {"approved": true, "summary": "通过", "issues": []}
                        """);
            }
            return response("fallback");
        };

        DispatchingStubGLMClient llmClient = new DispatchingStubGLMClient(dispatcher);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                llmClient,
                new ToolRegistry(),
                new NoOpMemoryManager(tempDir.toFile())
        );

        String finalResult = orchestrator.run("测试并行执行");

        assertTrue(finalResult.contains("任务A"), "finalResult should mention task A");
        assertTrue(finalResult.contains("任务B"), "finalResult should mention task B");
        // 两个 Worker 同时持有 chat() 调用 → 并发峰值至少为 2
        assertEquals(2, peakConcurrency.get(), "Expected two workers to run concurrently");
    }

    private static LlmClient.ChatResponse awaitBarrierThenReturn(CountDownLatch latch,
                                                                  AtomicInteger current,
                                                                  AtomicInteger peak,
                                                                  LlmClient.ChatResponse response) {
        int now = current.incrementAndGet();
        peak.updateAndGet(prev -> Math.max(prev, now));
        latch.countDown();
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Both workers should reach chat() concurrently");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            current.decrementAndGet();
        }
        return response;
    }

    @Test
    void shouldReportIncompleteRunWhenFailureBlocksRemainingSteps(@TempDir Path tempDir) {
        StubGLMClient llmClient = new StubGLMClient(List.of(
                response("""
                        {
                          "summary": "两步任务",
                          "steps": [
                            {
                              "id": "s1",
                              "description": "第一步",
                              "type": "COMMAND",
                              "dependencies": []
                            },
                            {
                              "id": "s2",
                              "description": "第二步",
                              "type": "ANALYSIS",
                              "dependencies": ["s1"]
                            }
                          ]
                        }
                        """),
                response("")
        ));

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                llmClient,
                new ToolRegistry(),
                new NoOpMemoryManager(tempDir.toFile())
        );

        String finalResult = orchestrator.run("测试失败阻塞");

        assertTrue(finalResult.contains("未完全完成"));
        assertTrue(finalResult.contains("[step_1] ❌ 第一步"));
        assertTrue(finalResult.contains("[step_2] ⏳ 第二步"));
    }

    @Test
    void dependentTeamStepInheritsTypedSearchUrlProvenance(@TempDir Path tempDir) {
        StubGLMClient llmClient = new StubGLMClient(List.of(
                response("""
                        {
                          "summary": "搜索后抓取",
                          "steps": [
                            {"id": "search", "description": "搜索目标文章", "type": "ANALYSIS", "dependencies": []},
                            {"id": "fetch", "description": "抓取目标文章", "type": "ANALYSIS", "dependencies": ["search"]}
                          ]
                        }
                        """),
                toolResponse("search", "web_search", "{\"query\":\"目标文章\"}"),
                response("已定位目标文章"),
                response("{\"approved\":true,\"summary\":\"通过\",\"issues\":[]}"),
                toolResponse("fetch", "web_fetch", "{\"url\":\"https://example.com/article\"}"),
                response("已抓取正文"),
                response("{\"approved\":true,\"summary\":\"通过\",\"issues\":[]}")
        ));
        RecordingWebRegistry registry = new RecordingWebRegistry();
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                llmClient,
                registry,
                new NoOpMemoryManager(tempDir.toFile())
        );

        String finalResult = orchestrator.run("帮我搜索目标文章并抓取正文");

        assertTrue(finalResult.contains("已抓取正文"));
        assertEquals(List.of("web_search", "web_fetch"),
                registry.invocations.stream().map(ToolInvocation::name).toList());
        assertTrue(llmClient.toolSnapshots.get(4).stream()
                .anyMatch(tool -> "web_fetch".equals(tool.name())),
                "依赖 worker 的首轮 schema 应继承前置 search provenance");
    }

    private static LlmClient.ChatResponse response(String content) {
        return new LlmClient.ChatResponse("assistant", content, null, 100, 20);
    }

    private static LlmClient.ChatResponse toolResponse(String id, String name, String arguments) {
        return new LlmClient.ChatResponse(
                "assistant",
                "",
                List.of(new LlmClient.ToolCall(
                        id,
                        new LlmClient.ToolCall.Function(name, arguments))),
                100,
                20);
    }

    private static Object readField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static final class NoOpMemoryManager extends MemoryManager {
        private NoOpMemoryManager(File storageDir) {
            super(new GLMClient("test-key"), 32768, 200000, new LongTermMemory(storageDir));
        }
    }

    private static final class StubGLMClient extends GLMClient {
        private final Queue<ChatResponse> responses;
        private final List<List<Tool>> toolSnapshots = new ArrayList<>();

        private StubGLMClient(List<ChatResponse> responses) {
            super("test-key");
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            toolSnapshots.add(tools == null ? List.of() : List.copyOf(tools));
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("缺少预设响应");
            }
            if (response.content() != null && !response.content().isEmpty()) {
                listener.onContentDelta(response.content());
            }
            return response;
        }
    }

    private static final class RecordingWebRegistry extends ToolRegistry {
        private final List<ToolInvocation> invocations = new ArrayList<>();

        @Override
        public List<ToolExecutionResult> executeTools(List<ToolInvocation> calls) {
            invocations.addAll(calls);
            return calls.stream()
                    .map(call -> new ToolExecutionResult(
                            call.id(),
                            call.name(),
                            call.argumentsJson(),
                            "web_search".equals(call.name())
                                    ? "1. 目标文章 https://example.com/article"
                                    : "已抓取",
                            0,
                            false,
                            List.of(),
                            true,
                            "web_search".equals(call.name())
                                    ? List.of("https://example.com/article") : List.of()))
                    .toList();
        }
    }

    /**
     * 基于最后一条用户消息内容派发响应的 stub，支持多线程并发调用。
     */
    private static final class DispatchingStubGLMClient extends GLMClient {
        private final Function<String, ChatResponse> dispatcher;

        private DispatchingStubGLMClient(Function<String, ChatResponse> dispatcher) {
            super("test-key");
            this.dispatcher = dispatcher;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            String lastUserMessage = findLastUser(messages);
            ChatResponse response = dispatcher.apply(lastUserMessage);
            if (response == null) {
                throw new IOException("无匹配响应，最后的 user 消息: " + lastUserMessage);
            }
            if (response.content() != null && !response.content().isEmpty()) {
                listener.onContentDelta(response.content());
            }
            return response;
        }

        private static String findLastUser(List<Message> messages) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Message m = messages.get(i);
                if ("user".equals(m.role())) {
                    return m.content() == null ? "" : m.content();
                }
            }
            return "";
        }
    }
}
