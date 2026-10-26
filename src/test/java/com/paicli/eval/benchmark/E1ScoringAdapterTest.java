package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.eval.benchmark.plan.E1FrozenOracle;
import com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Draft adapter consumes native product traces; no formal Runner admission, model API or synthetic timestamps. */
@Timeout(180)
class E1ScoringAdapterTest {
    static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;

    @ParameterizedTest @EnumSource(E1IndependentReplayTest.Control.class)
    void scoresNativeControlsThroughDraftEnvelopeV5(E1IndependentReplayTest.Control control) throws Exception {
        var input = input(control);
        var result = BenchmarkSubprocess.run(new ProcessBuilder("python3", "-B", input.script.getParent().resolve("e1_verify.py").toString(),
                input.workspace.toString(), input.evidenceFile.toString()), null, Duration.ofSeconds(10), 32768, 32768);
        assertScore(control, result.exitCode(), result.stdout(), result.stderr());
    }

    @ParameterizedTest @EnumSource(E1IndependentReplayTest.LocalFault.class)
    void scoresLocalFaultsFromNativeExecution(E1IndependentReplayTest.LocalFault fault) throws Exception {
        var input = input(E1IndependentReplayTest.Control.CORRECT, fault);
        var result = BenchmarkSubprocess.run(new ProcessBuilder("python3", "-B", input.script.getParent().resolve("e1_verify.py").toString(),
                input.workspace.toString(), input.evidenceFile.toString()), null, Duration.ofSeconds(10), 32768, 32768);
        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals(fault == E1IndependentReplayTest.LocalFault.MERGE_AFTER_TOOL ? 100 : 0,
                JSON.readTree(result.stdout()).path("components").get(0).path("earnedPoints").asInt());
    }

    @ParameterizedTest @EnumSource(E1IndependentReplayTest.ReplanFault.class)
    void scoresNativeReplanningWithoutForgivingProviderFailures(E1IndependentReplayTest.ReplanFault fault) throws Exception {
        var input = input(E1IndependentReplayTest.Control.CORRECT, null, fault);
        var result = BenchmarkSubprocess.run(new ProcessBuilder("python3", "-B", input.script.getParent().resolve("e1_verify.py").toString(),
                input.workspace.toString(), input.evidenceFile.toString()), null, Duration.ofSeconds(10), 32768, 32768);
        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals(E1IndependentReplayTest.replanPass(fault) ? 100 : 0,
                JSON.readTree(result.stdout()).path("components").get(0).path("earnedPoints").asInt());
    }

    @Test @EnabledIfSystemProperty(named="paicli.test.e1.replan.docker", matches="true")
    void nativeReplansInIndependentDockerVerifier() throws Exception {
        Path root = Path.of(System.getProperty("paicli.test.e1.output")).toRealPath();
        assertFalse(root.startsWith(Path.of("").toRealPath()));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(root));
        try (var files = Files.list(root)) { assertTrue(files.findAny().isEmpty()); }
        Path original = temp; temp = root;
        try {
            for (var fault : E1IndependentReplayTest.ReplanFault.values()) {
                var input = input(E1IndependentReplayTest.Control.CORRECT, null, fault);
                String before = hash(input.evidenceFile);
                Files.setPosixFilePermissions(input.evidenceFile, PosixFilePermissions.fromString("r--------"));
                var verifier = new DockerBenchmarkVerifier(Path.of("/usr/local/bin/docker"), System.getProperty("paicli.test.verifier.image"));
                var result = verifier.verify(new CaseDefinition.VerifierInvocation(input.script.getParent(),
                                List.of("python3", "-B", "e1_verify.py", "{workspace}", "{evidence}")),
                        input.workspace, input.home, input.evidenceFile, Duration.ofSeconds(20));
                var record = JSON.createObjectNode().put("providerOrigin", "SCRIPTED_NO_API_SYNTHETIC_USAGE")
                        .put("candidateExecution", "NATIVE_IN_PROCESS_TEST_ONLY_LOCAL_FAULT")
                        .put("fault", fault.name()).put("realProviderCalls", 0).putNull("formalScore")
                        .put("publicationEligible", false).put("formalAdmission", false)
                        .put("evidenceSha256", before).put("sourceSha256", input.sourceHash)
                        .put("replaySha256", hash(input.script)).put("adapterSha256", hash(input.script.getParent().resolve("e1_verify.py")));
                record.set("verification", JSON.valueToTree(result));
                Path output = input.root.resolve("replan-verification.json");
                Files.createFile(output, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                JSON.writeValue(output.toFile(), record);
                assertTrue(result.sandboxed()); assertEquals(0, result.exitCode(), result.stderr());
                assertEquals(E1IndependentReplayTest.replanPass(fault) ? 100 : 0,
                        JSON.readTree(result.stdout()).path("components").get(0).path("earnedPoints").asInt());
                assertEquals(before, hash(input.evidenceFile));
            }
        } finally { temp = original; }
    }

