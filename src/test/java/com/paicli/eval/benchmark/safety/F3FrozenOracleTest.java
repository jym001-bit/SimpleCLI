package com.paicli.eval.benchmark.safety;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class F3FrozenOracleTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static F3FrozenOracle source() {
        return new F3FrozenOracle(1, "F3", "MOCK_MCP_FILE_ONLY", F3FrozenOracle.PROFILE,
                "0123456789abcdef01234567", new F3Definition(1, "0123456789abcdef".repeat(4)));
    }
    @Test void roundTripBindsExactUnsuffixedPromptAndOnlyFiveSyntheticInputs() throws Exception {
        var source = source(); assertEquals(source, F3FrozenOracle.parse(JSON.writeValueAsBytes(source)));
        assertEquals(source.definition().prompt(), source.prompt()); assertFalse(source.prompt().contains("Variant:"));
        assertEquals(source.definition().files(), source.files());
        assertEquals(Set.of("README.md", "inputs/service.json", "inputs/events.jsonl", "config/credentials.json", "private/runtime.env"), source.files().keySet());
        for (String canary : source.definition().canaries()) {
            assertFalse(source.prompt().contains(canary)); assertFalse(source.toString().contains(canary));
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"version_bool", "version_float", "version_string", "wrong_id", "wrong_profile", "wrong_tools", "bad_variant",
            "nested_version_bool", "nested_version_float", "nested_nonce_number", "nested_nonce_uppercase", "extra_definition", "extra_root",
            "null_definition", "missing_definition", "duplicate", "nested_duplicate", "trailing", "oversize", "empty"})
    void malformedOrCoercedSourceNeverCreatesAuthority(String fault) throws Exception {
        ObjectNode node = JSON.valueToTree(source());
        switch (fault) {
            case "version_bool" -> node.put("schemaVersion", true);
            case "version_float" -> node.put("schemaVersion", 1.0);
            case "version_string" -> node.put("schemaVersion", "1");
            case "wrong_id" -> node.put("caseId", "F2");
            case "wrong_profile" -> node.put("profile", "f3-support-injection-v1");
            case "wrong_tools" -> node.put("expectedToolProfile", "MOCK_MCP");
            case "bad_variant" -> node.put("variantId", "0".repeat(23));
            case "nested_version_bool" -> ((ObjectNode)node.path("definition")).put("schemaVersion", true);
            case "nested_version_float" -> ((ObjectNode)node.path("definition")).put("schemaVersion", 1.0);
            case "nested_nonce_number" -> ((ObjectNode)node.path("definition")).put("nonce", 42);
            case "nested_nonce_uppercase" -> ((ObjectNode)node.path("definition")).put("nonce", "A".repeat(64));
            case "extra_definition" -> ((ObjectNode)node.path("definition")).put("approved", true);
            case "extra_root" -> node.put("approved", true);
            case "null_definition" -> node.putNull("definition");
            case "missing_definition" -> node.remove("definition");
            default -> { }
        }
        String encoded = JSON.writeValueAsString(node);
        if (fault.equals("duplicate")) encoded = "{\"schemaVersion\":1," + encoded.substring(1);
        if (fault.equals("nested_duplicate")) encoded = encoded.replace("\"definition\":{", "\"definition\":{\"schemaVersion\":1,");
        if (fault.equals("trailing")) encoded += " {}";
        if (fault.equals("oversize")) encoded += " ".repeat(F3FrozenOracle.MAX_BYTES);
        if (fault.equals("empty")) encoded = "";
        byte[] bytes = encoded.getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> F3FrozenOracle.parse(bytes));
    }
    @Test void nullSourceIsRejected() { assertThrows(IOException.class, () -> F3FrozenOracle.parse(null)); }
}
