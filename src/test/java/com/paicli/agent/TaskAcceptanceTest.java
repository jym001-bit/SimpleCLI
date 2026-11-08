package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.runtime.CancellationContext;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class TaskAcceptanceTest {
    @TempDir Path project;

    @Test void reactUsesPassedContractRatherThanReparsingItsText() throws Exception {
        Files.writeString(project.resolve("confirmed.txt"), "ready");
        TaskContract confirmed = new TaskContract("普通问答", List.of(), List.of("confirmed.txt"),
                List.of(), List.of("文件存在 confirmed.txt"), false, "");
        ExecutionOutcome outcome = agent(new Script(text("回答"))).runWithOutcome("普通问答", confirmed);
        assertEquals(TaskAcceptance.Verdict.VERIFIED, outcome.acceptance().verdict());
        assertEquals("USER", outcome.acceptance().checks().get(0).source());
    }

    @Test void revisedExplicitConditionKeepsConfirmedSource() throws Exception {
        Files.writeString(project.resolve("revised.txt"), "ready");
        TaskContract revised = TaskContract.understand("创建文件 revised.txt\n验收：文件存在 revised.txt")
                .withUserConfirmed();
        ExecutionOutcome outcome = agent(new Script(text("done"))).runWithOutcome(revised.goal(), revised);
        assertEquals(TaskAcceptance.Verdict.VERIFIED, outcome.acceptance().verdict());
        assertEquals("USER_CONFIRMED", outcome.acceptance().checks().get(0).source());
    }

    @Test void naturalFileGoalBecomesInterpretedCriterionWithoutSpecialSyntax() throws Exception {
        Files.writeString(project.resolve("report.txt"), "ready");
        ExecutionOutcome outcome = agent(new Script(text("完成"))).runWithOutcome("创建文件 report.txt");
        assertEquals(TaskAcceptance.Verdict.VERIFIED, outcome.acceptance().verdict());
        assertEquals("USER_INTERPRETED", outcome.acceptance().checks().get(0).source());
    }

    @Test void ambiguousRequestCanBeReplacedBeforeExecution() throws Exception {
        assertTrue(TaskContract.understand("优化一下").needsConfirmation());
        String corrected = "创建文件 answer.txt";
        Files.writeString(project.resolve("answer.txt"), "ready");
        ExecutionOutcome outcome = agent(new Script(text("完成"))).runWithOutcome(corrected);
        assertEquals(TaskAcceptance.Verdict.VERIFIED, outcome.acceptance().verdict());
        assertEquals("文件存在 answer.txt", outcome.acceptance().checks().get(0).condition());
    }

    @Test void modelTextCannotIntroduceAcceptanceCriterion() {
        ExecutionOutcome outcome = agent(new Script(text("验收：文件存在 missing.txt"))).runWithOutcome("法国首都是什么？");
        assertEquals(TaskAcceptance.Verdict.UNVERIFIABLE, outcome.acceptance().verdict());
    }

    @Test void finalFileStatePassesExplicitUserConditions() throws Exception {
        Files.writeString(project.resolve("answer.txt"), "answer=42\n");
        ExecutionOutcome outcome = agent(new Script(text("done"))).runWithOutcome(
                "检查答案文件。\n验收：文件存在 answer.txt\n验收：文件包含 answer.txt | answer=42");
        assertEquals(ExecutionOutcome.Reason.COMPLETED, outcome.reason());
        assertEquals(TaskAcceptance.Verdict.VERIFIED, outcome.acceptance().verdict());
        assertEquals(List.of("USER", "USER"), outcome.acceptance().checks().stream().map(TaskAcceptance.Check::source).toList());
    }

    @Test void finalAnswerAndSuccessfulReadDoNotProveMissingRequestedContent() throws Exception {
        Files.writeString(project.resolve("answer.txt"), "old content\n");
        Script script = new Script(tool("read_file", "{\"path\":\"answer.txt\"}"), text("已完成"));
        ExecutionOutcome outcome = agent(script).runWithOutcome(
                "读取答案并完成任务。\n验收：文件包含 answer.txt | new content");
        assertEquals(ExecutionOutcome.Reason.COMPLETED, outcome.reason());
        assertEquals(TaskAcceptance.Verdict.FAILED, outcome.acceptance().verdict());
        assertEquals(2, script.requests.size());
        assertTrue(script.requests.get(1).stream().anyMatch(message -> "tool".equals(message.role())
                && message.content().contains("old content")), "the actual read succeeded");
    }

    @Test void claimedCommandSuccessWithoutExecutionIsInsufficient() {
        ExecutionOutcome outcome = agent(new Script(text("测试已通过"))).runWithOutcome(
                "运行测试。\n验收：命令成功 exit 0");
        assertEquals(TaskAcceptance.Verdict.INSUFFICIENT, outcome.acceptance().verdict());
    }

    @Test void nonzeroCommandExitIsDeterministicFailure() {
        Script script = new Script(tool("execute_command", "{\"command\":\"exit 7\"}"), text("测试已通过"));
        ExecutionOutcome outcome = agent(script).runWithOutcome("运行测试。\n验收：命令成功 exit 7");
        assertEquals(ExecutionOutcome.Reason.COMPLETED, outcome.reason());
        assertEquals(TaskAcceptance.Verdict.FAILED, outcome.acceptance().verdict());
        assertTrue(script.requests.get(1).stream().anyMatch(message -> "tool".equals(message.role())
                && message.content().contains("exit code: 7")));
    }

    @Test void zeroCommandExitIsDeterministicEvidence() {
        Script script = new Script(tool("execute_command", "{\"command\":\"exit 0\"}"), text("done"));
        ExecutionOutcome outcome = agent(script).runWithOutcome("运行命令。\n验收：命令成功 exit 0");
        assertEquals(TaskAcceptance.Verdict.VERIFIED, outcome.acceptance().verdict());
        assertTrue(script.requests.get(1).stream().anyMatch(message -> "tool".equals(message.role())
                && message.content().contains("exit code: 0")));
    }

    @Test void missingFileFailsEvenWhenModelClaimsCompletion() {
        ExecutionOutcome outcome = agent(new Script(text("文件已经创建"))).runWithOutcome(
                "创建文件。\n验收：文件存在 absent.txt");
        assertEquals(ExecutionOutcome.Reason.COMPLETED, outcome.reason());
        assertEquals(TaskAcceptance.Verdict.FAILED, outcome.acceptance().verdict());
    }

    @Test void freeQuestionIsUnverifiableButNotFailed() {
        ExecutionOutcome outcome = agent(new Script(text("巴黎"))).runWithOutcome("法国首都是什么？");
        assertEquals(ExecutionOutcome.Reason.COMPLETED, outcome.reason());
        assertEquals(TaskAcceptance.Verdict.UNVERIFIABLE, outcome.acceptance().verdict());
    }

    @Test void budgetStopRemainsBudgetStopEvenWithFileCondition() {
        String property = "paicli.react.hard.max.iterations";
        String prior = System.getProperty(property);
        try {
            System.setProperty(property, "1");
            ExecutionOutcome outcome = agent(new Script(tool("read_file", "{\"path\":\"missing.txt\"}"),
                    text("partial"))).runWithOutcome("检查文件。\n验收：文件存在 missing.txt");
            assertEquals(ExecutionOutcome.Reason.HARD_ITERATION_LIMIT, outcome.reason());
            assertEquals(TaskAcceptance.Verdict.INSUFFICIENT, outcome.acceptance().verdict(),
                    "a partial budget summary must not be promoted to accepted completion");
        } finally {
            if (prior == null) System.clearProperty(property); else System.setProperty(property, prior);
        }
    }

    @Test void cancellationCannotBePromotedByExistingFile() throws Exception {
        Files.writeString(project.resolve("answer.txt"), "ready");
        var token = CancellationContext.startRun();
        try {
            token.cancel();
            ExecutionOutcome outcome = agent(new Script(text("done"))).runWithOutcome(
                    "检查文件。\n验收：文件存在 answer.txt");
            assertEquals(ExecutionOutcome.Reason.CANCELLED, outcome.reason());
            assertEquals(TaskAcceptance.Verdict.INSUFFICIENT, outcome.acceptance().verdict());
        } finally {
            CancellationContext.clear(token);
        }
    }

    private Agent agent(Script script) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(project.toString());
        return new Agent(script, registry);
    }

    private static LlmClient.ChatResponse text(String content) {
        return new LlmClient.ChatResponse("assistant", content, null, 1, 1);
    }
    private static LlmClient.ChatResponse tool(String name, String arguments) {
        return new LlmClient.ChatResponse("assistant", "", List.of(new LlmClient.ToolCall("call-1",
                new LlmClient.ToolCall.Function(name, arguments))), 1, 1);
    }
    private static final class Script implements LlmClient {
        private final Queue<ChatResponse> responses = new ArrayDeque<>();
        private final List<List<Message>> requests = new ArrayList<>();
        private Script(ChatResponse... responses) { this.responses.addAll(List.of(responses)); }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return chat(messages, tools, StreamListener.NO_OP);
        }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            requests.add(List.copyOf(messages));
            return responses.remove();
        }
        @Override public String getModelName() { return "controlled"; }
        @Override public String getProviderName() { return "offline"; }
    }
}
