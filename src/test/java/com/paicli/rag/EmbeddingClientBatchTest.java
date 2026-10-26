package com.paicli.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddingClientBatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void ollamaSendsOneRequestPerBatchAndKeepsOrder() throws Exception {
        server.enqueue(json("{\"embeddings\":[[1,0],[2,0]]}"));
        EmbeddingClient client = new EmbeddingClient("ollama", "nomic", url(), "");

        List<float[]> vectors = client.embedAll(List.of("a", "", "b"));

        assertEquals(1, server.getRequestCount());
        RecordedRequest request = server.takeRequest();
        assertEquals("/api/embed", request.getPath());
        assertEquals("[\"a\",\"b\"]", MAPPER.readTree(request.getBody().readUtf8()).path("input").toString(),
                "空文本不发给接口");
        assertEquals(1f, vectors.get(0)[0]);
        assertEquals(0, vectors.get(1).length);
        assertEquals(2f, vectors.get(2)[0]);
    }

    @Test
    void oldOllamaWithoutBatchEndpointFallsBackToSingleCalls() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("404 page not found"));
        server.enqueue(json("{\"embedding\":[1,0]}"));
        server.enqueue(json("{\"embedding\":[2,0]}"));
        EmbeddingClient client = new EmbeddingClient("ollama", "nomic", url(), "");

        List<float[]> vectors = client.embedAll(List.of("a", "b"));

        assertEquals(2f, vectors.get(1)[0]);
        assertEquals("/api/embed", server.takeRequest().getPath());
        assertEquals("/api/embeddings", server.takeRequest().getPath());
    }

    @Test
    void missingOllamaModelIsReportedInsteadOfFallingBack() {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("{\"error\":\"model \\\"nomic\\\" not found\"}"));
        EmbeddingClient client = new EmbeddingClient("ollama", "nomic", url(), "");

        IOException error = assertThrows(IOException.class, () -> client.embedAll(List.of("a")));

        assertTrue(error.getMessage().contains("not found"));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void openAiCompatibleUsesArrayInputAndIndexOrder() throws Exception {
        server.enqueue(json("""
                {"data":[{"index":1,"embedding":[2,0]},{"index":0,"embedding":[1,0]}]}
                """));
        EmbeddingClient client = new EmbeddingClient("zhipu", "embedding-3", url(), "key");

        List<float[]> vectors = client.embedAll(List.of("a", "b"));

        JsonNode body = MAPPER.readTree(server.takeRequest().getBody().readUtf8());
        assertEquals("[\"a\",\"b\"]", body.path("input").toString());
        assertEquals(1f, vectors.get(0)[0], "按 index 放回原位");
        assertEquals(2f, vectors.get(1)[0]);
    }

    @Test
    void mismatchedCountIsAnError() {
        server.enqueue(json("{\"embeddings\":[[1,0]]}"));
        EmbeddingClient client = new EmbeddingClient("ollama", "nomic", url(), "");

        assertThrows(IOException.class, () -> client.embedAll(List.of("a", "b")));
    }

    @Test
    void batchSizeConfiguration() {
        assertEquals(EmbeddingClient.DEFAULT_BATCH_SIZE, EmbeddingClient.parseBatchSize(""));
        assertEquals(10, EmbeddingClient.parseBatchSize("10"));
        assertEquals(EmbeddingClient.DEFAULT_BATCH_SIZE, EmbeddingClient.parseBatchSize("0"));
        assertEquals(EmbeddingClient.DEFAULT_BATCH_SIZE, EmbeddingClient.parseBatchSize("abc"));
    }

    private String url() {
        String url = server.url("/").toString();
        return url.substring(0, url.length() - 1);
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
