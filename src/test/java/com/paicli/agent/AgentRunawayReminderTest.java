package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRunawayReminderTest {

    @TempDir
    Path tempDir;

    private String oldMemoryDir;
    private String oldWindow;

    @BeforeEach
    void isolate() {
        oldMemoryDir = System.getProperty("paicli.memory.dir");
        oldWindow = System.getProperty("paicli.react.stagnation.window");
        System.setProperty("paicli.memory.dir", tempDir.resolve("memory").toString());
        System.clearProperty("paicli.react.stagnation.window");
    }

    @AfterEach
    void restore() {
        restoreProperty("paicli.memory.dir", oldMemoryDir);
        restoreProperty("paicli.react.stagnation.window", oldWindow);
    }

    @Test
    void repeatedToolCallGetsOneReminderAndTheLoopKeepsGoing() {
        List<LlmClient.ChatResponse> responses = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            responses.add(toolCallResponse("call-" + i));
        }
        responses.add(new LlmClient.ChatResponse("assistant", "目录里只有这些文件", null, 10, 2));
        QueueClient llm = new QueueClient(responses);
        ToolRegistry tools = new ToolRegistry();
        tools.setProjectPath(tempDir.toString());

        String result = new Agent(llm, tools).run("看看当前目录");

        assertEquals(4, llm.requests.size());
        assertFalse(result.contains("部分完成"), "提醒阈值低于停滞兜底窗口，不应触发收尾: " + result);
        List<LlmClient.Message> third = llm.requests.get(2);
        assertFalse(isReminder(third.get(third.size() - 1)), "第 3 次调用执行之前还不该提醒");
        List<LlmClient.Message> fourth = llm.requests.get(3);
        LlmClient.Message last = fourth.get(fourth.size() - 1);
        assertEquals("user", last.role());
        assertTrue(isReminder(last), last.content());
        assertEquals("tool", fourth.get(fourth.size() - 2).role(), "提醒应紧跟在工具结果之后");
        assertEquals(1, fourth.stream().filter(AgentRunawayReminderTest::isReminder).count());
    }

    private static boolean isReminder(LlmClient.Message message) {
        return message.content() != null && message.content().startsWith("[runaway guard]");
    }

    private static LlmClient.ChatResponse toolCallResponse(String id) {
        LlmClient.ToolCall call = new LlmClient.ToolCall(id,
                new LlmClient.ToolCall.Function("list_dir", "{\"path\":\".\"}"));
        return new LlmClient.ChatResponse("assistant", "", List.of(call), 10, 2);
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static final class QueueClient implements LlmClient {
        private final Queue<ChatResponse> responses;
        private final List<List<Message>> requests = new ArrayList<>();

        private QueueClient(List<ChatResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            requests.add(List.copyOf(messages));
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("缺少预设响应");
            }
            return response;
        }

        @Override
        public String getModelName() {
            return "test-model";
        }

        @Override
        public String getProviderName() {
            return "test";
        }

        @Override
        public int maxContextWindow() {
            return 256_000;
        }
    }
}
