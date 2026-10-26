package com.paicli.eval.benchmark.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.*;
import static com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol.*;

/** F3-only, host-retained raw evidence. Never reads Candidate audit files or decides a safety score. */
public final class F3ToolResultAudit {
    public static final long MAX_RAW_RESULT_CHARS = 16L * 1_048_576;
    public static final long MAX_AUDIT_JSON_CHARS = 64L * 1_048_576;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    // Match McpClient's parser for Candidate argument strings only. Raw strings remain preserved;
    // duplicate keys, trailing roots and non-object values are product input, not malformed audit JSON.
    private static final ObjectMapper NATIVE_ARGUMENTS = new ObjectMapper();
    private final String prompt;
    private final List<ProviderTurn> providerTurns = new ArrayList<>();
    private final List<McpExchange> mcpExchanges = new ArrayList<>();
    private final List<RawToolResult> toolResults = new ArrayList<>();
    private final List<WireToolCall> requested = new ArrayList<>();
    private final Set<Integer> mcpConsumed = new HashSet<>();
    private final List<StreamDelta> deltas = new ArrayList<>();
    private ChatRequest active;
    private WireChatResponse response;
    private WorkerComplete terminal;
    private boolean bound, failed;
    private int batchStart, finalizationToolsFrom = -1;
    private long rawChars, auditChars;

    public record ProviderTurn(int ordinal, ChatRequest request, WireChatResponse response, List<StreamDelta> streamDeltas) {
        public ProviderTurn { streamDeltas = List.copyOf(streamDeltas); }
    }
    public record McpExchange(int ordinal, int providerTurn, int toolOrdinal, McpRequest request, McpComplete response) { }
    public record Snapshot(int schemaVersion, String kind, String promptSha256, boolean failed,
                           List<ProviderTurn> providerTurns, List<McpExchange> mcpExchanges,
                           List<RawToolResult> toolResults, WorkerComplete terminal) {
        public Snapshot { providerTurns = List.copyOf(providerTurns); mcpExchanges = List.copyOf(mcpExchanges); toolResults = List.copyOf(toolResults); }
    }

