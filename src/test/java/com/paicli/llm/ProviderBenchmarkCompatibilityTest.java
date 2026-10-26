package com.paicli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderBenchmarkCompatibilityTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void hunyuanDefaultsToHy4PreviewOnTokenHub() {
        HunyuanClient client = new HunyuanClient("test-key");

        assertEquals("hy4-preview", client.getModelName());
        assertEquals("https://tokenhub.tencentmaas.com/v1/chat/completions", client.getApiUrl());
        assertEquals("hunyuan", client.getProviderName());
    }

    @Test
    void completeUsageRequiresBothNonNegativeIntegralTokenCounters() throws Exception {
        assertTrue(AbstractOpenAiCompatibleClient.hasCompleteTokenUsage(
                MAPPER.readTree("{\"prompt_tokens\":0,\"completion_tokens\":7}")));

        for (String invalid : List.of(
                "{}",
                "{\"prompt_tokens\":1}",
                "{\"completion_tokens\":1}",
                "{\"prompt_tokens\":\"1\",\"completion_tokens\":2}",
                "{\"prompt_tokens\":1,\"completion_tokens\":2.0}",
                "{\"prompt_tokens\":-1,\"completion_tokens\":2}",
                "{\"prompt_tokens\":2147483648,\"completion_tokens\":2}")) {
            assertFalse(AbstractOpenAiCompatibleClient.hasCompleteTokenUsage(
                    MAPPER.readTree(invalid)), invalid);
        }
    }

    @Test
    void malformedSseUsageNeverBecomesProviderUsageEvidence() throws Exception {
        for (String usage : List.of(
                "{}",
                "{\"prompt_tokens\":1}",
                "{\"completion_tokens\":1}",
                "{\"prompt_tokens\":\"1\",\"completion_tokens\":2}",
                "{\"prompt_tokens\":1,\"completion_tokens\":2.0}")) {
            try (MockWebServer server = new MockWebServer()) {
                server.enqueue(new MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody("data: {\"model\":\"deepseek-v4-flash\","
                                + "\"choices\":[{\"delta\":{\"role\":\"assistant\","
                                + "\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
                                + "\"usage\":" + usage + "}\n\ndata: [DONE]\n\n"));
                DeepSeekClient client = new DeepSeekClient(
                        "test-key", "deepseek-v4-flash",
                        server.url("/chat/completions").toString());

                LlmClient.ChatResponse response = client.chat(
                        List.of(LlmClient.Message.user("hello")), List.of());

                assertFalse(response.usagePresent(), usage);
                assertEquals(0, response.inputTokens(), usage);
                assertEquals(0, response.outputTokens(), usage);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek-flash", "deepseek-v4-flash", "deepseek-v4-pro"})
    void deepSeekV4UsesHighReasoningBenchmarkSettings(String model) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            enqueueTextResponse(server);
            DeepSeekClient client = new DeepSeekClient(
                    "test-key", model, server.url("/chat/completions").toString());

            client.chat(List.of(LlmClient.Message.user("你好")), null);

            JsonNode root = requestBody(server.takeRequest());
            assertEquals(model, root.path("model").asText());
            assertEquals(1.0, root.path("temperature").asDouble());
            assertEquals(0.95, root.path("top_p").asDouble());
            assertEquals("max", root.path("reasoning_effort").asText());
            assertEquals("enabled", root.path("thinking").path("type").asText());
            assertFalse(root.has("max_tokens"));
            assertEquals(1_000_000, client.maxContextWindow());
        }
    }

    @Test
    void olderDeepSeekModelDoesNotReceiveV4OnlyBenchmarkSettings() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            enqueueTextResponse(server);
            DeepSeekClient client = new DeepSeekClient(
                    "test-key", "deepseek-chat", server.url("/chat/completions").toString());

            client.chat(List.of(LlmClient.Message.user("你好")), null);

            JsonNode root = requestBody(server.takeRequest());
            assertFalse(root.has("temperature"));
            assertFalse(root.has("top_p"));
            assertFalse(root.has("reasoning_effort"));
            assertFalse(root.has("thinking"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek-flash", "deepseek-v4-flash", "deepseek-v4-pro"})
    void deepSeekV4StreamsOrdinaryMultiDeltaContentExactlyOnce(String model) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            List<String> contentDeltas = List.of(
                    "第一段普通正文会立即到达监听器。",
                    "第二段仍然保持流式，不等待响应结束。",
                    "第三段只在末尾完成剩余内容。");
            enqueueAssistantDeltas(server, List.of(), contentDeltas);
            DeepSeekClient client = new DeepSeekClient(
                    "test-key", model, server.url("/chat/completions").toString());
            List<String> streamedDeltas = new ArrayList<>();

            LlmClient.ChatResponse response = client.chat(
                    List.of(LlmClient.Message.user("介绍当前项目")),
                    List.of(readFileTool()),
                    new LlmClient.StreamListener() {
                        @Override
                        public void onContentDelta(String delta) {
                            streamedDeltas.add(delta);
                        }
                    });

            assertEquals(contentDeltas, streamedDeltas,
                    "ordinary content must not collapse into one completion-time replay");
            assertEquals(String.join("", contentDeltas), response.content());
            assertEquals(response.content(), String.join("", streamedDeltas));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek-flash", "deepseek-v4-flash", "deepseek-v4-pro"})
    void deepSeekV4ConvertsExposedDsmlToolCallAndCleansContent(String model) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            List<String> dsmlDeltas = List.of(
                    "准备检索。\n<｜｜DS",
                    "ML｜｜tool_",
                    "calls>\n<｜｜DSML｜｜invoke name=\"list_dir\">\n"
                            + "<｜｜DSML｜｜parameter name=\"path\" string=\"true\">.</｜｜DSML｜｜parameter>\n"
                            + "</｜｜DSML｜｜invoke>\n"
                            + "<｜｜DSML｜｜invoke name=\"glob_files\">\n"
                            + "<｜｜DSML｜｜parameter name=\"pattern\" string=\"true\">**/*.java</｜｜DSML｜｜parameter>\n"
                            + "<｜｜DSML｜｜parameter name=\"max_results\" string=\"false\">25</｜｜DSML｜｜parameter>\n"
                            + "</｜｜DSML｜｜invoke>\n"
                            + "</｜｜DSML｜｜tool_ca",
                    "lls>继续等待。");
            String dsmlContent = String.join("", dsmlDeltas);
            List<String> reasoningDeltas = List.of("先分析任务。", "再调用工具。");
            enqueueAssistantDeltas(server, reasoningDeltas, dsmlDeltas);
            DeepSeekClient client = new DeepSeekClient(
                    "test-key", model, server.url("/chat/completions").toString());
            StringBuilder streamedContent = new StringBuilder();
            List<String> streamedReasoning = new ArrayList<>();

            LlmClient.ChatResponse response = client.chat(
                    List.of(LlmClient.Message.user("查找 Java 文件")),
                    List.of(listDirTool(), globFilesTool()),
                    new LlmClient.StreamListener() {
                        @Override
                        public void onReasoningDelta(String delta) {
                            streamedReasoning.add(delta);
                        }

                        @Override
                        public void onContentDelta(String delta) {
                            streamedContent.append(delta);
                        }
                    });

            assertTrue(response.hasToolCalls());
            assertEquals(2, response.toolCalls().size());
            assertEquals("deepseek-v4-flash", response.resolvedModel());
            assertTrue(response.usagePresent());
            assertEquals("dsml_call_1", response.toolCalls().get(0).id());
            assertEquals("list_dir", response.toolCalls().get(0).function().name());
            assertEquals(".",
                    MAPPER.readTree(response.toolCalls().get(0).function().arguments()).path("path").asText());
            assertEquals("dsml_call_2", response.toolCalls().get(1).id());
            assertEquals("glob_files", response.toolCalls().get(1).function().name());
            JsonNode arguments = MAPPER.readTree(response.toolCalls().get(1).function().arguments());
            assertEquals("**/*.java", arguments.path("pattern").asText());
            assertEquals(25, arguments.path("max_results").asInt());
            assertTrue(arguments.path("max_results").isIntegralNumber());
            assertEquals("准备检索。\n继续等待。", response.content());
            assertFalse(response.content().contains("｜｜DSML｜｜"));
            assertEquals(response.content(), streamedContent.toString());
            assertFalse(streamedContent.toString().contains("｜｜DSML｜｜"));
            assertEquals(reasoningDeltas, streamedReasoning);
            assertEquals(String.join("", reasoningDeltas), response.reasoningContent());
            assertEquals(dsmlContent.replaceFirst(
                    "(?s)<｜｜DSML｜｜tool_calls>.*?</｜｜DSML｜｜tool_calls>", ""),
                    streamedContent.toString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek-flash", "deepseek-v4-flash", "deepseek-v4-pro"})
    void deepSeekV4RejectsDsmlJsonWithTrailingTokens(String model) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            String dsmlContent = """
                    <｜｜DSML｜｜tool_calls>
                    <｜｜DSML｜｜invoke name="glob_files">
                    <｜｜DSML｜｜parameter name="max_results" string="false">25 26</｜｜DSML｜｜parameter>
                    </｜｜DSML｜｜invoke>
                    </｜｜DSML｜｜tool_calls>""";
            enqueueAssistantContent(server, dsmlContent);
            DeepSeekClient client = new DeepSeekClient(
                    "test-key", model, server.url("/chat/completions").toString());
            List<String> streamedDeltas = new ArrayList<>();

            LlmClient.ChatResponse response = client.chat(
                    List.of(LlmClient.Message.user("查找 Java 文件")),
                    List.of(globFilesTool()),
                    new LlmClient.StreamListener() {
                        @Override
                        public void onContentDelta(String delta) {
                            streamedDeltas.add(delta);
                        }
                    });

            assertFalse(response.hasToolCalls());
            assertEquals(dsmlContent, response.content());
            assertEquals(List.of(dsmlContent), streamedDeltas,
                    "malformed DSML must be replayed unchanged and exactly once");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek-flash", "deepseek-v4-flash", "deepseek-v4-pro"})
    void deepSeekV4KeepsUnknownDsmlToolAsPlainText(String model) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            String unknownBlock = """
                    <｜｜DSML｜｜tool_calls>
                    <｜｜DSML｜｜invoke name="execute_command">
                    <｜｜DSML｜｜parameter name="command" string="true">pwd</｜｜DSML｜｜parameter>
                    </｜｜DSML｜｜invoke>
                    </｜｜DSML｜｜tool_calls>""";
            String dsmlContent = "安全前缀。\n" + unknownBlock + "\n安全后缀。";
            enqueueAssistantContent(server, dsmlContent);
            DeepSeekClient client = new DeepSeekClient(
                    "test-key", model, server.url("/chat/completions").toString());
            List<String> streamedDeltas = new ArrayList<>();

            LlmClient.ChatResponse response = client.chat(
                    List.of(LlmClient.Message.user("读取项目文件")),
                    List.of(readFileTool()),
                    new LlmClient.StreamListener() {
                        @Override
                        public void onContentDelta(String delta) {
                            streamedDeltas.add(delta);
                        }
                    });

            assertFalse(response.hasToolCalls());
            assertEquals(dsmlContent, response.content());
            assertEquals(dsmlContent, String.join("", streamedDeltas),
                    "unknown tools must stay visible in their original order without duplication");
            assertEquals(2, streamedDeltas.size(),
                    "safe prefix should stream before the rejected DSML tail is replayed");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek-flash", "deepseek-v4-flash", "deepseek-v4-pro"})
    void deepSeekV4LeavesNativeToolCallsUnchanged(String model) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            enqueueNativeToolCallResponse(server);
            DeepSeekClient client = new DeepSeekClient(
                    "test-key", model, server.url("/chat/completions").toString());
            List<String> streamedDeltas = new ArrayList<>();

            LlmClient.ChatResponse response = client.chat(
                    List.of(LlmClient.Message.user("读取 README")),
                    List.of(readFileTool()),
                    new LlmClient.StreamListener() {
                        @Override
                        public void onContentDelta(String delta) {
                            streamedDeltas.add(delta);
                        }
                    });

            assertTrue(response.hasToolCalls());
            assertEquals(1, response.toolCalls().size());
            assertEquals("call_native", response.toolCalls().get(0).id());
            assertEquals("read_file", response.toolCalls().get(0).function().name());
            assertEquals("README.md",
                    MAPPER.readTree(response.toolCalls().get(0).function().arguments()).path("path").asText());
            assertEquals("使用原生工具调用。", response.content());
            assertEquals(List.of("使用原生工具调用。"), streamedDeltas);
        }
    }

    @Test
    void glm53UsesOneMillionContextAndKeepsThinkingAcrossToolTurns() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            enqueueTextResponse(server);
            GLMClient client = new GLMClient(
                    "test-key", "glm-5.3-flash", server.url("/chat/completions").toString());

            client.chat(List.of(thinkingToolCallMessage()), List.of(readFileTool()));

            JsonNode root = requestBody(server.takeRequest());
            JsonNode assistant = root.path("messages").get(0);
            assertEquals("glm-5.3-flash", root.path("model").asText());
            assertEquals(1.0, root.path("temperature").asDouble());
            assertEquals(0.95, root.path("top_p").asDouble());
            assertEquals("max", root.path("reasoning_effort").asText());
            assertEquals("enabled", root.path("thinking").path("type").asText());
            assertFalse(root.path("thinking").path("clear_thinking").asBoolean(true));
            assertTrue(root.path("tool_stream").asBoolean());
            assertTrue(root.path("stream_options").path("include_usage").asBoolean());
            assertFalse(root.has("max_tokens"));
            assertEquals("hidden reasoning", assistant.path("reasoning_content").asText());
            assertEquals("call_1", assistant.path("tool_calls").get(0).path("id").asText());
            assertTrue(root.path("tools").isArray());
            assertEquals(1_000_000, client.maxContextWindow());
            assertTrue(client.supportsImageInput());
        }
    }

    @Test
    void olderGlmModelKeepsExistingRequestAndContextBehavior() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            enqueueTextResponse(server);
            GLMClient client = new GLMClient(
                    "test-key", "glm-5.1", server.url("/chat/completions").toString());

            client.chat(List.of(thinkingToolCallMessage()), null);

            JsonNode root = requestBody(server.takeRequest());
            assertFalse(root.has("temperature"));
            assertFalse(root.has("top_p"));
            assertFalse(root.has("reasoning_effort"));
            assertFalse(root.has("thinking"));
            assertFalse(root.has("tool_stream"));
            assertFalse(root.has("stream_options"));
            assertFalse(root.path("messages").get(0).has("reasoning_content"));
            assertEquals(200_000, client.maxContextWindow());
            assertTrue(client.supportsImageInput());
        }
    }

    @Test
    void hunyuanUsesTokenHubProtocolAndParsesToolUsageStream() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("""
                            data: {"model":"hy4-preview","choices":[{"delta":{"role":"assistant","reasoning_content":"先读文件。","tool_calls":[{"index":0,"id":"call_remote","function":{"name":"read_file","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}

                            data: {"model":"hy4-preview","choices":[],"usage":{"prompt_tokens":21,"completion_tokens":7,"prompt_tokens_details":{"cached_tokens":5}}}

                            data: [DONE]

                            """));
            HunyuanClient client = new HunyuanClient(
                    "test-key", "hy4-preview", server.url("/v1").toString());

            LlmClient.ChatResponse response = client.chat(
                    List.of(thinkingToolCallMessage()), List.of(readFileTool()));

            RecordedRequest request = server.takeRequest();
            JsonNode root = requestBody(request);
            JsonNode assistant = root.path("messages").get(0);
            assertEquals("Bearer test-key", request.getHeader("Authorization"));
            assertEquals("hy4-preview", root.path("model").asText());
            assertEquals(0.9, root.path("temperature").asDouble());
            assertEquals("high", root.path("reasoning_effort").asText());
            assertEquals("enabled", root.path("thinking").path("type").asText());
            assertTrue(root.path("stream_options").path("include_usage").asBoolean());
            assertFalse(root.has("max_tokens"));
            assertEquals("hidden reasoning", assistant.path("reasoning_content").asText());
            assertEquals("call_1", assistant.path("tool_calls").get(0).path("id").asText());
            assertTrue(root.path("tools").isArray());
            assertEquals("先读文件。", response.reasoningContent());
            assertEquals("call_remote", response.toolCalls().get(0).id());
            assertEquals("read_file", response.toolCalls().get(0).function().name());
            assertEquals(21, response.inputTokens());
            assertEquals(7, response.outputTokens());
            assertEquals(5, response.cachedInputTokens());
            assertEquals("hy4-preview", response.resolvedModel());
            assertTrue(response.usagePresent());
            assertEquals(1_000_000, client.maxContextWindow());
            assertFalse(client.supportsImageInput());
            assertTrue(client.supportsPromptCaching());
            assertEquals("hunyuan-prefix-cache", client.promptCacheMode());
        }
    }

    @Test
    void benchmarkConstructorsFreezeTheSameMaximumOutputTokens() throws Exception {
        try (MockWebServer deepSeekServer = new MockWebServer();
             MockWebServer glmServer = new MockWebServer();
             MockWebServer hunyuanServer = new MockWebServer()) {
            enqueueTextResponse(deepSeekServer);
            enqueueTextResponse(glmServer);
            enqueueTextResponse(hunyuanServer);

            DeepSeekClient deepSeek = new DeepSeekClient(
                    "test-key", "deepseek-v4-flash",
                    deepSeekServer.url("/chat/completions").toString(), 16_384);
            GLMClient glm = new GLMClient(
                    "test-key", "glm-5.3-flash",
                    glmServer.url("/chat/completions").toString(), 16_384);
            HunyuanClient hunyuan = new HunyuanClient(
                    "test-key", "hy4-preview", hunyuanServer.url("/v1").toString(), 16_384);

            deepSeek.chat(List.of(LlmClient.Message.user("test")), List.of());
            glm.chat(List.of(LlmClient.Message.user("test")), List.of());
            hunyuan.chat(List.of(LlmClient.Message.user("test")), List.of());

            assertEquals(16_384,
                    requestBody(deepSeekServer.takeRequest()).path("max_tokens").asInt());
            assertEquals(16_384,
                    requestBody(glmServer.takeRequest()).path("max_tokens").asInt());
            JsonNode hunyuanRequest = requestBody(hunyuanServer.takeRequest());
            assertEquals(16_384, hunyuanRequest.path("max_tokens").asInt());
            assertEquals(0.9, hunyuanRequest.path("temperature").asDouble());
            assertEquals("enabled", hunyuanRequest.path("thinking").path("type").asText());
            assertEquals("high", hunyuanRequest.path("reasoning_effort").asText());
            assertTrue(hunyuanRequest.path("stream_options").path("include_usage").asBoolean());
        }
    }

    @Test
    void rejectsMixedResolvedModelsWithinOneSseResponse() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("""
                            data: {"model":"model-a","choices":[{"delta":{"role":"assistant","content":"ok"}}]}

                            data: {"model":"model-b","choices":[{"delta":{},"finish_reason":"stop"}]}

                            data: [DONE]

                            """));
            DeepSeekClient client = new DeepSeekClient(
                    "test-key", "deepseek-chat", server.url("/chat/completions").toString());

            LlmClient.ChatResponse response = client.chat(
                    List.of(LlmClient.Message.user("hello")), List.of());

            assertEquals(null, response.resolvedModel());
            assertFalse(response.usagePresent());
        }
    }

    private static LlmClient.Message thinkingToolCallMessage() {
        return LlmClient.Message.assistant(
                "hidden reasoning",
                "",
                List.of(new LlmClient.ToolCall(
                        "call_1",
                        new LlmClient.ToolCall.Function("read_file", "{\"path\":\"README.md\"}"))));
    }

    private static LlmClient.Tool readFileTool() {
        return new LlmClient.Tool(
                "read_file",
                "Read a file",
                MAPPER.createObjectNode().put("type", "object"));
    }

    private static LlmClient.Tool globFilesTool() {
        return new LlmClient.Tool(
                "glob_files",
                "Find files by glob pattern",
                MAPPER.createObjectNode().put("type", "object"));
    }

    private static LlmClient.Tool listDirTool() {
        return new LlmClient.Tool(
                "list_dir",
                "List directory entries",
                MAPPER.createObjectNode().put("type", "object"));
    }

    private static void enqueueAssistantContent(MockWebServer server, String content) throws Exception {
        enqueueAssistantDeltas(server, List.of(), List.of(content));
    }

    private static void enqueueAssistantDeltas(MockWebServer server,
                                               List<String> reasoningDeltas,
                                               List<String> contentDeltas) throws Exception {
        StringBuilder body = new StringBuilder();
        boolean includeRole = true;
        for (String reasoning : reasoningDeltas) {
            var event = MAPPER.createObjectNode();
            var delta = event.putArray("choices").addObject().putObject("delta");
            if (includeRole) {
                delta.put("role", "assistant");
                includeRole = false;
            }
            delta.put("reasoning_content", reasoning);
            body.append("data: ").append(MAPPER.writeValueAsString(event)).append("\n\n");
        }
        for (String content : contentDeltas) {
            var event = MAPPER.createObjectNode();
            var delta = event.putArray("choices").addObject().putObject("delta");
            if (includeRole) {
                delta.put("role", "assistant");
                includeRole = false;
            }
            delta.put("content", content);
            body.append("data: ").append(MAPPER.writeValueAsString(event)).append("\n\n");
        }
        var event = MAPPER.createObjectNode();
        event.put("model", "deepseek-v4-flash");
        event.putObject("usage")
                .put("prompt_tokens", 12)
                .put("completion_tokens", 3);
        var choice = event.putArray("choices").addObject();
        choice.putObject("delta");
        choice.put("finish_reason", "stop");
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body + "data: " + MAPPER.writeValueAsString(event) + "\n\ndata: [DONE]\n\n"));
    }

    private static void enqueueNativeToolCallResponse(MockWebServer server) throws Exception {
        var event = MAPPER.createObjectNode();
        event.put("model", "deepseek-v4-flash");
        event.putObject("usage")
                .put("prompt_tokens", 12)
                .put("completion_tokens", 3);
        var choice = event.putArray("choices").addObject();
        var delta = choice.putObject("delta");
        delta.put("role", "assistant");
        delta.put("content", "使用原生工具调用。");
        var toolCall = delta.putArray("tool_calls").addObject();
        toolCall.put("index", 0);
        toolCall.put("id", "call_native");
        toolCall.putObject("function")
                .put("name", "read_file")
                .put("arguments", "{\"path\":\"README.md\"}");
        choice.put("finish_reason", "tool_calls");
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: " + MAPPER.writeValueAsString(event) + "\n\ndata: [DONE]\n\n"));
    }

    private static void enqueueTextResponse(MockWebServer server) {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        data: {"choices":[{"delta":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":1}}

                        data: [DONE]

                        """));
    }

    private static JsonNode requestBody(RecordedRequest request) throws Exception {
        return MAPPER.readTree(request.getBody().readUtf8());
    }
}
