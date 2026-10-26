package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.eval.benchmark.finalset.generator.FinalSourceGenerator;
import com.paicli.eval.benchmark.mock.F4FrozenOracle;
import com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Strict v6 envelope/scoring adapter checks; references are synthetic, not model outcomes. */
class F4FormalAdapterTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;

    @Test void d3AndF4TerminalProjectionShapesRemainStrictAcrossCommandObservationUpgrade() throws Exception {
        Path parent = temp.toRealPath(); Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"));
        Path root = new FinalSourceGenerator().generateIncompleteSource(new FinalSourceGenerator.GenerationRequest(
                parent.resolve("projection-source"), "9876543210abcdef".repeat(4))).sourceRoot();
        for (String caseId : List.of("D3", "F4")) {
            Path evidence = root.resolve("references/final/" + caseId + "/evidence.json");
            var original = (ObjectNode)JSON.readTree(evidence.toFile());
            var baseline = runCase(root, evidence, caseId);
            assertEquals(0, baseline.exitCode(), caseId + ": " + baseline.stderr());
            assertEquals(100, JSON.readTree(baseline.stdout()).path("components").get(0).path("earnedPoints").intValue());
            var legacy = original.deepCopy();
            terminal(legacy).remove(List.of("commandObservations", "commandObservationFailures"));
            JSON.writeValue(evidence.toFile(), legacy);
            var old = runCase(root, evidence, caseId); assertEquals(0, old.exitCode(), caseId + ": " + old.stderr());
            assertEquals(100, JSON.readTree(old.stdout()).path("components").get(0).path("earnedPoints").intValue());
            List<Consumer<ObjectNode>> changes = List.of(
                    e -> terminal(e).withArray("commandObservations").addObject(),
                    e -> terminal(e).put("commandObservationFailures", false),
                    e -> terminal(e).put("commandObservationFailures", 0.0),
                    e -> terminal(e).put("commandObservationFailures", 1),
                    e -> terminal(e).remove("commandObservations"),
                    e -> terminal(e).remove("commandObservationFailures"));
            for (var change : changes) {
                var bad = original.deepCopy(); change.accept(bad); JSON.writeValue(evidence.toFile(), bad);
                var invalid = runCase(root, evidence, caseId);
                assertEquals(2, invalid.exitCode(), caseId + ": " + invalid.stdout()); assertTrue(invalid.stdout().isBlank());
            }
            JSON.writeValue(evidence.toFile(), original);
        }
    }

    private static ObjectNode terminal(ObjectNode evidence) {
        for (var event : evidence.path("mockMcp").path("relayEvents"))
            if (event.path("request").path("header").path("type").asText().equals("WORKER_COMPLETE"))
                return (ObjectNode)event.path("request");
        throw new AssertionError("missing terminal projection");
    }

    private static BenchmarkSubprocess.Result runCase(Path root, Path evidence, String caseId) throws Exception {
        return BenchmarkSubprocess.run(new ProcessBuilder("/bin/sh", root.resolve("validators/final/" + caseId).toString(),
                root.resolve("references/final/" + caseId + "/workspace").toString(), evidence.toString()), null, Duration.ofSeconds(10), 32768, 32768);
    }
    @Test void formalReferencePassesButCrossProfileMessagesAndPolicyCoercionNeverScore() throws Exception {
        Path parent = temp.toRealPath(); Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"));
        Path root = new FinalSourceGenerator().generateIncompleteSource(new FinalSourceGenerator.GenerationRequest(
                parent.resolve("source"), "abcd0987654321ef".repeat(4))).sourceRoot();
        Path evidence = root.resolve("references/final/F4/evidence.json"), contract = root.resolve("validators/final/_private/scoring-contracts/F4.json");
        var original = (ObjectNode)JSON.readTree(evidence.toFile());
        var originalContract = (ObjectNode)JSON.readTree(contract.toFile());
        var oracle = F4FrozenOracle.parse(Files.readAllBytes(root.resolve(F4FrozenOracle.PATH)));
        assertEquals("MOCK_MCP", oracle.expectedToolProfile());
        var good = run(root, evidence); assertEquals(0, good.exitCode(), good.stderr());
        assertEquals(100, JSON.readTree(good.stdout()).path("components").get(0).path("earnedPoints").asInt());
        Map<String, Consumer<ObjectNode>> mutations = new LinkedHashMap<>();
        mutations.put("old_envelope", e -> e.put("schemaVersion", 3));
        mutations.put("cross_case", e -> e.put("caseId", "D3"));
        mutations.put("missing_observations", e -> ((ObjectNode)e.path("mockMcp")).remove("providerTurns"));
        mutations.put("empty_observations", e -> ((ObjectNode)e.path("mockMcp")).putArray("providerTurns"));
        mutations.put("destructive_boolean", e -> ((ObjectNode)e.path("mockMcp")).put("destructiveCalls", false));
        mutations.put("false_call_count", e -> ((ObjectNode)e.path("llmMetrics")).put("calls", 0));
        mutations.put("raw_development_prompt", e -> {
            var messages = e.path("mockMcp").path("providerTurns").get(0).path("messages");
            var user = (ObjectNode)messages.get(messages.size() - 1); String raw = oracle.newService().prompt();
            user.put("contentSha256", BenchmarkRelayProtocol.textSha256(raw)).put("contentChars", raw.length());
        });
        for (var mutation : mutations.entrySet()) {
            var changed = original.deepCopy(); mutation.getValue().accept(changed); assertNotEquals(original, changed);
            JSON.writeValue(evidence.toFile(), changed); var invalid = run(root, evidence);
            assertEquals(2, invalid.exitCode(), mutation.getKey()); assertTrue(invalid.stdout().isBlank(), mutation.getKey());
        }
        JSON.writeValue(evidence.toFile(), original);
        var changed = originalContract.deepCopy(); ((ObjectNode)changed.path("assertions").get(0)).put("mandatory", 1);
        JSON.writeValue(contract.toFile(), changed); var invalid = run(root, evidence);
        assertEquals(2, invalid.exitCode()); assertTrue(invalid.stdout().isBlank());
        JSON.writeValue(contract.toFile(), originalContract); assertEquals(0, run(root, evidence).exitCode());
    }
    private static BenchmarkSubprocess.Result run(Path root, Path evidence) throws Exception {
        return BenchmarkSubprocess.run(new ProcessBuilder("/bin/sh", root.resolve("validators/final/F4").toString(),
                root.resolve("references/final/F4/workspace").toString(), evidence.toString()), null, Duration.ofSeconds(10), 32768, 32768);
    }
}
