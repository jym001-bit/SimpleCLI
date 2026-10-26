package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol;
import com.paicli.eval.benchmark.mock.D3FrozenOracle;
import com.paicli.eval.benchmark.mock.D3ApprovalCalendarMock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;

import static com.paicli.eval.benchmark.D3ScriptedApprovalTest.Control;
import static org.junit.jupiter.api.Assertions.*;

/** Actual networkless Docker Worker plus independent Docker replay. Scripted host LLM, never a model score. */
@EnabledIfSystemProperty(named = "paicli.test.d3.docker", matches = "true")
@Timeout(240)
class D3DockerControlTest {
    @Test void realContainerRetainsTwoTurnApprovalAndNegativeControls() throws Exception {
        Path root = Path.of(System.getProperty("paicli.test.d3.output")).toRealPath();
        assertFalse(root.startsWith(Path.of("").toRealPath()));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(root));
        var json = new ObjectMapper();
        for (Control control : List.of(Control.CORRECT, Control.PREMATURE_WRITE, Control.SELF_APPROVE_TOOL,
                Control.CHANGE_KEY, Control.REPEAT_SAME_KEY, Control.CANCEL_AFTER_CREATE)) {
            Path episode = root.resolve(control.name().toLowerCase(java.util.Locale.ROOT));
            assertFalse(Files.exists(episode), "never overwrite an earlier control");
            BenchmarkProcessEnvironment.preparePrivateDirectory(episode);
            Path workspace = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("workspace"));
            Path home = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("home"));
            Path bundle = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("replay-bundle"));
            byte[] entropy = new byte[32]; java.util.Arrays.fill(entropy, (byte) 27);
            var oracle = new D3FrozenOracle("D3", "MOCK_MCP", "a".repeat(24), D3FrozenOracle.INTERACTION_MODE,
                    D3FrozenOracle.APPROVAL_SCRIPT, java.util.Map.of(), D3ApprovalCalendarMock.fromEntropy(entropy));
            Path oracleFile = bundle.resolve("oracle.json");
            writePrivate(oracleFile, json.writeValueAsBytes(oracle));
            assertEquals(oracle, D3FrozenOracle.parse(Files.readAllBytes(oracleFile)));
            Path script = bundle.resolve("d3_replay.py");
            try (var input = getClass().getResourceAsStream("/benchmark/d3_replay.py")) {
                assertNotNull(input); writePrivate(script, input.readAllBytes());
            }
            for (Path file : List.of(oracleFile, script)) Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--------"));
            String oracleSha = hash(oracleFile), scriptSha = hash(script);
            var mock = oracle.newService();
            var client = new D3McpRelayTest.HistoryCheckingClient(control);
            String image = System.getProperty("paicli.test.worker.image");
            var worker = new DockerBenchmarkWorkerProcess(Path.of("/usr/local/bin/docker"), image,
                    Path.of(System.getProperty("paicli.test.candidate.jar")),
                    Path.of(System.getProperty("paicli.test.runner.jar")), ignored -> client);
            var request = new BenchmarkProtocol.WorkerRequest(BenchmarkProtocol.VERSION,
                    client.getProviderName(), client.getModelName(), null, "synthetic-key-not-a-real-credential",
                    "REACT", BenchmarkToolProfile.MOCK_MCP, new BenchmarkProtocol.AgentLimits(100_000, 12, 2, 1_000_000, 16_384),
                    "2026-09-04", mock.prompt(), workspace.toString(), home.toString(), episode.toString());
            var execution = worker.executeWithMock(request, workspace, home, Duration.ofSeconds(30), mock);
            var evidence = new LinkedHashMap<String, Object>();
            evidence.put("kind", "D3_SCRIPTED_REAL_DOCKER_CONTROL_NOT_MODEL_EVALUATION");
            evidence.put("publishable", false); evidence.put("formalScore", null);
            evidence.put("control", control.name()); evidence.put("relayVersion", BenchmarkRelayProtocol.VERSION);
            evidence.put("providerOrigin", "SCRIPTED_NO_API_SYNTHETIC_TOKEN_COUNTS");
            evidence.put("candidateSha256", worker.candidateJarSha256());
            evidence.put("runnerSha256", worker.runnerJarSha256());
            evidence.put("runnerInventorySha256", worker.runnerContentManifestSha256());
            evidence.put("workerImage", image); evidence.put("execution", execution);
            evidence.put("mockSourceSha256", oracleSha); evidence.put("replayProgramSha256", scriptSha);
            evidence.put("sourceDerivation", "strict-oracle-written-read-back-and-frozen-before-worker");
            evidence.put("mockAudit", mock.audit()); evidence.put("relayAudit", mock.relayAudit());
            evidence.put("initialStateSha256", mock.initialStateSha256()); evidence.put("finalStateSha256", mock.stateSha256());
            evidence.put("events", mock.eventSnapshot()); evidence.put("writes", mock.writes());
            evidence.put("diagnosticSatisfied", execution.response() != null && mock.satisfies(execution.response().answer()));
            try (var out = Files.newByteChannel(episode.resolve("result.json"), java.util.Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                var buffer = java.nio.ByteBuffer.wrap(json.writerWithDefaultPrettyPrinter().writeValueAsBytes(evidence));
                while (buffer.hasRemaining()) out.write(buffer);
            }
            assertEquals(BenchmarkCoordinatorMain.WorkerStatus.COMPLETED, execution.status(), control + ": " + execution);
            assertNotNull(execution.response()); assertTrue(execution.response().success(), control + ": " + execution.response().errorType());
            assertEquals(0, execution.exitCode());
            assertEquals(control == Control.CORRECT, mock.satisfies(execution.response().answer()), control.name());
            int expectedWrites = List.of(Control.CORRECT, Control.REPEAT_SAME_KEY, Control.CANCEL_AFTER_CREATE).contains(control) ? 1 : 0;
            assertEquals(expectedWrites, mock.writes());
            assertTrue(client.sawRetainedHistory);
            assertEquals(client.calls, execution.response().metrics().calls());
            assertEquals(execution.response().metrics().toolCalls(), execution.toolExecutions().size());
            assertEquals(1, mock.relayAudit().stream().filter(e -> e.request().type() == BenchmarkRelayProtocol.FrameType.TURN_COMPLETE).count());
            assertFalse(Files.exists(episode.resolve("worker-docker-tmp/container.cid")), "container removed by bounded cleanup");
            var complete = new BenchmarkRelayProtocol.WorkerComplete(new BenchmarkRelayProtocol.Header(BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR,
                    BenchmarkRelayProtocol.FrameType.WORKER_COMPLETE, "", 0), execution.response().answer(),
                    execution.toolExecutions().stream().map(e -> new BenchmarkRelayProtocol.WireToolExecution(e.ordinal(), e.callId(), e.toolName(), e.argumentsJson(),
                            e.resultPreview(), e.resultSha256(), e.resultChars(), e.elapsedMillis(), e.timedOut(), e.successful())).toList());
            Path evidenceFile = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("verifier-evidence")).resolve("envelope.json");
            writePrivate(evidenceFile, json.writeValueAsBytes(D3IndependentReplayTest.evidence(oracleFile, complete, execution.response().metrics().toolCalls(), mock)));
            assertEquals(BenchmarkRelayProtocol.VERSION, json.readTree(Files.readAllBytes(evidenceFile)).path("relayVersion").intValue());
            Files.setPosixFilePermissions(evidenceFile, PosixFilePermissions.fromString("r--------"));
            String evidenceSha = hash(evidenceFile);
            String verifierImage = System.getProperty("paicli.test.verifier.image"); assertNotNull(verifierImage);
            var verifier = new DockerBenchmarkVerifier(Path.of("/usr/local/bin/docker"), verifierImage);
            var replay = verifier.verify(new CaseDefinition.VerifierInvocation(bundle,
                    List.of("python3", "-B", "d3_replay.py", "oracle.json", "{workspace}", "{evidence}")), workspace, home, evidenceFile, Duration.ofSeconds(20));
            var replayRecord = json.createObjectNode().put("kind", "D3_SCRIPTED_DOCKER_WORKER_AND_INDEPENDENT_DOCKER_REPLAY")
                    .put("publishable", false).put("realProviderCalls", 0).putNull("formalScore")
                    .put("mockSourceSha256", oracleSha).put("replayProgramSha256", scriptSha).put("evidenceSha256", evidenceSha).put("verifierImage", verifierImage);
            replayRecord.set("verification", json.valueToTree(replay));
            writePrivate(episode.resolve("independent-replay.json"), json.writerWithDefaultPrettyPrinter().writeValueAsBytes(replayRecord));
            assertEquals(oracleSha, hash(oracleFile)); assertEquals(scriptSha, hash(script)); assertEquals(evidenceSha, hash(evidenceFile));
            assertEquals(0, replay.exitCode(), replay.stderr()); assertTrue(replay.sandboxed());
            assertFalse(replay.stdoutTruncated()); assertFalse(replay.stderrTruncated());
            assertEquals(control == Control.CORRECT, json.readTree(replay.stdout()).path("diagnosticSatisfied").asBoolean());
            System.out.println("D3 actual Docker control " + control + ": writes=" + mock.writes() + ", diagnosticSatisfied=" + evidence.get("diagnosticSatisfied"));
        }
    }
    private static String hash(Path file) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
    private static void writePrivate(Path file, byte[] bytes) throws Exception {
        try (var out = Files.newByteChannel(file, java.util.Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            var buffer = java.nio.ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) out.write(buffer);
        }
    }
}
