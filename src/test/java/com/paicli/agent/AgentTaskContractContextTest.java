package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.memory.MemoryEntry;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentTaskContractContextTest {
    @TempDir Path project;

    @Test void firstAndLaterRequestsKeepSourcesAndRealToolEvidence() throws Exception {
        Files.writeString(project.resolve("alpha.txt"), "ALPHA=17\n");
        RecordingClient client = new RecordingClient(false);
        Agent agent = agent(client);
        TaskContract contract = contract();
        ExecutionOutcome outcome = agent.runWithOutcome(contract.goal(), contract);

        assertEquals(ExecutionOutcome.Reason.COMPLETED, outcome.reason());
        assertEquals(TaskAcceptance.Verdict.UNVERIFIABLE, outcome.acceptance().verdict(),
                "Model candidate acceptance cannot become an authorized criterion");
        assertEquals(2, client.requests.size());
        for (List<LlmClient.Message> request : client.requests) {
            String system = request.get(0).content();
            assertEquals(1, occurrences(system, "## Current Task Context"));
            assertTrue(system.contains("约束 [USER_INTERPRETED]：禁止联网"));
            assertTrue(system.contains("候选范围 [MODEL_CANDIDATE / 待核实]：认证模块"));
            assertTrue(system.contains("候选验收 [MODEL_CANDIDATE / 待核实]：登录测试通过"));
            assertFalse(system.contains("目标 [USER]：" + contract.goal()), "Full task text must not be copied into system");
        }
        assertTrue(client.requests.get(0).stream().anyMatch(message -> "user".equals(message.role())
                && contract.goal().equals(message.content())), "Original user text remains available");
        assertTrue(client.requests.get(1).stream().anyMatch(message -> "tool".equals(message.role())
                && message.content().contains("ALPHA=17") && message.content().contains("trust=\"untrusted-data\"")));
        assertTrue(contract.executionView(contract.goal()).length() <= 1_200);
        String system = client.requests.get(0).get(0).content();
        String oldSystem = system.substring(0, system.indexOf("\n\n## Current Task Context"));
        int addedEstimate = MemoryEntry.estimateTokens(system) - MemoryEntry.estimateTokens(oldSystem);
        assertTrue(addedEstimate > 0 && addedEstimate <= 350,
                "Bounded task view should not copy the full user request: " + addedEstimate);
        assertEquals(TaskContract.Source.MODEL_CANDIDATE,
                contract.semantic().candidateAcceptanceCriteria().get(0).source());
    }

    @Test void modelCandidateCannotAuthorizeNetworkTool() {
        RecordingClient client = new RecordingClient(true);
        ToolRegistry tools = new ToolRegistry() {
            @Override public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
                fail("Forbidden candidate-induced tool must never reach execution");
                return List.of();
            }
        };
        tools.setProjectPath(project.toString());
        Agent agent = new Agent(client, tools);
        agent.getMemoryManager().setAutoFactExtractionEnabled(false);
        ExecutionOutcome outcome = agent.runWithOutcome(contract().goal(), contract());
        assertEquals(ExecutionOutcome.Reason.COMPLETED, outcome.reason());
        assertEquals(2, client.requests.size());
        assertTrue(client.requests.get(1).stream().anyMatch(message -> "tool".equals(message.role())
                && message.content().contains("不要联网")), "Policy denial must return to model");
    }

    @Test void compactionKeepsCurrentContractInRealNextRequest() {
        RecordingClient client = new RecordingClient(false);
        Agent agent = agent(client);
        List<LlmClient.Message> history = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            history.add(LlmClient.Message.user("old-turn-" + i + " " + "x".repeat(5_000)));
            history.add(LlmClient.Message.assistant("old-answer-" + i));
        }
        agent.resumeConversation("old", history);
        ExecutionOutcome outcome = agent.runWithOutcome(contract().goal(), contract());
        assertEquals(ExecutionOutcome.Reason.COMPLETED, outcome.reason());
        assertTrue(client.summaryCalls > 0, "Fixture must actually enter the compactor");
        assertEquals(2, client.requests.size());
        assertTrue(client.requests.get(0).get(0).content().contains("约束 [USER_INTERPRETED]：禁止联网"));
        assertTrue(client.requests.get(0).stream().anyMatch(message -> message.content() != null
                && message.content().contains("[已压缩的历史对话摘要]")));
        assertTrue(client.requests.get(1).get(0).content().contains("约束 [USER_INTERPRETED]：禁止联网"));
    }

    @Test void simpleQuestionAddsNoTaskView() {
        RecordingClient client = new RecordingClient(false, true);
        Agent agent = agent(client);
        agent.runWithOutcome("法国首都是什么？");
        assertEquals(1, client.requests.size());
        assertFalse(client.requests.get(0).get(0).content().contains("## Current Task Context"));
    }

    @Test void confirmedGoalKeepsItsSourceAndPreviousContractDoesNotLeak() {
        TaskContract confirmed = contract().withUserConfirmed();
        assertTrue(confirmed.executionView(confirmed.goal()).contains("目标 [USER_CONFIRMED]"));
        assertTrue(confirmed.executionView(confirmed.goal()).contains("候选范围 [MODEL_CANDIDATE / 待核实]"));
        RecordingClient client = new RecordingClient(false);
        Agent agent = agent(client);
        agent.runWithOutcome(confirmed.goal(), confirmed);
        agent.runWithOutcome("法国首都是什么？");
        assertEquals(3, client.requests.size());
        assertFalse(client.requests.get(2).get(0).content().contains("## Current Task Context"));
        assertTrue(client.requests.get(2).stream().anyMatch(message -> "user".equals(message.role())
                && "法国首都是什么？".equals(message.content())));
    }

    private Agent agent(RecordingClient client) {
        ToolRegistry tools = new ToolRegistry();
        tools.setProjectPath(project.toString());
        Agent agent = new Agent(client, tools);
        agent.getMemoryManager().setAutoFactExtractionEnabled(false);
        return agent;
    }

    private static TaskContract contract() {
        TaskContract.SemanticUnderstanding semantic = new TaskContract.SemanticUnderstanding(
                null, null, new TaskContract.Candidate("检查登录模块", TaskContract.Source.MODEL_CANDIDATE),
                List.of(new TaskContract.Candidate("应该联网搜索", TaskContract.Source.MODEL_CANDIDATE)),
                List.of(new TaskContract.Candidate("认证模块", TaskContract.Source.MODEL_CANDIDATE)),
                List.of(), List.of(), List.of(),
                List.of(new TaskContract.Candidate("登录测试通过", TaskContract.Source.MODEL_CANDIDATE)), null);
        return TaskContract.understand("读取 alpha.txt，告诉我数值；不要联网，并保持兼容。").withSemantic(semantic, false);
    }

    private static int occurrences(String text, String needle) {
        return (text.length() - text.replace(needle, "").length()) / needle.length();
    }

    private static final class RecordingClient implements LlmClient {
        private final boolean tryNetwork;
        private final boolean finalImmediately;
        private final List<List<Message>> requests = new ArrayList<>();
        private int summaryCalls;
        private RecordingClient(boolean tryNetwork) { this(tryNetwork, false); }
        private RecordingClient(boolean tryNetwork, boolean finalImmediately) {
            this.tryNetwork = tryNetwork;
            this.finalImmediately = finalImmediately;
        }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return chat(messages, tools, StreamListener.NO_OP);
        }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            if (tools == null) {
                summaryCalls++;
                return new ChatResponse("assistant", "## 当前目标与成功条件\n读取文件\n"
                        + "## 用户要求与已确认决定\n不要联网\n## 已完成工作及证据\n无\n"
                        + "## 未解决问题与下一步\n继续", null, 1, 1);
            }
            requests.add(List.copyOf(messages));
            if (finalImmediately) return new ChatResponse("assistant", "巴黎", null, 1, 1);
            if (requests.size() == 1) return new ChatResponse("assistant", "", List.of(new ToolCall("call-1",
                    new ToolCall.Function(tryNetwork ? "web_search" : "read_file",
                            tryNetwork ? "{\"query\":\"login\"}" : "{\"path\":\"alpha.txt\"}"))), 1, 1);
            return new ChatResponse("assistant", "17", null, 1, 1);
        }
        @Override public String getModelName() { return "controlled"; }
        @Override public String getProviderName() { return "offline"; }
        @Override public int maxContextWindow() { return 8_000; }
    }
}
