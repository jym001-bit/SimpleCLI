package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.eval.benchmark.mock.D3ApprovalCalendarMock;
import com.paicli.eval.benchmark.mock.D3FrozenOracle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** Read-only retrospective qualification of saved SCRIPTED Docker episodes. No provider calls, no formal score. */
@EnabledIfSystemProperty(named = "paicli.test.d3.saved.replay", matches = "true")
@Timeout(120)
class D3SavedDockerReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void replaysBothSavedBatchesWithoutChangingThemOrUsingTheirHostVerdict() throws Exception {
        Path output = Path.of(System.getProperty("paicli.test.d3.replay.output")).toRealPath();
        assertFalse(output.startsWith(Path.of("").toRealPath()));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(output));
        String image = System.getProperty("paicli.test.verifier.image");
        assertNotNull(image);
        var summary = JSON.createObjectNode().put("kind", "D3_RETROSPECTIVE_SCRIPTED_DOCKER_REPLAY_NOT_FORMAL")
                .put("publishable", false).put("realProviderCalls", 0).putNull("formalScore");
        var rows = summary.putArray("episodes");
        List<String> controls = List.of("correct", "premature_write", "self_approve_tool", "change_key", "repeat_same_key", "cancel_after_create");
        for (int batch = 1; batch <= 2; batch++) {
            Path source = Path.of(System.getProperty("paicli.test.d3.replay.source" + batch)).toRealPath();
            assertFalse(source.startsWith(Path.of("").toRealPath()));
            for (String control : controls) {
                Path original = source.resolve(control).resolve("result.json");
                String before = hash(original);
                assertTrue(Files.size(original) < 16 * 1048576);
                JsonNode saved = JSON.readTree(Files.readAllBytes(original));
                assertEquals("D3_SCRIPTED_REAL_DOCKER_CONTROL_NOT_MODEL_EVALUATION", saved.path("kind").asText());
                Path artifacts = original.getParent().resolve("worker-docker-tmp/artifact-staging");
                assertEquals(saved.path("candidateSha256").asText(), hash(artifacts.resolve("candidate.jar")));
                assertEquals(saved.path("runnerSha256").asText(), hash(artifacts.resolve("runner.jar")));
                // Old records lacked an oracle snapshot. A retrospective source is permissible only
                // with an explicit label, known control seed, and identical preserved mock bytecode.
                assertSameMockBytecode(artifacts.resolve("candidate.jar"));
                try (var entries = Files.list(original.getParent().resolve("workspace"))) {
                    assertTrue(entries.findAny().isEmpty(), "the saved workspace baseline must actually be empty");
                }
                Path episode = BenchmarkProcessEnvironment.preparePrivateDirectory(output.resolve("batch" + batch + "-" + control));
                Path workspace = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("workspace"));
                Path home = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("home"));
                Path bundle = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("bundle"));
                Path evidenceDir = BenchmarkProcessEnvironment.preparePrivateDirectory(episode.resolve("verifier-evidence"));
                byte[] entropy = new byte[32]; Arrays.fill(entropy, (byte) 27);
                var oracle = new D3FrozenOracle("D3", "MOCK_MCP", "a".repeat(24), D3FrozenOracle.INTERACTION_MODE,
                        D3FrozenOracle.APPROVAL_SCRIPT, Map.of(), D3ApprovalCalendarMock.fromEntropy(entropy));
                Path oraclePath = bundle.resolve("oracle.json"); write(oraclePath, JSON.valueToTree(oracle));
                assertEquals(oracle, D3FrozenOracle.parse(Files.readAllBytes(oraclePath)));
                Path script = bundle.resolve("d3_replay.py");
                try (var in = getClass().getResourceAsStream("/benchmark/d3_replay.py")) { assertNotNull(in); Files.copy(in, script); }
                ObjectNode evidence = JSON.createObjectNode().put("schemaVersion", 1).put("caseId", "D3")
                        .put("profile", D3FrozenOracle.PROFILE).put("relayVersion", saved.path("relayVersion").intValue())
                        .put("mockSourceSha256", hash(oraclePath)).put("answer", saved.path("execution").path("response").path("answer").asText())
                        .put("modelToolCalls", saved.path("execution").path("response").path("metrics").path("toolCalls").longValue())
                        .put("initialStateSha256", saved.path("initialStateSha256").asText())
                        .put("finalStateSha256", saved.path("finalStateSha256").asText()).put("sideEffects", saved.path("writes").intValue());
                evidence.set("toolExecutions", saved.path("execution").path("toolExecutions"));
                evidence.set("events", saved.path("mockAudit")); evidence.set("relayEvents", saved.path("relayAudit"));
                Path evidenceFile = evidenceDir.resolve("evidence.json"); write(evidenceFile, evidence);
                Map<Path, String> frozen = new HashMap<>();
                for (Path path : List.of(script, oraclePath, evidenceFile)) {
                    frozen.put(path, hash(path)); Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r--------"));
                }
                var verifier = new DockerBenchmarkVerifier(Path.of("/usr/local/bin/docker"), image);
                var invocation = new CaseDefinition.VerifierInvocation(bundle,
                        List.of("python3", "-B", "d3_replay.py", "oracle.json", "{workspace}", "{evidence}"));
                var result = verifier.verify(invocation, workspace, home, evidenceFile, Duration.ofSeconds(20));
                var report = result.exitCode() != null && result.exitCode() == 0 ? JSON.readTree(result.stdout()) : JSON.nullNode();
                var record = rows.addObject().put("batch", batch).put("control", control).put("originalResultSha256", before)
                        .put("sourceDerivation", "retrospective-seed-27-and-identical-preserved-mock-bytecode")
                        .put("oracleSha256", hash(oraclePath)).put("replayProgramSha256", hash(script));
                record.set("verifier", JSON.valueToTree(result)); record.set("report", report);
                write(episode.resolve("replay-result.json"), record);
                assertEquals(before, hash(original), "saved result was modified");
                for (var entry : frozen.entrySet()) assertEquals(entry.getValue(), hash(entry.getKey()));
                assertEquals(0, result.exitCode(), result.stderr()); assertTrue(result.sandboxed());
                assertEquals(control.equals("correct"), report.path("diagnosticSatisfied").asBoolean());
                assertEquals(saved.path("diagnosticSatisfied").asBoolean(), report.path("diagnosticSatisfied").asBoolean(),
                        "old verdict is compared only AFTER independent replay, never included in its input");
                System.out.println("D3 independent saved replay batch=" + batch + " control=" + control + " satisfied=" + report.path("diagnosticSatisfied"));
            }
        }
        write(output.resolve("summary.json"), summary);
    }

    private static void assertSameMockBytecode(Path candidate) throws Exception {
        try (var jar = new JarFile(candidate.toFile())) {
            var entries = jar.stream().filter(e -> e.getName().startsWith("com/paicli/eval/benchmark/mock/D3ApprovalCalendarMock") && e.getName().endsWith(".class")).toList();
            assertTrue(entries.size() >= 8);
            for (var entry : entries) try (var old = jar.getInputStream(entry); var current = D3SavedDockerReplayTest.class.getResourceAsStream("/" + entry.getName())) {
                assertNotNull(current); assertArrayEquals(old.readAllBytes(), current.readAllBytes(), entry.getName());
            }
        }
    }
    private static String hash(Path path) throws Exception { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
    private static void write(Path file, JsonNode value) throws Exception {
        try (var out = Files.newByteChannel(file, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            var bytes = java.nio.ByteBuffer.wrap(JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
            while (bytes.hasRemaining()) out.write(bytes);
        }
    }
}
