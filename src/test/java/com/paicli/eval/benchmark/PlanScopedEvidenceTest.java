package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.eval.benchmark.relay.*;
import com.paicli.llm.LlmClient;
import com.paicli.plan.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static com.paicli.plan.PlanExecutionObserver.*;
import static com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol.*;
import static org.junit.jupiter.api.Assertions.*;

class PlanScopedEvidenceTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String GOAL = "先分别读取两份数据，最后合并。";
    static final String GRAPH = """
            {"tasks":[
              {"id":"L","description":"LEFT","type":"FILE_READ","dependencies":[]},
              {"id":"R","description":"RIGHT","type":"FILE_READ","dependencies":[]},
              {"id":"M","description":"MERGE","type":"FILE_WRITE","dependencies":["L","R"]}
            ]}
            """;
    @TempDir Path temp;

    @Test void differentActorPromptsAreBoundAndSingleActorDriftStillFails() throws Exception {
        Fixture f = fixture();
        f.plan(); f.enter(1); f.enter(2);
        f.call(f.scope(1), "left-system", f.input(1));
        f.call(f.scope(2), "right-system", f.input(2));
        f.exit(1); f.exit(2); f.enter(3);
        f.call(f.scope(3), "merge-system", f.input(3)); f.exit(3);
        var metrics = f.tracing.metrics();
        assertTrue(metrics.requestFingerprintComplete()); assertNull(metrics.systemPromptSha256());
        assertEquals(4, metrics.scopedRequestFingerprints().requests().size());
        assertTrue(BenchmarkProviderEvidenceGate.isSatisfied(request("plan"), metrics));
        assertEquals(BenchmarkProviderEvidenceGate.REQUEST_FINGERPRINT_UNPROVEN,
                BenchmarkProviderEvidenceGate.failureType(request("react"), metrics));
        assertEquals(BenchmarkProviderEvidenceGate.REQUEST_FINGERPRINT_UNPROVEN,
                BenchmarkProviderEvidenceGate.failureType(request("team"), metrics));
        var decoded = BenchmarkProtocol.readResponse(BenchmarkProtocol.writeResponse(
                BenchmarkProtocol.WorkerResponse.success("done", metrics)));
        assertEquals(metrics, decoded.metrics());
        assertEquals(textSha256(GRAPH), metrics.scopedRequestFingerprints().requests().get(1).binding().originSha256());
        var snapshot = f.audit.snapshot();
        assertEquals(2, snapshot.schemaVersion());
        assertEquals(0, snapshot.providerTurns().get(0).eventsSeenAtRequest());
        for (var turn : snapshot.providerTurns()) {
            assertTrue(turn.requestStartedNanos() <= turn.responseCompletedNanos());
            if (turn.eventsSeenAtRequest() > 0) assertTrue(snapshot.events().get(turn.eventsSeenAtRequest() - 1)
                    .hostElapsedNanos() <= turn.requestStartedNanos());
            if (turn.eventsSeenAtRequest() < snapshot.events().size()) assertTrue(turn.responseCompletedNanos()
                    <= snapshot.events().get(turn.eventsSeenAtRequest()).hostElapsedNanos());
        }

        Fixture drift = fixture(); drift.plan(); drift.enter(1);
        drift.call(drift.scope(1), "before", drift.input(1));
        drift.call(drift.scope(1), "after", drift.input(1));
        assertFalse(drift.tracing.metrics().requestFingerprintComplete());
        assertEquals(BenchmarkProviderEvidenceGate.REQUEST_FINGERPRINT_UNPROVEN,
                BenchmarkProviderEvidenceGate.failureType(request("PLAN"), drift.tracing.metrics()));
    }

    @Test void reactStillRequiresOneSystemAndDoesNotAcquireScopedEvidence() throws Exception {
        var tracing = new TracingLlmClient(capped(), temp.resolve("react.jsonl"));
        tracing.chat(messages("before", "input"), List.of());
        tracing.chat(messages("after", "input"), List.of());
        assertFalse(tracing.metrics().requestFingerprintComplete());
        assertNull(tracing.metrics().scopedRequestFingerprints());
        assertFalse(JSON.valueToTree(tracing.metrics()).has("scopedRequestFingerprints"));
    }

    @Test void absenceOrLossOfHostBindingCannotBeRepairedBySuccessfulProviderCalls() throws Exception {
        Fixture missing = fixture();
        missing.tracing.chat(messages("system", "input"), List.of());
        assertFalse(missing.tracing.metrics().requestFingerprintComplete());
        Fixture failed = fixture(); failed.plan(); failed.audit.markFailed();
        assertFalse(failed.tracing.metrics().requestFingerprintComplete());
        assertEquals(BenchmarkProviderEvidenceGate.REQUEST_FINGERPRINT_UNPROVEN,
                BenchmarkProviderEvidenceGate.failureType(request("PLAN"), failed.tracing.metrics()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"forged_graph", "duplicate_plan", "unknown_task", "repeat_enter", "repeat_input",
            "early_merge", "unknown_scope", "changed_input", "after_exit", "reused_iteration", "bad_ordinal", "planner_during_task"})
    void hostRejectsUnboundOrImpossibleLifecycle(String control) throws Exception {
        Fixture f = fixture(); f.plan();
        assertThrows(IOException.class, () -> {
            switch (control) {
                case "forged_graph" -> f.audit.accept(new PlanStarted(UUID.randomUUID().toString(), 1, "forged",
                        TextFingerprint.of(GOAL), List.of(new TaskNode("task_1", Task.TaskType.FILE_READ,
                        TextFingerprint.of("forged"), List.of())), List.of("task_1")));
                case "duplicate_plan" -> f.audit.accept(f.started());
                case "unknown_task" -> f.audit.accept(new TaskEntered(f.id, 1, "task_99", 1));
                case "repeat_enter" -> { f.enter(1); f.audit.accept(new TaskEntered(f.id, 2, "task_1", 1)); }
                case "repeat_input" -> { f.enter(1); f.audit.accept(f.prepared(1)); }
                case "early_merge" -> f.enter(3);
                case "unknown_scope" -> f.audit.begin(f.id + ":unknown", messages("sys", "in"), List.of());
                case "changed_input" -> { f.enter(1); f.audit.begin(f.scope(1), messages("sys", "changed"), List.of()); }
                case "after_exit" -> { f.enter(1); f.exit(1); f.audit.begin(f.scope(1), messages("sys", f.input(1)), List.of()); }
                case "reused_iteration" -> { f.enter(1); f.audit.accept(f.batch(0)); f.audit.accept(f.batch(0)); }
                case "bad_ordinal" -> { f.enter(1); f.audit.accept(f.batch(1)); }
                case "planner_during_task" -> { f.enter(1); f.audit.begin("planner", messages("sys", "请为以下任务制定执行计划：\n" + GOAL), List.of()); }
                default -> throw new AssertionError(control);
            }
        });
        assertTrue(f.audit.failed());
        assertFalse(f.tracing.metrics().requestFingerprintComplete());
    }

    @Test void graphMustMatchTheActualProviderResponseNotJustBeWellFormed() throws Exception {
        Fixture f = fixture();
        f.call("planner", "planner-system", "请为以下任务制定执行计划：\n" + GOAL);
        PlanStarted p = f.started();
        var changed = new java.util.ArrayList<>(p.tasks());
        changed.set(1, new TaskNode("task_2", Task.TaskType.FILE_READ, TextFingerprint.of("RIGHT"), List.of("task_1")));
        assertThrows(IOException.class, () -> f.audit.accept(new PlanStarted(p.executionId(), p.elapsedNanos(), p.planId(), p.goal(), changed, p.executionOrder())));
    }

    @Test void wireRoundTripRejectsMissingFieldsCoercionAndTampering() throws Exception {
        Fixture f = fixture();
        var event = f.started();
        var frame = new PlanEvent(worker(FrameType.PLAN_EVENT, "event-1"), "PlanStarted", PlanObservationWire.encode(event));
        assertEquals(frame, decode(encode(frame)));
        List<Consumer<ObjectNode>> mutations = List.of(
                n -> n.remove("elapsedNanos"), n -> n.put("elapsedNanos", "1"), n -> n.put("elapsedNanos", -1),
                n -> n.put("executionId", "not-a-uuid"), n -> n.put("extra", true),
                n -> ((ObjectNode)n.path("goal")).remove("present"),
                n -> ((ObjectNode)n.path("goal")).put("sha256", "bad"),
                n -> n.set("tasks", JSON.createArrayNode()));
        for (var mutation : mutations) {
            ObjectNode changed = (ObjectNode) PlanObservationWire.encode(event); mutation.accept(changed);
            assertThrows(IOException.class, () -> PlanObservationWire.decode("PlanStarted", changed));
        }
        assertThrows(IOException.class, () -> PlanObservationWire.decode("java.lang.Runtime", JSON.createObjectNode()));
        ObjectNode encoded = (ObjectNode) JSON.readTree(encode(frame));
        encoded.with("payload").put("eventType", 1);
        assertThrows(IOException.class, () -> decode(JSON.writeValueAsBytes(encoded)));
    }

    @Test void protocolSeparatesPlanAndReactAndAcknowledgesEachEventExactlyOnce() throws Exception {
        Fixture f = fixture();
        var start = session(AgentMode.PLAN); var validator = new Validator();
        validator.accept(start); validator.accept(new WorkerReady(worker(FrameType.WORKER_READY, ""), start.capabilities()));
        var event = new PlanEvent(worker(FrameType.PLAN_EVENT, "event-1"), "PlanStarted", PlanObservationWire.encode(f.started()));
        var ack = new PlanEventAck(new Header(Direction.COORDINATOR_TO_WORKER, FrameType.PLAN_EVENT_ACK, "event-1", 1));
        var chat = new ChatRequest(worker(FrameType.CHAT_REQUEST, "call-1"), List.of(new WireMessage("user", "input", null, List.of(), null)), List.of(), "planner");
        assertThrows(IllegalStateException.class, () -> validator.accept(new ChatRequest(chat.header(), chat.messages(), chat.tools())));
        validator.accept(event);
        assertThrows(IllegalStateException.class, () -> validator.accept(chat));
        assertThrows(IllegalStateException.class, () -> validator.accept(new PlanEventAck(new Header(Direction.COORDINATOR_TO_WORKER, FrameType.PLAN_EVENT_ACK, "wrong", 1))));
        validator.accept(ack);
        assertThrows(IllegalStateException.class, () -> validator.accept(event));
        validator.accept(chat);
        assertThrows(IllegalStateException.class, () -> validator.accept(event));
        var react = new Validator(); var r = session(AgentMode.REACT);
        react.accept(r); react.accept(new WorkerReady(worker(FrameType.WORKER_READY, ""), r.capabilities()));
        assertThrows(IllegalStateException.class, () -> react.accept(event));
        assertThrows(IllegalStateException.class, () -> react.accept(chat));
    }

    @Test void scopedProofRejectsMissingReorderedAndOriginDriftingRequests() {
        String h = "a".repeat(64), other = "b".repeat(64);
        var b = new ScopedRequestFingerprints.Binding("planner", h, h);
        var r = new ScopedRequestFingerprints.Request(1, b, h, h);
        assertFalse(new ScopedRequestFingerprints(1, "PLAN", List.of(r)).completeFor(2));
        assertFalse(new ScopedRequestFingerprints(1, "PLAN", List.of(r, r)).completeFor(2));
        assertFalse(new ScopedRequestFingerprints(1, "PLAN", List.of(r, new ScopedRequestFingerprints.Request(2,
                new ScopedRequestFingerprints.Binding("planner", other, h), h, h))).completeFor(2));
    }

    private Fixture fixture() throws IOException { return new Fixture(); }
    private final class Fixture {
        final String id = UUID.randomUUID().toString();
        final PlanRequestAudit audit = new PlanRequestAudit(GOAL);
        final TracingLlmClient tracing = new TracingLlmClient(capped(), temp.resolve(id + ".jsonl"), audit);
        Fixture() throws IOException {}
        String scope(int n) { return id + ":task_" + n; }
        String input(int n) { return "actual-input-" + n; }
        void call(String scope, String system, String input) throws IOException {
            var messages = messages(system, input); audit.begin(scope, messages, List.of());
            try { audit.response(tracing.chat(messages, List.of())); } finally { audit.end(); }
        }
        void plan() throws IOException {
            call("planner", "planner-system", "请为以下任务制定执行计划：\n" + GOAL);
            audit.accept(started());
        }
        PlanStarted started() {
            return new PlanStarted(id, 1, "plan-1", TextFingerprint.of(GOAL), List.of(
                    new TaskNode("task_1", Task.TaskType.FILE_READ, TextFingerprint.of("LEFT"), List.of()),
                    new TaskNode("task_2", Task.TaskType.FILE_READ, TextFingerprint.of("RIGHT"), List.of()),
                    new TaskNode("task_3", Task.TaskType.FILE_WRITE, TextFingerprint.of("MERGE"), List.of("task_1", "task_2"))),
                    List.of("task_1", "task_2", "task_3"));
        }
        void enter(int n) throws IOException { audit.accept(new TaskEntered(id, 2, "task_" + n, n)); audit.accept(prepared(n)); }
        TaskInputPrepared prepared(int n) {
            return new TaskInputPrepared(id, 3, "task_" + n, TextFingerprint.of(input(n)), 0,
                    n == 3 ? List.of(new DependencyInput("task_1", Task.TaskStatus.COMPLETED, TextFingerprint.of("result")),
                            new DependencyInput("task_2", Task.TaskStatus.COMPLETED, TextFingerprint.of("result"))) : List.of());
        }
        void exit(int n) throws IOException { audit.accept(new TaskExited(id, 4, "task_" + n, ExitKind.RETURNED, TextFingerprint.of("result"), null)); }
        ToolBatchReturned batch(int ordinal) {
            return new ToolBatchReturned(id, 4, "task_1", 1, List.of(new ToolResult(ordinal, "shared", "read_file",
                    TextFingerprint.of("{}"), TextFingerprint.of("result"), true, false, 0)));
        }
    }
    private static List<LlmClient.Message> messages(String system, String user) {
        return List.of(LlmClient.Message.system(system), LlmClient.Message.user(user));
    }
    private static LlmClient capped() {
        return ContextWindowCappedLlmClient.cap(new LlmClient() {
            public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) { return chat(messages, tools); }
            public ChatResponse chat(List<Message> messages, List<Tool> tools) {
                String content = messages.get(1).content().startsWith("请为以下任务制定执行计划") ? GRAPH : "result";
                return new ChatResponse("assistant", content, null, List.of(), 100, 30, 0, getModelName(), true);
            }
            public String getModelName() { return "deepseek-v4-flash"; }
            public String getProviderName() { return "deepseek"; }
            public int maxContextWindow() { return 1_000_000; }
        }, 1_000_000, 16_384);
    }
    private static BenchmarkProtocol.WorkerRequest request(String mode) {
        return new BenchmarkProtocol.WorkerRequest(BenchmarkProtocol.VERSION, "deepseek", "deepseek-v4-flash", null,
                "synthetic", mode, BenchmarkToolProfile.FILE_ONLY,
                new BenchmarkProtocol.AgentLimits(100_000, 32, 8, 1_000_000, 16_384), "2026-09-04", GOAL,
                "/tmp/workspace", "/tmp/home", "/tmp/episode");
    }
    private static Header worker(FrameType type, String id) { return new Header(Direction.WORKER_TO_COORDINATOR, type, id, 0); }
    private static SessionStart session(AgentMode mode) {
        return new SessionStart(new Header(Direction.COORDINATOR_TO_WORKER, FrameType.SESSION_START, "", 0),
                "plan-test", "deepseek", "deepseek-v4-flash", mode, ToolProfile.FILE_ONLY, GOAL, "2026-09-04", "UTC",
                System.currentTimeMillis() + 60_000, new AgentLimits(100_000, 32, 8, 1_000_000, 16_384),
                BenchmarkProviderRelay.capabilitiesOf(capped()),
                new Limits(BenchmarkFramedChannel.MAX_FRAME_BYTES, BenchmarkFramedChannel.MAX_SESSION_BYTES, 512, 128));
    }
}
