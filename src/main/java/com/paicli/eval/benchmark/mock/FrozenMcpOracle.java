package com.paicli.eval.benchmark.mock;

import java.io.IOException;
import java.util.Map;

/** Closed set of private formal source contracts. No generic mock/plugin deserialization. */
public sealed interface FrozenMcpOracle permits D1FrozenOracle, D2FrozenOracle, D3FrozenOracle, F4FrozenOracle {
    int MAX_BYTES = 32_768;
    String caseId();
    String variantId();
    Map<String, String> baselineFiles();
    String profile();
    AuditedMockMcp newService();

    static FrozenMcpOracle parse(String caseId, byte[] bytes) throws IOException {
        return switch (caseId) {
            case "D1" -> D1FrozenOracle.parse(bytes);
            case "D2" -> D2FrozenOracle.parse(bytes);
            case "D3" -> D3FrozenOracle.parse(bytes);
            case "F4" -> F4FrozenOracle.parse(bytes);
            default -> throw new IOException("unsupported frozen MCP case");
        };
    }
}
