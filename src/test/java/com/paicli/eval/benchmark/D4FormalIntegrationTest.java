package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.eval.benchmark.finalset.generator.FinalSourceGenerator;
import com.paicli.eval.benchmark.formal.FormalEpisodeOutcome;
import com.paicli.eval.benchmark.formal.FormalTestAdmission;
import com.paicli.eval.benchmark.mock.D4FrozenOracle;
import com.paicli.eval.benchmark.mock.D4WebMock;
import com.paicli.eval.benchmark.relay.BenchmarkProviderRelay;
import com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol;
import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.paicli.eval.benchmark.D4NativeWebTest.Control;
import static com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol.*;
import static org.junit.jupiter.api.Assertions.*;

/** Generated D4 -> frozen admission -> native Agent/relay -> Docker verifier. Synthetic LLM only. */
@Timeout(120)
class D4FormalIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;
    private String previousAudit;
    @BeforeEach void isolateAudit() { previousAudit = System.getProperty("paicli.audit.dir"); System.setProperty("paicli.audit.dir", temp.resolve("audit").toString()); }
    @AfterEach void restore() throws Exception {
        if (previousAudit == null) System.clearProperty("paicli.audit.dir"); else System.setProperty("paicli.audit.dir", previousAudit);
        try (var walk = Files.walk(temp)) {
            for (Path p : walk.toList()) if (!Files.isSymbolicLink(p))
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(Files.isDirectory(p) ? "rwx------" : "rw-------"));
        }
    }

    @Test void frozenSourceIsRequiredBeforeCredentialsAndEachEpisodeGetsFreshHostState() throws Exception {
        var ready = ready();
        var requests = ready.requests().stream().filter(r -> r.key().caseId().equals("D4")).toList();
        assertEquals(9, requests.size());
        for (var request : requests) {
            assertEquals(720, request.timeoutSeconds()); assertNull(request.mockBinding());
            var a = request.webBinding().newService(); var b = request.webBinding().newService();
            assertNotSame(a, b); assertEquals(a.definition(), b.definition());
            assertTrue(a.audit().isEmpty()); assertTrue(a.relayAudit().isEmpty()); assertTrue(a.providerAudit().isEmpty());
            var evidence = request.webBinding().evidence(a);
            assertEquals(12, evidence.relayVersion());
            assertThrows(IllegalArgumentException.class, () -> new FormalMockWebBinding.MockEvidence(
                    evidence.schemaVersion(), evidence.caseId(), evidence.profile(), 12,
                    evidence.mockSourceSha256(), evidence.promptSha256(), evidence.events(), evidence.relayEvents(), evidence.providerTurns()));
            assertEquals(textSha256(ready.plan().requireCase("D4").prompt()), evidence.promptSha256());
        }
        var plan = ready.plan().requireCase("D4");
        String wrongPrompt = plan.prompt() + "Changed scope.";
        var changed = new com.paicli.eval.benchmark.formal.FormalExecutionPlan.CasePlan(plan.ordinal(), plan.contract(), plan.scoringContract(),
                plan.scoringContractFile(), plan.scoringContractSha256(), wrongPrompt, textSha256(wrongPrompt), plan.fixture(), plan.verifier());
        assertThrows(IOException.class, () -> FormalMockWebBinding.capture(changed));
        corrupt(ready);
        assertThrows(IOException.class, () -> requests.get(0).webBinding().newService());
        var episode = ready.plan().episodes().stream().filter(e -> e.caseId().equals("D4")).findFirst().orElseThrow();
        assertThrows(FormalEpisodeRequestFactory.PreparationException.class,
                () -> FormalEpisodeRequestFactory.validateCapabilities(ready.plan(), episode));
    }

    @Test void sourceDriftAfterDispatchRetainsHostEvidenceButNeverGrades() throws Exception {
        var ready = ready();
        var report = FormalBatchRunner.run(ready, temp.toRealPath().resolve("output"), "d4-drift",
                worker((request, workspace, mock) -> {
                    var result = nativeControl(request, workspace, mock, Control.CORRECT, false);
                    corrupt(ready); return result;
                }), (invocation, workspace, home, evidence, timeout) -> { throw new AssertionError("drift must prevent grading"); }, Clock.systemUTC());
        assertEquals(1, report.summary().attemptedEpisodes()); assertNull(report.summary().observedMeanWeightedScores());
        var episode = report.summary().episodes().get(0);
        var failure = assertInstanceOf(FormalEpisodeOutcome.Dataset.class, episode.outcome());
        assertEquals(FormalEpisodeOutcome.DatasetCode.VERIFIER_DEPENDENCY_DRIFT, failure.code());
        assertEquals(5, episode.webEvidence().relayEvents().size()); assertEquals(3, episode.webEvidence().providerTurns().size());
    }

    @Test @EnabledIfSystemProperty(named = "paicli.test.verifier.image", matches = "sha256:[0-9a-f]{64}")
    void formalLoopGradesNineNativeControlsAtOriginalWeightsAndNeverClaimsModelScores() throws Exception {
        var ready = ready();
        var controls = List.of(Control.CORRECT, Control.BEFORE_SEARCH, Control.SAME_BATCH, Control.SNIPPET_URL,
                Control.BODY_URL, Control.LOCAL_TOOL, Control.WRONG_CITATION, Control.FENCED_ANSWER, Control.REVERSED_FETCH);
        var dispatches = new AtomicInteger(); var verifications = new AtomicInteger();
        var docker = new DockerBenchmarkVerifier(Path.of("/usr/local/bin/docker"), System.getProperty("paicli.test.verifier.image"));
        var report = FormalBatchRunner.run(ready, temp.toRealPath().resolve("output"), "d4-controls",
                worker((request, workspace, mock) -> nativeControl(request, workspace, mock, controls.get(dispatches.getAndIncrement()), false)),
                (invocation, workspace, home, evidence, timeout) -> {
                    var envelope = JSON.readTree(evidence.toFile()); String id = envelope.path("caseId").asText();
                    if (!id.equals("D4")) return FormalBatchRunnerTest.verification(ready.plan().requireCase(id), 100, false, 0);
                    verifications.incrementAndGet();
                    assertEquals(4, envelope.path("schemaVersion").asInt()); assertFalse(envelope.has("mockMcp"));
                    assertEquals(1, envelope.path("mockWeb").path("schemaVersion").asInt());
                    assertFalse(envelope.path("mockWeb").has("definition"));
                    assertTrue(envelope.path("mockWeb").path("providerTurns").isArray());
                    return docker.verify(invocation, workspace, home, evidence, Duration.ofSeconds(30));
                }, Clock.systemUTC());
        assertEquals(252, report.summary().attemptedEpisodes(), report.summary().toString());
        assertEquals(9, dispatches.get()); assertEquals(9, verifications.get());
        assertTrue(report.summary().completeValidCoverage()); assertFalse(report.summary().publishable()); assertNull(report.summary().formalScores());
        var episodes = report.summary().episodes().stream().filter(e -> e.key().caseId().equals("D4")).toList();
        assertEquals(List.of(100, 0, 0, 0, 0, 0, 0, 0, 100), episodes.stream()
                .map(e -> assertInstanceOf(FormalEpisodeOutcome.Scored.class, e.outcome()).score()).toList());
        for (int i : List.of(1, 2, 3, 4, 5)) assertTrue(((FormalEpisodeOutcome.Scored) episodes.get(i).outcome()).result().hardGate());
    }

    @Test void nativeBudgetExhaustionAndTimeoutRemainValidFailuresWithoutCompletedTranscript() throws Exception {
        var ready = ready(); var dispatches = new AtomicInteger();
        var report = FormalBatchRunner.run(ready, temp.toRealPath().resolve("output"), "d4-budget", worker((request, workspace, mock) -> {
            if (dispatches.getAndIncrement() % 2 == 1) return BenchmarkCoordinatorMain.WorkerExecution.timeout(720_000, "synthetic timeout",
                    FormalBatchRunnerTest.successfulWorker(request, false).response().metrics());
            return nativeControl(request, workspace, mock, Control.CORRECT, true);
        }), (invocation, workspace, home, evidence, timeout) -> {
            String id = JSON.readTree(evidence.toFile()).path("caseId").asText();
            assertNotEquals("D4", id, "incomplete Candidate execution must not demand a completed replay transcript");
            return FormalBatchRunnerTest.verification(ready.plan().requireCase(id), 100, false, 0);
        }, Clock.systemUTC());
        assertEquals(252, report.summary().attemptedEpisodes()); assertEquals(9, dispatches.get());
        assertTrue(report.summary().completeValidCoverage()); assertFalse(report.summary().publishable());
        for (var episode : report.summary().episodes()) if (episode.key().caseId().equals("D4")) {
            assertEquals(0, assertInstanceOf(FormalEpisodeOutcome.Scored.class, episode.outcome()).score());
            assertNotNull(episode.webEvidence());
        }
    }

    private FormalBatchPreparation.ReadyBatch ready() throws Exception {
        Path parent = temp.toRealPath(); Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"));
        var source = new FinalSourceGenerator().generateIncompleteSource(new FinalSourceGenerator.GenerationRequest(
                parent.resolve("source"), "ab9876ef012345cd".repeat(4)));
        return FormalBatchPreparation.prepare(FormalTestAdmission.createWithGeneratedMock(parent.resolve("admission"), source.sourceRoot(), "D4"),
                provider -> new FormalEpisodeRequestFactory.HostCredential(provider, null, "private-test-credential"));
    }
    private static void corrupt(FormalBatchPreparation.ReadyBatch ready) throws IOException {
        Path p = ready.plan().artifacts().frozenRoot().resolve(D4FrozenOracle.PATH);
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------")); Files.writeString(p, "{}");
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("r--------"));
    }
    @FunctionalInterface private interface Dispatch {
        BenchmarkCoordinatorMain.WorkerExecution run(BenchmarkProtocol.WorkerRequest request, Path workspace, D4WebMock mock) throws IOException;
    }
    private static BenchmarkCoordinatorMain.WorkerExecutor worker(Dispatch dispatch) {
        return new BenchmarkCoordinatorMain.WorkerExecutor() {
            public BenchmarkCoordinatorMain.WorkerExecution execute(BenchmarkProtocol.WorkerRequest request, Path workspace, Path home, Duration timeout) {
                assertNotEquals(BenchmarkToolProfile.MOCK_WEB, request.toolProfile()); return FormalBatchRunnerTest.successfulWorker(request, false);
            }
            public BenchmarkCoordinatorMain.WorkerExecution executeWithWeb(BenchmarkProtocol.WorkerRequest request, Path workspace, Path home,
                    Duration timeout, BenchmarkProviderRelay.MockWebEndpoint mock) throws IOException {
                assertFalse(Files.exists(workspace.getParent().resolve("trusted-verifier"))); assertFalse(Files.exists(workspace.resolve("validators")));
                try (var children = Files.list(workspace)) { assertEquals(List.of("README.md"), children.map(p -> p.getFileName().toString()).toList()); }
                return dispatch.run(request, workspace, assertInstanceOf(D4WebMock.class, mock));
            }
        };
    }
    private static BenchmarkCoordinatorMain.WorkerExecution nativeControl(BenchmarkProtocol.WorkerRequest request, Path workspace,
            D4WebMock mock, Control control, boolean exhaustBudget) throws IOException {
        try {
            var delegate = new D4NativeWebTest.ScriptedClient(mock.definition(), control, request.provider(), request.model());
            LlmClient client = exhaustBudget ? new LlmClient() {
                public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) { return chat(messages, tools); }
                public ChatResponse chat(List<Message> messages, List<Tool> tools) {
                    var r = delegate.chat(messages, tools);
                    return new ChatResponse(r.role(), r.content(), r.reasoningContent(), r.toolCalls(), 60_000, 30, 0, getModelName(), true);
                }
                public String getProviderName() { return delegate.getProviderName(); }
                public String getModelName() { return delegate.getModelName(); }
                public int maxContextWindow() { return delegate.maxContextWindow(); }
            } : delegate;
            var limits = request.agentLimits();
            var trace = new TracingLlmClient(ContextWindowCappedLlmClient.cap(client, limits.contextWindowCapTokens(), limits.maxOutputTokensPerCall()),
                    workspace.getParent().resolve("synthetic-provider-trace.jsonl"));
            var start = new SessionStart(new Header(Direction.COORDINATOR_TO_WORKER, FrameType.SESSION_START, "", 0),
                    "d4-formal-control", request.provider(), request.model(), AgentMode.REACT, ToolProfile.MOCK_WEB, request.prompt(),
                    request.runtimeDate(), "UTC", System.currentTimeMillis() + 60_000,
                    new AgentLimits(limits.tokenBudget(), limits.hardMaxIterations(), limits.stagnationWindow(), limits.contextWindowCapTokens(), limits.maxOutputTokensPerCall()),
                    BenchmarkProviderRelay.capabilitiesOf(trace), D4WebRelayTest.start(trace, request.prompt()).limits(), List.of());
            var run = D4WebRelayTest.runResult(mock, trace, workspace, start);
            var terminal = run.terminal();
            if (exhaustBudget) {
                assertTrue(run.budgetExhausted());
                assertInstanceOf(WorkerComplete.class, terminal); // Product retains its best-effort partial answer.
                assertEquals(3, trace.metrics().calls());
                return BenchmarkCoordinatorMain.WorkerExecution.completed(BenchmarkProtocol.WorkerResponse.failure(
                        "EPISODE_BUDGET_EXHAUSTED", "candidate budget exhausted", trace.metrics()), 0, 1, "");
            }
            var complete = assertInstanceOf(WorkerComplete.class, terminal);
            return BenchmarkCoordinatorMain.WorkerExecution.completed(BenchmarkProtocol.WorkerResponse.success(complete.answer(), trace.metrics()),
                    0, 1, "", complete.toolExecutions().stream().map(e -> new BenchmarkToolExecutionEvidence(e.ordinal(), e.callId(), e.toolName(),
                            e.argumentsJson(), e.resultPreview(), e.resultSha256(), e.resultChars(), e.elapsedMillis(), e.timedOut(), e.successful())).toList());
        } catch (Exception error) { throw new IOException("native D4 control failed", error); }
    }
}
