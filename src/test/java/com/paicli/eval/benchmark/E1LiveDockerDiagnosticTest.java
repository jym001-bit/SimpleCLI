package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.PaiCliConfig;
import com.paicli.eval.benchmark.finalset.generator.FinalSourceGenerator;
import com.paicli.eval.benchmark.formal.*;
import com.paicli.eval.benchmark.plan.E1FrozenOracle;
import com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol;
import com.paicli.eval.benchmark.scoring.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Paid opt-in diagnostic of one development sibling, never a 28-case formal admission. */
@EnabledIfSystemProperty(named = "paicli.test.e1.live", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E1LiveDockerDiagnosticTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEVELOPMENT_SEED = "e1d1a690".repeat(8);
    private Path root;
    private PreparedSource prepared;
    private FormalPlanBinding binding;
    private BenchmarkArtifactStore store;

    @BeforeAll void prepareSharedSourceBeforeLoadingAnyCredentials() throws Exception {
        root = Path.of(System.getProperty("paicli.test.e1.output"));
        prepared = prepareSource(root);
        binding = FormalPlanBinding.capture(prepared.plan());
        store = BenchmarkArtifactStore.create(root.resolve("artifacts"), "e1-live-diagnostic");
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("kind", "E1_DEVELOPMENT_SIBLING_NOT_FORMAL_DATASET");
        manifest.put("publishable", false); manifest.put("formalScore", null);
        manifest.put("caseId", "E1"); manifest.put("generatorImplementedRecipes", prepared.implementedRecipes());
        manifest.put("sourceTreeSha256BeforePermissionFreeze", prepared.generatedTreeSha256());
        manifest.put("sourceSha256", hash(prepared.source().resolve(E1FrozenOracle.PATH)));
        manifest.put("caseContract", prepared.plan().contract());
        manifest.put("promptSha256", prepared.plan().promptSha256());
        manifest.put("fixtureSha256", prepared.plan().fixture().snapshotSha256());
        manifest.put("checkOnly", Boolean.getBoolean("paicli.test.e1.check"));
        write(root.resolve("diagnostic-manifest.json"), manifest);
    }

    @ParameterizedTest
    @CsvSource({"deepseek,deepseek-v4-flash", "hunyuan,hy4-preview", "glm,glm-5.3-flash"})
    void realDockerPlanProducesVerifiableDagAndMergedArtifact(String provider, String model) throws Exception {
        var plan = prepared.plan();
        var artifact = store.episode("E1", provider + "-" + model, 1, 1);
        Path episode = artifact.directory();
        var report = new LinkedHashMap<String, Object>();
        report.put("schemaVersion", 1); report.put("kind", "E1_LIVE_DOCKER_DIAGNOSTIC_NOT_FORMAL");
        report.put("publishable", false); report.put("formalScore", null); report.put("diagnosticScore", null);
        report.put("provider", provider); report.put("model", model);
        report.put("sourceSha256", hash(prepared.source().resolve(E1FrozenOracle.PATH)));
        report.put("promptSha256", plan.promptSha256()); report.put("verifierBundleSha256", plan.verifier().bundleSha256());
        report.put("credentialPresent", false); report.put("providerAuthenticationVerified", false);
        report.put("workerDispatchAttempted", false);
        binding.verifyUnchanged();
        var credential = FormalBenchmarkCoordinatorMain.credential(PaiCliConfig.load(), provider);
        if (credential == null) {
            report.put("status", "CREDENTIAL_UNAVAILABLE"); write(episode.resolve("result.json"), report);
            Assumptions.abort("E1 provider credential unavailable: " + provider);
        }
        report.put("credentialPresent", true);
        if ("hunyuan".equals(provider))
            assertTrue(credential.baseUrl() == null || "https://tokenhub.tencentmaas.com/v1".equals(credential.baseUrl()));
        String image = System.getProperty("paicli.test.worker.image");
        String verifierImage = System.getProperty("paicli.test.verifier.image");
        Path docker = Path.of("/usr/local/bin/docker");
        assertTrue(Files.isExecutable(docker));
        var worker = new DockerBenchmarkWorkerProcess(docker, image,
                Path.of(System.getProperty("paicli.test.candidate.jar")), Path.of(System.getProperty("paicli.test.runner.jar")));
        var verifier = new DockerBenchmarkVerifier(docker, verifierImage);
        report.put("candidateJarSha256", worker.candidateJarSha256()); report.put("runnerJarSha256", worker.runnerJarSha256());
        report.put("runnerInventorySha256", worker.runnerContentManifestSha256());
        report.put("relayProtocolVersion", BenchmarkRelayProtocol.VERSION);
        report.put("workerImage", image); report.put("verifierImage", verifierImage);
        var limits = new BenchmarkProtocol.AgentLimits(plan.tokenBudget(), plan.hardMaxIterations(), plan.stagnationWindow(), 1_000_000, 16_384);
        report.put("agentLimits", limits); report.put("timeoutSeconds", plan.timeoutSeconds());
        if (Boolean.getBoolean("paicli.test.e1.check")) {
            report.put("status", "CHECK_READY_NO_API_CALL"); write(episode.resolve("result.json"), report);
            System.out.println("E1 credential/artifact check ready: " + provider + " (no API call; authentication unverified)");
            return;
        }
        Path workspace = artifact.createPrivateDirectory("workspace"), home = artifact.createPrivateDirectory("home");
        var fixture = FormalFixtureMaterializer.materialize(plan.fixture(), workspace); fixture.verifyReady();
        write(episode.resolve("fixture-before-worker.json"), Map.of("snapshotSha256", fixture.frozenSnapshotSha256(), "files", plan.fixture().files()));
        var session = binding.newSession();
        var request = new BenchmarkProtocol.WorkerRequest(BenchmarkProtocol.VERSION, provider, model, credential.baseUrl(), credential.apiKey(),
                "PLAN", BenchmarkToolProfile.FILE_ONLY, limits, "2026-09-04", plan.prompt(), workspace.toString(), home.toString(), episode.toString());
        report.put("status", "RUNNING"); report.put("workerDispatchAttempted", true);
        write(episode.resolve("started.json"), report);
        System.out.println("E1 live started: " + provider + " / " + model);
        BenchmarkCoordinatorMain.WorkerExecution execution;
        try {
            execution = worker.executeWithPlan(request, workspace, home, Duration.ofSeconds(plan.timeoutSeconds()), session);
        } catch (Exception error) {
            // Retain the owned audit for diagnosis; never turn a failed launch into a scoring attestation.
            report.put("status", "EVALUATION_INVALID"); report.put("dispatchErrorType", error.getClass().getSimpleName());
            retainAuditWithoutSecret(session, credential.apiKey(), report);
            assertFalse(BenchmarkSecretCanary.containsInTree(episode, credential.apiKey()));
            write(episode.resolve("result.json"), report);
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new AssertionError("E1 dispatch failed (" + error.getClass().getSimpleName() + "); inspect private result");
        }
        assertFalse(BenchmarkSecretCanary.contains(credential.apiKey(), JSON.writeValueAsString(execution)));
        assertFalse(BenchmarkSecretCanary.containsInTree(episode, credential.apiKey()));
        retainAuditWithoutSecret(session, credential.apiKey(), report);
        report.put("execution", execution); write(episode.resolve("execution.json"), report);
        try {
            binding.verifyUnchanged(); session.requireReturned(execution);
            var response = execution.response(); var metrics = response == null ? null : response.metrics();
            String defect = BenchmarkProviderEvidenceGate.invalidEvaluationFailureType(request, metrics);
            report.put("providerEvidenceFailure", defect);
            var disposition = BenchmarkFailureClassifier.classifyWorker(execution);
            if (defect != null || response != null && BenchmarkProviderEvidenceGate.isEvaluationInvalidFailureType(response.errorType())
                    || disposition == BenchmarkFailureClassifier.Disposition.INFRA_ERROR
                    || disposition == BenchmarkFailureClassifier.Disposition.SECURITY_HARD_GATE) {
                report.put("status", "EVALUATION_INVALID");
            } else if (disposition == BenchmarkFailureClassifier.Disposition.SCORED_FAILURE
                    || BenchmarkProviderEvidenceGate.failureType(request, metrics) != null) {
                report.put("status", "DIAGNOSTIC_COMPLETED"); report.put("diagnosticScore", 0); report.put("diagnosticSatisfied", false);
            } else {
                report.put("providerAuthenticationVerified", true);
                var snapshot = BenchmarkVerifierWorkspaceSnapshot.create(workspace, artifact.createPrivateDirectory("verifier-workspace"));
                var bundle = FormalVerifierBundleMaterializer.materialize(plan, artifact);
                Path envelope = BenchmarkEvidenceEnvelope.writeBoundPlan(episode, workspace, home, 1, session, snapshot, bundle, credential.apiKey());
                String evidenceSha = hash(envelope);
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
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        assertFalse(BenchmarkSecretCanary.contains(credential.apiKey(), JSON.writeValueAsString(report)));
        write(episode.resolve("result.json"), report);
        System.out.println("E1 live diagnostic " + provider + ": " + report.get("status") + ", score=" + report.get("diagnosticScore") + ", publishable=false");
        assertEquals("DIAGNOSTIC_COMPLETED", report.get("status"), "invalid real episode; preserve and inspect private evidence");
    }

    /** Same real generator/contract path as catalog admission; no synthetic 28-slot test admission. */
    static PreparedSource prepareSource(Path root) throws Exception {
        if (!root.isAbsolute() || !root.equals(root.toRealPath()) || Files.isSymbolicLink(root)
                || !Files.getPosixFilePermissions(root).equals(PosixFilePermissions.fromString("rwx------")))
            throw new IOException("E1 diagnostic needs a canonical owner-only output directory");
        for (Path dir = root; dir != null; dir = dir.getParent())
            for (String vcs : List.of(".git", ".hg", ".svn"))
                if (Files.exists(dir.resolve(vcs), LinkOption.NOFOLLOW_LINKS)) throw new IOException("E1 output must be outside version control");
        try (var entries = Files.list(root)) { if (entries.findAny().isPresent()) throw new IOException("E1 output must be empty"); }
        var generated = new FinalSourceGenerator().generateIncompleteSource(new FinalSourceGenerator.GenerationRequest(root.resolve("source"), DEVELOPMENT_SEED));
        if (generated.manifest().finalReady()) throw new IOException("development diagnostic is not a formal run");
        Path source = generated.sourceRoot();
        var contract = FinalExecutableSuiteContract.CaseContract.load(source.resolve("provenance/final/E1/execution-contract.json"));
        var scoring = ScoringContract.load(source.resolve(contract.scoringContractPath()));
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(
                    Files.isDirectory(path) || Files.isExecutable(path) ? "r-x------" : "r--------"));
        }
        var dependencies = new ArrayList<FormalExecutionPlan.VerifierDependency>();
        for (String relative : contract.verifierDependencyPaths()) {
            Path file = source.resolve(relative);
            dependencies.add(new FormalExecutionPlan.VerifierDependency(relative, file,
                    Files.isExecutable(file) ? "0500" : "0400", Files.size(file), hash(file)));
        }
        String fixturePath = "fixtures/final/E1";
        var files = new ArrayList<FormalExecutionPlan.FixtureFile>();
        for (String name : List.of("left.csv", "right.csv")) {
            Path file = source.resolve(fixturePath).resolve(name);
            files.add(new FormalExecutionPlan.FixtureFile(fixturePath + "/" + name, hash(file), Files.size(file), "0400"));
        }
        var identity = new StringBuilder("paicli-formal-fixture-snapshot-v1\0DIRECTORY\0" + fixturePath + "\n");
        for (var file : files) identity.append(file.frozenPath()).append('\0').append(file.sha256()).append('\0').append(file.mode()).append('\0').append(file.size()).append('\n');
        var fixture = new FormalExecutionPlan.FixtureSnapshot(fixturePath, fixturePath, source.resolve(fixturePath),
                FormalBenchmarkPreflight.FixtureKind.DIRECTORY, hash(identity.toString().getBytes(StandardCharsets.UTF_8)), files.size(),
                files.stream().mapToLong(FormalExecutionPlan.FixtureFile::size).sum(), files);
        String prompt = Files.readString(source.resolve("prompts/final/E1.md"));
        var plan = new FormalExecutionPlan.CasePlan(1, contract, scoring, source.resolve(contract.scoringContractPath()),
                contract.scoringContractSha256(), prompt, hash(prompt.getBytes(StandardCharsets.UTF_8)), fixture,
                new FormalExecutionPlan.VerifierCommand(source, List.of(contract.verifierEntryPath(), "{workspace}", "{evidence}"),
                        contract.verifierEntryPath(), source.resolve(contract.verifierEntryPath()), contract.verifierSha256(), dependencies, contract.verifierBundleSha256()));
        FormalPlanBinding.capture(plan);
        return new PreparedSource(source, plan, generated.completeTreeSha256(), generated.manifest().implementedRecipeCount());
    }

    record PreparedSource(Path source, FormalExecutionPlan.CasePlan plan, String generatedTreeSha256, int implementedRecipes) { }

    private static void retainAuditWithoutSecret(FormalPlanBinding.Session session, String secret, Map<String, Object> report) throws Exception {
        var audit = session.retainedAudit();
        assertFalse(BenchmarkSecretCanary.contains(secret, JSON.writeValueAsString(audit)));
        report.put("planAudit", audit);
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
