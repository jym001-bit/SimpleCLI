package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TracingLlmClientTest {

    @Test
    void recordsOnlySafeMetadataAndAggregatesUsage(@TempDir Path tempDir) throws Exception {
        Path trace = tempDir.resolve("llm-calls.jsonl");
        TracingLlmClient client = new TracingLlmClient(new StubClient(), trace);

        LlmClient.ChatResponse response = client.chat(
                List.of(
                        LlmClient.Message.system("private benchmark system prompt"),
                        LlmClient.Message.user("Bearer should-not-be-recorded")),
                List.of(new LlmClient.Tool("read_file", "secret description", null)));

        assertEquals("done", response.content());
        TracingLlmClient.Metrics metrics = client.metrics();
        assertEquals(new TracingLlmClient.Metrics(
                        1, 11, 7, 3, 1, metrics.elapsedMillis(),
                        1, "trace-resolved", true, true,
                        metrics.systemPromptSha256(), metrics.initialToolSchemaSha256(), true,
                        0, 0, 0, 11, 18, false),
                metrics);
        assertEquals(64, metrics.systemPromptSha256().length());
        assertEquals(64, metrics.initialToolSchemaSha256().length());
        assertTrue(metrics.requestFingerprintComplete());
        assertEquals(11, metrics.maxObservedInputTokensPerCall());
        assertEquals(18, metrics.maxObservedTotalTokensPerCall());
        assertEquals(0, metrics.requestedContextWindowCapTokens());
        assertEquals(0, metrics.effectiveContextWindowCapTokens());
        assertEquals(0, metrics.maxOutputTokensPerCall());
        assertFalse(metrics.contextCapSatisfied());
        String persisted = Files.readString(trace);
        assertTrue(persisted.contains("\"provider\":\"stub\""));
        assertTrue(persisted.contains("\"returnedTools\":[\"write_file\"]"));
        assertTrue(persisted.contains("\"resolvedModel\":\"trace-resolved\""));
        assertTrue(persisted.contains("\"usagePresent\":true"));
        assertTrue(persisted.contains("\"systemPromptSha256\":"));
        assertTrue(persisted.contains("\"toolSchemaSha256\":"));
        assertFalse(persisted.contains("should-not-be-recorded"));
        assertFalse(persisted.contains("secret description"));
        assertFalse(persisted.contains("private benchmark system prompt"));
        assertFalse(persisted.contains("{\\\"path\\\""));
    }

    @Test
    void requiresEverySuccessfulCallToHaveOneResolvedModelAndUsage(@TempDir Path tempDir) throws Exception {
        SequenceStubClient delegate = new SequenceStubClient(
                evidenceResponse("model-a", true),
                evidenceResponse("model-a", true),
                evidenceResponse("model-b", true),
                evidenceResponse("model-b", false));
        TracingLlmClient client = new TracingLlmClient(delegate, tempDir.resolve("calls.jsonl"));

        client.chat(messages("stable-system", "one"), List.of());
        client.chat(messages("stable-system", "two"), List.of());
        TracingLlmClient.Metrics consistent = client.metrics();
        assertEquals(2, consistent.successfulCalls());
        assertEquals("model-a", consistent.resolvedModel());
        assertTrue(consistent.resolvedModelConsistent());
        assertTrue(consistent.usageComplete());
        assertTrue(consistent.requestFingerprintComplete());

        client.chat(messages("stable-system", "three"), List.of());
        TracingLlmClient.Metrics mixed = client.metrics();
        assertEquals(3, mixed.successfulCalls());
        assertEquals(null, mixed.resolvedModel());
        assertFalse(mixed.resolvedModelConsistent());
        assertTrue(mixed.usageComplete());

        client.chat(messages("stable-system", "four"), List.of());
        assertFalse(client.metrics().usageComplete());
        assertTrue(client.metrics().requestFingerprintComplete());
    }

    @Test
    void missingResolvedModelNeverPassesConsistencyGate(@TempDir Path tempDir) throws Exception {
        TracingLlmClient client = new TracingLlmClient(
                new SequenceStubClient(evidenceResponse(null, true)),
                tempDir.resolve("calls.jsonl"));

        client.chat(List.of(LlmClient.Message.user("one")), List.of());

        assertEquals(null, client.metrics().resolvedModel());
        assertFalse(client.metrics().resolvedModelConsistent());
        assertTrue(client.metrics().usageComplete());
        assertFalse(client.metrics().requestFingerprintComplete());
    }

    @Test
    void rejectsSystemPromptDriftFromRequestFingerprintGate(@TempDir Path tempDir) throws Exception {
        TracingLlmClient client = new TracingLlmClient(
                new SequenceStubClient(
                        evidenceResponse("model-a", true),
                        evidenceResponse("model-a", true)),
                tempDir.resolve("calls.jsonl"));

        client.chat(messages("system-one", "one"), List.of());
        String initialToolSchema = client.metrics().initialToolSchemaSha256();
        client.chat(messages("system-two", "two"), List.of());

        assertFalse(client.metrics().requestFingerprintComplete());
        assertEquals(null, client.metrics().systemPromptSha256());
        assertEquals(initialToolSchema, client.metrics().initialToolSchemaSha256());
    }

    @Test
    void canonicalizesJsonSchemaObjectFieldOrder(@TempDir Path tempDir) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var firstSchema = mapper.createObjectNode();
        var firstProperties = mapper.createObjectNode();
        firstProperties.putObject("path").put("type", "string");
        firstSchema.put("type", "object");
        firstSchema.set("properties", firstProperties);
        var secondSchema = mapper.createObjectNode();
        var secondProperties = mapper.createObjectNode();
        secondProperties.putObject("path").put("type", "string");
        secondSchema.set("properties", secondProperties);
        secondSchema.put("type", "object");

        TracingLlmClient first = new TracingLlmClient(
                new StubClient(), tempDir.resolve("first.jsonl"));
        TracingLlmClient second = new TracingLlmClient(
                new StubClient(), tempDir.resolve("second.jsonl"));
        first.chat(messages("same-system", "one"),
                List.of(new LlmClient.Tool("read_file", "read", firstSchema)));
        second.chat(messages("same-system", "two"),
                List.of(new LlmClient.Tool("read_file", "read", secondSchema)));

        assertEquals(first.metrics().initialToolSchemaSha256(),
                second.metrics().initialToolSchemaSha256());
    }

    @Test
    void recordsSingleCallMaximumRatherThanCumulativeInput(@TempDir Path tempDir) throws Exception {
        LlmClient capped = ContextWindowCappedLlmClient.cap(
                new SequenceStubClient(
                        evidenceResponse("model-a", true, 1_000),
                        evidenceResponse("model-a", true, 2_000)),
                20_000,
                2_000);
        TracingLlmClient client = new TracingLlmClient(
                capped, tempDir.resolve("capped-calls.jsonl"));

        client.chat(messages("stable-system", "one"), List.of());
        client.chat(messages("stable-system", "two"), List.of());

        TracingLlmClient.Metrics metrics = client.metrics();
        assertEquals(3_000, metrics.inputTokens());
        assertEquals(2_000, metrics.maxObservedInputTokensPerCall());
        assertEquals(2_001, metrics.maxObservedTotalTokensPerCall());
        assertEquals(20_000, metrics.requestedContextWindowCapTokens());
        assertEquals(20_000, metrics.effectiveContextWindowCapTokens());
        assertEquals(2_000, metrics.maxOutputTokensPerCall());
        assertTrue(metrics.contextCapSatisfied());
    }

    @Test
    void recordsProviderUsageViolationAsUnsatisfiedWithoutCountingItAsSuccess(
            @TempDir Path tempDir) throws Exception {
        LlmClient capped = ContextWindowCappedLlmClient.cap(
                new SequenceStubClient(evidenceResponse("model-a", true, 8_001)),
                8_000,
                1_000);
        TracingLlmClient client = new TracingLlmClient(
                capped, tempDir.resolve("cap-violation.jsonl"));

        assertThrows(
                ContextWindowCappedLlmClient.ContextWindowCapExceededException.class,
                () -> client.chat(messages("stable-system", "one"), List.of()));

        TracingLlmClient.Metrics metrics = client.metrics();
        assertEquals(1, metrics.calls());
        assertEquals(0, metrics.successfulCalls());
        assertEquals(0, metrics.inputTokens());
        assertEquals(8_001, metrics.maxObservedInputTokensPerCall());
        assertEquals(8_002, metrics.maxObservedTotalTokensPerCall());
        assertEquals(8_000, metrics.requestedContextWindowCapTokens());
        assertEquals(8_000, metrics.effectiveContextWindowCapTokens());
        assertEquals(1_000, metrics.maxOutputTokensPerCall());
        assertFalse(metrics.contextCapSatisfied());
    }

    @Test
    void laterOrdinaryFailureCannotOverwriteEarlierEvaluationInvalidEvidence(
            @TempDir Path tempDir) throws Exception {
        TracingLlmClient client = new TracingLlmClient(
                new SequenceFailureClient(), tempDir.resolve("failure-precedence.jsonl"));

        assertThrows(IOException.class,
                () -> client.chat(messages("stable-system", "one"), List.of()));
        assertThrows(IOException.class,
                () -> client.chat(messages("stable-system", "two"), List.of()));

        assertEquals("LLM_API_ERROR", client.lastFailureType());
        assertEquals(BenchmarkProviderEvidenceGate.USAGE_UNPROVEN,
                client.evaluationInvalidFailureType());
        assertEquals(2, client.failedCalls());
    }

    @Test
    void doesNotTreatASmallerProviderWindowAsSatisfyingRequestedCapacity(
            @TempDir Path tempDir) throws Exception {
        LlmClient capped = ContextWindowCappedLlmClient.cap(
                new SequenceStubClient(evidenceResponse("model-a", true, 1_000)) {
                    @Override
                    public int maxContextWindow() {
                        return 8_000;
                    }
                },
                20_000,
                1_000);
        TracingLlmClient client = new TracingLlmClient(
                capped, tempDir.resolve("smaller-provider-window.jsonl"));

        client.chat(messages("stable-system", "one"), List.of());

        TracingLlmClient.Metrics metrics = client.metrics();
        assertEquals(20_000, metrics.requestedContextWindowCapTokens());
        assertEquals(8_000, metrics.effectiveContextWindowCapTokens());
        assertEquals(1_000, metrics.maxOutputTokensPerCall());
        assertFalse(metrics.contextCapSatisfied());
    }

    private static List<LlmClient.Message> messages(String system, String user) {
        return List.of(LlmClient.Message.system(system), LlmClient.Message.user(user));
    }

    private static LlmClient.ChatResponse evidenceResponse(String resolvedModel, boolean usagePresent) {
        return evidenceResponse(resolvedModel, usagePresent, 1);
    }

    private static LlmClient.ChatResponse evidenceResponse(
            String resolvedModel, boolean usagePresent, int inputTokens) {
        return new LlmClient.ChatResponse(
                "assistant", "done", null, List.of(), inputTokens, 1, 0,
                resolvedModel, usagePresent);
    }

    private static final class StubClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return response();
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            listener.onContentDelta("done");
            return response();
        }

        private ChatResponse response() {
            return new ChatResponse(
                    "assistant",
                    "done",
                    "hidden reasoning",
                    List.of(new ToolCall("call-1", new ToolCall.Function("write_file", "{\"path\":\"secret\"}"))),
                    11,
                    7,
                    3,
                    "trace-resolved",
                    true);
        }

        @Override
        public String getModelName() {
            return "trace-test";
        }

        @Override
        public String getProviderName() {
            return "stub";
        }
    }

    private static class SequenceStubClient implements LlmClient {
        private final ArrayDeque<ChatResponse> responses;

        private SequenceStubClient(ChatResponse... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return responses.removeFirst();
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            return responses.removeFirst();
        }

        @Override
        public String getModelName() {
            return "requested-model";
        }

        @Override
        public String getProviderName() {
            return "stub";
        }
    }

    private static final class SequenceFailureClient implements LlmClient {
        private int calls;

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            calls++;
            if (calls == 1) {
                throw ContextWindowCappedLlmClient.ContextWindowCapExceededException
                        .missingProviderUsage(1_000_000, 1_000_000, 16_384);
            }
            throw new IOException("ordinary later provider failure");
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                 StreamListener listener) throws IOException {
            return chat(messages, tools);
        }

        @Override public String getModelName() { return "requested-model"; }
        @Override public String getProviderName() { return "stub"; }
    }
}
