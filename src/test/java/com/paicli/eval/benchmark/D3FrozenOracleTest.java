package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.eval.benchmark.mock.D3ApprovalCalendarMock;
import com.paicli.eval.benchmark.mock.D3FrozenOracle;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class D3FrozenOracleTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void sourceFreezesApprovalModeAndRejectsMissingCoercedOrAmbiguousDefinitions() throws Exception {
        var oracle = new D3FrozenOracle("D3", "MOCK_MCP", "a".repeat(24), D3FrozenOracle.INTERACTION_MODE,
                D3FrozenOracle.APPROVAL_SCRIPT, Map.of("README.md", "b".repeat(64)), D3ApprovalCalendarMock.fromEntropy(new byte[32]));
        byte[] bytes = JSON.writeValueAsBytes(oracle);
        assertEquals(oracle, D3FrozenOracle.parse(bytes));
        var tree = (ObjectNode) JSON.readTree(bytes);
        List<Consumer<ObjectNode>> invalid = List.of(
                n -> n.put("caseId", "D2"), n -> n.put("expectedToolProfile", "FILE_ONLY"),
                n -> n.put("interactionMode", "SINGLE_TURN"), n -> n.put("approvalScript", "always-allow"),
                n -> n.put("candidateOverride", true), n -> n.remove("approvalScript"), n -> n.remove("definition"),
                n -> ((ObjectNode) n.path("baselineFiles")).putNull("README.md"),
                n -> world(n).put("schemaVersion", "1"), n -> world(n).put("durationMinutes", true),
                n -> world(n).put("durationMinutes", 0), n -> world(n).put("timezone", "Asia/Shanghai"),
                n -> world(n).put("durationMinutes", 15.0),
                n -> world(n).put("windowStart", "2026-09-05T09:00:00+00:00"),
                n -> world(n).remove("idempotencyKey"),
                n -> world(n).putNull("attendees"),
                n -> row(n, "slots", 0).put("available", "true"),
                n -> row(n, "slots", 0).remove("available"),
                n -> row(n, "slots", 0).put("start", row(n, "slots", 1).path("start").asText()),
                n -> row(n, "slots", 0).put("end", "2026-10-01T00:00:00Z"),
                n -> row(n, "slots", 0).put("id", row(n, "slots", 1).path("id").asText()),
                n -> row(n, "tools", 0).putNull("operation"),
                n -> row(n, "tools", 0).put("operation", "APPROVE"),
                n -> row(n, "tools", 0).put("operation", 0),
                n -> row(n, "tools", 0).put("operation", row(n, "tools", 1).path("operation").asText()));
        for (var mutation : invalid) {
            var bad = tree.deepCopy(); mutation.accept(bad);
            assertThrows(IOException.class, () -> D3FrozenOracle.parse(JSON.writeValueAsBytes(bad)), bad.toString());
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> D3FrozenOracle.parse((text + " {}").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> D3FrozenOracle.parse(text.replace("\"caseId\":\"D3\"", "\"caseId\":\"D3\",\"caseId\":\"D3\"").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> D3FrozenOracle.parse(new byte[D3FrozenOracle.MAX_BYTES + 1]));
        assertThrows(UnsupportedOperationException.class, () -> oracle.baselineFiles().clear());
        assertThrows(UnsupportedOperationException.class, () -> oracle.definition().slots().clear());
        assertNotSame(oracle.newService(), oracle.newService());
    }
    private static ObjectNode world(ObjectNode node) { return (ObjectNode) node.path("definition"); }
    private static ObjectNode row(ObjectNode node, String field, int index) { return (ObjectNode) world(node).path(field).get(index); }
}
