package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.eval.benchmark.mock.D4WebMock;
import com.paicli.eval.benchmark.mock.D4FrozenOracle;
import com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol;
import com.paicli.web.SearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;

import static com.paicli.eval.benchmark.D4NativeWebTest.Control;
import static org.junit.jupiter.api.Assertions.*;

/** Real networkless Docker Worker, scripted host LLM, closed offline Web. Never a formal/model score. */
@EnabledIfSystemProperty(named = "paicli.test.d4.docker", matches = "true")
@Timeout(240)
class D4DockerControlTest {
    @Test void realContainerPreservesWebControlsAndSeparatesHostFixtureFailure() throws Exception {
        Path root = Path.of(System.getProperty("paicli.test.d4.output")).toRealPath();
        assertFalse(root.startsWith(Path.of("").toRealPath()));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(root));
        for (Control control : Control.values()) run(root, control, false);
        run(root, Control.CORRECT, true);
    }

    private void run(Path root, Control control, boolean hostFailure) throws Exception {
        var json = new ObjectMapper(); String name = hostFailure ? "host_failure" : control.name().toLowerCase(Locale.ROOT);
        Path episode = root.resolve(name); assertFalse(Files.exists(episode), "never overwrite previous evidence");
        BenchmarkProcessEnvironment.preparePrivateDirectory(episode);
        Path workspace = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("workspace"));
        Path home = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("home"));
        var oracle = D4FrozenOracleTest.oracle();
        Path bundle = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("replay-bundle"));
        Path source = bundle.resolve("oracle.json"), script = bundle.resolve("d4_replay.py"), prompt = episode.resolve("prompt.txt");
        writePrivate(source, json.writeValueAsBytes(oracle));
        var mock = D4FrozenOracle.parse(Files.readAllBytes(source)).newService();
        try (var in = getClass().getResourceAsStream("/benchmark/d4_replay.py")) { assertNotNull(in); writePrivate(script, in.readAllBytes()); }
        writePrivate(prompt, mock.prompt().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(oracle, D4FrozenOracle.parse(Files.readAllBytes(source)));
        for (Path file : List.of(source, script, prompt)) Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--------"));
        String sourceHash = hash(source), scriptHash = hash(script), promptHash = hash(prompt);
        var client = new D4NativeWebTest.ScriptedClient(mock.definition(), control, "deepseek", "deepseek-v4-flash");
        String image = System.getProperty("paicli.test.worker.image");
        var worker = new DockerBenchmarkWorkerProcess(Path.of("/usr/local/bin/docker"), image,
                Path.of(System.getProperty("paicli.test.candidate.jar")), Path.of(System.getProperty("paicli.test.runner.jar")), ignored -> client);
        var request = new BenchmarkProtocol.WorkerRequest(BenchmarkProtocol.VERSION, client.getProviderName(), client.getModelName(),
                null, "synthetic-key-not-a-real-credential", "REACT", BenchmarkToolProfile.MOCK_WEB,
                new BenchmarkProtocol.AgentLimits(100_000, 32, 8, 1_000_000, 16_384), "2026-09-04", mock.prompt(),
                workspace.toString(), home.toString(), episode.toString());
        var backend = hostFailure ? new D4WebHostGuardTest.ForwardingEndpoint(mock) {
            @Override public List<SearchResult> search(String query, int topK) throws IOException { throw new IOException("injected host fixture failure"); }
        } : mock;
        var execution = worker.executeWithWeb(request, workspace, home, Duration.ofSeconds(30), backend);
        boolean exact = false;
        if (execution.response() != null) {
            try { exact = D4NativeWebTest.reference(mock.definition()).equals(json.readTree(execution.response().answer())); }
            catch (Exception invalidAnswer) { /* Invalid JSON is a negative control, not a harness failure. */ }
        }
        long fetches = mock.audit().stream().filter(e -> e.operation().equals("FETCH")).count();
        boolean diagnostic = exact && execution.toolExecutions().stream().allMatch(e -> e.successful()) && fetches == 2;
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("kind", "D4_SCRIPTED_REAL_DOCKER_CONTROL_NOT_MODEL_EVALUATION");
        evidence.put("publishable", false); evidence.put("formalScore", null); evidence.put("realProviderCalls", 0);
        evidence.put("control", name); evidence.put("relayVersion", BenchmarkRelayProtocol.VERSION);
        evidence.put("providerOrigin", "SCRIPTED_NO_API_SYNTHETIC_TOKEN_COUNTS");
        evidence.put("candidateSha256", worker.candidateJarSha256()); evidence.put("runnerSha256", worker.runnerJarSha256());
        evidence.put("runnerInventorySha256", worker.runnerContentManifestSha256()); evidence.put("workerImage", image);
        evidence.put("sourceDerivation", "strict-D4-oracle-program-and-prompt-frozen-before-worker; formal-admission-not-integrated");
        evidence.put("mockSourceSha256", sourceHash); evidence.put("replayProgramSha256", scriptHash); evidence.put("promptSha256", promptHash);
        evidence.put("execution", execution); evidence.put("mockAudit", mock.audit()); evidence.put("relayAudit", mock.relayAudit()); evidence.put("providerTurns", mock.providerAudit());
        evidence.put("diagnosticSatisfied", hostFailure ? null : diagnostic);
        evidence.put("failureDisposition", BenchmarkFailureClassifier.classifyWorker(execution).name());
        writePrivate(episode.resolve("result.json"), json.writerWithDefaultPrettyPrinter().writeValueAsBytes(evidence));
        assertEquals(sourceHash, hash(source)); assertEquals(scriptHash, hash(script)); assertEquals(promptHash, hash(prompt));
        try (var entries = Files.list(workspace)) { assertEquals(0, entries.count(), "Web profile must not mutate the workspace"); }
        assertFalse(Files.exists(episode.resolve("worker-docker-tmp/container.cid")), "bounded cleanup removed the container");
        assertEquals(BenchmarkCoordinatorMain.WorkerStatus.COMPLETED, execution.status(), name + ": " + execution);
        assertNotNull(execution.response());
        if (hostFailure) {
            assertFalse(execution.response().success()); assertEquals("FROZEN_MOCK_FAILURE", execution.response().errorType());
            assertEquals(BenchmarkFailureClassifier.Disposition.INFRA_ERROR, BenchmarkFailureClassifier.classifyWorker(execution));
            assertTrue(mock.audit().isEmpty()); assertTrue(mock.relayAudit().isEmpty());
        } else {
            assertTrue(execution.response().success(), name + ": " + execution.response().errorType()); assertEquals(0, execution.exitCode());
            assertEquals(control == Control.CORRECT || control == Control.REVERSED_FETCH, diagnostic, name);
            assertEquals(control == Control.NO_SEARCH ? 0 : 5, mock.relayAudit().size());
            assertEquals(execution.response().metrics().toolCalls(), execution.toolExecutions().size());
            for (var exchange : mock.relayAudit()) {
                var tool = execution.toolExecutions().get(exchange.toolOrdinal() - 1);
                assertEquals(exchange.request().operation() == BenchmarkRelayProtocol.WebOperation.SEARCH ? "web_search" : "web_fetch", tool.toolName());
                if (exchange.request().operation() != BenchmarkRelayProtocol.WebOperation.SEARCH)
                    assertTrue(Set.of(mock.definition().releaseUrl(), mock.definition().migrationUrl()).contains(exchange.request().input()));
            }
            if (Set.of(Control.BEFORE_SEARCH, Control.SAME_BATCH, Control.SNIPPET_URL, Control.BODY_URL, Control.QUERY_URL, Control.LOCAL_TOOL).contains(control))
                assertEquals(1, execution.toolExecutions().stream().filter(e -> !e.successful()).count(), "recovery must not erase denied attempts");
            var complete = new BenchmarkRelayProtocol.WorkerComplete(new BenchmarkRelayProtocol.Header(BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR,
                    BenchmarkRelayProtocol.FrameType.WORKER_COMPLETE, "", 0), execution.response().answer(),
                    execution.toolExecutions().stream().map(t -> new BenchmarkRelayProtocol.WireToolExecution(t.ordinal(), t.callId(), t.toolName(), t.argumentsJson(),
                            t.resultPreview(), t.resultSha256(), t.resultChars(), t.elapsedMillis(), t.timedOut(), t.successful())).toList());
            Path input = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("verifier-evidence")).resolve("envelope.json");
            writePrivate(input, json.writeValueAsBytes(D4IndependentReplayTest.evidence(source, complete, mock)));
            Files.setPosixFilePermissions(input, PosixFilePermissions.fromString("r--------")); String inputHash = hash(input);
            String verifierImage = System.getProperty("paicli.test.verifier.image"); assertNotNull(verifierImage);
            var verifier = new DockerBenchmarkVerifier(Path.of("/usr/local/bin/docker"), verifierImage);
            var replay = verifier.verify(new CaseDefinition.VerifierInvocation(bundle, List.of("python3", "-B", "d4_replay.py", "oracle.json", "{workspace}", "{evidence}")),
                    workspace, home, input, Duration.ofSeconds(20));
            var record = json.createObjectNode().put("kind", "D4_SCRIPTED_DOCKER_WORKER_AND_INDEPENDENT_DOCKER_REPLAY")
                    .put("publishable", false).put("realProviderCalls", 0).putNull("formalScore")
                    .put("mockSourceSha256", sourceHash).put("replayProgramSha256", scriptHash).put("evidenceSha256", inputHash).put("verifierImage", verifierImage);
            record.set("verification", json.valueToTree(replay)); writePrivate(episode.resolve("independent-replay.json"), json.writerWithDefaultPrettyPrinter().writeValueAsBytes(record));
            assertEquals(0, replay.exitCode(), replay.stderr()); assertTrue(replay.sandboxed()); assertFalse(replay.stdoutTruncated()); assertFalse(replay.stderrTruncated());
            assertEquals(diagnostic, json.readTree(replay.stdout()).path("diagnosticSatisfied").asBoolean());
            assertEquals(inputHash, hash(input)); assertEquals(sourceHash, hash(source)); assertEquals(scriptHash, hash(script));
        }
        System.out.println("D4 actual Docker control " + name + ": fetches=" + fetches + ", diagnosticSatisfied=" + evidence.get("diagnosticSatisfied")
                + ", disposition=" + evidence.get("failureDisposition") + ", realProviderCalls=0");
    }
    private static String hash(Path file) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
    private static void writePrivate(Path file, byte[] bytes) throws Exception {
        try (var out = Files.newByteChannel(file, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            var buffer = java.nio.ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) out.write(buffer);
        }
    }
}
