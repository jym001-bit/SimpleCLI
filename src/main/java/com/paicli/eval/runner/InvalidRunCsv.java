package com.paicli.eval.runner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * 无效运行的原始记录：只追加，不覆盖、不删除。
 */
public final class InvalidRunCsv {

    private static final String HEADER =
            "taskId,run,attempt,exitReason,outcome,requests,totalTokens,elapsedSeconds,recordedAt,note\n";

    private InvalidRunCsv() {
    }

    public static void append(Path file, RunResult result, String note) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        boolean needsHeader = !Files.exists(file) || Files.size(file) == 0;
        StringBuilder line = new StringBuilder();
        if (needsHeader) {
            line.append(HEADER);
        }
        line.append(csv(result.taskId())).append(',')
                .append(result.run()).append(',')
                .append(result.attempt()).append(',')
                .append(csv(result.exitReason())).append(',')
                .append(csv(result.outcome())).append(',')
                .append(result.requests()).append(',')
                .append(result.inputTokens() + result.outputTokens()).append(',')
                .append(String.format(java.util.Locale.ROOT, "%.2f", result.elapsedSeconds())).append(',')
                .append(Instant.now()).append(',')
                .append(csv(note)).append('\n');
        Files.writeString(file, line.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
