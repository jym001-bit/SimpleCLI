package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.eval.benchmark.mock.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class D4FrozenOracleTest {
    @Test void rejectsCoercionUnknownMissingFieldsAndNonFrozenRoutes() throws Exception {
        var json = new ObjectMapper(); var oracle = oracle();
        byte[] bytes = json.writeValueAsBytes(oracle);
        assertEquals(oracle, D4FrozenOracle.parse(bytes));
        var tree = (ObjectNode) json.readTree(bytes);
        List<Consumer<ObjectNode>> bad = List.of(n -> n.remove("definition"), n -> n.put("schemaVersion", "1"),
                n -> n.put("schemaVersion", true), n -> n.put("schemaVersion", 1.0), n -> n.put("profile", "override"),
                n -> n.putNull("variantId"), n -> n.put("variantId", "b".repeat(23)), n -> n.put("caseId", "D3"),
                n -> n.put("expectedToolProfile", "MOCK_MCP"), n -> n.with("baselineFiles").put("README.md", "a".repeat(64)),
                n -> n.with("definition").put("timeoutSeconds", "45"), n -> n.with("definition").put("cacheEntries", 128.0),
                n -> n.with("definition").put("timeoutSeconds", 5), n -> n.with("definition").put("cacheEntries", 32),
                n -> n.with("definition").remove("queryUrl"), n -> n.with("definition").put("releaseUrl", "https://outside.invalid/release"),
                n -> n.with("definition").put("bodyUrl", oracle.definition().releaseUrl()), n -> n.with("definition").put("release", "2.01"));
        for (var mutation : bad) {
            var copy = tree.deepCopy(); mutation.accept(copy);
            assertThrows(IOException.class, () -> D4FrozenOracle.parse(json.writeValueAsBytes(copy)), copy.toString());
        }
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        for (String invalid : List.of(text + " {}", text.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"), "null"))
            assertThrows(IOException.class, () -> D4FrozenOracle.parse(invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> D4FrozenOracle.parse(new byte[D4FrozenOracle.MAX_BYTES + 1]));
    }
    @Test void servicesAreFreshAndSourceIsImmutable() throws Exception {
        var source = oracle(); var one = source.newService(); var two = source.newService();
        one.search("test", 2); assertFalse(one.audit().isEmpty()); assertTrue(two.audit().isEmpty()); assertTrue(two.providerAudit().isEmpty());
        assertEquals(source.definition(), two.definition()); assertEquals(one.prompt(), two.prompt());
        assertThrows(UnsupportedOperationException.class, () -> source.baselineFiles().put("x", "y"));
    }
    @Test void formalV2RequiresExactlyOneDeclaredReadmeHashWithoutChangingV1() throws Exception {
        var v1 = oracle(); var json = new ObjectMapper();
        var baseline = new HashMap<>(Map.of("README.md", "b".repeat(64)));
        var v2 = new D4FrozenOracle(2, "D4", "MOCK_WEB", v1.variantId(), baseline, v1.definition());
        baseline.clear(); assertEquals(1, v2.baselineFiles().size());
        assertEquals(v2, D4FrozenOracle.parse(json.writeValueAsBytes(v2)));
        for (var bad : List.of(Map.<String, String>of(), Map.of("README.md", "bad"), Map.of("other.md", "b".repeat(64))))
            assertThrows(IllegalArgumentException.class, () -> new D4FrozenOracle(2, "D4", "MOCK_WEB", v1.variantId(), bad, v1.definition()));
        assertEquals(v1, D4FrozenOracle.parse(json.writeValueAsBytes(v1)));
    }
    static D4FrozenOracle oracle() { return new D4FrozenOracle(1, "D4", "MOCK_WEB", "a".repeat(24), Map.of(), D4WebMock.fromEntropy(D4NativeWebTest.entropy(42))); }
}
