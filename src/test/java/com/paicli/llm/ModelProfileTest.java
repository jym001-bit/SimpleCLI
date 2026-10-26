package com.paicli.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.PaiCliConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ModelProfileTest {
    private static MockResponse response(String content) {
        return new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":" + new com.fasterxml.jackson.databind.node.TextNode(content) + "}}]}\n\ndata: [DONE]\n\n");
    }

    @Test
    void templateKeepsDsmlAndThinkingWhileSendingNewModelIdAndImage() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(response("<｜｜DSML｜｜tool_calls><｜｜DSML｜｜invoke name=\"read_file\"><｜｜DSML｜｜parameter name=\"path\" string=\"true\">README.md</｜｜DSML｜｜parameter></｜｜DSML｜｜invoke></｜｜DSML｜｜tool_calls>"));
            var profile = new ModelProfile("deepseek-flash", 200_000, true, null, "high", true, null, 8192, "manual");
            LlmClient client = new DeepSeekClient("key", "deepseek-flash", server.url("/chat/completions").toString())
                    .withModelProfile("new-flash", profile);
            var result = client.chat(List.of(LlmClient.Message.assistant("retained thought", "previous answer"),
                    LlmClient.Message.user(List.of(LlmClient.ContentPart.imageBase64("aGVsbG8=", "image/png")))),
                    List.of(new LlmClient.Tool("read_file", "read", new ObjectMapper().readTree("{\"type\":\"object\"}"))));
            var body = new ObjectMapper().readTree(server.takeRequest().getBody().readUtf8());
            assertEquals("new-flash", body.path("model").asText());
            assertEquals("high", body.path("reasoning_effort").asText());
            assertEquals("enabled", body.path("thinking").path("type").asText());
            assertEquals(8192, body.path("max_tokens").asInt());
            assertEquals("retained thought", body.path("messages").get(0).path("reasoning_content").asText());
            assertEquals("image_url", body.path("messages").get(1).path("content").get(0).path("type").asText());
            assertEquals("read_file", result.toolCalls().get(0).function().name());
            assertEquals("new-flash", client.getModelName());
            assertEquals(200_000, client.maxContextWindow());
        }
    }

    @Test
    void explicitFalseOverridesTemplateVisionReasoningAndDsml() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            String dsml = "<｜｜DSML｜｜tool_calls><｜｜DSML｜｜invoke name=\"read_file\"></｜｜DSML｜｜invoke></｜｜DSML｜｜tool_calls>";
            server.enqueue(response(dsml));
            var profile = new ModelProfile("deepseek-flash", null, false, "disabled", null, false, false, null, "manual");
            LlmClient client = new DeepSeekClient("key", "deepseek-flash", server.url("/chat/completions").toString())
                    .withModelProfile("new-model", profile);
            var result = client.chat(List.of(LlmClient.Message.assistant("omit me", "old"),
                    LlmClient.Message.user(List.of(LlmClient.ContentPart.imageBase64("x", "image/png")))),
                    List.of(new LlmClient.Tool("read_file", "read", new ObjectMapper().createObjectNode())));
            var body = new ObjectMapper().readTree(server.takeRequest().getBody().readUtf8());
            assertFalse(body.has("reasoning_effort"));
            assertEquals("disabled", body.path("thinking").path("type").asText());
            assertFalse(body.path("messages").get(0).has("reasoning_content"));
            assertFalse(body.toString().contains("image_url"));
            assertFalse(client.supportsImageInput());
            assertFalse(result.hasToolCalls());
            assertEquals(dsml, result.content());
        }
    }

    @Test
    void factoryAppliesSavedProfileEvenWhenProviderUsesDefaultModel() {
        PaiCliConfig config = new PaiCliConfig() {
            @Override public String getModel(String provider) { return null; }
        };
        var provider = new PaiCliConfig.ProviderConfig("key", null, null);
        provider.getModels().put("deepseek-flash", new ModelProfile(null, 200_000, false, null, null, null, null, null, "manual"));
        config.getProviders().put("deepseek", provider);
        var client = LlmClientFactory.create("deepseek", config);
        assertEquals("deepseek-flash", client.getModelName());
        assertEquals(200_000, client.maxContextWindow());
        assertFalse(client.supportsImageInput());
    }
}
