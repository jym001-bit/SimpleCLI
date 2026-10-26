package com.paicli.eval.benchmark;

import com.paicli.eval.benchmark.mock.D3ApprovalCalendarMock;
import com.paicli.eval.benchmark.relay.*;
import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.paicli.eval.benchmark.D3ScriptedApprovalTest.Control;
import static org.junit.jupiter.api.Assertions.*;

/** Actual trusted Worker, Agent, native HITL and MCP over framed pipes. No provider API or Docker. */
@Timeout(30)
class D3McpRelayTest {
    @TempDir Path temp;
    private String previousAudit;

    @BeforeEach void isolateAudit() { previousAudit = System.getProperty("paicli.audit.dir"); System.setProperty("paicli.audit.dir", temp.resolve("audit").toString()); }
    @AfterEach void restoreAudit() { if (previousAudit == null) System.clearProperty("paicli.audit.dir"); else System.setProperty("paicli.audit.dir", previousAudit); }

    @Test void twoNativeWorkerTurnsKeepHistoryAndOnlyTheHostCanGrantExactWrite() throws Exception {
        for (Control control : List.of(Control.CORRECT, Control.REORDER_ATTENDEES)) {
            var mock = mock(17);
            var client = new HistoryCheckingClient(control);
            var complete = assertInstanceOf(BenchmarkRelayProtocol.WorkerComplete.class, run(mock, client, 100_000, 12));
            assertTrue(mock.satisfies(complete.answer()), complete.answer());
            assertEquals(2, complete.toolExecutions().size());
            assertEquals(4, client.calls);
            assertTrue(client.sawRetainedHistory);
            assertEquals(1, mock.writes());
            var events = mock.audit();
            int approved = index(events, "USER", "APPROVED");
            assertTrue(index(events, "MCP", "AVAILABILITY_READ") < approved);
            assertTrue(approved < index(events, "HITL", "ALLOW"));
            assertTrue(index(events, "HITL", "ALLOW") < index(events, "MCP", "CREATED"));
        }
    }

    @Test void negativeControlsStayNegativeAcrossTheWorkerAndHostBoundary() throws Exception {
        for (Control control : List.of(Control.NO_AVAILABILITY, Control.UNAVAILABLE_SLOT, Control.LATE_SLOT,
                Control.EXPANDED_ATTENDEES, Control.MISSING_KEY, Control.PREMATURE_WRITE, Control.SELF_APPROVE_TOOL,
                Control.FORGED_APPROVAL_STATUS, Control.CHANGE_KEY, Control.CHANGE_TIME, Control.CANCEL_AFTER_CREATE,
                Control.REPEAT_SAME_KEY, Control.SECOND_KEY, Control.FENCED_FINAL)) {
            var mock = mock(18);
            var result = assertInstanceOf(BenchmarkRelayProtocol.WorkerComplete.class,
                    run(mock, new HistoryCheckingClient(control), 100_000, 12));
            assertFalse(mock.satisfies(result.answer()), control.name());
            int expectedWrites = List.of(Control.CANCEL_AFTER_CREATE, Control.REPEAT_SAME_KEY, Control.SECOND_KEY, Control.FENCED_FINAL).contains(control) ? 1 : 0;
            assertEquals(expectedWrites, mock.writes(), control.name());
            if (control == Control.SELF_APPROVE_TOOL) {
                assertTrue(mock.audit().stream().anyMatch(e -> e.source().equals("POLICY")));
                assertTrue(mock.audit().stream().anyMatch(e -> e.source().equals("USER") && e.outcome().equals("REJECTED")));
                assertTrue(result.toolExecutions().stream().anyMatch(e -> e.resultPreview().contains("TOOL_NOT_ADVERTISED")));
            }
        }
    }

    @Test void tokenAndIterationBudgetsAreNotResetAtSecondUserTurn() throws Exception {
        for (boolean tokenLimit : List.of(true, false)) {
            var mock = mock(19); var client = new HistoryCheckingClient(Control.CORRECT);
            var failed = assertInstanceOf(BenchmarkRelayProtocol.WorkerFailure.class,
                    run(mock, client, tokenLimit ? 260 : 100_000, tokenLimit ? 12 : 2));
            assertEquals("EPISODE_BUDGET_EXHAUSTED", failed.errorType());
            assertEquals(2, client.calls);
            assertEquals(0, mock.writes());
            assertTrue(mock.audit().stream().noneMatch(e -> e.source().equals("USER")));
        }
        var mock = mock(20); var client = new HistoryCheckingClient(Control.CORRECT);
        var complete = assertInstanceOf(BenchmarkRelayProtocol.WorkerComplete.class, run(mock, client, 390, 12));
        assertEquals(4, client.calls, "three normal calls plus exactly one tool-free closing call for the episode");
        assertEquals(1, client.closingCalls);
        assertEquals(1, mock.writes());
        assertFalse(mock.satisfies(complete.answer()), "partial closing response is not a completed D3 task");
    }

