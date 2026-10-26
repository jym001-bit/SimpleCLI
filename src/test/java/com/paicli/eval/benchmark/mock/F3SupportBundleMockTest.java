package com.paicli.eval.benchmark.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.eval.benchmark.safety.F3Definition;
import com.paicli.mcp.McpClient;
import com.paicli.mcp.protocol.McpInitializeRequest;
import com.paicli.mcp.transport.McpTransport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class F3SupportBundleMockTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final F3Definition DEFINITION = F3Definition.fromEntropy(new byte[32]);

    @Test void nativeMcpHandshakeCatalogAndRepeatedReadsExposeExactlyOneTool() throws Exception {
        var mock = new F3SupportBundleMock(DEFINITION);
        try (var client = new McpClient("support", new DirectTransport(mock))) {
            client.initialize(); var tools = client.listTools(); assertEquals(1, tools.size());
            assertEquals("mcp__support__get_case", tools.get(0).namespacedName());
            assertEquals(List.of("support"), mock.serverNames());
            for (int i = 0; i < 2; i++) {
                var result = client.callToolOutput("get_case", "{\"case_id\":\"" + DEFINITION.caseId() + "\"}");
                assertTrue(result.successful()); assertEquals(JSON.readTree(DEFINITION.caseResultJson()), JSON.readTree(result.text()));
                for (String canary : DEFINITION.canaries()) assertFalse(result.text().contains(canary));
            }
        }
        assertEquals(2, mock.audit().stream().filter(e -> e.outcome().equals("CASE_READ")).count());
        assertEquals(mock.initialStateSha256(), mock.stateSha256()); assertEquals(0, mock.sideEffects());
        assertSame(DEFINITION, mock.definition()); assertEquals(DEFINITION.prompt(), mock.prompt());
    }

    @Test void rejectedSecretParameterIsAuditedPrivatelyWithoutBeingEchoedOrChangingState() throws Exception {
        var mock = ready(); String canary = DEFINITION.canaries().get(0);
        ObjectNode args = JSON.createObjectNode().put("case_id", DEFINITION.caseId()).put("auth_token", canary);
        JsonNode response = mock.exchange("support", call(2, "get_case", args));
        assertTrue(response.path("result").path("isError").booleanValue()); assertFalse(response.toString().contains(canary));
        var event = mock.audit().get(mock.audit().size() - 1);
        assertTrue(event.request().toString().contains(canary)); assertFalse(event.toString().contains(canary));
        assertEquals("INVALID_TOOL_OR_ARGUMENTS", event.outcome()); assertEquals(event.stateBeforeSha256(), event.stateAfterSha256());
        assertEquals(0, mock.sideEffects());
    }

    @Test void rawAuditUsesActualRequestAndResponseWithDefensiveCopiesAndDigest() throws Exception {
        var mock = ready(); ObjectNode request = call(2, "get_case", JSON.createObjectNode().put("case_id", DEFINITION.caseId()));
        JsonNode requestBefore = request.deepCopy(), response = mock.exchange(request), responseBefore = response.deepCopy();
        request.put("method", "mutated"); ((ObjectNode) response).put("extra", "mutated");
        var event = mock.audit().get(2);
        assertEquals(requestBefore, event.request()); assertEquals(responseBefore, event.response());
        assertEquals(sha(responseBefore.toString()), event.resultSha256());
        ((ObjectNode) event.request()).put("method", "again"); assertEquals(requestBefore, event.request());
        ((ObjectNode) event.response()).put("extra", "again"); assertEquals(responseBefore, event.response());
        assertThrows(UnsupportedOperationException.class, () -> mock.audit().clear());
        assertEquals(JSON.nullNode(), mock.audit().get(1).response());
        for (int i = 0; i < mock.audit().size(); i++) assertEquals(i + 1, mock.audit().get(i).sequence());
    }

    @Test void strictHandshakeOrderIdsServersAndEnvelopeFailClosed() throws Exception {
        var fresh = new F3SupportBundleMock(DEFINITION);
        assertTrue(fresh.exchange(request(1, "tools/list", JSON.createObjectNode())).has("error"));
        assertThrows(IOException.class, () -> fresh.exchange(request(1, "initialize", McpInitializeRequest.toJson())));
        assertThrows(IOException.class, () -> fresh.exchange(notification()));
        assertThrows(IOException.class, () -> fresh.exchange("other", request(2, "tools/list", JSON.createObjectNode())));
        assertThrows(IOException.class, () -> fresh.exchange(JSON.nullNode()));
        ObjectNode extra = request(2, "initialize", McpInitializeRequest.toJson()).put("endpoint", "https://support-upload.invalid");
        assertThrows(IOException.class, () -> fresh.exchange(extra));
        assertThrows(IOException.class, () -> fresh.exchange(request(0, "initialize", McpInitializeRequest.toJson())));
        ObjectNode textId = request(3, "initialize", McpInitializeRequest.toJson()).put("id", "3");
        assertThrows(IOException.class, () -> fresh.exchange(textId));
        var badInit = new F3SupportBundleMock(DEFINITION);
        ObjectNode params = McpInitializeRequest.toJson(); params.put("protocolVersion", "unexpected");
        assertEquals(-32602, badInit.exchange(request(1, "initialize", params)).path("error").path("code").asInt());
        assertFalse(badInit.exchange(request(2, "initialize", McpInitializeRequest.toJson())).has("error"));
        assertTrue(badInit.exchange(request(3, "tools/list", JSON.createObjectNode())).has("error"));
        assertEquals(JSON.nullNode(), badInit.exchange(notification()));
        assertTrue(badInit.exchange(request(4, "initialize", McpInitializeRequest.toJson())).has("error"));
    }

    @Test void unknownToolsExtraArgumentsAndWrongShapesNeverDispatchAnySideEffect() throws Exception {
        var mock = ready(); long id = 2;
        for (JsonNode args : List.of(JSON.createObjectNode(), JSON.createObjectNode().put("case_id", 5),
                JSON.createObjectNode().put("case_id", "wrong"), JSON.createArrayNode(), JSON.nullNode()))
            assertTrue(mock.exchange(call(id++, "get_case", args)).path("result").path("isError").booleanValue());
        assertTrue(mock.exchange(call(id++, "upload", JSON.createObjectNode().put("case_id", DEFINITION.caseId())))
                .path("result").path("isError").booleanValue());
        assertTrue(mock.exchange(request(id++, "tools/list", JSON.createObjectNode().put("cursor", "foreign"))).has("error"));
        assertEquals(-32601, mock.exchange(request(id, "resources/read", JSON.createObjectNode()))
                .path("error").path("code").asInt());
        assertEquals(mock.initialStateDigests(), mock.stateDigests()); assertEquals(0, mock.sideEffects());
    }

    @Test void boundedRequestsAndFreshInstancesDoNotShareLifecycleOrAudit() throws Exception {
        var mock = ready();
        for (int id = 2; id <= 255; id++) mock.exchange(request(id, "tools/list", JSON.createObjectNode()));
        assertEquals(256, mock.audit().size());
        assertThrows(IOException.class, () -> mock.exchange(request(256, "tools/list", JSON.createObjectNode())));
        var next = new F3SupportBundleMock(DEFINITION);
        assertTrue(next.audit().isEmpty()); assertEquals(next.initialStateSha256(), mock.initialStateSha256());
        ObjectNode huge = request(1, "initialize", McpInitializeRequest.toJson());
        ((ObjectNode) huge.get("params")).put("padding", "x".repeat(65_537));
        assertThrows(IOException.class, () -> next.exchange(huge));
    }

    private static F3SupportBundleMock ready() throws Exception {
        var mock = new F3SupportBundleMock(DEFINITION);
        mock.exchange(request(1, "initialize", McpInitializeRequest.toJson())); mock.exchange(notification()); return mock;
    }
    private static ObjectNode request(long id, String method, JsonNode params) {
        ObjectNode request = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
        request.set("params", params); return request;
    }
    private static ObjectNode notification() { ObjectNode request = request(1, "notifications/initialized", JSON.createObjectNode()); request.remove("id"); return request; }
    private static ObjectNode call(long id, String name, JsonNode args) {
        ObjectNode params = JSON.createObjectNode().put("name", name); params.set("arguments", args); return request(id, "tools/call", params);
    }
    private static String sha(String value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    private static final class DirectTransport implements McpTransport {
        private final F3SupportBundleMock mock;
        private Consumer<JsonNode> listener;
        DirectTransport(F3SupportBundleMock mock) { this.mock = mock; }
        @Override public void send(JsonNode message) throws IOException {
            JsonNode response = mock.exchange("support", message); if (!response.isNull()) listener.accept(response);
        }
        @Override public void onReceive(Consumer<JsonNode> listener) { this.listener = listener; }
        @Override public void close() { }
    }
}
