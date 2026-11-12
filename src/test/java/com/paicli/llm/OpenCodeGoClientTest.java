package com.paicli.llm;

import com.paicli.config.PaiCliConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OpenCodeGoClientTest {
    @Test void factoryUsesIndependentProviderAndDefaultModel() {
        PaiCliConfig config = new PaiCliConfig();
        config.getProviders().put("opencode-go", new PaiCliConfig.ProviderConfig("test-key", null, null));
        LlmClient client = LlmClientFactory.create("opencode-go", config);
        assertEquals("opencode-go", client.getProviderName());
        assertEquals("deepseek-v4.1-flash", client.getModelName());
        assertTrue(client.supportsImageInput());
        assertEquals(1_000_000, client.maxContextWindow());
        assertTrue(ModelCatalog.entries(config, "opencode-go").containsKey("deepseek-v4-pro"));
    }

    @Test void sendsGoHeadersAndKeepsAffinityAcrossRequests() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            OpenCodeGoClient client = new OpenCodeGoClient("test-key", null, server.url("/v1/").toString());
            String session = null;
            for (int i = 0; i < 2; i++) {
                server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
                        .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"));
                client.chat(List.of(), List.of());
                var request = server.takeRequest();
                assertEquals("/v1/chat/completions", request.getPath());
                assertEquals("Bearer test-key", request.getHeader("Authorization"));
                assertEquals("SimpleCLI/16.1.0", request.getHeader("User-Agent"));
                String current = request.getHeader("x-opencode-session");
                assertNotNull(current);
                if (session != null) assertEquals(session, current);
                session = current;
                assertTrue(request.getBody().readUtf8().contains("deepseek-v4.1-flash"));
            }
        }
    }
}
