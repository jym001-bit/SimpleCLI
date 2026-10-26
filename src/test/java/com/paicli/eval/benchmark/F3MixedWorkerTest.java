package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.paicli.eval.benchmark.mock.F3SupportBundleMock;
import com.paicli.eval.benchmark.relay.*;
import com.paicli.eval.benchmark.safety.F3Definition;
import com.paicli.llm.LlmClient;
import com.paicli.mcp.McpClient;
import com.paicli.mcp.transport.McpTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Native scripted transport controls only; no model calls and no benchmark score. */
@Timeout(30)
class F3MixedWorkerTest {
    private static final Set<String> MIXED_TOOLS = Set.of("read_file", "write_file", "list_dir",
            "glob_files", "grep_code", "create_project", "mcp__support__get_case");
    private static final String MARKER = "SYNTHETIC_RAW_RESULT_AFTER_PREVIEW";
    @TempDir Path temp;

    @Test void nativeMixedWorkerUploadsUntruncatedAndPolicyDeniedResults() throws Exception {
        runNative(false, false);
    }

    @Test void nativeDuplicateMcpArgumentsRetainOriginalStringsAndUseLastValue() throws Exception {
        runNative(true, false);
        runNative(true, true);
    }

    private void runNative(boolean duplicate, boolean differentFirstValue) throws Exception {
        Path workspace = temp.toRealPath();
        Files.writeString(workspace.resolve("README.md"), "x".repeat(20_000) + MARKER);
        var definition = new F3Definition(1, "ab19".repeat(16));
        var mock = new F3SupportBundleMock(definition);
        var script = new Script(definition, duplicate, differentFirstValue);
        var audit = new F3ToolResultAudit(definition.prompt());
        var pool = Executors.newSingleThreadExecutor();
        try (var workerIn = new PipedInputStream(1 << 20); var hostIn = new PipedInputStream(1 << 20);
             var hostOut = new PipedOutputStream(workerIn); var workerOut = new PipedOutputStream(hostIn)) {
            var worker = pool.submit(() -> { BenchmarkRelayWorkerMain.run(workerIn, workerOut, workspace); return null; });
            var relay = BenchmarkProviderRelay.connect(new BenchmarkFramedChannel(hostIn, hostOut),
                    start(script, definition.prompt()), script, mock, null, null, null, audit);
            BenchmarkProviderRelay.ServeResult result;
            do { result = relay.serveNext(); }
            while (result != BenchmarkProviderRelay.ServeResult.WORKER_COMPLETE
                    && result != BenchmarkProviderRelay.ServeResult.WORKER_FAILURE);
            worker.get(10, TimeUnit.SECONDS);
            assertEquals(BenchmarkProviderRelay.ServeResult.WORKER_COMPLETE, result,
                    () -> "calls=" + script.calls + ", MCP exchanges=" + mock.audit().size()
                            + ", terminal=" + (relay.terminalFrame() instanceof BenchmarkRelayProtocol.WorkerFailure failure
                            ? failure.errorType() : relay.terminalFrame().type()));
            var terminal = assertInstanceOf(BenchmarkRelayProtocol.WorkerComplete.class, relay.terminalFrame());
            assertSame(terminal, audit.terminal()); audit.requireHealthy();
            var snapshot = audit.snapshot();
            assertEquals(3, script.calls); assertEquals(3, snapshot.providerTurns().size());
            assertEquals(3, snapshot.toolResults().size()); assertEquals(3, terminal.toolExecutions().size());
            var file = snapshot.toolResults().get(1);
            assertTrue(file.result().endsWith(MARKER)); assertTrue(file.result().length() > 16_384);
            assertFalse(terminal.toolExecutions().get(1).resultPreview().contains(MARKER));
            assertEquals(BenchmarkRelayProtocol.textSha256(file.result()), terminal.toolExecutions().get(1).resultSha256());
            var denied = snapshot.toolResults().get(2);
            assertEquals("execute_command", denied.toolName()); assertFalse(denied.successful());
            assertTrue(denied.result().contains("denied") || denied.result().contains("拒绝") || denied.result().contains("不可用"));
            assertEquals("stream-only-synthetic", snapshot.providerTurns().get(2).streamDeltas().get(0).delta());
            assertEquals("complete reasoning", snapshot.providerTurns().get(2).response().reasoningContent());
            assertEquals(4, snapshot.mcpExchanges().size());
            assertEquals(List.of(0, 0, 0, 1), snapshot.mcpExchanges().stream().map(F3ToolResultAudit.McpExchange::toolOrdinal).toList());
            assertEquals(script.mcpArguments, snapshot.toolResults().get(0).argumentsJson());
            assertEquals(definition.caseId(), snapshot.mcpExchanges().get(3).request().message().path("params").path("arguments").path("case_id").textValue());
            assertTrue(snapshot.toolResults().get(0).successful());
            assertEquals(0, terminal.commandObservationFailures()); assertTrue(terminal.commandObservations().isEmpty());
        } finally { pool.shutdownNow(); }
    }

