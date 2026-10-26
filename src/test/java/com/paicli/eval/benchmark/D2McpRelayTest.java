package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.eval.benchmark.mock.D2ReadOnlyJoinMock;
import com.paicli.eval.benchmark.relay.*;
import com.paicli.llm.LlmClient;
import com.paicli.mcp.McpClient;
import com.paicli.mcp.transport.McpTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class D2McpRelayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;

    @Test void realAgentUsesThreeIndependentClientsAndStableIdJoins() throws Exception {
        var mock = mock(1);
        var complete = runWorker(mock, Control.CORRECT);
        assertTrue(mock.satisfies(complete.answer()), complete.answer());
        assertEquals(3, complete.toolExecutions().size());
        assertEquals(12, mock.audit().size());
        for (String server : D2ReadOnlyJoinMock.SERVERS) {
            assertEquals(List.of("initialize", "notifications/initialized", "tools/list", "tools/call"),
                    mock.audit().stream().filter(a -> a.server().equals(server)).map(D2ReadOnlyJoinMock.AuditEvent::method).toList());
        }
        assertEquals(0, mock.sideEffects());
        assertEquals(mock.initialStateDigests(), mock.stateDigests());
        assertFalse(mock(1).satisfies(complete.answer()), "knowing the answer cannot replace the three service reads");
        assertFalse(mock.satisfies(complete.answer() + " {}"));
    }

    @Test void sameNameExpiredIncidentWrongIdAndCancelledMeetingCannotPass() throws Exception {
        for (Control control : List.of(Control.WRONG_PERSON, Control.EXPIRED_INCIDENT, Control.WRONG_ID,
                Control.CANCELLED_EVENT, Control.FENCED_JSON)) {
            var mock = mock(2);
            var result = runWorker(mock, control);
            assertFalse(mock.satisfies(result.answer()), control.name());
            assertEquals(0, mock.sideEffects());
            assertEquals(mock.initialStateDigests(), mock.stateDigests());
        }
    }

    @Test void writesMutateOnlyTheSelectedServerAndAuditCopiesCannotBeEdited() throws Exception {
        var mock = mock(3);
        var result = runWorker(mock, Control.WRITE);
        assertFalse(mock.satisfies(result.answer()));
        assertEquals(1, mock.sideEffects());
        assertNotEquals(mock.initialStateDigests().get("directory"), mock.stateDigests().get("directory"));
        for (String server : List.of("ticket", "calendar"))
            assertEquals(mock.initialStateDigests().get(server), mock.stateDigests().get(server));
        var last = mock.audit().get(mock.audit().size() - 1);
        ((com.fasterxml.jackson.databind.node.ObjectNode) last.params()).removeAll();
        assertFalse(last.params().isEmpty());
        assertNotEquals(last.stateBeforeSha256(), last.stateAfterSha256());
        assertThrows(UnsupportedOperationException.class, () -> mock.stateDigests().put("other", "forged"));
    }

    @Test void nativeWorkerRetainsPreRegistryPolicyDenialsInToolEvidence() throws Exception {
        var mock = mock(9);
        var result = runWorker(mock, Control.FORBIDDEN_TOOL);
        assertTrue(mock.satisfies(result.answer()), "host-only business correctness cannot substitute for full Worker evidence");
        assertEquals(12, mock.audit().size());
        assertEquals(4, result.toolExecutions().size());
        var denied = result.toolExecutions().get(3);
        assertEquals("read_file", denied.toolName());
        assertFalse(denied.successful());
        assertTrue(denied.resultPreview().contains("TOOL_NOT_ADVERTISED"));
    }

    @Test void catalogsBindAtomicallyAndOtherNamespacesOrLocalToolsStayUnavailable() throws Exception {
        var mock = mock(4);
        var registry = new BenchmarkToolRegistry(BenchmarkToolProfile.MOCK_MCP);
        try (var directory = direct(mock, "directory"); var ticket = direct(mock, "ticket"); var calendar = direct(mock, "calendar")) {
            directory.initialize(); ticket.initialize(); calendar.initialize();
            Map<String, McpClient> wrong = new LinkedHashMap<>();
            wrong.put("directory", directory); wrong.put("calendar", ticket);
            assertThrows(IOException.class, () -> registry.bindMockMcp(wrong));
            assertTrue(registry.getToolDefinitions().isEmpty(), "failed partial catalog cannot become executable");
            Map<String, McpClient> all = new LinkedHashMap<>();
            all.put("directory", directory); all.put("ticket", ticket); all.put("calendar", calendar);
            registry.bindMockMcp(all);
            assertEquals(6, registry.getToolDefinitions().size());
            assertThrows(IOException.class, () -> registry.bindMockMcp(all));
            for (String name : List.of("execute_command", "read_file", "mcp__benchmark__fake", "mcp__outside__fake"))
                assertFalse(registry.executeToolOutput(name, "{}").successful());
        }
        assertThrows(IOException.class, () -> mock.exchange("outside", JSON.createObjectNode()));
        assertThrows(IOException.class, () -> mock.exchange(JSON.createObjectNode()));
    }

    @Test void relayBindsServerSelectorsAndRejectsCrossServerCompletions() throws Exception {
        var mock = mock(5);
        var scripted = new ScriptedClient(Control.CORRECT);
        var start = start(scripted, mock);
        assertEquals(start, BenchmarkRelayProtocol.decode(BenchmarkRelayProtocol.encode(start)));
        assertEquals(12, BenchmarkRelayProtocol.VERSION);
        var validator = new BenchmarkRelayProtocol.Validator();
        validator.accept(start);
        validator.accept(new BenchmarkRelayProtocol.WorkerReady(header(BenchmarkRelayProtocol.FrameType.WORKER_READY, "", 0), start.capabilities()));
        var reqHeader = header(BenchmarkRelayProtocol.FrameType.MCP_REQUEST, "mcp-1", 0);
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkRelayProtocol.McpRequest(reqHeader, "https://example.com", JSON.createObjectNode()));
        assertThrows(IllegalStateException.class, () -> validator.accept(new BenchmarkRelayProtocol.McpRequest(reqHeader, "outside", JSON.createObjectNode())));
        var request = new BenchmarkRelayProtocol.McpRequest(reqHeader, "directory", JSON.createObjectNode().put("method", "tools/list"));
        validator.accept(request);
        var responseHeader = new BenchmarkRelayProtocol.Header(BenchmarkRelayProtocol.Direction.COORDINATOR_TO_WORKER,
                BenchmarkRelayProtocol.FrameType.MCP_COMPLETE, "mcp-1", 1);
        assertThrows(IllegalStateException.class, () -> validator.accept(new BenchmarkRelayProtocol.McpComplete(responseHeader, "ticket", JSON.nullNode())));
        validator.accept(new BenchmarkRelayProtocol.McpComplete(responseHeader, "directory", JSON.nullNode()));
        var unregistered = new BenchmarkProviderRelay.MockMcpEndpoint() {
            @Override public JsonNode exchange(JsonNode message) { throw new AssertionError("must reject before serving"); }
        };
        assertThrows(IOException.class, () -> BenchmarkProviderRelay.connect(new BenchmarkFramedChannel(
                new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()), start, scripted, unregistered));
    }

    @Test void nativeArgumentDecoderKeepsLastDuplicateAndIgnoresLaterRoots() throws Exception {
        var mock = mock(8);
        try (var client = direct(mock, "directory")) {
            client.initialize();
            var tool = client.listTools().stream().filter(t -> t.description().startsWith("Search people")).findFirst().orElseThrow();
            String argument = "{\"full_name\":\"discarded\",\"full_name\":\"missing-person\"} {}";
            var result = client.callToolOutput(tool.name(), argument);
            assertTrue(result.successful());
            assertEquals("{\"records\":[]}", result.text());
            assertEquals(JSON.readTree("{\"full_name\":\"missing-person\"}"),
                    mock.audit().get(mock.audit().size() - 1).params().path("arguments"));
            assertThrows(IOException.class, () -> client.callToolOutput(tool.name(), "{"));
        }
    }

    @Test void serverRequestIdsAreIndependentAndSeedControlsCatalogAndState() throws Exception {
        var a = mock(6); var b = mock(6); var c = mock(7);
        assertEquals(a.stateDigests(), b.stateDigests()); assertNotEquals(a.stateDigests(), c.stateDigests());
        var request = JSON.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\"}}");
        for (String server : D2ReadOnlyJoinMock.SERVERS) assertTrue(a.exchange(server, request).has("result"));
        assertThrows(IOException.class, () -> a.exchange("directory", request));
        assertEquals(3, a.audit().size());
        try (var same = direct(b, "directory"); var other = direct(c, "directory")) {
            same.initialize(); other.initialize(); assertNotEquals(same.listTools(), other.listTools());
        }
    }

    private BenchmarkRelayProtocol.WorkerComplete runWorker(D2ReadOnlyJoinMock mock, Control control) throws Exception {
        return runWorker(mock, control, temp);
    }

    static BenchmarkRelayProtocol.WorkerComplete runWorker(D2ReadOnlyJoinMock mock, Control control, Path workspace) throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        try (var workerIn = new PipedInputStream(1 << 20); var hostIn = new PipedInputStream(1 << 20);
             var hostOut = new PipedOutputStream(workerIn); var workerOut = new PipedOutputStream(hostIn)) {
            var future = pool.submit(() -> { BenchmarkRelayWorkerMain.run(workerIn, workerOut, workspace); return null; });
            var scripted = new ScriptedClient(control);
            var relay = BenchmarkProviderRelay.connect(new BenchmarkFramedChannel(hostIn, hostOut), start(scripted, mock), scripted, mock);
            BenchmarkProviderRelay.ServeResult result;
            do { result = relay.serveNext(); }
            while (result == BenchmarkProviderRelay.ServeResult.CHAT_SERVED || result == BenchmarkProviderRelay.ServeResult.MCP_SERVED);
            future.get(10, TimeUnit.SECONDS);
            assertEquals(BenchmarkProviderRelay.ServeResult.WORKER_COMPLETE, result);
            return assertInstanceOf(BenchmarkRelayProtocol.WorkerComplete.class, relay.terminalFrame());
        } finally { pool.shutdownNow(); }
    }
    private static D2ReadOnlyJoinMock mock(int seed) {
        byte[] entropy = new byte[32]; Arrays.fill(entropy, (byte) seed);
        return new D2ReadOnlyJoinMock(D2ReadOnlyJoinMock.fromEntropy(entropy));
    }
    private static McpClient direct(D2ReadOnlyJoinMock mock, String server) {
        return new McpClient(server, new McpTransport() {
            private Consumer<JsonNode> receiver;
            @Override public void onReceive(Consumer<JsonNode> receiver) { this.receiver = receiver; }
            @Override public void send(JsonNode message) throws IOException {
                JsonNode result = mock.exchange(server, message); if (!result.isNull()) receiver.accept(result);
            }
            @Override public void close() {}
        });
    }
    private static BenchmarkRelayProtocol.Header header(BenchmarkRelayProtocol.FrameType type, String call, int sequence) {
        return new BenchmarkRelayProtocol.Header(BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR, type, call, sequence);
    }
    private static BenchmarkRelayProtocol.SessionStart start(LlmClient client, D2ReadOnlyJoinMock mock) {
        return new BenchmarkRelayProtocol.SessionStart(new BenchmarkRelayProtocol.Header(BenchmarkRelayProtocol.Direction.COORDINATOR_TO_WORKER,
                BenchmarkRelayProtocol.FrameType.SESSION_START, "", 0), "d2-control", client.getProviderName(), client.getModelName(),
                BenchmarkRelayProtocol.AgentMode.REACT, BenchmarkRelayProtocol.ToolProfile.MOCK_MCP, mock.prompt(), "2026-09-04", "UTC",
                System.currentTimeMillis() + 60_000, new BenchmarkRelayProtocol.AgentLimits(100_000, 10, 4, 1_000_000, 16_384),
                BenchmarkProviderRelay.capabilitiesOf(client), new BenchmarkRelayProtocol.Limits(BenchmarkFramedChannel.MAX_FRAME_BYTES,
                BenchmarkFramedChannel.MAX_SESSION_BYTES, 512, 128), mock.serverNames());
    }
    enum Control { CORRECT, WRONG_PERSON, EXPIRED_INCIDENT, WRONG_ID, CANCELLED_EVENT, FENCED_JSON, WRITE, INVALID_ARGUMENTS, MALFORMED_ARGUMENTS, FORBIDDEN_TOOL }

    /** Reads only the prompt, exposed catalog and actual tool results, never the host definition. */
    private static final class ScriptedClient implements LlmClient {
        private final Control control;
        private int calls;
        private String fullName, department, asOf;
        private JsonNode person, incident;
        private String answerBeforeDeniedCall;
        ScriptedClient(Control control) { this.control = control; }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) { return chat(messages, tools); }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            assertEquals(6, tools.size());
            String prompt = messages.stream().filter(m -> "user".equals(m.role())).findFirst().orElseThrow().content();
            try {
                calls++;
                if (control == Control.FORBIDDEN_TOOL && calls == 5) return answer(answerBeforeDeniedCall);
                if (calls == 1) {
                    fullName = field(prompt, "full_name"); department = field(prompt, "department"); asOf = field(prompt, "as_of");
                    return call(tools, "Search people", Map.of("full_name", fullName));
                }
                String result = messages.stream().filter(m -> "tool".equals(m.role())).reduce((a, b) -> b).orElseThrow().content();
                if (control == Control.WRITE) return calls == 2 ? call(tools, "Update directory", Map.of("full_name", fullName)) : answer("{}");
                if (calls == 4 && List.of(Control.INVALID_ARGUMENTS, Control.MALFORMED_ARGUMENTS).contains(control)) {
                    assertEquals(control == Control.INVALID_ARGUMENTS ? "MCP 工具返回错误: Invalid tool or arguments"
                            : "benchmark MCP request failed", result);
                    return answer("{}");
                }
                var records = JSON.readTree(result).path("records");
                if (records.isEmpty()) return answer("{}");
                if (calls == 2) {
                    for (JsonNode row : records) if (row.path("department").asText().equals(department) != (control == Control.WRONG_PERSON)) person = row;
                    assertNotNull(person);
                    return call(tools, "List incidents", Map.of("assignee_id", person.path(control == Control.WRONG_ID ? "employeeId" : "ticketAssigneeId").asText()));
                }
                if (calls == 3) {
                    for (JsonNode row : records) if (row.path("status").asText().equals("open") && row.path("priority").asText().equals("P1")
                            && Instant.parse(row.path("validUntil").asText()).isAfter(Instant.parse(asOf)) != (control == Control.EXPIRED_INCIDENT)) incident = row;
                    assertNotNull(incident);
                    if (control == Control.MALFORMED_ARGUMENTS) {
                        String name = tools.stream().filter(t -> t.description().startsWith("List handoff events")).findFirst().orElseThrow().name();
                        return new ChatResponse("assistant", "", "", List.of(new ToolCall("control-3",
                                new ToolCall.Function(name, "{"))), 100, 30, 0, getModelName(), true);
                    }
                    return call(tools, "List handoff events", Map.of("person_id", person.path("calendarPersonId").asText(),
                            "incident_ref", control == Control.INVALID_ARGUMENTS ? "" : incident.path("calendarReference").asText()));
                }
                JsonNode selected = null;
                for (JsonNode row : records) {
                    boolean status = row.path("status").asText().equals(control == Control.CANCELLED_EVENT ? "cancelled" : "scheduled");
                    if (status && Instant.parse(row.path("startsAt").asText()).isAfter(Instant.parse(asOf))
                            && (selected == null || row.path("startsAt").asText().compareTo(selected.path("startsAt").asText()) < 0)) selected = row;
                }
                assertNotNull(selected);
                String text = JSON.createObjectNode().put("employee_id", person.path("employeeId").asText())
                        .put("incident_id", incident.path("id").asText()).put("calendar_event_id", selected.path("id").asText())
                        .put("starts_at", selected.path("startsAt").asText()).toString();
                if (control == Control.FORBIDDEN_TOOL) {
                    answerBeforeDeniedCall = text;
                    return new ChatResponse("assistant", "", "", List.of(new ToolCall("control-4",
                            new ToolCall.Function("read_file", "{\"path\":\"README.md\"}"))), 100, 30, 0, getModelName(), true);
                }
                return answer(control == Control.FENCED_JSON ? "```json\n" + text + "\n```" : text);
            } catch (IOException error) { throw new AssertionError(error); }
        }
        private ChatResponse call(List<Tool> tools, String description, Map<String, String> args) throws IOException {
            String name = tools.stream().filter(t -> t.description().startsWith(description)).findFirst().orElseThrow().name();
            return new ChatResponse("assistant", "", "", List.of(new ToolCall("control-" + calls,
                    new ToolCall.Function(name, JSON.writeValueAsString(args)))), 100, 30, 0, getModelName(), true);
        }
        private ChatResponse answer(String answer) { return new ChatResponse("assistant", answer, "", List.of(), 100, 30, 0, getModelName(), true); }
        private static String field(String prompt, String name) {
            var matcher = Pattern.compile(name + "=\"([^\"]+)\"").matcher(prompt);
            assertTrue(matcher.find()); return matcher.group(1);
        }
        @Override public String getProviderName() { return "deepseek"; }
        @Override public String getModelName() { return "deepseek-v4-flash"; }
        @Override public int maxContextWindow() { return 1_000_000; }
    }
}
