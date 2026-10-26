package com.paicli.eval.benchmark.mock;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/** Typed private D3 source with an immutable two-turn approval script. */
public record D3FrozenOracle(String caseId, String expectedToolProfile, String variantId,
                             String interactionMode, String approvalScript, Map<String, String> baselineFiles,
                             D3ApprovalCalendarMock.Definition definition) implements FrozenMcpOracle {
    public static final int MAX_BYTES = 32_768;
    public static final String PROFILE = "d3-approved-calendar-v1";
    public static final String PATH = "validators/final/_private/oracles/D3.json";
    public static final String INTERACTION_MODE = "TWO_TURN_APPROVAL";
    public static final String APPROVAL_SCRIPT = "earliest-available-exact-create-once-v1";
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                    DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    public D3FrozenOracle {
        if (!"D3".equals(caseId) || !"MOCK_MCP".equals(expectedToolProfile)
                || variantId == null || !variantId.matches("[a-f0-9]{24}")
                || !INTERACTION_MODE.equals(interactionMode) || !APPROVAL_SCRIPT.equals(approvalScript)
                || definition == null || baselineFiles == null
                || !(baselineFiles.isEmpty() || baselineFiles.keySet().equals(Set.of("README.md")))
                || baselineFiles.values().stream().anyMatch(value -> value == null || !value.matches("[a-f0-9]{64}")))
            throw new IllegalArgumentException("invalid frozen D3 oracle");
        baselineFiles = Map.copyOf(baselineFiles);
    }

    public static D3FrozenOracle parse(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > MAX_BYTES) throw new IOException("D3 oracle exceeds size limit");
        return JSON.readValue(bytes, D3FrozenOracle.class);
    }

    public D3ApprovalCalendarMock newService() { return new D3ApprovalCalendarMock(definition); }
    @Override public String profile() { return PROFILE; }
}
