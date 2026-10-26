package com.paicli.llm;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekUnexecutedToolCallTest {

    private static final String DSML_BLOCK = """
            <｜｜DSML｜｜ calls>
            <｜｜DSML｜｜ invoke name="list_dir">
            <｜｜DSML｜｜ parameter name="path" string="true">demo</｜｜DSML｜｜ parameter>
            </｜｜DSML｜｜ invoke>
            </｜｜DSML｜｜ calls>""";

    @Test
    void flagsDsmlThatWasNotConvertedToToolCalls() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: " + contentDelta(DSML_BLOCK) + "\n\ndata: [DONE]\n\n"));
            DeepSeekClient client = new DeepSeekClient("test-key", "deepseek-flash",
                    server.url("/chat/completions").toString());

            // 没有开放任何工具时，DSML 不会被转换，原样留在正文里
            LlmClient.ChatResponse response = client.chat(List.of(LlmClient.Message.user("列目录")), null);

            assertFalse(response.hasToolCalls());
            assertTrue(client.looksLikeUnexecutedToolCall(response.content()));
            assertFalse(client.looksLikeUnexecutedToolCall("普通回答，没有调用"));
            assertFalse(client.looksLikeUnexecutedToolCall(null));
        }
    }

    private static String contentDelta(String content) {
        String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return "{\"choices\":[{\"delta\":{\"content\":\"" + escaped + "\"},\"finish_reason\":\"stop\"}]}";
    }
}
