package com.paicli.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.agent.Agent;
import com.paicli.tool.ToolRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DeepSeekConversationReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SOURCE = "public class Hello {\n"
            + "    public static void main(String[] args) {\n"
            + "        System.out.println(\"Hello World\");\n    }\n}\n";
    // Same tag spelling, spacing, tool name, and multiline arguments as the incident ledger.
    private static final String BLOCK = """
            <｜｜DSML｜｜ calls>
            <｜｜DSML｜｜ invoke name="write_file">
            <｜｜DSML｜｜ parameter name="path" string="true">Hello.java</｜｜DSML｜｜ parameter>
            <｜｜DSML｜｜ parameter name="content" string="true">%s</｜｜DSML｜｜ parameter>
            </｜｜DSML｜｜ invoke>
            </｜｜DSML｜｜ calls>""".formatted(SOURCE);

    @Test
    void directoryCompileInstructionExposesToolsAndExecutesDsml(@TempDir Path project) throws Exception {
        Files.createDirectory(project.resolve("demo"));
        Files.writeString(project.resolve("demo/Hello.java"), SOURCE);
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(content("""
                    <｜｜DSML｜｜ calls>
                    <｜｜DSML｜｜ invoke name="list_dir">
                    <｜｜DSML｜｜ parameter name="path" string="true">demo</｜｜DSML｜｜ parameter>
                    </｜｜DSML｜｜ invoke>
                    </｜｜DSML｜｜ calls>"""));
            server.enqueue(content("已读取目录。"));
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(project.toString());
            Agent agent = new Agent(new DeepSeekClient("test-key", "deepseek-flash",
                    server.url("/chat/completions").toString()), registry);

            agent.run("进入 demo 目录，编译并运行 Hello.java");

            var firstRequest = JSON.readTree(server.takeRequest().getBody().readUtf8());
            assertTrue(firstRequest.path("tools").toString().contains("list_dir"));
            assertTrue(firstRequest.path("tools").toString().contains("execute_command"));
            var secondRequest = JSON.readTree(server.takeRequest().getBody().readUtf8());
            boolean foundDirectoryResult = false;
            for (var message : secondRequest.path("messages")) {
                if (message.path("role").asText().equals("tool")) {
                    String result = message.path("content").asText();
                    foundDirectoryResult |= result.contains("Hello.java") && result.contains("tool_result");
                }
            }
            assertTrue(foundDirectoryResult, "DSML must execute list_dir and return the actual directory result");
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test
    void numberedReplyExposesToolsAndReplaysCreateThenEdit(@TempDir Path project) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(content("没有找到 Hello.java。\n1. **需要我先创建**：先写 Hello World，再修改。\n2. 文件在别处。"));
            server.enqueue(content("准备创建。\n" + BLOCK));
            Map<String, Object> editCall = Map.of("index", 0, "id", "edit_1", "type", "function",
                    "function", Map.of("name", "edit_file", "arguments", JSON.writeValueAsString(Map.of(
                            "path", "Hello.java", "old_text", "Hello World", "new_text", "Hello PaiCLI"))));
            String editDelta = JSON.writeValueAsString(Map.of("choices", List.of(Map.of(
                    "delta", Map.of("tool_calls", List.of(editCall))))));
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                    "data: " + editDelta + "\n\ndata: [DONE]\n\n"));
            server.enqueue(content("已创建并修改。"));
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(project.toString());
            Agent agent = new Agent(new DeepSeekClient("test-key", "deepseek-flash",
                    server.url("/chat/completions").toString()), registry);

            agent.run("把 Hello.java 里的 Hello World 改成 Hello PaiCLI");
            assertFalse(Files.exists(project.resolve("Hello.java")));
            agent.run("1");

            assertEquals(SOURCE.replace("Hello World", "Hello PaiCLI"), Files.readString(project.resolve("Hello.java")));
            server.takeRequest();
            var followUp = JSON.readTree(server.takeRequest().getBody().readUtf8());
            assertTrue(followUp.path("tools").toString().contains("write_file"));
            var afterWrite = JSON.readTree(server.takeRequest().getBody().readUtf8());
            assertTrue(afterWrite.path("messages").toString().contains("dsml_call_"));
            assertTrue(afterWrite.path("messages").toString().contains("tool_result"));
            assertEquals(4, server.getRequestCount());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"tool_calls", "calls", " calls"})
    void splitTagsStayHiddenAndMultilineContentSurvives(String container) throws Exception {
        String block = BLOCK.replace(" calls>", container + ">");
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(content("准备。\n" + block + "\n等待。"));
            StringBuilder streamed = new StringBuilder();
            var response = new DeepSeekClient("test-key", "deepseek-flash", server.url("/").toString())
                    .chat(List.of(LlmClient.Message.user("创建文件")), writeTools(), new LlmClient.StreamListener() {
                        @Override public void onContentDelta(String delta) { streamed.append(delta); }
                    });
            assertTrue(response.hasToolCalls());
            assertEquals("write_file", response.toolCalls().get(0).function().name());
            assertEquals(SOURCE, JSON.readTree(response.toolCalls().get(0).function().arguments()).path("content").asText());
            assertEquals("准备。\n\n等待。", streamed.toString());
            assertEquals(streamed.toString(), response.content());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-tools", "unknown", "mismatched", "multiple", "duplicate-parameter", "malformed-json"})
    void invalidOrUnexposedCallsRemainText(String scenario) throws Exception {
        String block = switch (scenario) {
            case "unknown" -> BLOCK.replace("write_file", "writefile");
            case "mismatched" -> BLOCK.replace("</｜｜DSML｜｜ calls>", "</｜｜DSML｜｜ tool_calls>");
            case "multiple" -> BLOCK + BLOCK;
            case "duplicate-parameter" -> BLOCK.replace("name=\"content\"", "name=\"path\"");
            case "malformed-json" -> BLOCK.replace("string=\"true\"", "string=\"false\"");
            default -> BLOCK;
        };
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(content(block));
            StringBuilder streamed = new StringBuilder();
            var response = new DeepSeekClient("test-key", "deepseek-flash", server.url("/").toString())
                    .chat(List.of(LlmClient.Message.user("创建文件")), scenario.equals("no-tools") ? List.of() : writeTools(),
                            new LlmClient.StreamListener() {
                                @Override public void onContentDelta(String delta) { streamed.append(delta); }
                            });
            assertFalse(response.hasToolCalls());
            assertEquals(block, response.content());
            assertEquals(block, streamed.toString());
        }
    }

    private static List<LlmClient.Tool> writeTools() {
        return List.of(new LlmClient.Tool("write_file", "Write a file", JSON.createObjectNode().put("type", "object")));
    }

    private static MockResponse content(String text) throws Exception {
        StringBuilder sse = new StringBuilder();
        // One character per SSE delta exercises every possible opening/closing tag boundary.
        for (int i = 0; i < text.length(); i++) {
            sse.append("data: ").append(JSON.writeValueAsString(Map.of("choices", List.of(Map.of(
                    "delta", Map.of("content", text.substring(i, i + 1))))))).append("\n\n");
        }
        return new MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(sse.append("data: [DONE]\n\n").toString());
    }
}
