package com.paicli.eval.benchmark.finalset.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Pure offline reference/negative evidence controls for the frozen formal adapter. */
class D4GeneratedVerifierTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;
    private Path root;
    @BeforeEach void generate() throws Exception {
        var parent = temp.toRealPath(); Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"));
        root = new FinalSourceGenerator().generateIncompleteSource(new FinalSourceGenerator.GenerationRequest(
                parent.resolve("source"), "12345678abcdef90".repeat(4))).sourceRoot();
    }
    @AfterEach void restorePermissions() throws Exception {
        try (var walk = Files.walk(temp)) {
            for (Path p : walk.toList()) if (!Files.isSymbolicLink(p))
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(Files.isDirectory(p) ? "rwx------" : "rw-------"));
        }
    }
    @Test void referencePassesButWrongAnswerAndChangedWorkspaceAreValidZeros() throws Exception {
        var original = evidence(); assertScore(original, 100);
        var wrong = original.deepCopy();
        var answer = (ObjectNode) JSON.readTree(wrong.path("answer").asText()); answer.put("timeout_seconds", 5);
        wrong.put("answer", answer.toString());
        var turns = wrong.path("mockWeb").path("providerTurns"); ((ObjectNode) turns.get(turns.size() - 1)).put("content", answer.toString());
        assertScore(wrong, 0);
        Files.writeString(root.resolve("references/final/D4/workspace/README.md"), "changed by Candidate");
        assertScore(original, 0);
    }
    @Test void incompleteContradictoryAndCoercedEvidenceProduceNoScore() throws Exception {
        List<Consumer<ObjectNode>> mutations = List.of(
                n -> n.remove("mockWeb"), n -> n.put("schemaVersion", 4.0), n -> n.put("schemaVersion", "4"),
                n -> n.put("mode", "plan"), n -> n.put("caseId", "D3"), n -> n.put("toolProfile", "FILE_ONLY"),
                n -> n.put("repeat", true), n -> n.with("llmMetrics").put("toolCalls", 3.0),
                n -> n.with("mockWeb").put("schemaVersion", "1"), n -> n.with("mockWeb").put("relayVersion", 7),
                n -> n.with("mockWeb").put("mockSourceSha256", "0".repeat(64)),
                n -> n.with("mockWeb").put("promptSha256", "0".repeat(64)), n -> n.with("mockWeb").remove("providerTurns"),
                n -> n.with("mockWeb").putArray("providerTurns"), n -> n.with("mockWeb").putArray("relayEvents"),
                n -> n.with("mockWeb").putArray("events"), n -> n.put("answer", "contradicts host final answer"),
                n -> n.put("extra", "unregistered"));
        for (var mutation : mutations) {
            var value = evidence(); mutation.accept(value); var run = run(value);
            assertEquals(2, run.code(), run.output()); assertFalse(run.output().contains("earnedPoints"));
        }
    }
    @Test void scoringPolicyIsTypeExactAndDoesNotAcceptBooleanPoints() throws Exception {
        Path path = root.resolve("validators/final/_private/scoring-contracts/D4.json");
        var contract = (ObjectNode) JSON.readTree(path.toFile());
        ((ObjectNode) contract.path("components").get(0)).put("maxPoints", true);
        Files.writeString(path, contract.toString());
        assertEquals(2, run(evidence()).code());
    }
    private ObjectNode evidence() throws Exception { return (ObjectNode) JSON.readTree(root.resolve("references/final/D4/evidence.json").toFile()); }
    private void assertScore(ObjectNode evidence, int score) throws Exception {
        var run = run(evidence); assertEquals(0, run.code(), run.output());
        assertEquals(score, JSON.readTree(run.output()).path("components").get(0).path("earnedPoints").asInt());
    }
    private Result run(ObjectNode evidence) throws Exception {
        Path input = temp.resolve("input.json"); Files.writeString(input, evidence.toString());
        var process = new ProcessBuilder(root.resolve("validators/final/D4").toString(),
                root.resolve("references/final/D4/workspace").toString(), input.toString()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    }
    private record Result(int code, String output) { }
}
