package com.paicli.eval.benchmark.relay;

import com.paicli.agent.AgentRole;
import com.paicli.agent.TeamExecutionObserver;
import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.paicli.agent.TeamExecutionObserver.TextFingerprint;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TEAM relay protocol semantics over real frames: exact audit binding, acknowledged
 * event/scope correlation, budget denial with preserved attempts, and observation
 * failures that can never complete as clean evidence. No model scores here.
 */
class TeamRelayProtocolTest {
    static final String GOAL = "relay protocol frozen goal PHASE3_PROTOCOL_PRIVATE_甲";
    static final String USER_TEXT = "总任务上下文：\n\n当前任务：demo";

    @Test
    void teamModeRequiresExactExclusiveHostAudit() throws Exception {
        FakeDelegate delegate = new FakeDelegate(false);
        try (Harness harness = new Harness(1)) {
            var session = start(delegate, 120_000);
            IOException missing = assertThrows(IOException.class,
                    () -> BenchmarkProviderRelay.connect(harness.pair.coordinator(), session, delegate,
                            null, null, null, null, null, null));
            assertEquals("Team mode requires exact host audit binding", missing.getMessage());

            TeamRequestAudit audit = new TeamRequestAudit(GOAL);
            IOException shared = assertThrows(IOException.class,
                    () -> BenchmarkProviderRelay.connect(harness.pair.coordinator(), session, delegate,
                            message -> null, null, null, null, null, audit));
            assertEquals("Team audit requires an exclusive static tool channel", shared.getMessage());

            var planSession = new BenchmarkRelayProtocol.SessionStart(
                    session.header(), session.sessionId(), delegate.getProviderName(), delegate.getModelName(),
                    BenchmarkRelayProtocol.AgentMode.PLAN, session.toolProfile(), GOAL,
                    session.runtimeDate(), session.runtimeZone(), session.deadlineEpochMillis(),
                    session.agentLimits(), session.capabilities(), session.limits());
            IOException planConflicting = assertThrows(IOException.class,
                    () -> BenchmarkProviderRelay.connect(harness.pair.coordinator(), planSession, delegate,
                            null, null, null, null, null, audit));
            assertEquals("Team mode requires exact host audit binding", planConflicting.getMessage(),
                    "the exact-binding check fires before the per-mode checks");
            assertEquals(0, delegate.calls.get(), "rejected bindings never reach the provider");
        }
    }