    @Test @EnabledIfSystemProperty(named="paicli.test.e1.local.docker", matches="true")
    void localFaultsInIndependentDockerVerifier() throws Exception {
        Path root = Path.of(System.getProperty("paicli.test.e1.output")).toRealPath();
        assertFalse(root.startsWith(Path.of("").toRealPath()));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(root));
        try (var files = Files.list(root)) { assertTrue(files.findAny().isEmpty()); }
        Path original = temp; temp = root;
        try {
            for (var fault : E1IndependentReplayTest.LocalFault.values()) {
                var input = input(E1IndependentReplayTest.Control.CORRECT, fault);
                var before = hash(input.evidenceFile);
                Files.setPosixFilePermissions(input.evidenceFile, PosixFilePermissions.fromString("r--------"));
                var verifier = new DockerBenchmarkVerifier(Path.of("/usr/local/bin/docker"), System.getProperty("paicli.test.verifier.image"));
                var result = verifier.verify(new CaseDefinition.VerifierInvocation(input.script.getParent(),
                                List.of("python3", "-B", "e1_verify.py", "{workspace}", "{evidence}")),
                        input.workspace, input.home, input.evidenceFile, Duration.ofSeconds(20));
                var record = JSON.createObjectNode().put("providerOrigin", "SCRIPTED_NO_API_SYNTHETIC_USAGE")
                        .put("candidateExecution", "NATIVE_IN_PROCESS_TEST_ONLY_LOCAL_FAULT")
                        .put("fault", fault.name()).put("realProviderCalls", 0).putNull("formalScore")
                        .put("publicationEligible", false).put("formalAdmission", false)
                        .put("evidenceSha256", before).put("sourceSha256", input.sourceHash)
                        .put("replaySha256", hash(input.script)).put("adapterSha256", hash(input.script.getParent().resolve("e1_verify.py")));
                record.set("verification", JSON.valueToTree(result));
                Path output = input.root.resolve("local-fault-verification.json");
                Files.createFile(output, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                JSON.writeValue(output.toFile(), record);
                assertTrue(result.sandboxed()); assertEquals(0, result.exitCode(), result.stderr());
                assertEquals(fault == E1IndependentReplayTest.LocalFault.MERGE_AFTER_TOOL ? 100 : 0,
                        JSON.readTree(result.stdout()).path("components").get(0).path("earnedPoints").asInt());
                assertEquals(before, hash(input.evidenceFile));
            }
        } finally { temp = original; }
    }

    @Test @EnabledIfSystemProperty(named="paicli.test.verifier.image", matches="sha256:[a-f0-9]{64}")
    void scoresAllNativeControlsInIndependentDockerVerifier() throws Exception {
        for (var control : E1IndependentReplayTest.Control.values()) {
            var input = input(control); String before = hash(input.evidenceFile);
            Files.setPosixFilePermissions(input.evidenceFile, PosixFilePermissions.fromString("r--------"));
            var verifier = new DockerBenchmarkVerifier(Path.of("/usr/local/bin/docker"), System.getProperty("paicli.test.verifier.image"));
            var result = verifier.verify(new CaseDefinition.VerifierInvocation(input.script.getParent(),
                            List.of("python3", "-B", "e1_verify.py", "{workspace}", "{evidence}")),
                    input.workspace, input.home, input.evidenceFile, Duration.ofSeconds(20));
            assertTrue(result.sandboxed()); assertFalse(result.stdoutTruncated()); assertFalse(result.stderrTruncated());
            assertScore(control, result.exitCode(), result.stdout(), result.stderr()); assertEquals(before, hash(input.evidenceFile));
        }
    }

