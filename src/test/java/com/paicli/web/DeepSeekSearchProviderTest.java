package com.paicli.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DeepSeekSearchProviderTest {
    private MockWebServer server;
    private OkHttpClient client;

    @BeforeEach
    void setup() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new OkHttpClient.Builder()
                .callTimeout(3, TimeUnit.SECONDS)
                .addInterceptor(chain -> {
                    assertEquals("https://api.deepseek.com/anthropic/v1/messages",
                            chain.request().url().toString());
                    return chain.proceed(chain.request().newBuilder()
                            .url(server.url("/anthropic/v1/messages")).build());
                }).build();
    }

    @AfterEach
    void shutdown() throws IOException {
        server.shutdown();
    }

    private DeepSeekSearchProvider provider() {
        return new DeepSeekSearchProvider("test-key", null, client);
    }

    @Test
    void sendsNativeSearchRequestAndParsesOnlyStructuredUrls() throws Exception {
        server.enqueue(new MockResponse().setBody("""
                {"content":[
                  {"type":"text","text":"https://invented.example/answer","citations":[
                    {"url":"https://paicoding.com/article/1","cited_text":"Java 教程"},
                    {"url":"https://citation-only.example/","cited_text":"不可扩充来源"}
                  ]},
                  {"type":"web_search_tool_result","content":[
                    {"type":"web_search_result","url":"https://paicoding.com/article/1","title":"技术派"},
                    {"type":"web_search_result","url":"https://example.com/","title":"无摘要来源"}
                  ]}
                ]}
                """));
        var results = provider().search("Java 教程", 5);
        assertEquals(2, results.size());
        assertEquals(SearchResult.of(1, "技术派", "https://paicoding.com/article/1", "Java 教程"), results.get(0));
        assertEquals("", results.get(1).snippet());
        var request = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("POST", request.getMethod());
        assertEquals("/anthropic/v1/messages", request.getPath());
        assertEquals("test-key", request.getHeader("x-api-key"));
        assertEquals("Bearer test-key", request.getHeader("Authorization"));
        assertEquals("2023-06-01", request.getHeader("anthropic-version"));
        JsonNode body = new ObjectMapper().readTree(request.getBody().readUtf8());
        assertEquals("deepseek-flash", body.path("model").asText());
        assertEquals(4096, body.path("max_tokens").asInt());
        assertEquals(1, body.path("messages").size());
        assertEquals("user", body.at("/messages/0/role").asText());
        assertEquals("Perform a web search for the query: Java 教程", body.at("/messages/0/content/0/text").asText());
        assertEquals("web_search_20250305", body.at("/tools/0/type").asText());
        assertEquals("web_search", body.at("/tools/0/name").asText());
        assertEquals(5, body.at("/tools/0/max_uses").asInt());
    }

    @Test
    void deduplicatesAcrossBlocksFiltersInvalidUrlsAndLimitsResults() throws Exception {
        server.enqueue(new MockResponse().setBody("""
                {"content":[
                  {"type":"web_search_tool_result","content":[
                    {"type":"web_search_result","url":""},
                    {"type":"web_search_result","url":"file:///tmp/secret"},
                    {"type":"web_search_result","url":"not a URL"},
                    {"type":"text","url":"https://wrong-type.example/"},
                    {"type":"web_search_result","url":"https://example.com/1"}
                  ]},
                  {"type":"web_search_tool_result","content":[
                    {"type":"web_search_result","url":"https://example.com/1"},
                    {"type":"web_search_result","url":"https://example.com/2"},
                    {"type":"web_search_result","url":"https://example.com/3"}
                  ]},
                  {"type":"text","citations":[
                    {"url":"https://example.com/2","cited_text":"第二条摘要"}
                  ]}
                ]}
                """));
        var results = provider().search("test", 2);
        assertEquals(2, results.size());
        assertEquals("https://example.com/1", results.get(0).url());
        assertEquals(2, results.get(1).position());
        assertEquals("第二条摘要", results.get(1).snippet());
    }

    @Test
    void supportsSeparateSearchModelAndEmptySearchResults() throws Exception {
        server.enqueue(new MockResponse().setBody("""
                {"content":[{"type":"web_search_tool_result","content":[]}]}
                """));
        assertTrue(new DeepSeekSearchProvider("test-key", " deepseek-v4-flash ", client).search("test", 0).isEmpty());
        var request = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("deepseek-v4-flash", new ObjectMapper().readTree(request.getBody().readUtf8()).path("model").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "not-json", "{}", "{\"content\":{}}",
            "{\"content\":[{\"type\":\"text\",\"text\":\"https://invented.example\"}]}",
            "{\"content\":[{\"type\":\"web_search_tool_result\",\"content\":null}]}"})
    void rejectsMissingOrMalformedSearchResults(String response) {
        server.enqueue(new MockResponse().setBody(response));
        assertThrows(IOException.class, () -> provider().search("test", 5));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"type\":\"web_search_tool_result_error\",\"error_code\":\"max_uses_exceeded\"}",
            "[{\"type\":\"web_search_tool_result_error\",\"error_code\":\"unavailable\"}]"})
    void rejectsToolErrorsEvenAfterEnoughResults(String content) {
        server.enqueue(new MockResponse().setBody("""
                {"content":[
                  {"type":"web_search_tool_result","content":[{"type":"web_search_result","url":"https://example.com/"}]},
                  {"type":"web_search_tool_result","content":%s}
                ]}
                """.formatted(content)));
        IOException error = assertThrows(IOException.class, () -> provider().search("test", 1));
        assertTrue(error.getMessage().contains("工具执行失败"));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 408, 429, 500, 503})
    void reportsHttpErrorsWithoutLeakingBodyOrRetrying(int status) {
        server.enqueue(new MockResponse().setResponseCode(status).setHeader("Retry-After", "0").setBody("test-key"));
        server.enqueue(new MockResponse().setBody("""
                {"content":[{"type":"web_search_tool_result","content":[]}]}
                """));
        IOException error = assertThrows(IOException.class, () -> provider().search("test", 5));
        assertTrue(error.getMessage().contains(Integer.toString(status)));
        assertFalse(error.getMessage().contains("test-key"));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void refusesRedirects() throws Exception {
        try (MockWebServer destination = new MockWebServer()) {
            destination.start();
            server.enqueue(new MockResponse().setResponseCode(307).setHeader("Location", destination.url("/steal")));
            IOException error = assertThrows(IOException.class, () -> provider().search("test", 5));
            assertTrue(error.getMessage().contains("307"));
            assertEquals(0, destination.getRequestCount());
        }
    }

    @Test
    void rejectsMissingKeyAndBlankQueryBeforeMakingRequest() {
        assertFalse(new DeepSeekSearchProvider(null).isReady());
        assertFalse(new DeepSeekSearchProvider("  ").isReady());
        assertTrue(provider().isReady());
        assertEquals("deepseek", provider().name());
        IOException error = assertThrows(IOException.class,
                () -> new DeepSeekSearchProvider("", null, client).search("test", 5));
        assertTrue(error.getMessage().contains("DEEPSEEK_API_KEY"));
        assertThrows(IOException.class, () -> provider().search(null, 5));
        assertThrows(IOException.class, () -> provider().search("  ", 5));
        assertEquals(0, server.getRequestCount());
    }
}
