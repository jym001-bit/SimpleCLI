package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.paicli.eval.benchmark.mock.*;
import com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol;
import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Independent Python consumes source + host/Worker evidence, never the Java diagnostic verdict. */
@Timeout(90)
class D4IndependentReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;

    @ParameterizedTest @EnumSource(D4NativeWebTest.Control.class)
    void independentReplayChecksEveryNativeControl(D4NativeWebTest.Control control) throws Exception {
        var input = input(control); var result = local(input);
        assertEquals(0, result.exitCode(), control + ": " + result.stderr());
        var report = JSON.readTree(result.stdout()); assertTrue(report.path("evaluationValid").asBoolean());
        assertEquals(Set.of(D4NativeWebTest.Control.CORRECT, D4NativeWebTest.Control.REVERSED_FETCH).contains(control), report.path("diagnosticSatisfied").asBoolean(), result.stdout());
    }
    @Test void contradictoryEvidenceIsInvalidAndNeverAnInflatedOrZeroScore() throws Exception {
        var input = input(D4NativeWebTest.Control.CORRECT);
        List<Consumer<ObjectNode>> mutations = List.of(n -> n.put("diagnosticSatisfied", true), n -> n.remove("providerTurns"),
                n -> n.put("relayVersion", 7), n -> n.put("relayVersion", 8.0), n -> n.put("relayVersion", 13),
                n -> n.put("relayVersion", 10.0), n -> n.put("relayVersion", "10"), n -> n.put("mockSourceSha256", "0".repeat(64)),
                n -> n.put("promptSha256", "0".repeat(64)), n -> n.put("modelToolCalls", 0), n -> n.put("answer", "{}"),
                n -> n.withArray("events").remove(0), n -> n.withArray("relayEvents").remove(0),
                n -> ((ObjectNode)n.path("events").get(0)).put("responseSha256", "0".repeat(64)),
                n -> ((ObjectNode)n.path("toolExecutions").get(0)).put("resultSha256", "0".repeat(64)),
                n -> ((ObjectNode)n.path("toolExecutions").get(1)).put("callId", "changed"),
                n -> ((ObjectNode)n.path("toolExecutions").get(1)).put("successful", "true"),
                n -> ((ObjectNode)n.path("providerTurns").get(0).path("toolCalls").get(0)).put("arguments", "{}"),
                n -> ((ObjectNode)n.path("providerTurns").get(1).path("observedTools").get(0)).put("contentSha256", "0".repeat(64)),
                n -> ((ObjectNode)n.path("providerTurns").get(1)).put("webExchangesSeen", 0),
                n -> first(n, "FETCH").with("response").with("page").put("body", "forged answer"),
                n -> first(n, "FETCH").with("request").put("input", "https://outside.invalid/"),
                n -> first(n, "CHECK_URL").with("response").put("denial", "DENIED"),
                n -> first(n, "SEARCH").with("response").with("header").put("callId", "other"),
                n -> first(n, "SEARCH").with("request").put("topK", 1));
        for (var mutation : mutations) {
            var bad = input.evidence().deepCopy(); mutation.accept(bad); JSON.writeValue(input.evidenceFile().toFile(), bad);
            var result = local(input); assertEquals(2, result.exitCode(), bad.toString()); assertTrue(result.stdout().isBlank());
        }
    }
    @Test void syntheticSameBatchBypassAndMissingModelObservationRemainCandidateFailures() throws Exception {
        var input = input(D4NativeWebTest.Control.CORRECT); var bad = input.evidence().deepCopy();
        var turns = bad.withArray("providerTurns");
        ((ObjectNode)turns.get(0)).withArray("toolCalls").addAll((ArrayNode)turns.get(1).path("toolCalls"));
        turns.remove(1); ((ObjectNode)turns.get(1)).put("ordinal", 2);
        JSON.writeValue(input.evidenceFile().toFile(), bad);
        var result = local(input); assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(JSON.readTree(result.stdout()).path("hardGates").path("unauthorized_url").asBoolean());
        assertFalse(JSON.readTree(result.stdout()).path("diagnosticSatisfied").asBoolean());
        bad = input.evidence().deepCopy();
        for (var turn : bad.path("providerTurns")) ((ObjectNode)turn).putArray("observedTools");
        JSON.writeValue(input.evidenceFile().toFile(), bad); result = local(input);
        assertEquals(0, result.exitCode(), result.stderr()); assertFalse(JSON.readTree(result.stdout()).path("checks").path("coverage").asBoolean());
    }
    @Test void answerTypesAndWorkspaceMutationAreValidFailures() throws Exception {
        var input = input(D4NativeWebTest.Control.CORRECT); var bad = input.evidence().deepCopy();
        var answer = (ObjectNode)JSON.readTree(bad.path("answer").asText()); answer.put("timeout_seconds", 45.0);
        bad.put("answer", answer.toString()); ((ObjectNode)bad.path("providerTurns").get(2)).put("content", answer.toString());
        JSON.writeValue(input.evidenceFile().toFile(), bad); var result = local(input);
        assertEquals(0, result.exitCode(), result.stderr()); assertFalse(JSON.readTree(result.stdout()).path("checks").path("answer").asBoolean());
        JSON.writeValue(input.evidenceFile().toFile(), input.evidence()); Files.writeString(input.workspace().resolve("unexpected.txt"), "candidate mutation");
        result = local(input); assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(JSON.readTree(result.stdout()).path("hardGates").path("workspace_mutation").asBoolean());
    }
    @Test void truncatedFactCheatingFailsButAnExtraAuthorizedFetchIsAllowed() throws Exception {
        for (boolean truncate : List.of(true, false)) {
            var input = input(mock -> new LlmClient() {
                int step;
                @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) { return chat(messages, tools); }
                @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) {
                    var d = mock.definition(); var calls = new ArrayList<ToolCall>(); step++;
                    if (step == 1) calls.add(call("search", "web_search", Map.of("query", d.project(), "top_k", 2)));
                    if (step == 2) {
                        calls.add(call("release", "web_fetch", Map.of("url", d.releaseUrl(), "max_chars", truncate ? 10 : 10000)));
                        calls.add(call("migration", "web_fetch", Map.of("url", d.migrationUrl(), "max_chars", truncate ? 10 : 10000)));
                    }
                    if (step == 3 && !truncate) calls.add(call("extra", "web_fetch", Map.of("url", d.releaseUrl())));
                    // Deliberate answer-only knowledge: the verifier must prove the supporting facts reached the model.
                    return new ChatResponse("assistant", calls.isEmpty() ? D4NativeWebTest.reference(d).toString() : "", null, calls, 100, 30, 0, getModelName(), true);
                }
                private ToolCall call(String id, String name, Map<String, Object> args) { return new ToolCall(id, new ToolCall.Function(name, JSON.valueToTree(args).toString())); }
                @Override public String getModelName() { return "scripted-d4-observation"; }
                @Override public String getProviderName() { return "scripted"; }
                @Override public int maxContextWindow() { return 1_000_000; }
            });
            var result = local(input); assertEquals(0, result.exitCode(), result.stderr());
            var report = JSON.readTree(result.stdout()); assertTrue(report.path("checks").path("answer").asBoolean());
            assertEquals(!truncate, report.path("checks").path("coverage").asBoolean());
            assertEquals(!truncate, report.path("diagnosticSatisfied").asBoolean(), report.toString());
        }
    }
    @Test @EnabledIfSystemProperty(named="paicli.test.verifier.image", matches="sha256:[0-9a-f]{64}")
    void independentVerifierRunsWithoutJavaOrCandidateCodeInDocker() throws Exception {
        for (var control : List.of(D4NativeWebTest.Control.CORRECT, D4NativeWebTest.Control.SAME_BATCH, D4NativeWebTest.Control.WRONG_CITATION)) {
            var input = input(control); String before = hash(input.evidenceFile());
            Files.setPosixFilePermissions(input.evidenceFile(), PosixFilePermissions.fromString("r--------"));
            var verifier = new DockerBenchmarkVerifier(Path.of("/usr/local/bin/docker"), System.getProperty("paicli.test.verifier.image"));
            var result = verifier.verify(new CaseDefinition.VerifierInvocation(input.script().getParent(), List.of("python3", "-B", "d4_replay.py", "oracle.json", "{workspace}", "{evidence}")),
                    input.workspace(), Files.createDirectory(input.workspace().getParent().resolve("home")), input.evidenceFile(), Duration.ofSeconds(20));
            assertEquals(0, result.exitCode(), result.stderr()); assertTrue(result.sandboxed()); assertFalse(result.stdoutTruncated()); assertFalse(result.stderrTruncated());
            assertEquals(control == D4NativeWebTest.Control.CORRECT, JSON.readTree(result.stdout()).path("diagnosticSatisfied").asBoolean());
            assertEquals(before, hash(input.evidenceFile()));
        }
    }
    private Input input(D4NativeWebTest.Control control) throws Exception {
        return input(mock -> new D4NativeWebTest.ScriptedClient(mock.definition(), control));
    }
    private Input input(java.util.function.Function<D4WebMock, LlmClient> provider) throws Exception {
        Path root = Files.createTempDirectory(temp, "d4-").toRealPath(), bundle = Files.createDirectory(root.resolve("bundle"));
        Path oracleFile = bundle.resolve("oracle.json"), script = bundle.resolve("d4_replay.py"), workspace = Files.createDirectory(root.resolve("workspace"));
        var oracle = D4FrozenOracleTest.oracle(); JSON.writeValue(oracleFile.toFile(), oracle);
        assertEquals(oracle, D4FrozenOracle.parse(Files.readAllBytes(oracleFile)));
        try (var in = getClass().getResourceAsStream("/benchmark/d4_replay.py")) { assertNotNull(in); Files.copy(in, script); }
        for (Path file : List.of(oracleFile, script)) Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--------"));
        var mock = oracle.newService(); var helper = new D4WebRelayTest(); helper.temp = workspace;
        var complete = helper.run(mock, provider.apply(mock));
        var evidence = evidence(oracleFile, complete, mock); Path file = Files.createDirectory(root.resolve("verifier-evidence")).resolve("evidence.json"); JSON.writeValue(file.toFile(), evidence);
        return new Input(workspace, oracleFile, script, file, evidence);
    }
    static ObjectNode evidence(Path oracle, BenchmarkRelayProtocol.WorkerComplete complete, D4WebMock mock) throws Exception {
        var result = JSON.createObjectNode().put("schemaVersion", 1).put("caseId", "D4").put("profile", D4FrozenOracle.PROFILE)
                .put("relayVersion", BenchmarkRelayProtocol.VERSION).put("mockSourceSha256", hash(oracle))
                .put("promptSha256", BenchmarkRelayProtocol.textSha256(mock.prompt())).put("answer", complete.answer())
                .put("modelToolCalls", mock.providerAudit().stream().mapToInt(t -> t.toolCalls().size()).sum());
        result.set("toolExecutions", JSON.valueToTree(complete.toolExecutions())); result.set("events", JSON.valueToTree(mock.audit()));
        result.set("relayEvents", JSON.valueToTree(mock.relayAudit())); result.set("providerTurns", JSON.valueToTree(mock.providerAudit())); return result;
    }
    private static ObjectNode first(ObjectNode evidence, String operation) {
        for (var event : evidence.path("relayEvents")) if (event.path("request").path("operation").asText().equals(operation)) return (ObjectNode)event;
        throw new AssertionError(operation);
    }
    static String hash(Path file) throws Exception { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); }
    private static BenchmarkSubprocess.Result local(Input input) throws Exception {
        String diagnostic = "import runpy,pathlib,sys,traceback\nf=runpy.run_path(sys.argv[1])\ntry:\n"
                + " p=pathlib.Path(sys.argv[2]); e=pathlib.Path(sys.argv[4]); print(f['compact'](f['qualify'](f['load'](p,32768),f['load'](e,16777216),pathlib.Path(sys.argv[3]),f['digest'](p.read_bytes()))))\n"
                + "except Exception:\n traceback.print_exc(); raise SystemExit(2)\n";
        return BenchmarkSubprocess.run(new ProcessBuilder("python3", "-B", "-c", diagnostic, input.script().toString(), input.oracle().toString(), input.workspace().toString(), input.evidenceFile().toString()),
                null, Duration.ofSeconds(10), 32768, 32768);
    }
    private record Input(Path workspace, Path oracle, Path script, Path evidenceFile, ObjectNode evidence) { }
}
