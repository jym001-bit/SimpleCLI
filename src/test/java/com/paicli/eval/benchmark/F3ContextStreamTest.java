package com.paicli.eval.benchmark;

import com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol;
import com.paicli.eval.benchmark.relay.F3ToolResultAudit;
import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** In-memory F3 adapter-stream controls; no HTTP requests or model calls. */
class F3ContextStreamTest {
    private static final List<LlmClient.Message> MESSAGES = List.of(LlmClient.Message.user("hello"));

    @Test void preservesActualFragmentOrderOnlyAfterCompleteUsageGateWithoutSynthesizingFinalText() throws Exception {
        var returned = new AtomicBoolean();
        var provider = new Provider(listener -> {
            listener.onReasoningDelta("stream-"); listener.onContentDelta("interleaved");
            listener.onReasoningDelta("only"); listener.onContentDelta(null); listener.onContentDelta("");
            returned.set(true); return response("different final", true, 100, 20);
        });
        var audit = audit(); var exposed = new ArrayList<String>();
        var capped = capped(provider, audit);
        var response = capped.chat(MESSAGES, List.of(), new LlmClient.StreamListener() {
            @Override public void onReasoningDelta(String value) { assertTrue(returned.get()); exposed.add("R:" + value); }
            @Override public void onContentDelta(String value) { assertTrue(returned.get()); exposed.add("C:" + value); }
        });
        assertEquals(List.of("R:stream-", "C:interleaved", "R:only"), exposed);
        assertEquals("different final", response.content()); assertEquals(1, provider.calls); audit.requireHealthy();
    }

    @Test void finalResponseWithoutObservedFragmentsDoesNotInventStreamEvidence() throws Exception {
        var provider = new Provider(listener -> response("final only", true, 100, 20));
        var exposed = new ArrayList<String>(); var audit = audit();
        assertEquals("final only", capped(provider, audit).chat(MESSAGES, List.of(), sink(exposed)).content());
        assertTrue(exposed.isEmpty()); audit.requireHealthy();
    }

    @Test void malformedUsageAndBothCapViolationsDeliverNoBufferedFragment() {
        for (var response : List.of(response("final", false, 100, 20), response("final", true, 100, 16_385),
                response("final", true, 1_000_000, 20))) {
            var provider = new Provider(listener -> { listener.onContentDelta("must stay private"); return response; });
            var exposed = new ArrayList<String>(); var audit = audit();
            assertThrows(ContextWindowCappedLlmClient.ContextWindowCapExceededException.class,
                    () -> capped(provider, audit).chat(MESSAGES, List.of(), sink(exposed)));
            assertTrue(exposed.isEmpty()); assertFalse(audit.failed()); assertEquals(1, provider.calls);
        }
    }

    @Test void genuineProviderFailureRetainsOriginalCategoryAndNeverDeliversPartialStream() {
        var original = new IOException("synthetic upstream interruption");
        var provider = new Provider(listener -> { listener.onReasoningDelta("partial"); throw original; });
        var audit = audit(); var exposed = new ArrayList<String>();
        assertSame(original, assertThrows(IOException.class,
                () -> capped(provider, audit).chat(MESSAGES, List.of(), sink(exposed))));
        assertFalse(audit.failed()); assertTrue(exposed.isEmpty()); assertEquals(1, provider.calls);
    }

    @Test void requestGateStillRunsBeforeAnyProviderCallback() {
        var provider = new Provider(listener -> response("unused", true, 100, 20));
        var audit = audit();
        var capped = ContextWindowCappedLlmClient.capPreservingObservedDeltas(provider, 8_000, 2_000, audit);
        assertThrows(ContextWindowCappedLlmClient.ContextWindowCapExceededException.class,
                () -> capped.chat(List.of(LlmClient.Message.user("x".repeat(5_000))), List.of(), sink(new ArrayList<>())));
        assertEquals(0, provider.calls); assertFalse(audit.failed());
    }

