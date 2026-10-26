package com.paicli.eval.benchmark.relay;

import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol.*;

/** Host relay association only: no Docker, command subprocess, or provider API. */
class F2CommandAuditTest {
    private static final String PROMPT = "F2 frozen diagnostic task";

    @Test void bindsActualProviderCallsAndRetainsOriginalTerminalWithRepeatedToolIds() throws Exception {
        var provider = new Provider(false);
        var expected = List.of(tool(1, "same-id", "read_file", "{\"path\":\"README.md\"}"),
                tool(2, "same-id", "execute_command", "{\"command\":\"python3 -I -B diagnose.py\"}"));
        var terminal = new WorkerComplete(header(FrameType.WORKER_COMPLETE, ""), "done", expected);
        var audit = new F2CommandAudit(PROMPT);
        var relay = connect(provider, audit, request("first", PROMPT), request("second", PROMPT), terminal);
        assertEquals(BenchmarkProviderRelay.ServeResult.CHAT_SERVED, relay.serveNext());
        assertEquals(BenchmarkProviderRelay.ServeResult.CHAT_SERVED, relay.serveNext());
        assertEquals(BenchmarkProviderRelay.ServeResult.WORKER_COMPLETE, relay.serveNext());
        assertSame(relay.terminalFrame(), audit.terminal());
        assertEquals(2, audit.requestedTools().size());
        assertEquals(List.of("same-id", "same-id"), audit.requestedTools().stream().map(WireToolCall::id).toList());
        assertEquals(expected.stream().map(WireToolExecution::argumentsJson).toList(),
                audit.requestedTools().stream().map(WireToolCall::arguments).toList());
        assertThrows(UnsupportedOperationException.class, () -> audit.requestedTools().clear());
        audit.requireHealthy(); assertFalse(audit.failed()); assertEquals(2, provider.calls.get());
    }

    @Test void promptDriftFailsTypedBeforeProviderIsCalled() throws Exception {
        var provider = new Provider(false); var audit = new F2CommandAudit(PROMPT);
        var relay = connect(provider, audit, request("first", "changed task"));
        assertThrows(F2CommandAudit.Failure.class, relay::serveNext);
        assertEquals(0, provider.calls.get()); assertTrue(audit.failed()); assertNull(audit.terminal());
        assertNull(relay.providerFailureType(), "audit defect must not become provider failure");
    }

    @Test void everyNormalTerminalMustMatchAllProviderToolsInOrder() throws Exception {
        var first = tool(1, "same-id", "read_file", "{\"path\":\"README.md\"}");
        var second = tool(2, "same-id", "execute_command", "{\"command\":\"python3 -I -B diagnose.py\"}");
        List<List<WireToolExecution>> corruptions = List.of(List.of(), List.of(first),
                List.of(tool(1, second.callId(), second.toolName(), second.argumentsJson()),
                        tool(2, first.callId(), first.toolName(), first.argumentsJson())),
                List.of(first, tool(2, "changed-id", second.toolName(), second.argumentsJson())),
                List.of(first, tool(2, second.callId(), "write_file", second.argumentsJson())),
                List.of(first, tool(2, second.callId(), second.toolName(), "{}")));
        for (var events : corruptions) {
            var audit = new F2CommandAudit(PROMPT); var provider = new Provider(false);
            var relay = connect(provider, audit, request("first", PROMPT), request("second", PROMPT),
                    new WorkerComplete(header(FrameType.WORKER_COMPLETE, ""), "done", events));
            relay.serveNext(); relay.serveNext();
            assertThrows(F2CommandAudit.Failure.class, relay::serveNext);
            assertTrue(audit.failed()); assertNull(audit.terminal()); assertNull(relay.providerFailureType());
        }
    }

