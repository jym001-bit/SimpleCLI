package com.paicli.eval.benchmark.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.eval.benchmark.mock.F3SupportBundleMock;
import com.paicli.eval.benchmark.safety.F3Definition;
import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol.*;
import static org.junit.jupiter.api.Assertions.*;

class F3HostFailureTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROMPT = "frozen F3 host failure control";
    private static final Provider PROVIDER = new Provider();

    @Test void hostExchangeIoRuntimeAndInvalidResponseAreStickyEvidenceFailures() throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            final int fault = kind;
            var endpoint = new BenchmarkProviderRelay.MockMcpEndpoint() {
                public List<String> serverNames() { return List.of("support"); }
                public JsonNode exchange(JsonNode value) throws IOException { return exchange("support", value); }
                public JsonNode exchange(String server, JsonNode value) throws IOException {
                    if (fault == 0) throw new IOException("synthetic host IO failure");
                    if (fault == 1) throw new IllegalStateException("synthetic host state failure");
                    return JSON.createObjectNode().put("tooLarge", "x".repeat(262_145));
                }
            };
            var audit = new F3ToolResultAudit(PROMPT);
            var relay = BenchmarkProviderRelay.connect(channel(List.of(ready(), mcp("support", "m1", initialize()))),
                    start(ToolProfile.MOCK_MCP_FILE_ONLY, PROMPT), PROVIDER, endpoint, null, null, null, audit);
            assertThrows(F3ToolResultAudit.Failure.class, relay::serveNext, "fault=" + fault);
            assertTrue(audit.failed()); assertThrows(F3ToolResultAudit.Failure.class, audit::requireHealthy);
            assertNull(relay.providerFailureType(), "a host mock error must not become an upstream API error");
            assertNull(relay.terminalFrame()); assertTrue(audit.snapshot().mcpExchanges().isEmpty());
        }
    }

    @Test void nativeInvalidArgumentsRemainAnOrdinaryAuditedMcpErrorResponse() throws Exception {
        var definition = new F3Definition(1, "41ab".repeat(16)); var mock = new F3SupportBundleMock(definition);
        var notification = JSON.createObjectNode().put("jsonrpc", "2.0").put("method", "notifications/initialized"); notification.putObject("params");
        var catalog = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/list"); catalog.putObject("params");
        var call = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", 3).put("method", "tools/call");
        call.putObject("params").put("name", "get_case").putObject("arguments").put("case_id", "wrong-case");
        var chat = new ChatRequest(in(FrameType.CHAT_REQUEST, "model-1"), List.of(
                new WireMessage("system", "frozen system", null, List.of(), null),
                new WireMessage("user", definition.prompt(), null, List.of(), null)), List.of());
        var audit = new F3ToolResultAudit(definition.prompt());
        var relay = BenchmarkProviderRelay.connect(channel(List.of(ready(), mcp("support", "m1", initialize()),
                        mcp("support", "m2", notification), mcp("support", "m3", catalog), chat, mcp("support", "m4", call))),
                start(ToolProfile.MOCK_MCP_FILE_ONLY, definition.prompt()), PROVIDER, mock, null, null, null, audit);
        for (int i = 0; i < 3; i++) assertEquals(BenchmarkProviderRelay.ServeResult.MCP_SERVED, relay.serveNext());
        assertEquals(BenchmarkProviderRelay.ServeResult.CHAT_SERVED, relay.serveNext());
        assertEquals(BenchmarkProviderRelay.ServeResult.MCP_SERVED, relay.serveNext());
        audit.requireHealthy(); assertFalse(audit.failed());
        var exchange = audit.snapshot().mcpExchanges().get(3);
        assertTrue(exchange.response().message().path("result").path("isError").booleanValue());
        assertEquals("INVALID_TOOL_OR_ARGUMENTS", mock.audit().get(3).outcome()); assertNull(relay.providerFailureType());
    }

    @Test void oldMcpProfilePreservesItsOriginalExceptionBoundary() throws Exception {
        IOException original = new IOException("legacy host failure");
        var endpoint = new BenchmarkProviderRelay.MockMcpEndpoint() {
            public JsonNode exchange(JsonNode value) throws IOException { throw original; }
        };
        var relay = BenchmarkProviderRelay.connect(channel(List.of(ready(), mcp("benchmark", "m1", initialize()))),
                start(ToolProfile.MOCK_MCP, PROMPT), PROVIDER, endpoint);
        assertSame(original, assertThrows(IOException.class, relay::serveNext));
    }

    private static JsonNode initialize() {
        var message = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "initialize");
        var params = message.putObject("params").put("protocolVersion", "2025-03-26");
        params.putObject("capabilities").putObject("tools"); params.putObject("clientInfo").put("name", "binding-test").put("version", "1");
        return message;
    }
    private static McpRequest mcp(String server, String id, JsonNode message) { return new McpRequest(in(FrameType.MCP_REQUEST, id), server, message); }
    private static Header in(FrameType type, String id) { return new Header(Direction.WORKER_TO_COORDINATOR, type, id, 0); }
    private static WorkerReady ready() { return new WorkerReady(in(FrameType.WORKER_READY, ""), BenchmarkProviderRelay.capabilitiesOf(PROVIDER)); }
    private static BenchmarkFramedChannel channel(List<Frame> input) throws Exception {
        var bytes = new ByteArrayOutputStream(); var encoded = new BenchmarkFramedChannel(new ByteArrayInputStream(new byte[0]), bytes);
        for (Frame frame : input) encoded.write(frame);
        return new BenchmarkFramedChannel(new ByteArrayInputStream(bytes.toByteArray()), new ByteArrayOutputStream());
    }
    private static SessionStart start(ToolProfile profile, String prompt) {
        return new SessionStart(new Header(Direction.COORDINATOR_TO_WORKER, FrameType.SESSION_START, "", 0), "f3-host-failure",
                PROVIDER.getProviderName(), PROVIDER.getModelName(), AgentMode.REACT, profile, prompt, "2026-09-05", "UTC",
                System.currentTimeMillis() + 60_000, new AgentLimits(100_000, 32, 8, 1_000_000, 16_384),
                BenchmarkProviderRelay.capabilitiesOf(PROVIDER), new Limits(BenchmarkFramedChannel.MAX_FRAME_BYTES,
                BenchmarkFramedChannel.MAX_SESSION_BYTES, MAX_MESSAGES, MAX_TOOLS),
                List.of(profile == ToolProfile.MOCK_MCP ? "benchmark" : "support"), InteractionMode.SINGLE_TURN);
    }
    private static final class Provider implements LlmClient {
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            return new ChatResponse("assistant", "", "", List.of(new ToolCall("call-1",
                    new ToolCall.Function("mcp__support__get_case", "{\"case_id\":\"wrong-case\"}"))), 100, 10, 0, getModelName(), true);
        }
        public ChatResponse chat(List<Message> messages, List<Tool> tools) { return chat(messages, tools, StreamListener.NO_OP); }
        public String getProviderName() { return "deepseek"; }
        public String getModelName() { return "deepseek-v4-flash"; }
        public int maxContextWindow() { return 1_000_000; }
    }
}
