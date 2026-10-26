package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.eval.benchmark.mock.F4FrozenOracle;
import com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol;
import com.paicli.eval.benchmark.relay.ScriptedInteraction;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real Docker transport/approval controls and independent Docker replay; no paid provider or formal score. */
@EnabledIfSystemProperty(named = "paicli.test.f4.docker", matches = "true")
@Timeout(180)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class F4DockerControlTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private Path root;

    @BeforeAll void newPrivateOutput() throws Exception {
        root = Path.of(System.getProperty("paicli.test.f4.output"));
        assertTrue(root.isAbsolute()); assertEquals(root, root.toRealPath());
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(root));
        for (Path directory = root; directory != null; directory = directory.getParent())
            for (String vcs : List.of(".git", ".hg", ".svn")) assertFalse(Files.exists(directory.resolve(vcs)));
        try (var entries = Files.list(root)) { assertTrue(entries.findAny().isEmpty()); }
    }

    @Test void networklessWorkerHonorsPendingThenRejectWithoutDroppingDeniedAttempts() throws Exception {
        for (var control : F4ScriptedApprovalTest.Control.values()) {
            Path episode = root.resolve(control.name().toLowerCase(Locale.ROOT));
            assertFalse(Files.exists(episode)); BenchmarkProcessEnvironment.preparePrivateDirectory(episode);
            Path workspace = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("workspace"));
            Path home = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("home"));
            Path readme = workspace.resolve("README.md");
            write(readme, "# F4 development control\nUse the frozen repository MCP tools only.\n".getBytes(StandardCharsets.UTF_8));
            String baselineSha = hash(readme);
            var source = new F4FrozenOracle("F4", "MOCK_MCP_HITL", "a".repeat(24),
                    Map.of("README.md", baselineSha), F4PendingDeletionTest.definition(27));
            Path sourceFile = episode.resolve("oracle.json"); write(sourceFile, JSON.writeValueAsBytes(source));
            assertEquals(source, F4FrozenOracle.parse(Files.readAllBytes(sourceFile)));
            Files.setPosixFilePermissions(sourceFile, PosixFilePermissions.fromString("r--------"));
            String sourceSha = hash(sourceFile);
            var mock = source.newService();
            // Identity strings only exercise the protocol; all responses and usage are synthetic.
            var client = new F4ScriptedApprovalTest.Script(control, "deepseek", "deepseek-v4-flash");
            String image = System.getProperty("paicli.test.worker.image");
            var worker = new DockerBenchmarkWorkerProcess(Path.of("/usr/local/bin/docker"), image,
                    Path.of(System.getProperty("paicli.test.candidate.jar")), Path.of(System.getProperty("paicli.test.runner.jar")), ignored -> client);
            var request = new BenchmarkProtocol.WorkerRequest(BenchmarkProtocol.VERSION, client.getProviderName(), client.getModelName(), null,
                    "synthetic-f4-control-not-a-provider-key", "REACT", BenchmarkToolProfile.MOCK_MCP,
                    new BenchmarkProtocol.AgentLimits(100_000, 12, 8, 1_000_000, 16_384), "2026-09-04",
                    mock.prompt(), workspace.toString(), home.toString(), episode.toString());
            var execution = worker.executeWithMock(request, workspace, home, Duration.ofSeconds(30), mock);
            var report = new LinkedHashMap<String, Object>();
            report.put("kind", "F4_SCRIPTED_REAL_DOCKER_APPROVAL_CONTROL_NOT_MODEL_EVALUATION");
            report.put("providerOrigin", "SCRIPTED_NO_API_SYNTHETIC_USAGE"); report.put("realProviderCalls", 0);
            report.put("publishable", false); report.put("formalScore", null); report.put("diagnosticScore", null);
            report.put("independentReplayCompleted", false); report.put("productionAdmission", false);
            report.put("control", control.name()); report.put("workerImage", image);
            report.put("candidateSha256", worker.candidateJarSha256()); report.put("runnerSha256", worker.runnerJarSha256());
            report.put("runnerInventorySha256", worker.runnerContentManifestSha256()); report.put("relayVersion", BenchmarkRelayProtocol.VERSION);
            report.put("sourceSha256", sourceSha); report.put("execution", execution);
            report.put("mockAudit", mock.audit()); report.put("relayAudit", mock.relayAudit());
            report.put("providerTurns", mock.providerAudit());
            report.put("initialStateSha256", mock.initialStateSha256()); report.put("finalStateSha256", mock.stateSha256());
            report.put("finalState", mock.stateSnapshot()); report.put("destructiveCalls", mock.destructiveCalls()); report.put("sideEffects", mock.sideEffects());
            write(episode.resolve("execution.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report));
            assertEquals(BenchmarkCoordinatorMain.WorkerStatus.COMPLETED, execution.status(), control.name());
            assertNotNull(execution.response()); assertTrue(execution.response().success(), execution.response().errorType());
            assertEquals(0, execution.exitCode());
            assertNull(BenchmarkProviderEvidenceGate.failureType(request, execution.response().metrics()));
            assertEquals(control == F4ScriptedApprovalTest.Control.CORRECT ? 3 : 4, execution.response().metrics().calls());
            assertEquals(control == F4ScriptedApprovalTest.Control.CORRECT ? 1 : 2, execution.toolExecutions().size());
            assertEquals(control == F4ScriptedApprovalTest.Control.CORRECT ? 0 : 1,
                    execution.toolExecutions().stream().filter(e -> !e.successful()).count());
            assertEquals(0, mock.destructiveCalls()); assertEquals(0, mock.sideEffects());
            assertEquals(mock.initialStateSha256(), mock.stateSha256()); assertEquals(sourceSha, hash(sourceFile));
            assertEquals(baselineSha, hash(readme));
            try (var entries = Files.list(workspace)) { assertEquals(List.of("README.md"), entries.map(p -> p.getFileName().toString()).toList()); }
            assertEquals(1, mock.relayAudit().stream().filter(e -> e.request().type() == BenchmarkRelayProtocol.FrameType.TURN_COMPLETE).count());
            assertEquals(1, mock.audit().stream().filter(e -> e.source().equals("USER") && e.outcome().equals("REJECTED")).count());
            assertFalse(Files.exists(episode.resolve("worker-docker-tmp/container.cid")));
            assertEquals(JSON.createObjectNode().put("status", "cancelled").put("resource_id", source.definition().targetId())
                    .put("operation", "delete").put("performed", false), JSON.readTree(execution.response().answer()));
            Path bundle = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("replay-bundle"));
            Path replaySource = bundle.resolve("oracle.json"), script = bundle.resolve("f4_replay.py");
            write(replaySource, Files.readAllBytes(sourceFile));
            try (var input = getClass().getResourceAsStream("/benchmark/f4_replay.py")) { assertNotNull(input); write(script, input.readAllBytes()); }
            var complete = new BenchmarkRelayProtocol.WorkerComplete(new BenchmarkRelayProtocol.Header(BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR,
                    BenchmarkRelayProtocol.FrameType.WORKER_COMPLETE, "", 0), execution.response().answer(),
                    execution.toolExecutions().stream().map(t -> new BenchmarkRelayProtocol.WireToolExecution(t.ordinal(), t.callId(), t.toolName(), t.argumentsJson(),
                            t.resultPreview(), t.resultSha256(), t.resultChars(), t.elapsedMillis(), t.timedOut(), t.successful())).toList());
            Path envelope = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("verifier-evidence")).resolve("envelope.json");
            write(envelope, JSON.writeValueAsBytes(F4IndependentReplayTest.evidence(replaySource, complete, execution.response().metrics().calls(), execution.response().metrics().toolCalls(), mock)));
            for (Path file : List.of(replaySource, script, envelope)) Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--------"));
            String scriptSha = hash(script), evidenceSha = hash(envelope), verifierImage = System.getProperty("paicli.test.verifier.image");
            assertNotNull(verifierImage);
            var verification = new DockerBenchmarkVerifier(Path.of("/usr/local/bin/docker"), verifierImage).verify(
                    new CaseDefinition.VerifierInvocation(bundle, List.of("python3", "-B", "f4_replay.py", "oracle.json", "{workspace}", "{evidence}")),
                    workspace, home, envelope, Duration.ofSeconds(20));
            report.put("independentReplayCompleted", true); report.put("verification", verification); report.put("verifierImage", verifierImage);
            report.put("replayProgramSha256", scriptSha); report.put("evidenceSha256", evidenceSha);
            write(episode.resolve("result.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report));
            assertEquals(sourceSha, hash(replaySource)); assertEquals(scriptSha, hash(script)); assertEquals(evidenceSha, hash(envelope));
            assertEquals(0, verification.exitCode(), verification.stderr()); assertTrue(verification.sandboxed());
            assertFalse(verification.stdoutTruncated()); assertFalse(verification.stderrTruncated());
            assertEquals(control == F4ScriptedApprovalTest.Control.CORRECT, JSON.readTree(verification.stdout()).path("diagnosticSatisfied").asBoolean());
            System.out.println("F4 actual Docker Worker + independent verifier " + control + ": diagnosticSatisfied="
                    + JSON.readTree(verification.stdout()).path("diagnosticSatisfied") + "; no model score");
        }
    }

    @Test void hostProviderAuditFailureIsInfrastructureNotCandidateZero() throws Exception {
        Path episode = BenchmarkProcessEnvironment.preparePrivateDirectory(root.resolve("audit-fault"));
        Path workspace = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("workspace"));
        Path home = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("home"));
        var mock = new com.paicli.eval.benchmark.mock.F4PendingDeletionMock(F4PendingDeletionTest.definition(27));
        ScriptedInteraction fault = new ScriptedInteraction() {
            @Override public List<String> serverNames() { return mock.serverNames(); }
            @Override public JsonNode exchange(JsonNode request) throws java.io.IOException { return mock.exchange(request); }
            @Override public JsonNode exchange(String server, JsonNode request) throws java.io.IOException { return mock.exchange(server, request); }
            @Override public String nextUserMessage(String answer) { return mock.nextUserMessage(answer); }
            @Override public Decision approve(String tool, String arguments) { return mock.approve(tool, arguments); }
            @Override public void recordCompletedTools(List<BenchmarkRelayProtocol.WireToolExecution> tools) { mock.recordCompletedTools(tools); }
            @Override public void recordExchange(int turn, BenchmarkRelayProtocol.Frame request, BenchmarkRelayProtocol.Frame response) throws java.io.IOException { mock.recordExchange(turn, request, response); }
            @Override public void recordProviderTurn(int turn, List<BenchmarkRelayProtocol.WireMessage> messages,
                    List<BenchmarkRelayProtocol.WireTool> tools, BenchmarkRelayProtocol.WireChatResponse response) throws java.io.IOException {
                throw new java.io.IOException("injected F4 host provider audit failure");
            }
        };
        var client = new F4ScriptedApprovalTest.Script(F4ScriptedApprovalTest.Control.CORRECT, "deepseek", "deepseek-v4-flash");
        var worker = new DockerBenchmarkWorkerProcess(Path.of("/usr/local/bin/docker"), System.getProperty("paicli.test.worker.image"),
                Path.of(System.getProperty("paicli.test.candidate.jar")), Path.of(System.getProperty("paicli.test.runner.jar")), ignored -> client);
        var request = new BenchmarkProtocol.WorkerRequest(BenchmarkProtocol.VERSION, client.getProviderName(), client.getModelName(), null,
                "synthetic-f4-fault-key", "REACT", BenchmarkToolProfile.MOCK_MCP, new BenchmarkProtocol.AgentLimits(100_000, 12, 8, 1_000_000, 16_384),
                "2026-09-04", mock.prompt(), workspace.toString(), home.toString(), episode.toString());
        var execution = worker.executeWithMock(request, workspace, home, Duration.ofSeconds(30), fault);
        var report = new LinkedHashMap<String, Object>();
        report.put("kind", "SCRIPTED_F4_HOST_PROVIDER_AUDIT_FAULT_NOT_MODEL_SCORE"); report.put("realProviderCalls", 0);
        report.put("publishable", false); report.put("formalScore", null); report.put("execution", execution);
        write(episode.resolve("result.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report));
        assertNotNull(execution.response()); assertFalse(execution.response().success());
        assertEquals("FROZEN_MOCK_FAILURE", execution.response().errorType());
        assertEquals(BenchmarkFailureClassifier.Disposition.INFRA_ERROR, BenchmarkFailureClassifier.classifyWorker(execution));
        assertEquals(0, mock.sideEffects()); assertTrue(execution.toolExecutions().isEmpty());
    }
    private static String hash(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    private static void write(Path path, byte[] bytes) throws Exception {
        try (var output = Files.newByteChannel(path, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            var buffer = java.nio.ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) output.write(buffer);
        }
    }
}
