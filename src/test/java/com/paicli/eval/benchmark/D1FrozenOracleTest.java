package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.eval.benchmark.mock.D1FrozenOracle;
import com.paicli.eval.benchmark.mock.D1ToolSelectionMock;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class D1FrozenOracleTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void parsesOnlyCompleteUnambiguousPrivateDefinitions() throws Exception {
        var original = new D1FrozenOracle("D1", "MOCK_MCP", "a".repeat(24),
                Map.of("README.md", "b".repeat(64)), D1ToolSelectionMock.fromEntropy(new byte[32]));
        byte[] bytes = JSON.writeValueAsBytes(original);
        assertEquals(original, D1FrozenOracle.parse(bytes));
        var tree = (ObjectNode) JSON.readTree(bytes);
        List<Consumer<ObjectNode>> invalid = List.of(
                n -> ((ObjectNode) n.path("definition").path("tools").get(0)).remove("sideEffect"),
                n -> ((ObjectNode) n.path("definition").path("tools").get(0)).putNull("sideEffect"),
                n -> ((ObjectNode) n.path("definition")).remove("schemaVersion"),
                n -> n.put("candidateOverride", true),
                n -> ((ObjectNode) n.path("baselineFiles")).putNull("README.md"),
                n -> ((ObjectNode) n.path("definition").path("tools").get(0)).put("description", "altered semantics"));
        for (var mutation : invalid) {
            var bad = tree.deepCopy(); mutation.accept(bad);
            assertThrows(IOException.class, () -> D1FrozenOracle.parse(JSON.writeValueAsBytes(bad)));
        }
        assertThrows(IOException.class, () -> D1FrozenOracle.parse(
                (new String(bytes, StandardCharsets.UTF_8) + " {}").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> D1FrozenOracle.parse(new byte[D1FrozenOracle.MAX_BYTES + 1]));
    }
}