    @Test void characterBoundaryIsExactAndOverflowRemainsTypedEvenWhenDelegateSwallowsOrWrapsIt() throws Exception {
        String fragment = "x".repeat(BenchmarkRelayProtocol.MAX_TEXT_CHARS);
        var goodAudit = audit(); var sizes = new ArrayList<Integer>();
        capped(new Provider(listener -> {
            for (int i = 0; i < 4; i++) listener.onContentDelta(fragment);
            return response("", true, 100, 20);
        }), goodAudit)
                .chat(MESSAGES, List.of(), new LlmClient.StreamListener() {
                    @Override public void onContentDelta(String delta) { sizes.add(delta.length()); }
                });
        assertEquals(List.of(BenchmarkRelayProtocol.MAX_TEXT_CHARS, BenchmarkRelayProtocol.MAX_TEXT_CHARS,
                BenchmarkRelayProtocol.MAX_TEXT_CHARS, BenchmarkRelayProtocol.MAX_TEXT_CHARS), sizes); goodAudit.requireHealthy();

        for (int mode = 0; mode < 3; mode++) {
            final int behavior = mode;
            var audit = audit(); var exposed = new ArrayList<String>();
            var provider = new Provider(listener -> {
                for (int i = 0; i < 4; i++) listener.onReasoningDelta(fragment);
                try { listener.onContentDelta("overflow"); }
                catch (UncheckedIOException failure) {
                    if (behavior == 0) throw failure;
                    if (behavior == 1) throw new IOException("provider wrapped callback failure", failure);
                    // A provider swallowing an observer failure must not make this attempt scoreable.
                }
                return response("", true, 100, 20);
            });
            var capped = capped(provider, audit);
            assertThrows(F3ToolResultAudit.Failure.class, () -> capped.chat(MESSAGES, List.of(), sink(exposed)));
            assertTrue(audit.failed()); assertTrue(exposed.isEmpty());
            assertThrows(F3ToolResultAudit.Failure.class, () -> capped.chat(MESSAGES, List.of(), sink(exposed)));
            assertEquals(1, provider.calls);
        }
    }

    @Test void aFragmentLargerThanTheWireLimitIsTypedInvalidNotSplitOrSilentlyLost() {
        var audit = audit(); var exposed = new ArrayList<String>();
        var provider = new Provider(listener -> {
            listener.onContentDelta("x".repeat(BenchmarkRelayProtocol.MAX_TEXT_CHARS + 1));
            return response("", true, 100, 20);
        });
        assertThrows(F3ToolResultAudit.Failure.class, () -> capped(provider, audit).chat(MESSAGES, List.of(), sink(exposed)));
        assertTrue(audit.failed()); assertTrue(exposed.isEmpty());
    }

    @Test void fragmentCountIsBoundedIndependentlyOfTotalCharacters() throws Exception {
        for (boolean overflow : List.of(false, true)) {
            var audit = audit(); var exposed = new ArrayList<String>();
            var provider = new Provider(listener -> {
                for (int i = 0; i < ContextWindowCappedLlmClient.MAX_OBSERVED_STREAM_DELTAS; i++) listener.onContentDelta("x");
                if (overflow) listener.onReasoningDelta("last");
                return response("", true, 100, 20);
            });
            if (overflow) {
                assertThrows(F3ToolResultAudit.Failure.class,
                        () -> capped(provider, audit).chat(MESSAGES, List.of(), sink(exposed)));
                assertTrue(exposed.isEmpty()); assertTrue(audit.failed());
            } else {
                capped(provider, audit).chat(MESSAGES, List.of(), sink(exposed));
                assertEquals(ContextWindowCappedLlmClient.MAX_OBSERVED_STREAM_DELTAS, exposed.size()); audit.requireHealthy();
            }
        }
    }

    @Test void explicitModeTwoArgumentCallsStillObserveAndLateCallbacksInvalidateAudit() throws Exception {
        var callback = new AtomicReference<LlmClient.StreamListener>(); var audit = audit();
        var provider = new Provider(listener -> { callback.set(listener); listener.onContentDelta("observed"); return response("final", true, 100, 20); });
        assertEquals("final", capped(provider, audit).chat(MESSAGES, List.of()).content()); audit.requireHealthy();
        assertThrows(UncheckedIOException.class, () -> callback.get().onContentDelta("after return"));
        assertTrue(audit.failed()); assertThrows(F3ToolResultAudit.Failure.class, audit::requireHealthy);
    }

    private static F3ToolResultAudit audit() { return new F3ToolResultAudit("frozen F3 unit task"); }
    private static ContextWindowCappedLlmClient capped(LlmClient provider, F3ToolResultAudit audit) {
        return ContextWindowCappedLlmClient.capPreservingObservedDeltas(provider, 1_000_000, 16_384, audit);
    }
    private static LlmClient.ChatResponse response(String text, boolean usage, int input, int output) {
        return new LlmClient.ChatResponse("assistant", text, "final reasoning", List.of(), input, output, 0, "deepseek-v4-flash", usage);
    }
    private static LlmClient.StreamListener sink(List<String> output) {
        return new LlmClient.StreamListener() {
            @Override public void onReasoningDelta(String value) { output.add("R:" + value); }
            @Override public void onContentDelta(String value) { output.add("C:" + value); }
        };
    }
    @FunctionalInterface private interface Behavior { LlmClient.ChatResponse respond(LlmClient.StreamListener listener) throws IOException; }
    private static final class Provider implements LlmClient {
        private final Behavior behavior;
        private int calls;
        private Provider(Behavior behavior) { this.behavior = behavior; }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) { throw new AssertionError("F3 must use actual listening overload"); }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            calls++; assertNotNull(listener); return behavior.respond(listener);
        }
        @Override public String getModelName() { return "deepseek-v4-flash"; }
        @Override public String getProviderName() { return "deepseek"; }
        @Override public int maxContextWindow() { return 1_000_000; }
    }
}
