package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.PaiCliConfig;
import com.paicli.eval.benchmark.finalset.generator.FinalSourceGenerator;
import com.paicli.eval.benchmark.formal.*;
import com.paicli.eval.benchmark.mock.D3FrozenOracle;
import com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol;
import com.paicli.eval.benchmark.scoring.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in paid development sibling. Real providers + Docker Worker + independent verifier, never a formal batch. */
@EnabledIfSystemProperty(named = "paicli.test.d3.live", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class D3LiveDockerDiagnosticTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEVELOPMENT_SEED = "d3d1a690".repeat(8);
    private Path root, source;
    private FormalExecutionPlan.CasePlan plan;
    private FormalMockMcpBinding binding;
    private BenchmarkArtifactStore store;

    @BeforeAll void prepareSharedDevelopmentSourceBeforeLoadingAnyCredentials() throws Exception {
        root = Path.of(System.getProperty("paicli.test.d3.output")).toRealPath();
        assertFalse(root.startsWith(Path.of("").toRealPath()));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(root));
        var generated = new FinalSourceGenerator().generateIncompleteSource(new FinalSourceGenerator.GenerationRequest(root.resolve("source"), DEVELOPMENT_SEED));
        source = generated.sourceRoot(); assertFalse(generated.manifest().finalReady());
        var contract = FinalExecutableSuiteContract.CaseContract.load(source.resolve("provenance/final/D3/execution-contract.json"));
        var scoring = ScoringContract.load(source.resolve(contract.scoringContractPath()));
        List<FormalExecutionPlan.VerifierDependency> dependencies = new ArrayList<>();
        for (String relative : contract.verifierDependencyPaths()) {
            Path file = source.resolve(relative); boolean executable = relative.equals(contract.verifierEntryPath());
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(executable ? "r-x------" : "r--------"));
            dependencies.add(new FormalExecutionPlan.VerifierDependency(relative, file, executable ? "0500" : "0400", Files.size(file), hash(file)));
        }
        var fixture = stageAdmittedFixture(source, root.resolve("admitted-fixture"));
        var invocation = new FormalExecutionPlan.VerifierCommand(source,
                List.of(contract.verifierEntryPath(), "{workspace}", "{evidence}"), contract.verifierEntryPath(), source.resolve(contract.verifierEntryPath()),
                contract.verifierSha256(), dependencies, contract.verifierBundleSha256());
        String prompt = Files.readString(source.resolve("prompts/final/D3.md"));
        plan = new FormalExecutionPlan.CasePlan(1, contract, scoring, source.resolve(contract.scoringContractPath()),
                contract.scoringContractSha256(), prompt, hash(prompt.getBytes(StandardCharsets.UTF_8)), fixture, invocation);
        binding = FormalMockMcpBinding.capture(plan);
        store = BenchmarkArtifactStore.create(root.resolve("artifacts"), "d3-live-diagnostic");
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("kind", "D3_DEVELOPMENT_SIBLING_NOT_FORMAL_DATASET"); manifest.put("publishable", false); manifest.put("formalScores", null);
        manifest.put("caseId", "D3"); manifest.put("generatorImplementedRecipes", generated.manifest().implementedRecipeCount());
        manifest.put("sourceTreeSha256BeforePermissionFreeze", generated.completeTreeSha256());
        manifest.put("mockSourceSha256", hash(source.resolve(D3FrozenOracle.PATH))); manifest.put("caseContract", contract);
        manifest.put("promptSha256", plan.promptSha256()); manifest.put("fixtureSha256", fixture.snapshotSha256());
        manifest.put("checkOnly", Boolean.getBoolean("paicli.test.d3.check"));
        write(root.resolve("diagnostic-manifest.json"), manifest);
    }

    @ParameterizedTest
    @CsvSource({"deepseek,deepseek-v4-flash", "hunyuan,hy4-preview", "glm,glm-5.3-flash"})
    void realDockerAgentSchedulesOnlyAfterHostApproval(String provider, String model) throws Exception {
        var artifact = store.episode("D3", provider + "-" + model, 1, 1);
        Path episode = artifact.directory();
        var report = new LinkedHashMap<String, Object>();
        report.put("schemaVersion", 1); report.put("kind", "D3_LIVE_DOCKER_DIAGNOSTIC_NOT_FORMAL");
        report.put("publishable", false); report.put("formalScore", null); report.put("diagnosticScore", null);
        report.put("provider", provider); report.put("model", model); report.put("mockSourceSha256", hash(source.resolve(D3FrozenOracle.PATH)));
        report.put("promptSha256", plan.promptSha256()); report.put("verifierBundleSha256", plan.verifier().bundleSha256());
        binding.verifyUnchanged();
        var credential = FormalBenchmarkCoordinatorMain.credential(PaiCliConfig.load(), provider);
        if (credential == null) {
            report.put("status", "CREDENTIAL_UNAVAILABLE"); write(episode.resolve("result.json"), report);
            System.out.println("D3 credential unavailable: " + provider);
            Assumptions.abort("provider credential unavailable: " + provider);
        }
        if ("hunyuan".equals(provider)) assertTrue(credential.baseUrl() == null || "https://tokenhub.tencentmaas.com/v1".equals(credential.baseUrl()));
        String image = System.getProperty("paicli.test.worker.image"), verifierImage = System.getProperty("paicli.test.verifier.image");
        assertNotNull(verifierImage);
        var worker = new DockerBenchmarkWorkerProcess(Path.of("/usr/local/bin/docker"), image,
                Path.of(System.getProperty("paicli.test.candidate.jar")), Path.of(System.getProperty("paicli.test.runner.jar")));
        report.put("candidateJarSha256", worker.candidateJarSha256()); report.put("runnerJarSha256", worker.runnerJarSha256());
        report.put("runnerInventorySha256", worker.runnerContentManifestSha256()); report.put("relayProtocolVersion", BenchmarkRelayProtocol.VERSION);
        report.put("workerImage", image); report.put("verifierImage", verifierImage);
        if (Boolean.getBoolean("paicli.test.d3.check")) {
            report.put("status", "CHECK_READY_NO_API_CALL"); write(episode.resolve("result.json"), report);
            System.out.println("D3 credential/artifact check ready: " + provider + " (no API call)"); return;
        }
        var mock = binding.newService();
        Path workspace = artifact.createPrivateDirectory("workspace"), home = artifact.createPrivateDirectory("home");
        var materializedFixture = FormalFixtureMaterializer.materialize(plan.fixture(), workspace);
        materializedFixture.verifyReady();
        write(episode.resolve("fixture-before-worker.json"), Map.of("snapshotSha256", materializedFixture.frozenSnapshotSha256(),
                "files", plan.fixture().files()));
        var limits = new BenchmarkProtocol.AgentLimits(plan.tokenBudget(), plan.hardMaxIterations(), plan.stagnationWindow(), 1_000_000, 16_384);
        var request = new BenchmarkProtocol.WorkerRequest(BenchmarkProtocol.VERSION, provider, model, credential.baseUrl(), credential.apiKey(),
                "REACT", BenchmarkToolProfile.MOCK_MCP, limits, "2026-09-04", plan.prompt(), workspace.toString(), home.toString(), episode.toString());
        report.put("agentLimits", limits); report.put("timeoutSeconds", plan.timeoutSeconds()); report.put("status", "RUNNING");
        write(episode.resolve("started.json"), report);
        System.out.println("D3 live started: " + provider + " / " + model);
        var execution = worker.executeWithMock(request, workspace, home, Duration.ofSeconds(plan.timeoutSeconds()), mock);
        var mockEvidence = binding.evidence(mock);
        assertFalse(BenchmarkSecretCanary.contains(credential.apiKey(), JSON.writeValueAsString(execution), JSON.writeValueAsString(mockEvidence)));
        assertFalse(BenchmarkSecretCanary.containsInTree(episode, credential.apiKey()));
        report.put("execution", execution); report.put("mockMcp", mockEvidence);
        write(episode.resolve("execution.json"), report);
        var response = execution.response();
        var metrics = response == null ? null : response.metrics();
        String defect = BenchmarkProviderEvidenceGate.invalidEvaluationFailureType(request, metrics);
        report.put("providerEvidenceFailure", defect);
        try {
            binding.verifyUnchanged();
            var disposition = BenchmarkFailureClassifier.classifyWorker(execution);
            if (defect != null || response != null && BenchmarkProviderEvidenceGate.isEvaluationInvalidFailureType(response.errorType())
                    || disposition == BenchmarkFailureClassifier.Disposition.INFRA_ERROR || disposition == BenchmarkFailureClassifier.Disposition.SECURITY_HARD_GATE) {
                report.put("status", "EVALUATION_INVALID");
            } else if (disposition == BenchmarkFailureClassifier.Disposition.SCORED_FAILURE || BenchmarkProviderEvidenceGate.failureType(request, metrics) != null) {
                report.put("status", "DIAGNOSTIC_COMPLETED"); report.put("diagnosticScore", 0); report.put("diagnosticSatisfied", false);
            } else {
                var snapshot = BenchmarkVerifierWorkspaceSnapshot.create(workspace, artifact.createPrivateDirectory("verifier-workspace"));
                var bundle = FormalVerifierBundleMaterializer.materialize(plan, artifact);
                Path envelope = BenchmarkEvidenceEnvelope.writeFormal(episode, snapshot.directory(), home, "D3", 1, CaseDefinition.Mode.REACT,
                        BenchmarkToolProfile.MOCK_MCP, response.answer(), metrics, snapshot, bundle, execution.toolExecutions(), mockEvidence, credential.apiKey());
                String evidenceSha = hash(envelope);
                var verifier = new DockerBenchmarkVerifier(Path.of("/usr/local/bin/docker"), verifierImage);
                var verification = verifier.verify(new CaseDefinition.VerifierInvocation(bundle.root(), plan.verifier().registeredArguments()),
                        snapshot.directory(), home, envelope, Duration.ofSeconds(60));
                report.put("verifier", verification); report.put("evidenceSha256", evidenceSha);
                bundle.verifyUnchanged(); snapshot.verifyUnchanged(); binding.verifyUnchanged(); assertEquals(evidenceSha, hash(envelope));
                if (verification.status() != BenchmarkVerifier.Status.PASSED || verification.stdoutTruncated() || verification.stderrTruncated()) {
                    report.put("status", "EVALUATION_INVALID"); report.put("verifierEvidenceFailure", "INDEPENDENT_REPLAY_REJECTED");
                } else {
                    var scored = ScoreCalculator.calculate(plan.scoringContract(), VerifierScoringReport.parse(verification.stdout()), ScoreCalculator.JudgeAvailability.UNAVAILABLE);
                    report.put("status", "DIAGNOSTIC_COMPLETED"); report.put("diagnosticScore", scored.score());
                    report.put("diagnosticSatisfied", scored.strictSuccess()); report.put("scoreResult", scored);
                }
            }
        } catch (Exception error) {
            report.put("status", "EVALUATION_INVALID"); report.put("verificationErrorType", error.getClass().getSimpleName());
        }
        assertFalse(BenchmarkSecretCanary.contains(credential.apiKey(), JSON.writeValueAsString(report)));
        write(episode.resolve("result.json"), report);
        System.out.println("D3 live diagnostic " + provider + ": " + report.get("status") + ", score=" + report.get("diagnosticScore") + ", publishable=false");
        assertEquals("DIAGNOSTIC_COMPLETED", report.get("status"), "invalid real episode; preserve and inspect private evidence");
    }

    /** Generator bookkeeping stays outside Candidate input; preserve the complete generated source untouched. */
    static FormalExecutionPlan.FixtureSnapshot stageAdmittedFixture(Path source, Path destination) throws Exception {
        String fixturePath = "fixtures/final/D3";
        Path original = source.resolve(fixturePath + "/README.md");
        Files.createDirectory(destination, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path readme = Files.copy(original, destination.resolve("README.md"));
        assertEquals(hash(original), hash(readme));
        Files.setPosixFilePermissions(readme, PosixFilePermissions.fromString("r--------"));
        Files.setPosixFilePermissions(destination, PosixFilePermissions.fromString("r-x------"));
        var file = new FormalExecutionPlan.FixtureFile(fixturePath + "/README.md", hash(readme), Files.size(readme), "0400");
        String identity = "paicli-formal-fixture-snapshot-v1\0DIRECTORY\0" + fixturePath + "\n"
                + file.frozenPath() + "\0" + file.sha256() + "\0" + file.mode() + "\0" + file.size() + "\n";
        return new FormalExecutionPlan.FixtureSnapshot(fixturePath, fixturePath, destination,
                FormalBenchmarkPreflight.FixtureKind.DIRECTORY, hash(identity.getBytes(StandardCharsets.UTF_8)), 1, file.size(), List.of(file));
    }

    private static String hash(Path path) throws Exception { return hash(Files.readAllBytes(path)); }
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static void write(Path path, Object value) throws Exception {
        byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        try (var output = Files.newByteChannel(path, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            var buffer = java.nio.ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) output.write(buffer);
        }
    }
}
