package com.paicli.cli;

import com.paicli.agent.*;
import com.paicli.config.PaiCliConfig;
import com.paicli.hitl.HitlHandler;
import com.paicli.llm.LlmClient;
import com.paicli.memory.MemoryManager;
import com.paicli.runtime.CancellationContext;
import com.paicli.runtime.RootTaskScope;
import com.paicli.runtime.RootTaskLlmClient;
import com.paicli.tool.ToolRegistry;
import com.paicli.tui.TuiSessionController;
import com.paicli.tui.pane.CenterPane;
import com.paicli.tui.pane.StatusPane;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Plan/Team loops through the actual CLI cancellation boundary and TUI command routes. */
class RootModeOutcomeTest {
    enum Mode { PLAN, TEAM }
    enum Case { NORMAL, STREAM_SUCCESS, BUDGET, MODEL_ERROR, STREAM_ERROR, CANCELLED }
    private static final String FULL = "ACTUALRESULT" + "verified-evidence-".repeat(16);
    @TempDir Path temp;
    private final Map<String, String> properties = new LinkedHashMap<>();

    @BeforeEach void setup() throws IOException {
        set("user.home", temp.resolve("home").toString());
        set("paicli.memory.auto.extract.enabled", "false");
        set("paicli.react.hard.max.iterations", "1");
        set("paicli.react.token.budget", "1000000");
        set("paicli.snapshot.enabled", "false"); // Isolate this result-routing test from asynchronous SideGit handles.
        Files.writeString(temp.resolve("evidence.txt"), "ACTUAL_TOOL_EVIDENCE\n");
    }
    @AfterEach void restore() {
        Thread.interrupted();
        properties.forEach((key, value) -> {
            if (value == null) System.clearProperty(key); else System.setProperty(key, value);
        });
    }
    private void set(String key, String value) {
        properties.put(key, System.getProperty(key));
        System.setProperty(key, value);
    }
    static Stream<org.junit.jupiter.params.provider.Arguments> cases() {
        return Arrays.stream(Mode.values()).flatMap(mode -> Arrays.stream(Case.values())
                .map(test -> org.junit.jupiter.params.provider.Arguments.of(mode, test)));
    }

