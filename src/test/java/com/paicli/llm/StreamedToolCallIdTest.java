package com.paicli.llm;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamedToolCallIdTest {

    @Test
    void toolCallWithoutIdGetsLocalIdInsteadOfBeingDropped() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("""
                            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"list_dir","arguments":"{\\"path\\""}}]}}]}

                            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":":\\".\\"}"}}]},"finish_reason":"tool_calls"}]}

                            data: [DONE]

                            """));
            DeepSeekClient client = new DeepSeekClient("test-key", "deepseek-v4-pro",
                    server.url("/chat/completions").toString());

            LlmClient.ChatResponse response = client.chat(List.of(LlmClient.Message.user("列目录")), null);

            assertTrue(response.hasToolCalls());
            assertEquals(1, response.toolCalls().size());
            assertEquals("call_0", response.toolCalls().get(0).id());
            assertEquals("list_dir", response.toolCalls().get(0).function().name());
            assertEquals("{\"path\":\".\"}", response.toolCalls().get(0).function().arguments());
        }
    }

    @Test
    void providerIdIsKeptWhenPresent() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("""
                            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_abc","function":{"name":"read_file","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}

                            data: [DONE]

                            """));
            DeepSeekClient client = new DeepSeekClient("test-key", "deepseek-v4-pro",
                    server.url("/chat/completions").toString());

            LlmClient.ChatResponse response = client.chat(List.of(LlmClient.Message.user("读")), null);

            assertEquals("call_abc", response.toolCalls().get(0).id());
        }
    }
}