    private BenchmarkRelayProtocol.Frame run(D3ApprovalCalendarMock mock, LlmClient client, int tokens, int calls) throws Exception {
        return runWorker(mock, client, tokens, calls, temp);
    }

    static BenchmarkRelayProtocol.Frame runWorker(D3ApprovalCalendarMock mock, LlmClient client, int tokens, int calls, Path workspace) throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        try (var workerIn = new PipedInputStream(1 << 20); var hostIn = new PipedInputStream(1 << 20);
             var hostOut = new PipedOutputStream(workerIn); var workerOut = new PipedOutputStream(hostIn)) {
            var future = pool.submit(() -> { BenchmarkRelayWorkerMain.run(workerIn, workerOut, workspace); return null; });
            var relay = BenchmarkProviderRelay.connect(new BenchmarkFramedChannel(hostIn, hostOut), start(client, mock, tokens, calls), client, mock);
            BenchmarkProviderRelay.ServeResult result;
            do { result = relay.serveNext(); }
            while (result != BenchmarkProviderRelay.ServeResult.WORKER_COMPLETE && result != BenchmarkProviderRelay.ServeResult.WORKER_FAILURE);
            future.get(10, TimeUnit.SECONDS);
            return relay.terminalFrame();
        } finally { pool.shutdownNow(); }
    }

    static D3ApprovalCalendarMock mock(int seed) {
        byte[] entropy = new byte[32]; Arrays.fill(entropy, (byte) seed);
        return new D3ApprovalCalendarMock(D3ApprovalCalendarMock.fromEntropy(entropy));
    }

    static BenchmarkRelayProtocol.SessionStart start(LlmClient client, D3ApprovalCalendarMock mock, int tokens, int calls) {
        return new BenchmarkRelayProtocol.SessionStart(new BenchmarkRelayProtocol.Header(BenchmarkRelayProtocol.Direction.COORDINATOR_TO_WORKER,
                BenchmarkRelayProtocol.FrameType.SESSION_START, "", 0), "d3-control", client.getProviderName(), client.getModelName(),
                BenchmarkRelayProtocol.AgentMode.REACT, BenchmarkRelayProtocol.ToolProfile.MOCK_MCP, mock.prompt(), "2026-09-04", "UTC",
                System.currentTimeMillis() + 60_000, new BenchmarkRelayProtocol.AgentLimits(tokens, calls, 2, 1_000_000, 16_384),
                BenchmarkProviderRelay.capabilitiesOf(client), new BenchmarkRelayProtocol.Limits(BenchmarkFramedChannel.MAX_FRAME_BYTES,
                BenchmarkFramedChannel.MAX_SESSION_BYTES, 512, 128), mock.serverNames(), BenchmarkRelayProtocol.InteractionMode.TWO_TURN_APPROVAL);
    }

    private static int index(List<D3ApprovalCalendarMock.AuditEvent> audit, String source, String outcome) {
        return audit.stream().filter(e -> e.source().equals(source) && e.outcome().equals(outcome)).findFirst().orElseThrow().sequence();
    }

    static final class HistoryCheckingClient implements LlmClient {
        private final D3ScriptedApprovalTest.ScriptedClient delegate;
        int calls, closingCalls;
        boolean sawRetainedHistory;
        HistoryCheckingClient(Control control) { delegate = new D3ScriptedApprovalTest.ScriptedClient(control); }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) { return chat(messages, tools); }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            calls++;
            if (tools.isEmpty()) {
                closingCalls++;
                return new ChatResponse("assistant", "{}", "", List.of(), 100, 30, 0, getModelName(), true);
            }
            if (messages.stream().filter(m -> m.role().equals("user")).count() == 2) {
                assertTrue(messages.stream().anyMatch(m -> m.role().equals("assistant") && m.content().contains("\"event\"")));
                sawRetainedHistory = true;
            }
            return delegate.chat(messages, tools);
        }
        @Override public String getProviderName() { return delegate.getProviderName(); }
        @Override public String getModelName() { return delegate.getModelName(); }
        @Override public int maxContextWindow() { return delegate.maxContextWindow(); }
    }
}
