package com.paicli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.PaiCliConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ApprovalClassifierClientTest {

    @Test
    void classifierPrefersCatalogFlashAndFallsBackWhenUnavailable() {
        assertEquals("deepseek-flash", ModelCatalog.approvalModel("deepseek", "deepseek-v4-pro"));
        assertEquals("glm-5.3-flash", ModelCatalog.approvalModel("glm", "glm-5.1"));
        assertEquals("kimi-k2.6", ModelCatalog.approvalModel("kimi", "kimi-k2.6"));
    }

    @Test
    void classifierRequestsDisableThinkingCapOutputAndSendNoTools() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"decision\\\":\\\"ask\\\"}\"},"
                            + "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"));
            PaiCliConfig config = new PaiCliConfig();
            config.getProviders().put("freellmapi", new PaiCliConfig.ProviderConfig(
                    "test-key", server.url("/v1").toString(), "auto"));

            LlmClient classifier = LlmClientFactory.createApprovalClassifier("freellmapi", null, config);
            assertNotNull(classifier);
            classifier.chat(List.of(LlmClient.Message.system("审查"), LlmClient.Message.user("{}")), null);

            JsonNode body = new ObjectMapper().readTree(server.takeRequest().getBody().readUtf8());
            assertEquals("disabled", body.path("thinking").path("type").asText());
            assertFalse(body.has("reasoning_effort"));
            assertEquals(400, body.path("max_tokens").asInt());
            assertFalse(body.has("tools"));
        }
    }
}
