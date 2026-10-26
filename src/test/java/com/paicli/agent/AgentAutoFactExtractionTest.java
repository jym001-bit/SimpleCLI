package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class AgentAutoFactExtractionTest {
    @TempDir Path tempDir;

    @Test
    void completedUserTurnIsExtractedAndRetrievedOnNextRequest() {
        String prior = System.getProperty("paicli.memory.dir");
        System.setProperty("paicli.memory.dir", tempDir.resolve("memory").toString());
        try {
            StubClient client = new StubClient(
                    "好的。",
                    "{\"facts\":[{\"quote\":\"我偏好中文回答\"}]}",
                    "我会用中文回答。");
            ToolRegistry tools = new ToolRegistry();
            tools.setProjectPath(tempDir.toString());
            Agent agent = new Agent(client, tools);
            agent.getMemoryManager().setAutoFactExtractionEnabled(true);

            assertTrue(agent.run("我偏好中文回答。现在先打个招呼。").contains("好的"));
            assertEquals(1, agent.getMemoryManager().getLongTermMemory().size());
            assertEquals(2, client.requests.size());

            agent.run("请按中文回答下一题。");
            assertEquals(3, client.requests.size());
            assertTrue(client.requests.get(2).get(0).content().contains("我偏好中文回答"));
        } finally {
            if (prior == null) System.clearProperty("paicli.memory.dir");
            else System.setProperty("paicli.memory.dir", prior);
        }
    }

    private static final class StubClient implements LlmClient {
        private final Queue<String> responses = new ArrayDeque<>();
        private final List<List<Message>> requests = new ArrayList<>();

        private StubClient(String... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                            StreamListener listener) throws IOException {
            requests.add(List.copyOf(messages));
            String content = responses.poll();
            if (content == null) throw new IOException("missing response");
            return new ChatResponse("assistant", content, null, 10, 5);
        }

        @Override public String getModelName() { return "test-model"; }
        @Override public String getProviderName() { return "test-provider"; }
        @Override public boolean supportsTools() { return false; }
    }
}
