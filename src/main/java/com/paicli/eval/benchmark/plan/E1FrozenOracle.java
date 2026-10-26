package com.paicli.eval.benchmark.plan;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Closed private E1 data contract. Parsing this source does not authorize a formal episode. */
public record E1FrozenOracle(int schemaVersion, String caseId, String expectedToolProfile,
                             String profile, String variantId, Map<String, String> files) {
    public static final String PROFILE = "e1-plan-join-v1";
    public static final String PATH = "validators/final/_private/oracles/E1.json";
    public static final String TITLE = "Plan DAG 并行与依赖汇总";
    public static final int MAX_BYTES = 131_072;
    private static final Set<String> FILES = Set.of("left.csv", "right.csv");
    private static final Pattern ROW = Pattern.compile("([A-Za-z0-9_\\-\\u4e00-\\u9fff]{1,64}),(-?(?:0|[1-9][0-9]{0,6}))");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);

    public E1FrozenOracle {
        if (schemaVersion != 2 || !"E1".equals(caseId) || !"FILE_ONLY".equals(expectedToolProfile)
                || !PROFILE.equals(profile) || variantId == null || !variantId.matches("[a-f0-9]{24}")
                || files == null || !files.keySet().equals(FILES))
            throw new IllegalArgumentException("invalid E1 source identity");
        files = Map.copyOf(files);
        for (String name : FILES) validateCsv(files.get(name));
    }

    public static E1FrozenOracle parse(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > MAX_BYTES) throw new IOException("E1 source exceeds size limit");
        JsonNode root = JSON.readTree(bytes);
        keys(root, Set.of("schemaVersion", "caseId", "expectedToolProfile", "profile", "variantId", "files"));
        if (!root.path("schemaVersion").isIntegralNumber() || !root.path("schemaVersion").canConvertToInt())
            throw new IOException("E1 source version must be an integer");
        for (String name : List.of("caseId", "expectedToolProfile", "profile", "variantId"))
            if (!root.path(name).isTextual()) throw new IOException("E1 source identity must be text");
        keys(root.path("files"), FILES);
        for (String name : FILES) if (!root.path("files").path(name).isTextual())
            throw new IOException("E1 source CSV must be text");
        return JSON.treeToValue(root, E1FrozenOracle.class);
    }

    public static String taskPrompt() {
        return "Use Plan mode for two independent CSV analyses followed by a dependent merge. "
                + "Create exactly three tasks with descriptions LEFT, RIGHT, and MERGE (original task IDs may vary). "
                + "LEFT and RIGHT must have no dependencies and run in overlapping task lifecycles; MERGE must depend on both. "
                + "LEFT reads only left.csv; RIGHT reads only right.csv. Each branch returns only a JSON object with "
                + "branch (left or right), rows (integer row count excluding header), ids (all id strings in source order), "
                + "and sum_cents (integer sum of amount_cents). Preserve every row, including negative amounts. "
                + "MERGE must consume both complete branch results, not reread the CSV files, and write report.json with "
                + "exactly left and right (the complete branch objects), and combined_cents (the sum of both sum_cents). "
                + "Do not modify the CSV inputs or create other files. Use only read_file and write_file; do not use commands or network. "
                + "The merge task may read report.json for verification. Do not wrap branch JSON in Markdown fences.";
    }

    public String prompt() { return "# E1 " + TITLE + "\n\n" + taskPrompt() + "\n\nVariant: " + variantId + "\n"; }

    /** Synthetic reference only; the independent Python verifier computes its own answers. */
    public Map<String, Object> referenceBranch(String side) {
        if (!Set.of("left", "right").contains(side)) throw new IllegalArgumentException("unknown E1 branch");
        String[] lines = files.get(side + ".csv").split("\n");
        List<String> ids = new ArrayList<>(); int sum = 0;
        for (int i = 1; i < lines.length; i++) {
            var match = ROW.matcher(lines[i]); if (!match.matches()) throw new IllegalStateException("validated E1 row changed");
            ids.add(match.group(1)); sum += Integer.parseInt(match.group(2));
        }
        var result = new LinkedHashMap<String, Object>(); result.put("branch", side); result.put("rows", ids.size());
        result.put("ids", List.copyOf(ids)); result.put("sum_cents", sum); return Collections.unmodifiableMap(result);
    }

    private static void validateCsv(String text) {
        if (text == null || text.getBytes(StandardCharsets.UTF_8).length > 32_768
                || !text.startsWith("id,amount_cents\n") || !text.endsWith("\n"))
            throw new IllegalArgumentException("invalid E1 CSV framing");
        String[] lines = text.split("\n", -1);
        if (lines.length < 4 || lines.length > 42) throw new IllegalArgumentException("invalid E1 CSV row count");
        var ids = new HashSet<String>();
        for (int i = 1; i < lines.length - 1; i++) {
            var match = ROW.matcher(lines[i]);
            if (!match.matches() || !ids.add(match.group(1))) throw new IllegalArgumentException("invalid E1 CSV row identity/value");
        }
    }

    private static void keys(JsonNode node, Set<String> expected) throws IOException {
        if (node == null || !node.isObject()) throw new IOException("invalid E1 source object");
        var actual = new HashSet<String>(); node.fieldNames().forEachRemaining(actual::add);
        if (!expected.equals(actual)) throw new IOException("invalid E1 source fields");
    }
}
