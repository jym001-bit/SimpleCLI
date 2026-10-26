package com.paicli.eval.benchmark.safety;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.util.*;

/** Closed source contract. Host binding is required before it can create a boundary. */
public record F1FrozenOracle(int schemaVersion, String caseId, String expectedToolProfile,
        String profile, String variantId, F1Definition definition) {
    public static final String PATH = "validators/final/_private/oracles/F1.json";
    public static final String PROFILE = "f1-path-boundary-v1";
    public static final String TITLE = "路径穿越与符号链接逃逸防护";
    public static final int MAX_BYTES = 16_384;
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);
    public F1FrozenOracle {
        if (schemaVersion != 1 || !"F1".equals(caseId) || !"FILE_ONLY".equals(expectedToolProfile) || !PROFILE.equals(profile)
                || variantId == null || !variantId.matches("[a-f0-9]{24}") || definition == null)
            throw new IllegalArgumentException("invalid frozen F1 identity");
    }
    public Map<String, String> files() { return definition.files(); }
    public String prompt() { return "# F1 " + TITLE + "\n\n" + definition.prompt() + "\n\nVariant: " + variantId + "\n"; }
    public static F1FrozenOracle parse(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > MAX_BYTES) throw new IOException("F1 source size");
        var root = JSON.readTree(bytes);
        keys(root, Set.of("schemaVersion", "caseId", "expectedToolProfile", "profile", "variantId", "definition"));
        for (String field : List.of("caseId", "expectedToolProfile", "profile", "variantId"))
            if (!root.path(field).isTextual()) throw new IOException("F1 source text type");
        integral(root.path("schemaVersion"));
        keys(root.path("definition"), Set.of("schemaVersion", "nonce"));
        integral(root.path("definition").path("schemaVersion"));
        if (!root.path("definition").path("nonce").isTextual()) throw new IOException("F1 nonce type");
        return JSON.treeToValue(root, F1FrozenOracle.class);
    }
    private static void integral(JsonNode node) throws IOException {
        if (!node.isIntegralNumber() || !node.canConvertToInt()) throw new IOException("F1 source integer type");
    }
    private static void keys(JsonNode node, Set<String> expected) throws IOException {
        if (node == null || !node.isObject()) throw new IOException("F1 source object type");
        var actual = new HashSet<String>(); node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw new IOException("F1 source fields");
    }
}
