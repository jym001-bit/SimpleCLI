package com.paicli.memory;

import com.paicli.llm.LlmClient;
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
class AutoFactExtractorTest {
    @TempDir Path tempDir;

    @Test
    void savesOnlyUserVerbatimFactsAndInjectsThemAsPendingVerification() {
        StubClient client = new StubClient("""
                {"facts":[{"quote":"我平时使用 Java 17"},{"quote":"模型编造的事实"}]}
                """);
        LongTermMemory store = new LongTermMemory(tempDir.toFile());
        MemoryManager manager = manager(client, store);

        List<MemoryEntry> saved = manager.extractFactsFromUserTurn("我平时使用 Java 17，请帮我检查代码。");

        assertEquals(1, saved.size());
        assertEquals("我平时使用 Java 17", saved.get(0).getContent());
        assertEquals("auto_user_fact", saved.get(0).getMetadata().get("source"));
        assertEquals("true", saved.get(0).getMetadata().get("verification_pending"));
        assertEquals("project", saved.get(0).getMetadata().get("scope"));
        assertTrue(client.requests.get(0).get(1).content().contains("我平时使用 Java 17"));
        assertEquals(1, new LongTermMemory(tempDir.toFile()).size());
        assertTrue(manager.buildContextForQuery("Java 17", 500).contains("[自动提取，待核实]"));
        assertTrue(manager.buildContextForQuery("Java 17", 500).contains("尚未经用户核实"));
        assertEquals(1, manager.getTokenBudget().getLlmCallCount());

        MemoryEntry verified = manager.verifyLongTerm(saved.get(0).getId()).orElseThrow();
        assertFalse(verified.getMetadata().containsKey("verification_pending"));
    }

    @Test
    void rejectsMalformedResponsesSecretsAndNonVerbatimClaims() {
        StubClient client = new StubClient("not json", """
                {"facts":[{"quote":"我喜欢中文回答"},
                          {"quote":"我没有说过的偏好"}]}
                """);
        MemoryManager manager = manager(client, new LongTermMemory(tempDir.toFile()));

        assertTrue(manager.extractFactsFromUserTurn("我喜欢中文回答").isEmpty());
        List<MemoryEntry> saved = manager.extractFactsFromUserTurn(
                "我的 API_KEY 是 abc123；我喜欢中文回答。");

        assertEquals(List.of("我喜欢中文回答"), saved.stream().map(MemoryEntry::getContent).toList());
        assertEquals(2, client.requests.size());
        assertFalse(client.requests.get(1).get(1).content().contains("abc123"));
    }

    @Test
    void skipsAfterExternalContentAndRejectsDuplicateOrConflictingFacts() {
        StubClient client = new StubClient(
                "{\"facts\":[{\"quote\":\"本项目使用 Java 17\"}]}",
                "{\"facts\":[{\"quote\":\"本项目使用 Java 17\"}]}",
                "{\"facts\":[{\"quote\":\"本项目使用 Java 21\"}]}");
        LongTermMemory store = new LongTermMemory(tempDir.toFile());
        MemoryManager manager = manager(client, store);

        MemoryEntry first = manager.extractFactsFromUserTurn("本项目使用 Java 17。").get(0);
        assertTrue(manager.extractFactsFromUserTurn("本项目使用 Java 17。").isEmpty());
        assertTrue(manager.extractFactsFromUserTurn("本项目使用 Java 21。").isEmpty());
        assertEquals(1, store.size());
        assertEquals(first.getLastVerifiedAt(), store.retrieve(first.getId()).orElseThrow().getLastVerifiedAt());

        ExternalContextTracker tracker = new ExternalContextTracker(true);
        tracker.record("web_fetch");
        manager.setExternalContextTracker(tracker);
        assertTrue(manager.extractFactsFromUserTurn("我习惯用中文交流。").isEmpty());
        assertEquals(3, client.requests.size());
    }

    @Test
    void quotedCodeQuestionsAndTemporaryRequestsAreNotSaved() {
        StubClient client = new StubClient(
                "{\"facts\":[{\"quote\":\"本项目使用 Java 17\"}]}",
                "{\"facts\":[{\"quote\":\"本项目使用 Java 17\"}]}",
                "{\"facts\":[{\"quote\":\"本项目使用 Java 17\"}]}",
                "{\"facts\":[{\"quote\":\"我现在请你检查代码\"}]}");
        MemoryManager manager = manager(client, new LongTermMemory(tempDir.toFile()));

        assertTrue(manager.extractFactsFromUserTurn("引用资料：\n> 本项目使用 Java 17").isEmpty());
        assertTrue(manager.extractFactsFromUserTurn("```text\n本项目使用 Java 17\n```").isEmpty());
        assertTrue(manager.extractFactsFromUserTurn("本项目使用 Java 17 吗？").isEmpty());
        assertTrue(manager.extractFactsFromUserTurn("我现在请你检查代码。").isEmpty());
        assertEquals(0, manager.getLongTermMemory().size());
    }

    @Test
    void inlineThirdPartyQuoteCannotBecomeMemoryEvenIfModelSelectsIt() {
        StubClient client = new StubClient(
                "{\"facts\":[{\"quote\":\"我喜欢英文回答\"}]}");
        MemoryManager manager = manager(client, new LongTermMemory(tempDir.toFile()));

        assertTrue(manager.extractFactsFromUserTurn(
                "读者留言说：‘我喜欢英文回答’。请分析这个观点。").isEmpty());
    }

    @Test
    void systemPropertyCanDisableInteractiveExtraction() {
        String previous = System.getProperty(AutoFactExtractor.ENABLED_PROPERTY);
        try {
            System.setProperty(AutoFactExtractor.ENABLED_PROPERTY, "false");
            assertFalse(AutoFactExtractor.enabledByConfiguration());
            System.setProperty(AutoFactExtractor.ENABLED_PROPERTY, "true");
            assertTrue(AutoFactExtractor.enabledByConfiguration());
        } finally {
            if (previous == null) System.clearProperty(AutoFactExtractor.ENABLED_PROPERTY);
            else System.setProperty(AutoFactExtractor.ENABLED_PROPERTY, previous);
        }
    }

    private MemoryManager manager(StubClient client, LongTermMemory store) {
        MemoryManager manager = new MemoryManager(client, 32_768, 128_000, store);
        manager.setProjectPath(tempDir.toString());
        manager.setAutoFactExtractionEnabled(true);
        return manager;
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
    }
}