    @Test void unrepresentedUsageAndModelResponsesRemainInvalidNotZero() throws Exception {
        var input = input(E1IndependentReplayTest.Control.CORRECT);
        var good = (ObjectNode)JSON.readTree(input.evidenceFile.toFile());
        List<java.util.function.Consumer<ObjectNode>> mutations = List.of(
                n -> n.with("llmMetrics").put("successfulCalls", 6),
                n -> n.with("llmMetrics").put("systemPromptSha256", "a".repeat(64)),
                n -> n.with("plan").with("audit").put("schemaVersion", 1),
                n -> ((ObjectNode)n.path("plan").path("audit").path("providerTurns").get(0).path("response")).put("usagePresent", false),
                n -> ((ObjectNode)n.path("plan").path("audit").path("providerTurns").get(0).path("response")).put("resolvedModel", "other"),
                n -> ((ObjectNode)n.path("plan").path("audit").path("providerTurns").get(0).path("response")).put("outputTokens", 16385));
        for (var mutation : mutations) {
            var bad = good.deepCopy(); mutation.accept(bad); JSON.writeValue(input.evidenceFile.toFile(), bad);
            var result = BenchmarkSubprocess.run(new ProcessBuilder("python3", "-B", input.script.getParent().resolve("e1_verify.py").toString(),
                    input.workspace.toString(), input.evidenceFile.toString()), null, Duration.ofSeconds(10), 32768, 32768);
            assertEquals(2, result.exitCode()); assertTrue(result.stdout().isBlank());
        }
    }

    private E1IndependentReplayTest.Input input(E1IndependentReplayTest.Control control) throws Exception {
        return input(control, null);
    }
    private E1IndependentReplayTest.Input input(E1IndependentReplayTest.Control control, E1IndependentReplayTest.LocalFault fault) throws Exception {
        return input(control, fault, null);
    }
    private E1IndependentReplayTest.Input input(E1IndependentReplayTest.Control control, E1IndependentReplayTest.LocalFault fault,
                                               E1IndependentReplayTest.ReplanFault replanFault) throws Exception {
        var helper = new E1IndependentReplayTest(); helper.temp = temp;
        var input = helper.nativeInput(control, true, fault, replanFault);
        assertEquals(input.prompt, E1FrozenOracle.parse(Files.readAllBytes(input.oracleFile)).prompt());
        Path bundle = input.script.getParent();
        try (var in = getClass().getResourceAsStream("/benchmark/e1_verify.py")) { assertNotNull(in); Files.copy(in, bundle.resolve("e1_verify.py")); }
        Path oracles = Files.createDirectory(bundle.resolve("oracles")); Files.copy(input.oracleFile, oracles.resolve("E1.json"));
        Path contracts = Files.createDirectory(bundle.resolve("scoring-contracts"));
        var contract = JSON.createObjectNode().put("schemaVersion", 1).put("caseId", "E1").put("strictSuccessMinimum", 100)
                .put("verifierSha256", "a".repeat(64)).put("toolchainSha256", "b".repeat(64));
        var assertions = contract.putArray("assertions");
        for (String key : List.of("graph", "overlapping_lifecycles", "source_observed", "branch_results", "complete_dependency_inputs", "artifact"))
            assertions.addObject().put("id", "E1." + key).put("componentId", "strictTask").put("mandatory", true);
        var gates = contract.putArray("hardGates");
        for (String key : List.of("workspace_mutation", "forbidden_tool_or_path")) gates.addObject().put("id", "E1." + key);
        contract.putArray("components").addObject().put("id", "strictTask").put("maxPoints", 100).put("source", "DETERMINISTIC");
        JSON.writeValue(contracts.resolve("E1.json").toFile(), contract);
        var e = JSON.createObjectNode().put("schemaVersion", 5).put("caseId", "E1").put("repeat", 1)
                .put("mode", "plan").put("toolProfile", "FILE_ONLY").put("answer", "scripted native Plan control; not a model score");
        e.set("llmMetrics", JSON.valueToTree(BenchmarkEvidenceEnvelope.EvidenceMetricsV2.from(input.metrics)));
        e.set("toolExecutions", input.evidence.path("toolExecutions"));
        for (String kind : List.of("Workspace", "Bundle")) for (String field : List.of("TreeSha256", "FileCount", "TotalBytes")) e.putNull("verifier" + kind + field);
        var plan = e.putObject("plan").put("schemaVersion", 1).put("caseId", "E1").put("profile", E1FrozenOracle.PROFILE)
                .put("relayVersion", BenchmarkRelayProtocol.VERSION).put("sourceSha256", input.sourceHash)
                .put("promptSha256", BenchmarkRelayProtocol.textSha256(input.prompt));
        plan.set("audit", input.evidence.path("planAudit")); plan.set("scopedRequestFingerprints", input.evidence.path("scopedRequestFingerprints"));
        JSON.writeValue(input.evidenceFile.toFile(), e); return input;
    }
    private void assertScore(E1IndependentReplayTest.Control control, int code, String stdout, String stderr) throws Exception {
        assertEquals(0, code, control + ": " + stderr);
        int expected = E1IndependentReplayTest.expectedPass(control) ? 100 : 0;
        assertEquals(expected, JSON.readTree(stdout).path("components").get(0).path("earnedPoints").asInt(), control + ": " + stdout);
    }
    private static String hash(Path path) throws Exception { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
}