    @Test void upstreamFailureDoesNotRequireInventedTerminalOrBecomeAuditFailure() throws Exception {
        var provider = new Provider(true); var audit = new F2CommandAudit(PROMPT);
        var relay = connect(provider, audit, request("first", PROMPT),
                new WorkerFailure(header(FrameType.WORKER_FAILURE, ""), "CANDIDATE_WORKER_ERROR", "stopped"));
        assertEquals(BenchmarkProviderRelay.ServeResult.CHAT_SERVED, relay.serveNext());
        assertEquals(BenchmarkProviderRelay.ServeResult.WORKER_FAILURE, relay.serveNext());
        assertNotNull(relay.providerFailureType()); assertNull(audit.terminal());
        assertTrue(audit.requestedTools().isEmpty()); assertFalse(audit.failed()); audit.requireHealthy();
    }

    @Test void zeroCallTerminalIsNotAnAuditDefectAndCommandCountersAreLeftForIndependentReplay() throws Exception {
        var audit = new F2CommandAudit(PROMPT);
        var terminal = new WorkerComplete(header(FrameType.WORKER_COMPLETE, ""), "done", List.of(), List.of(), 1);
        var relay = connect(new Provider(false), audit, terminal);
        assertEquals(BenchmarkProviderRelay.ServeResult.WORKER_COMPLETE, relay.serveNext());
        assertSame(relay.terminalFrame(), audit.terminal()); assertEquals(1, audit.terminal().commandObservationFailures());
        audit.requireHealthy();
    }

    @Test void hostBudgetStopDoesNotTurnMissingTerminalIntoAuditFailure() throws Exception {
        var provider = new Provider(false); var audit = new F2CommandAudit(PROMPT);
        var start = session(provider, ToolProfile.LOCAL_COMMAND, AgentMode.REACT, PROMPT, 1);
        var base = request("blocked", PROMPT);
        var blocked = new ChatRequest(base.header(), base.messages(), List.of(new WireTool("read_file", "read",
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("type", "object"))));
        var relay = connectWithSession(provider, audit, start, request("first", PROMPT), blocked,
                new WorkerFailure(header(FrameType.WORKER_FAILURE, ""), "EPISODE_BUDGET_EXHAUSTED", "stopped"));
        relay.serveNext(); relay.serveNext(); relay.serveNext();
        assertEquals(1, provider.calls.get()); assertTrue(relay.episodeBudgetExhausted());
        assertEquals(1, audit.requestedTools().size()); assertNull(audit.terminal());
        assertFalse(audit.failed()); audit.requireHealthy();
    }

    @Test void hostBudgetClosingResponseMayRequestIgnoredToolsWithoutInventingExecution() throws Exception {
        var provider = new Provider(false); var audit = new F2CommandAudit(PROMPT);
        var executed = List.of(tool(1, "same-id", "read_file", "{\"path\":\"README.md\"}"));
        var relay = connectWithSession(provider, audit,
                session(provider, ToolProfile.LOCAL_COMMAND, AgentMode.REACT, PROMPT, 1),
                request("first", PROMPT), request("budget-finalization", PROMPT),
                new WorkerComplete(header(FrameType.WORKER_COMPLETE, ""), "partial", executed));
        relay.serveNext(); relay.serveNext();
        assertEquals(BenchmarkProviderRelay.ServeResult.WORKER_COMPLETE, relay.serveNext());
        assertEquals(2, provider.calls.get()); assertTrue(relay.episodeBudgetExhausted());
        assertEquals(2, audit.requestedTools().size(), "retain actual ignored closing request");
        assertEquals(1, audit.terminal().toolExecutions().size(), "do not fabricate execution");
        assertTrue(audit.terminal().commandObservations().isEmpty());
        assertSame(relay.terminalFrame(), audit.terminal());
        assertFalse(audit.failed()); audit.requireHealthy();
    }

