package com.paicli.agent;

import com.paicli.llm.GLMClient;
import com.paicli.llm.LlmClient;
import com.paicli.memory.LongTermMemory;
import com.paicli.memory.MemoryManager;
import com.paicli.plan.ExecutionPlan;
import com.paicli.plan.Planner;
import com.paicli.plan.Task;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolRegistry.ToolExecutionResult;
import com.paicli.tool.ToolRegistry.ToolInvocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanExecuteAgentTest {

    @TempDir
    Path tempDir;

    @Test
    void completedPlanExtractsUserFactWithoutReadingTaskToolOutput() {
        StubGLMClient client = new StubGLMClient(List.of(
                new LlmClient.ChatResponse("assistant", "任务完成", null, 10, 2),
                new LlmClient.ChatResponse("assistant",
                        "{\"facts\":[{\"quote\":\"我偏好中文回答\"}]}", null, 10, 2)));
        LongTermMemory store = new LongTermMemory(tempDir.resolve("auto-memory").toFile());
        MemoryManager manager = new MemoryManager(client, 4096, 128000, store);
        manager.setAutoFactExtractionEnabled(true);
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        PlanExecuteAgent agent = new PlanExecuteAgent(client, registry, new StubPlanner(client),
                manager, (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                new PrintStream(new ByteArrayOutputStream()));

        assertTrue(agent.run("我偏好中文回答。请读取测试文件。").contains("计划执行完成"));
        assertEquals(1, store.size());
        assertEquals("我偏好中文回答", store.getAll().get(0).getContent());
        assertEquals(2, client.messageSnapshots.size());
    }

    @Test
    void shouldKeepPlanExecutionArtifactsInTheTaskConversationOnly() throws Exception {
        Path sampleFile = Files.createFile(tempDir.resolve("sample.txt"));
        Files.writeString(sampleFile, "plan-memory-content");

        StubGLMClient llmClient = new StubGLMClient(List.of(
                new LlmClient.ChatResponse(
                        "assistant",
                        "",
                        List.of(new LlmClient.ToolCall(
                                "call_1",
                                new LlmClient.ToolCall.Function(
                                        "read_file",
                                        "{\"path\":\"" + sampleFile.toString().replace("\\", "\\\\") + "\"}"
                                )
                        )),
                        120,
                        30
                ),
                new LlmClient.ChatResponse("assistant", "已读取并确认文件内容", null, 140, 40)
        ));

        MemoryManager memoryManager = new MemoryManager(
                llmClient,
                4096,
                128000,
                new LongTermMemory(tempDir.resolve("memory-store").toFile())
        );
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.setProjectPath(tempDir.toString());
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                toolRegistry,
                new StubPlanner(llmClient),
                memoryManager,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()
        );

        String result = agent.run("请读取测试文件并确认内容");

        assertTrue(result.contains("计划执行完成"));
        assertTrue(llmClient.messageSnapshots.stream().flatMap(List::stream)
                .anyMatch(message -> message.content() != null
                        && message.content().contains("请读取测试文件并确认内容")));
        assertTrue(llmClient.messageSnapshots.stream().flatMap(List::stream)
                .anyMatch(message -> message.content() != null
                        && message.content().contains("plan-memory-content")));
        assertEquals(0, memoryManager.getLongTermMemory().size());
    }

    @Test
    void shouldContinuePlanTaskBeyondLegacyFiveIterationLimit() throws Exception {
        String old = System.getProperty("paicli.react.hard.max.iterations");
        try {
            System.clearProperty("paicli.react.hard.max.iterations");
            List<LlmClient.ChatResponse> responses = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                Path file = tempDir.resolve("sample-" + i + ".txt");
                Files.writeString(file, "content-" + i);
                responses.add(new LlmClient.ChatResponse(
                        "assistant",
                        "",
                        List.of(new LlmClient.ToolCall(
                                "call_" + i,
                                new LlmClient.ToolCall.Function(
                                        "read_file",
                                        "{\"path\":\"" + file.toString().replace("\\", "\\\\") + "\"}"))),
                        10,
                        2));
            }
            responses.add(new LlmClient.ChatResponse(
                    "assistant", "六个文件均已读取完成", null, 10, 2));

            StubGLMClient llmClient = new StubGLMClient(responses);
            ToolRegistry toolRegistry = new ToolRegistry();
            toolRegistry.setProjectPath(tempDir.toString());
            PlanExecuteAgent agent = new PlanExecuteAgent(
                    llmClient,
                    toolRegistry,
                    new StubPlanner(llmClient),
                    null,
                    (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute());

            String result = agent.run("依次读取六个测试文件并汇总");

            assertTrue(result.contains("计划执行完成"));
            assertEquals(7, llmClient.toolSnapshots.size(), "计划任务不应再在第 5 轮提前退出");
        } finally {
            if (old == null) {
                System.clearProperty("paicli.react.hard.max.iterations");
            } else {
                System.setProperty("paicli.react.hard.max.iterations", old);
            }
        }
    }

    @Test
    void failedTaskShouldSkipDependentsAndKeepCompletedResults() {
        StubGLMClient llmClient = new StubGLMClient(List.of(
                new LlmClient.ChatResponse("assistant", "独立任务已完成", null, 10, 2),
                new LlmClient.ChatResponse("assistant", "独立任务已完成", null, 10, 2)));
        DiamondFailurePlanner planner = new DiamondFailurePlanner(llmClient);
        PlanExecuteAgent agent = new PlanExecuteAgent(llmClient, new ToolRegistry(), planner, null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                new PrintStream(new ByteArrayOutputStream()));

        String result = agent.run("分析项目并输出报告");

        assertTrue(result.startsWith("⚠️ 计划部分完成，有任务失败。"), result);
        assertTrue(result.contains("已完成的任务结果"), "失败汇总不能丢掉已完成任务的结果: " + result);
        assertTrue(result.contains("[a] 独立任务已完成"), result);
        assertTrue(result.contains("任务 c 失败"), result);
        assertTrue(result.contains("任务 d 已跳过"), result);
        assertEquals(Task.TaskStatus.SKIPPED, planner.lastPlan.getTask("d").getStatus());
    }

    @Test
    void replanningShouldStopAtConfiguredLimit() {
        String old = System.getProperty(PlanExecuteAgent.MAX_REPLANS_PROPERTY);
        try {
            System.setProperty(PlanExecuteAgent.MAX_REPLANS_PROPERTY, "1");
            StubGLMClient llmClient = new StubGLMClient(List.of());
            AlwaysFailingChainPlanner planner = new AlwaysFailingChainPlanner(llmClient);
            PlanExecuteAgent agent = new PlanExecuteAgent(llmClient, new ToolRegistry(), planner, null,
                    (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                    new PrintStream(new ByteArrayOutputStream()));

            String result = agent.run("执行两步任务");

            assertEquals(2, planner.createCount.get(), "首次规划 + 1 次重规划后应停止");
            assertTrue(result.startsWith("⚠️ 计划部分完成，有任务失败。"), result);
            assertTrue(result.contains("任务 y 已跳过: 已达到重新规划上限"), result);
            assertEquals(Task.TaskStatus.SKIPPED, planner.lastPlan.getTask("y").getStatus());
        } finally {
            if (old == null) {
                System.clearProperty(PlanExecuteAgent.MAX_REPLANS_PROPERTY);
            } else {
                System.setProperty(PlanExecuteAgent.MAX_REPLANS_PROPERTY, old);
            }
        }
    }

    @Test
    void shouldNotExtractFactsWhenPlanIsCanceled() throws Exception {
        StubGLMClient llmClient = new StubGLMClient(List.of());
        LongTermMemory longTermMemory = new LongTermMemory(tempDir.resolve("memory-store-cancel").toFile());
        MemoryManager memoryManager = new MemoryManager(
                llmClient,
                4096,
                128000,
                longTermMemory
        );
        memoryManager.setAutoFactExtractionEnabled(true);
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                new ToolRegistry(),
                new StubPlanner(llmClient),
                memoryManager,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.cancel()
        );

        String result = agent.run("我偏好中文回答。列出当前目录的文件");

        assertEquals("⏹️ 已取消本次计划执行。", result);
        assertEquals(0, longTermMemory.size());
    }

    @Test
    void shouldNotRepeatStreamedTaskOutputInFinalPlanSummary() throws Exception {
        StubGLMClient llmClient = StubGLMClient.streaming(List.of(
                StubResponse.streamed(new LlmClient.ChatResponse(
                        "assistant",
                        "当前目录包含 8 个目录和 8 个文件。",
                        null,
                        60,
                        20
                ))
        ));

        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                new ToolRegistry(),
                new StubPlanner(llmClient),
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()
        );

        String result = agent.run("列出当前目录的文件");

        assertEquals("✅ 计划执行完成！", result);
    }

    @Test
    void shouldNotPrintEmptyTaskReasoningHeadingAndShouldUseOutputLabel() throws Exception {
        StubGLMClient llmClient = StubGLMClient.streaming(List.of(
                StubResponse.scripted(
                        listener -> {
                            listener.onReasoningDelta("  \n");
                            listener.onContentDelta("我来读取 pom.xml 文件。");
                        },
                        new LlmClient.ChatResponse(
                                "assistant",
                                "我来读取 pom.xml 文件。",
                                "  \n",
                                null,
                                60,
                                20
                        )
                )
        ));

        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                new ToolRegistry(),
                new StubPlanner(llmClient),
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()
        );

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            agent.run("读取 pom.xml");
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertFalse(rendered.contains("任务思考 [task_1]"),
                "空白 reasoning 不应打印空的任务思考标题: " + rendered);
        assertTrue(rendered.contains("任务输出 [task_1]"));
        assertFalse(rendered.contains("任务结果 [task_1]"),
                "tool-call 前后的流式 content 不应被误标成任务结果: " + rendered);
    }

    @Test
    void supplementRebuildsToolPolicyBeforeReplanning() throws Exception {
        LlmClient.ToolCall search = new LlmClient.ToolCall(
                "call_search",
                new LlmClient.ToolCall.Function(
                        "web_search",
                        "{\"query\":\"最新 Agent 资料\"}"
                )
        );
        StubGLMClient llmClient = new StubGLMClient(List.of(
                new LlmClient.ChatResponse("assistant", "", List.of(search), 10, 2),
                new LlmClient.ChatResponse("assistant", "已按搜索结果改写", null, 20, 5)
        ));
        RecordingToolRegistry registry = new RecordingToolRegistry();
        AtomicInteger reviews = new AtomicInteger();
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                registry,
                new StubPlanner(llmClient),
                null,
                (goal, plan) -> reviews.getAndIncrement() == 0
                        ? PlanExecuteAgent.PlanReviewDecision.supplement("请联网搜索最新资料")
                        : PlanExecuteAgent.PlanReviewDecision.execute()
        );

        agent.run("改写这个标题");

        assertEquals(1, registry.invocations.size(),
                "首轮 tools=" + llmClient.toolSnapshots.get(0).stream().map(tool -> tool.name()).toList());
        assertEquals("web_search", registry.invocations.get(0).name());
        assertTrue(llmClient.toolSnapshots.get(0).stream()
                .anyMatch(tool -> "web_search".equals(tool.name())));
    }

    @Test
    void noWebSupplementTightensToolPolicyBeforeReplanning() throws Exception {
        LlmClient.ToolCall search = new LlmClient.ToolCall(
                "call_search",
                new LlmClient.ToolCall.Function("web_search", "{\"query\":\"最新资料\"}"));
        StubGLMClient llmClient = new StubGLMClient(List.of(
                new LlmClient.ChatResponse("assistant", "", List.of(search), 10, 2),
                new LlmClient.ChatResponse("assistant", "已仅按已有文本改写", null, 20, 5)
        ));
        RecordingToolRegistry registry = new RecordingToolRegistry();
        AtomicInteger reviews = new AtomicInteger();
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                registry,
                new StubPlanner(llmClient),
                null,
                (goal, plan) -> reviews.getAndIncrement() == 0
                        ? PlanExecuteAgent.PlanReviewDecision.supplement("不需要联网，只改写已有标题")
                        : PlanExecuteAgent.PlanReviewDecision.execute()
        );

        agent.run("请联网搜索资料后改写这个标题");

        assertTrue(registry.invocations.isEmpty());
        assertTrue(llmClient.toolSnapshots.get(0).stream()
                .noneMatch(tool -> "web_search".equals(tool.name())));
    }

    @Test
    void dependentTaskInheritsOnlyTypedSearchUrlProvenance() throws Exception {
        StubGLMClient llmClient = new StubGLMClient(List.of(
                new LlmClient.ChatResponse("assistant", "", List.of(new LlmClient.ToolCall(
                        "search",
                        new LlmClient.ToolCall.Function("web_search", "{\"query\":\"目标文章\"}"))), 10, 2),
                new LlmClient.ChatResponse("assistant", "已定位目标文章", null, 10, 2),
                new LlmClient.ChatResponse("assistant", "", List.of(new LlmClient.ToolCall(
                        "fetch",
                        new LlmClient.ToolCall.Function(
                                "web_fetch", "{\"url\":\"https://example.com/article\"}"))), 10, 2),
                new LlmClient.ChatResponse("assistant", "已抓取正文", null, 10, 2)
        ));
        RecordingToolRegistry registry = new RecordingToolRegistry();
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                registry,
                new TwoTaskWebPlanner(llmClient),
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()
        );

        String result = agent.run("帮我搜索目标文章并抓取正文");

        assertTrue(result.contains("计划执行完成"));
        assertEquals(List.of("web_search", "web_fetch"),
                registry.invocations.stream().map(ToolInvocation::name).toList());
        assertTrue(llmClient.toolSnapshots.get(2).stream()
                .anyMatch(tool -> "web_fetch".equals(tool.name())),
                "依赖任务首轮应继承前置 web_search 的类型化 URL 授权");
    }

    private record StubResponse(LlmClient.ChatResponse response, boolean streamContent,
                                java.util.function.Consumer<LlmClient.StreamListener> streamScript) {
        private static StubResponse plain(LlmClient.ChatResponse response) {
            return new StubResponse(response, false, null);
        }

        private static StubResponse streamed(LlmClient.ChatResponse response) {
            return new StubResponse(response, true, null);
        }

        private static StubResponse scripted(java.util.function.Consumer<LlmClient.StreamListener> streamScript,
                                             LlmClient.ChatResponse response) {
            return new StubResponse(response, false, streamScript);
        }
    }

    private static final class StubPlanner extends Planner {
        private StubPlanner(LlmClient llmClient) {
            super(llmClient);
        }

        @Override
        public ExecutionPlan createPlan(String goal) {
            ExecutionPlan plan = new ExecutionPlan("plan-test", goal);
            plan.addTask(new Task("task_1", "读取测试文件", Task.TaskType.FILE_READ));
            plan.computeExecutionOrder();
            return plan;
        }

        @Override
        public ExecutionPlan revisePlan(ExecutionPlan current, String feedback) {
            return createPlan(current.getGoal() + "\n补充要求：" + feedback);
        }
    }

    private static final class DiamondFailurePlanner extends Planner {
        private ExecutionPlan lastPlan;

        private DiamondFailurePlanner(LlmClient llmClient) {
            super(llmClient);
        }

        @Override
        public ExecutionPlan createPlan(String goal) {
            ExecutionPlan plan = new ExecutionPlan("plan-diamond", goal);
            plan.addTask(new Task("a", "读取配置", Task.TaskType.FILE_READ));
            plan.addTask(new Task("b", "读取源码", Task.TaskType.FILE_READ));
            plan.addTask(new Task("c", "分析源码", Task.TaskType.ANALYSIS, List.of("b")));
            plan.addTask(new Task("d", "输出报告", Task.TaskType.ANALYSIS, List.of("c")));
            plan.computeExecutionOrder();
            lastPlan = plan;
            return plan;
        }
    }

    private static final class AlwaysFailingChainPlanner extends Planner {
        private final AtomicInteger createCount = new AtomicInteger();
        private ExecutionPlan lastPlan;

        private AlwaysFailingChainPlanner(LlmClient llmClient) {
            super(llmClient, new PrintStream(new ByteArrayOutputStream()));
        }

        @Override
        public ExecutionPlan createPlan(String goal) {
            createCount.incrementAndGet();
            ExecutionPlan plan = new ExecutionPlan("plan-chain-" + createCount.get(), goal);
            plan.addTask(new Task("x", "第一步", Task.TaskType.ANALYSIS));
            plan.addTask(new Task("y", "第二步", Task.TaskType.ANALYSIS, List.of("x")));
            plan.computeExecutionOrder();
            lastPlan = plan;
            return plan;
        }
    }

    private static final class TwoTaskWebPlanner extends Planner {
        private TwoTaskWebPlanner(LlmClient llmClient) {
            super(llmClient);
        }

        @Override
        public ExecutionPlan createPlan(String goal) {
            ExecutionPlan plan = new ExecutionPlan("plan-web", goal);
            plan.addTask(new Task("search", "搜索目标文章", Task.TaskType.ANALYSIS));
            plan.addTask(new Task("fetch", "抓取目标文章正文", Task.TaskType.FILE_READ, List.of("search")));
            plan.computeExecutionOrder();
            return plan;
        }
    }

    private static final class StubGLMClient extends GLMClient {
        private final Queue<StubResponse> responses;
        private final List<List<Tool>> toolSnapshots = new ArrayList<>();
        private final List<List<Message>> messageSnapshots = new ArrayList<>();

        private StubGLMClient(List<ChatResponse> responses) {
            super("test-key");
            this.responses = new ArrayDeque<>(responses.stream().map(StubResponse::plain).toList());
        }

        private StubGLMClient(Queue<StubResponse> responses) {
            super("test-key");
            this.responses = responses;
        }

        private static StubGLMClient streaming(List<StubResponse> responses) {
            return new StubGLMClient(new ArrayDeque<>(responses));
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        // 并行任务会同时调用这里，队列和快照列表都不是线程安全的，必须串行出队
        @Override
        public synchronized ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            toolSnapshots.add(tools == null ? List.of() : List.copyOf(tools));
            messageSnapshots.add(List.copyOf(messages));
            StubResponse stubResponse = responses.poll();
            if (stubResponse == null) {
                throw new IOException("缺少预设响应");
            }
            if (stubResponse.streamScript() != null) {
                stubResponse.streamScript().accept(listener);
            } else if (stubResponse.streamContent() && stubResponse.response().content() != null) {
                listener.onContentDelta(stubResponse.response().content());
            }
            return stubResponse.response();
        }
    }

    private static final class RecordingToolRegistry extends ToolRegistry {
        private final List<ToolInvocation> invocations = new ArrayList<>();

        @Override
        public List<ToolExecutionResult> executeTools(List<ToolInvocation> calls) {
            invocations.addAll(calls);
            return calls.stream()
                    .map(call -> new ToolExecutionResult(
                            call.id(),
                            call.name(),
                            call.argumentsJson(),
                            "1. 搜索结果 https://example.com/article",
                            0,
                            false,
                            List.of(),
                            true,
                            "web_search".equals(call.name())
                                    ? List.of("https://example.com/article") : List.of()))
                    .toList();
        }
    }
}
