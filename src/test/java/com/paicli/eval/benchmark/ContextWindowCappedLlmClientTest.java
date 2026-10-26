package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.HunyuanClient;
import com.paicli.llm.LlmClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextWindowCappedLlmClientTest {
    @Test
    void allowsNormalRequestsThroughBothChatOverloadsAndPreservesCapabilities() throws Exception {
        RecordingClient delegate = new RecordingClient(1_000_000, response(23));
        ContextWindowCappedLlmClient capped = ContextWindowCappedLlmClient.cap(
                delegate, 321_000, 16_384);

        LlmClient.ChatResponse direct = capped.chat(
                List.of(LlmClient.Message.user("hello")), List.of());
        List<String> streamed = new ArrayList<>();
        LlmClient.ChatResponse streaming = capped.chat(
                List.of(LlmClient.Message.user("hello again")),
                List.of(),
                new LlmClient.StreamListener() {
                    @Override
                    public void onReasoningDelta(String delta) {
                        streamed.add("reasoning:" + delta);
                    }

                    @Override
                    public void onContentDelta(String delta) {
                        streamed.add("content:" + delta);
                    }
                });

        assertEquals(2, delegate.calls());
        assertEquals("ok", direct.content());
        assertEquals("ok", streaming.content());
        assertEquals(List.of("reasoning:thought", "content:ok"), streamed);
        assertEquals(321_000, capped.maxContextWindow());
        assertEquals(16_384, capped.maxOutputTokensPerCall());
        assertEquals(delegate.getModelName(), capped.getModelName());
        assertEquals(delegate.getProviderName(), capped.getProviderName());
        assertEquals(delegate.supportsPromptCaching(), capped.supportsPromptCaching());
        assertEquals(delegate.supportsTools(), capped.supportsTools());
        assertEquals(delegate.supportsImageInput(), capped.supportsImageInput());
        assertEquals(delegate.promptCacheMode(), capped.promptCacheMode());
    }

    @Test
    void rejectsOversizedUserBeforeCallingProvider() {
        RecordingClient delegate = new RecordingClient(128_000, response(10));
        LlmClient capped = ContextWindowCappedLlmClient.cap(delegate, 8_000, 2_000);

        ContextWindowCappedLlmClient.ContextWindowCapExceededException error = assertThrows(
                ContextWindowCappedLlmClient.ContextWindowCapExceededException.class,
                () -> capped.chat(
                        List.of(LlmClient.Message.user("x".repeat(5_000))), List.of()));

        assertEquals(
                ContextWindowCappedLlmClient.ContextWindowCapExceededException.Kind
                        .REQUEST_ESTIMATE_EXCEEDED,
                error.kind());
        assertTrue(error.estimatedInputBudgetUnits() > 0);
        assertEquals(-1, error.observedInputTokens());
        assertEquals(0, delegate.calls());
    }

    @Test
    void countsReasoningAndToolCallArgumentsBeforeCallingProvider() {
        RecordingClient delegate = new RecordingClient(128_000, response(10));
        LlmClient capped = ContextWindowCappedLlmClient.cap(delegate, 8_000, 2_000);
        LlmClient.ToolCall call = new LlmClient.ToolCall(
                "call-1",
                new LlmClient.ToolCall.Function("read_file", "a".repeat(1_000)));
        LlmClient.Message message = LlmClient.Message.assistant(
                "r".repeat(4_000), "", List.of(call));

        assertThrows(
                ContextWindowCappedLlmClient.ContextWindowCapExceededException.class,
                () -> capped.chat(List.of(message), List.of()));

        assertEquals(0, delegate.calls());
    }

    @Test
    void countsToolNameDescriptionAndJsonSchemaBeforeCallingProvider() {
        RecordingClient delegate = new RecordingClient(128_000, response(10));
        LlmClient capped = ContextWindowCappedLlmClient.cap(delegate, 8_000, 2_000);
        var schema = new ObjectMapper().createObjectNode();
        schema.put("type", "object");
        schema.put("description", "s".repeat(5_000));
        LlmClient.Tool tool = new LlmClient.Tool("search_code", "search", schema);

        assertThrows(
                ContextWindowCappedLlmClient.ContextWindowCapExceededException.class,
                () -> capped.chat(List.of(LlmClient.Message.user("find it")), List.of(tool)));

        assertEquals(0, delegate.calls());
    }

    @Test
    void countsJsonEscapesAndFixedProviderOverheadBeforeCallingProvider() {
        RecordingClient delegate = new RecordingClient(128_000, response(10));
        LlmClient capped = ContextWindowCappedLlmClient.cap(delegate, 8_000, 2_000);
        String adversarial = ("\u0000\\\"").repeat(520);

        ContextWindowCappedLlmClient.ContextWindowCapExceededException error = assertThrows(
                ContextWindowCappedLlmClient.ContextWindowCapExceededException.class,
                () -> capped.chat(List.of(LlmClient.Message.user(adversarial)), List.of()));

        assertEquals(
                ContextWindowCappedLlmClient.ContextWindowCapExceededException.Kind
                        .REQUEST_ESTIMATE_EXCEEDED,
                error.kind());
        assertTrue(error.estimatedInputBudgetUnits() + error.maxOutputTokensPerCall() > 8_000);
        assertEquals(0, delegate.calls());
    }

    @Test
    void byteBudgetEstimateUpperBoundsAProviderNeutralMaximumShape() throws Exception {
        List<LlmClient.Message> messages = new ArrayList<>();
        for (int messageIndex = 0; messageIndex < 64; messageIndex++) {
            List<LlmClient.ToolCall> calls = new ArrayList<>();
            for (int callIndex = 0; callIndex < 4; callIndex++) {
                calls.add(new LlmClient.ToolCall(
                        "call-" + messageIndex + "-" + callIndex,
                        new LlmClient.ToolCall.Function(
                                "tool_" + callIndex,
                                "{\"escaped\":\"\\u0000\\\\\\\"\",\"payload\":\""
                                        + "x".repeat(256) + "\"}")));
            }
            messages.add(new LlmClient.Message(
                    "assistant",
                    "fallback-" + messageIndex,
                    "reasoning-" + "推理".repeat(64),
                    calls,
                    "tool-result-" + messageIndex,
                    List.of(
                            LlmClient.ContentPart.text("text-" + "内容".repeat(64)),
                            LlmClient.ContentPart.imageBase64(
                                    "YWJjZA==".repeat(32), "image/png"),
                            LlmClient.ContentPart.imageUrl(
                                    "https://example.invalid/image/" + messageIndex))));
        }
        List<LlmClient.Tool> tools = new ArrayList<>();
        for (int index = 0; index < 64; index++) {
            var schema = new ObjectMapper().createObjectNode();
            schema.put("type", "object");
            schema.putObject("properties")
                    .putObject("value")
                    .put("type", "string")
                    .put("description", "schema-" + "s".repeat(256));
            tools.add(new LlmClient.Tool(
                    "tool_" + index, "description-" + "d".repeat(128), schema));
        }

        long estimate = ContextWindowCappedLlmClient.estimateInputBudgetUnits(messages, tools);
        long exactProviderNeutralBytes = providerNeutralRequestBytes(messages, tools);

        assertTrue(estimate >= exactProviderNeutralBytes,
                () -> "byte-budget estimate " + estimate
                        + " must cover serialized request " + exactProviderNeutralBytes);
    }

    @Test
    void rejectsProviderUsageAboveEffectiveCapForNonStreamingCall() {
        RecordingClient delegate = new RecordingClient(8_000, response(8_001, 1, true));
        LlmClient capped = ContextWindowCappedLlmClient.cap(delegate, 20_000, 1_000);

        ContextWindowCappedLlmClient.ContextWindowCapExceededException error = assertThrows(
                ContextWindowCappedLlmClient.ContextWindowCapExceededException.class,
                () -> capped.chat(List.of(LlmClient.Message.user("hello")), List.of()));

        assertEquals(
                ContextWindowCappedLlmClient.ContextWindowCapExceededException.Kind
                        .PROVIDER_CONTEXT_EXCEEDED,
                error.kind());
        assertEquals(8_001, error.observedInputTokens());
        assertEquals(8_002, error.observedTotalTokens());
        assertEquals(1, delegate.calls());
    }

    @Test
    void requiresCompleteNonNegativeProviderUsageWithinBothCaps() {
        assertResponseRejected(
                null, 8_000, 1_000,
                ContextWindowCappedLlmClient.ContextWindowCapExceededException.Kind
                        .PROVIDER_USAGE_INVALID);
        assertResponseRejected(
                response(1, 1, false), 8_000, 1_000,
                ContextWindowCappedLlmClient.ContextWindowCapExceededException.Kind
                        .PROVIDER_USAGE_MISSING);
        assertResponseRejected(
                response(-1, 1, true), 8_000, 1_000,
                ContextWindowCappedLlmClient.ContextWindowCapExceededException.Kind
                        .PROVIDER_USAGE_INVALID);
        assertResponseRejected(
                response(1, -1, true), 8_000, 1_000,
                ContextWindowCappedLlmClient.ContextWindowCapExceededException.Kind
                        .PROVIDER_USAGE_INVALID);
        assertResponseRejected(
                response(1_000, 1_001, true), 8_000, 1_000,
                ContextWindowCappedLlmClient.ContextWindowCapExceededException.Kind
                        .PROVIDER_OUTPUT_EXCEEDED);
        assertResponseRejected(
                response(7_500, 501, true), 8_000, 1_000,
                ContextWindowCappedLlmClient.ContextWindowCapExceededException.Kind
                        .PROVIDER_CONTEXT_EXCEEDED);
    }

    @Test
    void doesNotReplayStreamingDeltasWhenProviderUsageExceedsCap() {
        RecordingClient delegate = new RecordingClient(8_000, response(8_001, 1, true));
        LlmClient capped = ContextWindowCappedLlmClient.cap(delegate, 20_000, 1_000);
        List<String> exposed = new ArrayList<>();

        assertThrows(
                ContextWindowCappedLlmClient.ContextWindowCapExceededException.class,
                () -> capped.chat(
                        List.of(LlmClient.Message.user("hello")),
                        List.of(),
                        new LlmClient.StreamListener() {
                            @Override
                            public void onReasoningDelta(String delta) {
                                exposed.add(delta);
                            }

                            @Override
                            public void onContentDelta(String delta) {
                                exposed.add(delta);
                            }
                        }));

        assertEquals(1, delegate.calls());
        assertEquals(List.of(), exposed);
    }

    @Test
    void malformedRealSseUsageFailsClosedBeforeAnyListenerOutput() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("""
                            data: {"model":"hy4-preview","choices":[{"delta":{"role":"assistant","content":"must stay buffered"},"finish_reason":"stop"}],"usage":{}}

                            data: [DONE]

                            """));
            HunyuanClient provider = new HunyuanClient(
                    "test-key", "hy4-preview", server.url("/v1").toString(), 16_384);
            LlmClient capped = ContextWindowCappedLlmClient.cap(
                    provider, 1_000_000, 16_384);
            List<String> exposed = new ArrayList<>();

            ContextWindowCappedLlmClient.ContextWindowCapExceededException error = assertThrows(
                    ContextWindowCappedLlmClient.ContextWindowCapExceededException.class,
                    () -> capped.chat(
                            List.of(LlmClient.Message.user("hello")),
                            List.of(),
                            new LlmClient.StreamListener() {
                                @Override
                                public void onContentDelta(String delta) {
                                    exposed.add(delta);
                                }
                            }));

            assertEquals(
                    ContextWindowCappedLlmClient.ContextWindowCapExceededException.Kind
                            .PROVIDER_USAGE_MISSING,
                    error.kind());
            assertEquals(List.of(), exposed);
        }
    }

    @Test
    void usesRetrySafeDelegateOverloadAndReplaysOnlyFinalResponse() throws Exception {
        RetrySafeDelegate delegate = new RetrySafeDelegate();
        LlmClient capped = ContextWindowCappedLlmClient.cap(delegate, 20_000, 2_000);
        List<String> exposed = new ArrayList<>();

        LlmClient.ChatResponse response = capped.chat(
                List.of(LlmClient.Message.user("retry interrupted stream")),
                List.of(),
                new LlmClient.StreamListener() {
                    @Override
                    public void onReasoningDelta(String delta) {
                        exposed.add("reasoning:" + delta);
                    }

                    @Override
                    public void onContentDelta(String delta) {
                        exposed.add("content:" + delta);
                    }
                });

        assertEquals("final", response.content());
        assertEquals(2, delegate.attempts());
        assertEquals(1, delegate.nonListeningCalls());
        assertEquals(0, delegate.listeningCalls());
        assertEquals(List.of("reasoning:final thought", "content:final"), exposed);
    }

    @Test
    void preservesSmallerDelegateWindowAndRejectsInvalidCaps() {
        LlmClient delegate = new RecordingClient(128_000, response(1));

        assertEquals(128_000,
                ContextWindowCappedLlmClient.cap(delegate, 321_000, 16_384).maxContextWindow());
        assertThrows(IllegalArgumentException.class,
                () -> ContextWindowCappedLlmClient.cap(delegate, 7_999, 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> ContextWindowCappedLlmClient.cap(delegate, 10_000_001, 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> ContextWindowCappedLlmClient.cap(delegate, 100_000, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ContextWindowCappedLlmClient.cap(delegate, 100_000, 16_385));
        assertThrows(IllegalArgumentException.class,
                () -> ContextWindowCappedLlmClient.cap(delegate, 8_000, 8_001));
        assertThrows(IllegalArgumentException.class,
                () -> ContextWindowCappedLlmClient.cap(
                        new RecordingClient(0, response(1)), 100_000, 16_384));
    }

    private static LlmClient.ChatResponse response(int inputTokens) {
        return response(inputTokens, 1, true);
    }

    private static LlmClient.ChatResponse response(
            int inputTokens, int outputTokens, boolean usagePresent) {
        return new LlmClient.ChatResponse(
                "assistant", "ok", "thought", List.of(),
                inputTokens, outputTokens, 0, "resolved-model", usagePresent);
    }

    private static void assertResponseRejected(
            LlmClient.ChatResponse response,
            int contextCap,
            int outputCap,
            ContextWindowCappedLlmClient.ContextWindowCapExceededException.Kind expectedKind) {
        RecordingClient delegate = new RecordingClient(contextCap, response);
        LlmClient capped = ContextWindowCappedLlmClient.cap(delegate, contextCap, outputCap);

        ContextWindowCappedLlmClient.ContextWindowCapExceededException error = assertThrows(
                ContextWindowCappedLlmClient.ContextWindowCapExceededException.class,
                () -> capped.chat(List.of(LlmClient.Message.user("hello")), List.of()));

        assertEquals(expectedKind, error.kind());
        assertEquals(1, delegate.calls());
    }

    private static long providerNeutralRequestBytes(
            List<LlmClient.Message> messages,
            List<LlmClient.Tool> tools) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var root = mapper.createObjectNode();
        root.put("model", "provider-model");
        root.put("stream", true);
        root.put("max_tokens", 16_384);
        root.put("temperature", 1.0);
        root.put("top_p", 0.95);
        root.put("reasoning_effort", "max");
        root.putObject("thinking").put("type", "enabled").put("clear_thinking", false);
        root.putObject("stream_options").put("include_usage", true);
        root.put("tool_stream", true);
        var messageArray = root.putArray("messages");
        for (LlmClient.Message message : messages) {
            var node = messageArray.addObject();
            node.put("role", message.role());
            if (message.hasContentParts()) {
                var content = node.putArray("content");
                for (LlmClient.ContentPart part : message.contentParts()) {
                    if (part.isText()) {
                        content.addObject().put("type", "text").put("text", part.text());
                    } else if ("image_base64".equals(part.type())) {
                        content.addObject()
                                .put("type", "image_url")
                                .putObject("image_url")
                                .put("url", "data:" + part.mimeType()
                                        + ";base64," + part.imageBase64());
                    } else if ("image_url".equals(part.type())) {
                        content.addObject()
                                .put("type", "image_url")
                                .putObject("image_url")
                                .put("url", part.imageUrl());
                    }
                }
            } else {
                node.put("content", message.content());
            }
            if (message.reasoningContent() != null) {
                node.put("reasoning_content", message.reasoningContent());
            }
            if (message.toolCallId() != null) {
                node.put("tool_call_id", message.toolCallId());
            }
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                var calls = node.putArray("tool_calls");
                for (LlmClient.ToolCall call : message.toolCalls()) {
                    var callNode = calls.addObject();
                    callNode.put("id", call.id());
                    callNode.put("type", "function");
                    callNode.putObject("function")
                            .put("name", call.function().name())
                            .put("arguments", call.function().arguments());
                }
            }
        }
        var toolArray = root.putArray("tools");
        for (LlmClient.Tool tool : tools) {
            var function = toolArray.addObject()
                    .put("type", "function")
                    .putObject("function");
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.set("parameters", tool.parameters());
        }
        return mapper.writeValueAsString(root).getBytes(StandardCharsets.UTF_8).length;
    }

    private static final class RecordingClient implements LlmClient {
        private final int maxContextWindow;
        private final ChatResponse response;
        private final AtomicInteger calls = new AtomicInteger();

        private RecordingClient(int maxContextWindow, ChatResponse response) {
            this.maxContextWindow = maxContextWindow;
            this.response = response;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            calls.incrementAndGet();
            return response;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                 StreamListener listener) {
            calls.incrementAndGet();
            listener.onReasoningDelta("thought");
            listener.onContentDelta("ok");
            return response;
        }

        int calls() {
            return calls.get();
        }

        @Override public String getModelName() { return "test-model"; }
        @Override public String getProviderName() { return "test-provider"; }
        @Override public int maxContextWindow() { return maxContextWindow; }
        @Override public boolean supportsPromptCaching() { return true; }
        @Override public boolean supportsTools() { return false; }
        @Override public boolean supportsImageInput() { return false; }
        @Override public String promptCacheMode() { return "automatic-prefix-cache"; }
    }

    /** Deterministically models a provider that retries one interrupted SSE attempt internally. */
    private static final class RetrySafeDelegate implements LlmClient {
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicInteger nonListeningCalls = new AtomicInteger();
        private final AtomicInteger listeningCalls = new AtomicInteger();

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            nonListeningCalls.incrementAndGet();
            attempts.incrementAndGet(); // interrupted before any externally consumable output
            attempts.incrementAndGet(); // provider retry succeeds
            return new ChatResponse(
                    "assistant", "final", "final thought", List.of(),
                    500, 20, 0, "resolved-model", true);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                 StreamListener listener) throws java.io.IOException {
            listeningCalls.incrementAndGet();
            listener.onContentDelta("partial leaked attempt");
            throw new java.io.IOException("interrupted SSE after content");
        }

        int attempts() { return attempts.get(); }
        int nonListeningCalls() { return nonListeningCalls.get(); }
        int listeningCalls() { return listeningCalls.get(); }

        @Override public String getModelName() { return "retry-safe-model"; }
        @Override public String getProviderName() { return "retry-safe-provider"; }
        @Override public int maxContextWindow() { return 20_000; }
    }
}
