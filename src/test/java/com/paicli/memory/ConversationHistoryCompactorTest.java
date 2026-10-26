package com.paicli.memory;

import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConversationHistoryCompactorTest {

    @Test
    void doesNothingWhenBelowTrigger() {
        StubCompactor c = new StubCompactor("MOCK SUMMARY", 3);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("system"));
        history.add(LlmClient.Message.user("hi"));

        boolean compacted = c.compactIfNeeded(history, 100_000);

        assertFalse(compacted);
        assertEquals(2, history.size());
        assertEquals(0, c.summarizeCalls.get());
    }

    @Test
    void compactNowIgnoresTriggerAndKeepsRecentTurns() {
        StubCompactor c = new StubCompactor("MANUAL SUMMARY", 2);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("SYSTEM_PROMPT"));
        for (int i = 0; i < 3; i++) {
            history.add(LlmClient.Message.user("Q" + i + longText(100)));
            history.add(LlmClient.Message.assistant("A" + i + longText(100)));
        }

        boolean compacted = c.compactNow(history);

        assertTrue(compacted);
        assertEquals(1, c.summarizeCalls.get());
        assertTrue(history.get(1).content().contains("MANUAL SUMMARY"));
        assertEquals(5, history.size());
        assertTrue(history.get(3).content().startsWith("Q2"));
    }

    @Test
    void doesNothingWhenUserTurnsTooFew() {
        // 只有 2 个 user message，retainRecent=3，不应该压缩（即使 token 超阈值）
        StubCompactor c = new StubCompactor("MOCK SUMMARY", 3);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("system"));
        history.add(LlmClient.Message.user(longText(20_000)));
        history.add(LlmClient.Message.assistant(longText(20_000)));
        history.add(LlmClient.Message.user(longText(20_000)));
        history.add(LlmClient.Message.assistant(longText(20_000)));

        boolean compacted = c.compactIfNeeded(history, 100);

        assertFalse(compacted, "user turns 不够 retainRecent 时应跳过");
        assertEquals(5, history.size());
        assertEquals(0, c.summarizeCalls.get());
    }

    @Test
    void compactsOldRoundsAndKeepsRecentTurns() {
        StubCompactor c = new StubCompactor("MOCK SUMMARY OF OLD CONTENT", 2);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("SYSTEM_PROMPT"));
        // 6 轮 user/assistant
        for (int i = 0; i < 6; i++) {
            history.add(LlmClient.Message.user("Q" + i + ": " + longText(5_000)));
            history.add(LlmClient.Message.assistant("A" + i + ": " + longText(5_000)));
        }

        boolean compacted = c.compactIfNeeded(history, 100);

        assertTrue(compacted);
        assertEquals(1, c.summarizeCalls.get());

        // 重建后结构：[system] + [user(摘要)] + [assistant(确认)] + [保留的 retainRecent=2 个 user 起算的尾部]
        // 即 1 + 1 + 1 + (2 个 user × 2 行 = 4 条) = 7 条
        assertEquals(7, history.size());
        assertEquals("system", history.get(0).role());
        assertEquals("user", history.get(1).role());
        assertTrue(history.get(1).content().contains("已压缩的历史对话摘要"));
        assertTrue(history.get(1).content().contains("MOCK SUMMARY OF OLD CONTENT"));
        assertEquals("assistant", history.get(2).role());

        // 保留的最后两个 user message 仍是 Q4 / Q5（不是 Q3）
        assertTrue(history.get(3).content().startsWith("Q4"));
        assertTrue(history.get(5).content().startsWith("Q5"));
    }

    @Test
    void preservesToolCallPairAtSplitBoundary() {
        // 故意构造一个尾部带 tool_call/tool_result 的形态，验证不会被切断
        StubCompactor c = new StubCompactor("SUMMARY", 2);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("S"));
        // 旧轮次（应当被压缩）
        history.add(LlmClient.Message.user("OldQ1: " + longText(3_000)));
        history.add(LlmClient.Message.assistant("OldA1"));
        history.add(LlmClient.Message.user("OldQ2"));
        history.add(LlmClient.Message.assistant("OldA2"));
        // 保留的最近两轮，每轮带 tool_call
        history.add(LlmClient.Message.user("RecentQ1"));
        List<LlmClient.ToolCall> tcs1 = List.of(new LlmClient.ToolCall("c1",
                new LlmClient.ToolCall.Function("read_file", "{\"path\":\"a\"}")));
        history.add(LlmClient.Message.assistant(null, null, tcs1));
        history.add(new LlmClient.Message("tool", "file content", null, null, "c1"));
        history.add(LlmClient.Message.assistant("done1"));
        history.add(LlmClient.Message.user("RecentQ2"));
        history.add(LlmClient.Message.assistant("RecentA2"));

        boolean compacted = c.compactIfNeeded(history, 100);

        assertTrue(compacted);
        // 找到摘要后的第一个 user
        int firstUserAfterSummary = -1;
        for (int i = 0; i < history.size(); i++) {
            if ("user".equals(history.get(i).role())
                    && !history.get(i).content().contains("已压缩的历史对话摘要")) {
                firstUserAfterSummary = i;
                break;
            }
        }
        assertTrue(firstUserAfterSummary > 0);
        // splitIdx 必然落在 user 边界，紧随的 assistant(tool_call) 和 tool 配对应该完整保留
        assertEquals("RecentQ1", history.get(firstUserAfterSummary).content());
        assertEquals("assistant", history.get(firstUserAfterSummary + 1).role());
        assertNotNull(history.get(firstUserAfterSummary + 1).toolCalls());
        assertEquals("tool", history.get(firstUserAfterSummary + 2).role());
    }

    @Test
    void emptySummaryAbortsCompaction() {
        StubCompactor c = new StubCompactor("", 2);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("S"));
        for (int i = 0; i < 5; i++) {
            history.add(LlmClient.Message.user("Q" + i + " " + longText(2_000)));
            history.add(LlmClient.Message.assistant("A" + i));
        }
        int before = history.size();

        boolean compacted = c.compactIfNeeded(history, 100);

        assertFalse(compacted);
        assertEquals(before, history.size());
    }

    @Test
    void summaryThatIsLongerThanOriginalKeepsHistoryIntact() {
        StubCompactor c = new StubCompactor(longText(10_000), 1);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("S"));
        for (int i = 0; i < 3; i++) {
            history.add(LlmClient.Message.user("Q" + i));
            history.add(LlmClient.Message.assistant("A" + i));
        }
        List<LlmClient.Message> before = List.copyOf(history);

        assertFalse(c.compactNow(history));
        assertEquals(before, history);
    }

    @Test
    void llmFailureDoesNotCorruptHistory() {
        StubCompactor c = new StubCompactor(null, 2) {
            @Override
            protected String summarize(List<LlmClient.Message> messages) throws IOException {
                summarizeCalls.incrementAndGet();
                throw new IOException("LLM unavailable");
            }
        };
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("S"));
        for (int i = 0; i < 5; i++) {
            history.add(LlmClient.Message.user("Q" + i + " " + longText(2_000)));
            history.add(LlmClient.Message.assistant("A" + i));
        }
        int before = history.size();

        boolean compacted = c.compactIfNeeded(history, 100);

        assertFalse(compacted);
        assertEquals(before, history.size());
    }

    @Test
    void longSummaryInputCoversEarlyMiddleAndRecentMessages() {
        List<LlmClient.Message> messages = List.of(
                LlmClient.Message.user("ORIGINAL_REQUIREMENT " + longText(60_000)),
                LlmClient.Message.assistant("MIDDLE_DECISION " + longText(60_000)),
                LlmClient.Message.user("LATEST_UNRESOLVED_DECISION"));

        List<String> chunks = ConversationHistoryCompactor.renderSummaryChunks(messages);
        String rendered = String.join("", chunks);

        assertTrue(chunks.size() >= 3);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.length() <= 60_000));
        assertTrue(rendered.contains("ORIGINAL_REQUIREMENT"));
        assertTrue(rendered.contains("MIDDLE_DECISION"));
        assertTrue(rendered.contains("LATEST_UNRESOLVED_DECISION"));
        assertFalse(rendered.contains("中间历史过长"));
    }

    @Test
    void exactSummaryInputLimitKeepsAllCharacters() {
        String content = longText(59_992);
        List<String> chunks = ConversationHistoryCompactor.renderSummaryChunks(List.of(LlmClient.Message.user(content)));

        assertEquals(1, chunks.size());
        assertEquals(60_000, chunks.get(0).length());
        assertEquals("USER: " + content + "\n\n", chunks.get(0));
    }

    @Test
    void longHistorySummarizesEveryChunkBeforeMerging() throws IOException {
        RecordingClient client = new RecordingClient();
        ConversationHistoryCompactor compactor = new ConversationHistoryCompactor(client);
        List<LlmClient.Message> messages = List.of(
                LlmClient.Message.user("EARLY_REQUIREMENT " + longText(60_000)),
                LlmClient.Message.assistant("MIDDLE_DECISION " + longText(60_000)),
                LlmClient.Message.user("RECENT_UNRESOLVED_ITEM"));

        String summary = compactor.summarize(messages);

        assertEquals(RecordingClient.STRUCTURED_SUMMARY.trim(), summary);
        assertEquals(ConversationHistoryCompactor.renderSummaryChunks(messages).size() + 1,
                client.prompts.size());
        assertTrue(client.prompts.stream().anyMatch(prompt -> prompt.contains("EARLY_REQUIREMENT")));
        assertTrue(client.prompts.stream().anyMatch(prompt -> prompt.contains("MIDDLE_DECISION")));
        assertTrue(client.prompts.stream().anyMatch(prompt -> prompt.contains("RECENT_UNRESOLVED_ITEM")));
        assertTrue(client.prompts.get(client.prompts.size() - 1).contains("PARTIAL_SUMMARY_1"));
    }

    @Test
    void rejectsUnstructuredSummaryWithoutChangingHistory() {
        RecordingClient client = new RecordingClient(true);
        ConversationHistoryCompactor compactor = new ConversationHistoryCompactor(client, 1);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("SYSTEM"));
        for (int i = 0; i < 3; i++) {
            history.add(LlmClient.Message.user("Q" + i + longText(2_000)));
            history.add(LlmClient.Message.assistant("A" + i));
        }
        List<LlmClient.Message> before = List.copyOf(history);

        assertFalse(compactor.compactIfNeeded(history, 100));
        assertEquals(before, history);
        assertEquals(2, client.prompts.size());
    }

    private static String longText(int chars) {
        StringBuilder sb = new StringBuilder(chars);
        for (int i = 0; i < chars; i++) sb.append('x');
        return sb.toString();
    }

    private static final class RecordingClient implements LlmClient {
        private static final String STRUCTURED_SUMMARY = """
                ## 当前目标与成功条件
                goal
                ## 用户要求与已确认决定
                requirement
                ## 已完成工作及证据
                evidence
                ## 未解决问题与下一步
                next
                """;
        private final List<String> prompts = new ArrayList<>();
        private final boolean invalidRepair;

        private RecordingClient() {
            this(false);
        }

        private RecordingClient(boolean invalidRepair) {
            this.invalidRepair = invalidRepair;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            String prompt = messages.get(1).content();
            prompts.add(prompt);
            String content = prompt.contains("按时间顺序合并")
                    ? STRUCTURED_SUMMARY
                    : prompt.contains("请仅整理下面已有的会话摘要") && !invalidRepair
                        ? STRUCTURED_SUMMARY : "PARTIAL_SUMMARY_" + prompts.size();
            return new ChatResponse("assistant", content, List.of(), 0, 0);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            return chat(messages, tools);
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

    /** 测试用 stub：summarize 返回固定字符串，避免真实 LLM 依赖。 */
    private static class StubCompactor extends ConversationHistoryCompactor {
        final AtomicInteger summarizeCalls = new AtomicInteger();
        private final String mockSummary;

        StubCompactor(String mockSummary, int retainRecent) {
            super(null, retainRecent);
            this.mockSummary = mockSummary;
        }

        @Override
        protected String summarize(List<LlmClient.Message> messages) throws IOException {
            summarizeCalls.incrementAndGet();
            return mockSummary;
        }
    }
}
