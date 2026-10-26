package com.paicli.eval.benchmark.team;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Closed private E2 contract. Parsing this source does not authorize a formal episode. */
public record E2FrozenOracle(int schemaVersion, String caseId, String expectedToolProfile,
                             String profile, String variantId, Map<String, String> files,
                             List<ExpectedWrite> expectedWrites, boolean requireNoConflicts,
                             List<String> requireWorkspaceFiles, int minApprovedReviews,
                             String docPath, boolean docConsumesDependencies) {
    public static final String PROFILE = "e2-team-code-doc-v1";
    public static final String PATH = "validators/final/_private/oracles/E2.json";
    public static final String TITLE = "代码测试文档的 Team 协作";
    public static final int MAX_BYTES = 131_072;
    private static final Set<String> FILES = Set.of("README.md", "spec/rules.md", "check.sh");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);

    public E2FrozenOracle {
        if (schemaVersion != 1 || !"E2".equals(caseId) || !"LOCAL_COMMAND".equals(expectedToolProfile)
                || !PROFILE.equals(profile) || variantId == null || !variantId.matches("[a-f0-9]{24}")
                || files == null || !files.keySet().equals(FILES)
                || expectedWrites == null || expectedWrites.size() != 3
                || !requireNoConflicts || requireWorkspaceFiles == null || requireWorkspaceFiles.size() != 3
                || minApprovedReviews != 3 || !"docs/notes.md".equals(docPath) || !docConsumesDependencies)
            throw new IllegalArgumentException("invalid E2 source identity");
        files = Map.copyOf(files);
        expectedWrites = List.copyOf(expectedWrites);
        requireWorkspaceFiles = List.copyOf(requireWorkspaceFiles);
        for (String name : FILES) validateFixture(name, files.get(name));
    }

    public record ExpectedWrite(String path, String role) {
        public ExpectedWrite {
            if (path == null || path.isBlank() || !"WORKER".equals(role))
                throw new IllegalArgumentException("invalid E2 expected write");
        }
    }

    public static E2FrozenOracle parse(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > MAX_BYTES) throw new IOException("E2 source exceeds size limit");
        JsonNode root = JSON.readTree(bytes);
        keys(root, Set.of("schemaVersion", "caseId", "expectedToolProfile", "profile", "variantId",
                "files", "expectedWrites", "requireNoConflicts", "requireWorkspaceFiles",
                "minApprovedReviews", "docPath", "docConsumesDependencies"));
        if (!root.path("schemaVersion").isIntegralNumber() || !root.path("schemaVersion").canConvertToInt())
            throw new IOException("E2 source version must be an integer");
        for (String name : List.of("caseId", "expectedToolProfile", "profile", "variantId", "docPath"))
            if (!root.path(name).isTextual()) throw new IOException("E2 source identity must be text");
        if (!root.path("requireNoConflicts").isBoolean() || !root.path("docConsumesDependencies").isBoolean()
                || !root.path("minApprovedReviews").isIntegralNumber())
            throw new IOException("E2 source contract fields must be typed");
        keys(root.path("files"), FILES);
        for (String name : FILES) if (!root.path("files").path(name).isTextual())
            throw new IOException("E2 source fixture must be text");
        JsonNode writes = root.path("expectedWrites");
        if (!writes.isArray() || writes.size() != 3) throw new IOException("E2 expected writes must list three entries");
        for (JsonNode write : writes) {
            if (!write.isObject() || write.size() != 2 || !write.path("path").isTextual()
                    || !write.path("role").isTextual()) throw new IOException("invalid E2 expected write");
        }
        JsonNode required = root.path("requireWorkspaceFiles");
        if (!required.isArray() || required.size() != 3) throw new IOException("E2 required files must list three entries");
        for (JsonNode file : required) if (!file.isTextual()) throw new IOException("E2 required file must be text");
        return JSON.treeToValue(root, E2FrozenOracle.class);
    }

    public static String taskPrompt() {
        return "Use Team mode with three worker steps named CODE, TEST, and DOC (original step IDs may vary). "
                + "CODE has no dependencies and writes src/triage.py implementing classify(amount) exactly as "
                + "spec/rules.md defines: it must return the lowercase tier name for the seeded thresholds and be a "
                + "complete runnable Python 3 module without third-party imports. TEST has no dependencies and writes "
                + "tests/test_triage.py containing at least one assert per spec example plus one negative assertion; "
                + "then execute the provided checker exactly once with `bash check.sh src/triage.py` and use its real "
                + "output. DOC depends on both CODE and TEST, consumes their complete results instead of rereading "
                + "inputs, and writes docs/notes.md containing both worker results verbatim in its dependency section. "
                + "Do not modify README.md, spec/rules.md, or check.sh; do not create other files; do not use network. "
                + "Every step must pass review before the run completes. Do not wrap code or results in Markdown fences "
                + "inside the files.";
    }

    public String prompt() { return "# E2 " + TITLE + "\n\n" + taskPrompt() + "\n\nVariant: " + variantId + "\n"; }

    private static void validateFixture(String name, String text) {
        if (text == null || text.isEmpty() || text.getBytes(StandardCharsets.UTF_8).length > 32_768)
            throw new IllegalArgumentException("invalid E2 fixture file: " + name);
        if (!text.endsWith("\n")) throw new IllegalArgumentException("E2 fixture must end with newline: " + name);
    }

    private static void keys(JsonNode node, Set<String> expected) throws IOException {
        if (node == null || !node.isObject()) throw new IOException("invalid E2 source object");
        var actual = new HashSet<String>(); node.fieldNames().forEachRemaining(actual::add);
        if (!expected.equals(actual)) throw new IOException("invalid E2 source fields");
    }
}