    public F3ToolResultAudit(String frozenPrompt) {
        prompt = Objects.requireNonNull(frozenPrompt);
        if (prompt.isBlank() || prompt.length() > MAX_TEXT_CHARS) throw new IllegalArgumentException("invalid F3 prompt");
    }
    synchronized void bind(SessionStart session) throws Failure {
        require(!bound && !failed && session != null, "F3 audit cannot be rebound");
        require(session.mode() == AgentMode.REACT && session.toolProfile() == ToolProfile.MOCK_MCP_FILE_ONLY
                && session.interactionMode() == InteractionMode.SINGLE_TURN && session.mockServers().equals(List.of("support"))
                && prompt.equals(session.prompt()), "F3 session binding mismatch");
        bound = true;
    }
    synchronized void begin(ChatRequest request, boolean budgetFinalization) throws Failure {
        require(bound && !failed && active == null && terminal == null && finalizationToolsFrom < 0,
                "F3 provider request lifecycle mismatch");
        require(toolResults.size() == requested.size(), "F3 raw results missing before next provider request");
        var user = request.messages().stream().filter(m -> "user".equals(m.role())).findFirst();
        require(request.executionScope() == null && user.isPresent() && prompt.equals(user.get().content())
                && user.get().contentParts().isEmpty(), "F3 provider input differs from frozen prompt");
        require(providerTurns.size() < MAX_TOOL_EXECUTIONS, "F3 provider turn limit");
        if (budgetFinalization) {
            require(request.tools().isEmpty(), "F3 budget finalization exposes tools");
            finalizationToolsFrom = requested.size();
        }
        reserve(request); active = copy(request, ChatRequest.class); response = null; deltas.clear();
    }
    synchronized void delta(StreamDelta delta) throws Failure {
        require(active != null && !failed && response == null && deltas.size() < 65_536
                && delta.callId().equals(active.callId()) && delta.eventSequence() == deltas.size() + 1L,
                "F3 stream evidence lifecycle mismatch");
        reserve(delta); deltas.add(delta);
    }
    synchronized void response(WireChatResponse value) throws Failure {
        require(active != null && !failed && response == null && value != null, "F3 provider response lifecycle mismatch");
        require(value.toolCalls().size() <= MAX_TOOL_EXECUTIONS - requested.size(), "F3 tool request limit");
        reserve(value); response = copy(value, WireChatResponse.class);
        batchStart = requested.size(); requested.addAll(response.toolCalls());
    }
    /** Provider failures may retain an incomplete turn; the host classifies the termination before scoring. */
    synchronized void end() {
        if (active != null) providerTurns.add(new ProviderTurn(providerTurns.size() + 1, active, response, deltas));
        active = null; response = null; deltas.clear();
    }
    synchronized int beforeMcp(McpRequest request) throws Failure {
        require(bound && !failed && active == null && terminal == null && request.server().equals("support"), "F3 MCP lifecycle mismatch");
        String method = request.message().path("method").asText();
        if (!method.equals("tools/call")) {
            require(providerTurns.isEmpty() && Set.of("initialize", "notifications/initialized", "tools/list").contains(method),
                    "F3 unexpected MCP handshake");
            return 0;
        }
        var params = request.message().path("params");
        String name = "mcp__support__" + params.path("name").asText();
        for (int i = batchStart; i < requested.size(); i++) {
            var call = requested.get(i);
            if (!mcpConsumed.contains(i) && name.equals(call.name())) {
                try {
                    var args = call.arguments() == null || call.arguments().isBlank()
                            ? NATIVE_ARGUMENTS.createObjectNode() : NATIVE_ARGUMENTS.readTree(call.arguments());
                    if (args == null) args = NATIVE_ARGUMENTS.createObjectNode();
                    if (args.equals(params.path("arguments"))) {
                        mcpConsumed.add(i); return i + 1;
                    }
                } catch (IOException ignored) { /* malformed model arguments are not transport authorization */ }
            }
        }
        throw fail("F3 MCP request has no unconsumed current provider tool call");
    }
    synchronized void mcp(McpRequest request, McpComplete response, int ordinal) throws Failure {
        require(!failed && mcpExchanges.size() < MAX_TOOL_EXECUTIONS, "F3 MCP audit unavailable/oversized");
        var exchange = new McpExchange(mcpExchanges.size() + 1, ordinal == 0 ? 0 : providerTurns.size(), ordinal,
                copy(request, McpRequest.class), copy(response, McpComplete.class));
        reserve(exchange); mcpExchanges.add(exchange);
    }
    synchronized void raw(RawToolResult value) throws Failure {
        require(bound && !failed && active == null && terminal == null && value != null
                && toolResults.size() < MAX_TOOL_EXECUTIONS && value.ordinal() == toolResults.size() + 1,
                "F3 raw tool result lifecycle mismatch");
        require(value.ordinal() <= requested.size(), "F3 raw result has no provider call");
        var call = requested.get(value.ordinal() - 1);
        require(call.id().equals(value.callId()) && call.name().equals(value.toolName())
                && call.arguments().equals(value.argumentsJson()), "F3 raw result differs from provider tool request");
        require(value.result().length() <= MAX_RAW_RESULT_CHARS - rawChars, "F3 raw result total limit");
        reserve(value); rawChars += value.result().length(); toolResults.add(value);
    }
    synchronized void complete(WorkerComplete value) throws Failure {
        require(bound && !failed && active == null && terminal == null && value != null, "F3 terminal lifecycle mismatch");
        int required = finalizationToolsFrom < 0 ? requested.size() : finalizationToolsFrom;
        require(toolResults.size() >= required && toolResults.size() <= requested.size()
                && value.toolExecutions().size() == toolResults.size(), "F3 terminal omitted raw tool evidence");
        for (int i = 0; i < toolResults.size(); i++) {
            var raw = toolResults.get(i); var actual = value.toolExecutions().get(i);
            String preview = raw.result().substring(0, Math.min(16_384, raw.result().length()));
            var expected = new WireToolExecution(raw.ordinal(), raw.callId(), raw.toolName(), raw.argumentsJson(), preview,
                    textSha256(raw.result()), raw.result().length(), raw.elapsedMillis(), raw.timedOut(), raw.successful());
            require(expected.equals(actual), "F3 terminal preview/digest differs from original result");
        }
        reserve(value); terminal = value;
    }
    public synchronized WorkerComplete terminal() { return terminal; }
    public synchronized boolean failed() { return failed; }
    /** Host-only bounded stream capture failed; never downgrade this to an upstream API failure. */
    public synchronized Failure invalidateStreamObservation() { return fail("F3 provider stream observation is incomplete"); }
    public synchronized void requireHealthy() throws Failure { require(!failed, "F3 host evidence is invalid"); }
    public synchronized Snapshot snapshot() throws Failure {
        var turns = new ArrayList<>(providerTurns);
        if (active != null) turns.add(new ProviderTurn(turns.size() + 1, active, response, deltas));
        return copy(new Snapshot(1, "F3_HOST_TOOL_RESULT_AUDIT", textSha256(prompt), failed,
                turns, mcpExchanges, toolResults, terminal), Snapshot.class);
    }
    synchronized Failure fail(String message) { failed = true; return new Failure(message); }
    private void require(boolean ok, String message) throws Failure { if (!ok) throw fail(message); }
    private void reserve(Object value) throws Failure {
        try {
            long size = JSON.writeValueAsString(value).length() + 128L;
            require(size <= MAX_AUDIT_JSON_CHARS - auditChars, "F3 complete audit limit"); auditChars += size;
        } catch (IOException error) { throw fail("F3 audit serialization failed"); }
    }
    private <T> T copy(T value, Class<T> type) throws Failure {
        try { return JSON.readValue(JSON.writeValueAsBytes(value), type); }
        catch (IOException | RuntimeException error) { throw fail("F3 evidence copy failed"); }
    }
    public static final class Failure extends IOException { private Failure(String message) { super(message); } }
}
