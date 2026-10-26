package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.plan.ExecutionPlan;
import com.paicli.plan.Planner;
import com.paicli.plan.Task;
import com.paicli.runtime.CancellationContext;
import com.paicli.runtime.CancellationToken;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolRegistry.ToolExecutionResult;
import com.paicli.tool.ToolRegistry.ToolInvocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计划执行的收尾控制：取消不触发重规划，任务循环内的防打转提醒和预算收尾。
 */
class PlanExecutionControlTest {

    private static final PrintStream SILENT = new PrintStream(OutputStream.nullOutputStream());

    @Test
    void cancellingDuringATaskDoesNotTriggerReplanning() {
        CancellationToken token = CancellationContext.startRun();
        try {
            ScriptedClient client = new ScriptedClient(new ArrayDeque<>()) {
                @Override
                public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                        throws IOException {
                    token.cancel();
                    throw new IOException("stream interrupted");
                }
            };
            ChainPlanner planner = new ChainPlanner(client);
            PlanExecuteAgent agent = new PlanExecuteAgent(client, new ToolRegistry(), planner, null,
                    (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(), SILENT);

            String result = agent.run("先读配置，再输出报告");

            assertEquals("⏹️ 已取消当前计划执行。", result);
            assertEquals(1, planner.created.get(), "取消后不能再发起重新规划");
            assertEquals(ExecutionPlan.PlanStatus.CANCELLED, planner.lastPlan.getStatus());
        } finally {
            CancellationContext.clear(token);
        }
    }

    @Test
    void failureMessageFallsBackToExceptionTypeWhenMessageIsMissing() {
        assertEquals("InterruptedException", PlanExecuteAgent.failureMessage(new InterruptedException()));
        assertEquals("超时", PlanExecuteAgent.failureMessage(new IOException("超时")));
    }

    @Test
    void repeatedToolCallsInsideATaskGetRemindedThenFinalizedWithoutTools() {
        LlmClient.ToolCall sameRead = new LlmClient.ToolCall("call_read",
                new LlmClient.ToolCall.Function("read_file", "{\"path\":\"a.txt\"}"));
        Queue<LlmClient.ChatResponse> responses = new ArrayDeque<>();
        for (int i = 0; i < 5; i++) {
            responses.add(new LlmClient.ChatResponse("assistant", "", List.of(sameRead), 10, 2));
        }
        responses.add(new LlmClient.ChatResponse("assistant", "按已读内容整理的结果", null, 10, 2));
        ScriptedClient client = new ScriptedClient(responses);
        PlanExecuteAgent agent = new PlanExecuteAgent(client, new OkToolRegistry(), new ChainPlanner(client, 1),
                null, (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(), SILENT);

        String result = agent.run("读取 a.txt 并总结");

        assertTrue(client.requests.stream().anyMatch(messages -> messages.stream()
                        .anyMatch(message -> "user".equals(message.role())
                                && String.valueOf(message.content()).startsWith("[runaway guard]"))),
                "同一动作连续 3 次要注入提醒");
        assertTrue(client.tools.get(client.tools.size() - 1).isEmpty(), "停滞后收尾请求不再提供工具");
        assertTrue(result.contains("部分完成"), result);
        assertTrue(result.contains("按已读内容整理的结果"), result);
    }

    private static class ScriptedClient implements LlmClient {
        private final Queue<ChatResponse> responses;
        final List<List<Message>> requests = new ArrayList<>();
        final List<List<Tool>> tools = new ArrayList<>();

        ScriptedClient(Queue<ChatResponse> responses) {
            this.responses = responses;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            requests.add(List.copyOf(messages));
            this.tools.add(tools == null ? List.of() : List.copyOf(tools));
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("缺少预设响应");
            }
            return response;
        }

        @Override
        public String getModelName() {
            return "scripted";
        }

        @Override
        public String getProviderName() {
            return "offline";
        }
    }

    /** 生成一条 x → y 的链式计划，或只有一个任务的计划。 */
    private static final class ChainPlanner extends Planner {
        private final AtomicInteger created = new AtomicInteger();
        private final int taskCount;
        private ExecutionPlan lastPlan;

        ChainPlanner(LlmClient client) {
            this(client, 2);
        }

        ChainPlanner(LlmClient client, int taskCount) {
            super(client, SILENT);
            this.taskCount = taskCount;
        }

        @Override
        public ExecutionPlan createPlan(String goal) {
            created.incrementAndGet();
            ExecutionPlan plan = new ExecutionPlan("plan-control", goal);
            plan.addTask(new Task("x", "读取配置", Task.TaskType.FILE_READ));
            if (taskCount > 1) {
                plan.addTask(new Task("y", "输出报告", Task.TaskType.ANALYSIS, List.of("x")));
            }
            plan.computeExecutionOrder();
            lastPlan = plan;
            return plan;
        }
    }

    private static final class OkToolRegistry extends ToolRegistry {
        @Override
        public List<ToolExecutionResult> executeTools(List<ToolInvocation> calls) {
            return calls.stream()
                    .map(call -> new ToolExecutionResult(call.id(), call.name(), call.argumentsJson(),
                            "文件内容：hello", 0, false, List.of(), true, List.of()))
                    .toList();
        }
    }
}
