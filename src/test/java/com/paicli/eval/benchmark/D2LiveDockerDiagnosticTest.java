package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.PaiCliConfig;
import com.paicli.eval.benchmark.mock.D2ReadOnlyJoinMock;
import com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit paid developer diagnostic. Not a generated final recipe, formal verifier, or formal score. */
@EnabledIfSystemProperty(named = "paicli.test.d2.live", matches = "true")
class D2LiveDockerDiagnosticTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DIAGNOSTIC_SEED = "8badf00d".repeat(8);

    @ParameterizedTest
    @CsvSource({"deepseek,deepseek-v4-flash", "hunyuan,hy4-preview", "glm,glm-5.3-flash"})
    void realDockerCandidateQueriesThreeHostOwnedServices(String provider, String model) throws Exception {
        Path root = Path.of(System.getProperty("paicli.test.d2.output")).toRealPath();
        assertTrue(root.isAbsolute());
        assertFalse(root.startsWith(Path.of("").toRealPath()));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(root));
        Path episode = root.resolve(provider);
        assertFalse(Files.exists(episode, LinkOption.NOFOLLOW_LINKS), "never overwrite/retry a diagnostic attempt");
        BenchmarkProcessEnvironment.preparePrivateDirectory(episode);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("kind", "D2_LIVE_DOCKER_DIAGNOSTIC_NOT_FORMAL");
        report.put("publishable", false); report.put("formalScore", null);
        report.put("provider", provider); report.put("model", model);
        var credential = FormalBenchmarkCoordinatorMain.credential(PaiCliConfig.load(), provider);
        if (credential == null) {
            report.put("status", "CREDENTIAL_UNAVAILABLE");
            write(episode.resolve("result.json"), report);
            Assumptions.abort("local provider credential unavailable: " + provider);
        }
        if ("hunyuan".equals(provider))
            assertTrue(credential.baseUrl() == null || "https://tokenhub.tencentmaas.com/v1".equals(credential.baseUrl()));
        var definition = D2ReadOnlyJoinMock.fromEntropy(HexFormat.of().parseHex(DIAGNOSTIC_SEED));
        Path source = episode.resolve("host-definition.json");
        write(source, definition);
        report.put("hostDefinitionSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))));
        var mock = new D2ReadOnlyJoinMock(definition);
        Path workspace = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("workspace"));
        Path home = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("home"));
        String image = System.getProperty("paicli.test.worker.image");
        var worker = new DockerBenchmarkWorkerProcess(Path.of("/usr/local/bin/docker"), image,
                Path.of(System.getProperty("paicli.test.candidate.jar")), Path.of(System.getProperty("paicli.test.runner.jar")));
        var request = new BenchmarkProtocol.WorkerRequest(BenchmarkProtocol.VERSION, provider, model,
                credential.baseUrl(), credential.apiKey(), "REACT", BenchmarkToolProfile.MOCK_MCP,
                new BenchmarkProtocol.AgentLimits(100_000, 32, 8, 1_000_000, 16_384),
                "2026-09-04", mock.prompt(), workspace.toString(), home.toString(), episode.toString());
        report.put("candidateJarSha256", worker.candidateJarSha256());
        report.put("runnerJarSha256", worker.runnerJarSha256());
        report.put("runnerInventorySha256", worker.runnerContentManifestSha256());
        report.put("relayProtocolVersion", BenchmarkRelayProtocol.VERSION);
        report.put("workerImage", image); report.put("agentLimits", request.agentLimits());
        report.put("mockServers", mock.serverNames()); report.put("prompt", mock.prompt());
        report.put("mockInitialStateDigests", mock.initialStateDigests());
        report.put("status", "RUNNING"); write(episode.resolve("started.json"), report);
        var execution = worker.executeWithMock(request, workspace, home, Duration.ofMinutes(12), mock);
        assertFalse(BenchmarkSecretCanary.contains(credential.apiKey(), JSON.writeValueAsString(execution), JSON.writeValueAsString(mock.audit())));
        assertFalse(BenchmarkSecretCanary.containsInTree(episode, credential.apiKey()));
        var response = execution.response();
        String defect = BenchmarkProviderEvidenceGate.invalidEvaluationFailureType(request, response == null ? null : response.metrics());
        boolean toolEvidenceComplete = response != null && response.metrics() != null
                && response.metrics().toolCalls() == execution.toolExecutions().size();
        boolean valid = execution.status() == BenchmarkCoordinatorMain.WorkerStatus.COMPLETED && Integer.valueOf(0).equals(execution.exitCode())
                && response != null && defect == null && toolEvidenceComplete
                && BenchmarkFailureClassifier.classifyResponse(response) != BenchmarkFailureClassifier.Disposition.INFRA_ERROR;
        var allowedNames = definition.tools().stream().map(t -> "mcp__" + t.operation().server() + "__" + t.name()).collect(java.util.stream.Collectors.toSet());
        boolean satisfied = valid && response.success() && mock.satisfies(response.answer())
                && execution.toolExecutions().stream().allMatch(t -> allowedNames.contains(t.toolName()));
        report.put("status", valid ? "DIAGNOSTIC_COMPLETED" : "EVALUATION_INVALID");
        report.put("diagnosticSatisfied", valid ? satisfied : null); report.put("providerEvidenceFailure", defect);
        report.put("toolEvidenceComplete", toolEvidenceComplete);
        report.put("execution", execution); report.put("mockAudit", mock.audit());
        report.put("mockSideEffects", mock.sideEffects()); report.put("mockFinalStateDigests", mock.stateDigests());
        write(episode.resolve("result.json"), report);
        System.out.println("D2 diagnostic " + provider + ": " + report.get("status")
                + ", satisfied=" + report.get("diagnosticSatisfied") + ", publishable=false");
        assertTrue(valid, "real episode invalid; inspect private result.json");
    }

    private static void write(Path path, Object value) throws Exception {
        byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        try (var output = Files.newByteChannel(path, java.util.Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            var buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) output.write(buffer);
        }
    }
}
