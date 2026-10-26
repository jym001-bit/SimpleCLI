package com.paicli.history;

import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationLedgerTest {

    @Test
    void persistsCompleteRawMessagesAsAppendOnlyJsonl(@TempDir Path tempDir) throws Exception {
        ConversationLedger ledger =
                ConversationLedger.open(tempDir.resolve("history"), "test-session");

        LlmClient.Message system = LlmClient.Message.system("system prompt");
        LlmClient.Message user = LlmClient.Message.user(List.of(
                LlmClient.ContentPart.text("inspect this"),
                LlmClient.ContentPart.imageBase64("raw-image-data", "image/png")));
        LlmClient.Message toolCall = LlmClient.Message.assistant(
                "private reasoning",
                "I will inspect it",
                List.of(new LlmClient.ToolCall(
                        "call-1",
                        new LlmClient.ToolCall.Function("read_file", "{\"path\":\"README.md\"}"))));
        LlmClient.Message toolResult = LlmClient.Message.tool("call-1", "complete tool output");
        LlmClient.Message assistant =
                LlmClient.Message.assistant("final reasoning", "final answer");

        ledger.appendMessage("react", "agent", "session_attach", system);
        ledger.appendMessage("react", "agent", "user_input", user);
        ledger.appendMessage("react", "agent", "llm_response", toolCall);
        ledger.appendMessage("react", "agent", "tool_execution", toolResult);
        ledger.appendMessage("react", "agent", "llm_response", assistant);

        List<ConversationLedger.Entry> entries = ledger.readAll();
        assertEquals(List.of("system", "user", "tool_call", "tool_result", "assistant"),
                entries.stream().map(ConversationLedger.Entry::event).toList());
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L),
                entries.stream().map(ConversationLedger.Entry::sequence).toList());
        assertEquals("react", entries.get(2).mode());
        assertEquals("agent", entries.get(2).actor());
        assertEquals("llm_response", entries.get(2).source());
        assertEquals("private reasoning", entries.get(2).message().reasoningContent());
        assertEquals("call-1", entries.get(2).message().toolCalls().get(0).id());
        assertEquals("read_file", entries.get(2).message().toolCalls().get(0).function().name());
        assertEquals("{\"path\":\"README.md\"}",
                entries.get(2).message().toolCalls().get(0).function().arguments());
        assertEquals("call-1", entries.get(3).message().toolCallId());
        assertEquals("complete tool output", entries.get(3).message().content());
        assertEquals("raw-image-data", entries.get(1).message().contentParts().get(1).imageBase64());
        assertEquals("final reasoning", entries.get(4).message().reasoningContent());
    }

    @Test
    void clearAndCompactionAppendBoundariesWithoutRewritingEarlierBytes(@TempDir Path tempDir)
            throws Exception {
        ConversationLedger ledger =
                ConversationLedger.open(tempDir.resolve("history"), "append-only");
        ledger.appendMessage("react", "agent", "user_input", LlmClient.Message.user("original"));
        byte[] originalBytes = Files.readAllBytes(ledger.file());

        ledger.appendEvent(
                "compaction",
                "react",
                "agent",
                "automatic",
                Map.of("beforeMessages", 12, "afterMessages", 5));
        ledger.appendEvent(
                "history_clear",
                "react",
                "agent",
                "slash_clear",
                Map.of("discardedViewMessages", 5));

        byte[] afterBytes = Files.readAllBytes(ledger.file());
        assertTrue(afterBytes.length > originalBytes.length);
        assertArrayEquals(originalBytes,
                java.util.Arrays.copyOf(afterBytes, originalBytes.length));

        List<ConversationLedger.Entry> entries = ledger.readAll();
        assertEquals(List.of("user", "compaction", "history_clear"),
                entries.stream().map(ConversationLedger.Entry::event).toList());
        assertEquals(12, entries.get(1).metadata().get("beforeMessages"));
        assertEquals(5, entries.get(2).metadata().get("discardedViewMessages"));
    }

    @Test
    void restrictsLedgerDirectoryAndFileOnPosixFileSystems(@TempDir Path tempDir)
            throws Exception {
        ConversationLedger ledger =
                ConversationLedger.open(tempDir.resolve("history"), "secure-session");
        assertNotNull(ledger.file());

        PosixFileAttributeView fileView =
                Files.getFileAttributeView(ledger.file(), PosixFileAttributeView.class);
        if (fileView == null) {
            return;
        }

        Set<PosixFilePermission> directoryPermissions =
                Files.getPosixFilePermissions(ledger.file().getParent());
        Set<PosixFilePermission> filePermissions =
                Files.getPosixFilePermissions(ledger.file());
        assertEquals("rwx------",
                java.nio.file.attribute.PosixFilePermissions.toString(directoryPermissions));
        assertEquals("rw-------",
                java.nio.file.attribute.PosixFilePermissions.toString(filePermissions));
    }

    @Test
    void writesOneValidRecordPerConcurrentAppend(@TempDir Path tempDir) throws Exception {
        ConversationLedger ledger =
                ConversationLedger.open(tempDir.resolve("history"), "parallel-session");
        List<Thread> writers = java.util.stream.IntStream.range(0, 20)
                .mapToObj(index -> new Thread(() -> ledger.appendMessage(
                        "team",
                        "worker-" + index,
                        "llm_response",
                        LlmClient.Message.assistant("answer-" + index))))
                .toList();

        writers.forEach(Thread::start);
        for (Thread writer : writers) {
            writer.join();
        }

        List<ConversationLedger.Entry> entries = ledger.readAll();
        assertEquals(20, entries.size());
        assertEquals(20, entries.stream().map(ConversationLedger.Entry::sequence).distinct().count());
        assertTrue(Files.readString(ledger.file(), StandardCharsets.UTF_8).endsWith("\n"));
    }

    @Test
    void continuesSequenceWhenAnExistingSessionIsReopened(@TempDir Path tempDir)
            throws Exception {
        Path history = tempDir.resolve("history");
        ConversationLedger first = ConversationLedger.open(history, "resumed-session");
        first.appendMessage("react", "agent", "user_input", LlmClient.Message.user("first"));
        byte[] original = Files.readAllBytes(first.file());

        ConversationLedger resumed = ConversationLedger.open(history, "resumed-session");
        resumed.appendMessage("react", "agent", "user_input", LlmClient.Message.user("second"));

        byte[] after = Files.readAllBytes(resumed.file());
        assertArrayEquals(original, java.util.Arrays.copyOf(after, original.length));
        assertEquals(List.of(0L, 1L),
                resumed.readAll().stream().map(ConversationLedger.Entry::sequence).toList());
    }
}
