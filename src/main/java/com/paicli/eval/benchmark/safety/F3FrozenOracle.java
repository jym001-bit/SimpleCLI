package com.paicli.eval.benchmark.safety;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.util.*;

/** Strict private F3 source identity; synthetic fixtures never contain a provider credential. */
public record F3FrozenOracle(int schemaVersion, String caseId, String expectedToolProfile,
        String profile, String variantId, F3Definition definition) {
    public static final String PATH = "validators/final/_private/oracles/F3.json";
    public static final String PROFILE = "f3-support-bundle-v1";
    public static final String TITLE = "工具结果提示注入与密钥诱导防护";
    public static final int MAX_BYTES = 65_536;
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);

    public F3FrozenOracle {
        if (schemaVersion != 1 || !"F3".equals(caseId) || !"MOCK_MCP_FILE_ONLY".equals(expectedToolProfile)
                || !PROFILE.equals(profile) || variantId == null || !variantId.matches("[a-f0-9]{24}") || definition == null)
            throw new IllegalArgumentException("invalid frozen F3 identity");
    }

    public String prompt() { return definition.prompt(); }
    public Map<String, String> files() { return definition.files(); }

    public static F3FrozenOracle parse(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("F3 source size");
        JsonNode root = JSON.readTree(bytes);
        keys(root, Set.of("schemaVersion", "caseId", "expectedToolProfile", "profile", "variantId", "definition"));
        for (String field : List.of("caseId", "expectedToolProfile", "profile", "variantId"))
            if (!root.path(field).isTextual()) throw new IOException("F3 source text type");
        integral(root.path("schemaVersion"));
        keys(root.path("definition"), Set.of("schemaVersion", "nonce"));
        integral(root.path("definition").path("schemaVersion"));
        if (!root.path("definition").path("nonce").isTextual()) throw new IOException("F3 nonce type");
        return JSON.treeToValue(root, F3FrozenOracle.class);
    }

    private static void integral(JsonNode node) throws IOException {
        if (!node.isIntegralNumber() || !node.canConvertToInt()) throw new IOException("F3 source integer type");
    }

    private static void keys(JsonNode node, Set<String> expected) throws IOException {
        if (node == null || !node.isObject()) throw new IOException("F3 source object type");
        var actual = new HashSet<String>(); node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw new IOException("F3 source fields");
    }

    @Override public String toString() { return "F3FrozenOracle[caseId=F3, privateSyntheticSource=<redacted>]"; }
}
