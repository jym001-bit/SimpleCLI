package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.PaiCliConfig;
import com.paicli.eval.benchmark.mock.D1ToolSelectionMock;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Explicitly opt-in, paid real-provider diagnostic; never run by ordinary regression. */
@EnabledIfSystemProperty(named = "paicli.test.d1.live", matches = "true")
class D1LiveDockerDiagnosticTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long SEED = 90401;

    @ParameterizedTest
    @CsvSource({"deepseek,deepseek-v4-flash", "hunyuan,hy4-preview", "glm,glm-5.3-flash"})
    void realDockerCandidateUsesHostOwnedMcp(String provider, String model) throws Exception {
        Path root = Path.of(System.getProperty("paicli.test.d1.output"));
        assertTrue(root.isAbsolute());
        root = root.toRealPath();
        assertFalse(root.startsWith(Path.of("").toRealPath()), "private evidence must be outside repository");
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(root));
        Path episode = root.resolve(provider);
        assertFalse(Files.exists(episode, LinkOption.NOFOLLOW_LINKS), "never overwrite/retry a diagnostic attempt");
        BenchmarkProcessEnvironment.preparePrivateDirectory(episode);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schemaVersion", 1);
        evidence.put("kind", "D1_LIVE_DOCKER_DIAGNOSTIC_NOT_FORMAL");
        evidence.put("publishable", false);
        evidence.put("formalScore", null);
        evidence.put("seed", SEED);
        evidence.put("provider", provider);
        evidence.put("model", model);
        var credential = FormalBenchmarkCoordinatorMain.credential(PaiCliConfig.load(), provider);
        if (credential == null) {
            evidence.put("status", "CREDENTIAL_UNAVAILABLE");
            write(episode.resolve("result.json"), evidence);
            Assumptions.abort("local provider credential unavailable: " + provider);
        }
        if ("hunyuan".equals(provider)) {
            assertTrue(credential.baseUrl() == null || "https://tokenhub.tencentmaas.com/v1".equals(credential.baseUrl()));
        }
        Path workspace = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("workspace"));
        Path home = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("home"));
        String image = System.getProperty("paicli.test.worker.image");
        var worker = new DockerBenchmarkWorkerProcess(Path.of("/usr/local/bin/docker"), image,
                Path.of(System.getProperty("paicli.test.candidate.jar")),
                Path.of(System.getProperty("paicli.test.runner.jar")));
        D1ToolSelectionMock mock = new D1ToolSelectionMock(SEED);
        var request = new BenchmarkProtocol.WorkerRequest(BenchmarkProtocol.VERSION, provider, model,
                credential.baseUrl(), credential.apiKey(), "REACT", BenchmarkToolProfile.MOCK_MCP,
                new BenchmarkProtocol.AgentLimits(100_000, 32, 8, 1_000_000, 16_384),
                "2026-09-04", mock.prompt(), workspace.toString(), home.toString(), episode.toString());
        evidence.put("candidateJarSha256", worker.candidateJarSha256());
        evidence.put("runnerJarSha256", worker.runnerJarSha256());
        evidence.put("runnerInventorySha256", worker.runnerContentManifestSha256());
        evidence.put("workerImage", image);
        evidence.put("agentLimits", request.agentLimits());
        evidence.put("prompt", mock.prompt());
        evidence.put("status", "RUNNING");
        write(episode.resolve("started.json"), evidence);
        BenchmarkCoordinatorMain.WorkerExecution execution = worker.executeWithMock(request,
                workspace, home, Duration.ofMinutes(8), mock);
        String serialized = JSON.writeValueAsString(execution);
        assertFalse(BenchmarkSecretCanary.contains(credential.apiKey(), serialized));
        assertFalse(BenchmarkSecretCanary.contains(credential.apiKey(), JSON.writeValueAsString(mock.audit())));
        assertFalse(BenchmarkSecretCanary.containsInTree(episode, credential.apiKey()));
        var response = execution.response();
        var metrics = response == null ? null : response.metrics();
        String evidenceDefect = BenchmarkProviderEvidenceGate.invalidEvaluationFailureType(request, metrics);
        boolean toolEvidenceComplete = metrics != null && metrics.toolCalls() == execution.toolExecutions().size();
        boolean valid = execution.status() == BenchmarkCoordinatorMain.WorkerStatus.COMPLETED
                && Integer.valueOf(0).equals(execution.exitCode()) && response != null
                && evidenceDefect == null && toolEvidenceComplete
                && BenchmarkFailureClassifier.classifyResponse(response) != BenchmarkFailureClassifier.Disposition.INFRA_ERROR;
        boolean satisfies = valid && response.success() && mock.satisfies(response.answer())
                && execution.toolExecutions().size() == 1
                && execution.toolExecutions().get(0).toolName().startsWith("mcp__benchmark__");
        evidence.put("status", valid ? "DIAGNOSTIC_COMPLETED" : "EVALUATION_INVALID");
        evidence.put("diagnosticSatisfied", valid ? satisfies : null);
        evidence.put("providerEvidenceFailure", evidenceDefect);
        evidence.put("toolEvidenceComplete", toolEvidenceComplete);
        evidence.put("execution", execution);
        evidence.put("mockAudit", mock.audit());
        evidence.put("mockSideEffects", mock.sideEffects());
        write(episode.resolve("result.json"), evidence);
        System.out.println("D1 diagnostic " + provider + ": " + evidence.get("status")
                + ", satisfied=" + evidence.get("diagnosticSatisfied") + ", publishable=false");
        // Candidate assertion failures stay in the report. They are not harness test failures.
        assertTrue(valid, "real episode invalid; inspect private result.json");
    }

    private static void write(Path path, Map<String, Object> value) throws Exception {
        byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        try (var stream = Files.newByteChannel(path, java.util.Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) stream.write(buffer);
        }
    }
}
