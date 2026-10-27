package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.tool.ToolRegistry;
import com.paicli.render.Renderer;
import com.paicli.render.StatusInfo;
import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentBudgetFinalizationTest {

    @Test
    void modelIoFailureStopsTheTurnWithAnExplicitFailure(@TempDir Path tempDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        RecordingClient llm = new RecordingClient(List.of());
        StatusRenderer renderer = new StatusRenderer();
        Agent agent = new Agent(llm, registry);
        agent.setRenderer(renderer);
        String result = agent.run("读取当前目录");
        assertTrue(result.contains("调用 LLM 失败"));
        assertEquals(1, llm.messageSnapshots.size());
        assertEquals(List.of("running", "idle"), renderer.phases,
                "模型异常返回后必须清理运行中状态");
        assertTrue(agent.getConversationHistory().stream().noneMatch(message -> "assistant".equals(message.role())),
                "模型失败不能追加虚构的成功回答");
    }

    @Test
    void explicitIterationLimitUsesOneToolFreeFinalizationCall(@TempDir Path tempDir) {
        String old = System.getProperty("paicli.react.hard.max.iterations");
        try {
            System.setProperty("paicli.react.hard.max.iterations", "1");
            LlmClient.ToolCall listDir = new LlmClient.ToolCall(
                    "call_1",
                    new LlmClient.ToolCall.Function("list_dir", "{\"path\":\".\"}"));
            RecordingClient llm = new RecordingClient(List.of(
                    new LlmClient.ChatResponse("assistant", "", List.of(listDir), 10, 2),
                    new LlmClient.ChatResponse(
                            "assistant",
                            "已完成目录读取；尚未继续分析文件内容。",
                            null,
                            12,
                            4)));
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());
            Agent agent = new Agent(llm, registry);

            String result = agent.run("列出当前目录并分析内容");

            assertEquals(2, llm.messageSnapshots.size());
            assertNotNull(llm.toolSnapshots.get(0));
            assertTrue(!llm.toolSnapshots.get(0).isEmpty());
            assertNotNull(llm.toolSnapshots.get(1));
            assertTrue(llm.toolSnapshots.get(1).isEmpty(), "收尾调用不得暴露工具");
            assertTrue(llm.messageSnapshots.get(1).get(0).content().contains("本次请求没有开放工具"),
                    "收尾上下文也不能继续宣称工具可用");
            assertTrue(llm.messageSnapshots.get(1).stream()
                    .filter(message -> "user".equals(message.role()))
                    .map(LlmClient.Message::content)
                    .anyMatch(content -> content != null && content.contains("不要再调用任何工具")));
            assertTrue(result.contains("部分完成"));
            assertTrue(result.contains("尚未继续分析文件内容"));
        } finally {
            if (old == null) {
                System.clearProperty("paicli.react.hard.max.iterations");
            } else {
                System.setProperty("paicli.react.hard.max.iterations", old);
            }
        }
    }

    private static final class StatusRenderer implements Renderer {
        private final PrintStream output = new PrintStream(new ByteArrayOutputStream());
        private final List<String> phases = new ArrayList<>();
        @Override public void start() { }
        @Override public void close() { output.close(); }
        @Override public PrintStream stream() { return output; }
        @Override public void appendToolCalls(List<LlmClient.ToolCall> calls) { }
        @Override public void appendDiff(String path, String before, String after) { }
        @Override public void updateStatus(StatusInfo status) { phases.add(status.phase()); }
        @Override public ApprovalResult promptApproval(ApprovalRequest request) { return ApprovalResult.reject("test"); }
        @Override public int openPalette(String title, List<String> items) { return -1; }
    }

    private static final class RecordingClient implements LlmClient {
        private final Queue<ChatResponse> responses;
        private final List<List<Message>> messageSnapshots = new ArrayList<>();
        private final List<List<Tool>> toolSnapshots = new ArrayList<>();

        private RecordingClient(List<ChatResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            messageSnapshots.add(List.copyOf(messages));
            toolSnapshots.add(tools == null ? null : List.copyOf(tools));
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("缺少预设响应");
            }
            return response;
        }

        @Override
        public String getModelName() {
            return "test";
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }
}