    @Test
    void teamEventsAcknowledgeBindActivationScopeAndServeAttributedChat() throws Exception {
        FakeDelegate delegate = new FakeDelegate(false);
        try (Harness harness = new Harness(2)) {
            TeamRequestAudit audit = new TeamRequestAudit(GOAL);
            Future<BenchmarkProviderRelay> connecting = harness.pool.submit(() ->
                    BenchmarkProviderRelay.connect(harness.pair.coordinator(), start(delegate, 120_000),
                            delegate, null, null, null, null, null, audit));
            RelayLlmClient worker = RelayLlmClient.accept(harness.pair.worker());
            worker.setTeamObservationFailureSupplier(() -> 0L);
            BenchmarkProviderRelay provider = connecting.get();

            String runId = UUID.randomUUID().toString();
            String activationId = UUID.randomUUID().toString();
            var identity = new TeamExecutionObserver.ActivationIdentity(
                    runId, "step_1", 1, AgentRole.WORKER, activationId, UUID.randomUUID().toString(), 0);

            Future<BenchmarkProviderRelay.ServeResult> serving = harness.pool.submit(provider::serveNext);
            worker.observeTeam(new TeamExecutionObserver.RunStarted(runId, 1,
                    TextFingerprint.of(GOAL), TextFingerprint.of(GOAL), true, 1, 2));
            assertEquals(BenchmarkProviderRelay.ServeResult.TEAM_EVENT_SERVED, serving.get());

            serving = harness.pool.submit(provider::serveNext);
            worker.observeTeam(new TeamExecutionObserver.ActivationEntered(
                    identity, 2, Thread.currentThread().getId(), 2));
            assertEquals(BenchmarkProviderRelay.ServeResult.TEAM_EVENT_SERVED, serving.get());

            serving = harness.pool.submit(provider::serveNext);
            worker.observeTeam(new TeamExecutionObserver.ActivationInputPrepared(
                    identity, 3, 1, TextFingerprint.of(USER_TEXT), 0));
            assertEquals(BenchmarkProviderRelay.ServeResult.TEAM_EVENT_SERVED, serving.get());

            serving = harness.pool.submit(provider::serveNext);
            LlmClient.ChatResponse response = worker.chat(
                    List.of(LlmClient.Message.system("sys"), LlmClient.Message.user(USER_TEXT)),
                    List.of(new LlmClient.Tool("read_file", "read",
                            new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("type", "object"))),
                    LlmClient.StreamListener.NO_OP);
            assertEquals(BenchmarkProviderRelay.ServeResult.CHAT_SERVED, serving.get());
            assertEquals("answer-1", response.content());
            assertEquals(1, delegate.calls.get());

            assertFalse(audit.failed());
            var snapshot = audit.snapshot();
            assertEquals(1, snapshot.schemaVersion());
            assertEquals("TEAM", snapshot.mode());
            assertEquals(1, snapshot.providerAttempts().size());
            var attempt = snapshot.providerAttempts().get(0);
            String scope = "team:" + activationId;
            assertEquals(scope, attempt.scope());
            assertEquals(scope, attempt.binding().scope());
            assertTrue(attempt.providerDispatched());
            assertTrue(attempt.delivered());
            assertEquals(attempt.returnedResponse(), attempt.response());
            assertEquals(identity, attempt.activation(), "team activation identity is preserved");
            assertTrue(attempt.tools().stream().anyMatch(tool -> "read_file".equals(tool.name())));
            assertEquals(0, snapshot.events().stream().filter(event -> "ChatRequest".equals(event.eventType())).count(),
                    "chat frames are not observation events");
        }
    }

    @Test
    void chatWithoutAcknowledgedActivationIsRejectedBeforeTheProvider() throws Exception {
        FakeDelegate delegate = new FakeDelegate(false);
        try (Harness harness = new Harness(1)) {
            TeamRequestAudit audit = new TeamRequestAudit(GOAL);
            Future<BenchmarkProviderRelay> connecting = harness.pool.submit(() ->
                    BenchmarkProviderRelay.connect(harness.pair.coordinator(), start(delegate, 120_000),
                            delegate, null, null, null, null, null, audit));
            RelayLlmClient worker = RelayLlmClient.accept(harness.pair.worker());
            worker.setTeamObservationFailureSupplier(() -> 0L);
            connecting.get();

            IOException error = assertThrows(IOException.class, () -> worker.chat(
                    List.of(LlmClient.Message.system("sys"), LlmClient.Message.user(USER_TEXT)),
                    List.of(), LlmClient.StreamListener.NO_OP));
            assertTrue(error.getMessage().contains("no acknowledged text-only activation"),
                    "unexpected error: " + error.getMessage());
            assertEquals(0, delegate.calls.get());
            assertFalse(audit.failed(), "a locally rejected request never reached the host audit");
        }
    }

