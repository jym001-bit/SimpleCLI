package com.paicli.agent;

import com.paicli.llm.GLMClient;
import com.paicli.llm.LlmClient;
import com.paicli.memory.ExternalContextTracker;
import com.paicli.memory.MemoryEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMemoryHintTest {

    @TempDir
    Path tempDir;

    @Test
    void explicitChromeRememberRequestStoresSitePreferenceInLongTermMemory() {
        String oldMemoryDir = System.getProperty("paicli.memory.dir");
        System.setProperty("paicli.memory.dir", tempDir.toString());
        try {
            StubGLMClient llmClient = new StubGLMClient(List.of(
                    new LlmClient.ChatResponse("assistant", "已打开链接。", null, 20, 10),
                    new LlmClient.ChatResponse("assistant", "已记住。", null, 20, 10)
            ));
            Agent agent = new Agent(llmClient);

            agent.run("打开 https://www.yuque.com/itwanger/gykdzg 这个语雀文档");
            assertEquals(0, agent.getMemoryManager().getLongTermMemory().size());

            agent.run("你可以直接复用我已经登录的Chrome，记一下");

            List<String> facts = agent.getMemoryManager().getLongTermMemory().getAll().stream()
                    .map(MemoryEntry::getContent)
                    .toList();
            assertTrue(facts.contains("访问 yuque.com（语雀）时优先复用用户已登录的 Chrome 登录态。"));
            assertEquals("global", agent.getMemoryManager().getLongTermMemory().getAll().get(0).getMetadata().get("scope"));
        } finally {
            if (oldMemoryDir == null) {
                System.clearProperty("paicli.memory.dir");
            } else {
                System.setProperty("paicli.memory.dir", oldMemoryDir);
            }
        }
    }

    @Test
    void browserLoginHintIsNotAutoSavedAfterExternalContent() {
        String oldMemoryDir = System.getProperty("paicli.memory.dir");
        String oldGuard = System.getProperty(ExternalContextTracker.GUARD_PROPERTY);
        System.setProperty("paicli.memory.dir", tempDir.toString());
        System.setProperty(ExternalContextTracker.GUARD_PROPERTY, "true");
        try {
            StubGLMClient llmClient = new StubGLMClient(List.of(
                    new LlmClient.ChatResponse("assistant", "已打开链接。", null, 20, 10),
                    new LlmClient.ChatResponse("assistant", "好的。", null, 20, 10)
            ));
            Agent agent = new Agent(llmClient);

            agent.run("打开 https://www.yuque.com/itwanger/gykdzg 这个语雀文档");
            // 模拟上一轮 web_fetch / 浏览器 MCP 结果已进入上下文。
            agent.getMemoryManager().getExternalContextTracker().record("web_fetch");
            agent.run("你可以直接复用我已经登录的Chrome，记一下");

            assertEquals(0, agent.getMemoryManager().getLongTermMemory().size());

            agent.clearHistory();
            assertFalse(agent.getMemoryManager().getExternalContextTracker().hasExternalContext());
        } finally {
            restore("paicli.memory.dir", oldMemoryDir);
            restore(ExternalContextTracker.GUARD_PROPERTY, oldGuard);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static final class StubGLMClient extends GLMClient {
        private final Queue<ChatResponse> responses;

        private StubGLMClient(List<ChatResponse> responses) {
            super("test-key");
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("缺少预设响应");
            }
            return response;
        }
    }
}
