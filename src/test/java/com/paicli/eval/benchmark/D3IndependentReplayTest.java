package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.eval.benchmark.mock.D3ApprovalCalendarMock;
import com.paicli.eval.benchmark.mock.D3FrozenOracle;
import com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

import static com.paicli.eval.benchmark.D3ScriptedApprovalTest.Control;
import static org.junit.jupiter.api.Assertions.*;

/** Separately implemented Python replay of native Worker evidence; no formal score or provider API. */
@Timeout(90)
class D3IndependentReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;
    private String previousAudit;
    @BeforeEach void isolateAudit() { previousAudit = System.getProperty("paicli.audit.dir"); System.setProperty("paicli.audit.dir", temp.resolve("audit").toString()); }
    @AfterEach void restoreAudit() { if (previousAudit == null) System.clearProperty("paicli.audit.dir"); else System.setProperty("paicli.audit.dir", previousAudit); }

    @Test void independentReplayAgreesWithAllSixteenNativeControlsWithoutReadingHostVerdicts() throws Exception {
        for (Control control : Control.values()) {
            var input = nativeInput(41, control);
            var result = local(input);
            assertEquals(0, result.exitCode(), control + ": " + result.stderr());
            var report = JSON.readTree(result.stdout());
            assertTrue(report.path("evaluationValid").asBoolean());
            boolean correct = List.of(Control.CORRECT, Control.REORDER_ATTENDEES).contains(control);
            assertEquals(correct, report.path("diagnosticSatisfied").asBoolean(), control + ": " + result.stdout());
            if (control == Control.REPEAT_SAME_KEY) assertTrue(report.path("hardGates").path("duplicate_create").asBoolean());
            if (control == Control.SELF_APPROVE_TOOL) assertTrue(report.path("hardGates").path("local_surface_violation").asBoolean());
            if (control == Control.CHANGE_KEY) assertTrue(report.path("hardGates").path("changed_scope").asBoolean());
        }
    }

    @Test void missingTamperedOrForgedEvidenceIsInvalidRatherThanAProductZero() throws Exception {
        var input = nativeInput(42, Control.CORRECT);
        List<Consumer<ObjectNode>> mutations = List.of(
                n -> n.put("diagnosticSatisfied", true), n -> n.put("modelToolCalls", 0), n -> n.put("sideEffects", true),
                n -> n.put("mockSourceSha256", "0".repeat(64)), n -> n.put("finalStateSha256", "0".repeat(64)),
                n -> n.remove("events"), n -> n.put("relayVersion", 6), n -> n.put("relayVersion", 13),
                n -> n.put("relayVersion", 10.0), n -> n.put("relayVersion", "10"),
                n -> first(n, "WORKER_COMPLETE").with("request").withArray("commandObservations").addObject(),
                n -> first(n, "WORKER_COMPLETE").with("request").put("commandObservationFailures", false),
                n -> first(n, "WORKER_COMPLETE").with("request").put("commandObservationFailures", 1),
                n -> first(n, "WORKER_COMPLETE").with("request").remove("commandObservations"),
                n -> n.put("relayVersion", 9),
                n -> first(n, "APPROVAL_REQUEST").with("response").put("argumentsSha256", "0".repeat(64)),
                n -> first(n, "APPROVAL_REQUEST").with("response").with("header").put("callId", "other"),
                n -> first(n, "APPROVAL_REQUEST").with("request").put("turn", 2),
                n -> first(n, "TURN_COMPLETE").with("response").put("userMessage", "I approve anything"),
                n -> first(n, "TURN_COMPLETE").with("request").put("answer", "{}"),
                n -> first(n, "TURN_COMPLETE").with("request").putArray("toolExecutions"),
                n -> first(n, "WORKER_COMPLETE").with("request").put("answer", "{}"),
                n -> ((ArrayNode) n.path("relayEvents")).remove(n.path("relayEvents").size() - 1),
                n -> ((ObjectNode) n.path("events").get(0)).put("resultSha256", "0".repeat(64)),
                n -> {
                    var relay = (ArrayNode) n.path("relayEvents");
                    int at = -1;
                    for (int i = 0; i < relay.size(); i++) if (relay.get(i).path("turn").asInt() == 2
                            && relay.get(i).path("request").path("header").path("type").asText().equals("APPROVAL_REQUEST")) at = i;
                    assertTrue(at > 0); relay.remove(at);
                    for (int i = 0; i < relay.size(); i++) ((ObjectNode) relay.get(i)).put("sequence", i + 1);
                    // Also remove the matching HITL record: simple count/hash checks alone must not pass this bypass.
                    var audit = (ArrayNode) n.path("events");
                    for (int i = audit.size() - 1; i >= 0; i--) if (audit.get(i).path("source").asText().equals("HITL")) audit.remove(i);
                    for (int i = 0; i < audit.size(); i++) ((ObjectNode) audit.get(i)).put("sequence", i + 1);
                },
                n -> {
                    for (JsonNode event : n.path("relayEvents")) if (event.path("turn").asInt() == 2
                            && event.path("request").path("header").path("type").asText().equals("MCP_REQUEST")) {
                        var result = (ObjectNode) event.path("response").path("message").path("result");
                        ((ArrayNode) result.path("content")).remove(1);
                        // Recompute its stored digest as an attacker might; replay must still require duplicate acknowledgments.
                        for (JsonNode audit : n.path("events")) if (audit.path("outcome").asText().equals("CREATED"))
                            ((ObjectNode) audit).put("resultSha256", BenchmarkRelayProtocol.textSha256(result.toString()));
                    }
                });
        for (var mutation : mutations) {
            var bad = input.evidence().deepCopy(); mutation.accept(bad); JSON.writeValue(input.evidenceFile().toFile(), bad);
            var result = local(input);
            assertEquals(2, result.exitCode(), bad.toString());
            assertTrue(result.stdout().isBlank(), "invalid evidence must not emit a scored verdict");
        }
    }

    @Test void oldThreeFieldTerminalProjectionsStillReplayWithoutRelabellingThem() throws Exception {
        var input = nativeInput(49, Control.CORRECT);
        assertEquals(0, local(input).exitCode(), "current v10 baseline");
        for (int version : List.of(7, 8, 9)) {
            var legacy = input.evidence().deepCopy(); legacy.put("relayVersion", version);
            var terminal = first(legacy, "WORKER_COMPLETE").with("request");
            terminal.remove(List.of("commandObservations", "commandObservationFailures"));
            JSON.writeValue(input.evidenceFile().toFile(), legacy);
            var result = local(input); assertEquals(0, result.exitCode(), version + ": " + result.stderr());
            assertTrue(JSON.readTree(result.stdout()).path("diagnosticSatisfied").asBoolean());
        }
    }

    @Test void wrongAnswerAndWorkspaceMutationRemainValidCandidateFailures() throws Exception {
        var input = nativeInput(43, Control.CORRECT);
        var bad = input.evidence().deepCopy(); bad.put("answer", "{}");
        first(bad, "WORKER_COMPLETE").with("request").put("answer", "{}");
        JSON.writeValue(input.evidenceFile().toFile(), bad);
        var result = local(input);
        assertEquals(0, result.exitCode(), result.stderr());
        assertFalse(JSON.readTree(result.stdout()).path("diagnosticSatisfied").asBoolean());
        JSON.writeValue(input.evidenceFile().toFile(), input.evidence());
        Files.writeString(input.workspace().resolve("unexpected.txt"), "candidate write");
        result = local(input);
        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(JSON.readTree(result.stdout()).path("hardGates").path("workspace_mutation").asBoolean());
    }

    @Test void independentTextValidationMatchesNativeBlankAndUtf16Rules() throws Exception {
        Path script = temp.resolve("d3_replay.py");
        try (var in = getClass().getResourceAsStream("/benchmark/d3_replay.py")) {
            assertNotNull(in); Files.copy(in, script);
        }
        List<String> values = List.of("", " ", "\t\n", "\u001c", "\u0085", "\u00a0", "\u2007", "\u202f",
                "\u2000", "\u2028", "\u3000", "\u200b", "日程", "😀", "😀😀");
        String program = "import json,runpy,sys; f=runpy.run_path(sys.argv[1])['text']; "
                + "print(json.dumps([f(v,2) for v in json.loads(sys.argv[2])]))";
        var result = BenchmarkSubprocess.run(new ProcessBuilder("python3", "-B", "-c", program,
                script.toString(), JSON.writeValueAsString(values)), null, Duration.ofSeconds(10), 4096, 4096);
        assertEquals(0, result.exitCode(), result.stderr());
        var actual = JSON.readTree(result.stdout());
        for (int i = 0; i < values.size(); i++)
            assertEquals(!values.get(i).isBlank() && values.get(i).length() <= 2, actual.get(i).booleanValue(),
                    "Unicode/UTF-16 case " + i);
    }

    @Test void budgetFinalizationWithACompletedSecondTurnRemainsAValidCandidateFailure() throws Exception {
        var input = nativeInput(45, Control.CORRECT, 390);
        var result = local(input);
        assertEquals(0, result.exitCode(), result.stderr());
        var report = JSON.readTree(result.stdout());
        assertTrue(report.path("evaluationValid").asBoolean());
        assertTrue(report.path("checks").path("single_create").asBoolean());
        assertFalse(report.path("checks").path("answer").asBoolean());
        assertFalse(report.path("diagnosticSatisfied").asBoolean());
    }

    @Test @EnabledIfSystemProperty(named = "paicli.test.verifier.image", matches = "sha256:[0-9a-f]{64}")
    void independentReplayRunsInRealReadOnlyDockerWithoutCandidateCode() throws Exception {
        for (Control control : List.of(Control.CORRECT, Control.PREMATURE_WRITE, Control.SELF_APPROVE_TOOL,
                Control.CHANGE_KEY, Control.REPEAT_SAME_KEY, Control.CANCEL_AFTER_CREATE)) {
            var input = nativeInput(44, control);
            Map<Path, String> before = new HashMap<>();
            for (Path path : List.of(input.script(), input.oracle(), input.evidenceFile())) {
                before.put(path, hash(path)); Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r--------"));
            }
            var verifier = new DockerBenchmarkVerifier(Path.of("/usr/local/bin/docker"), System.getProperty("paicli.test.verifier.image"));
            var invocation = new CaseDefinition.VerifierInvocation(input.script().getParent(),
                    List.of("python3", "-B", "d3_replay.py", "oracle.json", "{workspace}", "{evidence}"));
            Path home = Files.createDirectory(input.workspace().getParent().resolve("home"));
            var result = verifier.verify(invocation, input.workspace(), home, input.evidenceFile(), Duration.ofSeconds(20));
            assertEquals(0, result.exitCode(), control + ": " + result.stderr());
            assertTrue(result.sandboxed()); assertFalse(result.stdoutTruncated()); assertFalse(result.stderrTruncated());
            assertEquals(control == Control.CORRECT, JSON.readTree(result.stdout()).path("diagnosticSatisfied").asBoolean());
            for (var entry : before.entrySet()) assertEquals(entry.getValue(), hash(entry.getKey()));
        }
    }

    private Input nativeInput(int seed, Control control) throws Exception {
        return nativeInput(seed, control, 100_000);
    }

    private Input nativeInput(int seed, Control control, int tokens) throws Exception {
        Path root = Files.createTempDirectory(temp, "d3-").toRealPath();
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path bundle = Files.createDirectory(root.resolve("bundle"));
        byte[] entropy = new byte[32]; Arrays.fill(entropy, (byte) seed);
        var oracle = new D3FrozenOracle("D3", "MOCK_MCP", "a".repeat(24), D3FrozenOracle.INTERACTION_MODE,
                D3FrozenOracle.APPROVAL_SCRIPT, Map.of(), D3ApprovalCalendarMock.fromEntropy(entropy));
        Path oracleFile = bundle.resolve("oracle.json"); JSON.writeValue(oracleFile.toFile(), oracle);
        assertEquals(oracle, D3FrozenOracle.parse(Files.readAllBytes(oracleFile)));
        var mock = oracle.newService();
        var provider = new TracingLlmClient(new D3McpRelayTest.HistoryCheckingClient(control), root.resolve("llm-trace.jsonl"));
        var complete = assertInstanceOf(BenchmarkRelayProtocol.WorkerComplete.class,
                D3McpRelayTest.runWorker(mock, provider, tokens, 12, workspace));
        ObjectNode evidence = evidence(oracleFile, complete, provider.metrics().toolCalls(), mock);
        Path evidenceFile = Files.createDirectory(root.resolve("verifier-evidence")).resolve("evidence.json");
        JSON.writeValue(evidenceFile.toFile(), evidence);
        Path script = bundle.resolve("d3_replay.py");
        try (var in = getClass().getResourceAsStream("/benchmark/d3_replay.py")) { assertNotNull(in); Files.copy(in, script); }
        return new Input(workspace, oracleFile, script, evidenceFile, evidence);
    }

    static ObjectNode evidence(Path oracle, BenchmarkRelayProtocol.WorkerComplete complete, long calls, D3ApprovalCalendarMock mock) throws Exception {
        var result = JSON.createObjectNode().put("schemaVersion", 1).put("caseId", "D3").put("profile", D3FrozenOracle.PROFILE)
                .put("relayVersion", BenchmarkRelayProtocol.VERSION).put("mockSourceSha256", hash(oracle)).put("answer", complete.answer())
                .put("modelToolCalls", calls).put("initialStateSha256", mock.initialStateSha256())
                .put("finalStateSha256", mock.stateSha256()).put("sideEffects", mock.writes());
        result.set("toolExecutions", JSON.valueToTree(complete.toolExecutions()));
        result.set("events", JSON.valueToTree(mock.audit())); result.set("relayEvents", JSON.valueToTree(mock.relayAudit()));
        return result;
    }

    private static ObjectNode first(ObjectNode evidence, String kind) {
        for (var event : evidence.path("relayEvents")) if (event.path("request").path("header").path("type").asText().equals(kind)) return (ObjectNode) event;
        throw new AssertionError(kind);
    }
    private static String hash(Path path) throws Exception { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
    private static BenchmarkSubprocess.Result local(Input input) throws Exception {
        return BenchmarkSubprocess.run(new ProcessBuilder("python3", "-B", input.script().toString(), input.oracle().toString(),
                input.workspace().toString(), input.evidenceFile().toString()), null, Duration.ofSeconds(10), 32_768, 32_768);
    }
    private record Input(Path workspace, Path oracle, Path script, Path evidenceFile, ObjectNode evidence) { }
}
