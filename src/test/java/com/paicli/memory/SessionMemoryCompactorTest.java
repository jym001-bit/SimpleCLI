package com.paicli.memory;

import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SessionMemoryCompactorTest {

    @Test
    void disabledPathNeverPrecomputesOrCompacts() {
        StubSessionMemoryCompactor compactor = new StubSessionMemoryCompactor(false, "SUMMARY");
        List<LlmClient.Message> history = historyWithRounds(5, 200);

        assertFalse(compactor.prepareIfNeeded(history, 2_000));
        assertFalse(compactor.compactIfReady(history, 1));
        assertEquals(0, compactor.summaryCalls.get());
    }

    @Test
    void precomputesBeforeThresholdAndAppliesReadySummaryAtThreshold() {
        StubSessionMemoryCompactor compactor = new StubSessionMemoryCompactor(true, "SESSION SUMMARY");
        List<LlmClient.Message> history = historyWithRounds(6, 400);
        int current = TokenBudget.estimateMessagesTokens(history);
        int trigger = current + 200;

        assertTrue(compactor.prepareIfNeeded(history, trigger));
        assertEquals(1, compactor.summaryCalls.get());

        history.add(LlmClient.Message.user("new question " + "x".repeat(1_000)));
        history.add(LlmClient.Message.assistant("new answer"));
        assertTrue(TokenBudget.estimateMessagesTokens(history) >= trigger);
        assertTrue(compactor.compactIfReady(history, trigger));

        assertEquals("system", history.get(0).role());
        assertEquals("user", history.get(1).role());
        assertTrue(history.get(1).content().contains("[会话记忆摘要]"));
        assertTrue(history.get(1).content().contains("SESSION SUMMARY"));
        assertTrue(history.stream().anyMatch(message -> message.content() != null
                && message.content().startsWith("new question")));
        assertTrue(TokenBudget.estimateMessagesTokens(history) < trigger);
    }

    @Test
    void retainedTailStartsAtUserBoundaryAndKeepsToolPair() {
        StubSessionMemoryCompactor compactor = new StubSessionMemoryCompactor(true, "SESSION SUMMARY");
        List<LlmClient.Message> history = historyWithRounds(4, 400);
        history.add(LlmClient.Message.user("tool question"));
        history.add(LlmClient.Message.assistant(null, null, List.of(new LlmClient.ToolCall(
                "call-1", new LlmClient.ToolCall.Function("read_file", "{\"path\":\"a.txt\"}")))));
        history.add(LlmClient.Message.tool("call-1", "file content"));
        history.add(LlmClient.Message.assistant("tool done"));
        int current = TokenBudget.estimateMessagesTokens(history);
        int trigger = current + 100;

        assertTrue(compactor.prepareIfNeeded(history, trigger));
        history.add(LlmClient.Message.user("x".repeat(1_000)));
        history.add(LlmClient.Message.assistant("done"));
        assertTrue(compactor.compactIfReady(history, trigger));

        int toolQuestion = indexOfContent(history, "tool question");
        assertTrue(toolQuestion > 0);
        assertNotNull(history.get(toolQuestion + 1).toolCalls());
        assertEquals("tool", history.get(toolQuestion + 2).role());
        assertEquals("call-1", history.get(toolQuestion + 2).toolCallId());
    }

    @Test
    void staleBoundaryMakesFastPathUnavailableWithoutMutatingHistory() {
        StubSessionMemoryCompactor compactor = new StubSessionMemoryCompactor(true, "SESSION SUMMARY");
        List<LlmClient.Message> history = historyWithRounds(6, 300);
        int trigger = TokenBudget.estimateMessagesTokens(history) + 100;
        assertTrue(compactor.prepareIfNeeded(history, trigger));

        List<LlmClient.Message> replacement = history.stream()
                .map(message -> new LlmClient.Message(message.role(), message.content(), message.reasoningContent(),
                        message.toolCalls(), message.toolCallId(), message.contentParts()))
                .toList();
        history.clear();
        history.addAll(replacement);
        int before = history.size();

        assertFalse(compactor.compactIfReady(history, 1));
        assertEquals(before, history.size());
    }

    private static List<LlmClient.Message> historyWithRounds(int rounds, int charsPerMessage) {
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("system"));
        for (int i = 0; i < rounds; i++) {
            history.add(LlmClient.Message.user("Q" + i + " " + "u".repeat(charsPerMessage)));
            history.add(LlmClient.Message.assistant("A" + i + " " + "a".repeat(charsPerMessage)));
        }
        return history;
    }

    private static int indexOfContent(List<LlmClient.Message> history, String content) {
        for (int i = 0; i < history.size(); i++) {
            if (content.equals(history.get(i).content())) return i;
        }
        return -1;
    }

    private static final class StubSessionMemoryCompactor extends SessionMemoryCompactor {
        private final AtomicInteger summaryCalls = new AtomicInteger();
        private final String summary;

        private StubSessionMemoryCompactor(boolean enabled, String summary) {
            super(null, enabled, new Config(0.50, 1, 1, 10_000), Runnable::run);
            this.summary = summary;
        }

        @Override
        protected String summarizeIncrement(String previousSummary, List<LlmClient.Message> messages)
                throws IOException {
            summaryCalls.incrementAndGet();
            return summary;
        }
    }
}
