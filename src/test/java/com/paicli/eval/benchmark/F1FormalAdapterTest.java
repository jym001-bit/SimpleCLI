package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.eval.benchmark.finalset.generator.FinalSourceGenerator;
import com.paicli.eval.benchmark.scoring.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

/** Generated synthetic reference against the independent adapter and Java score calculator. */
class F1FormalAdapterTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;
    @Test void referenceScoresAndCorruptionsAreRejectedWithoutNumericScores() throws Exception {
        temp = temp.toRealPath();
        Path root = new FinalSourceGenerator().generateIncompleteSource(new FinalSourceGenerator.GenerationRequest(
                temp.resolve("source"), "0123456789abcdef".repeat(4))).sourceRoot();
        Path evidence = root.resolve("references/final/F1/evidence.json"), workspace = root.resolve("references/final/F1/workspace");
        ObjectNode original = (ObjectNode)JSON.readTree(evidence.toFile());
        var contract = ScoringContract.load(root.resolve("validators/final/_private/scoring-contracts/F1.json"));
        var correct = run(root, workspace, evidence);
        assertEquals(0, correct.exitCode(), correct.stdout() + correct.stderr());
        assertEquals(100, ScoreCalculator.calculate(contract, VerifierScoringReport.parse(correct.stdout()), ScoreCalculator.JudgeAvailability.UNAVAILABLE).score());
        for (String corruption : new String[]{"boolean_version", "wrong_source", "wrong_prompt", "missing_observation", "unbound_projection", "tool_count", "tool_hash"}) {
            var bad = original.deepCopy(); var bound = (ObjectNode)bad.path("boundary");
            switch (corruption) {
                case "boolean_version" -> bad.put("schemaVersion", true);
                case "wrong_source" -> bound.put("sourceSha256", "0".repeat(64));
                case "wrong_prompt" -> bound.put("promptSha256", "0".repeat(64));
                case "missing_observation" -> bound.remove("observation");
                case "unbound_projection" -> bound.put("workspaceProjection", "all_links_allowed");
                case "tool_count" -> ((ObjectNode)bad.path("llmMetrics")).put("toolCalls", 0);
                case "tool_hash" -> ((ObjectNode)bad.path("toolExecutions").get(0)).put("resultSha256", "0".repeat(64));
            }
            Path file = temp.resolve(corruption + ".json"); Files.write(file, JSON.writeValueAsBytes(bad));
            var result = run(root, workspace, file);
            assertEquals(2, result.exitCode(), corruption + ": " + result.stdout() + result.stderr());
            assertTrue(result.stdout().isBlank());
        }
        var falseClaim = original.deepCopy(); falseClaim.put("answer", "{\"status\":\"copied\",\"verified\":1}");
        Path badAnswer = temp.resolve("valid-wrong-answer.json"); Files.write(badAnswer, JSON.writeValueAsBytes(falseClaim));
        var wrong = run(root, workspace, badAnswer); assertEquals(0, wrong.exitCode(), wrong.stderr());
        assertEquals(0, ScoreCalculator.calculate(contract, VerifierScoringReport.parse(wrong.stdout()), ScoreCalculator.JudgeAvailability.UNAVAILABLE).score());
        var longTrace = original.deepCopy();
        var events = (com.fasterxml.jackson.databind.node.ArrayNode)longTrace.path("toolExecutions");
        for (int i = 5; i <= 140; i++) {
            var repeated = ((ObjectNode)events.get(3)).deepCopy().put("ordinal", i);
            events.add(repeated); // Full valid rereads; call IDs may repeat in later responses.
        }
        ((ObjectNode)longTrace.path("llmMetrics")).put("toolCalls", events.size());
        Path longer = temp.resolve("complete-long-trace.json"); Files.write(longer, JSON.writeValueAsBytes(longTrace));
        var extended = run(root, workspace, longer); assertEquals(0, extended.exitCode(), extended.stderr());
        assertEquals(100, ScoreCalculator.calculate(contract, VerifierScoringReport.parse(extended.stdout()), ScoreCalculator.JudgeAvailability.UNAVAILABLE).score());
    }
    @ParameterizedTest
    @ValueSource(strings = {"oversized_copy", "excess_regular_files", "excess_with_link"})
    void candidateWorkspaceOverflowIsAValidHardGateFailure(String behavior) throws Exception {
        temp = temp.toRealPath();
        Path root = new FinalSourceGenerator().generateIncompleteSource(new FinalSourceGenerator.GenerationRequest(
                temp.resolve("source"), "0123456789abcdef".repeat(4))).sourceRoot();
        Path original = root.resolve("references/final/F1/evidence.json");
        Path workspace = root.resolve("references/final/F1/workspace");
        ObjectNode envelope = (ObjectNode)JSON.readTree(original.toFile());
        var events = (com.fasterxml.jackson.databind.node.ArrayNode)envelope.path("toolExecutions");
        // Explicit synthetic Candidate mutations after the successful reference
        // sequence. Each write remains in complete bounded tool evidence.
        int count = behavior.equals("oversized_copy") ? 1 : 140;
        for (int i = 0; i < count; i++) {
            String path = behavior.equals("oversized_copy") ? "result/copied.txt" : "extra-" + i + ".txt";
            String content = behavior.equals("oversized_copy") ? "x".repeat(200 * 1024) : "extra";
            Files.writeString(workspace.resolve(path), content);
            String args = JSON.createObjectNode().put("path", path).put("content", content).toString();
            int ordinal = events.size() + 1;
            events.add(JSON.valueToTree(BenchmarkToolExecutionEvidence.from(ordinal, "overflow-" + ordinal,
                    "write_file", args, "文件已写入: " + path, 0, false, true)));
        }
        if (behavior.equals("excess_with_link"))
            Files.createSymbolicLink(workspace.resolve("uninspected-link"), Path.of("../outside"));
        ((ObjectNode)envelope.path("llmMetrics")).put("toolCalls", events.size());
        Path evidence = temp.resolve("candidate-overflow.json");
        Files.write(evidence, JSON.writeValueAsBytes(envelope));
        var result = run(root, workspace, evidence);
        assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
        var report = JSON.readTree(result.stdout());
        assertTrue(java.util.stream.StreamSupport.stream(report.path("hardGates").spliterator(), false)
                .anyMatch(gate -> gate.path("id").asText().equals("F1.workspace_links_or_special") && gate.path("violated").asBoolean()));
        var contract = ScoringContract.load(root.resolve("validators/final/_private/scoring-contracts/F1.json"));
        assertEquals(0, ScoreCalculator.calculate(contract, VerifierScoringReport.parse(result.stdout()),
                ScoreCalculator.JudgeAvailability.UNAVAILABLE).score());
    }
    private BenchmarkSubprocess.Result run(Path source, Path workspace, Path evidence) throws Exception {
        var builder = new ProcessBuilder("python3", "-B", source.resolve("validators/final/_private/f1_verify.py").toString(), workspace.toString(), evidence.toString());
        BenchmarkProcessEnvironment.sanitize(builder.environment(), temp, temp);
        return BenchmarkSubprocess.run(builder, null, Duration.ofSeconds(10), 65536, 65536);
    }
}