    @Test void mixedCatalogBindsAtomicallyWhileOldMcpRemainsDynamicOnly() throws Exception {
        for (var profile : List.of(BenchmarkToolProfile.MOCK_MCP_FILE_ONLY, BenchmarkToolProfile.MOCK_MCP)) {
            var registry = new BenchmarkToolRegistry(profile);
            assertTrue(registry.getToolDefinitions().isEmpty());
            try (var client = direct(new F3SupportBundleMock(new F3Definition(1, "12ac".repeat(16))))) {
                client.initialize();
                if (profile == BenchmarkToolProfile.MOCK_MCP_FILE_ONLY)
                    assertThrows(IOException.class, () -> registry.bindMockMcp(Map.of("wrong", client)));
                registry.bindMockMcp(Map.of("support", client));
                var names = registry.getToolDefinitions().stream().map(LlmClient.Tool::name).collect(Collectors.toSet());
                assertEquals(profile == BenchmarkToolProfile.MOCK_MCP_FILE_ONLY ? MIXED_TOOLS : Set.of("mcp__support__get_case"), names);
                assertThrows(IOException.class, () -> registry.bindMockMcp(Map.of("support", client)));
                assertFalse(registry.executeToolOutput("execute_command", "{}").successful());
                if (profile == BenchmarkToolProfile.MOCK_MCP)
                    assertFalse(registry.executeToolOutput("read_file", "{}").successful());
            }
        }
    }

    @Test void hostWorkerRejectsMixedProfileBeforeProviderOrFilesystemSetup() {
        var request = new BenchmarkProtocol.WorkerRequest(BenchmarkProtocol.VERSION, "deepseek", "deepseek-v4-flash",
                "", "not-a-real-key", "react", BenchmarkToolProfile.MOCK_MCP_FILE_ONLY,
                new BenchmarkProtocol.AgentLimits(100_000, 8, 3, 1_000_000, 16_384), "2026-09-05", "frozen",
                "/missing-f3-test-workspace", "/missing-f3-test-home", "/missing-f3-test-episode");
        var response = BenchmarkWorkerMain.execute(request);
        assertEquals("UNSUPPORTED_TOOL_PROFILE", response.errorType()); assertNull(response.metrics());
    }

    private static McpClient direct(F3SupportBundleMock mock) {
        return new McpClient("support", new McpTransport() {
            private Consumer<JsonNode> receive;
            @Override public void send(JsonNode value) throws IOException {
                JsonNode response = mock.exchange(value); if (!response.isNull()) receive.accept(response);
            }
            @Override public void onReceive(Consumer<JsonNode> receiver) { receive = receiver; }
            @Override public void close() { }
        });
    }

    private static BenchmarkRelayProtocol.SessionStart start(LlmClient provider, String prompt) {
        return new BenchmarkRelayProtocol.SessionStart(new BenchmarkRelayProtocol.Header(
                BenchmarkRelayProtocol.Direction.COORDINATOR_TO_WORKER, BenchmarkRelayProtocol.FrameType.SESSION_START, "", 0),
                "f3-native-control", provider.getProviderName(), provider.getModelName(), BenchmarkRelayProtocol.AgentMode.REACT,
                BenchmarkRelayProtocol.ToolProfile.MOCK_MCP_FILE_ONLY, prompt, "2026-09-05", "UTC", System.currentTimeMillis() + 60_000,
                new BenchmarkRelayProtocol.AgentLimits(100_000, 8, 3, 1_000_000, 16_384), BenchmarkProviderRelay.capabilitiesOf(provider),
                new BenchmarkRelayProtocol.Limits(BenchmarkFramedChannel.MAX_FRAME_BYTES, BenchmarkFramedChannel.MAX_SESSION_BYTES, 512, 128),
                List.of("support"), BenchmarkRelayProtocol.InteractionMode.SINGLE_TURN);
    }

    private static final class Script implements LlmClient {
        private final F3Definition definition;
        private final String mcpArguments;
        private int calls;
        Script(F3Definition definition, boolean duplicate, boolean differentFirstValue) {
            this.definition = definition;
            mcpArguments = "{" + (duplicate ? "\"case_id\":\"" + (differentFirstValue ? "wrong-first-value" : definition.caseId()) + "\"," : "")
                    + "\"case_id\":\"" + definition.caseId() + "\"}";
        }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            assertEquals(MIXED_TOOLS, tools.stream().map(Tool::name).collect(Collectors.toSet()));
            List<ToolCall> requested = switch (++calls) {
                case 1 -> List.of(call("same", "mcp__support__get_case", mcpArguments));
                case 2 -> List.of(call("same", "read_file", "{\"path\":\"README.md\"}"), call("denied", "execute_command", "{}"));
                default -> List.of();
            };
            if (calls == 3 && listener != null) listener.onReasoningDelta("stream-only-synthetic");
            return new ChatResponse("assistant", calls == 3 ? "done" : "", "complete reasoning", requested,
                    100, 30, 0, getModelName(), true);
        }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) { return chat(messages, tools, null); }
        private static ToolCall call(String id, String name, String args) { return new ToolCall(id, new ToolCall.Function(name, args)); }
        @Override public String getModelName() { return "deepseek-v4-flash"; }
        @Override public String getProviderName() { return "deepseek"; }
        @Override public int maxContextWindow() { return 1_000_000; }
    }
}
