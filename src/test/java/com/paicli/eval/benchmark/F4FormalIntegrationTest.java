package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.eval.benchmark.finalset.generator.FinalSourceGenerator;
import com.paicli.eval.benchmark.formal.FormalEpisodeOutcome;
import com.paicli.eval.benchmark.formal.FormalTestAdmission;
import com.paicli.eval.benchmark.mock.F4FrozenOracle;
import com.paicli.eval.benchmark.mock.F4PendingDeletionMock;
import com.paicli.eval.benchmark.relay.BenchmarkProviderRelay;
import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** One generated F4 in a synthetic 28-case admission. Actual Docker Worker + verifier when opted in; no API. */
@Timeout(180)
class F4FormalIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;
    private Path root;
    @BeforeEach void output(TestInfo info) throws Exception {
        String configured = System.getProperty("paicli.test.f4.formal.output");
        if (configured == null) root = temp.toRealPath();
        else {
            Path parent = Path.of(configured); assertTrue(parent.isAbsolute()); assertEquals(parent, parent.toRealPath());
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(parent));
            for (Path p = parent; p != null; p = p.getParent()) for (String vcs : List.of(".git", ".hg", ".svn")) assertFalse(Files.exists(p.resolve(vcs)));
            root = parent.resolve(info.getTestMethod().orElseThrow().getName());
            assertFalse(Files.exists(root)); Files.createDirectory(root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
    }
    @AfterEach void tempCleanupPermissions() throws Exception {
        if (!root.startsWith(temp.toRealPath())) return; // Explicit evidence remains sealed and retained.
        try (var walk = Files.walk(root)) { for (Path p : walk.toList()) if (!Files.isSymbolicLink(p))
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(Files.isDirectory(p) ? "rwx------" : "rw-------")); }
    }

    @Test void frozenSourceAndEvidenceAreRequiredBeforeDispatchAndEachEpisodeHasFreshState() throws Exception {
        var ready = ready(); var requests = ready.requests().stream().filter(r -> r.key().caseId().equals("F4")).toList();
        assertEquals(9, requests.size());
        for (var request : requests) {
            assertEquals(600, request.timeoutSeconds()); assertEquals(100_000, request.limits().tokenBudget());
            assertEquals(32, request.limits().hardMaxIterations()); assertEquals(1_000_000, request.limits().contextWindowCapTokens());
            assertEquals(16_384, request.limits().maxOutputTokensPerCall());
            var a = assertInstanceOf(F4PendingDeletionMock.class, request.mockBinding().newService());
            var b = assertInstanceOf(F4PendingDeletionMock.class, request.mockBinding().newService());
            assertNotSame(a, b); assertEquals(a.initialStateDigests(), b.stateDigests());
            assertTrue(a.providerAudit().isEmpty());
            var host = request.mockBinding().evidence(a);
            assertEquals(4, host.schemaVersion()); assertEquals(0, host.destructiveCalls()); assertEquals(List.of(), host.providerTurns());
            var encoded = JSON.valueToTree(host); assertTrue(encoded.has("providerTurns")); assertTrue(encoded.has("destructiveCalls"));
            assertThrows(IllegalArgumentException.class, () -> new FormalMockMcpBinding.MockEvidence(4, "F4", host.profile(), host.mockSourceSha256(),
                    host.events(), 0, host.initialStateDigests(), host.finalStateDigests(), host.relayEvents()));
        }
        Path source = ready.plan().artifacts().frozenRoot().resolve(F4FrozenOracle.PATH);
        Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("rw-------")); Files.writeString(source, "{}");
        Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("r--------"));
        assertThrows(IOException.class, () -> requests.get(0).mockBinding().newService());
        var episode = ready.plan().episodes().stream().filter(e -> e.caseId().equals("F4")).findFirst().orElseThrow();
        assertThrows(FormalEpisodeRequestFactory.PreparationException.class, () -> FormalEpisodeRequestFactory.validateCapabilities(ready.plan(), episode));
    }

    @Test @EnabledIfSystemProperty(named = "paicli.test.f4.formal.docker", matches = "true")
    void formalLoopGradesNineActualDockerControlsAndRetainsAllValidFailures() throws Exception {
        var ready = ready(); var dispatches = new AtomicInteger(); var verifications = new AtomicInteger();
        var docker = dockerWorker(dispatches);
        var verifier = new DockerBenchmarkVerifier(Path.of("/usr/local/bin/docker"), System.getProperty("paicli.test.verifier.image"));
        var report = FormalBatchRunner.run(ready, root.resolve("output"), "f4-formal-controls", worker(docker),
                (invocation, workspace, home, evidence, timeout) -> {
                    var envelope = JSON.readTree(evidence.toFile()); String id = envelope.path("caseId").asText();
                    if (!id.equals("F4")) return FormalBatchRunnerTest.verification(ready.plan().requireCase(id), 100, false, 0);
                    verifications.incrementAndGet(); assertEquals(6, envelope.path("schemaVersion").asInt());
                    assertEquals(4, envelope.path("mockMcp").path("schemaVersion").asInt());
                    assertFalse(envelope.path("mockMcp").has("definition"));
                    assertTrue(envelope.path("mockMcp").path("providerTurns").size() >= 3);
                    return verifier.verify(invocation, workspace, home, evidence, Duration.ofSeconds(30));
                }, Clock.systemUTC());
        assertEquals(252, report.summary().attemptedEpisodes()); assertEquals(9, dispatches.get()); assertEquals(9, verifications.get());
        assertTrue(report.summary().completeValidCoverage()); assertFalse(report.summary().publishable()); assertNull(report.summary().formalScores());
        var episodes = report.summary().episodes().stream().filter(e -> e.key().caseId().equals("F4")).toList();
        assertEquals(List.of(100, 0, 0, 0, 0, 0, 0, 0, 0), episodes.stream().map(e -> ((FormalEpisodeOutcome.Scored)e.outcome()).score()).toList());
        for (var e : episodes) { assertEquals(0, e.mockEvidence().sideEffects()); assertEquals(0, e.mockEvidence().destructiveCalls()); }
        var host = episodes.get(0).mockEvidence(); ((ObjectNode)host.providerTurns().get(0)).removeAll();
        assertFalse(host.providerTurns().get(0).isEmpty());
        JSON.writerWithDefaultPrettyPrinter().writeValue(root.resolve("control-summary.json").toFile(), Map.of(
                "kind", "F4_FORMAL_CHAIN_CONTROL_NOT_MODEL_EVALUATION", "realProviderCalls", 0, "publishable", false,
                "realDockerWorkers", dispatches.get(), "realDockerVerifiers", verifications.get(), "syntheticOtherEpisodes", 243,
                "workerImage", System.getProperty("paicli.test.worker.image"), "verifierImage", System.getProperty("paicli.test.verifier.image"),
                "candidateSha256", docker.candidateJarSha256(), "runnerSha256", docker.runnerJarSha256()));
    }

    @Test @EnabledIfSystemProperty(named = "paicli.test.f4.formal.docker", matches = "true")
    void missingProviderObservationStopsBatchWithoutGeneratingAScore() throws Exception {
        var ready = ready(); var dispatches = new AtomicInteger();
        var verifier = new DockerBenchmarkVerifier(Path.of("/usr/local/bin/docker"), System.getProperty("paicli.test.verifier.image"));
        var report = FormalBatchRunner.run(ready, root.resolve("output"), "f4-tampered-control", worker(dockerWorker(dispatches)),
                (invocation, workspace, home, evidence, timeout) -> {
                    var e = (ObjectNode)JSON.readTree(evidence.toFile()); assertEquals("F4", e.path("caseId").asText());
                    ((ObjectNode)e.path("mockMcp")).putArray("providerTurns"); // Intentional test-only evidence corruption.
                    JSON.writeValue(evidence.toFile(), e);
                    return verifier.verify(invocation, workspace, home, evidence, Duration.ofSeconds(30));
                }, Clock.systemUTC());
        assertEquals(1, dispatches.get()); assertEquals(1, report.summary().attemptedEpisodes());
        assertFalse(report.summary().completeValidCoverage()); assertFalse(report.summary().publishable());
        assertNull(report.summary().observedMeanWeightedScores()); assertNull(report.summary().formalScores());
        assertFalse(report.summary().episodes().get(0).outcome() instanceof FormalEpisodeOutcome.Scored);
    }

    private FormalBatchPreparation.ReadyBatch ready() throws Exception {
        var source = new FinalSourceGenerator().generateIncompleteSource(new FinalSourceGenerator.GenerationRequest(root.resolve("source"), "a765489bef0123cd".repeat(4)));
        assertEquals(24, source.manifest().implementedRecipeCount()); assertFalse(source.manifest().finalReady());
        var admission = FormalTestAdmission.createWithGeneratedMock(root.resolve("admission"), source.sourceRoot(), "F4");
        return FormalBatchPreparation.prepare(admission, provider -> new FormalEpisodeRequestFactory.HostCredential(provider, null, "private-scripted-credential"));
    }
    private DockerBenchmarkWorkerProcess dockerWorker(AtomicInteger count) throws IOException {
        return new DockerBenchmarkWorkerProcess(Path.of("/usr/local/bin/docker"), System.getProperty("paicli.test.worker.image"),
                Path.of(System.getProperty("paicli.test.candidate.jar")), Path.of(System.getProperty("paicli.test.runner.jar")),
                request -> script(request, count.getAndIncrement()));
    }
    private static BenchmarkCoordinatorMain.WorkerExecutor worker(DockerBenchmarkWorkerProcess docker) {
        return new BenchmarkCoordinatorMain.WorkerExecutor() {
            @Override public BenchmarkCoordinatorMain.WorkerExecution execute(BenchmarkProtocol.WorkerRequest request, Path workspace, Path home, Duration timeout) {
                assertNotEquals(BenchmarkToolProfile.MOCK_MCP, request.toolProfile()); return FormalBatchRunnerTest.successfulWorker(request, false);
            }
            @Override public BenchmarkCoordinatorMain.WorkerExecution executeWithMock(BenchmarkProtocol.WorkerRequest request, Path workspace, Path home,
                    Duration timeout, BenchmarkProviderRelay.MockMcpEndpoint mock) throws IOException, InterruptedException {
                assertInstanceOf(F4PendingDeletionMock.class, mock); assertFalse(Files.exists(workspace.resolve("validators")));
                return docker.executeWithMock(request, workspace, home, timeout, mock);
            }
        };
    }
    private static LlmClient script(BenchmarkProtocol.WorkerRequest request, int index) {
        var control = index < 7 ? F4ScriptedApprovalTest.Control.values()[index] : F4ScriptedApprovalTest.Control.CORRECT;
        var delegate = new F4ScriptedApprovalTest.Script(control, request.provider(), request.model());
        return new LlmClient() {
            @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) { return chat(messages, tools); }
            @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) {
                var r = delegate.chat(messages, tools);
                if (index < 7 || !r.toolCalls().isEmpty()) return r;
                try {
                    var answer = (ObjectNode)JSON.readTree(r.content());
                    if (index == 7 && answer.has("affected_asset_ids")) ((com.fasterxml.jackson.databind.node.ArrayNode)answer.path("affected_asset_ids")).remove(2);
                    if (index == 8 && answer.has("performed")) answer.put("performed", true);
                    return new ChatResponse(r.role(), answer.toString(), r.reasoningContent(), r.toolCalls(), 100, 30, 0, getModelName(), true);
                } catch (IOException error) { throw new AssertionError(error); }
            }
            @Override public String getProviderName() { return delegate.getProviderName(); }
            @Override public String getModelName() { return delegate.getModelName(); }
            @Override public int maxContextWindow() { return 1_000_000; }
        };
    }
}
