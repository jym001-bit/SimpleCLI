package com.paicli.memory;

import com.paicli.llm.LlmClient;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolResultOffloader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 记忆/压缩 A 层：只补现有测试没有覆盖的边界，不重复已有断言。 */
class MemoryCompressionBoundaryTest {

    @TempDir
    Path tempDir;

    private static final Pattern OFFLOAD_PATH = Pattern.compile("(\\.paicli/tool-outputs/[^\\s，]+)");

    @Test
    void clearerKeepsMultiCallAndOutOfOrderPairs() {
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("system"));
        history.add(LlmClient.Message.user("q"));
        history.add(LlmClient.Message.assistant("", List.of(
                call("a", "read_file"), call("b", "read_file"))));
        history.add(LlmClient.Message.tool("b", big("b-result")));
        history.add(LlmClient.Message.tool("a", big("a-result")));
        history.add(LlmClient.Message.assistant("done"));

        ToolResultClearer clearer = new ToolResultClearer(
                new ToolResultClearer.Config(true, 0, 0, Set.of()));
        clearer.clearNow(history);

        assertPairs(history, Set.of("a", "b"));
        assertTrue(history.get(3).content().startsWith(ToolResultClearer.PLACEHOLDER_PREFIX));
        assertTrue(history.get(4).content().startsWith(ToolResultClearer.PLACEHOLDER_PREFIX));
    }

    @Test
    void offloadReadBackCoversOffsetBoundsAndFullHash() throws Exception {
        ToolRegistry registry = registry();
        String content = numberedLines(120, "FACT-");
        String summary = offload(registry, content);
        Path file = offloadFile(summary);
        assertEquals(sha(content.getBytes(StandardCharsets.UTF_8)),
                sha(Files.readAllBytes(file)), "落盘文件必须与原始字节一致");

        assertTrue(read(registry, file, 1, 2).contains("line-0001"));
        assertTrue(read(registry, file, 120, 1).contains("line-0120"));
        assertTrue(read(registry, file, 121, 1).contains("offset 超出范围"));
        assertTrue(read(registry, file, 118, 100).contains("line-0120"));
    }

    @Test
    void offloadReadBackCoversEmptyUtf8CrlfAndLongLine() throws Exception {
        ToolRegistry registry = registry();
        Path empty = tempDir.resolve("empty.txt");
        Files.createFile(empty);
        assertTrue(read(registry, empty, 1, 1).contains("offset 超出范围"));

        String utf8 = "第一行-FACT-UTF8-中😀\n第二行-内容\n";
        Path utf8File = tempDir.resolve("utf8.txt");
        Files.writeString(utf8File, utf8, StandardCharsets.UTF_8);
        String utf8Read = read(registry, utf8File, 1, 1);
        assertTrue(utf8Read.contains("FACT-UTF8"), utf8Read);

        String crlf = "first\r\nsecond\r\nthird\r\n";
        Path crlfFile = tempDir.resolve("crlf.txt");
        Files.writeString(crlfFile, crlf, StandardCharsets.UTF_8);
        assertTrue(read(registry, crlfFile, 2, 1).contains("second"));

        String longLine = "FACT-LONG-" + "x".repeat(5_000);
        Path longFile = tempDir.resolve("long.txt");
        Files.writeString(longFile, longLine, StandardCharsets.UTF_8);
        String longRead = read(registry, longFile, 1, 1);
        assertTrue(longRead.contains("FACT-LONG"), "单行超长不能被截断");
    }

    @Test
    void clearedPlaceholderCanReadBackRandomFact() throws Exception {
        ToolRegistry registry = registry();
        String fact = randomFact("RECOVER");
        String content = numberedLines(150, "") + "\n" + fact;
        String summary = offload(registry, content);
        Path file = offloadFile(summary);

        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("system"));
        history.add(LlmClient.Message.user("q"));
        history.add(LlmClient.Message.assistant("", List.of(call("a", "read_file"))));
        history.add(LlmClient.Message.tool("a", summary));
        history.add(LlmClient.Message.assistant("done"));

        new ToolResultClearer(new ToolResultClearer.Config(true, 0, 0, Set.of()))
                .clearNow(history);
        String placeholder = history.get(3).content();
        Matcher matcher = OFFLOAD_PATH.matcher(placeholder);
        assertTrue(matcher.find(), placeholder);

        Path fromPlaceholder = tempDir.resolve(matcher.group(1).trim());
        assertTrue(Files.readString(fromPlaceholder).contains(fact));
        assertTrue(registry.executeTool("read_file", "{\"path\":\""
                + matcher.group(1).trim() + "\",\"offset\":1,\"limit\":10000}").contains(fact));
        assertNotNull(file);
    }

    @Test
    void summaryKeepsParallelPairAndEarlyFact() throws Exception {
        String fact = randomFact("SUMMARY");
        FakeClient fake = new FakeClient(fact);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("system"));
        history.add(LlmClient.Message.user("first turn"));
        history.add(LlmClient.Message.assistant("", List.of(
                call("p1", "read_file"), call("p2", "read_file"))));
        history.add(LlmClient.Message.tool("p1", fact + "\n" + "y".repeat(4_000)));
        history.add(LlmClient.Message.tool("p2", "second result"));
        history.add(LlmClient.Message.assistant("first done"));
        history.add(LlmClient.Message.user("second turn"));
        history.add(LlmClient.Message.assistant("second done"));
        history.add(LlmClient.Message.user("third turn"));
        history.add(LlmClient.Message.assistant("third done"));

        int before = TokenBudget.estimateMessagesTokens(history);
        ConversationHistoryCompactor compactor = new ConversationHistoryCompactor(fake, 2);
        assertTrue(compactor.compactIfNeeded(history, 1));
        assertTrue(TokenBudget.estimateMessagesTokens(history) < before);
        assertPairs(history, Set.of());
        String all = history.stream().map(LlmClient.Message::content)
                .filter(c -> c != null).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(all.contains(fact), "摘要输入/结果应保留早期随机事实");
        assertTrue(fake.lastSummaryInput().contains(fact), "摘要请求应包含早期事实消息");
    }

    @Test
    void summaryHandlesOutOfOrderParallelResultsAtBoundary() throws Exception {
        String fact = randomFact("ORDER");
        FakeClient fake = new FakeClient(fact);
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("system"));
        history.add(LlmClient.Message.user("old"));
        history.add(LlmClient.Message.assistant("", List.of(
                call("o1", "read_file"), call("o2", "read_file"))));
        history.add(LlmClient.Message.tool("o2", "second" + "z".repeat(2_000)));
        history.add(LlmClient.Message.tool("o1", fact + "z".repeat(2_000)));
        history.add(LlmClient.Message.assistant("old done"));
        history.add(LlmClient.Message.user("middle"));
        history.add(LlmClient.Message.assistant("middle done"));
        history.add(LlmClient.Message.user("recent"));
        history.add(LlmClient.Message.assistant("recent done"));

        assertTrue(new ConversationHistoryCompactor(fake, 2).compactIfNeeded(history, 1));
        assertPairs(history, Set.of());
        assertTrue(history.stream().map(LlmClient.Message::content)
                .filter(c -> c != null).anyMatch(c -> c.contains(fact)));
    }

    @Test
    void memoryVariantsAppearOnceInContextAndNoCapFound() {
        String oldHome = System.getProperty("user.home");
        String oldDir = System.getProperty("paicli.memory.dir");
        System.setProperty("user.home", tempDir.toString());
        System.setProperty("paicli.memory.dir", tempDir.resolve("memory").toString());
        try {
            MemoryManager manager = new MemoryManager(new FakeClient("unused"));
            manager.setProjectPath(tempDir.toString());
            String fact = randomFact("DEDUP");
            manager.storeFact(fact);
            manager.storeFact("  " + fact + "  \n");

            String context = manager.buildContextForQuery(fact, 20_000);
            assertEquals(1, occurrences(context, fact), context);
            assertEquals(1, manager.searchLongTerm(fact, 20).size());

            for (int i = 0; i < 64; i++) manager.storeFact(randomFact("CAP" + i));
            assertEquals(65, manager.listLongTerm().size(), "未发现条数上限；只验证 64 条不丢失");
        } finally {
            restore("user.home", oldHome);
            restore("paicli.memory.dir", oldDir);
        }
    }

    private ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        registry.setToolResultOffloader(new ToolResultOffloader(tempDir, true, 2_000));
        return registry;
    }

    private String offload(ToolRegistry registry, String content) {
        return registry.getToolResultOffloader()
                .offloadIfOversized("grep_code", "{}", content);
    }

    private Path offloadFile(String summary) {
        Matcher matcher = OFFLOAD_PATH.matcher(summary);
        assertTrue(matcher.find(), summary);
        return tempDir.resolve(matcher.group(1).trim());
    }

    private String read(ToolRegistry registry, Path file, int offset, int limit) {
        String path = tempDir.relativize(file).toString().replace('\\', '/');
        return registry.executeTool("read_file", "{\"path\":\"" + path
                + "\",\"offset\":" + offset + ",\"limit\":" + limit + "}");
    }

    private static void assertPairs(List<LlmClient.Message> history, Set<String> expected) {
        Set<String> calls = new HashSet<>();
        Set<String> results = new HashSet<>();
        for (LlmClient.Message message : history) {
            if (message.toolCalls() != null) {
                message.toolCalls().forEach(call -> calls.add(call.id()));
            }
            if ("tool".equals(message.role())) results.add(message.toolCallId());
        }
        for (String result : results) {
            assertTrue(calls.contains(result), "孤立 tool result: " + result);
        }
        for (String call : calls) {
            assertTrue(results.contains(call), "孤立 tool call: " + call);
        }
        assertTrue(calls.containsAll(expected));
    }

    private static LlmClient.ToolCall call(String id, String name) {
        return new LlmClient.ToolCall(id, new LlmClient.ToolCall.Function(name, "{}"));
    }

    private static String numberedLines(int count, String prefix) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            sb.append(String.format("%sline-%04d ", prefix, i)).append("x".repeat(40)).append('\n');
        }
        return sb.toString();
    }

    private static String big(String value) {
        return value + "x".repeat(1_000);
    }

    private static String randomFact(String tag) {
        return "FACT-" + tag + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static int occurrences(String text, String value) {
        int count = 0, index = 0;
        while ((index = text.indexOf(value, index)) >= 0) {
            count++;
            index += value.length();
        }
        return count;
    }

    private static void restore(String key, String old) {
        if (old == null) System.clearProperty(key);
        else System.setProperty(key, old);
    }

    private static final class FakeClient implements LlmClient {
        private final String summary;
        private String summaryInput = "";

        FakeClient(String summary) {
            this.summary = summary;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            summaryInput = messages.stream().map(Message::content)
                    .filter(c -> c != null).reduce("", (a, b) -> a + "\n" + b);
            return new ChatResponse("assistant",
                    "## 当前目标与成功条件\nkeep\n## 用户要求与已确认决定\nkeep\n"
                            + "## 已完成工作及证据\n" + summary + "\n## 未解决问题与下一步\nnone",
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

        String lastSummaryInput() { return summaryInput; }
    }
}
