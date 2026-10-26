package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.eval.benchmark.finalset.generator.FinalSourceGenerator;
import com.paicli.eval.benchmark.formal.*;
import com.paicli.eval.benchmark.plan.E1FrozenOracle;
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

/** One catalog-generated E1 and 27 synthetic cases; no production admission, provider calls or model scores. */
@Timeout(180)
class E1FormalIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;
    @BeforeEach void privateRoot() throws Exception { temp = temp.toRealPath(); chmod(temp, "rwx------"); }
    @AfterEach void thaw() throws Exception {
        try (var paths = Files.walk(temp)) {
            for (Path p : paths.toList()) if (!Files.isSymbolicLink(p)) chmod(p, Files.isDirectory(p) ? "rwx------" : "rw-------");
        }
    }

    @Test void capturesE1BeforeCredentialsAndCreatesFreshSingleUseSessionsForNineSlots() throws Exception {
        var ready = ready(temp);
        var requests = ready.requests().stream().filter(r -> r.key().caseId().equals("E1")).toList();
        assertEquals(9, requests.size());
        for (var request : requests) {
            assertEquals("PLAN", request.mode().toUpperCase(Locale.ROOT)); assertEquals(1800, request.timeoutSeconds());
            assertNull(request.mockBinding()); assertNull(request.webBinding()); assertNotNull(request.planBinding());
            var a = request.planBinding().newSession(); var b = request.planBinding().newSession();
            assertNotSame(a, b); assertTrue(a.retainedAudit().events().isEmpty()); assertTrue(b.retainedAudit().providerTurns().isEmpty());
        }
        corrupt(ready);
        assertThrows(IOException.class, () -> requests.get(0).planBinding().newSession());
        var credentials = new AtomicInteger();
        assertThrows(FormalEpisodeRequestFactory.PreparationException.class, () -> {
            for (var episode : ready.plan().episodes()) FormalEpisodeRequestFactory.validateCapabilities(ready.plan(), episode);
            credentials.incrementAndGet();
        });
        assertEquals(0, credentials.get());
    }

    @Test void failedBindingLaunchAndCanaryNeverBecomeCandidateScores() throws Exception {
        for (String fault : List.of("source-drift", "unbound", "different-result", "launch", "canary")) {
            Path root = BenchmarkProcessEnvironment.preparePrivateDirectory(temp.resolve(fault)); var ready = ready(root);
            var report = FormalBatchRunner.run(ready, root.resolve("output"), fault, worker((r, w, h, s) -> {
                if (fault.equals("unbound")) return FormalBatchRunnerTest.successfulWorker(r, true);
                s.begin(r, w, h);
                var audit = s.audit();
                audit.begin("planner", List.of(LlmClient.Message.system("synthetic planner"),
                        LlmClient.Message.user("请为以下任务制定执行计划：\n" + r.prompt())), List.of());
                audit.response(new LlmClient.ChatResponse("assistant", fault.equals("canary") ? r.apiKey() : "{\"tasks\":[]}",
                        null, List.of(), 10, 5, 0, r.model(), true)); audit.end();
                var result = FormalBatchRunnerTest.successfulWorker(r, true);
                s.finish(fault.equals("launch") ? null : result);
                if (fault.equals("launch")) throw new IOException("scripted launch failure");
                if (fault.equals("source-drift")) corrupt(ready);
                return fault.equals("different-result") ? FormalBatchRunnerTest.successfulWorker(r, true) : result;
            }), (a, b, c, d, e) -> { throw new AssertionError("invalid attempt must not reach verifier"); }, Clock.systemUTC());
            assertEquals(1, report.summary().attemptedEpisodes(), fault); assertNull(report.summary().formalScores());
            assertNull(report.summary().observedMeanWeightedScores()); assertFalse(report.summary().completeValidCoverage());
            var episode = report.summary().episodes().get(0);
            switch (fault) {
                case "source-drift" -> assertInstanceOf(FormalEpisodeOutcome.Dataset.class, episode.outcome());
                case "launch" -> assertInstanceOf(FormalEpisodeOutcome.Infra.class, episode.outcome());
                case "canary" -> {
                    assertInstanceOf(FormalEpisodeOutcome.Security.class, episode.outcome()); assertNull(episode.planAudit());
                    assertFalse(BenchmarkSecretCanary.containsInTree(report.directory(), "private-test-credential"));
                }
                default -> assertInstanceOf(FormalEpisodeOutcome.EvaluationDefect.class, episode.outcome());
            }
            if (!Set.of("canary", "unbound").contains(fault)) assertEquals(1, episode.planAudit().providerTurns().size());
        }
    }

    @Test void terminalCandidateFailuresDoNotRequireCompletePlanTranscriptsAndDoNotStopBatch() throws Exception {
        var ready = ready(temp); var dispatched = new AtomicInteger();
        var report = FormalBatchRunner.run(ready, temp.resolve("output"), "e1-terminals", worker((r, w, h, s) -> {
            s.begin(r, w, h);
            var result = dispatched.getAndIncrement() % 2 == 0 ? FormalBatchRunnerTest.successfulWorker(r, true)
                    : BenchmarkCoordinatorMain.WorkerExecution.timeout(1800_000, "scripted timeout",
                    FormalBatchRunnerTest.successfulWorker(r, false).response().metrics());
            s.finish(result); return result;
        }), (invocation, workspace, home, evidence, timeout) -> {
            String id = JSON.readTree(evidence.toFile()).path("caseId").asText(); assertNotEquals("E1", id);
            return FormalBatchRunnerTest.verification(ready.plan().requireCase(id), 100, false, 0);
        }, Clock.systemUTC());
        assertEquals(9, dispatched.get()); assertEquals(252, report.summary().attemptedEpisodes());
        assertTrue(report.summary().completeValidCoverage()); assertFalse(report.summary().publishable()); assertNull(report.summary().formalScores());
        for (var episode : report.summary().episodes()) if (episode.key().caseId().equals("E1")) {
            assertEquals(0, assertInstanceOf(FormalEpisodeOutcome.Scored.class, episode.outcome()).score());
            assertNotNull(episode.planAudit()); assertNull(episode.evidenceSha256());
        }
    }

    @Test @EnabledIfSystemProperty(named="paicli.test.e1.batch.docker", matches="true")
    void nineBoundDockerWorkersUseFormalLoopAndIndependentVerifierWithSyntheticProvidersOnly() throws Exception {
        Path root = Path.of(System.getProperty("paicli.test.e1.batch.output")).toRealPath();
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(root));
        try (var paths = Files.list(root)) { assertEquals(0, paths.count()); }
        var ready = ready(root);
        var oracle = (com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(ready.plan().artifacts().frozenRoot().resolve(E1FrozenOracle.PATH).toFile());
        var controls = List.of(E1IndependentReplayTest.Control.CORRECT, E1IndependentReplayTest.Control.WRONG_MERGE,
                E1IndependentReplayTest.Control.INVALID_DESCRIPTION, E1IndependentReplayTest.Control.MISSING_DESCRIPTION,
                E1IndependentReplayTest.Control.NBSP_ID, E1IndependentReplayTest.Control.INVALID_GRAPH,
                E1IndependentReplayTest.Control.EMPTY_MERGE_AFTER_WRITE, E1IndependentReplayTest.Control.MISSING_READ,
                E1IndependentReplayTest.Control.EXTRA_WRITE);
        var dispatches = new AtomicInteger(); var verifications = new AtomicInteger();
        var dockerWorker = new DockerBenchmarkWorkerProcess(Path.of("/usr/local/bin/docker"), System.getProperty("paicli.test.worker.image"),
                Path.of(System.getProperty("paicli.test.candidate.jar")), Path.of(System.getProperty("paicli.test.runner.jar")),
                r -> new E1IndependentReplayTest.Script(oracle, controls.get(dispatches.getAndIncrement()), r.provider(), r.model()));
        var dockerVerifier = new DockerBenchmarkVerifier(Path.of("/usr/local/bin/docker"), System.getProperty("paicli.test.verifier.image"));
        var report = FormalBatchRunner.run(ready, root.resolve("output"), "e1-scripted-batch", worker((r, w, h, s) -> {
            assertFalse(Files.exists(w.getParent().resolve("trusted-verifier")));
            return dockerWorker.executeWithPlan(r, w, h, Duration.ofSeconds(40), s);
        }), (invocation, workspace, home, evidence, timeout) -> {
            var envelope = JSON.readTree(evidence.toFile()); String id = envelope.path("caseId").asText();
            if (!id.equals("E1")) return FormalBatchRunnerTest.verification(ready.plan().requireCase(id), 100, false, 0);
            verifications.incrementAndGet(); assertEquals(5, envelope.path("schemaVersion").asInt());
            assertTrue(envelope.path("plan").path("audit").path("providerTurns").isArray());
            assertFalse(envelope.has("mockMcp")); assertFalse(envelope.has("mockWeb"));
            return dockerVerifier.verify(invocation, workspace, home, evidence, Duration.ofSeconds(25));
        }, Clock.systemUTC());
        var evidence = JSON.createObjectNode().put("providerOrigin", "SCRIPTED_NO_API_SYNTHETIC_USAGE")
                .put("realProviderCalls", 0).put("actualDockerCandidates", dispatches.get()).put("actualDockerVerifiers", verifications.get())
                .put("syntheticOtherEpisodes", 243).put("productionAdmission", false).put("publicationEligible", false).putNull("formalScore")
                .put("candidateSha256", dockerWorker.candidateJarSha256()).put("runnerSha256", dockerWorker.runnerJarSha256())
                .put("runnerInventorySha256", dockerWorker.runnerContentManifestSha256());
        evidence.set("controls", JSON.valueToTree(controls)); evidence.set("summary", JSON.valueToTree(report.summary()));
        Path result = root.resolve("batch-control-result.json"); JSON.writeValue(result.toFile(), evidence); chmod(result, "rw-------");
        assertEquals(252, report.summary().attemptedEpisodes(), report.summary().toString());
        assertEquals(9, dispatches.get()); assertEquals(9, verifications.get());
        assertTrue(report.summary().completeValidCoverage()); assertFalse(report.summary().publishable()); assertNull(report.summary().formalScores());
        var episodes = report.summary().episodes().stream().filter(e -> e.key().caseId().equals("E1")).toList();
        assertEquals(controls.stream().map(c -> E1IndependentReplayTest.expectedPass(c) ? 100 : 0).toList(),
                episodes.stream().map(e -> assertInstanceOf(FormalEpisodeOutcome.Scored.class, e.outcome()).score()).toList());
        for (var episode : episodes) { assertNotNull(episode.planAudit()); assertNotNull(episode.evidenceSha256()); }
    }

    private static FormalBatchPreparation.ReadyBatch ready(Path root) throws Exception {
        new FinalSourceGenerator().generateIncompleteSource(new FinalSourceGenerator.GenerationRequest(
                root.resolve("source"), "abcdef0123456789".repeat(4)));
        try (var files = Files.list(root.resolve("source/fixtures/final/E1"))) {
            assertEquals(Set.of("left.csv", "right.csv"), files.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        }
        return FormalBatchPreparation.prepare(FormalTestAdmission.createWithGeneratedE1(root.resolve("admission"), root.resolve("source")),
                provider -> new FormalEpisodeRequestFactory.HostCredential(provider, null, "private-test-credential"));
    }
    private static void corrupt(FormalBatchPreparation.ReadyBatch ready) throws IOException {
        Path source = ready.plan().artifacts().frozenRoot().resolve(E1FrozenOracle.PATH);
        chmod(source, "rw-------"); Files.writeString(source, "{}"); chmod(source, "r--------");
    }
    @FunctionalInterface private interface Dispatch {
        BenchmarkCoordinatorMain.WorkerExecution run(BenchmarkProtocol.WorkerRequest request, Path workspace, Path home,
                FormalPlanBinding.Session session) throws IOException, InterruptedException;
    }
    private static BenchmarkCoordinatorMain.WorkerExecutor worker(Dispatch dispatch) {
        return new BenchmarkCoordinatorMain.WorkerExecutor() {
            public BenchmarkCoordinatorMain.WorkerExecution execute(BenchmarkProtocol.WorkerRequest r, Path w, Path h, Duration t) {
                assertFalse(r.prompt().startsWith("# E1 "), "no unbound E1 fallback; other synthetic slots may use PLAN");
                return FormalBatchRunnerTest.successfulWorker(r, false);
            }
            public BenchmarkCoordinatorMain.WorkerExecution executeWithPlan(BenchmarkProtocol.WorkerRequest r, Path w, Path h, Duration t,
                    FormalPlanBinding.Session s) throws IOException, InterruptedException { return dispatch.run(r, w, h, s); }
        };
    }
    private static void chmod(Path p, String mode) throws IOException { Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(mode)); }
}
