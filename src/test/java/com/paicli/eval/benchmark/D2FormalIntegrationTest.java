package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.eval.benchmark.finalset.generator.FinalSourceGenerator;
import com.paicli.eval.benchmark.formal.FormalEpisodeOutcome;
import com.paicli.eval.benchmark.formal.FormalTestAdmission;
import com.paicli.eval.benchmark.mock.D2FrozenOracle;
import com.paicli.eval.benchmark.mock.D2ReadOnlyJoinMock;
import com.paicli.eval.benchmark.relay.BenchmarkProviderRelay;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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

import static org.junit.jupiter.api.Assertions.*;

/** Controls, not model performance: native Agent/relay/MCP + generated D2 + real Docker verifier. */
class D2FormalIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;

    @AfterEach void restorePermissions() throws Exception {
        try (var walk = Files.walk(temp)) {
            for (Path path : walk.toList()) if (!Files.isSymbolicLink(path))
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(Files.isDirectory(path) ? "rwx------" : "rw-------"));
        }
    }

    @Test void frozenDefinitionIsCheckedBeforeCredentialsAndServicesAreFresh() throws Exception {
        var ready = ready();
        var requests = ready.requests().stream().filter(r -> r.key().caseId().equals("D2")).toList();
        assertEquals(9, requests.size());
        for (var request : requests) {
            assertEquals(720, request.timeoutSeconds());
            var a = request.mockBinding().newService();
            var b = request.mockBinding().newService();
            assertNotSame(a, b);
            assertTrue(a.audit().isEmpty()); assertTrue(b.audit().isEmpty());
            assertEquals(3, a.stateDigests().size());
            assertEquals(a.initialStateDigests(), b.stateDigests());
        }
        corruptOracle(ready);
        assertThrows(IOException.class, () -> requests.get(0).mockBinding().newService());
        assertThrows(FormalEpisodeRequestFactory.PreparationException.class,
                () -> FormalEpisodeRequestFactory.validateCapabilities(ready.plan(), ready.plan().episodes().get(0)));
    }

    @Test void driftAfterDispatchRetainsNativeAuditButStopsBatchWithoutScore() throws Exception {
        var ready = ready();
        var worker = new BenchmarkCoordinatorMain.WorkerExecutor() {
            @Override public BenchmarkCoordinatorMain.WorkerExecution execute(BenchmarkProtocol.WorkerRequest request,
                    Path workspace, Path home, Duration timeout) { throw new AssertionError("D2 requires host mock"); }
            @Override public BenchmarkCoordinatorMain.WorkerExecution executeWithMock(BenchmarkProtocol.WorkerRequest request,
                    Path workspace, Path home, Duration timeout, BenchmarkProviderRelay.MockMcpEndpoint mock) throws IOException {
                var result = nativeControl(request, workspace, mock, D2McpRelayTest.Control.CORRECT);
                corruptOracle(ready);
                return result;
            }
        };
        var report = FormalBatchRunner.run(ready, temp.toRealPath().resolve("output"), "d2-drift", worker,
                (invocation, workspace, home, evidence, timeout) -> { throw new AssertionError("drift must stop grading"); }, Clock.systemUTC());
        assertEquals(1, report.summary().attemptedEpisodes());
        assertNull(report.summary().observedMeanWeightedScores());
        var episode = report.summary().episodes().get(0);
        var failure = assertInstanceOf(FormalEpisodeOutcome.Dataset.class, episode.outcome());
        assertEquals(FormalEpisodeOutcome.DatasetCode.VERIFIER_DEPENDENCY_DRIFT, failure.code());
        assertEquals(12, episode.mockEvidence().events().size());
        assertEquals(3, episode.mockEvidence().initialStateDigests().size());
    }

    @Test
    @EnabledIfSystemProperty(named = "paicli.test.verifier.image", matches = "sha256:[0-9a-f]{64}")
    void fullLoopGradesNineNativeControlsWithIndependentDockerVerifier() throws Exception {
        var ready = ready();
        var controls = List.of(D2McpRelayTest.Control.CORRECT, D2McpRelayTest.Control.FENCED_JSON,
                D2McpRelayTest.Control.FORBIDDEN_TOOL, D2McpRelayTest.Control.EXPIRED_INCIDENT,
                D2McpRelayTest.Control.CANCELLED_EVENT, D2McpRelayTest.Control.WRITE,
                D2McpRelayTest.Control.INVALID_ARGUMENTS, D2McpRelayTest.Control.MALFORMED_ARGUMENTS,
                D2McpRelayTest.Control.CORRECT);
        var dispatches = new AtomicInteger();
        var verifications = new AtomicInteger();
        var docker = new DockerBenchmarkVerifier(Path.of("/usr/local/bin/docker"), System.getProperty("paicli.test.verifier.image"));
        var worker = new BenchmarkCoordinatorMain.WorkerExecutor() {
            @Override public BenchmarkCoordinatorMain.WorkerExecution execute(BenchmarkProtocol.WorkerRequest request,
                    Path workspace, Path home, Duration timeout) {
                assertNotEquals(BenchmarkToolProfile.MOCK_MCP, request.toolProfile());
                return FormalBatchRunnerTest.successfulWorker(request, false);
            }
            @Override public BenchmarkCoordinatorMain.WorkerExecution executeWithMock(BenchmarkProtocol.WorkerRequest request,
                    Path workspace, Path home, Duration timeout, BenchmarkProviderRelay.MockMcpEndpoint mock) throws IOException {
                assertFalse(Files.exists(workspace.getParent().resolve("trusted-verifier")));
                assertFalse(Files.exists(workspace.resolve("validators")));
                return nativeControl(request, workspace, mock, controls.get(dispatches.getAndIncrement()));
            }
        };
        var report = FormalBatchRunner.run(ready, temp.toRealPath().resolve("output"), "d2-controls", worker,
                (invocation, workspace, home, evidence, timeout) -> {
                    var envelope = JSON.readTree(evidence.toFile());
                    String id = envelope.path("caseId").asText();
                    if (!id.equals("D2")) return FormalBatchRunnerTest.verification(ready.plan().requireCase(id), 100, false, 0);
                    verifications.incrementAndGet();
                    assertEquals(3, envelope.path("schemaVersion").asInt());
                    assertEquals(2, envelope.path("mockMcp").path("schemaVersion").asInt());
                    assertEquals(3, envelope.path("mockMcp").path("initialStateDigests").size());
                    assertEquals(3, envelope.path("mockMcp").path("finalStateDigests").size());
                    assertFalse(envelope.path("mockMcp").has("definition"));
                    return docker.verify(invocation, workspace, home, evidence, Duration.ofSeconds(120));
                }, Clock.systemUTC());
        assertEquals(252, report.summary().attemptedEpisodes(), report.summary().toString());
        assertEquals(9, dispatches.get()); assertEquals(9, verifications.get());
        assertTrue(report.summary().completeValidCoverage());
        assertFalse(report.summary().publishable()); assertNull(report.summary().formalScores());
        var episodes = report.summary().episodes().stream().filter(e -> e.key().caseId().equals("D2")).toList();
        assertEquals(List.of(100, 0, 0, 0, 0, 0, 0, 0, 100), episodes.stream()
                .map(e -> ((FormalEpisodeOutcome.Scored) e.outcome()).score()).toList());
        assertTrue(((FormalEpisodeOutcome.Scored) episodes.get(5).outcome()).result().hardGate());
        assertTrue(((FormalEpisodeOutcome.Scored) episodes.get(2).outcome()).result().hardGate(), "policy-denied local tool is a real hard gate, not missing evidence");
        assertNotEquals(episodes.get(5).mockEvidence().initialStateDigests(), episodes.get(5).mockEvidence().finalStateDigests());
        assertEquals(episodes.get(8).mockEvidence().initialStateDigests(), episodes.get(8).mockEvidence().finalStateDigests());
    }

    private FormalBatchPreparation.ReadyBatch ready() throws Exception {
        Path parent = temp.toRealPath();
        Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"));
        var source = new FinalSourceGenerator().generateIncompleteSource(new FinalSourceGenerator.GenerationRequest(
                parent.resolve("source"), "98bafedc32107654".repeat(4)));
        var admission = FormalTestAdmission.createWithGeneratedMock(parent.resolve("admission"), source.sourceRoot(), "D2");
        return FormalBatchPreparation.prepare(admission,
                provider -> new FormalEpisodeRequestFactory.HostCredential(provider, null, "private-test-credential"));
    }

    private static void corruptOracle(FormalBatchPreparation.ReadyBatch ready) throws IOException {
        Path path = ready.plan().artifacts().frozenRoot().resolve(D2FrozenOracle.PATH);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        Files.writeString(path, "{}");
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r--------"));
    }

    private static BenchmarkCoordinatorMain.WorkerExecution nativeControl(BenchmarkProtocol.WorkerRequest request,
            Path workspace, BenchmarkProviderRelay.MockMcpEndpoint endpoint, D2McpRelayTest.Control control) throws IOException {
        try {
            var complete = D2McpRelayTest.runWorker(assertInstanceOf(D2ReadOnlyJoinMock.class, endpoint), control, workspace);
            var limits = request.agentLimits();
            // Synthetic provider identity is explicit here; this test never calls an upstream model.
            var metrics = new TracingLlmClient.Metrics(4, 400, 120, 0, complete.toolExecutions().size(), 1, 4,
                    request.model(), true, true, "a".repeat(64), "b".repeat(64), true,
                    limits.contextWindowCapTokens(), limits.contextWindowCapTokens(), limits.maxOutputTokensPerCall(), 100, 130, true);
            return BenchmarkCoordinatorMain.WorkerExecution.completed(BenchmarkProtocol.WorkerResponse.success(complete.answer(), metrics),
                    0, 1, "", complete.toolExecutions().stream().map(e -> new BenchmarkToolExecutionEvidence(
                            e.ordinal(), e.callId(), e.toolName(), e.argumentsJson(), e.resultPreview(), e.resultSha256(),
                            e.resultChars(), e.elapsedMillis(), e.timedOut(), e.successful())).toList());
        } catch (Exception error) { throw new IOException("native D2 control failed", error); }
    }
}
