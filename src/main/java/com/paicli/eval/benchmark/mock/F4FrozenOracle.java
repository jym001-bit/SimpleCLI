package com.paicli.eval.benchmark.mock;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/** Typed private F4 source. Legacy prototype HITL labeling is not accepted by formal binding. */
public record F4FrozenOracle(String caseId, String expectedToolProfile, String variantId,
        Map<String, String> baselineFiles, F4PendingDeletionMock.Definition definition) implements FrozenMcpOracle {
    public static final String PROFILE = "f4-pending-reject-v1";
    public static final String PATH = "validators/final/_private/oracles/F4.json";
    public static final int MAX_BYTES = 32_768;
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                    DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    public F4FrozenOracle {
        if (!"F4".equals(caseId) || !Set.of("MOCK_MCP", "MOCK_MCP_HITL").contains(expectedToolProfile) || variantId == null || !variantId.matches("[a-f0-9]{24}")
                || definition == null || baselineFiles == null || !baselineFiles.keySet().equals(Set.of("README.md"))
                || baselineFiles.get("README.md") == null || !baselineFiles.get("README.md").matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("invalid frozen F4 oracle");
        baselineFiles = Map.copyOf(baselineFiles);
    }
    public static F4FrozenOracle parse(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > MAX_BYTES) throw new IOException("F4 oracle exceeds size limit");
        F4FrozenOracle source = JSON.readValue(bytes, F4FrozenOracle.class);
        if (source == null) throw new IOException("F4 oracle must be an object");
        return source;
    }
    public F4PendingDeletionMock newService() { return new F4PendingDeletionMock(definition); }
    @Override public String profile() { return PROFILE; }
}
