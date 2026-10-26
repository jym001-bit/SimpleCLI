package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.agent.AgentOrchestrator;
import com.paicli.agent.AgentRole;
import com.paicli.agent.TeamExecutionObserver;
import com.paicli.eval.benchmark.relay.TeamObservationWire;
import com.paicli.eval.benchmark.relay.TeamRequestAudit;
import com.paicli.llm.LlmClient;
import com.paicli.memory.AutoCompactionManager;
import com.paicli.memory.LongTermMemory;
import com.paicli.memory.MemoryManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.paicli.agent.TeamExecutionObserver.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual offline Team actors plus separately captured provider requests; no model score/admission. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class TeamRequestAuditTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String GOAL = "离线完成代码和测试，再根据两者编写文档；允许项目内文件读写，禁止联网。TEAM_PRIVATE_GOAL_甲";
    static final String MODEL = "deepseek-v4-flash";
    static final String CODE_RESULT = "CODE-result-1";
    static final String TEST_RESULT = "TEST-result-1";
    @TempDir Path temp;
    private String priorSessionMemory;

    @BeforeEach void noAsynchronousSummaryRequests() {
        priorSessionMemory = System.getProperty(AutoCompactionManager.SESSION_MEMORY_PROPERTY);
        System.setProperty(AutoCompactionManager.SESSION_MEMORY_PROPERTY, "false");
    }
    @AfterEach void restoreConfiguration() {
        if (priorSessionMemory == null) System.clearProperty(AutoCompactionManager.SESSION_MEMORY_PROPERTY);
        else System.setProperty(AutoCompactionManager.SESSION_MEMORY_PROPERTY, priorSessionMemory);
    }

    @Test void parallelActorsBindActualInputsToolsAndDependencyResultsBeforeDocumentStarts() throws Exception {
        var harness = new NativeHarness(temp, Scenario.PARALLEL, Mutation.NONE, true);
        assertTrue(harness.run().startsWith("✅"));
        harness.assertHealthy();
        var snapshot = harness.audit.snapshot();
        assertEquals(1, snapshot.schemaVersion()); assertEquals("TEAM", snapshot.mode());
        assertEquals(10, snapshot.providerAttempts().size()); assertEquals(10, harness.script.requests.size());
        assertEquals(CODE_RESULT + "\n" + TEST_RESULT, Files.readString(temp.resolve("doc.txt")));
        assertTrue(harness.script.docConsumedBoth);
        long overlap = Math.min(exit(harness.events, "step_1").elapsedNanos(), exit(harness.events, "step_2").elapsedNanos())
                - Math.max(enter(harness.events, "step_1").elapsedNanos(), enter(harness.events, "step_2").elapsedNanos());
        assertTrue(overlap > 0, "native worker bodies overlap; the single host provider channel remains serial");
        assertTrue(enter(harness.events, "step_3").elapsedNanos() >= Math.max(
                exit(harness.events, "step_1").elapsedNanos(), exit(harness.events, "step_2").elapsedNanos()));
        for (int i = 0; i < snapshot.providerAttempts().size(); i++) {
            var attempt = snapshot.providerAttempts().get(i);
            var actual = harness.script.requests.get(i);
            assertEquals(i + 1, attempt.ordinal()); assertEquals("team:" + attempt.activation().activationId(), attempt.scope());
            assertEquals(attempt.scope(), attempt.binding().scope());
            assertTrue(attempt.providerDispatched()); assertTrue(attempt.delivered());
            assertNotNull(attempt.returnedResponse()); assertEquals(attempt.returnedResponse(), attempt.response());
            assertTrue(attempt.requestStartedNanos() <= attempt.terminalNanos());
            assertTrue(attempt.eventsSeenAtRequest() > 0);
            assertTrue(snapshot.events().get(attempt.eventsSeenAtRequest() - 1).hostElapsedNanos() <= attempt.requestStartedNanos());
            assertEquals(actual.size(), attempt.messages().size());
            for (int m = 0; m < actual.size(); m++) {
                assertEquals(actual.get(m).role(), attempt.messages().get(m).role());
                assertEquals(actual.get(m).content(), attempt.messages().get(m).content());
                assertEquals(actual.get(m).reasoningContent(), attempt.messages().get(m).reasoningContent());
                assertEquals(actual.get(m).toolCallId(), attempt.messages().get(m).toolCallId());
            }
            assertEquals(harness.script.exposures.get(i).stream().map(LlmClient.Tool::name).toList(),
                    attempt.tools().stream().map(tool -> tool.name()).toList());
        }
        assertFalse(harness.budgetRequests.stream().anyMatch(Boolean::booleanValue), "no-tools planner/reviewer are not budget closing calls");
        var planner = snapshot.providerAttempts().stream().filter(p -> p.activation().role() == AgentRole.PLANNER).findFirst().orElseThrow();
        assertTrue(planner.tools().isEmpty());
        assertTrue(snapshot.providerAttempts().stream().filter(p -> p.activation().role() == AgentRole.WORKER)
                .allMatch(p -> p.tools().stream().map(tool -> tool.name()).collect(java.util.stream.Collectors.toSet())
                        .equals(BenchmarkToolProfile.FILE_ONLY.allowedTools())));
        assertTrue(select(harness.events, ReviewEvaluated.class).stream().allMatch(e -> e.decision() == ReviewDecision.APPROVED));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.providerAttempts().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.providerAttempts().get(0).messages().clear());
        assertFalse(JSON.writeValueAsString(snapshot.events()).contains("TEAM_PRIVATE_GOAL_甲"));
        assertTrue(JSON.writeValueAsString(snapshot.providerAttempts()).contains("TEAM_PRIVATE_GOAL_甲"),
                "private host audit retains actual request bodies; digest-only events are not a substitute");
    }

    @Test void threeRetriesRetainTheExactHistoryButBindEachFreshActivationSeparately() throws Exception {
        var harness = new NativeHarness(temp, Scenario.RETRY, Mutation.NONE, false);
        assertTrue(harness.run().contains("WORK-result-3")); harness.assertHealthy();
        var workers = select(harness.events, ActivationEntered.class).stream().filter(e -> e.activation().role() == AgentRole.WORKER).toList();
        assertEquals(3, workers.size());
        assertEquals(1, workers.stream().map(e -> e.activation().actorInstanceId()).distinct().count());
        assertEquals(1, workers.stream().map(e -> e.activation().historyGeneration()).distinct().count());
        assertEquals(3, workers.stream().map(e -> e.activation().activationId()).distinct().count());
        for (int number = 2; number <= 3; number++) {
            int attempt = number;
            var request = harness.audit.snapshot().providerAttempts().stream()
                    .filter(p -> p.activation().role() == AgentRole.WORKER && p.activation().attempt() == attempt).findFirst().orElseThrow();
            assertTrue(request.messages().stream().anyMatch(m -> ("WORK-result-" + (attempt - 1)).equals(m.content())));
            assertTrue(request.messages().stream().anyMatch(m -> "user".equals(m.role()) && m.content().contains("之前的执行结果被审查拒绝，原因：")));
        }
        assertEquals(List.of(ReviewDecision.REJECTED, ReviewDecision.REJECTED, ReviewDecision.APPROVED),
                select(harness.events, ReviewEvaluated.class).stream().map(ReviewEvaluated::decision).toList());
        assertEquals(workers.get(2).activation().activationId(), only(harness.events, StepExited.class).acceptedWorkerActivationId());
        assertEquals(10, harness.audit.snapshot().providerAttempts().size());
        assertEquals(3, select(harness.events, ToolBatchReturned.class).size());
        assertTrue(select(harness.events, ToolBatchReturned.class).stream().allMatch(e -> e.results().get(0).callId().equals("same-call")));
    }

    @ParameterizedTest @ValueSource(strings = {"REVIEW_ERROR_FIRST", "REVIEW_ERROR_RETRY", "REJECT_ALL"})
    void productCompletedNeverTurnsFailedOrRejectedReviewIntoApproval(String control) throws Exception {
        var scenario = Scenario.valueOf(control);
        var harness = new NativeHarness(temp, scenario, Mutation.NONE, false);
        assertTrue(harness.run().startsWith("✅")); harness.assertHealthy();
        var decisions = select(harness.events, ReviewEvaluated.class).stream().map(ReviewEvaluated::decision).toList();
        assertFalse(decisions.contains(ReviewDecision.APPROVED));
        assertEquals(ProductStatus.COMPLETED, only(harness.events, StepExited.class).productStatus());
        if (scenario == Scenario.REJECT_ALL) {
            assertEquals(List.of(ReviewDecision.REJECTED, ReviewDecision.REJECTED, ReviewDecision.REJECTED), decisions);
            assertEquals(StepExitReason.RETRIES_EXHAUSTED_RETAINED, only(harness.events, StepExited.class).reason());
        } else {
            assertEquals(ReviewDecision.ERROR, decisions.get(decisions.size() - 1));
            assertEquals(StepExitReason.REVIEW_ERROR_RETAINED, only(harness.events, StepExited.class).reason());
            var failed = harness.audit.snapshot().providerAttempts().stream().filter(p -> !p.delivered()).toList();
            assertEquals(1, failed.size()); assertTrue(failed.get(0).providerDispatched());
            assertNull(failed.get(0).response()); assertNull(failed.get(0).returnedResponse());
            assertEquals("IOException", failed.get(0).failureType());
            assertEquals(harness.script.calls.get(), harness.audit.snapshot().providerAttempts().size(), "failed provider attempt must not disappear");
            assertFalse(JSON.writeValueAsString(harness.audit.snapshot()).contains("PRIVATE_UPSTREAM_ERROR"));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"UNKNOWN_SCOPE", "CROSS_ROLE", "INPUT_INDEX", "INPUT_TEXT", "SYSTEM_DRIFT", "MISSING_INPUT", "MISSING_BATCH", "RETRY_PREFIX", "RETRY_HISTORY"})
    void independentlyObservedRequestsRejectScopeInputHistoryAndObservationDrift(String control) throws Exception {
        var mutation = Mutation.valueOf(control);
        var scenario = mutation == Mutation.RETRY_PREFIX || mutation == Mutation.RETRY_HISTORY ? Scenario.RETRY : Scenario.SINGLE;
        var harness = new NativeHarness(temp, scenario, mutation, false);
        harness.run();
        assertTrue(harness.mutated.get(), "negative control must actually reach its corruption point");
        assertTrue(harness.audit.failed()); assertThrows(IOException.class, harness.audit::assertComplete);
        assertNull(harness.audit.currentBinding());
        assertTrue(!harness.auditFailures.isEmpty() || !harness.observerFailures.isEmpty());
        assertTrue(harness.codecFailures.isEmpty(), "negative controls retain valid wire shape");
    }

    @Test void omissionOfTerminalRunEventCannotBeRepairedByAllSuccessfulProviderCalls() throws Exception {
        var harness = new NativeHarness(temp, Scenario.SINGLE, Mutation.MISSING_RUN_EXIT, false);
        assertTrue(harness.run().startsWith("✅")); assertTrue(harness.mutated.get());
        assertEquals(4, harness.audit.snapshot().providerAttempts().size());
        assertTrue(harness.audit.snapshot().providerAttempts().stream().allMatch(p -> p.delivered()));
        assertThrows(IOException.class, harness.audit::assertComplete);
        assertTrue(harness.audit.failed());
    }

    @Test void emptyNativePlannerIsNotInventedAsABudgetFinalization() throws Exception {
        var harness = new NativeHarness(temp, Scenario.PLAN_EMPTY, Mutation.NONE, false);
        harness.run(); harness.assertHealthy();
        assertEquals(1, harness.audit.snapshot().providerAttempts().size());
        assertEquals(List.of(false), harness.budgetRequests);
        assertTrue(harness.audit.snapshot().providerAttempts().get(0).tools().isEmpty());
        assertNotEquals(RunExitReason.COMPLETED, only(harness.events, RunExited.class).reason());
    }

    enum Scenario { PARALLEL, SINGLE, RETRY, REVIEW_ERROR_FIRST, REVIEW_ERROR_RETRY, REJECT_ALL, PLAN_EMPTY }
    enum Mutation { NONE, UNKNOWN_SCOPE, CROSS_ROLE, INPUT_INDEX, INPUT_TEXT, SYSTEM_DRIFT, MISSING_INPUT, MISSING_BATCH,
        RETRY_PREFIX, RETRY_HISTORY, MISSING_RUN_EXIT }

    static final class NativeHarness {
        final TeamRequestAudit audit = new TeamRequestAudit(GOAL);
        final List<Event> events = new CopyOnWriteArrayList<>();
        final List<Throwable> observerFailures = new CopyOnWriteArrayList<>(), codecFailures = new CopyOnWriteArrayList<>(), auditFailures = new CopyOnWriteArrayList<>();
        final List<Boolean> budgetRequests = new CopyOnWriteArrayList<>();
        final ThreadLocal<ActivationIdentity> active = new ThreadLocal<>();
        final AtomicBoolean mutated = new AtomicBoolean();
        final Script script;
        final AgentOrchestrator orchestrator;
        final Mutation mutation;
        final Object hostChannel = new Object();
        final CountDownLatch parallelWorkers = new CountDownLatch(2);
        final Map<String, Integer> activationCalls = new ConcurrentHashMap<>();
        final Map<String, String> rewrittenInputs = new ConcurrentHashMap<>();
        final boolean barrier;
        final TracingLlmClient tracing;

        NativeHarness(Path workspace, Scenario scenario, Mutation mutation, boolean barrier) throws IOException {
            this.mutation = mutation; this.barrier = barrier; this.script = new Script(scenario);
            Files.createDirectories(workspace);
            var registry = new BenchmarkToolRegistry(BenchmarkToolProfile.FILE_ONLY);
            registry.setProjectPath(workspace.toString());
            tracing = new TracingLlmClient(ContextWindowCappedLlmClient.cap(script, 1_000_000, 16_384), workspace.resolve("host-trace.jsonl"), null, audit);
            var client = new LlmClient() {
                @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException { return chat(messages, tools, StreamListener.NO_OP); }
                @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
                    ActivationIdentity identity = active.get();
                    if (identity == null) throw new IOException("native request has no activation");
                    int number = activationCalls.merge(identity.activationId(), 1, Integer::sum);
                    if (barrier && identity.role() == AgentRole.WORKER && !identity.stepId().equals("step_3") && number == 1) await(parallelWorkers);
                    synchronized (hostChannel) {
                        var observedMessages = mutateMessages(identity, number, messages);
                        String scope = "team:" + identity.activationId();
                        if (mutation == Mutation.UNKNOWN_SCOPE && mutated.compareAndSet(false, true)) scope = "team:" + UUID.randomUUID();
                        try {
                            audit.begin(scope, observedMessages, tools);
                            budgetRequests.add(audit.isBudgetFinalizationRequest());
                            audit.dispatched();
                            ChatResponse response;
                            try { response = tracing.chat(observedMessages, tools, listener); }
                            catch (IOException failure) {
                                audit.providerFailure("IOException");
                                throw new IOException("relay provider failure [LLM_API_ERROR]");
                            }
                            audit.returnedResponse(response); audit.response(response); return response;
                        } catch (TeamRequestAudit.Failure failure) {
                            auditFailures.add(failure); throw failure;
                        } finally { audit.end(); }
                    }
                }
                @Override public String getModelName() { return MODEL; }
                @Override public String getProviderName() { return "deepseek"; }
                @Override public int maxContextWindow() { return 1_000_000; }
            };
            orchestrator = orchestrator(client, registry, workspace);
            orchestrator.setExecutionObserver(event -> {
                events.add(event);
                Event forwarded = mutateEvent(event);
                if (forwarded == null) return;
                try {
                    assertEquals(forwarded, TeamObservationWire.decode(forwarded.getClass().getSimpleName(), TeamObservationWire.encode(forwarded)));
                } catch (IOException | RuntimeException | AssertionError failure) { codecFailures.add(failure); }
                try { audit.accept(forwarded); }
                catch (IOException failure) { observerFailures.add(failure); throw new UncheckedIOException(failure); }
                if (event instanceof ActivationEntered entered) active.set(entered.activation());
                if (event instanceof ActivationExited) active.remove();
            });
        }

        String run() { return orchestrator.runExplicitTask(GOAL, GOAL); }
        void assertHealthy() throws IOException {
            assertAll(() -> assertTrue(codecFailures.isEmpty(), "codec failures must not be swallowed by native observer"),
                    () -> assertTrue(observerFailures.isEmpty(), () -> "host event rejection count: " + observerFailures.size()),
                    () -> assertTrue(auditFailures.isEmpty(), () -> "host request rejection count: " + auditFailures.size()),
                    () -> assertEquals(0, orchestrator.getExecutionObservationFailures()),
                    () -> assertFalse(audit.failed()));
            audit.assertComplete(); assertNull(audit.currentBinding());
        }
        private Event mutateEvent(Event event) {
            if (mutation == Mutation.MISSING_RUN_EXIT && event instanceof RunExited && mutated.compareAndSet(false, true)) return null;
            if (mutation == Mutation.MISSING_BATCH && event instanceof ToolBatchReturned && mutated.compareAndSet(false, true)) return null;
            if (event instanceof ActivationEntered entered && entered.activation().role() == AgentRole.WORKER
                    && mutation == Mutation.CROSS_ROLE && mutated.compareAndSet(false, true)) {
                var id = entered.activation();
                return new ActivationEntered(new ActivationIdentity(id.runId(), id.stepId(), id.attempt(), AgentRole.REVIEWER,
                        id.activationId(), id.actorInstanceId(), id.historyGeneration()), entered.elapsedNanos(), entered.threadId(), entered.historyMessagesBefore());
            }
            if (event instanceof ActivationInputPrepared input && input.activation().role() == AgentRole.WORKER) {
                if (mutation == Mutation.MISSING_INPUT && mutated.compareAndSet(false, true)) return null;
                if (mutation == Mutation.INPUT_INDEX && mutated.compareAndSet(false, true))
                    return new ActivationInputPrepared(input.activation(), input.elapsedNanos(), input.userMessageIndex() + 1, input.userText(), input.imagePartCount());
                if (mutation == Mutation.RETRY_PREFIX && input.activation().attempt() == 2 && mutated.compareAndSet(false, true)) {
                    String forged = "总任务上下文：\n被改写的审查反馈\n\n当前任务：WORK";
                    rewrittenInputs.put(input.activation().activationId(), forged);
                    return new ActivationInputPrepared(input.activation(), input.elapsedNanos(), input.userMessageIndex(), TextFingerprint.of(forged), input.imagePartCount());
                }
            }
            return event;
        }
        private List<LlmClient.Message> mutateMessages(ActivationIdentity identity, int number, List<LlmClient.Message> messages) {
            var altered = new ArrayList<>(messages);
            String rewritten = rewrittenInputs.get(identity.activationId());
            if (rewritten != null) altered.set(lastUserIndex(altered), LlmClient.Message.user(rewritten));
            if (identity.role() != AgentRole.WORKER) return altered;
            if (mutation == Mutation.INPUT_TEXT && mutated.compareAndSet(false, true))
                altered.set(lastUserIndex(altered), LlmClient.Message.user("wrong actual worker task"));
            if (mutation == Mutation.SYSTEM_DRIFT && number == 2 && mutated.compareAndSet(false, true))
                altered.set(0, LlmClient.Message.system("different actual worker system"));
            if (mutation == Mutation.RETRY_HISTORY && identity.attempt() == 2 && mutated.compareAndSet(false, true)) {
                for (int i = 1; i < altered.size() - 1; i++) {
                    if ("assistant".equals(altered.get(i).role()) && "WORK-result-1".equals(altered.get(i).content())) {
                        altered.set(i, LlmClient.Message.assistant("forged previous worker result")); break;
                    }
                }
            }
            return altered;
        }
    }

    static AgentOrchestrator orchestrator(LlmClient client, BenchmarkToolRegistry registry, Path workspace) {
        return new AgentOrchestrator(client, registry, new MemoryManager(client, 4_096, 1_000_000,
                new LongTermMemory(workspace.resolve("memory").toFile())), new PrintStream(OutputStream.nullOutputStream()));
    }
    static final class Script implements LlmClient {
        final Scenario scenario;
        final AtomicInteger calls = new AtomicInteger(), reviews = new AtomicInteger();
        final Map<String, Integer> workerAttempts = new ConcurrentHashMap<>();
        final List<List<Message>> requests = new CopyOnWriteArrayList<>();
        final List<List<Tool>> exposures = new CopyOnWriteArrayList<>();
        volatile boolean docConsumedBoth;
        Script(Scenario scenario) { this.scenario = scenario; }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException { return chat(messages, tools, StreamListener.NO_OP); }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            calls.incrementAndGet(); requests.add(List.copyOf(messages)); exposures.add(tools == null ? List.of() : List.copyOf(tools));
            String input = messages.get(lastUserIndex(messages)).content();
            if (input.startsWith("请为以下任务制定执行计划")) return response(scenario == Scenario.PLAN_EMPTY ? "{\"steps\":[]}" : plan(scenario == Scenario.PARALLEL), List.of());
            if (input.startsWith("原始任务：")) {
                int review = reviews.incrementAndGet();
                if (scenario == Scenario.REVIEW_ERROR_FIRST || scenario == Scenario.REVIEW_ERROR_RETRY && review == 2)
                    throw new IOException("PRIVATE_UPSTREAM_ERROR");
                boolean approved = scenario != Scenario.REJECT_ALL && (scenario != Scenario.RETRY || review == 3)
                        && (scenario != Scenario.REVIEW_ERROR_RETRY || review != 1);
                return response("{\"approved\":" + approved + ",\"summary\":\"TEAM_PRIVATE_REVIEW_乙\",\"issues\":[\"检查边界\"]}", List.of());
            }
            String label = input.substring(input.lastIndexOf("当前任务：") + "当前任务：".length()).split("[\r\n]", 2)[0];
            if ("user".equals(messages.get(messages.size() - 1).role())) {
                workerAttempts.merge(label, 1, Integer::sum);
                if (label.equals("DOC")) docConsumedBoth = input.contains(CODE_RESULT) && input.contains(TEST_RESULT);
                String content = label.equals("DOC") ? CODE_RESULT + "\n" + TEST_RESULT : label + " file data";
                return response("TEAM_PRIVATE_TOOL_REASON_丙", List.of(new ToolCall("same-call", new ToolCall.Function("write_file",
                        JSON.writeValueAsString(Map.of("path", label.toLowerCase(java.util.Locale.ROOT) + ".txt", "content", content))))));
            }
            return response(label + "-result-" + workerAttempts.get(label), List.of());
        }
        @Override public String getModelName() { return MODEL; }
        @Override public String getProviderName() { return "deepseek"; }
        @Override public int maxContextWindow() { return 1_000_000; }
    }
    static LlmClient.ChatResponse response(String content, List<LlmClient.ToolCall> tools) {
        return new LlmClient.ChatResponse("assistant", content, "TEAM_PRIVATE_REASONING_丁", tools, 20, 10, 0, MODEL, true);
    }
    static String plan(boolean parallel) {
        return parallel ? "{\"steps\":[{\"id\":\"c\",\"description\":\"CODE\",\"type\":\"FILE_WRITE\",\"dependencies\":[]},"
                + "{\"id\":\"t\",\"description\":\"TEST\",\"type\":\"FILE_WRITE\",\"dependencies\":[]},"
                + "{\"id\":\"d\",\"description\":\"DOC\",\"type\":\"FILE_WRITE\",\"dependencies\":[\"c\",\"t\"]}]}"
                : "{\"steps\":[{\"id\":\"w\",\"description\":\"WORK\",\"type\":\"FILE_WRITE\",\"dependencies\":[]}]}";
    }
    static int lastUserIndex(List<LlmClient.Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) if ("user".equals(messages.get(i).role())) return i;
        throw new IllegalStateException("missing actual user input");
    }
    static <T extends Event> List<T> select(List<Event> events, Class<T> type) { return events.stream().filter(type::isInstance).map(type::cast).toList(); }
    static <T extends Event> T only(List<Event> events, Class<T> type) { var matches = select(events, type); assertEquals(1, matches.size()); return matches.get(0); }
    static StepEntered enter(List<Event> events, String id) { return select(events, StepEntered.class).stream().filter(e -> e.stepId().equals(id)).findFirst().orElseThrow(); }
    static StepExited exit(List<Event> events, String id) { return select(events, StepExited.class).stream().filter(e -> e.stepId().equals(id)).findFirst().orElseThrow(); }
    static void await(CountDownLatch barrier) throws IOException {
        barrier.countDown();
        try { if (!barrier.await(10, TimeUnit.SECONDS)) throw new IOException("offline worker barrier timed out"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("offline worker interrupted", error); }
    }
}
