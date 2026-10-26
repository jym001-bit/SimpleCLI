package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.eval.benchmark.mock.D2FrozenOracle;
import com.paicli.eval.benchmark.mock.D2ReadOnlyJoinMock;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class D2FrozenOracleTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void rejectsCoercionsMissingFieldsAmbiguousIdentitiesAndBrokenJoins() throws Exception {
        var original = new D2FrozenOracle("D2", "MOCK_MCP", "a".repeat(24),
                Map.of("README.md", "b".repeat(64)), D2ReadOnlyJoinMock.fromEntropy(new byte[32]));
        byte[] bytes = JSON.writeValueAsBytes(original);
        assertEquals(original, D2FrozenOracle.parse(bytes));
        var tree = (ObjectNode) JSON.readTree(bytes);
        List<Consumer<ObjectNode>> invalid = List.of(
                n -> n.put("caseId", "D1"),
                n -> n.put("candidateOverride", true),
                n -> n.remove("definition"),
                n -> ((ObjectNode) n.path("baselineFiles")).putNull("README.md"),
                n -> ((ObjectNode) n.path("definition")).put("schemaVersion", "1"),
                n -> ((ObjectNode) n.path("definition")).remove("asOf"),
                n -> ((ObjectNode) n.path("definition")).put("asOf", "2026-09-04T09:00:00+00:00"),
                n -> row(n, "tools", 0).putNull("operation"),
                n -> row(n, "tools", 0).put("operation", "NETWORK_WRITE"),
                n -> row(n, "people", 0).put("employeeId", 17),
                n -> row(n, "people", 0).put("employeeId", row(n, "people", 1).path("employeeId").asText()),
                n -> row(n, "people", 0).put("department", row(n, "people", 1).path("department").asText()),
                n -> row(n, "incidents", 0).put("assigneeId", "assignee-" + "f".repeat(20)),
                n -> row(n, "events", 0).put("incidentRef", "ref-" + "f".repeat(20)),
                n -> row(n, "events", 0).put("startsAt", "not-a-time"),
                n -> {
                    var incidents = n.path("definition").path("incidents");
                    for (var incident : incidents) ((ObjectNode) incident).put("status", "open").put("validUntil", "2026-09-05T09:00:00Z");
                });
        for (var mutation : invalid) {
            var bad = tree.deepCopy(); mutation.accept(bad);
            assertThrows(IOException.class, () -> D2FrozenOracle.parse(JSON.writeValueAsBytes(bad)), bad.toString());
        }
        assertThrows(IOException.class, () -> D2FrozenOracle.parse(new byte[D2FrozenOracle.MAX_BYTES + 1]));
        assertThrows(IOException.class, () -> D2FrozenOracle.parse((new String(bytes, java.nio.charset.StandardCharsets.UTF_8) + " {}").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThrows(UnsupportedOperationException.class, () -> original.definition().people().clear());
        assertThrows(UnsupportedOperationException.class, () -> original.baselineFiles().clear());
    }

    private static ObjectNode row(ObjectNode node, String rows, int index) {
        return (ObjectNode) node.path("definition").path(rows).get(index);
    }
}