    @ParameterizedTest @MethodSource("cases")
    void rootReasonAndEvidenceReachActualCliBoundary(Mode mode, Case test) throws Exception {
        Script client = new Script(mode, test);
        Callable<ExecutionOutcome> root = root(mode, client);
        ExecutionOutcome outcome = cli(root);
        assertEquals(reason(test), outcome.reason());
        if (test == Case.NORMAL || test == Case.STREAM_SUCCESS) {
            assertTrue(outcome.content().contains(FULL), "Machine result must not be truncated or stream-suppressed");
            if (mode == Mode.PLAN && test == Case.STREAM_SUCCESS)
                assertFalse(outcome.displayContent().contains(FULL), "Already streamed Plan output must not repeat");
            if (mode == Mode.TEAM) assertFalse(outcome.displayContent().contains(FULL), "Legacy Team preview stays bounded");
        } else if (test == Case.BUDGET) assertTrue(outcome.content().contains("RETAINED_BUDGET"));
        else if (test == Case.MODEL_ERROR) assertTrue(outcome.content().contains("controlled model failure"));
        else assertTrue(outcome.content().contains("ACTUAL_STREAM_PARTIAL"));
        assertEquals(switch (test) {
            case NORMAL, STREAM_SUCCESS -> mode == Mode.PLAN ? 2 : 3;
            case BUDGET -> 3;
            default -> 2;
        }, client.calls, "No dependent, reviewer or replan after terminal child outcome");
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void completedChildrenAndApprovedReviewerDoNotOverrideRootFileAcceptance(Mode mode) throws Exception {
        String input = "inspect\n验收：文件包含 evidence.txt | REQUIRED_NEW_CONTENT";
        ExecutionOutcome outcome = cli(root(mode, new Script(mode, Case.NORMAL), input));
        assertEquals(ExecutionOutcome.Reason.COMPLETED, outcome.reason());
        assertEquals(TaskAcceptance.Verdict.FAILED, outcome.acceptance().verdict());
        assertTrue(outcome.content().contains(FULL));
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void planAndTeamUsePassedContractInsteadOfReparsingGoal(Mode mode) throws Exception {
        TaskContract confirmed = new TaskContract("inspect", List.of(), List.of("evidence.txt"),
                List.of(), List.of("文件存在 evidence.txt"), false, "");
        Script client = new Script(mode, Case.NORMAL);
        ToolRegistry tools = registry();
        MemoryManager memory = new MemoryManager(client);
        memory.setAutoFactExtractionEnabled(false);
        ExecutionOutcome outcome = mode == Mode.PLAN
                ? new PlanExecuteAgent(client, tools, memory,
                    (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()).runWithOutcome("inspect", confirmed)
                : new AgentOrchestrator(client, tools, memory).runWithOutcome("inspect", confirmed);
        assertEquals(TaskAcceptance.Verdict.VERIFIED, outcome.acceptance().verdict());
        assertEquals("USER", outcome.acceptance().checks().get(0).source());
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void plannerWorkerAndReviewerShareRootRequestAdmission(Mode mode) throws Exception {
        set("paicli.root.model.request.limit", mode == Mode.PLAN ? "2" : "3");
        Script provider = new Script(mode, Case.NORMAL);
        LlmClient client = RootTaskLlmClient.wrap(provider);
        ToolRegistry tools = registry();
        MemoryManager memory = new MemoryManager(client);
        memory.setAutoFactExtractionEnabled(false);
        try (RootTaskScope scope = new RootTaskScope(); var ignored = scope.bind()) {
            ExecutionOutcome outcome = mode == Mode.PLAN
                    ? new PlanExecuteAgent(client, tools, memory,
                        (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute())
                        .runWithOutcome("inspect")
                    : new AgentOrchestrator(client, tools, memory).runWithOutcome("inspect");
            assertEquals(ExecutionOutcome.Reason.COMPLETED, outcome.reason());
            assertEquals(mode == Mode.PLAN ? 2 : 3, scope.requests());
            assertEquals(scope.requests(), provider.calls);
            assertThrows(RootTaskScope.RootRequestBudgetExceededException.class,
                    () -> client.chat(List.of(LlmClient.Message.user("extra")), List.of()));
            assertEquals(scope.requests(), provider.calls, "Blocked request must not reach provider");
        }
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void currentRootCommandEvidenceReachesAcceptanceWithoutObservationSink(Mode mode) throws Exception {
        System.setProperty("paicli.react.hard.max.iterations", "3");
        // The command tool starts a logger whose Windows file handle outlives @TempDir cleanup.
        System.setProperty("user.home", Path.of("target", "cross-acceptance-test-home").toAbsolutePath().toString());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        LlmClient client = new LlmClient() {
            @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) {
                return chat(messages, tools, StreamListener.NO_OP);
            }
            @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
                int call = calls.incrementAndGet();
                if (call == 1) return response("{\"" + (mode == Mode.PLAN ? "tasks" : "steps")
                        + "\":[{\"id\":\"a\",\"description\":\"run exit 0\",\"type\":\"COMMAND\",\"dependencies\":[]}]}");
                if (call == 2) return new ChatResponse("assistant", "", List.of(new ToolCall("command-1",
                        new ToolCall.Function("execute_command", "{\"command\":\"exit 0\"}"))), 2, 2);
                if (call == 3) return response("command finished");
                if (mode == Mode.TEAM && call == 4) return response("{\"approved\":true}");
                throw new AssertionError("unexpected request " + call);
            }
            @Override public String getProviderName() { return "offline"; }
            @Override public String getModelName() { return "controlled"; }
        };
        ToolRegistry tools = registry();
        MemoryManager memory = new MemoryManager(client);
        memory.setAutoFactExtractionEnabled(false);
        String input = "运行命令。\n验收：命令成功 exit 0";
        ExecutionOutcome outcome = mode == Mode.PLAN
                ? new PlanExecuteAgent(client, tools, memory,
                    (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()).runWithOutcome(input)
                : new AgentOrchestrator(client, tools, memory).runWithOutcome(input);
        assertEquals(ExecutionOutcome.Reason.COMPLETED, outcome.reason());
        assertEquals(TaskAcceptance.Verdict.VERIFIED, outcome.acceptance().verdict());
        assertEquals(mode == Mode.PLAN ? 3 : 4, calls.get());
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void oldStringApiStillReturnsItsOriginalDisplayView(Mode mode) throws Exception {
        Script typedClient = new Script(mode, Case.STREAM_SUCCESS);
        ExecutionOutcome typed = cli(root(mode, typedClient));
        Script legacyClient = new Script(mode, Case.STREAM_SUCCESS);
        ToolRegistry tools = registry();
        MemoryManager memory = new MemoryManager(legacyClient);
        memory.setAutoFactExtractionEnabled(false);
        String legacy = mode == Mode.PLAN
                ? new PlanExecuteAgent(legacyClient, tools, memory,
                    (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()).run("inspect")
                : new AgentOrchestrator(legacyClient, tools, memory).run("inspect");
        assertEquals(typed.displayContent(), legacy);
        assertTrue(typed.content().contains(FULL));
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void actualTuiBudgetCommandRetainsMachineReason(Mode mode) throws Exception {
        runTui(mode, Case.BUDGET);
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void actualTuiStreamingCommandDisplaysCompleteBodyOnce(Mode mode) throws Exception {
        runTui(mode, Case.STREAM_SUCCESS);
    }

    private void runTui(Mode mode, Case test) throws Exception {
        Script client = new Script(mode, test);
        Agent react = new Agent(client, registry());
        react.getMemoryManager().setAutoFactExtractionEnabled(false);
        CenterPane center = mock(CenterPane.class);
        try (TuiSessionController tui = new TuiSessionController(new PaiCliConfig(), client, react,
                mock(HitlHandler.class), center, mock(StatusPane.class), () -> {}, () -> {}, Runnable::run)) {
            tui.submit((mode == Mode.PLAN ? "/plan " : "/team ") + "inspect");
            var field = TuiSessionController.class.getDeclaredField("currentTask");
            field.setAccessible(true);
            ((Future<?>) field.get(tui)).get(8, TimeUnit.SECONDS);
            assertEquals(reason(test), tui.getLastOutcome().reason());
            if (test == Case.STREAM_SUCCESS) {
                assertTrue(tui.getLastOutcome().content().contains(FULL));
                var output = org.mockito.ArgumentCaptor.forClass(String.class);
                verify(center).appendAssistantOutput(output.capture());
                String rendered = output.getValue().replaceAll("\\s+", ""); // Renderer may wrap long lines.
                assertEquals(1, rendered.split(java.util.regex.Pattern.quote(FULL), -1).length - 1,
                        "Complete result must occur once after line-wrap normalization: " + output.getValue());
            }
        }
    }

    @Test void workerRepairErrorsCannotBecomeRootSuccessOrUnblockDependents() throws Exception {
        Script client = new Script(Mode.TEAM, Case.NORMAL) {
            @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
                calls++;
                return switch (calls) {
                    case 1 -> response(plan(true));
                    case 2 -> response("PREVIOUS_WORKER_OUTPUT");
                    case 3 -> response("{\"approved\":false,\"issues\":[\"repair\"]}");
                    case 4, 5 -> throw new IOException("repair execution failed");
                    default -> throw new AssertionError("Reviewer or dependent started after failed repair");
                };
            }
        };
        ExecutionOutcome outcome = cli(root(Mode.TEAM, client));
        assertEquals(ExecutionOutcome.Reason.MODEL_ERROR, outcome.reason());
        assertTrue(outcome.content().contains("PREVIOUS_WORKER_OUTPUT"));
        assertTrue(outcome.content().contains("repair execution failed"));
        assertEquals(5, client.calls);
    }

    private ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(temp.toString());
        return registry;
    }
    private Callable<ExecutionOutcome> root(Mode mode, Script client) {
        return root(mode, client, "inspect");
    }
    private Callable<ExecutionOutcome> root(Mode mode, Script client, String input) {
        ToolRegistry tools = registry();
        MemoryManager memory = new MemoryManager(client);
        memory.setAutoFactExtractionEnabled(false);
        PrintStream sink = new PrintStream(new ByteArrayOutputStream());
        if (mode == Mode.PLAN) {
            PlanExecuteAgent plan = new PlanExecuteAgent(client, tools, memory,
                    (goal, parsed) -> PlanExecuteAgent.PlanReviewDecision.execute(), sink);
            return () -> plan.runWithOutcome(input);
        }
        AgentOrchestrator team = new AgentOrchestrator(client, tools, memory, sink);
        return () -> team.runWithOutcome(input);
    }
    private static ExecutionOutcome cli(Callable<ExecutionOutcome> task) throws Exception {
        var method = Main.class.getDeclaredMethod("runOutcomeWithCancelSupport",
                org.jline.terminal.Terminal.class, PrintStream.class, Callable.class);
        method.setAccessible(true);
        try { return (ExecutionOutcome) method.invoke(null, null, System.out, task); }
        catch (InvocationTargetException e) { throw new AssertionError(e.getCause()); }
    }
    private static ExecutionOutcome.Reason reason(Case test) {
        return switch (test) {
            case NORMAL, STREAM_SUCCESS -> ExecutionOutcome.Reason.COMPLETED;
            case BUDGET -> ExecutionOutcome.Reason.HARD_ITERATION_LIMIT;
            case CANCELLED -> ExecutionOutcome.Reason.CANCELLED;
            default -> ExecutionOutcome.Reason.MODEL_ERROR;
        };
    }
    private static LlmClient.ChatResponse response(String text) {
        return new LlmClient.ChatResponse("assistant", text, null, 2, 2);
    }
    private static class Script implements LlmClient {
        final Mode mode;
        final Case test;
        int calls;
        Script(Mode mode, Case test) { this.mode = mode; this.test = test; }
        String plan(boolean dependency) {
            String entries = "{\"id\":\"a\",\"description\":\"inspect\",\"type\":\"ANALYSIS\",\"dependencies\":[]}"
                    + (dependency ? ",{\"id\":\"b\",\"description\":\"dependent\",\"type\":\"ANALYSIS\",\"dependencies\":[\"a\"]}" : "");
            return "{\"summary\":\"inspect\",\"" + (mode == Mode.PLAN ? "tasks" : "steps") + "\":[" + entries + "]}";
        }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            calls++;
            if (calls == 1) return response(plan(test != Case.NORMAL && test != Case.STREAM_SUCCESS));
            if (calls == 2) {
                if (test == Case.BUDGET) return new ChatResponse("assistant", "", List.of(new ToolCall("evidence",
                        new ToolCall.Function("read_file", "{\"path\":\"evidence.txt\"}"))), 2, 2);
                if (test == Case.CANCELLED || test == Case.STREAM_ERROR) listener.onContentDelta("ACTUAL_STREAM_PARTIAL");
                if (test == Case.CANCELLED) CancellationContext.current().cancel();
                if (test == Case.CANCELLED || test == Case.STREAM_ERROR || test == Case.MODEL_ERROR)
                    throw new IOException("controlled model failure");
                if (test == Case.STREAM_SUCCESS) listener.onContentDelta(FULL);
                return response(FULL);
            }
            if (test == Case.BUDGET && calls == 3) {
                assertTrue(tools.isEmpty());
                return response("RETAINED_BUDGET");
            }
            if (mode == Mode.TEAM && calls == 3) return response("{\"approved\":true}");
            throw new AssertionError("Unexpected model request " + calls);
        }
        @Override public String getProviderName() { return "offline"; }
        @Override public String getModelName() { return "controlled-root"; }
        @Override public boolean managesRequestRetries() { return true; }
    }
}
