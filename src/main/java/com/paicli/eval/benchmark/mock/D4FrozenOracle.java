package com.paicli.eval.benchmark.mock;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

/** Strict private Web source. Registration with formal admission is a separate integration step. */
public record D4FrozenOracle(int schemaVersion, String caseId, String expectedToolProfile, String variantId,
                             Map<String, String> baselineFiles, D4WebMock.Definition definition) {
    public static final String PROFILE = "d4-grounded-web-v1";
    public static final String PATH = "validators/final/_private/oracles/D4.json";
    public static final int MAX_BYTES = 32_768;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);

    public D4FrozenOracle {
        if ((schemaVersion != 1 && schemaVersion != 2) || !"D4".equals(caseId) || !"MOCK_WEB".equals(expectedToolProfile)
                || variantId == null || !variantId.matches("[a-f0-9]{24}") || definition == null
                || baselineFiles == null || (schemaVersion == 1 ? !baselineFiles.isEmpty()
                : !baselineFiles.keySet().equals(Set.of("README.md")) || baselineFiles.get("README.md") == null
                || !baselineFiles.get("README.md").matches("[a-f0-9]{64}"))) throw new IllegalArgumentException("invalid D4 source");
        baselineFiles = Map.copyOf(baselineFiles);
    }
    public static D4FrozenOracle parse(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > MAX_BYTES) throw new IOException("D4 source exceeds size limit");
        JsonNode root = JSON.readTree(bytes);
        keys(root, Set.of("schemaVersion", "caseId", "expectedToolProfile", "variantId", "baselineFiles", "definition"));
        integer(root.path("schemaVersion"));
        for (String key : Set.of("caseId", "expectedToolProfile", "variantId")) text(root.path(key));
        keys(root.path("baselineFiles"), root.path("schemaVersion").asInt() == 2 ? Set.of("README.md") : Set.of());
        if (root.path("schemaVersion").asInt() == 2) text(root.path("baselineFiles").path("README.md"));
        var d = root.path("definition");
        keys(d, Set.of("project", "release", "timeoutSeconds", "cacheEntries", "releaseUrl", "migrationUrl", "snippetUrl", "bodyUrl", "queryUrl"));
        for (String key : Set.of("timeoutSeconds", "cacheEntries")) integer(d.path(key));
        for (String key : Set.of("project", "release", "releaseUrl", "migrationUrl", "snippetUrl", "bodyUrl", "queryUrl")) text(d.path(key));
        return JSON.treeToValue(root, D4FrozenOracle.class);
    }
    private static void keys(JsonNode node, Set<String> expected) throws IOException {
        if (node == null || !node.isObject()) throw new IOException("invalid D4 object");
        var actual = new java.util.HashSet<String>(); node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw new IOException("invalid D4 fields");
    }
    private static void integer(JsonNode node) throws IOException {
        if (!node.isIntegralNumber() || !node.canConvertToInt()) throw new IOException("invalid D4 integer");
    }
    private static void text(JsonNode node) throws IOException { if (!node.isTextual()) throw new IOException("invalid D4 text"); }
    public D4WebMock newService() { return new D4WebMock(definition); }
}