    @Test
    void hostBudgetDenialRecordsTheAttemptAndNeverCallsTheProviderTwice() throws Exception {
        FakeDelegate delegate = new FakeDelegate(false);
        try (Harness harness = new Harness(2)) {
            TeamRequestAudit audit = new TeamRequestAudit(GOAL);
            Future<BenchmarkProviderRelay> connecting = harness.pool.submit(() ->
                    BenchmarkProviderRelay.connect(harness.pair.coordinator(),
                            start(delegate, 120_000, new BenchmarkRelayProtocol.AgentLimits(
                                    5, 10, 3, 1_000_000, 16_384)),
                            delegate, null, null, null, null, null, audit));
            RelayLlmClient worker = RelayLlmClient.accept(harness.pair.worker());
            worker.setTeamObservationFailureSupplier(() -> 0L);
            BenchmarkProviderRelay provider = connecting.get();

            String runId = UUID.randomUUID().toString();
            var identity = identity(runId);
            serveEvent(harness, provider, () -> worker.observeTeam(new TeamExecutionObserver.RunStarted(
                    runId, 1, TextFingerprint.of(GOAL), TextFingerprint.of(GOAL), true, 1, 2)));
            serveEvent(harness, provider, () -> worker.observeTeam(new TeamExecutionObserver.ActivationEntered(
                    identity, 2, Thread.currentThread().getId(), 2)));
            serveEvent(harness, provider, () -> worker.observeTeam(new TeamExecutionObserver.ActivationInputPrepared(
                    identity, 3, 1, TextFingerprint.of(USER_TEXT), 0)));

            Future<BenchmarkProviderRelay.ServeResult> first = harness.pool.submit(provider::serveNext);
            worker.chat(List.of(LlmClient.Message.system("sys"), LlmClient.Message.user(USER_TEXT)),
                    List.of(), LlmClient.StreamListener.NO_OP);
            assertEquals(BenchmarkProviderRelay.ServeResult.CHAT_SERVED, first.get());

            Future<BenchmarkProviderRelay.ServeResult> second = harness.pool.submit(provider::serveNext);
            IOException denied = assertThrows(IOException.class, () -> worker.chat(
                    List.of(LlmClient.Message.system("sys"), LlmClient.Message.user(USER_TEXT)),
                    List.of(), LlmClient.StreamListener.NO_OP));
            assertEquals(BenchmarkProviderRelay.ServeResult.CHAT_SERVED, second.get());
            assertTrue(denied.getMessage().contains("EPISODE_BUDGET_EXHAUSTED"),
                    "unexpected error: " + denied.getMessage());
            assertEquals(1, delegate.calls.get(), "a denied budget request never reaches the provider");

            var attempts = audit.snapshot().providerAttempts();
            assertEquals(2, attempts.size(), "the denied request stays recorded");
            var deniedAttempt = attempts.get(1);
            assertFalse(deniedAttempt.providerDispatched());
            assertFalse(deniedAttempt.delivered());
            assertEquals("EPISODE_BUDGET_EXHAUSTED", deniedAttempt.failureType());
        }
    }

