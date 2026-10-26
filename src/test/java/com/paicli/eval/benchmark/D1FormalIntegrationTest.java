package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.eval.benchmark.finalset.generator.FinalSourceGenerator;
import com.paicli.eval.benchmark.formal.FormalEpisodeOutcome;
import com.paicli.eval.benchmark.formal.FormalTestAdmission;
import com.paicli.eval.benchmark.mock.D1FrozenOracle;
import com.paicli.eval.benchmark.relay.BenchmarkProviderRelay;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class D1FormalIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;

    @AfterEach void restoreTestTreePermissions() throws Exception {
        try (var walk = Files.walk(temp)) {
            for (Path path : walk.toList()) if (!Files.isSymbolicLink(path))
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(
                        Files.isDirectory(path) ? "rwx------" : "rw-------"));
        }
    }

    @Test void admitsBoundD1BeforeKeysAndRetainsOneFreshMockPerEpisode() throws Exception {
        var ready = ready();
        var requests = ready.requests().stream().filter(r -> r.key().caseId().equals("D1")).toList();
        assertEquals(9, requests.size());
        for (var request : requests) {
            assertEquals(BenchmarkToolProfile.MOCK_MCP, request.toolProfile());
            assertEquals(480, request.timeoutSeconds());
            assertNotNull(request.mockBinding());
            var a = request.mockBinding().newService();
            var b = request.mockBinding().newService();
            assertNotSame(a, b);
            assertTrue(a.audit().isEmpty()); assertTrue(b.audit().isEmpty());
        }
        var plan = ready.plan();
        var oracle = plan.artifacts().frozenRoot().resolve(D1FrozenOracle.PATH);
        byte[] original = Files.readAllBytes(oracle);
        Files.setPosixFilePermissions(oracle, PosixFilePermissions.fromString("rw-------"));
        Files.writeString(oracle, "{}");
        Files.setPosixFilePermissions(oracle, PosixFilePermissions.fromString("r--------"));
        assertThrows(IOException.class, () -> requests.get(0).mockBinding().newService());
        assertThrows(FormalEpisodeRequestFactory.PreparationException.class,
                () -> FormalEpisodeRequestFactory.validateCapabilities(plan, plan.episodes().get(0)));
        Files.setPosixFilePermissions(oracle, PosixFilePermissions.fromString("rw-------"));
        Files.write(oracle, original);
        Files.setPosixFilePermissions(oracle, PosixFilePermissions.fromString("r--------"));
        requests.get(0).mockBinding().verifyUnchanged();
        Path alias = temp.resolve("oracle-alias");
        Files.createLink(alias, oracle);
        assertThrows(IOException.class, () -> requests.get(0).mockBinding().verifyUnchanged());
        Files.delete(alias); // This test's hardlink only; original is retained.
    }

    @Test void frozenMockDriftAfterDispatchInvalidatesBatchAndRetainsHostAudit() throws Exception {
        var ready = ready();
        BenchmarkCoordinatorMain.WorkerExecutor worker = new BenchmarkCoordinatorMain.WorkerExecutor() {
            @Override public BenchmarkCoordinatorMain.WorkerExecution execute(BenchmarkProtocol.WorkerRequest request,
                    Path workspace, Path home, Duration timeout) {
                throw new AssertionError("D1 must dispatch with its bound host mock");
            }
            @Override public BenchmarkCoordinatorMain.WorkerExecution executeWithMock(BenchmarkProtocol.WorkerRequest request,
                    Path workspace, Path home, Duration timeout, BenchmarkProviderRelay.MockMcpEndpoint mock) throws IOException {
                var result = scriptedControl(request, mock, 0);
                Path oracle = ready.plan().artifacts().frozenRoot().resolve(D1FrozenOracle.PATH);
                Files.setPosixFilePermissions(oracle, PosixFilePermissions.fromString("rw-------"));
                Files.writeString(oracle, "{}");
                Files.setPosixFilePermissions(oracle, PosixFilePermissions.fromString("r--------"));
                return result;
            }
        };
        var report = FormalBatchRunner.run(ready, temp.toRealPath().resolve("output"), "d1-drift", worker,
                (invocation, workspace, home, evidence, timeout) -> { throw new AssertionError("drift must stop grading"); },
                Clock.systemUTC());
        assertEquals(1, report.summary().attemptedEpisodes());
        assertFalse(report.summary().completeValidCoverage());
        assertNull(report.summary().observedMeanWeightedScores());
        var episode = report.summary().episodes().get(0);
        assertInstanceOf(FormalEpisodeOutcome.Dataset.class, episode.outcome());
        assertEquals(FormalEpisodeOutcome.DatasetCode.VERIFIER_DEPENDENCY_DRIFT,
                ((FormalEpisodeOutcome.Dataset) episode.outcome()).code());
        assertEquals(4, episode.mockEvidence().events().size());
    }

    @Test
    @EnabledIfSystemProperty(named = "paicli.test.verifier.image", matches = "sha256:[0-9a-f]{64}")
    void fullRunnerUsesGeneratedD1HostEvidenceAndRealDockerVerifierForNineControls() throws Exception {
        var ready = ready();
        var d1Calls = new AtomicInteger();
        var verifications = new AtomicInteger();
        var docker = new DockerBenchmarkVerifier(Path.of("/usr/local/bin/docker"), System.getProperty("paicli.test.verifier.image"));
        BenchmarkCoordinatorMain.WorkerExecutor worker = new BenchmarkCoordinatorMain.WorkerExecutor() {
            @Override public BenchmarkCoordinatorMain.WorkerExecution execute(BenchmarkProtocol.WorkerRequest request,
                    Path workspace, Path home, Duration timeout) {
                assertNotEquals(BenchmarkToolProfile.MOCK_MCP, request.toolProfile());
                return FormalBatchRunnerTest.successfulWorker(request, false);
            }
            @Override public BenchmarkCoordinatorMain.WorkerExecution executeWithMock(BenchmarkProtocol.WorkerRequest request,
                    Path workspace, Path home, Duration timeout, BenchmarkProviderRelay.MockMcpEndpoint mock) throws IOException {
                assertFalse(Files.exists(workspace.getParent().resolve("trusted-verifier")));
                assertFalse(Files.exists(workspace.resolve("validators")));
                int control = d1Calls.getAndIncrement();
                return scriptedControl(request, mock, control);
            }
        };
        var report = FormalBatchRunner.run(ready, temp.toRealPath().resolve("output"), "d1-controls", worker,
                (invocation, workspace, home, evidence, timeout) -> {
                    var envelope = JSON.readTree(evidence.toFile());
                    var id = envelope.path("caseId").asText();
                    if (!id.equals("D1")) return FormalBatchRunnerTest.verification(ready.plan().requireCase(id), 100, false, 0);
                    verifications.incrementAndGet();
                    assertEquals(3, envelope.path("schemaVersion").asInt());
                    assertEquals(4, envelope.path("mockMcp").path("events").size());
                    assertEquals("d1-ledger-v1", envelope.path("mockMcp").path("profile").asText());
                    assertFalse(envelope.path("mockMcp").has("definition"));
                    return docker.verify(invocation, workspace, home, evidence, Duration.ofSeconds(120));
                }, Clock.systemUTC());
        assertEquals(252, report.summary().attemptedEpisodes());
        assertEquals(9, d1Calls.get()); assertEquals(9, verifications.get());
        assertTrue(report.summary().completeValidCoverage());
        assertFalse(report.summary().publishable()); assertNull(report.summary().formalScores());
        var d1 = report.summary().episodes().stream().filter(e -> e.key().caseId().equals("D1")).toList();
        assertEquals(List.of(100, 0, 0, 0, 0, 100, 100, 100, 100), d1.stream()
                .map(e -> ((FormalEpisodeOutcome.Scored) e.outcome()).score()).toList());
        assertTrue(((FormalEpisodeOutcome.Scored) d1.get(3).outcome()).result().hardGate());
        assertTrue(d1.stream().allMatch(e -> e.mockEvidence() != null && e.mockEvidence().events().size() == 4));
    }

    private FormalBatchPreparation.ReadyBatch ready() throws Exception {
        Path parent = temp.toRealPath();
        Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"));
        var source = new FinalSourceGenerator().generateIncompleteSource(new FinalSourceGenerator.GenerationRequest(
                parent.resolve("source"), "98bafedc32107654".repeat(4)));
        var admission = FormalTestAdmission.createWithGeneratedD1(parent.resolve("admission"), source.sourceRoot());
        return FormalBatchPreparation.prepare(admission,
                provider -> new FormalEpisodeRequestFactory.HostCredential(provider, null, "private-test-credential"));
    }

    /** Scripted controls exercise the runner, not model ability. No API calls are made. */
    private static BenchmarkCoordinatorMain.WorkerExecution scriptedControl(BenchmarkProtocol.WorkerRequest request,
            BenchmarkProviderRelay.MockMcpEndpoint mock, int control) throws IOException {
        mock.exchange(JSON.valueToTree(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize",
                "params", Map.of("protocolVersion", "2025-03-26"))));
        mock.exchange(JSON.valueToTree(Map.of("jsonrpc", "2.0", "method", "notifications/initialized", "params", Map.of())));
        JsonNode catalog = mock.exchange(JSON.valueToTree(Map.of("jsonrpc", "2.0", "id", 2,
                "method", "tools/list", "params", Map.of()))).path("result").path("tools");
        String prefix = control == 2 ? "Read a draft invoice" : control == 3 ? "Post draft invoices" : "Read the authoritative posted";
        String name = null;
        for (JsonNode tool : catalog) if (tool.path("description").asText().startsWith(prefix)) name = tool.path("name").asText();
        assertNotNull(name);
        var customer = Pattern.compile("C-[0-9]{5}").matcher(request.prompt());
        var month = Pattern.compile("2026-0[1-8]").matcher(request.prompt());
        assertTrue(customer.find()); assertTrue(month.find());
        var args = Map.of("customer_id", customer.group(), "month", month.group(), "currency", control == 4 ? "EUR" : "USD");
        var result = mock.exchange(JSON.valueToTree(Map.of("jsonrpc", "2.0", "id", 3, "method", "tools/call",
                "params", Map.of("name", name, "arguments", args)))).path("result");
        String text = result.path("content").get(0).path("text").asText();
        if (result.path("isError").asBoolean()) text = "MCP 工具返回错误: " + text;
        var tool = new BenchmarkToolExecutionEvidence(1, "control-call", "mcp__benchmark__" + name,
                JSON.writeValueAsString(args), text, hash(text), text.length(), 1, false, !result.path("isError").asBoolean());
        var limits = request.agentLimits();
        var metrics = new TracingLlmClient.Metrics(2, 100, 30, 0, 1, 1, 2, request.model(), true, true,
                "a".repeat(64), "b".repeat(64), true, limits.contextWindowCapTokens(), limits.contextWindowCapTokens(),
                limits.maxOutputTokensPerCall(), 100, 130, true);
        String answer = control == 1 ? "```json\n" + text + "\n```" : text;
        return BenchmarkCoordinatorMain.WorkerExecution.completed(BenchmarkProtocol.WorkerResponse.success(answer, metrics),
                0, 1, "", List.of(tool));
    }

    private static String hash(String text) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
