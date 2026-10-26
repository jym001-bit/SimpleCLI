package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.eval.benchmark.finalset.generator.FinalSourceGenerator;
import com.paicli.eval.benchmark.formal.*;
import com.paicli.eval.benchmark.safety.F1FrozenOracle;
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

/** One generated F1 and 27 synthetic cases; Docker responses/usage are scripted, never model scores. */
@Timeout(180)
class F1FormalIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;
    private Path root;
    @BeforeEach void output(TestInfo info) throws Exception {
        String configured = System.getProperty("paicli.test.f1.formal.output");
        if (configured == null) root = temp.toRealPath();
        else {
            Path parent = Path.of(configured); assertEquals(parent, parent.toRealPath());
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(parent));
            root = F1BoundarySessionTest.privateDir(parent.resolve(info.getTestMethod().orElseThrow().getName()));
        }
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
    }
    @AfterEach void restoreTemporaryPermissions() throws Exception {
        if (!root.startsWith(temp.toRealPath())) return;
        try (var walk = Files.walk(root)) { for (Path p : walk.toList()) if (!Files.isSymbolicLink(p))
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(Files.isDirectory(p) ? "rwx------" : "rw-------")); }
    }
    @Test void sourceAndCapsAreBoundBeforeCredentialUseAndSessionsAreDistinct() throws Exception {
        var ready = ready(); var requests = ready.requests().stream().filter(r -> r.key().caseId().equals("F1")).toList();
        assertEquals(9, requests.size());
        for (var request : requests) {
            assertNotNull(request.boundaryBinding()); assertNull(request.mockBinding()); assertNull(request.planBinding());
            assertEquals(600, request.timeoutSeconds()); assertEquals(100_000, request.limits().tokenBudget());
            assertEquals(32, request.limits().hardMaxIterations()); assertEquals(1_000_000, request.limits().contextWindowCapTokens());
            assertEquals(16_384, request.limits().maxOutputTokensPerCall());
            assertNotSame(request.boundaryBinding().newSession(), request.boundaryBinding().newSession());
        }
        Path source = ready.plan().artifacts().frozenRoot().resolve(F1FrozenOracle.PATH);
        Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("rw-------")); Files.writeString(source, "{}");
        Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("r--------"));
        assertThrows(IOException.class, () -> requests.get(0).boundaryBinding().newSession());
        var episode = ready.plan().episodes().stream().filter(e -> e.caseId().equals("F1")).findFirst().orElseThrow();
        assertThrows(FormalEpisodeRequestFactory.PreparationException.class, () -> FormalEpisodeRequestFactory.validateCapabilities(ready.plan(), episode));
    }

    @Test @EnabledIfSystemProperty(named = "paicli.test.f1.formal.docker", matches = "true")
    void formalLoopScoresActualWorkersAndRetainsProtectionAndBypassOutcomes() throws Exception {
        var ready = ready(); var dispatches = new AtomicInteger(); var verifications = new AtomicInteger();
        var docker = dockerWorker(dispatches);
        var verifier = new DockerBenchmarkVerifier(Path.of("/usr/local/bin/docker"), System.getProperty("paicli.test.verifier.image"));
        var report = FormalBatchRunner.run(ready, root.resolve("output"), "f1-formal-controls", worker(docker),
                (invocation, workspace, home, evidence, timeout) -> {
                    var envelope = JSON.readTree(evidence.toFile()); String id = envelope.path("caseId").asText();
                    if (!id.equals("F1")) return FormalBatchRunnerTest.verification(ready.plan().requireCase(id), 100, false, 0);
                    verifications.incrementAndGet(); assertEquals(7, envelope.path("schemaVersion").asInt());
                    assertEquals("HOST_PORTAL_OMITTED_V1", envelope.path("boundary").path("workspaceProjection").asText());
                    assertFalse(Files.exists(workspace.resolve("review-drop"), LinkOption.NOFOLLOW_LINKS));
                    assertEquals(envelope.path("boundary").path("observation").path("before"), envelope.path("boundary").path("observation").path("after"));
                    return verifier.verify(invocation, workspace, home, evidence, Duration.ofSeconds(30));
                }, Clock.systemUTC());
        assertEquals(252, report.summary().attemptedEpisodes()); assertEquals(9, dispatches.get()); assertEquals(9, verifications.get());
        assertTrue(report.summary().completeValidCoverage()); assertFalse(report.summary().publishable()); assertNull(report.summary().formalScores());
        var episodes = report.summary().episodes().stream().filter(e -> e.key().caseId().equals("F1")).toList();
        assertEquals(List.of(100, 100, 100, 0, 0, 0, 0, 0, 100), episodes.stream().map(e -> ((FormalEpisodeOutcome.Scored)e.outcome()).score()).toList());
        JSON.writerWithDefaultPrettyPrinter().writeValue(root.resolve("control-summary.json").toFile(), Map.of(
                "kind", "F1_FORMAL_CHAIN_SCRIPTED_CONTROL", "realProviderCalls", 0, "publishable", false,
                "realDockerWorkers", dispatches.get(), "realDockerVerifiers", verifications.get(), "syntheticOtherEpisodes", 243,
                "workerImage", System.getProperty("paicli.test.worker.image"), "verifierImage", System.getProperty("paicli.test.verifier.image"),
                "candidateSha256", docker.candidateJarSha256(), "runnerSha256", docker.runnerJarSha256()));
    }

    @Test @EnabledIfSystemProperty(named = "paicli.test.f1.formal.docker", matches = "true")
    void tamperedBoundaryEvidenceStopsTheBatchWithNoScore() throws Exception {
        var ready = ready(); var dispatches = new AtomicInteger();
        var verifier = new DockerBenchmarkVerifier(Path.of("/usr/local/bin/docker"), System.getProperty("paicli.test.verifier.image"));
        var report = FormalBatchRunner.run(ready, root.resolve("output"), "f1-tampered-control", worker(dockerWorker(dispatches)),
                (invocation, workspace, home, evidence, timeout) -> {
                    var tree = (ObjectNode)JSON.readTree(evidence.toFile()); assertEquals("F1", tree.path("caseId").asText());
                    ((ObjectNode)tree.path("boundary")).put("sourceSha256", "0".repeat(64));
                    JSON.writeValue(evidence.toFile(), tree);
                    return verifier.verify(invocation, workspace, home, evidence, Duration.ofSeconds(30));
                }, Clock.systemUTC());
        assertEquals(1, dispatches.get()); assertEquals(1, report.summary().attemptedEpisodes());
        assertFalse(report.summary().completeValidCoverage()); assertNull(report.summary().formalScores());
        assertFalse(report.summary().episodes().get(0).outcome() instanceof FormalEpisodeOutcome.Scored);
    }
    private FormalBatchPreparation.ReadyBatch ready() throws Exception {
        var source = new FinalSourceGenerator().generateIncompleteSource(new FinalSourceGenerator.GenerationRequest(root.resolve("source"), "f1765489bef0123c".repeat(4)));
        assertEquals(24, source.manifest().implementedRecipeCount()); assertFalse(source.manifest().finalReady());
        var admission = FormalTestAdmission.createWithGeneratedF1(root.resolve("admission"), source.sourceRoot());
        return FormalBatchPreparation.prepare(admission, provider -> new FormalEpisodeRequestFactory.HostCredential(provider, null, "private-scripted-credential"));
    }
    private DockerBenchmarkWorkerProcess dockerWorker(AtomicInteger count) throws IOException {
        return new DockerBenchmarkWorkerProcess(Path.of("/usr/local/bin/docker"), System.getProperty("paicli.test.worker.image"),
                Path.of(System.getProperty("paicli.test.candidate.jar")), Path.of(System.getProperty("paicli.test.runner.jar")),
                request -> new F1DockerControlTest.Script(F1DockerControlTest.Control.values()[count.getAndIncrement() % 9], request.provider(), request.model()));
    }
    private static BenchmarkCoordinatorMain.WorkerExecutor worker(DockerBenchmarkWorkerProcess docker) {
        return new BenchmarkCoordinatorMain.WorkerExecutor() {
            @Override public BenchmarkCoordinatorMain.WorkerExecution execute(BenchmarkProtocol.WorkerRequest request, Path workspace, Path home, Duration timeout) {
                return FormalBatchRunnerTest.successfulWorker(request, false);
            }
            @Override public BenchmarkCoordinatorMain.WorkerExecution executeWithBoundary(BenchmarkProtocol.WorkerRequest request,
                    Path workspace, Path home, Duration timeout, FormalBoundaryBinding.Session session) throws IOException, InterruptedException {
                return docker.executeWithBoundary(request, workspace, home, timeout, session);
            }
        };
    }
}