    @Test
    void onlyAnObservedBudgetFinalizationUnlocksTheOneToolFreeClosingCall() throws Exception {
        FakeDelegate delegate = new FakeDelegate(false);
        try (Harness harness = new Harness(2)) {
            TeamRequestAudit audit = new TeamRequestAudit(GOAL);
            Future<BenchmarkProviderRelay> connecting = harness.pool.submit(() ->
                    BenchmarkProviderRelay.connect(harness.pair.coordinator(),
                            start(delegate, 120_000, new BenchmarkRelayProtocol.AgentLimits(
                                    15, 10, 3, 1_000_000, 16_384)),
                            delegate, null, null, null, null, null, audit));
            RelayLlmClient worker = RelayLlmClient.accept(harness.pair.worker());
            worker.setTeamObservationFailureSupplier(() -> 0L);
            BenchmarkProviderRelay provider = connecting.get();

            String runId = UUID.randomUUID().toString();
            var identity = identity(runId);
            serveEvent(harness, provider, () -> worker.observeTeam(new TeamExecutionObserver.RunStarted(
                    runId, 1, TextFingerprint.of(GOAL), TextFingerprint.of(GOAL), true, 1, 2)));
            serveEvent(harness, provider, () -> worker.observeTeam(new TeamExecutionObserver.ActivationEntered(
                    identity, 2, Thread.currentThread().getId(), 2)));
            serveEvent(harness, provider, () -> worker.observeTeam(new TeamExecutionObserver.ActivationInputPrepared(
                    identity, 3, 1, TextFingerprint.of(USER_TEXT), 0)));

            // Normal request consumes the whole episode budget.
            Future<BenchmarkProviderRelay.ServeResult> first = harness.pool.submit(provider::serveNext);
            worker.chat(List.of(LlmClient.Message.system("sys"), LlmClient.Message.user(USER_TEXT)),
                    List.of(), LlmClient.StreamListener.NO_OP);
            assertEquals(BenchmarkProviderRelay.ServeResult.CHAT_SERVED, first.get());

            // The product observes its own budget finalization; the empty-tools closing call is permitted once.
            serveEvent(harness, provider, () -> worker.observeTeam(new TeamExecutionObserver.BudgetFinalization(
                    identity, 4, 1, "TOKEN_BUDGET_EXCEEDED")));
            Future<BenchmarkProviderRelay.ServeResult> closing = harness.pool.submit(provider::serveNext);
            worker.chat(List.of(LlmClient.Message.system("sys"), LlmClient.Message.user(USER_TEXT)),
                    List.of(), LlmClient.StreamListener.NO_OP);
            assertEquals(BenchmarkProviderRelay.ServeResult.CHAT_SERVED, closing.get());
            assertEquals(2, delegate.calls.get(), "the closing call reaches the provider");

            // A second closing-style call is denied: emptiness alone is never a permit.
            Future<BenchmarkProviderRelay.ServeResult> second = harness.pool.submit(provider::serveNext);
            IOException denied = assertThrows(IOException.class, () -> worker.chat(
                    List.of(LlmClient.Message.system("sys"), LlmClient.Message.user(USER_TEXT)),
                    List.of(), LlmClient.StreamListener.NO_OP));
            assertEquals(BenchmarkProviderRelay.ServeResult.CHAT_SERVED, second.get());
            assertTrue(denied.getMessage().contains("EPISODE_BUDGET_EXHAUSTED"));
            assertEquals(2, delegate.calls.get());

            assertFalse(audit.failed());
            var attempts = audit.snapshot().providerAttempts();
            assertEquals(3, attempts.size());
            assertTrue(attempts.get(1).delivered(), "the permitted closing call was served");
            assertFalse(attempts.get(2).providerDispatched());
            assertEquals("EPISODE_BUDGET_EXHAUSTED", attempts.get(2).failureType());
        }
    }

    @Test
    void teamObservationFailureCanOnlyExitAsUnprovenEvidence() throws Exception {
        FakeDelegate delegate = new FakeDelegate(false);
        try (Harness harness = new Harness(2)) {
            TeamRequestAudit audit = new TeamRequestAudit(GOAL);
            Future<BenchmarkProviderRelay> connecting = harness.pool.submit(() ->
                    BenchmarkProviderRelay.connect(harness.pair.coordinator(), start(delegate, 120_000),
                            delegate, null, null, null, null, null, audit));
            RelayLlmClient worker = RelayLlmClient.accept(harness.pair.worker());
            BenchmarkProviderRelay provider = connecting.get();
            worker.setTeamObservationFailureSupplier(() -> 7L);

            Future<BenchmarkProviderRelay.ServeResult> terminal = harness.pool.submit(provider::serveNext);
            worker.complete("answer", List.of());

            assertEquals(BenchmarkProviderRelay.ServeResult.WORKER_FAILURE, terminal.get());
            var failure = assertInstanceOf(BenchmarkRelayProtocol.WorkerFailure.class, provider.terminalFrame());
            assertEquals("REQUEST_FINGERPRINT_UNPROVEN", failure.errorType());
            assertTrue(audit.failed(), "an unproven worker failure must fail the host audit too");
        }
    }

    private static void serveEvent(Harness harness, BenchmarkProviderRelay provider, Runnable observe) throws Exception {
        Future<BenchmarkProviderRelay.ServeResult> serving = harness.pool.submit(provider::serveNext);
        observe.run();
        assertEquals(BenchmarkProviderRelay.ServeResult.TEAM_EVENT_SERVED, serving.get(30, java.util.concurrent.TimeUnit.SECONDS));
    }

