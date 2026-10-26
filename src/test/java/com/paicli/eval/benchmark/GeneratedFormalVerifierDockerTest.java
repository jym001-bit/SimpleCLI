package com.paicli.eval.benchmark;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.eval.benchmark.finalset.generator.FinalSourceGenerator;
import com.paicli.eval.benchmark.formal.FinalExecutableSuiteContract;
import com.paicli.eval.benchmark.formal.FormalA1TestPlan;
import com.paicli.eval.benchmark.formal.FormalExecutionPlan;
import com.paicli.eval.benchmark.formal.FormalVerifierBundleMaterializer;
import com.paicli.eval.benchmark.scoring.ScoreCalculator;
import com.paicli.eval.benchmark.scoring.ScoringContract;
import com.paicli.eval.benchmark.scoring.VerifierScoringReport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Real networkless verifier containers, but synthetic reference answers/trajectories, no model calls. */
@EnabledIfSystemProperty(named = "paicli.test.verifier.image", matches = "sha256:[0-9a-f]{64}")
class GeneratedFormalVerifierDockerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;

    @AfterEach
    void restoreOnlyTestOwnedPermissions() throws Exception {
        try (var walk = Files.walk(temp)) {
            for (Path p : walk.toList()) if (!Files.isSymbolicLink(p))
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(
                        Files.isDirectory(p) ? "rwx------" : "rw-------"));
        }
    }

    @Test
    void allGeneratedBundlesRunIndependentlyOnReadOnlySnapshots() throws Exception {
        Path parent = temp.toRealPath();
        Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"));
        var generated = new FinalSourceGenerator().generateIncompleteSource(
                new FinalSourceGenerator.GenerationRequest(parent.resolve("source"), "fed0123456789abc".repeat(4)));
        Path root = generated.sourceRoot();
        // Only the bundle materializer is under test, not fixture admission. This synthetic
        // fixture descriptor is deliberately unused by the real verifier path below.
        var unusedFixture = FormalA1TestPlan.create(parent.resolve("test-plan")).cases().get(0).fixture();
        var store = BenchmarkArtifactStore.create(parent.resolve("artifacts"), "reference-bundle-controls");
        var docker = new DockerBenchmarkVerifier(Path.of(System.getProperty(
                "paicli.test.docker.executable", "/usr/local/bin/docker")),
                System.getProperty("paicli.test.verifier.image"));
        List<Executable> checks = new ArrayList<>();
        for (var item : generated.manifest().cases()) {
            if (item.caseContractPath().isEmpty()) continue;
            var contract = FinalExecutableSuiteContract.CaseContract.load(root.resolve(item.caseContractPath()));
            var scoring = ScoringContract.load(root.resolve(contract.scoringContractPath()));
            List<FormalExecutionPlan.VerifierDependency> dependencies = new ArrayList<>();
            for (String path : contract.verifierDependencyPaths()) {
                Path file = root.resolve(path);
                boolean entry = path.equals(contract.verifierEntryPath());
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(entry ? "r-x------" : "r--------"));
                dependencies.add(new FormalExecutionPlan.VerifierDependency(path, file,
                        entry ? "0500" : "0400", Files.size(file), hash(Files.readAllBytes(file))));
            }
            var invocation = new FormalExecutionPlan.VerifierCommand(root,
                    List.of(contract.verifierEntryPath(), CaseDefinition.WORKSPACE_PLACEHOLDER,
                            CaseDefinition.EVIDENCE_PLACEHOLDER),
                    contract.verifierEntryPath(), root.resolve(contract.verifierEntryPath()),
                    contract.verifierSha256(), dependencies, contract.verifierBundleSha256());
            String prompt = Files.readString(root.resolve(item.publicPromptPath()));
            var plan = new FormalExecutionPlan.CasePlan(1, contract, scoring,
                    root.resolve(contract.scoringContractPath()), contract.scoringContractSha256(),
                    prompt, hash(prompt.getBytes(StandardCharsets.UTF_8)), unusedFixture, invocation);
            checks.add(() -> {
                var artifact = store.episode(item.id(), "reference-control", 1, 1);
                Path workspace = artifact.createPrivateDirectory("workspace");
                Path home = artifact.createPrivateDirectory("home");
                BenchmarkFixtureCopier.copy(root.resolve(item.referenceWorkspacePath()), workspace);
                var snapshot = BenchmarkVerifierWorkspaceSnapshot.create(workspace,
                        artifact.createPrivateDirectory("verifier-workspace"));
                var bundle = FormalVerifierBundleMaterializer.materialize(plan, artifact);
                assertFalse(Files.exists(bundle.root().resolve("references")));
                assertFalse(Files.exists(bundle.root().resolve("fixtures")));
                try (var files = Files.list(bundle.root().resolve("validators/final/_private/oracles"))) {
                    assertEquals(List.of(item.id() + ".json"), files.map(p -> p.getFileName().toString()).toList());
                }
                var reference = JSON.readTree(root.resolve("references/final/" + item.id() + "/evidence.json").toFile());
                Path evidence;
                if (List.of("E1", "F1", "F2", "F3").contains(item.id())) {
                    // Explicit synthetic-reference test input, not a host Session or production envelope writer.
                    var input = ((com.fasterxml.jackson.databind.node.ObjectNode) reference).deepCopy();
                    input.put("verifierWorkspaceTreeSha256", snapshot.treeSha256()).put("verifierWorkspaceFileCount", snapshot.fileCount())
                            .put("verifierWorkspaceTotalBytes", snapshot.totalBytes()).put("verifierBundleTreeSha256", bundle.bundleSha256())
                            .put("verifierBundleFileCount", bundle.dependencies().size()).put("verifierBundleTotalBytes",
                                    bundle.dependencies().stream().mapToLong(FormalVerifierBundleMaterializer.MaterializedDependency::size).sum());
                    evidence = artifact.createPrivateDirectory("synthetic-reference-evidence").resolve("evidence.json");
                    Files.createFile(evidence, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                    JSON.writeValue(evidence.toFile(), input);
                    Files.setPosixFilePermissions(evidence, PosixFilePermissions.fromString("r--------"));
                } else {
                var metrics = JSON.treeToValue(reference.path("llmMetrics"), TracingLlmClient.Metrics.class);
                List<BenchmarkToolExecutionEvidence> events = JSON.convertValue(reference.path("toolExecutions"),
                        new TypeReference<>() {});
                evidence = BenchmarkEvidenceEnvelope.writeFormal(artifact.directory(), snapshot.directory(),
                        home, item.id(), 1, CaseDefinition.Mode.REACT,
                        BenchmarkToolProfile.valueOf(item.toolProfile()), reference.path("answer").asText(),
                        metrics, snapshot, bundle, events,
                        reference.has("mockMcp") ? JSON.treeToValue(reference.path("mockMcp"),
                                FormalMockMcpBinding.MockEvidence.class) : null,
                        reference.has("mockWeb") ? JSON.treeToValue(reference.path("mockWeb"),
                                FormalMockWebBinding.MockEvidence.class) : null, null);
                }
                Path originalValidators = root.resolve("validators");
                Files.setPosixFilePermissions(originalValidators, PosixFilePermissions.fromString("---------"));
                BenchmarkVerifier.Result verification;
                try {
                    verification = docker.verify(new CaseDefinition.VerifierInvocation(bundle.root(),
                                    invocation.registeredArguments()), snapshot.directory(), home, evidence,
                            Duration.ofSeconds(120));
                } finally {
                    Files.setPosixFilePermissions(originalValidators, PosixFilePermissions.fromString("rwx------"));
                }
                bundle.verifyUnchanged(); snapshot.verifyUnchanged();
                assertEquals(BenchmarkVerifier.Status.PASSED, verification.status(), item.id() + ": " + verification.stderr());
                var report = VerifierScoringReport.parse(verification.stdout());
                var score = ScoreCalculator.calculate(scoring, report, ScoreCalculator.JudgeAvailability.UNAVAILABLE);
                System.out.println("Reference bundle control: " + item.id() + " score=" + score.score());
                if (scoring.requiresJudge()) assertNull(score.score(), item.id());
                else assertEquals(item.id().equals("B5") ? 20 : 100, score.score(), item.id());
                assertFalse(score.hardGate(), item.id());
            });
        }
        assertEquals(24, checks.size());
        assertAll("generated verifier dependency and read-only snapshot controls", checks);
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
