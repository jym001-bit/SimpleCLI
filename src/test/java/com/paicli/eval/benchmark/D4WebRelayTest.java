package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.eval.benchmark.mock.D4WebMock;
import com.paicli.eval.benchmark.relay.*;
import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Native Worker + shared framed relay + closed host backend. No real model/API or formal scoring. */
@Timeout(30)
class D4WebRelayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;

    @ParameterizedTest @EnumSource(D4NativeWebTest.Control.class)
    void allNativeControlsRetainTheirMeaningAcrossTheRelay(D4NativeWebTest.Control control) throws Exception {
        var mock = new D4WebMock(D4NativeWebTest.entropy(42));
        var client = new D4NativeWebTest.ScriptedClient(mock.definition(), control);
        var complete = run(mock, client);
        boolean exact;
        try { exact = D4NativeWebTest.reference(mock.definition()).equals(JSON.readTree(complete.answer())); }
        catch (Exception malformed) { exact = false; }
        boolean passed = exact && complete.toolExecutions().stream().allMatch(BenchmarkRelayProtocol.WireToolExecution::successful)
                && mock.audit().stream().filter(e -> e.operation().equals("FETCH")).count() == 2;
        assertEquals(control == D4NativeWebTest.Control.CORRECT || control == D4NativeWebTest.Control.REVERSED_FETCH, passed);
        assertEquals(control == D4NativeWebTest.Control.NO_SEARCH ? 0 : 5, mock.relayAudit().size());
        var d = mock.definition();
        for (var exchange : mock.relayAudit()) {
            var tool = complete.toolExecutions().get(exchange.toolOrdinal() - 1);
            assertEquals(exchange.request().callId(), exchange.response().callId());
            assertEquals(exchange.request().operation(), exchange.response().operation());
            assertEquals(exchange.request().operation() == BenchmarkRelayProtocol.WebOperation.SEARCH ? "web_search" : "web_fetch", tool.toolName());
            if (exchange.request().operation() != BenchmarkRelayProtocol.WebOperation.SEARCH)
                assertTrue(Set.of(d.releaseUrl(), d.migrationUrl()).contains(exchange.request().input()));
        }
        assertTrue(mock.relayAudit().stream().map(e -> e.request().callId()).distinct().count() == mock.relayAudit().size());
    }

    @Test void bindingMustMatchTheFrozenProfileBeforeHandshake() {
        var mock = new D4WebMock(D4NativeWebTest.entropy(1));
        var client = new D4NativeWebTest.ScriptedClient(mock.definition(), D4NativeWebTest.Control.CORRECT);
        var out = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> BenchmarkProviderRelay.connect(
                new BenchmarkFramedChannel(new ByteArrayInputStream(new byte[0]), out), start(client, mock.prompt()), client));
        assertEquals(0, out.size()); assertTrue(mock.audit().isEmpty());
    }

    BenchmarkRelayProtocol.WorkerComplete run(D4WebMock mock, LlmClient client) throws Exception {
        return assertInstanceOf(BenchmarkRelayProtocol.WorkerComplete.class, runFrame(mock, client, temp, start(client, mock.prompt())));
    }

    static BenchmarkRelayProtocol.Frame runFrame(D4WebMock mock, LlmClient client, Path workspace,
                                                 BenchmarkRelayProtocol.SessionStart start) throws Exception {
        return runResult(mock, client, workspace, start).terminal();
    }
    record RunResult(BenchmarkRelayProtocol.Frame terminal, boolean budgetExhausted) { }
    static RunResult runResult(D4WebMock mock, LlmClient client, Path workspace,
                               BenchmarkRelayProtocol.SessionStart start) throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        try (var workerIn = new PipedInputStream(1 << 20); var hostIn = new PipedInputStream(1 << 20);
             var hostOut = new PipedOutputStream(workerIn); var workerOut = new PipedOutputStream(hostIn)) {
            var worker = pool.submit(() -> { BenchmarkRelayWorkerMain.run(workerIn, workerOut, workspace); return null; });
            var relay = BenchmarkProviderRelay.connect(new BenchmarkFramedChannel(hostIn, hostOut), start, client, null, mock);
            BenchmarkProviderRelay.ServeResult result;
            do { result = relay.serveNext(); }
            while (result == BenchmarkProviderRelay.ServeResult.CHAT_SERVED || result == BenchmarkProviderRelay.ServeResult.WEB_SERVED);
            worker.get(10, TimeUnit.SECONDS);
            assertTrue(result == BenchmarkProviderRelay.ServeResult.WORKER_COMPLETE || result == BenchmarkProviderRelay.ServeResult.WORKER_FAILURE);
            return new RunResult(relay.terminalFrame(), relay.episodeBudgetExhausted());
        } finally { pool.shutdownNow(); }
    }

    static BenchmarkRelayProtocol.SessionStart start(LlmClient client, String prompt) {
        return new BenchmarkRelayProtocol.SessionStart(new BenchmarkRelayProtocol.Header(BenchmarkRelayProtocol.Direction.COORDINATOR_TO_WORKER,
                BenchmarkRelayProtocol.FrameType.SESSION_START, "", 0), "d4-control", client.getProviderName(), client.getModelName(),
                BenchmarkRelayProtocol.AgentMode.REACT, BenchmarkRelayProtocol.ToolProfile.MOCK_WEB, prompt, "2026-09-04", "UTC",
                System.currentTimeMillis() + 60_000, new BenchmarkRelayProtocol.AgentLimits(100_000, 32, 8, 1_000_000, 16_384),
                BenchmarkProviderRelay.capabilitiesOf(client), new BenchmarkRelayProtocol.Limits(
                        BenchmarkFramedChannel.MAX_FRAME_BYTES, BenchmarkFramedChannel.MAX_SESSION_BYTES, 512, 128), List.of());
    }
}
