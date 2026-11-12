package com.paicli.eval.runner;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InvalidRunCsvTest {

    private static RunResult result(String taskId, int run, int attempt, String reason) {
        return new RunResult(taskId, "stub", run, attempt, false, false, "run_failed",
                4, reason, 1, 10L, 5L, 0.5, 0, "", "boom",
                false, "skip", "boom");
    }

    @Test
    void appendsWithoutOverwritingAndWritesHeaderOnce() throws Exception {
        Path file = Files.createTempFile("invalid-runs-", ".csv");
        Files.delete(file);

        InvalidRunCsv.append(file, result("t-x", 1, 1, "error"), "auto-retry");
        InvalidRunCsv.append(file, result("t-x", 1, 2, "error"), "auto-retry");

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(3, lines.size(), "1 行表头 + 2 行记录");
        assertEquals("taskId,run,attempt,exitReason,outcome,requests,totalTokens,elapsedSeconds,recordedAt,note",
                lines.get(0));
        assertEquals(1, (int) lines.stream().filter(l -> l.startsWith("taskId,")).count());
        Files.deleteIfExists(file);
    }
}
