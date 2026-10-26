package com.paicli.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MemoryFreshnessTest {
    private static final Instant NOW = Instant.parse("2026-09-23T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    @Test
    void lastVerifiedDefaultsToWriteTimestampAndUpdatesImmutably() {
        Instant written = Instant.parse("2026-01-01T00:00:00Z");
        MemoryEntry entry = new MemoryEntry("f1", "项目使用 Java 17", MemoryEntry.MemoryType.FACT, written, Map.of(), 5);

        MemoryEntry verified = entry.withLastVerifiedAt(NOW);

        assertEquals(written, entry.getLastVerifiedAt());
        assertEquals(written, verified.getTimestamp());
        assertEquals(NOW, verified.getLastVerifiedAt());
        assertNotSame(entry, verified);
    }

    @Test
    void lastVerifiedSurvivesDiskRoundTrip() {
        Instant written = Instant.parse("2026-01-01T00:00:00Z");
        LongTermMemory memory = new LongTermMemory(tempDir.toFile());
        memory.store(new MemoryEntry("f1", "项目使用 Java 17", MemoryEntry.MemoryType.FACT,
                written, NOW, Map.of(), 5));

        MemoryEntry reloaded = new LongTermMemory(tempDir.toFile()).retrieve("f1").orElseThrow();

        assertEquals(written, reloaded.getTimestamp());
        assertEquals(NOW, reloaded.getLastVerifiedAt());
    }

    @Test
    void legacyJsonWithoutLastVerifiedFallsBackToTimestamp() throws Exception {
        Files.writeString(tempDir.resolve("long_term_memory.json"), new ObjectMapper().writeValueAsString(List.of(
                Map.of("id", "legacy", "content", "旧记忆", "type", "FACT",
                        "timestamp", "2025-05-01T00:00:00Z", "metadata", Map.of(), "tokenCount", 2))));

        MemoryEntry entry = new LongTermMemory(tempDir.toFile()).retrieve("legacy").orElseThrow();

        assertEquals(Instant.parse("2025-05-01T00:00:00Z"), entry.getLastVerifiedAt());
    }

    @Test
    void retrieverLabelsEntriesNotVerifiedWithinConfiguredPeriod() {
        LongTermMemory memory = new LongTermMemory(tempDir.toFile());
        memory.store(new MemoryEntry("old", "项目构建使用 Java 17", MemoryEntry.MemoryType.FACT,
                NOW.minus(Duration.ofDays(90)), Map.of(), 5));
        memory.store(new MemoryEntry("fresh", "项目构建使用 Maven", MemoryEntry.MemoryType.FACT,
                NOW.minus(Duration.ofDays(90)), NOW.minus(Duration.ofDays(3)), Map.of(), 5));
        MemoryRetriever retriever = new MemoryRetriever(memory, Duration.ofDays(30), CLOCK);

        String context = retriever.buildContextForQuery("项目构建", 500);

        String oldLine = lineContaining(context, "Java 17");
        String freshLine = lineContaining(context, "Maven");
        assertTrue(oldLine.contains("[可能已过时]"), oldLine);
        assertTrue(oldLine.contains("写入 2026-06-25"), oldLine);
        assertTrue(oldLine.contains("已超过 30 天未核实"), oldLine);
        assertFalse(freshLine.contains("可能已过时"), freshLine);
        assertTrue(freshLine.contains("最后核实 2026-09-20"), freshLine);
        assertTrue(context.contains("线索而不是事实"));
    }

    @Test
    void nonPositiveStalePeriodDisablesLabel() {
        LongTermMemory memory = new LongTermMemory(tempDir.toFile());
        memory.store(new MemoryEntry("old", "项目构建使用 Java 17", MemoryEntry.MemoryType.FACT,
                NOW.minus(Duration.ofDays(900)), Map.of(), 5));
        MemoryRetriever retriever = new MemoryRetriever(memory, Duration.ZERO, CLOCK);

        assertFalse(retriever.buildContextForQuery("项目构建", 500).contains("可能已过时"));
    }

    @Test
    void markVerifiedRefreshesOnlyVerificationTime() {
        LongTermMemory memory = new LongTermMemory(tempDir.toFile());
        Instant written = NOW.minus(Duration.ofDays(60));
        memory.store(new MemoryEntry("f1", "项目构建使用 Java 17", MemoryEntry.MemoryType.FACT, written, Map.of(), 5));

        MemoryEntry verified = memory.markVerified("f1", NOW).orElseThrow();

        assertEquals(written, verified.getTimestamp());
        assertEquals(NOW, memory.retrieve("f1").orElseThrow().getLastVerifiedAt());
        assertTrue(memory.markVerified("missing", NOW).isEmpty());
    }

    private static String lineContaining(String text, String needle) {
        return text.lines().filter(line -> line.contains(needle)).findFirst().orElseThrow();
    }
}
