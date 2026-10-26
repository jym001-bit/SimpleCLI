package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.memory.LongTermMemory;
import com.paicli.memory.MemoryManager;
import com.paicli.plan.ExecutionPlan;
import com.paicli.plan.Planner;
import com.paicli.plan.Task;
import com.paicli.skill.SkillRegistry;
import com.paicli.skill.SkillStateStore;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** load_skill 的正文必须在同一轮的下一次 LLM 请求里出现，而不是等用户下一条消息。 */
class LoadSkillSameTurnTest {

    private static final String BODY_MARKER = "SKILL_BODY_MARKER 先 web_fetch 再切浏览器";

    @TempDir
    Path tempDir;

    private String oldMemoryDir;

    @BeforeEach
    void isolateMemory() {
        oldMemoryDir = System.getProperty("paicli.memory.dir");
        System.setProperty("paicli.memory.dir", tempDir.resolve("memory").toString());
    }

    @AfterEach
    void restoreMemory() {
        if (oldMemoryDir == null) {
            System.clearProperty("paicli.memory.dir");
        } else {
            System.setProperty("paicli.memory.dir", oldMemoryDir);
        }
    }

    @Test
    void reactInjectsSkillBodyBeforeNextRequestInSameRun() throws IOException {
        QueueClient llm = loadSkillThenAnswer();
        Agent agent = new Agent(llm, toolsWithSkill());

        agent.run("帮我读一篇微信文章");

        assertSkillVisibleOnlyAfterLoad(llm);
    }

