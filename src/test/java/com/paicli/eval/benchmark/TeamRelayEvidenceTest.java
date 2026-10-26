package com.paicli.eval.benchmark;

import com.paicli.agent.AgentOrchestrator;
import com.paicli.agent.TeamExecutionObserver;
import com.paicli.eval.benchmark.relay.BenchmarkFramedChannel;
import com.paicli.eval.benchmark.relay.BenchmarkProviderRelay;
import com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol;
import com.paicli.eval.benchmark.relay.RelayLlmClient;
import com.paicli.eval.benchmark.relay.TeamRequestAudit;
import com.paicli.llm.LlmClient;
import com.paicli.memory.AutoCompactionManager;
import com.paicli.memory.MemoryManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A full native TEAM episode over real relay frames: the production worker wiring
 * (orchestrator → RelayLlmClient → observeTeam) against the production host wiring
 * (BenchmarkProviderRelay → TeamRequestAudit + TracingLlmClient). Proves request
 * attribution and closed evidence survive the wire. Script providers only, never
 * model scores or admission.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class TeamRelayEvidenceTest {
    static final String GOAL = "离线完成 WORK 步骤并写入 notes.txt；PHASE3_E2_PRIVATE_甲";
    static final String MODEL = "deepseek-v4-flash";

    private String priorSessionMemory;

    @BeforeEach void noAsynchronousSummaryRequests() {
        priorSessionMemory = System.getProperty(AutoCompactionManager.SESSION_MEMORY_PROPERTY);
        System.setProperty(AutoCompactionManager.SESSION_MEMORY_PROPERTY, "false");
    }

    @AfterEach void restoreConfiguration() {
        if (priorSessionMemory == null) System.clearProperty(AutoCompactionManager.SESSION_MEMORY_PROPERTY);
        else System.setProperty(AutoCompactionManager.SESSION_MEMORY_PROPERTY, priorSessionMemory);
    }

    @Test
    void nativeTeamEpisodeLeavesClosedAttributedHostEvidenceThroughRealFrames(
            @TempDir Path workspace) throws Exception {
        Script script = new Script();
        try (Pool pool = new Pool(3); Host host = new Host(script, workspace, pool.pool(), 120_000)) {

            Future<String> run = pool.pool().submit(() -> {
                String answer;
                try { answer = host.worker().runExplicitTask(GOAL, GOAL); }
                finally { host.relay().verifyTeamObservationFailures(host.worker().getExecutionObservationFailures()); }
                // The trusted worker always sends one terminal frame; a missing one is evidence failure.
                host.relay().complete(answer, List.of());
                return answer;
            });
            Future<BenchmarkProviderRelay.ServeResult> serving = pool.pool().submit(host::serveUntilTerminal);

            assertTrue(run.get(60, TimeUnit.SECONDS).startsWith("✅"), "native run must complete");
            assertEquals(BenchmarkProviderRelay.ServeResult.WORKER_COMPLETE, serving.get(60, TimeUnit.SECONDS));
            assertEquals(0, host.worker().getExecutionObservationFailures());
            host.audit().assertComplete();
            assertFalse(host.audit().failed());

            var snapshot = host.audit().snapshot();
            assertEquals(1, snapshot.schemaVersion());
            assertEquals("TEAM", snapshot.mode());
            assertEquals(script.calls.get(), snapshot.providerAttempts().size(),
                    "every provider call is attributed exactly once");
            assertTrue(snapshot.providerAttempts().stream().allMatch(attempt ->
                    attempt.delivered() && attempt.providerDispatched() && attempt.failureType() == null));
            assertTrue(snapshot.providerAttempts().stream()
                    .filter(attempt -> attempt.activation().role() == com.paicli.agent.AgentRole.PLANNER)
                    .allMatch(attempt -> attempt.tools().isEmpty()));
            assertEquals(3, snapshot.providerAttempts().stream().map(attempt -> attempt.scope()).distinct().count(),
                    "planner, worker and reviewer are distinct activation scopes");
            String evidence = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(snapshot);
            assertTrue(evidence.contains("PHASE3_E2_PRIVATE_甲"),
                    "private host audit retains actual request bodies");

            // Write attribution: the single write_file belongs to the worker activation; planner/reviewer write nothing.
            assertEquals(1, snapshot.writeAttributions().size());
            var write = snapshot.writeAttributions().get(0);
            assertEquals("notes.txt", write.path());
            assertEquals(com.paicli.agent.AgentRole.WORKER, write.role());
            assertEquals("step_1", write.stepId());
            assertEquals(1, write.attempt());
            assertTrue(write.successful());
            assertTrue(snapshot.writeConflicts().isEmpty(),
                    "one worker writing one path is not a conflict");
            assertEquals(write.scope(), snapshot.providerAttempts().stream()
                    .filter(attempt -> attempt.activation().role() == com.paicli.agent.AgentRole.WORKER)
                    .findFirst().orElseThrow().scope(),
                    "the write is attributed to the activation that issued the call");

            TracingLlmClient.Metrics metrics = host.tracing().metrics();
            assertTrue(metrics.requestFingerprintComplete());
            assertTrue(metrics.usageComplete());
            assertTrue(metrics.contextCapSatisfied());
            assertEquals(2, metrics.scopedRequestFingerprints().schemaVersion());
            assertEquals("TEAM", metrics.scopedRequestFingerprints().mode());
            assertTrue(metrics.scopedRequestFingerprints().completeFor(script.calls.get()));
        }
    }

    @Test
    void driftedObservationBreaksTheEpisodeInsteadOfYieldingScores(@TempDir Path workspace) throws Exception {
        Script script = new Script();
        try (Pool pool = new Pool(3); Host host = new Host(script, workspace, pool.pool(), 10_000)) {
            host.dropNextActivationInput();

            Future<String> run = pool.pool().submit(() -> {
                try { return host.worker().runExplicitTask(GOAL, GOAL); }
                finally { host.relay().verifyTeamObservationFailures(host.worker().getExecutionObservationFailures()); }
            });
            Future<BenchmarkProviderRelay.ServeResult> serving = pool.pool().submit(host::serveUntilTerminal);

            var rejected = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> serving.get(30, TimeUnit.SECONDS));
            assertInstanceOf(TeamRequestAudit.Failure.class, rejected.getCause(),
                    "the host must reject the drifted request instead of serving it");
            assertTrue(host.audit().failed());

            host.breakWorkerDownstream();
            assertFalse(run.get(30, TimeUnit.SECONDS).startsWith("✅"),
                    "a corrupted episode must not report a clean product completion");
            assertEquals(0, script.calls.get(), "requests after host rejection never reach the provider");
            assertTrue(host.audit().snapshot().providerAttempts().isEmpty());
            assertThrows(IOException.class, host.audit()::assertComplete);
        }
    }

    @Test
    void samePathWrittenByTwoWorkersIsAttributedAndFlaggedAsConflict(@TempDir Path workspace)
            throws Exception {
        Script script = new Script(true);
        try (Pool pool = new Pool(3); Host host = new Host(script, workspace, pool.pool(), 120_000)) {

            Future<String> run = pool.pool().submit(() -> {
                String answer;
                try { answer = host.worker().runExplicitTask(GOAL, GOAL); }
                finally { host.relay().verifyTeamObservationFailures(host.worker().getExecutionObservationFailures()); }
                host.relay().complete(answer, List.of());
                return answer;
            });
            Future<BenchmarkProviderRelay.ServeResult> serving = pool.pool().submit(host::serveUntilTerminal);

            assertTrue(run.get(60, TimeUnit.SECONDS).startsWith("✅"));
            assertEquals(BenchmarkProviderRelay.ServeResult.WORKER_COMPLETE, serving.get(60, TimeUnit.SECONDS));
            host.audit().assertComplete();
            assertFalse(host.audit().failed(),
                    "a write conflict is recorded observation, not an audit failure");

            var snapshot = host.audit().snapshot();
            assertEquals(2, snapshot.writeAttributions().size(),
                    "both worker writes are attributed to their own activations");
            assertTrue(snapshot.writeAttributions().stream().allMatch(write ->
                    "shared.txt".equals(write.path())
                            && write.role() == com.paicli.agent.AgentRole.WORKER
                            && write.successful()));
            assertEquals(2, snapshot.writeAttributions().stream().map(write -> write.scope()).distinct().count(),
                    "parallel workers are distinct activations");
            assertEquals(1, snapshot.writeConflicts().size());
            var conflict = snapshot.writeConflicts().get(0);
            assertEquals("shared.txt", conflict.path());
            assertEquals(2, conflict.activationIds().size(),
                    "both conflicting activation identities are preserved");
        }
    }

    /** Host side: framed channel, provider relay, scripted provider, team audit and tracing. */
    static final class Host implements AutoCloseable {
        private final Duplex pair;
        private final RelayLlmClient relay;
        private final AgentOrchestrator worker;
        private final TeamRequestAudit audit;
        private final TracingLlmClient tracing;
        private final BenchmarkProviderRelay provider;
        private final AtomicInteger dropInput = new AtomicInteger();

        Host(Script script, Path workspace, ExecutorService pool, long deadlineMillis) throws Exception {
            this.pair = new Duplex();
            this.audit = new TeamRequestAudit(GOAL);
            this.tracing = new TracingLlmClient(
                    ContextWindowCappedLlmClient.cap(script, 1_000_000, 16_384),
                    workspace.getParent().resolve("host-trace.jsonl"), null, audit);
            var session = new BenchmarkRelayProtocol.SessionStart(
                    new BenchmarkRelayProtocol.Header(
                            BenchmarkRelayProtocol.Direction.COORDINATOR_TO_WORKER,
                            BenchmarkRelayProtocol.FrameType.SESSION_START, "", 0),
                    "session-team-evidence", script.getProviderName(), script.getModelName(),
                    BenchmarkRelayProtocol.AgentMode.TEAM, BenchmarkRelayProtocol.ToolProfile.FILE_ONLY,
                    GOAL, "2026-09-07", "UTC", System.currentTimeMillis() + deadlineMillis,
                    new BenchmarkRelayProtocol.AgentLimits(100_000, 100, 3, 1_000_000, 16_384),
                    BenchmarkProviderRelay.capabilitiesOf(tracing),
                    new BenchmarkRelayProtocol.Limits(
                            BenchmarkFramedChannel.MAX_FRAME_BYTES,
                            BenchmarkFramedChannel.MAX_SESSION_BYTES,
                            BenchmarkRelayProtocol.MAX_MESSAGES,
                            BenchmarkRelayProtocol.MAX_TOOLS));
            Future<BenchmarkProviderRelay> connecting = pool.submit(() -> BenchmarkProviderRelay.connect(
                    pair.coordinator(), session, tracing, null, null, null, null, null, audit));
            this.relay = RelayLlmClient.accept(pair.worker());
            this.provider = connecting.get();

            BenchmarkToolRegistry tools = new BenchmarkToolRegistry(BenchmarkToolProfile.FILE_ONLY);
            tools.setProjectPath(workspace.toString());
            this.worker = new AgentOrchestrator(relay, tools, new MemoryManager(relay), silentPrintStream());
            worker.setExternalContextSupplier(tools::promptPolicy);
            relay.setTeamObservationFailureSupplier(worker::getExecutionObservationFailures);
            worker.setExecutionObserver(event -> {
                if (event instanceof TeamExecutionObserver.ActivationInputPrepared
                        && dropInput.compareAndSet(1, 0)) return;
                relay.observeTeam(event);
            });
        }

        void dropNextActivationInput() { dropInput.set(1); }

        BenchmarkProviderRelay.ServeResult serveUntilTerminal() throws IOException {
            while (true) {
                BenchmarkProviderRelay.ServeResult result = provider.serveNext();
                if (result == BenchmarkProviderRelay.ServeResult.WORKER_COMPLETE
                        || result == BenchmarkProviderRelay.ServeResult.WORKER_FAILURE) return result;
            }
        }

        /** Unblocks the worker after a host rejection; a broken pipe is not evidence. */
        void breakWorkerDownstream() throws IOException {
            pair.coordinatorOutput.close();
        }

        RelayLlmClient relay() { return relay; }
        AgentOrchestrator worker() { return worker; }
        TeamRequestAudit audit() { return audit; }
        TracingLlmClient tracing() { return tracing; }

        @Override public void close() throws IOException {
            pair.close();
        }
    }

    private static PrintStream silentPrintStream() {
        return new PrintStream(java.io.OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8);
    }

    /** Deterministic planner/worker/reviewer stand-in; never a model score. */
    static final class Script implements LlmClient {
        private final AtomicInteger calls = new AtomicInteger();
        private final boolean sharedWrite;

        Script() { this(false); }

        Script(boolean sharedWrite) { this.sharedWrite = sharedWrite; }

        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            calls.incrementAndGet();
            String input = messages.get(messages.size() - 1).content();
            for (Message message : messages) {
                if ("user".equals(message.role())) { input = message.content(); break; }
            }
            if (input.startsWith("请为以下任务制定执行计划：\n")) {
                String plan = sharedWrite
                        ? "{\"steps\":[{\"id\":\"a\",\"description\":\"WORKA\",\"type\":\"FILE_WRITE\",\"dependencies\":[]},"
                          + "{\"id\":\"b\",\"description\":\"WORKB\",\"type\":\"FILE_WRITE\",\"dependencies\":[]}]}"
                        : "{\"steps\":[{\"id\":\"w\",\"description\":\"WORK\",\"type\":\"FILE_WRITE\",\"dependencies\":[]}]}";
                return new ChatResponse("assistant", plan, null, List.of(), 900, 60, 800, MODEL, true);
            }
            if (input.startsWith("原始任务：")) {
                return new ChatResponse("assistant",
                        "{\"approved\":true,\"summary\":\"TEAM_E2_REVIEW_乙\",\"issues\":[]}",
                        null, List.of(), 700, 40, 600, MODEL, true);
            }
            if ("user".equals(messages.get(messages.size() - 1).role())) {
                return new ChatResponse("assistant", "planning the write", null,
                        List.of(new ToolCall("team-call", new ToolCall.Function("write_file",
                                "{\"path\":\"" + (sharedWrite ? "shared.txt" : "notes.txt")
                                        + "\",\"content\":\"team done\"}"))),
                        800, 50, 700, MODEL, true);
            }
            return new ChatResponse("assistant", "WORK-result-1", null, List.of(), 300, 20, 250, MODEL, true);
        }

        @Override public String getModelName() { return MODEL; }
        @Override public String getProviderName() { return "deepseek"; }
        @Override public int maxContextWindow() { return 1_000_000; }
    }

    private static final class Duplex implements AutoCloseable {
        private final PipedInputStream workerInput = new PipedInputStream(1 << 20);
        private final PipedInputStream coordinatorInput = new PipedInputStream(1 << 20);
        private final PipedOutputStream coordinatorOutput;
        private final PipedOutputStream workerOutput;
        private final BenchmarkFramedChannel coordinator;
        private final BenchmarkFramedChannel worker;

        private Duplex() throws IOException {
            coordinatorOutput = new PipedOutputStream(workerInput);
            workerOutput = new PipedOutputStream(coordinatorInput);
            coordinator = new BenchmarkFramedChannel(coordinatorInput, coordinatorOutput);
            worker = new BenchmarkFramedChannel(workerInput, workerOutput);
        }

        private BenchmarkFramedChannel coordinator() { return coordinator; }
        private BenchmarkFramedChannel worker() { return worker; }

        @Override public void close() {
            for (AutoCloseable stream : List.of(coordinatorOutput, workerOutput, coordinatorInput, workerInput)) {
                try { stream.close(); }
                catch (IOException error) { throw new UncheckedIOException(error); }
                catch (Exception error) { throw new IllegalStateException(error); }
            }
        }
    }

    static final class Pool implements AutoCloseable {
        private final ExecutorService pool;

        Pool(int threads) { pool = Executors.newFixedThreadPool(threads); }

        ExecutorService pool() { return pool; }

        @Override public void close() { pool.shutdownNow(); }
    }
}
