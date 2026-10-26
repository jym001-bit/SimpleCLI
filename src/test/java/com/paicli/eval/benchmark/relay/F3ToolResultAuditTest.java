package com.paicli.eval.benchmark.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol.*;
import static org.junit.jupiter.api.Assertions.*;

class F3ToolResultAuditTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROMPT = "Frozen F3 diagnostic task";
    private static final Provider PROVIDER = new Provider();

    @Test void retainsFullRawResultAndStreamOnlyOutputAndOriginalTerminal() throws Exception {
        var audit = bound(); var request = request("r1"); audit.begin(request, false);
        audit.delta(new StreamDelta(out(FrameType.STREAM_DELTA, "r1", 1), DeltaKind.REASONING, "stream-only-fake"));
        audit.response(response(List.of(call("same", "read_file", "{}")))); audit.end();
        String full = "x".repeat(20_000) + "FAKE_CREDENTIAL_AFTER_PREVIEW";
        var raw = raw(1, "same", "read_file", "{}", full); audit.raw(raw);
        var terminal = terminal(List.of(wire(raw))); audit.complete(terminal);
        assertSame(terminal, audit.terminal()); audit.requireHealthy();
        var snapshot = audit.snapshot();
        assertEquals(full, snapshot.toolResults().get(0).result());
        assertFalse(snapshot.terminal().toolExecutions().get(0).resultPreview().contains("FAKE_CREDENTIAL"));
        assertEquals("stream-only-fake", snapshot.providerTurns().get(0).streamDeltas().get(0).delta());
        assertEquals("reasoning retained", snapshot.providerTurns().get(0).response().reasoningContent());
        assertEquals(request, snapshot.providerTurns().get(0).request());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.toolResults().clear());
    }

    @Test void rawIdentityOrderMissingAndPreviewTamperingAreTypedDefects() throws Exception {
        for (int variant = 0; variant < 4; variant++) {
            var audit = bound(); audit.begin(request("r1"), false);
            audit.response(response(List.of(call("same", "read_file", "{}")))); audit.end();
            if (variant == 0) assertThrows(F3ToolResultAudit.Failure.class, () -> audit.complete(terminal(List.of())));
            if (variant == 1) assertThrows(F3ToolResultAudit.Failure.class, () -> audit.raw(raw(1, "other", "read_file", "{}", "ok")));
            if (variant == 2) assertThrows(F3ToolResultAudit.Failure.class, () -> audit.begin(request("r2"), false));
            if (variant == 3) {
                audit.raw(raw(1, "same", "read_file", "{}", "secret"));
                assertThrows(F3ToolResultAudit.Failure.class, () -> audit.complete(terminal(List.of(wire(raw(1, "same", "read_file", "{}", "public"))))));
            }
            assertTrue(audit.failed()); assertThrows(F3ToolResultAudit.Failure.class, audit::requireHealthy);
        }
    }

    @Test void mcpMatchesOnlyCurrentProviderBatchAndConsumesEachPermitOnce() throws Exception {
        var audit = bound();
        var call = call("same", "mcp__support__get_case", "{\"case_id\":\"one\"}");
        for (int turn = 1; turn <= 2; turn++) {
            audit.begin(request("r" + turn), false); audit.response(response(List.of(call))); audit.end();
            var request = mcp("m" + turn, "one"); int ordinal = audit.beforeMcp(request);
            assertEquals(turn, ordinal);
            audit.mcp(request, new McpComplete(out(FrameType.MCP_COMPLETE, request.callId(), 1), "support", JSON.nullNode()), ordinal);
            audit.raw(raw(turn, "same", call.name(), call.arguments(), "ok"));
        }
        assertEquals(List.of(1, 2), audit.snapshot().mcpExchanges().stream().map(F3ToolResultAudit.McpExchange::providerTurn).toList());
        assertThrows(F3ToolResultAudit.Failure.class, () -> audit.beforeMcp(mcp("duplicate", "one")));
        var changed = bound(); changed.begin(request("r1"), false); changed.response(response(List.of(call))); changed.end();
        assertThrows(F3ToolResultAudit.Failure.class, () -> changed.beforeMcp(mcp("wrong", "two")));
    }

    @Test void candidateMcpArgumentsMatchNativeParsingWithoutRelaxingEvidenceJson() throws Exception {
        for (String arguments : List.of("{\"case_id\":\"one\",\"case_id\":\"one\"}",
                "{\"case_id\":\"wrong\",\"case_id\":\"one\"}", "{\"case_id\":\"one\"} {}")) {
            var audit = bound(); audit.begin(request("r1"), false);
            audit.response(response(List.of(call("id", "mcp__support__get_case", arguments)))); audit.end();
            assertEquals(1, audit.beforeMcp(mcp("m", "one")));
            audit.raw(raw(1, "id", "mcp__support__get_case", arguments, "ok")); audit.requireHealthy();
            assertEquals(arguments, audit.snapshot().toolResults().get(0).argumentsJson());
        }
        for (String arguments : List.of("null", "[]", "1", " ")) {
            var audit = bound(); audit.begin(request("r1"), false);
            audit.response(response(List.of(call("id", "mcp__support__get_case", arguments)))); audit.end();
            var message = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
            message.putObject("params").put("name", "get_case").set("arguments",
                    arguments.isBlank() ? JSON.createObjectNode() : JSON.readTree(arguments));
            assertEquals(1, audit.beforeMcp(new McpRequest(in(FrameType.MCP_REQUEST, "m"), "support", message)));
            audit.requireHealthy();
        }
    }

    @Test void budgetClosingOnlyExcusesItsOwnIgnoredTrailingTools() throws Exception {
        var audit = bound(); audit.begin(request("r1"), false);
        audit.response(response(List.of(call("same", "read_file", "{}")))); audit.end();
        var raw = raw(1, "same", "read_file", "{}", "ok"); audit.raw(raw);
        audit.begin(request("closing"), true); audit.response(response(List.of(call("ignored", "write_file", "{}")))); audit.end();
        audit.complete(terminal(List.of(wire(raw)))); audit.requireHealthy();
        assertEquals(2, audit.snapshot().providerTurns().size()); assertEquals(1, audit.snapshot().toolResults().size());
        var failure = bound(); failure.begin(request("upstream-error"), false); failure.end();
        assertNull(failure.snapshot().providerTurns().get(0).response()); assertNull(failure.terminal()); failure.requireHealthy();
    }

    @Test void singleAndCumulativeRawLimitsDoNotSilentlyTruncate() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> raw(1, "id", "read_file", "{}", "x".repeat(MAX_RESULT_CHARS + 1)));
        var audit = bound(); audit.begin(request("r1"), false);
        audit.response(response(Collections.nCopies(5, call("id", "read_file", "{}")))); audit.end();
        String full = "x".repeat(MAX_RESULT_CHARS);
        for (int i = 1; i <= 4; i++) audit.raw(raw(i, "id", "read_file", "{}", full));
        assertThrows(F3ToolResultAudit.Failure.class, () -> audit.raw(raw(5, "id", "read_file", "{}", "x")));
        assertTrue(audit.failed()); assertEquals(4, audit.snapshot().toolResults().size());
    }

    @Test void wireRoundTripRejectsCoercionsUnknownFieldsAndFutureVersions() throws Exception {
        var frame = new F3ToolResult(in(FrameType.F3_TOOL_RESULT, "raw-1"), raw(1, "id", "read_file", "{}", "full\n中文"));
        assertEquals(frame, decode(encode(frame))); assertEquals(12, VERSION);
        var valid = (ObjectNode)JSON.readTree(encode(frame));
        List<java.util.function.Consumer<ObjectNode>> mutations = List.of(
                n -> n.put("ordinal", "1"), n -> n.put("ordinal", 1.0), n -> n.put("elapsedMillis", false),
                n -> n.put("timedOut", 0), n -> n.put("successful", "true"), n -> n.putNull("result"),
                n -> n.put("argumentsJson", 1), n -> n.remove("callId"), n -> n.put("extra", true));
        for (var mutation : mutations) {
            var value = valid.deepCopy(); mutation.accept((ObjectNode)value.path("payload").path("result"));
            assertThrows(F3EvidenceException.class, () -> decode(JSON.writeValueAsBytes(value)));
        }
        for (var version : List.of(JSON.getNodeFactory().numberNode(13), JSON.getNodeFactory().numberNode(12.0), JSON.getNodeFactory().textNode("12"))) {
            var value = valid.deepCopy(); value.set("protocolVersion", version);
            assertThrows(F3EvidenceException.class, () -> decode(JSON.writeValueAsBytes(value)));
        }
    }

    @Test void rawFramesCannotEnterOldProfilesOrInterleaveWithChatAndAcksMustMatch() throws Exception {
        var raw = new F3ToolResult(in(FrameType.F3_TOOL_RESULT, "raw"), raw(1, "id", "read_file", "{}", "ok"));
        for (var profile : ToolProfile.values()) {
            var validator = new Validator(); validator.accept(session(profile));
            validator.accept(new WorkerReady(in(FrameType.WORKER_READY, ""), BenchmarkProviderRelay.capabilitiesOf(PROVIDER)));
            if (profile != ToolProfile.MOCK_MCP_FILE_ONLY) assertThrows(IllegalStateException.class, () -> validator.accept(raw));
            else {
                validator.accept(raw);
                assertThrows(IllegalStateException.class, () -> validator.accept(request("interleaved")));
                assertThrows(IllegalStateException.class, () -> validator.accept(new F3ToolResultAck(out(FrameType.F3_TOOL_RESULT_ACK, "other", 1))));
                validator.accept(new F3ToolResultAck(out(FrameType.F3_TOOL_RESULT_ACK, "raw", 1)));
            }
        }
    }

    @Test void hostRequiresAuditAndStickyWorkerObservationFailureStaysTyped() throws Exception {
        var audit = new F3ToolResultAudit(PROMPT);
        assertThrows(IOException.class, () -> BenchmarkProviderRelay.connect(emptyChannel(), session(ToolProfile.MOCK_MCP_FILE_ONLY), PROVIDER, endpoint()));
        var input = new ByteArrayOutputStream(); var encoded = new BenchmarkFramedChannel(new ByteArrayInputStream(new byte[0]), input);
        encoded.write(new WorkerReady(in(FrameType.WORKER_READY, ""), BenchmarkProviderRelay.capabilitiesOf(PROVIDER)));
        encoded.write(new WorkerFailure(in(FrameType.WORKER_FAILURE, ""), "F3_TOOL_RESULT_EVIDENCE_INVALID", "lost"));
        var relay = BenchmarkProviderRelay.connect(new BenchmarkFramedChannel(new ByteArrayInputStream(input.toByteArray()), new ByteArrayOutputStream()),
                session(ToolProfile.MOCK_MCP_FILE_ONLY), PROVIDER, endpoint(), null, null, null, audit);
        assertThrows(F3ToolResultAudit.Failure.class, relay::serveNext); assertTrue(audit.failed()); assertNull(relay.providerFailureType());
        var changed = bound(); assertThrows(F3ToolResultAudit.Failure.class, () -> changed.bind(session(ToolProfile.MOCK_MCP_FILE_ONLY)));
    }

    @Test void workerOversizedResultBeforeSendingRawFrameStillSendsTypedFailure() throws Exception {
        var input = new ByteArrayOutputStream();
        new BenchmarkFramedChannel(new ByteArrayInputStream(new byte[0]), input).write(session(ToolProfile.MOCK_MCP_FILE_ONLY));
        var output = new ByteArrayOutputStream();
        var worker = RelayLlmClient.accept(new BenchmarkFramedChannel(new ByteArrayInputStream(input.toByteArray()), output));
        var oversized = new com.paicli.tool.ToolRegistry.ToolExecutionResult("id", "read_file", "{}",
                "x".repeat(MAX_RESULT_CHARS + 1), 1, false, List.of(), true, List.of());
        assertThrows(IllegalStateException.class, () -> worker.observeF3ToolResult(oversized));
        worker.fail("CANDIDATE_WORKER_ERROR", "ordinary outer exception");
        var frames = new BenchmarkFramedChannel(new ByteArrayInputStream(output.toByteArray()), new ByteArrayOutputStream());
        assertInstanceOf(WorkerReady.class, frames.read());
        var failure = assertInstanceOf(WorkerFailure.class, frames.read());
        assertEquals("F3_TOOL_RESULT_EVIDENCE_INVALID", failure.errorType()); assertNull(frames.read());
    }

    private static BenchmarkProviderRelay.MockMcpEndpoint endpoint() {
        return new BenchmarkProviderRelay.MockMcpEndpoint() {
            public com.fasterxml.jackson.databind.JsonNode exchange(com.fasterxml.jackson.databind.JsonNode input) { return JSON.nullNode(); }
            public List<String> serverNames() { return List.of("support"); }
        };
    }
    private static BenchmarkFramedChannel emptyChannel() { return new BenchmarkFramedChannel(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()); }
    private static F3ToolResultAudit bound() throws Exception { var audit = new F3ToolResultAudit(PROMPT); audit.bind(session(ToolProfile.MOCK_MCP_FILE_ONLY)); return audit; }
    private static Header in(FrameType type, String id) { return new Header(Direction.WORKER_TO_COORDINATOR, type, id, 0); }
    private static Header out(FrameType type, String id, long sequence) { return new Header(Direction.COORDINATOR_TO_WORKER, type, id, sequence); }
    private static ChatRequest request(String id) { return new ChatRequest(in(FrameType.CHAT_REQUEST, id), List.of(
            new WireMessage("system", "frozen", null, List.of(), null), new WireMessage("user", PROMPT, null, List.of(), null)), List.of()); }
    private static WireToolCall call(String id, String name, String args) { return new WireToolCall(id, name, args); }
    private static WireChatResponse response(List<WireToolCall> calls) { return new WireChatResponse("assistant", "", "reasoning retained", calls, 10, 2, 0, "deepseek-v4-flash", true); }
    private static RawToolResult raw(int ordinal, String id, String name, String args, String result) { return new RawToolResult(ordinal, id, name, args, result, 2, false, true); }
    private static WireToolExecution wire(RawToolResult r) { return new WireToolExecution(r.ordinal(), r.callId(), r.toolName(), r.argumentsJson(), r.result().substring(0, Math.min(16_384, r.result().length())), textSha256(r.result()), r.result().length(), r.elapsedMillis(), r.timedOut(), r.successful()); }
    private static WorkerComplete terminal(List<WireToolExecution> tools) { return new WorkerComplete(in(FrameType.WORKER_COMPLETE, ""), "done", tools); }
    private static McpRequest mcp(String id, String value) {
        var node = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
        node.putObject("params").put("name", "get_case").putObject("arguments").put("case_id", value);
        return new McpRequest(in(FrameType.MCP_REQUEST, id), "support", node);
    }
    private static SessionStart session(ToolProfile profile) {
        return new SessionStart(out(FrameType.SESSION_START, "", 0), "f3-audit", PROVIDER.getProviderName(), PROVIDER.getModelName(), AgentMode.REACT,
                profile, PROMPT, "2026-09-05", "UTC", System.currentTimeMillis() + 60_000,
                new AgentLimits(100_000, 32, 8, 1_000_000, 16_384), BenchmarkProviderRelay.capabilitiesOf(PROVIDER),
                new Limits(BenchmarkFramedChannel.MAX_FRAME_BYTES, BenchmarkFramedChannel.MAX_SESSION_BYTES, MAX_MESSAGES, MAX_TOOLS),
                profile == ToolProfile.MOCK_MCP_FILE_ONLY ? List.of("support") : profile == ToolProfile.MOCK_MCP ? List.of("benchmark") : List.of(), InteractionMode.SINGLE_TURN);
    }
    private static final class Provider implements LlmClient {
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) { throw new AssertionError("no provider call expected"); }
        public ChatResponse chat(List<Message> messages, List<Tool> tools) { throw new AssertionError("no provider call expected"); }
        public String getModelName() { return "deepseek-v4-flash"; }
        public String getProviderName() { return "deepseek"; }
        public int maxContextWindow() { return 1_000_000; }
    }
}
