package com.paicli.eval.benchmark.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayLlmClientEndToEndTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void handshakesStreamsInFrameOrderAndPreservesToolsUsageModelAcrossSerialCalls() throws Exception {
        FakeClient delegate = new FakeClient(false);
        try (Harness harness = new Harness(2)) {
            Duplex pair = harness.pair;
            ExecutorService pool = harness.pool;
            BenchmarkRelayProtocol.SessionStart start = start(delegate, "frozen private prompt");
            Future<BenchmarkProviderRelay> coordinator = pool.submit(
                    () -> BenchmarkProviderRelay.connect(pair.coordinator(), start, delegate));
            RelayLlmClient worker = RelayLlmClient.accept(pair.worker());
            BenchmarkProviderRelay provider = coordinator.get();

            assertEquals(BenchmarkRelayProtocol.AgentMode.REACT, worker.session().mode());
            assertEquals(BenchmarkRelayProtocol.ToolProfile.FILE_ONLY, worker.session().toolProfile());
            assertEquals("2026-08-31", worker.session().runtimeDate());
            assertEquals("UTC", worker.session().runtimeZone());
            assertEquals(1_000_000, worker.session().agentLimits().contextWindowCapTokens());
            assertEquals(1_000_000, worker.maxContextWindow());
            assertEquals(delegate.promptCacheMode(), worker.promptCacheMode());
            assertFalse(worker.supportsImageInput());

            Future<List<BenchmarkProviderRelay.ServeResult>> serving = pool.submit(() -> List.of(
                    provider.serveNext(), provider.serveNext()));
            List<String> deltas = new ArrayList<>();
            LlmClient.ChatResponse first = worker.chat(messages(), tools(), listener(deltas));
            LlmClient.ChatResponse second = worker.chat(messages(), tools(), LlmClient.StreamListener.NO_OP);

            assertEquals(List.of("reasoning:r1", "content:c1", "reasoning:r2", "content:c2"), deltas);
            assertEquals("answer-1", first.content());
            assertEquals("reasoning-1", first.reasoningContent());
            assertEquals("resolved-model-1", first.resolvedModel());
            assertTrue(first.usagePresent());
            assertEquals(11, first.inputTokens());
            assertEquals(7, first.outputTokens());
            assertEquals(5, first.cachedInputTokens());
            assertEquals("read_file", first.toolCalls().get(0).function().name());
            assertEquals("{\"path\":\"README.md\"}", first.toolCalls().get(0).function().arguments());
            assertEquals("answer-2", second.content());
            assertEquals(2, delegate.calls.get());
            assertEquals(List.of(BenchmarkProviderRelay.ServeResult.CHAT_SERVED,
                    BenchmarkProviderRelay.ServeResult.CHAT_SERVED), serving.get());
        }
    }

    @Test
    void providerIOExceptionBecomesSafeFailureWithoutRelayRetry() throws Exception {
        FakeClient delegate = new FakeClient(true);
        try (Harness harness = new Harness(2)) {
            Duplex pair = harness.pair;
            ExecutorService pool = harness.pool;
            Future<BenchmarkProviderRelay> coordinator = pool.submit(
                    () -> BenchmarkProviderRelay.connect(pair.coordinator(), start(delegate, "prompt"), delegate));
            RelayLlmClient worker = RelayLlmClient.accept(pair.worker());
            BenchmarkProviderRelay provider = coordinator.get();
            Future<BenchmarkProviderRelay.ServeResult> serving = pool.submit(provider::serveNext);

            IOException error = assertThrows(IOException.class,
                    () -> worker.chat(messages(), tools(), LlmClient.StreamListener.NO_OP));
            assertEquals("relay provider failure [LLM_API_ERROR]", error.getMessage());
            assertFalse(error.getMessage().contains("provider-secret"));
            assertEquals(BenchmarkProviderRelay.ServeResult.CHAT_SERVED, serving.get());
            assertEquals(1, delegate.calls.get());
            assertEquals("LLM_API_ERROR", provider.providerFailureType());
        }
    }

    @Test
    void rejectsMalformedOrOversizedWorkerDataBeforeCallingProvider() throws Exception {
        FakeClient delegate = new FakeClient(false);
        try (Harness harness = new Harness(1)) {
            Duplex pair = harness.pair;
            ExecutorService pool = harness.pool;
            Future<BenchmarkProviderRelay> coordinator = pool.submit(
                    () -> BenchmarkProviderRelay.connect(pair.coordinator(), start(delegate, "prompt"), delegate));
            RelayLlmClient worker = RelayLlmClient.accept(pair.worker());
            coordinator.get();

            LlmClient.Tool malformed = new LlmClient.Tool("bad", "bad", null);
            assertThrows(IOException.class, () -> worker.chat(messages(), List.of(malformed)));
            assertThrows(IOException.class, () -> worker.chat(
                    List.of(LlmClient.Message.user("x".repeat(BenchmarkRelayProtocol.MAX_TEXT_CHARS + 1))),
                    List.of()));
            assertEquals(0, delegate.calls.get());
        }
    }

    @Test
    void terminalCompleteCarriesOrderedToolExecutionEvidenceToCoordinator() throws Exception {
        FakeClient delegate = new FakeClient(false);
        try (Harness harness = new Harness(2)) {
            Duplex pair = harness.pair;
            ExecutorService pool = harness.pool;
            Future<BenchmarkProviderRelay> coordinator = pool.submit(
                    () -> BenchmarkProviderRelay.connect(
                            pair.coordinator(), start(delegate, "prompt"), delegate));
            RelayLlmClient worker = RelayLlmClient.accept(pair.worker());
            BenchmarkProviderRelay provider = coordinator.get();
            Future<BenchmarkProviderRelay.ServeResult> terminal = pool.submit(provider::serveNext);
            List<BenchmarkRelayProtocol.WireToolExecution> toolExecutions = List.of(
                    toolExecution(1, "call-1", "read_file", "contents", true),
                    toolExecution(2, "call-2", "grep_code", "not found", false));

            worker.complete("final answer", toolExecutions);

            assertEquals(BenchmarkProviderRelay.ServeResult.WORKER_COMPLETE, terminal.get());
            BenchmarkRelayProtocol.WorkerComplete complete = assertInstanceOf(
                    BenchmarkRelayProtocol.WorkerComplete.class, provider.terminalFrame());
            assertEquals("final answer", complete.answer());
            assertEquals(toolExecutions, complete.toolExecutions());
            assertEquals(List.of(1, 2),
                    complete.toolExecutions().stream().map(event -> event.ordinal()).toList());
            assertEquals(List.of("read_file", "grep_code"),
                    complete.toolExecutions().stream().map(event -> event.toolName()).toList());
            assertEquals(0, delegate.calls.get());
        }
    }

    @Test
    void terminalCompleteCarriesProcessLocalCommandObservationsAndFailures() throws Exception {
        FakeClient delegate = new FakeClient(false);
        try (Harness harness = new Harness(2)) {
            Future<BenchmarkProviderRelay> coordinator = harness.pool.submit(() -> BenchmarkProviderRelay.connect(
                    harness.pair.coordinator(), start(delegate, "prompt", BenchmarkRelayProtocol.ToolProfile.LOCAL_COMMAND), delegate));
            RelayLlmClient worker = RelayLlmClient.accept(harness.pair.worker());
            BenchmarkProviderRelay provider = coordinator.get();
            Future<BenchmarkProviderRelay.ServeResult> terminal = harness.pool.submit(provider::serveNext);
            var observation = new com.paicli.tool.CommandExecutionObserver.Event(1,
                    com.paicli.tool.CommandExecutionObserver.Phase.REJECTED, "sudo true", "/workspace", List.of(),
                    0, 1_800_000_000_000L, null, com.paicli.tool.CommandExecutionObserver.Outcome.POLICY_DENIED, "", 0);
            worker.complete("done", List.of(), List.of(observation), 3);
            assertEquals(BenchmarkProviderRelay.ServeResult.WORKER_COMPLETE, terminal.get());
            var complete = assertInstanceOf(BenchmarkRelayProtocol.WorkerComplete.class, provider.terminalFrame());
            assertEquals(List.of(observation), complete.commandObservations());
            assertEquals(3, complete.commandObservationFailures());
            assertEquals(0, delegate.calls.get());
        }
    }

    @Test
    void terminalCompleteRejectsOversizedToolExecutionEvidenceBeforeWriting() throws Exception {
        FakeClient delegate = new FakeClient(false);
        try (Harness harness = new Harness(1)) {
            Duplex pair = harness.pair;
            ExecutorService pool = harness.pool;
            Future<BenchmarkProviderRelay> coordinator = pool.submit(
                    () -> BenchmarkProviderRelay.connect(
                            pair.coordinator(), start(delegate, "prompt"), delegate));
            RelayLlmClient worker = RelayLlmClient.accept(pair.worker());
            coordinator.get();
            BenchmarkRelayProtocol.WireToolExecution event =
                    toolExecution(1, "call-1", "read_file", "ok", true);

            assertThrows(IllegalArgumentException.class, () -> worker.complete(
                    "final answer",
                    java.util.Collections.nCopies(
                            BenchmarkRelayProtocol.MAX_TOOL_EXECUTIONS + 1, event)));
        }
    }

    private static LlmClient.StreamListener listener(List<String> deltas) {
        return new LlmClient.StreamListener() {
            @Override
            public void onReasoningDelta(String delta) {
                deltas.add("reasoning:" + delta);
            }

            @Override
            public void onContentDelta(String delta) {
                deltas.add("content:" + delta);
            }
        };
    }

    private static List<LlmClient.Message> messages() {
        return List.of(
                LlmClient.Message.system("system"),
                LlmClient.Message.user(List.of(
                        LlmClient.ContentPart.text("question"),
                        LlmClient.ContentPart.imageUrl("https://example.invalid/image.png"))));
    }

    private static List<LlmClient.Tool> tools() {
        return List.of(new LlmClient.Tool(
                "read_file", "read a file", JSON.createObjectNode().put("type", "object")));
    }

    private static BenchmarkRelayProtocol.SessionStart start(LlmClient delegate, String prompt) {
        return start(delegate, prompt, BenchmarkRelayProtocol.ToolProfile.FILE_ONLY);
    }

    private static BenchmarkRelayProtocol.SessionStart start(LlmClient delegate, String prompt,
            BenchmarkRelayProtocol.ToolProfile profile) {
        return new BenchmarkRelayProtocol.SessionStart(
                new BenchmarkRelayProtocol.Header(
                        BenchmarkRelayProtocol.Direction.COORDINATOR_TO_WORKER,
                        BenchmarkRelayProtocol.FrameType.SESSION_START, "", 0),
                "session-e2e", delegate.getProviderName(), delegate.getModelName(),
                BenchmarkRelayProtocol.AgentMode.REACT, profile,
                prompt, "2026-08-31", "UTC", System.currentTimeMillis() + 120_000,
                new BenchmarkRelayProtocol.AgentLimits(
                        100_000, 100, 3, 1_000_000, 16_384),
                BenchmarkProviderRelay.capabilitiesOf(delegate),
                new BenchmarkRelayProtocol.Limits(
                        BenchmarkFramedChannel.MAX_FRAME_BYTES,
                        BenchmarkFramedChannel.MAX_SESSION_BYTES,
                        BenchmarkRelayProtocol.MAX_MESSAGES,
                        BenchmarkRelayProtocol.MAX_TOOLS));
    }

    private static BenchmarkRelayProtocol.WireToolExecution toolExecution(
            int ordinal, String callId, String toolName,
            String resultPreview, boolean successful) {
        return new BenchmarkRelayProtocol.WireToolExecution(
                ordinal,
                callId,
                toolName,
                "{\"input\":\"value\"}",
                resultPreview,
                "0".repeat(64),
                resultPreview.length(),
                3,
                false,
                successful);
    }

    private static final class FakeClient implements LlmClient {
        private final AtomicInteger calls = new AtomicInteger();
        private final boolean fail;

        private FakeClient(boolean fail) {
            this.fail = fail;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                 StreamListener listener) throws IOException {
            int call = calls.incrementAndGet();
            assertEquals(2, messages.size());
            assertTrue(messages.get(1).hasImageContent());
            assertEquals("read_file", tools.get(0).name());
            if (fail) {
                throw new IOException("provider-secret /Users/private/key");
            }
            listener.onReasoningDelta("r1");
            listener.onContentDelta("c1");
            listener.onReasoningDelta("r2");
            listener.onContentDelta("c2");
            return new ChatResponse(
                    "assistant", "answer-" + call, "reasoning-" + call,
                    List.of(new ToolCall("tool-" + call,
                            new ToolCall.Function("read_file", "{\"path\":\"README.md\"}"))),
                    10 + call, 6 + call, 5, "resolved-model-" + call, true);
        }

        @Override
        public String getModelName() {
            return "deepseek-v4-flash";
        }

        @Override
        public String getProviderName() {
            return "deepseek";
        }

        @Override
        public int maxContextWindow() {
            return 1_000_000;
        }

        @Override
        public boolean supportsPromptCaching() {
            return true;
        }

        @Override
        public boolean supportsImageInput() {
            return false;
        }

        @Override
        public String promptCacheMode() {
            return "automatic-prefix-cache";
        }
    }

    private static final class Duplex implements AutoCloseable {
        private final PipedInputStream workerInput = new PipedInputStream(1 << 20);
        private final PipedInputStream coordinatorInput = new PipedInputStream(1 << 20);
        private final PipedOutputStream coordinatorOutput;
        private final PipedOutputStream workerOutput;
        private final BenchmarkFramedChannel coordinator;
        private final BenchmarkFramedChannel worker;

        private Duplex() throws IOException {
            coordinatorOutput = new PipedOutputStream(workerInput);
            workerOutput = new PipedOutputStream(coordinatorInput);
            coordinator = new BenchmarkFramedChannel(coordinatorInput, coordinatorOutput);
            worker = new BenchmarkFramedChannel(workerInput, workerOutput);
        }

        private BenchmarkFramedChannel coordinator() {
            return coordinator;
        }

        private BenchmarkFramedChannel worker() {
            return worker;
        }

        @Override
        public void close() throws IOException {
            coordinatorOutput.close();
            workerOutput.close();
            coordinatorInput.close();
            workerInput.close();
        }
    }

    private static final class Harness implements AutoCloseable {
        private final Duplex pair;
        private final ExecutorService pool;

        private Harness(int threads) throws IOException {
            pair = new Duplex();
            pool = Executors.newFixedThreadPool(threads);
        }

        @Override
        public void close() throws IOException {
            pool.shutdownNow();
            pair.close();
        }
    }
}
