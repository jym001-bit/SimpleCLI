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

class F2FrozenOracleTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static F2FrozenOracle source() {
        return new F2FrozenOracle(1, "F2", "LOCAL_COMMAND", F2FrozenOracle.PROFILE,
                "0123456789abcdef01234567", new F2Definition(1, "0123456789abcdef".repeat(4)));
    }
    @Test void roundTripBindsOnlyTheClosedSourceAndExactDiagnosticPrompt() throws Exception {
        var source = source();
        assertEquals(source, F2FrozenOracle.parse(JSON.writeValueAsBytes(source)));
        assertEquals(Set.of("README.md", "runbook.md", "health.json", "diagnose.py", "archive/sentinel.txt"), source.files().keySet());
        assertEquals("# F2 " + F2FrozenOracle.TITLE + "\n\n" + source.definition().prompt()
                + "\n\nVariant: " + source.variantId() + "\n", source.prompt());
        assertEquals("python3 -I -B diagnose.py", source.definition().safeCommand());
    }
    @ParameterizedTest
    @ValueSource(strings = {"version_bool", "version_float", "wrong_id", "wrong_profile", "wrong_tools", "bad_variant",
            "nested_version_bool", "nested_nonce_number", "extra_definition", "extra_root", "null_definition", "duplicate", "trailing", "oversize"})
    void malformedOrCoercedSourceNeverCreatesAuthority(String fault) throws Exception {
        ObjectNode node = JSON.valueToTree(source());
        switch (fault) {
            case "version_bool" -> node.put("schemaVersion", true);
            case "version_float" -> node.put("schemaVersion", 1.0);
            case "wrong_id" -> node.put("caseId", "F1");
            case "wrong_profile" -> node.put("profile", "arbitrary-command");
            case "wrong_tools" -> node.put("expectedToolProfile", "FILE_ONLY");
            case "bad_variant" -> node.put("variantId", "0".repeat(23));
            case "nested_version_bool" -> ((ObjectNode)node.path("definition")).put("schemaVersion", true);
            case "nested_nonce_number" -> ((ObjectNode)node.path("definition")).put("nonce", 42);
            case "extra_definition" -> ((ObjectNode)node.path("definition")).put("approved", true);
            case "extra_root" -> node.put("approved", true);
            case "null_definition" -> node.putNull("definition");
            default -> { }
        }
        String encoded = JSON.writeValueAsString(node);
        if (fault.equals("duplicate")) encoded = "{\"schemaVersion\":1," + encoded.substring(1);
        if (fault.equals("trailing")) encoded += " {}";
        if (fault.equals("oversize")) encoded += " ".repeat(F2FrozenOracle.MAX_BYTES);
        byte[] bytes = encoded.getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> F2FrozenOracle.parse(bytes));
    }
}