    private static TeamExecutionObserver.ActivationIdentity identity(String runId) {
        return new TeamExecutionObserver.ActivationIdentity(runId, "step_1", 1, AgentRole.WORKER,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), 0);
    }

    private static BenchmarkRelayProtocol.SessionStart start(FakeDelegate delegate, long deadlineMillis) {
        return start(delegate, deadlineMillis,
                new BenchmarkRelayProtocol.AgentLimits(100_000, 100, 3, 1_000_000, 16_384));
    }

    private static BenchmarkRelayProtocol.SessionStart start(FakeDelegate delegate, long deadlineMillis,
                                                             BenchmarkRelayProtocol.AgentLimits limits) {
        return new BenchmarkRelayProtocol.SessionStart(
                new BenchmarkRelayProtocol.Header(
                        BenchmarkRelayProtocol.Direction.COORDINATOR_TO_WORKER,
                        BenchmarkRelayProtocol.FrameType.SESSION_START, "", 0),
                "session-team-relay", delegate.getProviderName(), delegate.getModelName(),
                BenchmarkRelayProtocol.AgentMode.TEAM, BenchmarkRelayProtocol.ToolProfile.FILE_ONLY,
                GOAL, "2026-09-07", "UTC", System.currentTimeMillis() + deadlineMillis,
                limits, BenchmarkProviderRelay.capabilitiesOf(delegate),
                new BenchmarkRelayProtocol.Limits(
                        BenchmarkFramedChannel.MAX_FRAME_BYTES,
                        BenchmarkFramedChannel.MAX_SESSION_BYTES,
                        BenchmarkRelayProtocol.MAX_MESSAGES,
                        BenchmarkRelayProtocol.MAX_TOOLS));
    }

    /** Deterministic provider stand-in; never a model score. */
    private static final class FakeDelegate implements LlmClient {
        private final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        private final boolean fail;

        private FakeDelegate(boolean fail) { this.fail = fail; }

        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                           StreamListener listener) throws IOException {
            int call = calls.incrementAndGet();
            if (fail) throw new IOException("provider failure");
            return new ChatResponse("assistant", "answer-" + call, "reasoning-" + call, List.of(),
                    10, 5, 4, getModelName(), true);
        }

        @Override public String getModelName() { return "deepseek-v4-flash"; }
        @Override public String getProviderName() { return "deepseek"; }
        @Override public int maxContextWindow() { return 1_000_000; }
        @Override public boolean supportsTools() { return true; }
        @Override public boolean supportsImageInput() { return false; }
    }

    private static final class Duplex implements AutoCloseable {
        private final java.io.PipedInputStream workerInput = new java.io.PipedInputStream(1 << 20);
        private final java.io.PipedInputStream coordinatorInput = new java.io.PipedInputStream(1 << 20);
        private final java.io.PipedOutputStream coordinatorOutput;
        private final java.io.PipedOutputStream workerOutput;
        private final BenchmarkFramedChannel coordinator;
        private final BenchmarkFramedChannel worker;

        private Duplex() throws IOException {
            coordinatorOutput = new java.io.PipedOutputStream(workerInput);
            workerOutput = new java.io.PipedOutputStream(coordinatorInput);
            coordinator = new BenchmarkFramedChannel(coordinatorInput, coordinatorOutput);
            worker = new BenchmarkFramedChannel(workerInput, workerOutput);
        }

        private BenchmarkFramedChannel coordinator() { return coordinator; }
        private BenchmarkFramedChannel worker() { return worker; }

        @Override public void close() throws IOException {
            coordinatorOutput.close();
            workerOutput.close();
            coordinatorInput.close();
            workerInput.close();
        }
    }

    private static final class Harness implements AutoCloseable {
        private final Duplex pair;
        private final ExecutorService pool;

        private Harness(int threads) throws IOException {
            pair = new Duplex();
            pool = Executors.newFixedThreadPool(threads);
        }

        @Override public void close() throws IOException {
            pool.shutdownNow();
            pair.close();
        }
    }
}