    @Test void hostBudgetClosingDoesNotExcuseMissingOrChangedEarlierTools() throws Exception {
        for (var executions : List.<List<WireToolExecution>>of(List.of(),
                List.of(tool(1, "same-id", "execute_command", "{\"command\":\"python3 -I -B diagnose.py\"}")))) {
            var provider = new Provider(false); var audit = new F2CommandAudit(PROMPT);
            var relay = connectWithSession(provider, audit,
                    session(provider, ToolProfile.LOCAL_COMMAND, AgentMode.REACT, PROMPT, 1),
                    request("first", PROMPT), request("budget-finalization", PROMPT),
                    new WorkerComplete(header(FrameType.WORKER_COMPLETE, ""), "partial", executions));
            relay.serveNext(); relay.serveNext();
            assertTrue(relay.episodeBudgetExhausted());
            assertThrows(F2CommandAudit.Failure.class, relay::serveNext);
            assertTrue(audit.failed()); assertNull(audit.terminal()); assertNull(relay.providerFailureType());
        }
    }

    @Test void hostBindingIsSingleUseAndRejectsOtherProfilesAndChannelsBeforeHandshake() throws Exception {
        var provider = new Provider(false);
        for (var profile : ToolProfile.values()) {
            if (profile == ToolProfile.LOCAL_COMMAND) continue;
            var audit = new F2CommandAudit(PROMPT);
            assertThrows(F2CommandAudit.Failure.class, () -> BenchmarkProviderRelay.connect(emptyChannel(),
                    session(provider, profile, AgentMode.REACT, PROMPT), provider, null, null, null, audit));
            assertTrue(audit.failed());
        }
        for (var mode : List.of(AgentMode.PLAN, AgentMode.TEAM)) {
            var audit = new F2CommandAudit(PROMPT);
            assertThrows(F2CommandAudit.Failure.class, () -> BenchmarkProviderRelay.connect(emptyChannel(),
                    session(provider, ToolProfile.LOCAL_COMMAND, mode, PROMPT), provider, null, null, null, audit));
        }
        var wrongPrompt = new F2CommandAudit("another source");
        assertThrows(F2CommandAudit.Failure.class, () -> BenchmarkProviderRelay.connect(emptyChannel(),
                session(provider, ToolProfile.LOCAL_COMMAND, AgentMode.REACT, PROMPT), provider, null, null, null, wrongPrompt));
        var shared = new F2CommandAudit(PROMPT);
        assertThrows(F2CommandAudit.Failure.class, () -> BenchmarkProviderRelay.connect(emptyChannel(),
                session(provider, ToolProfile.LOCAL_COMMAND, AgentMode.REACT, PROMPT), provider, message -> message,
                null, null, shared));
        var reused = new F2CommandAudit(PROMPT); connect(provider, reused);
        assertThrows(F2CommandAudit.Failure.class, () -> connect(provider, reused));
    }

    @Test void observerOrderAndBoundsFailuresRemainTyped() throws Exception {
        var audit = new F2CommandAudit(PROMPT); var provider = new Provider(false);
        audit.bind(session(provider, ToolProfile.LOCAL_COMMAND, AgentMode.REACT, PROMPT));
        for (int round = 0; round < MAX_TOOL_EXECUTIONS / MAX_TOOL_CALLS; round++) {
            audit.begin(request("r-" + round, PROMPT));
            audit.response(new WireChatResponse("assistant", "", null,
                    Collections.nCopies(MAX_TOOL_CALLS, new WireToolCall("same-id", "read_file", "{}")),
                    1, 1, 0, "deepseek-v4-flash", true));
            audit.endRequest();
        }
        audit.begin(request("overflow", PROMPT));
        assertThrows(F2CommandAudit.Failure.class, () -> audit.response(new WireChatResponse("assistant", "", null,
                List.of(new WireToolCall("same-id", "read_file", "{}")), 1, 1, 0, "deepseek-v4-flash", true)));
        assertEquals(MAX_TOOL_EXECUTIONS, audit.requestedTools().size()); assertTrue(audit.failed());
        assertThrows(F2CommandAudit.Failure.class, audit::requireHealthy);
    }

