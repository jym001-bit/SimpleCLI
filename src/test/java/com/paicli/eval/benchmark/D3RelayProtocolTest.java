package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.eval.benchmark.relay.*;
import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.List;

import static com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol.*;
import static org.junit.jupiter.api.Assertions.*;

class D3RelayProtocolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NAME = "mcp__calendar__op_test";
    private static final String ARGS = "{\"x\":1}";

    @Test void closedWorkflowRejectsExtraTurnsForgedDecisionsWrongArgumentsAndInterleaving() throws Exception {
        var provider = new CountingProvider(true, true);
        var start = start(provider, 100_000);
        var approval = new ApprovalRequest(worker(FrameType.APPROVAL_REQUEST, "approval-1"), 1, NAME, ARGS);
        var decision = new ApprovalComplete(host(FrameType.APPROVAL_COMPLETE, "approval-1"), 1, NAME, textSha256(ARGS), true, "allow");
        var turn = new TurnComplete(worker(FrameType.TURN_COMPLETE, "turn-1"), 1, "proposal", List.of());
        var next = new TurnContinue(host(FrameType.TURN_CONTINUE, "turn-1"), 2, "host user message");
        for (Frame frame : List.of(start, approval, decision, turn, next)) assertEquals(frame, decode(encode(frame)));
        var validator = new Validator(); validator.accept(start); validator.accept(ready(start));
        assertThrows(IllegalStateException.class, () -> validator.accept(new WorkerComplete(worker(FrameType.WORKER_COMPLETE, ""), "early")));
        assertThrows(IllegalStateException.class, () -> validator.accept(next));
        assertThrows(IllegalStateException.class, () -> validator.accept(new ApprovalRequest(worker(FrameType.APPROVAL_REQUEST, "bad"), 2, NAME, ARGS)));
        validator.accept(approval);
        assertThrows(IllegalStateException.class, () -> validator.accept(turn));
        assertThrows(IllegalStateException.class, () -> validator.accept(new ApprovalComplete(host(FrameType.APPROVAL_COMPLETE, "approval-1"), 1, NAME, "0".repeat(64), true, "forged")));
        assertThrows(IllegalStateException.class, () -> validator.accept(new ApprovalComplete(host(FrameType.APPROVAL_COMPLETE, "other"), 1, NAME, textSha256(ARGS), true, "forged")));
        assertThrows(IllegalStateException.class, () -> validator.accept(new ApprovalComplete(host(FrameType.APPROVAL_COMPLETE, "approval-1"), 2, NAME, textSha256(ARGS), true, "forged")));
        validator.accept(decision);
        assertThrows(IllegalStateException.class, () -> validator.accept(approval));
        validator.accept(turn); validator.accept(next);
        assertThrows(IllegalStateException.class, () -> validator.accept(new TurnComplete(worker(FrameType.TURN_COMPLETE, "turn-again"), 1, "proposal", List.of())));
        assertThrows(IllegalStateException.class, () -> validator.accept(new ApprovalRequest(worker(FrameType.APPROVAL_REQUEST, "old-turn"), 1, NAME, ARGS)));
        var finalFrame = new WorkerComplete(worker(FrameType.WORKER_COMPLETE, ""), "done");
        validator.accept(finalFrame);
        assertThrows(IllegalStateException.class, () -> validator.accept(finalFrame));

        var single = new SessionStart(start.header(), start.sessionId(), start.provider(), start.model(), start.mode(), start.toolProfile(),
                start.prompt(), start.runtimeDate(), start.runtimeZone(), start.deadlineEpochMillis(), start.agentLimits(), start.capabilities(), start.limits(), start.mockServers());
        var singleValidator = new Validator(); singleValidator.accept(single); singleValidator.accept(ready(single));
        assertThrows(IllegalStateException.class, () -> singleValidator.accept(approval));
        assertThrows(IllegalStateException.class, () -> singleValidator.accept(turn));
        assertThrows(IllegalArgumentException.class, () -> new SessionStart(start.header(), start.sessionId(), start.provider(), start.model(), AgentMode.PLAN,
                start.toolProfile(), start.prompt(), start.runtimeDate(), start.runtimeZone(), start.deadlineEpochMillis(), start.agentLimits(), start.capabilities(), start.limits(), start.mockServers(), start.interactionMode()));
        String wire = new String(encode(decision), java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> decode(wire.replace("\"approved\":true", "\"approved\":true,\"approveAll\":true").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> decode(wire.replace("\"protocolVersion\":" + VERSION,
                "\"protocolVersion\":" + (VERSION - 1)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test void hostRefusesMissingAlteredOrRewrittenToolEvidenceBeforeApproval() throws Exception {
        for (List<WireToolExecution> evidence : List.of(List.<WireToolExecution>of(), List.of(execution("{\"x\":2}", "ok")))) {
            var provider = new CountingProvider(true, true); var mock = new ClosedMock();
            var relay = relay(provider, mock, 100_000, chat("c1", true), new TurnComplete(worker(FrameType.TURN_COMPLETE, "turn1"), 1, "proposal", evidence));
            relay.serveNext();
            assertThrows(IOException.class, relay::serveNext);
            assertEquals(0, mock.nextCalls);
        }
        var provider = new CountingProvider(true, true); var mock = new ClosedMock();
        var relay = relay(provider, mock, 100_000, chat("c1", true),
                new TurnComplete(worker(FrameType.TURN_COMPLETE, "turn1"), 1, "proposal", List.of(execution(ARGS, "ok"))),
                new WorkerComplete(worker(FrameType.WORKER_COMPLETE, ""), "done", List.of(execution(ARGS, "rewritten"))));
        relay.serveNext(); relay.serveNext();
        assertEquals(1, mock.nextCalls);
        assertThrows(IOException.class, relay::serveNext);
    }

    @Test void approvalRequiresAnUpstreamCallAndMcpPermitIsArgumentBoundAndSingleUse() throws Exception {
        var provider = new CountingProvider(true, true); var mock = new ClosedMock();
        var approval = new ApprovalRequest(worker(FrameType.APPROVAL_REQUEST, "a1"), 1, NAME, ARGS);
        var noCall = relay(provider, mock, 100_000, approval);
        assertThrows(IOException.class, noCall::serveNext);
        assertEquals(0, mock.approvals);
        var noApproval = relay(provider, mock, 100_000, chat("c1", true), mcp("m1", ARGS));
        noApproval.serveNext(); assertThrows(IOException.class, noApproval::serveNext);
        assertEquals(0, mock.exchanges);
        var wrongArgs = relay(provider, mock, 100_000, chat("c1", true), approval, mcp("m1", "{\"x\":2}"));
        wrongArgs.serveNext(); wrongArgs.serveNext(); assertThrows(IOException.class, wrongArgs::serveNext);
        assertEquals(0, mock.exchanges);
        var replay = relay(provider, mock, 100_000, chat("c1", true), approval, mcp("m1", ARGS), mcp("m2", ARGS));
        replay.serveNext(); replay.serveNext(); replay.serveNext();
        assertEquals(1, mock.exchanges);
        assertThrows(IOException.class, replay::serveNext);
        assertEquals(1, mock.exchanges);
        var stale = relay(provider, mock, 100_000, chat("c1", true), approval,
                new TurnComplete(worker(FrameType.TURN_COMPLETE, "turn1"), 1, "proposal", List.of(execution(ARGS, "unused"))),
                mcp("m1", ARGS));
        stale.serveNext(); stale.serveNext(); stale.serveNext();
        assertThrows(IOException.class, stale::serveNext, "unused approval cannot cross into another user turn");
        assertEquals(1, mock.exchanges);
    }

    @Test void hostBudgetCannotBeBypassedByAWorkerAndOnlyOneToolFreeClosingCallIsAllowed() throws Exception {
        var provider = new CountingProvider(false, true); var mock = new ClosedMock();
        var relay = relay(provider, mock, 130, chat("c1", true), chat("c2", true), chat("c3", false), chat("c4", false),
                new TurnComplete(worker(FrameType.TURN_COMPLETE, "turn1"), 1, "proposal", List.of()));
        relay.serveNext(); assertEquals(1, provider.calls);
        relay.serveNext(); assertEquals(1, provider.calls, "normal requests after exhaustion never reach provider");
        relay.serveNext(); assertEquals(2, provider.calls, "one closing call is retained");
        relay.serveNext(); assertEquals(2, provider.calls, "no second closing allowance");
        assertThrows(IOException.class, relay::serveNext);
        assertEquals(0, mock.nextCalls);
    }

    @Test void missingProviderUsageCannotBecomeAUserApproval() throws Exception {
        var provider = new CountingProvider(false, false); var mock = new ClosedMock();
        var relay = relay(provider, mock, 100_000, chat("c1", true), new TurnComplete(worker(FrameType.TURN_COMPLETE, "turn1"), 1, "proposal", List.of()));
        relay.serveNext();
        assertEquals("PROVIDER_USAGE_INCOMPLETE", relay.providerFailureType());
        assertThrows(IOException.class, relay::serveNext);
        assertEquals(0, mock.nextCalls);
    }

    private static BenchmarkProviderRelay relay(CountingProvider provider, ClosedMock mock, int tokens, Frame... frames) throws Exception {
        var start = start(provider, tokens);
        var bytes = new ByteArrayOutputStream();
        var writer = new BenchmarkFramedChannel(InputStream.nullInputStream(), bytes); writer.write(ready(start));
        for (Frame frame : frames) writer.write(frame);
        return BenchmarkProviderRelay.connect(new BenchmarkFramedChannel(new ByteArrayInputStream(bytes.toByteArray()), new ByteArrayOutputStream()), start, provider, mock);
    }
    private static SessionStart start(LlmClient provider, int tokens) { return D3McpRelayTest.start(provider, D3McpRelayTest.mock(31), tokens, 12); }
    private static WorkerReady ready(SessionStart start) { return new WorkerReady(worker(FrameType.WORKER_READY, ""), start.capabilities()); }
    private static Header worker(FrameType type, String call) { return new Header(Direction.WORKER_TO_COORDINATOR, type, call, 0); }
    private static Header host(FrameType type, String call) { return new Header(Direction.COORDINATOR_TO_WORKER, type, call, 1); }
    private static ChatRequest chat(String call, boolean tools) {
        return new ChatRequest(worker(FrameType.CHAT_REQUEST, call), List.of(new WireMessage("user", "task", null, List.of(), null)),
                tools ? List.of(new WireTool(NAME, "test", JSON.createObjectNode())) : List.of());
    }
    private static McpRequest mcp(String call, String args) throws Exception {
        var message = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
        message.putObject("params").put("name", "op_test").set("arguments", JSON.readTree(args));
        return new McpRequest(worker(FrameType.MCP_REQUEST, call), "calendar", message);
    }
    private static WireToolExecution execution(String args, String result) {
        return new WireToolExecution(1, "tool-1", NAME, args, result, textSha256(result), result.length(), 0, false, true);
    }
    private static final class CountingProvider implements LlmClient {
        private final boolean tool, usage;
        int calls;
        CountingProvider(boolean tool, boolean usage) { this.tool = tool; this.usage = usage; }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) { return chat(messages, tools); }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            calls++;
            return new ChatResponse("assistant", "answer", "", tool ? List.of(new ToolCall("tool-1", new ToolCall.Function(NAME, ARGS))) : List.of(),
                    100, 30, 0, getModelName(), usage);
        }
        @Override public String getProviderName() { return "deepseek"; }
        @Override public String getModelName() { return "deepseek-v4-flash"; }
        @Override public int maxContextWindow() { return 1_000_000; }
    }
    /** Protocol spy only, not a D3 oracle or a claim about business correctness. */
    private static final class ClosedMock implements ScriptedInteraction {
        int approvals, exchanges, nextCalls;
        @Override public List<String> serverNames() { return List.of("availability", "calendar"); }
        @Override public JsonNode exchange(JsonNode message) { throw new UnsupportedOperationException(); }
        @Override public JsonNode exchange(String server, JsonNode message) { exchanges++; return JSON.createObjectNode(); }
        @Override public String nextUserMessage(String firstAnswer) { nextCalls++; return "host decision"; }
        @Override public Decision approve(String name, String args) { approvals++; return new Decision(NAME.equals(name) && ARGS.equals(args), "test decision"); }
        @Override public void recordCompletedTools(List<WireToolExecution> tools) { }
        @Override public void recordExchange(int turn, Frame request, Frame response) { }
    }
}
