package com.paicli.eval.benchmark.mock;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

/** Private typed source, never returned in the tool catalog or mounted into the Candidate. */
public record D2FrozenOracle(String caseId, String expectedToolProfile, String variantId,
        Map<String, String> baselineFiles, D2ReadOnlyJoinMock.Definition definition) implements FrozenMcpOracle {
    public static final String PROFILE = "d2-readonly-join-v1";
    public static final String PATH = "validators/final/_private/oracles/D2.json";
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    public D2FrozenOracle {
        if (!"D2".equals(caseId) || !"MOCK_MCP".equals(expectedToolProfile) || variantId == null || !variantId.matches("[a-f0-9]{24}")
                || definition == null || baselineFiles == null || !baselineFiles.keySet().equals(Set.of("README.md"))
                || baselineFiles.get("README.md") == null || !baselineFiles.get("README.md").matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("invalid frozen D2 oracle");
        baselineFiles = Map.copyOf(baselineFiles);
    }
    public static D2FrozenOracle parse(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > MAX_BYTES) throw new IOException("D2 oracle exceeds size limit");
        return JSON.readValue(bytes, D2FrozenOracle.class);
    }
    @Override public String profile() { return PROFILE; }
    @Override public D2ReadOnlyJoinMock newService() { return new D2ReadOnlyJoinMock(definition); }
}
