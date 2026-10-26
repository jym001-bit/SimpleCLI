package com.paicli.eval.benchmark.finalset.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.eval.benchmark.plan.E1FrozenOracle;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Source/scoring prototype tests, not catalog registration or formal execution authority. */
@Timeout(90)
class E1GeneratedVerifierTest {
    static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;

    @Test void deterministicPrivateSiblingSourcesAndExplicitlySyntheticReferences() throws Exception {
        Path a = generate("a", 1), b = generate("b", 1), c = generate("c", 2);
        assertEquals(files(a).keySet(), files(b).keySet());
        for (String path : files(a).keySet()) assertArrayEquals(files(a).get(path), files(b).get(path), path);
        assertNotEquals(Files.readString(a.resolve(E1FrozenOracle.PATH)), Files.readString(c.resolve(E1FrozenOracle.PATH)));
        for (Path root : List.of(a, b, c)) {
            assertScore(root, evidence(root), 100);
            var manifest = JSON.readTree(root.resolve("e1-prototype-manifest.json").toFile());
            assertEquals("IMPLEMENTED", manifest.path("implementationStatus").asText());
            assertEquals("NOT_INTEGRATED", manifest.path("runnerIntegrationStatus").asText());
            assertFalse(manifest.path("publicationEligible").asBoolean()); assertEquals(0, manifest.path("realProviderCalls").asInt());
            assertEquals(4, manifest.path("caseWeight").asInt());
            assertEquals(25, FinalSourceRecipeCatalog.implementedIds().size());
            assertFalse(FinalSourceRecipeCatalog.missingIds().contains("E1"));
        }
        assertThrows(java.io.IOException.class, () -> E1CaseMaterializer.materializePrototype(new PrivateSourceWriter(a), variant(1)));
    }

    @Test void strictJavaAndPythonSourceSchemasAndPromptAgree() throws Exception {
        Path root = generate("source", 3), file = root.resolve(E1FrozenOracle.PATH);
        byte[] bytes = Files.readAllBytes(file); var oracle = E1FrozenOracle.parse(bytes);
        assertEquals(Files.readString(root.resolve("prompts/final/E1.md")), oracle.prompt());
        assertEquals(E1FrozenOracle.TITLE, FinalSourceRecipeCatalog.require("E1").title());
        assertThrows(UnsupportedOperationException.class, () -> oracle.files().clear());
        List<Consumer<ObjectNode>> mutations = List.of(n -> n.put("schemaVersion", 2.0), n -> n.put("schemaVersion", "2"),
                n -> n.put("schemaVersion", 1), n -> n.put("expectedToolProfile", "LOCAL_COMMAND"), n -> n.put("caseId", "E2"),
                n -> n.put("variantId", "bad"), n -> n.put("profile", "arbitrary"), n -> n.put("extra", true),
                n -> n.with("files").put("extra.csv", "private"), n -> n.with("files").put("left.csv", true),
                n -> n.with("files").put("left.csv", "id,amount_cents\na,1\na,2\n"),
                n -> n.with("files").put("left.csv", "id,amount_cents\na,1.0\nb,2\n"),
                n -> n.with("files").put("left.csv", "id,amount_cents\na,1\n"),
                n -> n.with("files").put("left.csv", "id,amount_cents\na,1\nb,2"),
                n -> n.with("files").put("left.csv", "id,amount_cents\r\na,1\r\nb,2\r\n"),
                n -> n.with("files").put("left.csv", "id,amount_cents\na,10000000\nb,2\n"));
        for (var mutation : mutations) {
            var bad = (ObjectNode) JSON.readTree(bytes); mutation.accept(bad); byte[] malformed = JSON.writeValueAsBytes(bad);
            assertThrows(java.io.IOException.class, () -> E1FrozenOracle.parse(malformed), bad.toString());
            Files.write(file, malformed); assertEquals(2, run(root, evidence(root)).code());
        }
        Files.write(file, bytes);
        String duplicate = new String(bytes, StandardCharsets.UTF_8).replaceFirst("\\{", "{\"caseId\":\"E1\",");
        assertThrows(java.io.IOException.class, () -> E1FrozenOracle.parse(duplicate.getBytes(StandardCharsets.UTF_8)));
        assertThrows(java.io.IOException.class, () -> E1FrozenOracle.parse((new String(bytes, StandardCharsets.UTF_8) + " {}").getBytes(StandardCharsets.UTF_8)));
        assertScore(root, evidence(root), 100);
    }

