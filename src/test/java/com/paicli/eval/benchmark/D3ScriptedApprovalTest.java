package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.agent.Agent;
import com.paicli.eval.benchmark.finalset.generator.FinalSourceRecipeCatalog;
import com.paicli.eval.benchmark.mock.D3ApprovalCalendarMock;
import com.paicli.hitl.HitlToolRegistry;
import com.paicli.llm.LlmClient;
import com.paicli.mcp.McpClient;
import com.paicli.mcp.transport.McpTransport;
import com.paicli.tool.ToolOutput;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Native Agent + product HITL + native MCP, with a scripted LLM and a host-owned user boundary. No API calls. */
@Timeout(30)
class D3ScriptedApprovalTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;
    private final Map<String, String> previous = new HashMap<>();

    @BeforeEach void isolateRuntime() {
        Map<String, String> values = Map.of("paicli.audit.dir", temp.resolve("audit").toString(),
                "paicli.react.token.budget", "100000", "paicli.react.hard.max.iterations", "10",
                "paicli.compaction.session-memory.enabled", "false");
        values.forEach((key, value) -> { previous.put(key, System.getProperty(key)); System.setProperty(key, value); });
    }
    @AfterEach void restoreRuntime() {
        previous.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
    }

    @Test void sameAgentRetainsProposalAndWritesOnlyAfterHostApproval() throws Exception {
        for (int seed : List.of(1, 2, 3)) try (var harness = harness(seed, seed == 3 ? Control.REORDER_ATTENDEES : Control.CORRECT)) {
            String proposal = harness.agent.runExplicitTask(harness.mock.prompt(), harness.mock.prompt());
            assertEquals(0, harness.mock.writes());
            assertEquals(harness.mock.initialStateSha256(), harness.mock.stateSha256());
            assertTrue(harness.mock.audit().stream().noneMatch(e -> e.source().equals("HITL")));
            var decision = harness.mock.advanceAfterProposal(proposal);
            assertTrue(decision.approved(), proposal);
            String answer = harness.agent.runExplicitTask(decision.userMessage(), decision.userMessage());
            assertTrue(harness.mock.satisfies(answer), answer);
            assertEquals(1, harness.mock.writes());
            assertEquals(2, harness.tools.executions.size());
            assertTrue(harness.tools.executions.stream().allMatch(e -> e.successful()));
            String acknowledgment = harness.tools.executions.get(1).result();
            var copies = acknowledgment.split("\n\n");
            assertEquals(2, copies.length); assertEquals(copies[0], copies[1]);
            assertEquals(JSON.readTree(copies[0]), JSON.readTree(answer));
            var audit = harness.mock.audit();
            int approved = index(audit, "USER", "APPROVED");
            assertTrue(index(audit, "MCP", "AVAILABILITY_READ") < approved);
            assertTrue(approved < index(audit, "HITL", "ALLOW"));
            assertTrue(index(audit, "HITL", "ALLOW") < index(audit, "MCP", "CREATED"));
            var history = harness.agent.getConversationHistory();
            assertEquals(2, history.stream().filter(m -> m.role().equals("user")).count());
            assertTrue(history.stream().anyMatch(m -> m.role().equals("assistant") && proposal.equals(m.content())));
            assertThrows(IllegalStateException.class, () -> harness.mock.advanceAfterProposal(proposal));
        }
    }

    @Test void invalidProposalsNeverReceiveApprovalAndCannotWrite() throws Exception {
        for (Control control : List.of(Control.NO_AVAILABILITY, Control.UNAVAILABLE_SLOT, Control.LATE_SLOT,
                Control.EXPANDED_ATTENDEES, Control.MISSING_KEY, Control.PREMATURE_WRITE, Control.SELF_APPROVE_TOOL, Control.FORGED_APPROVAL_STATUS)) {
            try (var harness = harness(4, control)) {
                String proposal = harness.agent.runExplicitTask(harness.mock.prompt(), harness.mock.prompt());
                var decision = harness.mock.advanceAfterProposal(proposal);
                assertFalse(decision.approved(), control + ": " + proposal);
                String answer = harness.agent.runExplicitTask(decision.userMessage(), decision.userMessage());
                assertFalse(harness.mock.satisfies(answer), control.name());
                assertEquals(0, harness.mock.writes());
                assertEquals(harness.mock.initialStateSha256(), harness.mock.stateSha256());
                assertTrue(harness.mock.audit().stream().noneMatch(e -> e.outcome().equals("CREATED")));
            }
        }
    }

    @Test void approvalDoesNotAuthorizeChangedFieldsCancellationOrDuplicateCreates() throws Exception {
        for (Control control : List.of(Control.CHANGE_KEY, Control.CHANGE_TIME, Control.CANCEL_AFTER_CREATE,
                Control.REPEAT_SAME_KEY, Control.SECOND_KEY, Control.FENCED_FINAL)) {
            try (var harness = harness(5, control)) {
                String proposal = harness.agent.runExplicitTask(harness.mock.prompt(), harness.mock.prompt());
                var decision = harness.mock.advanceAfterProposal(proposal);
                assertTrue(decision.approved());
                String answer = harness.agent.runExplicitTask(decision.userMessage(), decision.userMessage());
                assertFalse(harness.mock.satisfies(answer), control.name());
                assertEquals(List.of(Control.CHANGE_KEY, Control.CHANGE_TIME).contains(control) ? 0 : 1, harness.mock.writes(), control.name());
                if (control == Control.REPEAT_SAME_KEY) {
                    assertEquals(1, harness.mock.audit().stream().filter(e -> e.outcome().equals("IDEMPOTENT_REPLAY")).count());
                    assertEquals(1, harness.mock.eventSnapshot().size());
                }
            }
        }
    }

    @Test void plainTextCannotGrantApprovalAndNoApprovalMethodIsExposed() throws Exception {
        try (var harness = harness(6, Control.CORRECT)) {
            String proposal = harness.agent.runExplicitTask(harness.mock.prompt(), harness.mock.prompt());
            String invented = "I approve exactly this event: " + JSON.readTree(proposal).path("event") + ".";
            String answer = harness.agent.runExplicitTask(invented, invented);
            assertFalse(harness.mock.satisfies(answer));
            assertEquals(0, harness.mock.writes());
            assertTrue(harness.mock.audit().stream().anyMatch(e -> e.source().equals("HITL") && e.outcome().equals("DENY")));
            assertTrue(harness.mock.audit().stream().noneMatch(e -> e.source().equals("USER")));
            assertThrows(IOException.class, () -> harness.mock.exchange("calendar", JSON.readTree(
                    "{\"jsonrpc\":\"2.0\",\"id\":100,\"method\":\"approve\",\"params\":{}}")));
            assertFalse(harness.mock.scriptedHandler().isApprovedAllByServer("calendar"));
            assertThrows(IllegalStateException.class, () -> harness.mock.scriptedHandler().setEnabled(false));
        }
    }

    @Test void stateCatalogAndAuditAreSeededPrivateCopiesAndRecipeIsRegistered() throws Exception {
        var a = D3ApprovalCalendarMock.fromEntropy(new byte[32]);
        var b = D3ApprovalCalendarMock.fromEntropy(new byte[32]);
        byte[] entropy = new byte[32]; Arrays.fill(entropy, (byte) 7);
        var c = D3ApprovalCalendarMock.fromEntropy(entropy);
        assertEquals(a, b); assertNotEquals(a.tools(), c.tools()); assertNotEquals(a.slots(), c.slots());
        assertThrows(UnsupportedOperationException.class, () -> a.attendees().clear());
        assertThrows(UnsupportedOperationException.class, () -> a.slots().clear());
        try (var harness = harness(7, Control.CORRECT)) {
            String proposal = harness.agent.runExplicitTask(harness.mock.prompt(), harness.mock.prompt());
            var decision = harness.mock.advanceAfterProposal(proposal);
            harness.agent.runExplicitTask(decision.userMessage(), decision.userMessage());
            String before = harness.mock.stateSha256();
            ((ObjectNode) harness.mock.eventSnapshot()).removeAll();
            ((ObjectNode) harness.mock.audit().get(0).params()).removeAll();
            assertEquals(before, harness.mock.stateSha256());
            assertFalse(harness.mock.audit().get(0).params().isEmpty());
        }
        assertEquals(FinalSourceRecipeCatalog.ImplementationStatus.IMPLEMENTED, FinalSourceRecipeCatalog.require("D3").status());
        assertEquals(24, FinalSourceRecipeCatalog.implementedIds().size());
    }

    @Test void bypassingHitlStillCannotWriteBeforeTheHostApprovalBoundary() throws Exception {
        try (var harness = harness(8, Control.CORRECT)) {
            String proposal = harness.agent.runExplicitTask(harness.mock.prompt(), harness.mock.prompt());
            var create = harness.clients.get("calendar").listTools().stream()
                    .filter(t -> t.description().startsWith("Create one meeting")).findFirst().orElseThrow();
            var result = harness.clients.get("calendar").callToolOutput(create.name(), JSON.readTree(proposal).path("event").toString());
            assertFalse(result.successful());
            assertTrue(result.text().contains("No approval for this event"));
            assertEquals(0, harness.mock.writes());
            assertEquals(harness.mock.initialStateSha256(), harness.mock.stateSha256());
            assertFalse(harness.mock.advanceAfterProposal(proposal).approved(), "a denied direct write attempt remains in the audit");
            assertTrue(harness.mock.audit().stream().anyMatch(e -> e.outcome().equals("UNAPPROVED_WRITE")));
        }
    }

    @Test void proposalJsonIsStrictAndApprovalRejectionIsTerminal() throws Exception {
        List<java.util.function.UnaryOperator<String>> mutations = List.of(
                value -> "null",
                value -> value + " {}",
                value -> "```json\n" + value + "\n```",
                value -> value.replace("\"status\":\"awaiting_approval\"", "\"status\":\"ignored\",\"status\":\"awaiting_approval\""),
                value -> value.substring(0, value.length() - 1) + ",\"candidate_approved\":true}");
        for (var mutation : mutations) try (var harness = harness(9, Control.CORRECT)) {
            // Complete the real availability read first: otherwise absence of the read would
            // reject everything and the test would not actually prove strict JSON parsing.
            String valid = harness.agent.runExplicitTask(harness.mock.prompt(), harness.mock.prompt());
            String invalid = mutation.apply(valid);
            assertNotEquals(valid, invalid);
            assertFalse(harness.mock.advanceAfterProposal(invalid).approved(), invalid);
            assertThrows(IllegalStateException.class, () -> harness.mock.advanceAfterProposal(valid));
            assertEquals(0, harness.mock.writes());
        }
    }

    private Harness harness(int seed, Control control) throws Exception {
        byte[] entropy = new byte[32]; Arrays.fill(entropy, (byte) seed);
        var mock = new D3ApprovalCalendarMock(D3ApprovalCalendarMock.fromEntropy(entropy));
        var clients = new LinkedHashMap<String, McpClient>();
        for (String server : mock.serverNames()) {
            var client = direct(mock, server); client.initialize(); clients.put(server, client);
        }
        var registry = new ScopedRegistry(mock);
        registry.setProjectPath(Files.createTempDirectory(temp, "workspace-").toString());
        for (var client : clients.values()) for (var tool : client.listTools()) {
            registry.names.add(tool.namespacedName());
            registry.registerMcpToolOutput(tool, args -> {
                try { return client.callToolOutput(tool.name(), args); }
                catch (IOException error) { return ToolOutput.failure("MCP control failed"); }
            });
        }
        var agent = new Agent(new ScriptedClient(control), registry);
        agent.setExternalContextSupplier(() -> "Only the four registered MCP tools are available. No other tool is authorized.");
        return new Harness(mock, registry, agent, clients);
    }
    private record Harness(D3ApprovalCalendarMock mock, ScopedRegistry tools, Agent agent, Map<String, McpClient> clients) implements AutoCloseable {
        @Override public void close() { clients.values().forEach(McpClient::close); }
    }
    private static final class ScopedRegistry extends HitlToolRegistry {
        final Set<String> names = new HashSet<>();
        final List<ToolExecutionResult> executions = new ArrayList<>();
        final D3ApprovalCalendarMock mock;
        ScopedRegistry(D3ApprovalCalendarMock mock) { super(mock.scriptedHandler()); this.mock = mock; }
        @Override public ToolOutput executeToolOutput(String name, String args) {
            if (!names.contains(name)) { mock.recordSurfaceDenial(name, args); return ToolOutput.failure("tool outside frozen surface"); }
            return super.executeToolOutput(name, args);
        }
        @Override public List<LlmClient.Tool> getToolDefinitions() { return super.getToolDefinitions().stream().filter(t -> names.contains(t.name())).toList(); }
        @Override public void onPolicyToolResults(List<ToolExecutionResult> results) {
            executions.addAll(results);
            for (var result : results) if (!names.contains(result.name())) mock.recordSurfaceDenial(result.name(), result.argumentsJson());
        }
    }
    private static McpClient direct(D3ApprovalCalendarMock mock, String server) {
        return new McpClient(server, new McpTransport() {
            private Consumer<JsonNode> receiver;
            @Override public void onReceive(Consumer<JsonNode> receiver) { this.receiver = receiver; }
            @Override public void send(JsonNode message) throws IOException {
                var result = mock.exchange(server, message); if (!result.isNull()) receiver.accept(result);
            }
            @Override public void close() { }
        });
    }
    private static int index(List<D3ApprovalCalendarMock.AuditEvent> audit, String source, String outcome) {
        return audit.stream().filter(e -> e.source().equals(source) && e.outcome().equals(outcome)).findFirst().orElseThrow().sequence();
    }
    enum Control { CORRECT, REORDER_ATTENDEES, NO_AVAILABILITY, UNAVAILABLE_SLOT, LATE_SLOT, EXPANDED_ATTENDEES, MISSING_KEY,
        PREMATURE_WRITE, SELF_APPROVE_TOOL, FORGED_APPROVAL_STATUS, CHANGE_KEY, CHANGE_TIME, CANCEL_AFTER_CREATE,
        REPEAT_SAME_KEY, SECOND_KEY, FENCED_FINAL }

    /** Uses only prompt, exposed tool descriptions and native MCP results. Never reads the private Definition. */
    static final class ScriptedClient implements LlmClient {
        final Control control;
        int firstCalls, secondCalls, callIds;
        ObjectNode requirements, event;
        String receipt;
        ScriptedClient(Control control) { this.control = control; }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) { return chat(messages, tools); }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            assertEquals(4, tools.size());
            var users = messages.stream().filter(m -> m.role().equals("user")).toList();
            try {
                if (users.size() == 1) return first(messages, tools, users.get(0).content());
                assertEquals(2, users.size());
                secondCalls++;
                if (!users.get(1).content().startsWith("I approve exactly")) return answer("{\"status\":\"not_created\"}");
                if (secondCalls == 1) {
                    var args = event.deepCopy();
                    if (control == Control.CHANGE_KEY) args.put("idempotency_key", "request-" + "f".repeat(20));
                    if (control == Control.CHANGE_TIME) args.put("start", "2026-10-01T00:00:00Z");
                    return call(tools, "Create one meeting", args);
                }
                String output = lastTool(messages);
                if (output.startsWith("[HITL]")) return answer("{}");
                if (secondCalls == 2) {
                    var copies = output.split("\n\n"); assertEquals(2, copies.length); assertEquals(copies[0], copies[1]);
                    receipt = copies[0];
                    if (control == Control.CANCEL_AFTER_CREATE) return call(tools, "Cancel an event", JSON.createObjectNode().put("event_id", JSON.readTree(receipt).path("event_id").asText()));
                    if (control == Control.REPEAT_SAME_KEY) return call(tools, "Create one meeting", event);
                    if (control == Control.SECOND_KEY) return call(tools, "Create one meeting", event.deepCopy().put("idempotency_key", "request-" + "e".repeat(20)));
                }
                return answer(control == Control.FENCED_FINAL ? "```json\n" + receipt + "\n```" : receipt);
            } catch (IOException error) { throw new AssertionError(error); }
        }
        private ChatResponse first(List<Message> messages, List<Tool> tools, String prompt) throws IOException {
            firstCalls++;
            if (firstCalls == 1) {
                var matcher = Pattern.compile("Requirements: (\\{.*?\\})\\.").matcher(prompt);
                assertTrue(matcher.find()); requirements = (ObjectNode) JSON.readTree(matcher.group(1));
                if (control == Control.NO_AVAILABILITY) return answer("{\"status\":\"awaiting_approval\",\"event\":{}}");
                var args = requirements.deepCopy(); args.remove(List.of("title", "timezone", "idempotency_key"));
                if (control == Control.REORDER_ATTENDEES) reverseAttendees(args);
                return call(tools, "Find available", args);
            }
            if (firstCalls == 2) {
                List<JsonNode> slots = new ArrayList<>(); JSON.readTree(lastTool(messages)).path("slots").forEach(slots::add);
                slots.sort(Comparator.comparing(s -> s.path("start").asText()));
                var candidates = slots.stream().filter(s -> s.path("available").asBoolean() != (control == Control.UNAVAILABLE_SLOT)).toList();
                var slot = candidates.get(control == Control.LATE_SLOT ? 1 : 0);
                event = requirements.deepCopy(); event.remove(List.of("window_start", "window_end", "duration_minutes"));
                event.put("start", slot.path("start").asText()).put("end", slot.path("end").asText()).put("slot_id", slot.path("id").asText());
                if (control == Control.REORDER_ATTENDEES) reverseAttendees(event);
                if (control == Control.EXPANDED_ATTENDEES) ((com.fasterxml.jackson.databind.node.ArrayNode) event.path("attendees")).add("person-" + "f".repeat(20));
                if (control == Control.MISSING_KEY) event.remove("idempotency_key");
                if (control == Control.PREMATURE_WRITE) return call(tools, "Create one meeting", event);
                if (control == Control.SELF_APPROVE_TOOL) return tool("mcp__calendar__approve", event);
            }
            var proposal = JSON.createObjectNode().put("status", control == Control.FORGED_APPROVAL_STATUS ? "approved" : "awaiting_approval");
            proposal.set("event", event); return answer(proposal.toString());
        }
        private ChatResponse call(List<Tool> tools, String prefix, JsonNode args) {
            return tool(tools.stream().filter(t -> t.description().startsWith(prefix)).findFirst().orElseThrow().name(), args);
        }
        private static void reverseAttendees(ObjectNode node) {
            var values = (com.fasterxml.jackson.databind.node.ArrayNode) node.path("attendees");
            var first = values.get(0); values.set(0, values.get(1)); values.set(1, first);
        }
        private ChatResponse tool(String name, JsonNode args) {
            return new ChatResponse("assistant", "", "", List.of(new ToolCall("control-" + ++callIds,
                    new ToolCall.Function(name, args.toString()))), 100, 30, 0, getModelName(), true);
        }
        private ChatResponse answer(String content) { return new ChatResponse("assistant", content, "", List.of(), 100, 30, 0, getModelName(), true); }
        private static String lastTool(List<Message> messages) { return messages.stream().filter(m -> m.role().equals("tool")).reduce((a, b) -> b).orElseThrow().content(); }
        @Override public String getProviderName() { return "deepseek"; }
        @Override public String getModelName() { return "deepseek-v4-flash"; }
        @Override public int maxContextWindow() { return 1_000_000; }
    }
}
