package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AgentToolExposureTest {
    @Test void requestContextMatchesRestrictedToolsAndCarriesActualFailureIntoRecovery(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve("alpha.txt"), "ALPHA=17\n");
        ToolRegistry registry = new ToolRegistry() {
            @Override public List<LlmClient.Tool> getToolDefinitions() {
                return super.getToolDefinitions().stream().filter(tool -> tool.name().equals("read_file")).toList();
            }
        };
        registry.setProjectPath(temp.toString());
        List<List<LlmClient.Message>> requests = new ArrayList<>();
        LlmClient client = new LlmClient() {
            @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) { return chat(messages, tools, StreamListener.NO_OP); }
            @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
                requests.add(List.copyOf(messages));
                assertEquals(List.of("read_file"), tools.stream().map(Tool::name).toList());
                String system = messages.get(0).content();
                assertTrue(system.contains("本次请求实际开放的工具只有：read_file"));
                assertFalse(system.contains("你可以使用以下工具："), "Static broad catalogue must not contradict restricted tools");
                if (requests.size() == 1) return tool("bad", "{}");
                if (requests.size() == 2) return tool("recovery", "{\"path\":\"alpha.txt\"}");
                return new ChatResponse("assistant", "17", null, 1, 1);
            }
            @Override public String getModelName() { return "controlled"; }
            @Override public String getProviderName() { return "offline"; }
        };
        Agent agent = new Agent(client, registry);
        var contextLoads = new java.util.concurrent.atomic.AtomicInteger();
        agent.setExternalContextSupplier(() -> { contextLoads.incrementAndGet(); return "固定项目上下文"; });
        contextLoads.set(0);
        agent.run("先用空对象验证参数校验，看到错误后读取 alpha.txt，最后回答整数");
        assertEquals(1, contextLoads.get(), "Tool refresh must not reload external context each iteration");
        assertEquals(3, requests.size());
        assertTrue(requests.get(1).stream().anyMatch(m -> "tool".equals(m.role()) && "bad".equals(m.toolCallId())
                && m.content().contains("缺少必填参数 path")));
        assertTrue(requests.get(2).stream().anyMatch(m -> "tool".equals(m.role()) && "recovery".equals(m.toolCallId())
                && m.content().contains("ALPHA=17")));
        assertEquals(requests.get(2).get(0), agent.getConversationHistory().get(0), "Actual history must match the request, not a hidden copy");
    }

    private static LlmClient.ChatResponse tool(String id, String args) {
        return new LlmClient.ChatResponse("assistant", "", List.of(new LlmClient.ToolCall(id,
                new LlmClient.ToolCall.Function("read_file", args))), 1, 1);
    }
}
