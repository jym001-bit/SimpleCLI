package com.paicli.eval.benchmark;

import com.paicli.eval.benchmark.mock.D4WebMock;
import com.paicli.eval.benchmark.relay.*;
import com.paicli.llm.LlmClient;
import com.paicli.web.SearchResult;
import com.paicli.web.WebFetcher;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.List;

import static com.paicli.eval.benchmark.D4WebProtocolTest.*;
import static com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol.*;
import static org.junit.jupiter.api.Assertions.*;

/** Raw hostile Worker frames must not become arbitrary host Web requests. No model/API calls. */
class D4WebHostGuardTest {
    private static final String QUERY = "fixture query";

    @Test void noProviderToolCallCannotReachBackend() throws Exception {
        var mock = mock(); var relay = relay(mock, List.of(), false,
                request("web-1", WebOperation.SEARCH, QUERY, 2));
        assertThrows(IOException.class, relay::serveNext); assertTrue(mock.audit().isEmpty());
    }

    @Test void mismatchedQueryTopKAndUrlCannotReachBackend() throws Exception {
        for (var bad : List.of(request("w1", WebOperation.SEARCH, "other", 2),
                request("w1", WebOperation.SEARCH, QUERY, 3),
                request("w1", WebOperation.CHECK_URL, "https://other.invalid/", 0))) {
            var mock = mock(); var relay = relay(mock, calls(mock), true, bad);
            assertThrows(IOException.class, relay::serveNext); assertTrue(mock.audit().isEmpty());
        }
    }

    @Test void searchAndUrlCheckAreSingleUseEvenWithNewRequestIds() throws Exception {
        for (var operation : List.of(WebOperation.SEARCH, WebOperation.CHECK_URL)) {
            var mock = mock(); String input = operation == WebOperation.SEARCH ? QUERY : mock.definition().releaseUrl();
            int topK = operation == WebOperation.SEARCH ? 2 : 0;
            var relay = relay(mock, calls(mock), true, request("w1", operation, input, topK), request("w2", operation, input, topK));
            assertEquals(BenchmarkProviderRelay.ServeResult.WEB_SERVED, relay.serveNext());
            assertThrows(IOException.class, relay::serveNext); assertEquals(1, mock.audit().size());
        }
    }

    @Test void fetchRequiresUnusedMatchingSuccessfulCheck() throws Exception {
        var mock = mock(); String url = mock.definition().releaseUrl();
        var early = relay(mock, calls(mock), true, request("w1", WebOperation.FETCH, url, 0));
        assertThrows(IOException.class, early::serveNext); assertTrue(mock.audit().isEmpty());
        var relay = relay(mock, calls(mock), true, request("w1", WebOperation.CHECK_URL, url, 0),
                request("w2", WebOperation.FETCH, url, 0), request("w3", WebOperation.FETCH, url, 0));
        assertEquals(BenchmarkProviderRelay.ServeResult.WEB_SERVED, relay.serveNext());
        assertEquals(BenchmarkProviderRelay.ServeResult.WEB_SERVED, relay.serveNext());
        assertThrows(IOException.class, relay::serveNext); assertEquals(2, mock.audit().size());
        var mismatch = relay(mock, calls(mock), true, request("w1", WebOperation.CHECK_URL, url, 0),
                request("w2", WebOperation.FETCH, mock.definition().migrationUrl(), 0));
        mismatch.serveNext(); assertThrows(IOException.class, mismatch::serveNext); assertEquals(3, mock.audit().size());
        var denied = relay(new ForwardingEndpoint(mock) {
            @Override public String checkUrl(String ignored) { return "DENIED"; }
        }, calls(mock), true, request("w1", WebOperation.CHECK_URL, url, 0), request("w2", WebOperation.FETCH, url, 0));
        denied.serveNext(); assertThrows(IOException.class, denied::serveNext); assertEquals(3, mock.audit().size());
    }

    @Test void missingOrRewrittenTerminalToolsCannotBeAccepted() throws Exception {
        for (String mutation : List.of("missing", "id", "name", "arguments")) {
            var mock = mock(); var call = calls(mock).get(0);
            var evidence = new WireToolExecution(1, mutation.equals("id") ? "other" : call.id(),
                    mutation.equals("name") ? "web_fetch" : call.function().name(),
                    mutation.equals("arguments") ? "{}" : call.function().arguments(), "", textSha256(""), 0, 0, false, true);
            var relay = relay(mock, List.of(call), true, new WorkerComplete(
                    header(Direction.WORKER_TO_COORDINATOR, FrameType.WORKER_COMPLETE, "", 0), "done",
                    mutation.equals("missing") ? List.of() : List.of(evidence)));
            assertThrows(IOException.class, relay::serveNext, mutation); assertNull(relay.terminalFrame());
        }
    }

