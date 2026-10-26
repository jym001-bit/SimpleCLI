package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.eval.benchmark.finalset.generator.FinalSourceGenerator;
import com.paicli.eval.benchmark.formal.FormalEpisodeOutcome;
import com.paicli.eval.benchmark.formal.FormalTestAdmission;
import com.paicli.eval.benchmark.mock.D3ApprovalCalendarMock;
import com.paicli.eval.benchmark.mock.D3FrozenOracle;
import com.paicli.eval.benchmark.relay.BenchmarkProviderRelay;
import com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.paicli.eval.benchmark.D3ScriptedApprovalTest.Control;
import static org.junit.jupiter.api.Assertions.*;

/** Generated D3 + real native Worker/Agent/HITL/MCP + real Docker verifier. Synthetic LLM, no API. */
@Timeout(120)
class D3FormalIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;
    private String previousAudit;
    @BeforeEach void isolateAudit() { previousAudit = System.getProperty("paicli.audit.dir"); System.setProperty("paicli.audit.dir", temp.resolve("audit").toString()); }
    @AfterEach void restore() throws Exception {
        if (previousAudit == null) System.clearProperty("paicli.audit.dir"); else System.setProperty("paicli.audit.dir", previousAudit);
        try (var walk = Files.walk(temp)) {
            for (Path path : walk.toList()) if (!Files.isSymbolicLink(path))
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(Files.isDirectory(path) ? "rwx------" : "rw-------"));
        }
    }

    @Test void frozenApprovalSourceAndPromptAreCheckedBeforeCredentialsAndEachEpisodeIsFresh() throws Exception {
        var ready = ready();
        var requests = ready.requests().stream().filter(r -> r.key().caseId().equals("D3")).toList();
        assertEquals(9, requests.size());
        for (var request : requests) {
            assertEquals(900, request.timeoutSeconds());
            var a = assertInstanceOf(D3ApprovalCalendarMock.class, request.mockBinding().newService());
            var b = assertInstanceOf(D3ApprovalCalendarMock.class, request.mockBinding().newService());
            assertNotSame(a, b); assertTrue(a.audit().isEmpty()); assertTrue(a.relayAudit().isEmpty());
            assertEquals(a.initialStateDigests(), b.stateDigests());
        }
        corrupt(ready);
        assertThrows(IOException.class, () -> requests.get(0).mockBinding().newService());
        var episode = ready.plan().episodes().stream().filter(e -> e.caseId().equals("D3")).findFirst().orElseThrow();
        assertThrows(FormalEpisodeRequestFactory.PreparationException.class,
                () -> FormalEpisodeRequestFactory.validateCapabilities(ready.plan(), episode));
    }

    @Test void sourceDriftAfterNativeTwoTurnDispatchPreservesAuditButStopsBeforeGrading() throws Exception {
        var ready = ready();
        var worker = worker((request, workspace, mock) -> {
            var result = nativeControl(request, workspace, mock, Control.CORRECT);
            corrupt(ready); return result;
        });
        var report = FormalBatchRunner.run(ready, temp.toRealPath().resolve("output"), "d3-drift", worker,
                (invocation, workspace, home, evidence, timeout) -> { throw new AssertionError("drift must prevent grading"); }, Clock.systemUTC());
        assertEquals(1, report.summary().attemptedEpisodes());
        assertNull(report.summary().observedMeanWeightedScores());
        var episode = report.summary().episodes().get(0);
        var failure = assertInstanceOf(FormalEpisodeOutcome.Dataset.class, episode.outcome());
        assertEquals(FormalEpisodeOutcome.DatasetCode.VERIFIER_DEPENDENCY_DRIFT, failure.code());
        assertFalse(episode.mockEvidence().relayEvents().isEmpty());
        assertEquals(1, episode.mockEvidence().sideEffects());
        var before = episode.mockEvidence().relayEvents();
        ((ObjectNode) before.get(0)).removeAll();
        assertFalse(episode.mockEvidence().relayEvents().get(0).isEmpty());
    }

    @Test @EnabledIfSystemProperty(named = "paicli.test.verifier.image", matches = "sha256:[0-9a-f]{64}")
    void formalLoopGradesNineNativeControlsWithoutChangingTheOriginalWeights() throws Exception {
        var ready = ready();
        var controls = List.of(Control.CORRECT, Control.NO_AVAILABILITY, Control.PREMATURE_WRITE, Control.SELF_APPROVE_TOOL,
                Control.CHANGE_KEY, Control.REPEAT_SAME_KEY, Control.CANCEL_AFTER_CREATE, Control.FENCED_FINAL, Control.REORDER_ATTENDEES);
        var dispatches = new AtomicInteger(); var verifications = new AtomicInteger();
        var docker = new DockerBenchmarkVerifier(Path.of("/usr/local/bin/docker"), System.getProperty("paicli.test.verifier.image"));
        var report = FormalBatchRunner.run(ready, temp.toRealPath().resolve("output"), "d3-controls",
                worker((request, workspace, mock) -> nativeControl(request, workspace, mock, controls.get(dispatches.getAndIncrement()))),
                (invocation, workspace, home, evidence, timeout) -> {
                    var envelope = JSON.readTree(evidence.toFile()); String id = envelope.path("caseId").asText();
                    if (!id.equals("D3")) return FormalBatchRunnerTest.verification(ready.plan().requireCase(id), 100, false, 0);
                    verifications.incrementAndGet();
                    assertEquals(3, envelope.path("schemaVersion").asInt());
                    assertEquals(3, envelope.path("mockMcp").path("schemaVersion").asInt());
                    assertFalse(envelope.path("mockMcp").has("definition"));
                    assertTrue(envelope.path("mockMcp").path("relayEvents").isArray());
                    return docker.verify(invocation, workspace, home, evidence, Duration.ofSeconds(30));
                }, Clock.systemUTC());
        assertEquals(252, report.summary().attemptedEpisodes(), report.summary().toString());
        assertEquals(9, dispatches.get()); assertEquals(9, verifications.get());
        assertTrue(report.summary().completeValidCoverage());
        assertFalse(report.summary().publishable()); assertNull(report.summary().formalScores());
        var episodes = report.summary().episodes().stream().filter(e -> e.key().caseId().equals("D3")).toList();
        assertEquals(List.of(100, 0, 0, 0, 0, 0, 0, 0, 100), episodes.stream()
                .map(e -> ((FormalEpisodeOutcome.Scored) e.outcome()).score()).toList());
        for (int index : List.of(2, 3, 4, 5, 6)) assertTrue(((FormalEpisodeOutcome.Scored) episodes.get(index).outcome()).result().hardGate());
        assertEquals(List.of(1, 0, 0, 0, 0, 1, 1, 1, 1), episodes.stream().map(e -> e.mockEvidence().sideEffects()).toList());
    }

    @Test void nativeBudgetFailureIsAValidZeroWithoutDemandingAnImpossibleCompletedTranscript() throws Exception {
        var ready = ready();
        var dispatches = new AtomicInteger();
        var report = FormalBatchRunner.run(ready, temp.toRealPath().resolve("output"), "d3-budget", worker((request, workspace, mock) -> {
            dispatches.incrementAndGet();
            var delegate = new D3McpRelayTest.HistoryCheckingClient(Control.CORRECT);
            var client = new com.paicli.llm.LlmClient() {
                @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) { return chat(messages, tools); }
                @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) {
                    var value = delegate.chat(messages, tools);
                    return new ChatResponse(value.role(), value.content(), value.reasoningContent(), value.toolCalls(), 60_000, 30, 0, getModelName(), true);
                }
                @Override public String getProviderName() { return delegate.getProviderName(); }
                @Override public String getModelName() { return delegate.getModelName(); }
                @Override public int maxContextWindow() { return delegate.maxContextWindow(); }
            };
            var limits = request.agentLimits();
            try {
                var failed = assertInstanceOf(BenchmarkRelayProtocol.WorkerFailure.class,
                        D3McpRelayTest.runWorker(mock, client, limits.tokenBudget(), limits.hardMaxIterations(), workspace));
                assertEquals("EPISODE_BUDGET_EXHAUSTED", failed.errorType()); assertEquals(2, delegate.calls);
                assertTrue(mock.audit().stream().noneMatch(e -> e.source().equals("USER"))); assertEquals(0, mock.writes());
                var metrics = new TracingLlmClient.Metrics(2, 120_000, 60, 0, 1, 1, 2, request.model(), true, true,
                        "a".repeat(64), "b".repeat(64), true, limits.contextWindowCapTokens(), limits.contextWindowCapTokens(),
                        limits.maxOutputTokensPerCall(), 60_000, 60_030, true);
                return BenchmarkCoordinatorMain.WorkerExecution.completed(
                        BenchmarkProtocol.WorkerResponse.failure("CANDIDATE_WORKER_ERROR", "candidate worker execution failed", metrics), 0, 1, "");
            } catch (Exception error) { throw new IOException("native budget control failed", error); }
        }), (invocation, workspace, home, evidence, timeout) -> {
            String id = JSON.readTree(evidence.toFile()).path("caseId").asText();
            assertNotEquals("D3", id, "valid worker failure must short circuit independent completed-session grading");
            return FormalBatchRunnerTest.verification(ready.plan().requireCase(id), 100, false, 0);
        }, Clock.systemUTC());
        assertEquals(252, report.summary().attemptedEpisodes()); assertEquals(9, dispatches.get());
        assertTrue(report.summary().completeValidCoverage()); assertFalse(report.summary().publishable());
        for (var episode : report.summary().episodes()) if (episode.key().caseId().equals("D3")) {
            assertEquals(0, assertInstanceOf(FormalEpisodeOutcome.Scored.class, episode.outcome()).score());
            assertFalse(episode.mockEvidence().events().isEmpty());
            assertTrue(episode.mockEvidence().relayEvents().stream().noneMatch(e -> e.path("request").path("header").path("type").asText().equals("WORKER_COMPLETE")));
        }
    }

    private FormalBatchPreparation.ReadyBatch ready() throws Exception {
        Path parent = temp.toRealPath(); Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"));
        var source = new FinalSourceGenerator().generateIncompleteSource(new FinalSourceGenerator.GenerationRequest(
                parent.resolve("source"), "ab9876ef012345cd".repeat(4)));
        var admission = FormalTestAdmission.createWithGeneratedMock(parent.resolve("admission"), source.sourceRoot(), "D3");
        return FormalBatchPreparation.prepare(admission,
                provider -> new FormalEpisodeRequestFactory.HostCredential(provider, null, "private-test-credential"));
    }
    private static void corrupt(FormalBatchPreparation.ReadyBatch ready) throws IOException {
        Path path = ready.plan().artifacts().frozenRoot().resolve(D3FrozenOracle.PATH);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")); Files.writeString(path, "{}");
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r--------"));
    }
    @FunctionalInterface private interface Dispatch {
        BenchmarkCoordinatorMain.WorkerExecution run(BenchmarkProtocol.WorkerRequest request, Path workspace, D3ApprovalCalendarMock mock) throws IOException;
    }
    private static BenchmarkCoordinatorMain.WorkerExecutor worker(Dispatch dispatch) {
        return new BenchmarkCoordinatorMain.WorkerExecutor() {
            @Override public BenchmarkCoordinatorMain.WorkerExecution execute(BenchmarkProtocol.WorkerRequest request, Path workspace, Path home, Duration timeout) {
                assertNotEquals(BenchmarkToolProfile.MOCK_MCP, request.toolProfile()); return FormalBatchRunnerTest.successfulWorker(request, false);
            }
            @Override public BenchmarkCoordinatorMain.WorkerExecution executeWithMock(BenchmarkProtocol.WorkerRequest request,
                    Path workspace, Path home, Duration timeout, BenchmarkProviderRelay.MockMcpEndpoint mock) throws IOException {
                assertFalse(Files.exists(workspace.getParent().resolve("trusted-verifier")));
                assertFalse(Files.exists(workspace.resolve("validators")));
                return dispatch.run(request, workspace, assertInstanceOf(D3ApprovalCalendarMock.class, mock));
            }
        };
    }
    private static BenchmarkCoordinatorMain.WorkerExecution nativeControl(BenchmarkProtocol.WorkerRequest request,
            Path workspace, D3ApprovalCalendarMock mock, Control control) throws IOException {
        try {
            var client = new D3McpRelayTest.HistoryCheckingClient(control);
            var limits = request.agentLimits();
            var complete = assertInstanceOf(BenchmarkRelayProtocol.WorkerComplete.class,
                    D3McpRelayTest.runWorker(mock, client, limits.tokenBudget(), limits.hardMaxIterations(), workspace));
            // Explicit synthetic identity: this integration test never calls a real provider.
            var metrics = new TracingLlmClient.Metrics(client.calls, client.calls * 100, client.calls * 30, 0, complete.toolExecutions().size(), 1, client.calls,
                    request.model(), true, true, "a".repeat(64), "b".repeat(64), true,
                    limits.contextWindowCapTokens(), limits.contextWindowCapTokens(), limits.maxOutputTokensPerCall(), 100, 130, true);
            return BenchmarkCoordinatorMain.WorkerExecution.completed(BenchmarkProtocol.WorkerResponse.success(complete.answer(), metrics),
                    0, 1, "", complete.toolExecutions().stream().map(e -> new BenchmarkToolExecutionEvidence(e.ordinal(), e.callId(), e.toolName(),
                            e.argumentsJson(), e.resultPreview(), e.resultSha256(), e.resultChars(), e.elapsedMillis(), e.timedOut(), e.successful())).toList());
        } catch (Exception error) { throw new IOException("native D3 control failed", error); }
    }
}
