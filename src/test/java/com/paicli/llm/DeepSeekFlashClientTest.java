package com.paicli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.PaiCliConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeepSeekFlashClientTest {
    @Test
    void factoryDefaultsToFlashWithoutOverridingExplicitModels() {
        PaiCliConfig config = mock(PaiCliConfig.class);
        when(config.getApiKey("deepseek")).thenReturn("test-key");
        LlmClient client = LlmClientFactory.create("deepseek", config);
        assertInstanceOf(DeepSeekClient.class, client);
        assertEquals("deepseek-flash", client.getModelName());
        assertTrue(client.supportsImageInput());
        assertEquals(1_000_000, client.maxContextWindow());

        when(config.getModel("deepseek")).thenReturn("deepseek-v4-pro");
        LlmClient pro = LlmClientFactory.create("deepseek", config);
        assertEquals("deepseek-v4-pro", pro.getModelName());
        assertFalse(pro.supportsImageInput());
    }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek-flash", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp"})
    void flashSendsImagesWithThinkingSettings(String model) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("""
                            data: {"model":"deepseek-flash","choices":[{"delta":{"content":"ok"}}],"usage":{"prompt_tokens":12,"completion_tokens":1}}

                            data: [DONE]

                            """));
            DeepSeekClient client = new DeepSeekClient("test-key", model,
                    server.url("/chat/completions").toString());
            LlmClient.ChatResponse response = client.chat(List.of(LlmClient.Message.user(List.of(
                    LlmClient.ContentPart.text("看图"),
                    LlmClient.ContentPart.imageBase64("aGVsbG8=", "image/png")
            ))), null);

            JsonNode request = new ObjectMapper().readTree(server.takeRequest().getBody().readUtf8());
            assertEquals(model, request.path("model").asText());
            assertEquals("enabled", request.path("thinking").path("type").asText());
            assertEquals("max", request.path("reasoning_effort").asText());
            JsonNode content = request.path("messages").get(0).path("content");
            assertEquals("看图", content.get(0).path("text").asText());
            assertEquals("image_url", content.get(1).path("type").asText());
            assertEquals("data:image/png;base64,aGVsbG8=", content.get(1).path("image_url").path("url").asText());
            assertEquals("deepseek-flash", response.resolvedModel());
            assertTrue(response.usagePresent());
            assertEquals("ok", response.content());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek-v4-pro", "deepseek-chat", "deepseek-reasoner", "deepseek-custom"})
    void otherModelsDoNotClaimVisionSupport(String model) {
        assertFalse(new DeepSeekClient("test-key", model).supportsImageInput());
    }
}
