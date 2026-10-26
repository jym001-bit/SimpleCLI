package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.eval.benchmark.finalset.generator.E1BindingTestSource;
import com.paicli.eval.benchmark.finalset.generator.FinalSourceRecipeCatalog;
import com.paicli.eval.benchmark.formal.*;
import com.paicli.eval.benchmark.plan.E1FrozenOracle;
import com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Frozen E1 primitive tests. Deliberately no generator registration or FormalBatchRunner admission. */
@Timeout(180)
class E1FormalBindingTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;
    @BeforeEach void privateRoot() throws Exception { temp = temp.toRealPath(); chmod(temp, "rwx------"); }
    @AfterEach void thawTestFiles() throws Exception {
        try (var paths = Files.walk(temp)) { for (Path p : paths.toList()) if (!Files.isSymbolicLink(p)) chmod(p, Files.isDirectory(p) ? "rwx------" : "rw-------"); }
    }
    @Test void validatesGeneratedSourceAndCreatesSingleUseHostSessionsWithoutOpeningAdmission() throws Exception {
        var plan = E1BindingTestSource.generate(temp.resolve("source")); var binding = FormalPlanBinding.capture(plan);
        assertTrue(FormalPlanBinding.supports(plan)); binding.verifyUnchanged();
        var a = binding.newSession(); var b = binding.newSession(); assertNotSame(a, b);
        assertThrows(IOException.class, a::evidence); assertThrows(IOException.class, a::audit);
        var dirs = dirs(temp.resolve("episode"), plan); var request = request(plan, dirs, plan.prompt(), "PLAN", BenchmarkToolProfile.FILE_ONLY, 1_000_000);
        a.begin(request, dirs.workspace, dirs.home); assertTrue(a.audit().events().isEmpty());
        assertThrows(IOException.class, () -> a.begin(request, dirs.workspace, dirs.home));
        a.finish(null); assertThrows(IOException.class, a::evidence); assertThrows(IOException.class, a::audit);
        assertThrows(IOException.class, () -> a.begin(request, dirs.workspace, dirs.home));
        assertEquals(24, FinalSourceRecipeCatalog.implementedIds().size()); assertFalse(FinalSourceRecipeCatalog.missingIds().contains("E1"));
    }
    @Test void rejectsPromptModeToolAndInputDriftBeforeClaimingSession() throws Exception {
        var plan = E1BindingTestSource.generate(temp.resolve("source")); var binding = FormalPlanBinding.capture(plan);
        var wrong = new FormalExecutionPlan.CasePlan(plan.ordinal(), plan.contract(), plan.scoringContract(), plan.scoringContractFile(),
                plan.scoringContractSha256(), plan.prompt() + "changed", BenchmarkRelayProtocol.textSha256(plan.prompt() + "changed"), plan.fixture(), plan.verifier());
        assertThrows(IOException.class, () -> FormalPlanBinding.capture(wrong));
        var dirs = dirs(temp.resolve("episode"), plan);
        for (var request : List.of(request(plan, dirs, plan.prompt() + "changed", "PLAN", BenchmarkToolProfile.FILE_ONLY, 1_000_000),
                request(plan, dirs, plan.prompt(), "REACT", BenchmarkToolProfile.FILE_ONLY, 1_000_000),
                request(plan, dirs, plan.prompt(), "PLAN", BenchmarkToolProfile.LOCAL_COMMAND, 1_000_000),
                request(plan, dirs, plan.prompt(), "PLAN", BenchmarkToolProfile.FILE_ONLY, 200_000)))
            assertThrows(IOException.class, () -> binding.newSession().begin(request, dirs.workspace, dirs.home));
        var request = request(plan, dirs, plan.prompt(), "PLAN", BenchmarkToolProfile.FILE_ONLY, 1_000_000);
        Files.writeString(dirs.workspace.resolve("extra.txt"), "unexpected");
        assertThrows(IOException.class, () -> binding.newSession().begin(request, dirs.workspace, dirs.home));
    }
    @Test void refusesSourcePermissionsHardlinksAndSameBytesInodeReplacement() throws Exception {
        for (String mutation : List.of("permission", "hardlink", "replacement", "content", "parent", "extra-fixture")) {
            var plan = E1BindingTestSource.generate(temp.resolve(mutation)); var binding = FormalPlanBinding.capture(plan);
            Path source = temp.resolve(mutation).resolve(E1FrozenOracle.PATH);
            switch (mutation) {
                case "permission" -> chmod(source, "rw-------");
                case "hardlink" -> Files.createLink(temp.resolve("oracle-hardlink"), source);
                case "replacement" -> {
                    Path replacement = temp.resolve("replacement.json"); Files.copy(source, replacement); chmod(replacement, "r--------");
                    chmod(source.getParent(), "rwx------"); Files.move(replacement, source, StandardCopyOption.REPLACE_EXISTING); chmod(source.getParent(), "r-x------");
                }
                case "content" -> { chmod(source, "rw-------"); Files.writeString(source, "{}"); chmod(source, "r--------"); }
                case "parent" -> chmod(source.getParent(), "r-xr-xr-x");
                case "extra-fixture" -> { chmod(plan.fixture().sourcePath(), "rwx------"); Files.writeString(plan.fixture().sourcePath().resolve("extra.csv"), "unexpected"); }
            }
            assertThrows(IOException.class, binding::verifyUnchanged, mutation);
            assertThrows(IOException.class, binding::newSession, mutation);
        }
    }
    @Test void candidateInputReplacementAndCrossEpisodeEvidenceAreRejected() throws Exception {
        var plan = E1BindingTestSource.generate(temp.resolve("source")); var binding = FormalPlanBinding.capture(plan);
        var dirs = dirs(temp.resolve("episode"), plan); Path left = dirs.workspace.resolve("left.csv");
        chmod(left, "rw-------"); Files.writeString(left, "wrong");
        assertThrows(IOException.class, () -> binding.newSession().begin(request(plan, dirs, plan.prompt(), "PLAN", BenchmarkToolProfile.FILE_ONLY, 1_000_000), dirs.workspace, dirs.home));
        var other = dirs(temp.resolve("other"), plan); var session = binding.newSession();
        session.begin(request(plan, other, plan.prompt(), "PLAN", BenchmarkToolProfile.FILE_ONLY, 1_000_000), other.workspace, other.home);
        assertThrows(IOException.class, () -> session.requirePaths(dirs.episode, dirs.workspace, dirs.home)); session.finish(null);
    }
    @Test void genericWorkersCannotSilentlyIgnoreBoundPlanSession() throws Exception {
        var plan = E1BindingTestSource.generate(temp.resolve("source")); var dirs = dirs(temp.resolve("episode"), plan);
        BenchmarkCoordinatorMain.WorkerExecutor worker = (r, w, h, t) -> { throw new AssertionError("unbound fallback forbidden"); };
        assertThrows(IOException.class, () -> worker.executeWithPlan(request(plan, dirs, plan.prompt(), "PLAN", BenchmarkToolProfile.FILE_ONLY, 1_000_000),
                dirs.workspace, dirs.home, Duration.ofSeconds(10), FormalPlanBinding.capture(plan).newSession()));
    }
    @Test @EnabledIfSystemProperty(named="paicli.test.e1.binding.docker", matches="true")
    void generatedSourceToBoundDockerHostEnvelopeAndIndependentScoring() throws Exception {
        Path root = Path.of(System.getProperty("paicli.test.e1.output")).toRealPath();
        var plan = E1BindingTestSource.generate(root.resolve("source")); var binding = FormalPlanBinding.capture(plan);
        var oracle = (com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(root.resolve("source").resolve(E1FrozenOracle.PATH).toFile());
        var store = BenchmarkArtifactStore.create(root.resolve("runs"), "bound-controls");
        for (var control : E1IndependentReplayTest.Control.values()) {
            var episode = store.episode("E1", control.name().toLowerCase(Locale.ROOT), 1, 1);
            var dirs = dirs(episode, plan); var session = binding.newSession(); var constructions = new AtomicInteger();
            var worker = new DockerBenchmarkWorkerProcess(Path.of("/usr/local/bin/docker"), System.getProperty("paicli.test.worker.image"),
                    Path.of(System.getProperty("paicli.test.candidate.jar")), Path.of(System.getProperty("paicli.test.runner.jar")),
                    ignored -> { constructions.incrementAndGet(); return new E1IndependentReplayTest.Script(oracle, control); });
            var request = request(plan, dirs, plan.prompt(), "PLAN", BenchmarkToolProfile.FILE_ONLY, 1_000_000);
            assertThrows(IOException.class, () -> worker.executeWithPlan(request(plan, dirs, plan.prompt() + "changed", "PLAN", BenchmarkToolProfile.FILE_ONLY, 1_000_000),
                    dirs.workspace, dirs.home, Duration.ofSeconds(30), session));
            assertEquals(0, constructions.get(), "invalid binding must not initialize a provider");
            var execution = worker.executeWithPlan(request, dirs.workspace, dirs.home, Duration.ofSeconds(30), session);
            assertEquals(1, constructions.get()); assertTrue(execution.response().success(), execution.toString());
            assertThrows(IOException.class, () -> worker.executeWithPlan(request, dirs.workspace, dirs.home, Duration.ofSeconds(30), session));
            assertEquals(1, constructions.get(), "reuse must not initialize another provider");
            var snapshot = BenchmarkVerifierWorkspaceSnapshot.create(dirs.workspace, episode.createPrivateDirectory("verifier-workspace"));
            var bundle = FormalVerifierBundleMaterializer.materialize(plan, episode);
            assertThrows(IOException.class, () -> BenchmarkEvidenceEnvelope.writeBoundPlan(episode.directory(), dirs.home, dirs.workspace,
                    1, session, snapshot, bundle, request.apiKey()));
            if (control == E1IndependentReplayTest.Control.CORRECT) {
                assertThrows(IOException.class, () -> BenchmarkEvidenceEnvelope.writeBoundPlan(episode.directory(), dirs.workspace, dirs.home,
                        1, session, snapshot, bundle, "Use Plan mode"), "an exact canary present only in raw Plan content must be rejected");
                Path rejected = episode.directory().resolve(BenchmarkEvidenceEnvelope.DIRECTORY_NAME);
                try (var files = Files.list(rejected)) { assertEquals(0, files.count(), "no secret-bearing envelope may be written"); }
                Files.move(rejected, episode.directory().resolve("rejected-canary-evidence")); // Preserve the empty rejected attempt.
            }
            Path evidence = BenchmarkEvidenceEnvelope.writeBoundPlan(episode.directory(), dirs.workspace, dirs.home, 1, session, snapshot, bundle, request.apiKey());
            var tree = JSON.readTree(evidence.toFile()); assertEquals(5, tree.path("schemaVersion").asInt());
            assertEquals(JSON.readTree(JSON.writeValueAsBytes(session.evidence())), tree.path("plan"));
            assertFalse(tree.has("mockMcp")); assertFalse(tree.has("mockWeb"));
            assertFalse(BenchmarkSecretCanary.containsInTree(episode.directory(), request.apiKey()));
            var verifier = new DockerBenchmarkVerifier(Path.of("/usr/local/bin/docker"), System.getProperty("paicli.test.verifier.image"));
            var result = verifier.verify(new CaseDefinition.VerifierInvocation(bundle.root(), plan.verifier().registeredArguments()),
                    snapshot.directory(), dirs.home, evidence, Duration.ofSeconds(20));
            var record = JSON.createObjectNode().put("control", control.name()).put("providerOrigin", "SCRIPTED_NO_API_SYNTHETIC_USAGE")
                    .put("realProviderCalls", 0).putNull("formalScore").put("publicationEligible", false).put("formalAdmission", false)
                    .put("candidateSha256", worker.candidateJarSha256()).put("runnerSha256", worker.runnerJarSha256())
                    .put("runnerInventorySha256", worker.runnerContentManifestSha256());
            record.set("execution", JSON.valueToTree(execution)); record.set("verification", JSON.valueToTree(result)); episode.writeRun(record);
            assertEquals(0, result.exitCode(), control + ": " + result.stderr()); assertTrue(result.sandboxed());
            assertEquals(E1IndependentReplayTest.expectedPass(control) ? 100 : 0,
                    JSON.readTree(result.stdout()).path("components").get(0).path("earnedPoints").asInt(), control.name());
            var scored = com.paicli.eval.benchmark.scoring.ScoreCalculator.calculate(plan.scoringContract(),
                    com.paicli.eval.benchmark.scoring.VerifierScoringReport.parse(result.stdout()),
                    com.paicli.eval.benchmark.scoring.ScoreCalculator.JudgeAvailability.UNAVAILABLE);
            assertEquals(E1IndependentReplayTest.expectedPass(control) ? 100 : 0, scored.score());
            assertEquals(control == E1IndependentReplayTest.Control.EXTRA_WRITE, scored.hardGate());
            snapshot.verifyUnchanged(); bundle.verifyUnchanged(); binding.verifyUnchanged();
            System.out.println("E1 bound generated Docker control " + control + ": expected score verified; formalAdmission=false; realProviderCalls=0");
        }
        assertEquals(24, FinalSourceRecipeCatalog.implementedIds().size());
    }
    private static Dirs dirs(Path episode, FormalExecutionPlan.CasePlan plan) throws Exception {
        BenchmarkProcessEnvironment.preparePrivateDirectory(episode);
        Path workspace = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("workspace"));
        Path home = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("home"));
        BenchmarkFixtureCopier.copy(plan.fixture().sourcePath(), workspace); return new Dirs(episode, workspace, home);
    }
    private static Dirs dirs(BenchmarkArtifactStore.EpisodeArtifacts episode, FormalExecutionPlan.CasePlan plan) throws Exception {
        Path workspace = episode.createPrivateDirectory("workspace"), home = episode.createPrivateDirectory("home");
        BenchmarkFixtureCopier.copy(plan.fixture().sourcePath(), workspace); return new Dirs(episode.directory(), workspace, home);
    }
    private static BenchmarkProtocol.WorkerRequest request(FormalExecutionPlan.CasePlan plan, Dirs dirs, String prompt, String mode, BenchmarkToolProfile profile, int cap) {
        return new BenchmarkProtocol.WorkerRequest(BenchmarkProtocol.VERSION, "deepseek", "deepseek-v4-flash", null,
                "synthetic-bound-key-no-provider", mode, profile, new BenchmarkProtocol.AgentLimits(200_000, 32, 8, cap, 16_384),
                "2026-09-04", prompt, dirs.workspace.toString(), dirs.home.toString(), dirs.episode.toString());
    }
    private static void chmod(Path path, String mode) throws IOException { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode)); }
    private record Dirs(Path episode, Path workspace, Path home) { }
}