    private static BenchmarkProviderRelay connect(Provider provider, F2CommandAudit audit, Frame... frames) throws Exception {
        return connectWithSession(provider, audit, session(provider, ToolProfile.LOCAL_COMMAND, AgentMode.REACT, PROMPT), frames);
    }
    private static BenchmarkProviderRelay connectWithSession(Provider provider, F2CommandAudit audit, SessionStart start, Frame... frames) throws Exception {
        var bytes = new ByteArrayOutputStream();
        var encoded = new BenchmarkFramedChannel(new ByteArrayInputStream(new byte[0]), bytes);
        encoded.write(new WorkerReady(header(FrameType.WORKER_READY, ""), BenchmarkProviderRelay.capabilitiesOf(provider)));
        for (var frame : frames) encoded.write(frame);
        return BenchmarkProviderRelay.connect(new BenchmarkFramedChannel(new ByteArrayInputStream(bytes.toByteArray()), new ByteArrayOutputStream()),
                start, provider, null, null, null, audit);
    }
    private static BenchmarkFramedChannel emptyChannel() { return new BenchmarkFramedChannel(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()); }
    private static Header header(FrameType type, String id) { return new Header(Direction.WORKER_TO_COORDINATOR, type, id, 0); }
    private static ChatRequest request(String id, String prompt) {
        return new ChatRequest(header(FrameType.CHAT_REQUEST, id),
                List.of(new WireMessage("system", "frozen", null, List.of(), null),
                        new WireMessage("user", prompt, null, List.of(), null)), List.of());
    }
    private static WireToolExecution tool(int ordinal, String id, String name, String args) {
        return new WireToolExecution(ordinal, id, name, args, "ok", textSha256("ok"), 2, 1, false, true);
    }
    private static SessionStart session(Provider provider, ToolProfile profile, AgentMode mode, String prompt) {
        return session(provider, profile, mode, prompt, 100_000);
    }
    private static SessionStart session(Provider provider, ToolProfile profile, AgentMode mode, String prompt, int tokenBudget) {
        return new SessionStart(new Header(Direction.COORDINATOR_TO_WORKER, FrameType.SESSION_START, "", 0),
                "f2-host-binding", provider.getProviderName(), provider.getModelName(), mode, profile, prompt,
                "2026-09-05", "UTC", System.currentTimeMillis() + 60_000,
                new AgentLimits(tokenBudget, 32, 8, 1_000_000, 16_384), BenchmarkProviderRelay.capabilitiesOf(provider),
                new Limits(BenchmarkFramedChannel.MAX_FRAME_BYTES, BenchmarkFramedChannel.MAX_SESSION_BYTES, MAX_MESSAGES, MAX_TOOLS),
                profile == ToolProfile.MOCK_MCP_FILE_ONLY ? List.of("support")
                        : profile == ToolProfile.MOCK_MCP ? List.of("benchmark") : List.of(), InteractionMode.SINGLE_TURN);
    }
    private static final class Provider implements LlmClient {
        private final AtomicInteger calls = new AtomicInteger(); private final boolean fail;
        private Provider(boolean fail) { this.fail = fail; }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException { return chat(messages, tools); }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            int call = calls.incrementAndGet(); if (fail) throw new IOException("upstream unavailable");
            String name = call == 1 ? "read_file" : "execute_command";
            String args = call == 1 ? "{\"path\":\"README.md\"}" : "{\"command\":\"python3 -I -B diagnose.py\"}";
            return new ChatResponse("assistant", "", "", List.of(new ToolCall("same-id", new ToolCall.Function(name, args))),
                    20, 5, 0, getModelName(), true);
        }
        @Override public String getProviderName() { return "deepseek"; }
        @Override public String getModelName() { return "deepseek-v4-flash"; }
        @Override public int maxContextWindow() { return 1_000_000; }
    }
}
