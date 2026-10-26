package com.paicli.memory;

import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Skill 注入和 runaway 提醒以 user 角色进入历史，但压缩时既不算轮次，也不交给摘要模型。 */
class CompactorIgnoresInternalUserMessagesTest {

    private static final String SKILL_BODY = "SKILL_BODY_MARKER 先 web_fetch 再切浏览器";

    @Test
    void internalUserMessagesAreNotCountedAsRounds() {
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("system"));
        addRound(history, "Q1");
        history.add(skillInjection("web-access"));
        addRound(history, "Q2");
        history.add(LlmClient.Message.user(InternalUserMessages.RUNAWAY_REMINDER_PREFIX + " 换个思路"));
        addRound(history, "Q3");

        assertEquals(3, ConversationHistoryCompactor.userIndices(history, 1).size());
        RecordingCompactor compactor = new RecordingCompactor(3);
        assertFalse(compactor.compactIfNeeded(history, 1), "只有 3 个真实轮次，不应被内部消息凑够压缩条件");
        assertTrue(compactor.summarized.isEmpty());
    }

    @Test
    void retainsRealRoundsAndDropsSkillBodyWithReloadHint() {
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("system"));
        addRound(history, "Q1");
        LlmClient.Message injection = skillInjection("web-access");
        history.add(injection);
        history.add(LlmClient.Message.user(InternalUserMessages.RUNAWAY_REMINDER_PREFIX + " 换个思路"));
        addRound(history, "Q2");
        addRound(history, "Q3");
        addRound(history, "Q4");

        RecordingCompactor compactor = new RecordingCompactor(3);
        assertTrue(compactor.compactIfNeeded(history, 1));

        assertFalse(compactor.summarized.stream().anyMatch(m -> m.content() != null
                && (m.content().contains(SKILL_BODY) || m.content().contains("换个思路"))),
                "内部消息不应交给摘要模型");
        assertTrue(compactor.summarized.stream().anyMatch(m -> m.content().startsWith("Q1")));

        assertTrue(history.get(1).content().contains("SUMMARY"));
        assertTrue(history.get(1).content().contains("Skill 正文已随压缩移出上下文：web-access"),
                "摘要应提示哪些 Skill 需要重新加载");
        assertEquals("assistant", history.get(2).role());
        assertFalse(history.contains(injection), "被切到摘要范围的 Skill 正文应移出上下文");
        assertTrue(history.get(3).content().startsWith("Q2"), "保留的应是最近 3 个真实轮次");
        assertTrue(history.stream().noneMatch(m -> m.content() != null && m.content().contains("换个思路")));
    }

    @Test
    void sessionMemoryRebuildAlsoDropsSkillBody() {
        StubSessionMemoryCompactor compactor = new StubSessionMemoryCompactor();
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("system"));
        addRound(history, "Q1 " + "u".repeat(400));
        LlmClient.Message injection = skillInjection("web-access");
        history.add(injection);
        for (int i = 2; i <= 6; i++) {
            addRound(history, "Q" + i + " " + "u".repeat(400));
        }
        int trigger = TokenBudget.estimateMessagesTokens(history) + 200;

        assertTrue(compactor.prepareIfNeeded(history, trigger));
        assertFalse(compactor.delta.stream().anyMatch(m -> m.content() != null && m.content().contains(SKILL_BODY)));
        history.add(LlmClient.Message.user("new question " + "x".repeat(1_000)));
        history.add(LlmClient.Message.assistant("new answer"));
        assertTrue(compactor.compactIfReady(history, trigger));

        assertTrue(history.get(1).content().contains("[会话记忆摘要]"));
        assertFalse(history.contains(injection), "会话记忆路径同样把 Skill 正文移出上下文");
        assertTrue(history.get(1).content().contains("Skill 正文已随压缩移出上下文：web-access"));
    }

    private static void addRound(List<LlmClient.Message> history, String question) {
        history.add(LlmClient.Message.user(question + " " + "q".repeat(200)));
        history.add(LlmClient.Message.assistant("answer to " + question + " " + "a".repeat(200)));
    }

    private static LlmClient.Message skillInjection(String name) {
        return LlmClient.Message.user(InternalUserMessages.SKILL_INJECTION_MARKER + "\n"
                + "## 已加载 Skill：" + name + "（来源：user）\n" + SKILL_BODY + "\n---");
    }

    private static final class RecordingCompactor extends ConversationHistoryCompactor {
        private final List<LlmClient.Message> summarized = new ArrayList<>();

        private RecordingCompactor(int retainRecent) {
            super(null, retainRecent);
        }

        @Override
        protected String summarize(List<LlmClient.Message> messages) {
            summarized.addAll(messages);
            return "SUMMARY";
        }
    }

    private static final class StubSessionMemoryCompactor extends SessionMemoryCompactor {
        private final List<LlmClient.Message> delta = new ArrayList<>();

        private StubSessionMemoryCompactor() {
            super(null, true, new Config(0.50, 1, 1, 10_000), Runnable::run);
        }

        @Override
        protected String summarizeIncrement(String previousSummary, List<LlmClient.Message> messages)
                throws IOException {
            delta.addAll(messages);
            return "SESSION SUMMARY";
        }
    }
}
