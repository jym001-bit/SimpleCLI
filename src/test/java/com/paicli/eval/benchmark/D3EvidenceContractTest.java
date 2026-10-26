package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.eval.benchmark.mock.D1FrozenOracle;
import com.paicli.eval.benchmark.mock.D2FrozenOracle;
import com.paicli.eval.benchmark.mock.D3FrozenOracle;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class D3EvidenceContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Test void d1AndD2KeepTheirExactWireShapeWhenApprovalRelayIsAbsent() throws Exception {
        var d1 = JSON.createObjectNode().put("schemaVersion", 1).put("caseId", "D1").put("profile", D1FrozenOracle.PROFILE)
                .put("mockSourceSha256", "a".repeat(64)).put("sideEffects", 0);
        d1.putArray("events");
        var d2 = d1.deepCopy().put("schemaVersion", 2).put("caseId", "D2").put("profile", D2FrozenOracle.PROFILE);
        for (String field : List.of("initialStateDigests", "finalStateDigests")) {
            var states = d2.putObject(field);
            for (String server : List.of("directory", "ticket", "calendar")) states.put(server, "b".repeat(64));
        }
        for (var input : List.of(d1, d2)) {
            var value = JSON.treeToValue(input, FormalMockMcpBinding.MockEvidence.class);
            assertEquals(input, JSON.valueToTree(value));
            assertNull(value.relayEvents());
        }
    }
    @Test void d3RequiresOrderedRelayDataAndDoesNotAliasCallerOwnedTrees() throws Exception {
        var states = Map.of("calendar", "a".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> new FormalMockMcpBinding.MockEvidence(3, "D3", D3FrozenOracle.PROFILE,
                "b".repeat(64), List.of(), 0, states, states, null));
        var empty = new FormalMockMcpBinding.MockEvidence(3, "D3", D3FrozenOracle.PROFILE, "b".repeat(64), List.of(), 0, states, states, List.of());
        var tree = JSON.valueToTree(empty);
        assertTrue(tree.path("relayEvents").isArray()); assertTrue(tree.path("relayEvents").isEmpty());
        assertEquals(empty, JSON.treeToValue(tree, FormalMockMcpBinding.MockEvidence.class));
        ObjectNode relay = JSON.createObjectNode().put("sequence", 1).put("turn", 1);
        relay.putObject("request"); relay.putNull("response");
        var value = new FormalMockMcpBinding.MockEvidence(3, "D3", D3FrozenOracle.PROFILE, "b".repeat(64), List.of(), 0, states, states, List.of(relay));
        relay.removeAll(); assertFalse(value.relayEvents().get(0).isEmpty());
        ((ObjectNode) value.relayEvents().get(0)).removeAll(); assertFalse(value.relayEvents().get(0).isEmpty());
    }
}
