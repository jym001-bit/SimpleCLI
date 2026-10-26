package com.paicli.llm;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ModelDiscoveryTest {
    @Test
    void fetchesModelsOnceFromConfiguredApiWithoutInference() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"data\":[{\"id\":\"vendor/new\"},{\"id\":\"vendor/new\"},{\"id\":\"next\"}]}"));
            var client = new FreeLlmApiClient("synthetic-key", "old", server.url("/v1").toString());
            assertEquals(List.of("vendor/new", "next"), new ModelDiscovery().discover(client));
            var request = server.takeRequest();
            assertEquals("/v1/models", request.getPath());
            assertEquals("GET", request.getMethod());
            assertEquals("Bearer synthetic-key", request.getHeader("Authorization"));
            assertEquals(0, request.getBodySize());
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test
    void doesNotFollowRedirectOrExposeProviderErrorBody() throws Exception {
        try (MockWebServer server = new MockWebServer(); MockWebServer destination = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", destination.url("/models"))
                    .setBody("secret-body"));
            IOException error = assertThrows(IOException.class, () -> new ModelDiscovery().fetch(server.url("/models"), "secret"));
            assertTrue(error.getMessage().contains("302"));
            assertFalse(error.getMessage().contains("secret"));
            assertEquals(0, destination.getRequestCount());
        }
    }

    @Test
    void rejectsInvalidAndOversizedResponsesAndDoesNotRetryErrors() throws Exception {
        for (String body : List.of("{}", "not json", "{\"data\":[{\"id\":\"bad\\nID\"}]}", "x".repeat(1_048_577))) {
            try (MockWebServer server = new MockWebServer()) {
                server.enqueue(new MockResponse().setBody(body));
                assertThrows(IOException.class, () -> new ModelDiscovery().fetch(server.url("/models"), "key"));
                assertEquals(1, server.getRequestCount());
            }
        }
        for (int status : List.of(401, 403, 404, 405, 429, 500)) {
            try (MockWebServer server = new MockWebServer()) {
                server.enqueue(new MockResponse().setResponseCode(status).setBody("sensitive"));
                IOException failure = assertThrows(IOException.class, () -> new ModelDiscovery().fetch(server.url("/models"), "key"));
                assertFalse(failure.getMessage().contains("sensitive"));
                assertEquals(1, server.getRequestCount());
            }
        }
    }
}
