package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.eval.benchmark.mock.D4WebMock;
import com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol.*;
import static org.junit.jupiter.api.Assertions.*;

class D4WebProtocolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String URL = "https://fixture.invalid/article";

    @Test void strictWebWireRoundTripsAndRejectsMissingFieldsCoercionAndExtraEndpoint() throws Exception {
        var request = request("web-1", WebOperation.SEARCH, "fixture query", 2);
        assertEquals(12, VERSION); assertEquals(request, decode(encode(request)));
        for (String key : List.of("protocolVersion", "eventSequence", "callId")) {
            ObjectNode wire = (ObjectNode) JSON.readTree(encode(request));
            if (key.equals("callId")) wire.put(key, 1); else wire.put(key, wire.path(key).asText());
            assertThrows(IOException.class, () -> decode(JSON.writeValueAsBytes(wire)), key);
        }
        assertThrows(IOException.class, () -> decode("null".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        for (String mutation : List.of("missing", "string", "float", "null", "extra")) {
            ObjectNode wire = (ObjectNode) JSON.readTree(encode(request)); var payload = (ObjectNode) wire.path("payload");
            switch (mutation) {
                case "missing" -> payload.remove("topK");
                case "string" -> payload.put("topK", "2");
                case "float" -> payload.put("topK", 2.0);
                case "null" -> payload.putNull("topK");
                case "extra" -> payload.put("endpoint", "https://outside.invalid/");
            }
            assertThrows(IOException.class, () -> decode(JSON.writeValueAsBytes(wire)), mutation);
        }
        var page = new WebPage(URL, "<p>body</p>", "text/html", "UTF-8", false);
        var response = response("web-1", WebOperation.FETCH, List.of(), page, null);
        assertEquals(response, decode(encode(response)));
        ObjectNode wire = (ObjectNode) JSON.readTree(encode(response));
        ((ObjectNode) wire.path("payload").path("page")).put("truncated", "false");
        assertThrows(IOException.class, () -> decode(JSON.writeValueAsBytes(wire)));
        assertThrows(IllegalArgumentException.class, () -> new WebPage(URL, "x".repeat(262_145), "text/html", "UTF-8", false));
        assertThrows(IllegalArgumentException.class, () -> request("web-1", WebOperation.FETCH, URL, 1));
        assertThrows(IllegalArgumentException.class, () -> response("web-1", WebOperation.CHECK_URL, List.of(), null, null));
        var mutable = new ArrayList<WebSearchResult>(); mutable.add(new WebSearchResult(1, "title", URL, "snippet", "fixture.invalid"));
        var copied = response("web-1", WebOperation.SEARCH, mutable, null, null); mutable.clear();
        assertEquals(1, copied.results().size()); assertThrows(UnsupportedOperationException.class, () -> copied.results().clear());
    }

    @Test void webFramesCannotInterleaveOrChangeOperationUrlOrReplayIdentifiers() throws Exception {
        var validator = ready(); var request = request("web-1", WebOperation.FETCH, URL, 0); validator.accept(request);
        assertThrows(IllegalStateException.class, () -> validator.accept(request("web-2", WebOperation.SEARCH, "query", 2)));
        assertThrows(IllegalStateException.class, () -> validator.accept(new WorkerComplete(header(Direction.WORKER_TO_COORDINATOR, FrameType.WORKER_COMPLETE, "", 0), "done")));
        assertThrows(IllegalStateException.class, () -> validator.accept(response("web-1", WebOperation.CHECK_URL, List.of(), null, "")));
        assertThrows(IllegalStateException.class, () -> validator.accept(response("web-2", WebOperation.FETCH, List.of(), new WebPage(URL, "body", "text/html", "UTF-8", false), null)));
        assertThrows(IllegalStateException.class, () -> validator.accept(response("web-1", WebOperation.FETCH, List.of(), new WebPage(URL + "/other", "body", "text/html", "UTF-8", false), null)));
        validator.accept(response("web-1", WebOperation.FETCH, List.of(), new WebPage(URL, "body", "text/html", "UTF-8", false), null));
        assertThrows(IllegalStateException.class, () -> validator.accept(request));
        validator.accept(request("web-3", WebOperation.SEARCH, "query", 1));
        var result = new WebSearchResult(1, "title", URL, "snippet", "fixture.invalid");
        assertThrows(IllegalStateException.class, () -> validator.accept(response("web-3", WebOperation.SEARCH, List.of(result, result), null, null)));
        validator.accept(response("web-3", WebOperation.SEARCH, List.of(result), null, null));
    }

    private static Validator ready() {
        var mock = new D4WebMock(new byte[32]);
        var client = new D4NativeWebTest.ScriptedClient(mock.definition(), D4NativeWebTest.Control.CORRECT);
        var start = D4WebRelayTest.start(client, mock.prompt()); var validator = new Validator(); validator.accept(start);
        validator.accept(new WorkerReady(header(Direction.WORKER_TO_COORDINATOR, FrameType.WORKER_READY, "", 0), start.capabilities()));
        return validator;
    }
    static Header header(Direction direction, FrameType type, String id, long sequence) { return new Header(direction, type, id, sequence); }
    static WebRequest request(String id, WebOperation operation, String input, int topK) {
        return new WebRequest(header(Direction.WORKER_TO_COORDINATOR, FrameType.WEB_REQUEST, id, 0), operation, input, topK);
    }
    static WebComplete response(String id, WebOperation operation, List<WebSearchResult> results, WebPage page, String denial) {
        return new WebComplete(header(Direction.COORDINATOR_TO_WORKER, FrameType.WEB_COMPLETE, id, 1), operation, results, page, denial);
    }
}
