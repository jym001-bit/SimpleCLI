package com.paicli.memory;

import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 记忆/压缩 A 层缺口：现有实现不会修复输入的孤立 tool call/result，也不会去重
 * 重复的 tool result。默认跳过；-Dpaicli.memory.gap=true 时用于确认缺口。
 */
@EnabledIfSystemProperty(named = "paicli.memory.gap", matches = "true")
class MemoryCompressionGapTest {

    @Test
    void clearerDoesNotRepairMissingToolResult() {
        List<LlmClient.Message> history = base();
        history.add(2, LlmClient.Message.assistant("", List.of(
                call("a", "read_file"), call("b", "read_file"))));
        history.add(LlmClient.Message.tool("a", "result-a"));

        new ToolResultClearer(new ToolResultClearer.Config(true, 0, 0, Set.of()))
                .clearNow(history);

        assertEquals(1, count(history, "a"));
        assertEquals(1, count(history, "b"), "缺失的 tool result 仍孤立");
    }

    @Test
    void clearerDoesNotDeduplicateRepeatedToolResultId() {
        List<LlmClient.Message> history = base();
        history.add(2, LlmClient.Message.assistant("", List.of(call("a", "read_file"))));
        history.add(LlmClient.Message.tool("a", "result-1"));
        history.add(LlmClient.Message.tool("a", "result-2"));

        new ToolResultClearer(new ToolResultClearer.Config(true, 0, 0, Set.of()))
                .clearNow(history);

        assertEquals(1, count(history, "a"), "重复 tool result 仍保留两条");
    }

    @Test
    void summaryBoundaryDoesNotDeduplicateRepeatedToolResultId() throws Exception {
        List<LlmClient.Message> history = base();
        history.add(LlmClient.Message.user("first"));
        history.add(LlmClient.Message.assistant("first done"));
        history.add(LlmClient.Message.user("second"));
        history.add(LlmClient.Message.assistant("", List.of(call("d", "read_file"))));
        history.add(LlmClient.Message.tool("d", "one"));
        history.add(LlmClient.Message.tool("d", "two"));
        history.add(LlmClient.Message.assistant("second done"));

        new ConversationHistoryCompactor(new FakeClient(), 1).compactNow(history);

        assertEquals(1, count(history, "d"), "摘要切分后重复 tool result 仍保留两条");
    }

    @Test
    void summaryBoundaryDoesNotRepairMissingToolResult() throws Exception {
        List<LlmClient.Message> history = base();
        history.add(LlmClient.Message.user("first"));
        history.add(LlmClient.Message.assistant("", List.of(
                call("a", "read_file"), call("b", "read_file"))));
        history.add(LlmClient.Message.tool("a", "result-a"));
        history.add(LlmClient.Message.assistant("first done"));
        history.add(LlmClient.Message.user("second"));
        history.add(LlmClient.Message.assistant("second done"));
        history.add(LlmClient.Message.user("third"));
        history.add(LlmClient.Message.assistant("third done"));

        new ConversationHistoryCompactor(new FakeClient(), 1).compactNow(history);

        assertEquals(1, count(history, "a"));
        assertEquals(1, count(history, "b"), "摘要切分后缺失的 tool result 仍孤立");
    }

    private List<LlmClient.Message> base() {
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("system"));
        history.add(LlmClient.Message.user("q"));
        return history;
    }

    private static int count(List<LlmClient.Message> history, String id) {
        int count = 0;
        for (LlmClient.Message message : history) {
            if ("tool".equals(message.role()) && id.equals(message.toolCallId())) count++;
        }
        return count;
    }

    private static LlmClient.ToolCall call(String id, String name) {
        return new LlmClient.ToolCall(id, new LlmClient.ToolCall.Function(name, "{}"));
    }

    private static final class FakeClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return new ChatResponse("assistant",
                    "## 当前目标与成功条件\nkeep\n## 用户要求与已确认决定\nkeep\n"
                            + "## 已完成工作及证据\nsummary\n## 未解决问题与下一步\nnone",
                    null, 10, 5);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            return chat(messages, tools);
        }

        @Override
        public String getModelName() { return "fake"; }

        @Override
        public String getProviderName() { return "fake"; }
    }
}