    @Test
    void planTaskInjectsSkillBodyBeforeNextRequestInSameTask() throws IOException {
        QueueClient llm = loadSkillThenAnswer();
        MemoryManager memory = new MemoryManager(llm, 4096, 128000,
                new LongTermMemory(tempDir.resolve("plan-memory").toFile()));
        PlanExecuteAgent agent = new PlanExecuteAgent(llm, toolsWithSkill(), new SingleTaskPlanner(llm),
                memory, (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                new PrintStream(new ByteArrayOutputStream()));

        agent.run("帮我读一篇微信文章");

        assertSkillVisibleOnlyAfterLoad(llm);
    }

    @Test
    void teamWorkerInjectsSkillBodyBeforeNextRequestInSameTask() throws IOException {
        QueueClient llm = loadSkillThenAnswer();
        SubAgent worker = new SubAgent("worker", AgentRole.WORKER, llm, toolsWithSkill());

        worker.execute(AgentMessage.task("orchestrator", "帮我读一篇微信文章"),
                new PrintStream(new ByteArrayOutputStream()));

        assertSkillVisibleOnlyAfterLoad(llm);
    }

    @Test
    void reactSkillBodyCanBeReloadedAfterManualCompaction() throws IOException {
        QueueClient llm = new QueueClient(List.of(
                loadSkillCall("call-1"),
                answer("第一轮完成"),
                answer("第二轮完成"),
                answer("## 当前目标与成功条件\n读文章\n## 用户要求与已确认决定\n无\n"
                        + "## 已完成工作及证据\n已读\n## 未解决问题与下一步\n无"),
                loadSkillCall("call-2"),
                answer("第三轮完成")));
        Agent agent = new Agent(llm, toolsWithSkill());

        agent.run("帮我读一篇微信文章");
        agent.run("继续");
        assertTrue(agent.compactHistoryNow().compacted());
        agent.run("再按 skill 处理一次");

        assertFalse(containsBody(llm.requests.get(3)), "Skill 正文不应交给摘要模型");
        List<LlmClient.Message> last = llm.requests.get(llm.requests.size() - 1);
        List<LlmClient.Message> beforeReload = llm.requests.get(4);
        assertFalse(containsBody(beforeReload), "压缩后正文应移出上下文");
        assertTrue(beforeReload.stream().anyMatch(m -> m.content() != null
                && m.content().contains("Skill 正文已随压缩移出上下文")), "摘要应提示可以重新加载");
        assertEquals(1, last.stream().filter(m -> m.content() != null && m.content().contains(BODY_MARKER)).count(),
                "压缩后重新加载应再注入一份正文");
    }

    @Test
    void parallelPlanTasksEachGetTheirOwnSkillInjection() throws IOException {
        TaskAwareClient llm = new TaskAwareClient();
        MemoryManager memory = new MemoryManager(llm, 4096, 128000,
                new LongTermMemory(tempDir.resolve("plan-memory").toFile()));
        PlanExecuteAgent agent = new PlanExecuteAgent(llm, toolsWithSkill(), new TwoParallelTaskPlanner(llm),
                memory, (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                new PrintStream(new ByteArrayOutputStream()));

        agent.run("同时读两篇微信文章");

        List<List<LlmClient.Message>> afterLoad = llm.requests.stream()
                .filter(LoadSkillSameTurnTest::endsWithSkillInjection)
                .toList();
        assertEquals(2, afterLoad.size(), "两个并行任务都应各自拿到正文");
        afterLoad.forEach(request -> assertTrue(containsBody(request)));
    }

    private static boolean endsWithSkillInjection(List<LlmClient.Message> request) {
        LlmClient.Message last = request.get(request.size() - 1);
        return com.paicli.memory.InternalUserMessages.isSkillInjection(last);
    }

    private static LlmClient.ChatResponse loadSkillCall(String id) {
        return new LlmClient.ChatResponse("assistant", "", List.of(new LlmClient.ToolCall(id,
                new LlmClient.ToolCall.Function("load_skill", "{\"name\":\"web-access\"}"))), 10, 2);
    }

    private static LlmClient.ChatResponse answer(String content) {
        return new LlmClient.ChatResponse("assistant", content, null, 10, 2);
    }

    private static void assertSkillVisibleOnlyAfterLoad(QueueClient llm) {
        assertEquals(2, llm.requests.size(), "load_skill 之后应在同一轮再请求一次模型");
        assertFalse(containsBody(llm.requests.get(0)), "加载前的请求不应包含正文");

        List<LlmClient.Message> second = llm.requests.get(1);
        LlmClient.Message last = second.get(second.size() - 1);
        assertEquals("user", last.role());
        assertTrue(last.content().contains("## 已加载 Skill：web-access"), last.content());
        assertTrue(last.content().contains(BODY_MARKER), last.content());
        assertEquals("tool", second.get(second.size() - 2).role(), "正文应紧跟在工具结果之后");
    }

    private static boolean containsBody(List<LlmClient.Message> messages) {
        return messages.stream().anyMatch(m -> m.content() != null && m.content().contains(BODY_MARKER));
    }

    private QueueClient loadSkillThenAnswer() {
        LlmClient.ToolCall loadSkill = new LlmClient.ToolCall("call-1",
                new LlmClient.ToolCall.Function("load_skill", "{\"name\":\"web-access\"}"));
        return new QueueClient(List.of(
                new LlmClient.ChatResponse("assistant", "", List.of(loadSkill), 10, 2),
                new LlmClient.ChatResponse("assistant", "已按 skill 完成", null, 10, 2)));
    }

    private ToolRegistry toolsWithSkill() throws IOException {
        Path skillDir = tempDir.resolve("user-skills").resolve("web-access");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
                "---\nname: web-access\ndescription: 联网决策手册\n---\n# 联网\n" + BODY_MARKER + "\n");
        SkillRegistry skills = new SkillRegistry(null, tempDir.resolve("user-skills"), null,
                new SkillStateStore(tempDir.resolve("skills.json")));
        skills.reload();
        ToolRegistry tools = new ToolRegistry();
        tools.setProjectPath(tempDir.toString());
        tools.setSkillRegistry(skills);
        return tools;
    }

    private static final class SingleTaskPlanner extends Planner {
        private SingleTaskPlanner(LlmClient llmClient) {
            super(llmClient);
        }

        @Override
        public ExecutionPlan createPlan(String goal) {
            ExecutionPlan plan = new ExecutionPlan("plan-skill", goal);
            plan.addTask(new Task("task_1", goal, Task.TaskType.ANALYSIS));
            plan.computeExecutionOrder();
            return plan;
        }
    }

    private static final class TwoParallelTaskPlanner extends Planner {
        private TwoParallelTaskPlanner(LlmClient llmClient) {
            super(llmClient);
        }

        @Override
        public ExecutionPlan createPlan(String goal) {
            ExecutionPlan plan = new ExecutionPlan("plan-skill-parallel", goal);
            plan.addTask(new Task("task_1", "读第一篇", Task.TaskType.ANALYSIS));
            plan.addTask(new Task("task_2", "读第二篇", Task.TaskType.ANALYSIS));
            plan.computeExecutionOrder();
            return plan;
        }
    }

    /** 并行任务的请求交错到达，按每个请求自己的最后一条消息决定回复，与调度顺序无关。 */
    private static final class TaskAwareClient implements LlmClient {
        private final List<List<Message>> requests = new ArrayList<>();

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public synchronized ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            requests.add(List.copyOf(messages));
            return endsWithSkillInjection(messages) ? answer("已按 skill 完成") : loadSkillCall("call-" + requests.size());
        }

        @Override
        public String getModelName() {
            return "test-model";
        }

        @Override
        public String getProviderName() {
            return "test";
        }

        @Override
        public int maxContextWindow() {
            return 256_000;
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
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            requests.add(List.copyOf(messages));
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("缺少预设响应");
            }
            return response;
        }

        @Override
        public String getModelName() {
            return "test-model";
        }

        @Override
        public String getProviderName() {
            return "test";
        }

        @Override
        public int maxContextWindow() {
            return 256_000;
        }
    }
}
