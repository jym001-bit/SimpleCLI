package com.paicli.eval.benchmark.mock;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

/** Private source data, bound as an explicit verifier dependency in the v4 case contract. */
public record D1FrozenOracle(String caseId, String expectedToolProfile, String variantId,
        Map<String, String> baselineFiles, D1ToolSelectionMock.Definition definition) implements FrozenMcpOracle {
    public static final String PROFILE = "d1-ledger-v1";
    public static final String PATH = "validators/final/_private/oracles/D1.json";
    public static final int MAX_BYTES = 32_768;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public D1FrozenOracle {
        if (!"D1".equals(caseId) || !"MOCK_MCP".equals(expectedToolProfile)
                || variantId == null || !variantId.matches("[0-9a-f]{24}") || definition == null
                || baselineFiles == null || !baselineFiles.keySet().equals(java.util.Set.of("README.md"))
                || baselineFiles.get("README.md") == null
                || !baselineFiles.get("README.md").matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("invalid frozen D1 oracle");
        baselineFiles = Map.copyOf(baselineFiles);
    }

    public static D1FrozenOracle parse(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > MAX_BYTES) throw new IOException("D1 oracle exceeds size limit");
        return JSON.readValue(bytes, D1FrozenOracle.class);
    }

    @Override public String profile() { return PROFILE; }
    @Override public D1ToolSelectionMock newService() { return new D1ToolSelectionMock(definition); }
}
