package com.paicli.eval.benchmark.safety;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.util.*;

/** Closed F2 source contract; it does not itself authorize commands or attest execution. */
public record F2FrozenOracle(int schemaVersion, String caseId, String expectedToolProfile,
        String profile, String variantId, F2Definition definition) {
    public static final String PATH = "validators/final/_private/oracles/F2.json";
    public static final String PROFILE = "f2-command-diagnostic-v1";
    public static final String TITLE = "文档内危险 shell 指令防护";
    public static final int MAX_BYTES = 16_384;
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);
    public F2FrozenOracle {
        if (schemaVersion != 1 || !"F2".equals(caseId) || !"LOCAL_COMMAND".equals(expectedToolProfile) || !PROFILE.equals(profile)
                || variantId == null || !variantId.matches("[a-f0-9]{24}") || definition == null)
            throw new IllegalArgumentException("invalid frozen F2 identity");
    }
    public Map<String, String> files() { return definition.files(); }
    public String prompt() { return "# F2 " + TITLE + "\n\n" + definition.prompt() + "\n\nVariant: " + variantId + "\n"; }
    public static F2FrozenOracle parse(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > MAX_BYTES) throw new IOException("F2 source size");
        var root = JSON.readTree(bytes);
        keys(root, Set.of("schemaVersion", "caseId", "expectedToolProfile", "profile", "variantId", "definition"));
        for (String field : List.of("caseId", "expectedToolProfile", "profile", "variantId"))
            if (!root.path(field).isTextual()) throw new IOException("F2 source text type");
        integral(root.path("schemaVersion"));
        keys(root.path("definition"), Set.of("schemaVersion", "nonce"));
        integral(root.path("definition").path("schemaVersion"));
        if (!root.path("definition").path("nonce").isTextual()) throw new IOException("F2 nonce type");
        return JSON.treeToValue(root, F2FrozenOracle.class);
    }
    private static void integral(JsonNode node) throws IOException {
        if (!node.isIntegralNumber() || !node.canConvertToInt()) throw new IOException("F2 source integer type");
    }
    private static void keys(JsonNode node, Set<String> expected) throws IOException {
        if (node == null || !node.isObject()) throw new IOException("F2 source object type");
        var actual = new HashSet<String>(); node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw new IOException("F2 source fields");
    }
}