    @Test void hostFailureIsTypedButCandidatePipeClosureIsNot() throws Exception {
        var mock = mock(); var failure = relay(new ForwardingEndpoint(mock) {
            @Override public List<SearchResult> search(String query, int topK) throws IOException { throw new IOException("injected fixture fault"); }
        }, calls(mock), true, request("w1", WebOperation.SEARCH, QUERY, 2));
        assertThrows(BenchmarkProviderRelay.MockWebFailure.class, failure::serveNext);
        assertEquals("FROZEN_MOCK_FAILURE", failure.providerFailureType()); assertTrue(mock.audit().isEmpty());
        var output = new SwitchableOutput();
        var brokenPipe = relay(mock, calls(mock), true, output, request("w1", WebOperation.SEARCH, QUERY, 2));
        output.fail = true;
        assertFalse(assertThrows(IOException.class, brokenPipe::serveNext) instanceof BenchmarkProviderRelay.MockWebFailure);
        assertNull(brokenPipe.providerFailureType()); assertEquals(1, mock.audit().size());
    }

    @Test void providerAuditFailureIsNotMisclassifiedAsALlmFailure() {
        var mock = mock();
        assertThrows(BenchmarkProviderRelay.MockWebFailure.class, () -> relay(new ForwardingEndpoint(mock) {
            @Override public void recordProviderTurn(List<WireMessage> messages, WireChatResponse response) throws IOException { throw new IOException("injected audit failure"); }
        }, calls(mock), true));
        assertTrue(mock.audit().isEmpty());
    }

    private static D4WebMock mock() { return new D4WebMock(D4NativeWebTest.entropy(42)); }
    private static List<LlmClient.ToolCall> calls(D4WebMock mock) {
        return List.of(new LlmClient.ToolCall("search", new LlmClient.ToolCall.Function("web_search", "{\"query\":\"fixture query\",\"top_k\":2}")),
                new LlmClient.ToolCall("fetch", new LlmClient.ToolCall.Function("web_fetch", "{\"url\":\"" + mock.definition().releaseUrl() + "\"}")));
    }
    private static BenchmarkProviderRelay relay(BenchmarkProviderRelay.MockWebEndpoint mock, List<LlmClient.ToolCall> calls,
                                                boolean chat, Frame... frames) throws Exception {
        return relay(mock, calls, chat, new ByteArrayOutputStream(), frames);
    }
    private static BenchmarkProviderRelay relay(BenchmarkProviderRelay.MockWebEndpoint mock, List<LlmClient.ToolCall> calls,
                                                boolean chat, OutputStream output, Frame... frames) throws Exception {
        LlmClient client = new LlmClient() {
            @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) { return chat(messages, tools); }
            @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) {
                return new ChatResponse("assistant", "", null, calls, 100, 30, 0, getModelName(), true);
            }
            @Override public String getModelName() { return "scripted-web-guard"; }
            @Override public String getProviderName() { return "scripted"; }
            @Override public int maxContextWindow() { return 1_000_000; }
        };
        var start = D4WebRelayTest.start(client, "Search fixture query");
        var input = new ByteArrayOutputStream(); var writer = new BenchmarkFramedChannel(InputStream.nullInputStream(), input);
        writer.write(new WorkerReady(header(Direction.WORKER_TO_COORDINATOR, FrameType.WORKER_READY, "", 0), start.capabilities()));
        if (chat) writer.write(new ChatRequest(header(Direction.WORKER_TO_COORDINATOR, FrameType.CHAT_REQUEST, "chat-1", 0),
                List.of(new WireMessage("user", start.prompt(), null, List.of(), null)), List.of()));
        for (var frame : frames) writer.write(frame);
        var relay = BenchmarkProviderRelay.connect(new BenchmarkFramedChannel(new ByteArrayInputStream(input.toByteArray()), output), start, client, null, mock);
        if (chat) assertEquals(BenchmarkProviderRelay.ServeResult.CHAT_SERVED, relay.serveNext());
        return relay;
    }
    static class ForwardingEndpoint implements BenchmarkProviderRelay.MockWebEndpoint {
        private final D4WebMock mock;
        ForwardingEndpoint(D4WebMock mock) { this.mock = mock; }
        @Override public void recordProviderTurn(List<WireMessage> messages, WireChatResponse response) throws IOException { mock.recordProviderTurn(messages, response); }
        @Override public List<SearchResult> search(String query, int topK) throws IOException { return mock.search(query, topK); }
        @Override public String checkUrl(String url) throws IOException { return mock.checkUrl(url); }
        @Override public WebFetcher.RawResponse fetch(String url) throws IOException { return mock.fetch(url); }
        @Override public void recordExchange(int ordinal, WebRequest request, WebComplete response) throws IOException { mock.recordExchange(ordinal, request, response); }
    }
    private static class SwitchableOutput extends OutputStream {
        boolean fail;
        @Override public void write(int b) throws IOException { if (fail) throw new IOException("candidate closed its pipe"); }
    }
}
