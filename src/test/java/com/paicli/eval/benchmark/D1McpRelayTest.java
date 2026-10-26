package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.eval.benchmark.mock.D1ToolSelectionMock;
import com.paicli.eval.benchmark.relay.*;
import com.paicli.llm.LlmClient;
import com.paicli.mcp.McpClient;
import com.paicli.mcp.transport.McpTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class D1McpRelayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;

    @Test void realAgentMcpClientAndHostRelayCompleteOneCorrectRead() throws Exception {
        D1ToolSelectionMock mock = new D1ToolSelectionMock(90401);
        BenchmarkRelayProtocol.WorkerComplete complete = runWorker(mock, "Read the authoritative posted");
        assertTrue(mock.satisfies(complete.answer()), complete.answer());
        assertFalse(mock.satisfies(complete.answer() + " {}"));
        assertFalse(mock.satisfies(complete.answer().replaceAll("([0-9]+)}$", "$1.0}")));
        assertFalse(mock.satisfies(complete.answer().replace("\"currency\":\"USD\"", "\"currency\":\"EUR\",\"currency\":\"USD\"")));
        assertEquals(1, complete.toolExecutions().size());
        assertTrue(complete.toolExecutions().get(0).toolName().startsWith("mcp__benchmark__ledger_"));
        assertEquals(List.of("initialize", "notifications/initialized", "tools/list", "tools/call"),
                mock.audit().stream().map(D1ToolSelectionMock.AuditEvent::method).toList());
        assertEquals(0, mock.sideEffects());
    }

    @Test void readOnlyDistractorAndSideEffectAreRealCallsButFailOracle() throws Exception {
        D1ToolSelectionMock read = new D1ToolSelectionMock(90401);
        var wrongRead = runWorker(read, "draft invoice preview");
        assertFalse(read.satisfies(wrongRead.answer()));
        assertEquals("DISTRACTOR_READ", read.audit().get(3).outcome());
        D1ToolSelectionMock write = new D1ToolSelectionMock(90401);
        var wrongWrite = runWorker(write, "Post draft invoices");
        assertFalse(write.satisfies(wrongWrite.answer()));
        assertEquals(1, write.sideEffects());
        assertEquals("SIDE_EFFECT", write.audit().get(3).outcome());
    }

    @Test void catalogIsSeededFrozenAndDynamicOnly() throws Exception {
        D1ToolSelectionMock mock = new D1ToolSelectionMock(21);
        BenchmarkToolRegistry registry = new BenchmarkToolRegistry(BenchmarkToolProfile.MOCK_MCP);
        assertTrue(registry.getToolDefinitions().isEmpty());
        try (McpClient client = direct(mock)) {
            client.initialize();
            registry.bindMockMcp(client);
            assertEquals(13, registry.getToolDefinitions().size());
            assertTrue(registry.getToolDefinitions().stream().allMatch(t -> t.name().startsWith("mcp__benchmark__")));
            assertThrows(IOException.class, () -> registry.bindMockMcp(client));
            for (String forbidden : List.of("write_file", "execute_command", "read_file", "mcp__external__ledger")) {
                assertFalse(registry.executeToolOutput(forbidden, "{}").successful());
            }
            assertEquals(3, mock.audit().size());
            try (McpClient same = direct(new D1ToolSelectionMock(21));
                 McpClient other = direct(new D1ToolSelectionMock(22))) {
                same.initialize(); other.initialize();
                assertEquals(client.listTools(), same.listTools());
                assertNotEquals(client.listTools(), other.listTools());
            }
        }
    }

    @Test void argumentsAreValidatedAndAuditCannotBeMutatedByCaller() throws Exception {
        D1ToolSelectionMock mock = new D1ToolSelectionMock(1);
        try (McpClient client = direct(mock)) {
            client.initialize();
            var correct = client.listTools().stream().filter(t -> t.description().startsWith("Read the authoritative posted")).findFirst().orElseThrow();
            var invalid = client.callToolOutput(correct.name(), "{\"endpoint\":\"http://localhost\"}");
            assertFalse(invalid.successful());
            assertEquals("MCP 工具返回错误: Invalid tool or arguments", invalid.text());
            assertEquals("INVALID_ARGUMENTS", mock.audit().get(3).outcome());
            ((com.fasterxml.jackson.databind.node.ObjectNode) mock.audit().get(3).params()).removeAll();
            assertFalse(mock.audit().get(3).params().isEmpty());
            assertFalse(mock.satisfies("{}"));
        }
    }

    @Test void mockRejectsOrderReplayAndUnsupportedMethodWithoutNetworkAccess() throws Exception {
        D1ToolSelectionMock mock = new D1ToolSelectionMock(1);
        JsonNode list = JSON.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}");
        assertEquals("NOT_INITIALIZED", mock.exchange(list).path("error").path("message").asText());
        assertThrows(IOException.class, () -> mock.exchange(list));
        assertThrows(IOException.class, () -> mock.exchange(JSON.readTree(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}")));
    }

    @Test void mcpFramesAreImmutableBoundedVersionedAndCannotCompleteAChat() throws Exception {
        var header = new BenchmarkRelayProtocol.Header(BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR,
                BenchmarkRelayProtocol.FrameType.MCP_REQUEST, "mcp-1", 0);
        var value = JSON.createObjectNode().put("method", "tools/list");
        var request = new BenchmarkRelayProtocol.McpRequest(header, value);
        value.put("endpoint", "not allowed");
        assertFalse(request.message().has("endpoint"));
        assertEquals(request, BenchmarkRelayProtocol.decode(BenchmarkRelayProtocol.encode(request)));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkRelayProtocol.McpRequest(header,
                JSON.createObjectNode().put("x", "x".repeat(65_537))));
        var validator = new BenchmarkRelayProtocol.Validator();
        var start = start(new ScriptedClient(""), "prompt");
        validator.accept(start);
        validator.accept(new BenchmarkRelayProtocol.WorkerReady(new BenchmarkRelayProtocol.Header(
                BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR, BenchmarkRelayProtocol.FrameType.WORKER_READY, "", 0), start.capabilities()));
        validator.accept(request);
        assertThrows(IllegalStateException.class, () -> validator.accept(new BenchmarkRelayProtocol.McpComplete(
                new BenchmarkRelayProtocol.Header(BenchmarkRelayProtocol.Direction.COORDINATOR_TO_WORKER,
                        BenchmarkRelayProtocol.FrameType.MCP_COMPLETE, "different-call", 1), JSON.nullNode())));
        assertThrows(IllegalStateException.class, () -> validator.accept(new BenchmarkRelayProtocol.ChatFailure(
                new BenchmarkRelayProtocol.Header(BenchmarkRelayProtocol.Direction.COORDINATOR_TO_WORKER,
                        BenchmarkRelayProtocol.FrameType.CHAT_FAILURE, "mcp-1", 1), "ERROR", "failed", false)));
        validator.accept(new BenchmarkRelayProtocol.McpComplete(new BenchmarkRelayProtocol.Header(
                BenchmarkRelayProtocol.Direction.COORDINATOR_TO_WORKER, BenchmarkRelayProtocol.FrameType.MCP_COMPLETE,
                "mcp-1", 1), JSON.nullNode()));
    }

    @Test void mockProfileWithoutAHostMockIsRejectedBeforeHandshake() {
        LlmClient client = new ScriptedClient("");
        var channel = new BenchmarkFramedChannel(new java.io.ByteArrayInputStream(new byte[0]),
                new java.io.ByteArrayOutputStream());
        IOException rejected = assertThrows(IOException.class,
                () -> BenchmarkProviderRelay.connect(channel, start(client, "prompt"), client));
        assertEquals("MCP profile requires exactly one host-owned mock", rejected.getMessage());
    }

    private BenchmarkRelayProtocol.WorkerComplete runWorker(D1ToolSelectionMock mock, String selection) throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        try (PipedInputStream workerIn = new PipedInputStream(1 << 20);
             PipedInputStream hostIn = new PipedInputStream(1 << 20);
             PipedOutputStream hostOut = new PipedOutputStream(workerIn);
             PipedOutputStream workerOut = new PipedOutputStream(hostIn)) {
            var future = pool.submit(() -> { BenchmarkRelayWorkerMain.run(workerIn, workerOut, temp); return null; });
            ScriptedClient scripted = new ScriptedClient(selection);
            var relay = BenchmarkProviderRelay.connect(new BenchmarkFramedChannel(hostIn, hostOut),
                    start(scripted, mock.prompt()), scripted, mock);
            BenchmarkProviderRelay.ServeResult result;
            do { result = relay.serveNext(); }
            while (result == BenchmarkProviderRelay.ServeResult.CHAT_SERVED || result == BenchmarkProviderRelay.ServeResult.MCP_SERVED);
            future.get(10, TimeUnit.SECONDS);
            assertEquals(BenchmarkProviderRelay.ServeResult.WORKER_COMPLETE, result);
            assertEquals(2, scripted.calls);
            return assertInstanceOf(BenchmarkRelayProtocol.WorkerComplete.class, relay.terminalFrame());
        } finally { pool.shutdownNow(); }
    }

    private static McpClient direct(D1ToolSelectionMock mock) {
        return new McpClient("benchmark", new McpTransport() {
            private Consumer<JsonNode> receive;
            @Override public void send(JsonNode value) throws IOException {
                JsonNode response = mock.exchange(value);
                if (!response.isNull()) receive.accept(response);
            }
            @Override public void onReceive(Consumer<JsonNode> receiver) { receive = receiver; }
            @Override public void close() { }
        });
    }

    private static BenchmarkRelayProtocol.SessionStart start(LlmClient client, String prompt) {
        return new BenchmarkRelayProtocol.SessionStart(new BenchmarkRelayProtocol.Header(
                BenchmarkRelayProtocol.Direction.COORDINATOR_TO_WORKER, BenchmarkRelayProtocol.FrameType.SESSION_START, "", 0),
                "mcp-diagnostic", client.getProviderName(), client.getModelName(), BenchmarkRelayProtocol.AgentMode.REACT,
                BenchmarkRelayProtocol.ToolProfile.MOCK_MCP, prompt, "2026-09-04", "UTC", System.currentTimeMillis() + 60_000,
                new BenchmarkRelayProtocol.AgentLimits(100_000, 8, 3, 1_000_000, 16_384),
                BenchmarkProviderRelay.capabilitiesOf(client), new BenchmarkRelayProtocol.Limits(
                BenchmarkFramedChannel.MAX_FRAME_BYTES, BenchmarkFramedChannel.MAX_SESSION_BYTES, 512, 128));
    }

    /** Deterministic control, not a model result or benchmark score. */
    private static final class ScriptedClient implements LlmClient {
        private final String select;
        private int calls;
        ScriptedClient(String select) { this.select = select; }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            return chat(messages, tools);
        }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            assertEquals(13, tools.size());
            assertTrue(tools.stream().allMatch(t -> t.name().startsWith("mcp__benchmark__")));
            if (++calls == 1) {
                String prompt = messages.stream().filter(m -> "user".equals(m.role())).reduce((a, b) -> b).orElseThrow().content();
                var customer = Pattern.compile("C-[0-9]{5}").matcher(prompt);
                var month = Pattern.compile("2026-0[1-8]").matcher(prompt);
                assertTrue(customer.find()); assertTrue(month.find());
                String args = JSON.createObjectNode().put("customer_id", customer.group()).put("month", month.group())
                        .put("currency", "USD").toString();
                String name = tools.stream().filter(t -> t.description().contains(select)).findFirst().orElseThrow().name();
                return new ChatResponse("assistant", "", "", List.of(new ToolCall("control-1", new ToolCall.Function(name, args))),
                        100, 30, 0, getModelName(), true);
            }
            String result = messages.stream().filter(m -> "tool".equals(m.role())).reduce((a, b) -> b).orElseThrow().content();
            return new ChatResponse("assistant", result, "", List.of(), 100, 30, 0, getModelName(), true);
        }
        @Override public String getModelName() { return "deepseek-v4-flash"; }
        @Override public String getProviderName() { return "deepseek"; }
        @Override public int maxContextWindow() { return 1_000_000; }
    }
}