    @Test void artifactAndWorkspaceFailuresProduceZeroButEvidenceAndPolicyDriftHaveNoScore() throws Exception {
        Path root = generate("negative", 4); var original = evidence(root); assertScore(root, original, 100);
        Path report = root.resolve("references/final/E1/workspace/report.json"); String saved = Files.readString(report);
        var answer = (ObjectNode)JSON.readTree(saved); answer.put("combined_cents", answer.path("combined_cents").asInt() + 1);
        Files.writeString(report, answer.toString()); assertScore(root, original, 0); Files.writeString(report, saved);
        Files.writeString(root.resolve("references/final/E1/workspace/extra.txt"), "unrequested"); assertScore(root, original, 0);
        List<Consumer<ObjectNode>> mutations = List.of(n -> n.put("schemaVersion", 5.0), n -> n.put("mode", "react"),
                n -> n.put("toolProfile", "LOCAL_COMMAND"), n -> n.put("repeat", true), n -> n.remove("plan"),
                n -> n.with("plan").put("sourceSha256", "0".repeat(64)), n -> n.with("plan").put("promptSha256", "0".repeat(64)),
                n -> n.with("llmMetrics").put("calls", 8), n -> n.with("llmMetrics").put("inputTokens", 0),
                n -> n.with("llmMetrics").put("usageComplete", false), n -> n.with("llmMetrics").put("resolvedModel", "different"),
                n -> n.with("llmMetrics").put("initialToolSchemaSha256", "0".repeat(64)),
                n -> n.put("verifierWorkspaceTreeSha256", "0".repeat(64)), n -> n.put("unregistered", "field"));
        for (var mutation : mutations) { var bad = original.deepCopy(); mutation.accept(bad); var result = run(root, bad);
            assertEquals(2, result.code(), result.output()); assertFalse(result.output().contains("earnedPoints")); }
        Path contract = root.resolve("validators/final/_private/scoring-contracts/E1.json");
        var policy = (ObjectNode)JSON.readTree(contract.toFile()); ((ObjectNode)policy.path("components").get(0)).put("maxPoints", true);
        Files.writeString(contract, policy.toString()); assertEquals(2, run(root, original).code());
    }

    @Test void refusesVersionControlRootsWithoutTouchingTheirContents() throws Exception {
        Path vcs = Files.createDirectory(temp.resolve("repo")); Files.createDirectory(vcs.resolve(".git"));
        Path root = Files.createDirectory(vcs.resolve("empty"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        assertThrows(java.io.IOException.class, () -> E1CaseMaterializer.materializePrototype(new PrivateSourceWriter(root), variant(1)));
        try (var entries = Files.list(root)) { assertEquals(0, entries.count()); }
    }

    @Test @EnabledIfSystemProperty(named="paicli.test.e1.prototype.output", matches=".+")
    void retainOneNewPrivatePrototypeForInspectionWithoutOpeningProductionAdmission() throws Exception {
        Path root = Path.of(System.getProperty("paicli.test.e1.prototype.output")).toRealPath();
        E1CaseMaterializer.materializePrototype(new PrivateSourceWriter(root), variant(19));
        assertScore(root, evidence(root), 100);
        System.out.println("E1 private source prototype retained; NOT_INTEGRATED, realProviderCalls=0");
    }

    private Path generate(String name, int seed) throws Exception {
        Path root = Files.createDirectory(temp.resolve(name), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toRealPath();
        E1CaseMaterializer.materializePrototype(new PrivateSourceWriter(root), variant(seed)); return root;
    }
    private static SeededVariant variant(int seed) { byte[] bytes = new byte[32]; Arrays.fill(bytes, (byte)seed); return SeededVariant.derive(bytes, "E1", "1"); }
    private static ObjectNode evidence(Path root) throws Exception { return (ObjectNode)JSON.readTree(root.resolve("references/final/E1/evidence.json").toFile()); }
    private void assertScore(Path root, ObjectNode evidence, int score) throws Exception {
        var result = run(root, evidence); assertEquals(0, result.code(), result.output());
        assertEquals(score, JSON.readTree(result.output()).path("components").get(0).path("earnedPoints").asInt());
    }
    private Result run(Path root, ObjectNode evidence) throws Exception {
        Path input = Files.createTempFile(temp, "evidence-", ".json"); Files.writeString(input, evidence.toString());
        var process = new ProcessBuilder(root.resolve("validators/final/E1").toString(), root.resolve("references/final/E1/workspace").toString(), input.toString()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }
    private Map<String, byte[]> files(Path root) throws Exception {
        var result = new TreeMap<String, byte[]>(); try (var paths = Files.walk(root)) {
            for (Path p : paths.filter(Files::isRegularFile).toList()) result.put(root.relativize(p).toString(), Files.readAllBytes(p));
        } return result;
    }
    private record Result(int code, String output) { }
}
