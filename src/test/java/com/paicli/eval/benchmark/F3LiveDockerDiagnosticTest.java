package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.PaiCliConfig;
import com.paicli.eval.benchmark.finalset.generator.FinalSourceGenerator;
import com.paicli.eval.benchmark.formal.*;
import com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol;
import com.paicli.eval.benchmark.safety.F3FrozenOracle;
import com.paicli.eval.benchmark.scoring.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in two-model paid diagnostic, never a final holdout or a full-batch admission. */
@EnabledIfSystemProperty(named = "paicli.test.f3.live", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class F3LiveDockerDiagnosticTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEVELOPMENT_SEED = "f3d1a690".repeat(8);
    private static final String RUNTIME_DATE = "2026-09-05";
    private Path root, source;
    private FormalExecutionPlan.CasePlan plan;
    private FormalInjectionBinding binding;
    private BenchmarkArtifactStore store;
    private boolean checkOnly;

    @BeforeAll void freezeSharedSiblingBeforeAnyCredentialRead() throws Exception {
        checkOnly = Boolean.getBoolean("paicli.test.f3.live.check");
        Path requested = Path.of(System.getProperty("paicli.test.f3.live.output"));
        root = requested.toRealPath(); assertEquals(requested, root);
        assertFalse(root.startsWith(Path.of("").toRealPath()));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(root));
        try (var files = Files.list(root)) { assertEquals(0, files.count(), "new private output only"); }
        var generated = new FinalSourceGenerator().generateIncompleteSource(
                new FinalSourceGenerator.GenerationRequest(root.resolve("source"), DEVELOPMENT_SEED));
        source = generated.sourceRoot(); assertFalse(generated.manifest().finalReady());
        plan = F3LiveFixture.create(source); binding = FormalInjectionBinding.capture(plan);
        store = BenchmarkArtifactStore.create(root.resolve("artifacts"), "f3-live-diagnostic");
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("kind", "F3_DEVELOPMENT_SIBLING_NOT_FORMAL_DATASET");
        manifest.put("formalScores", null); manifest.put("publishable", false);
        manifest.put("generatorImplementedRecipes", generated.manifest().implementedRecipeCount());
        manifest.put("sourceTreeSha256BeforePermissionFreeze", generated.completeTreeSha256());
        manifest.put("sourceSha256", hash(source.resolve(F3FrozenOracle.PATH)));
        manifest.put("promptSha256", plan.promptSha256()); manifest.put("fixtureSha256", plan.fixture().snapshotSha256());
        manifest.put("verifierBundleSha256", plan.verifier().bundleSha256()); manifest.put("caseContract", plan.contract());
        manifest.put("runtimeDate", RUNTIME_DATE); manifest.put("repeats", 1);
        manifest.put("models", List.of("deepseek/deepseek-v4-flash", "glm/glm-5.3-flash"));
        manifest.put("modelScope", "USER_SELECTED_TWO_MODELS_2026_09_05");
        manifest.put("checkOnly", checkOnly);
        write(root.resolve("diagnostic-manifest.json"), manifest, null);
    }

    @ParameterizedTest
    @CsvSource({"deepseek,deepseek-v4-flash", "glm,glm-5.3-flash"})
    void realAgentResistsToolResultInjection(String provider, String model) throws Exception {
        var artifact = store.episode("F3", provider + "-" + model, 1, 1);
        Path episode = artifact.directory();
        var report = new LinkedHashMap<String, Object>();
        report.put("schemaVersion", 1); report.put("kind", "F3_LIVE_DOCKER_DIAGNOSTIC_NOT_FORMAL");
        report.put("formalScores", null); report.put("publishable", false); report.put("diagnosticScore", null);
        report.put("diagnosticSatisfied", false); report.put("provider", provider); report.put("model", model);
        report.put("status", "EVALUATION_INVALID"); report.put("promptSha256", plan.promptSha256());
        report.put("fixtureSha256", plan.fixture().snapshotSha256()); report.put("verifierBundleSha256", plan.verifier().bundleSha256());
        String secret = null;
        try {
            binding.verifyUnchanged();
            report.put("sourceSha256", hash(source.resolve(F3FrozenOracle.PATH)));
            var credential = FormalBenchmarkCoordinatorMain.credential(PaiCliConfig.load(), provider);
            if (credential == null) {
                report.put("status", "CREDENTIAL_UNAVAILABLE"); report.put("realProviderCalls", 0);
            } else {
                secret = credential.apiKey();
                assertNull(credential.baseUrl(), "frozen provider endpoint only");
                String workerImage = System.getProperty("paicli.test.worker.image");
                String verifierImage = System.getProperty("paicli.test.verifier.image");
                var verifier = new DockerBenchmarkVerifier(Path.of("/usr/local/bin/docker"), verifierImage);
                var worker = new DockerBenchmarkWorkerProcess(Path.of("/usr/local/bin/docker"), workerImage,
                        Path.of(System.getProperty("paicli.test.candidate.jar")), Path.of(System.getProperty("paicli.test.runner.jar")));
                Path imageCheck = artifact.createPrivateDirectory("image-check");
                verifyImage(workerImage, imageCheck); verifyImage(verifierImage, imageCheck);
                report.put("candidateJarSha256", worker.candidateJarSha256()); report.put("runnerJarSha256", worker.runnerJarSha256());
                report.put("runnerInventorySha256", worker.runnerContentManifestSha256());
                report.put("workerImage", workerImage); report.put("verifierImage", verifierImage);
                report.put("relayProtocolVersion", BenchmarkRelayProtocol.VERSION);
                if (checkOnly) {
                    report.put("status", "CHECK_READY_NO_API_CALL"); report.put("realProviderCalls", 0);
                } else {
                    run(artifact, provider, model, credential, worker, verifier, report);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); invalidate(report, "INTERRUPTED");
        } catch (Exception error) {
            invalidate(report, error.getClass().getSimpleName());
        } finally {
            boolean interrupted = Thread.interrupted();
            try {
                try { binding.verifyUnchanged(); }
                catch (IOException error) { invalidate(report, "FROZEN_SOURCE_DRIFT"); }
                persistResult(episode.resolve("result.json"), report, secret);
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
        System.out.println("F3 live " + provider + ": " + report.get("status") + ", score="
                + report.get("diagnosticScore") + ", publishable=false");
        if ("CREDENTIAL_UNAVAILABLE".equals(report.get("status"))) Assumptions.abort("credential unavailable: " + provider);
        assertTrue(Set.of("CHECK_READY_NO_API_CALL", "DIAGNOSTIC_COMPLETED").contains(report.get("status")),
                "invalid episode preserved in private result; no automatic retry");
    }

    private void run(BenchmarkArtifactStore.EpisodeArtifacts artifact, String provider, String model,
            FormalEpisodeRequestFactory.HostCredential credential, DockerBenchmarkWorkerProcess worker,
            DockerBenchmarkVerifier verifier, Map<String, Object> report) throws Exception {
        if (checkOnly) throw new IllegalStateException("check-only mode cannot execute a provider episode");
        Path episode = artifact.directory(), workspace = artifact.createPrivateDirectory("workspace"), home = artifact.createPrivateDirectory("home");
        FormalFixtureMaterializer.materialize(plan.fixture(), workspace).verifyReady();
        var limits = new BenchmarkProtocol.AgentLimits(plan.tokenBudget(), plan.hardMaxIterations(), plan.stagnationWindow(), 1_000_000, 16_384);
        var request = new BenchmarkProtocol.WorkerRequest(BenchmarkProtocol.VERSION, provider, model, credential.baseUrl(), credential.apiKey(),
                "REACT", BenchmarkToolProfile.MOCK_MCP_FILE_ONLY, limits, RUNTIME_DATE, plan.prompt(), workspace.toString(), home.toString(), episode.toString());
        var session = binding.newSession();
        report.put("agentLimits", limits); report.put("timeoutSeconds", plan.timeoutSeconds()); report.put("status", "RUNNING");
        write(episode.resolve("started.json"), report, credential.apiKey());
        System.out.println("F3 live started: " + provider + " / " + model);
        BenchmarkCoordinatorMain.WorkerExecution execution = null;
        boolean securityBoundary = false;
        try {
            execution = worker.executeWithInjection(request, workspace, home, Duration.ofSeconds(plan.timeoutSeconds()), session);
            var response = execution.response(); var metrics = response == null ? null : response.metrics();
            report.put("workerStatus", execution.status().name());
            // Adapter-level calls; provider-internal HTTP retries are not separate audit records.
            if (metrics != null) report.put("recordedProviderCalls", metrics.calls());
            if (execution.status() == BenchmarkCoordinatorMain.WorkerStatus.SECURITY_ERROR
                    || BenchmarkSecretCanary.contains(credential.apiKey(), JSON.writeValueAsString(execution))
                    || BenchmarkSecretCanary.containsInTree(episode, credential.apiKey())) {
                securityBoundary = true;
                invalidate(report, "CREDENTIAL_SECURITY_BOUNDARY"); return;
            }
            report.put("metrics", metrics); report.put("failureType", response == null ? null : response.errorType());
            session.requireReturned(execution); session.verifyTerminalUnchanged();
            write(episode.resolve("execution.json"), execution, credential.apiKey());
            String disposition = diagnosticDisposition(request, execution, session.audit().failed());
            if ("INVALID".equals(disposition)) { invalidate(report, "PROVIDER_OR_HOST_EVIDENCE_INVALID"); return; }
            if ("VALID_FAILURE".equals(disposition)) {
                report.put("status", "DIAGNOSTIC_COMPLETED"); report.put("diagnosticScore", 0); return;
            }
            var snapshot = session.snapshot(artifact.createPrivateDirectory("verifier-workspace"));
            var bundle = FormalVerifierBundleMaterializer.materialize(plan, artifact);
            Path envelope = BenchmarkEvidenceEnvelope.writeBoundInjection(episode, workspace, home, 1, session, snapshot, bundle, credential.apiKey());
            String evidenceHash = hash(envelope); report.put("evidenceSha256", evidenceHash);
            session.verifyTerminalUnchanged(); bundle.verifyUnchanged(); snapshot.verifyUnchanged();
            var verification = verifier.verify(new CaseDefinition.VerifierInvocation(bundle.root(), plan.verifier().registeredArguments()),
                            snapshot.directory(), home, envelope, Duration.ofSeconds(60));
            report.put("verifier", verification);
            session.verifyTerminalUnchanged(); bundle.verifyUnchanged(); snapshot.verifyUnchanged();
            if (!evidenceHash.equals(hash(envelope))) throw new IOException("evidence changed");
            if (verification.status() != BenchmarkVerifier.Status.PASSED || verification.stdoutTruncated() || verification.stderrTruncated()) {
                invalidate(report, "INDEPENDENT_VERIFIER_REJECTED"); return;
            }
            var score = ScoreCalculator.calculate(plan.scoringContract(), VerifierScoringReport.parse(verification.stdout()), ScoreCalculator.JudgeAvailability.UNAVAILABLE);
            report.put("scoreResult", score); report.put("diagnosticScore", score.score());
            report.put("diagnosticSatisfied", score.strictSuccess()); report.put("status", "DIAGNOSTIC_COMPLETED");
        } finally {
            // A healthy partial audit is diagnostic-only. Never invent a terminal or a scoring envelope.
            try {
                if (securityBoundary || execution != null && execution.status() == BenchmarkCoordinatorMain.WorkerStatus.SECURITY_ERROR) {
                    report.put("rawAuditRetention", "OMITTED_SECURITY_BOUNDARY");
                } else if (session.audit().failed()) {
                    report.put("rawAuditRetention", "OMITTED_FAILED_AUDIT");
                } else {
                    var retained = Map.of("kind", "F3_PARTIAL_OR_COMPLETE_HOST_AUDIT_DIAGNOSTIC_ONLY", "audit", session.audit().snapshot(), "mockAudit", session.mock().audit());
                    write(episode.resolve("diagnostic-host-audit.json"), retained, credential.apiKey());
                    report.put("rawAuditRetention", "PRIVATE_DIAGNOSTIC_ONLY");
                }
            } catch (Exception retentionError) {
                report.put("rawAuditRetention", "UNAVAILABLE"); invalidate(report, "AUDIT_RETENTION_REJECTED");
            }
        }
    }

    static String diagnosticDisposition(BenchmarkProtocol.WorkerRequest request,
            BenchmarkCoordinatorMain.WorkerExecution execution, boolean auditFailed) {
        if (execution == null || auditFailed || execution.status() == BenchmarkCoordinatorMain.WorkerStatus.SECURITY_ERROR) return "INVALID";
        var response = execution.response(); var metrics = response == null ? null : response.metrics();
        if (response != null && BenchmarkProviderEvidenceGate.isEvaluationInvalidFailureType(response.errorType())
                || BenchmarkProviderEvidenceGate.invalidEvaluationFailureType(request, metrics) != null
                || BenchmarkFailureClassifier.classifyWorker(execution) == BenchmarkFailureClassifier.Disposition.INFRA_ERROR) return "INVALID";
        return BenchmarkFailureClassifier.classifyWorker(execution) == BenchmarkFailureClassifier.Disposition.SCORED_FAILURE
                || BenchmarkProviderEvidenceGate.failureType(request, metrics) != null ? "VALID_FAILURE" : "VERIFY";
    }
    static void invalidate(Map<String, Object> report, String code) {
        report.put("status", "EVALUATION_INVALID"); report.put("diagnosticScore", null);
        report.put("diagnosticSatisfied", false); report.remove("scoreResult"); report.put("failureCode", code);
    }
    private static void verifyImage(String image, Path privateDirectory) throws IOException, InterruptedException {
        var builder = new ProcessBuilder("/usr/local/bin/docker", "image", "inspect", "--format", "{{.Id}}", image);
        BenchmarkProcessEnvironment.sanitize(builder.environment(), privateDirectory, privateDirectory);
        var result = BenchmarkSubprocess.run(builder, new byte[0], Duration.ofSeconds(15), 4096, 4096);
        if (result.timedOut() || !Objects.equals(0, result.exitCode()) || result.stdoutTruncated() || result.stderrTruncated()
                || !image.equals(result.stdout().trim())) throw new IOException("frozen Docker image unavailable");
    }
    static void persistResult(Path path, Map<String, Object> report, String secret) throws IOException {
        boolean interrupted = Thread.interrupted();
        try {
            try { write(path, report, secret); }
            catch (CredentialRejected rejected) {
                // Rejection happens before file creation. Do not carry any original object into fallback.
                report.clear(); report.put("kind", "F3_DIAGNOSTIC_SECURITY_REDACTED");
                report.put("formalScores", null); report.put("publishable", false);
                invalidate(report, "CREDENTIAL_IN_REPORT"); write(path, report, null);
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private static String hash(Path file) throws IOException { return F1BoundarySession.sha(Files.readAllBytes(file)); }
    private static void write(Path path, Object value, String secret) throws IOException {
        byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        if (BenchmarkSecretCanary.contains(secret, new String(bytes, java.nio.charset.StandardCharsets.UTF_8)))
            throw new CredentialRejected();
        try (var out = Files.newByteChannel(path, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            var buffer = java.nio.ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) out.write(buffer);
        }
    }
    private static final class CredentialRejected extends IOException {
        CredentialRejected() { super("credential-bearing diagnostic rejected"); }
    }
}
