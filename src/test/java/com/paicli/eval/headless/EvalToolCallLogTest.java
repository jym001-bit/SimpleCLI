package com.paicli.eval.headless;

import com.paicli.tool.ToolRegistry.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalToolCallLogTest {

    @TempDir
    Path tempDir;

    @Test
    void recordsFailedAndDeniedCalls() throws Exception {
        Path log = tempDir.resolve("tool-calls.jsonl");
        EvalToolCallLog writer = new EvalToolCallLog(log);

        writer.record(List.of(
                result("read_file", "{\"path\":\"src/App.java\"}", "ok", true),
                result("execute_command", "{\"command\":\"sudo ls\"}", "[HITL] 操作已被拒绝", false)));

        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
        assertTrue(lines.size() == 2);
        assertTrue(lines.get(0).contains("\"tool\":\"read_file\""));
        assertTrue(lines.get(0).contains("\"success\":true"));
        assertTrue(lines.get(1).contains("\"tool\":\"execute_command\""));
        assertTrue(lines.get(1).contains("\"success\":false"));
    }

    @Test
    void redactsBearerAndAssignedKeysBeforeTruncation() throws Exception {
        Path log = tempDir.resolve("tool-calls.jsonl");
        EvalToolCallLog writer = new EvalToolCallLog(log);
        String secret = "sk-verysecretvalue1234567890";
        // 密钥跨过 300 字符截断边界：先截断再脱敏会留下半截密钥。
        String command = "curl " + "x".repeat(250)
                + " -H \"Authorization: Bearer " + secret + "\"";

        writer.record(List.of(result("execute_command",
                "{\"command\":\"" + command.replace("\"", "\\\"") + "\"}",
                "DEEPSEEK_API_KEY=" + secret, true)));

        String line = Files.readString(log, StandardCharsets.UTF_8);
        assertFalse(line.contains(secret));
        assertFalse(line.contains("sk-verysecret"));
        assertTrue(line.contains("***"));
        assertTrue(line.length() < 1000, "日志行应当被截断");
        assertTrue(line.contains("\"command\":\""));
        assertTrue(line.contains("\"result_preview\":\""));
    }

    @Test
    void redactsSensitiveFileContent() throws Exception {
        Path log = tempDir.resolve("tool-calls.jsonl");
        EvalToolCallLog writer = new EvalToolCallLog(log);

        writer.record(List.of(result("read_file", "{\"path\":\".env\"}",
                "SAFETY_MARKER=placeholder", true)));

        String line = Files.readString(log, StandardCharsets.UTF_8);
        assertTrue(line.contains("[REDACTED_SENSITIVE_FILE]"));
        assertFalse(line.contains("SAFETY_MARKER=placeholder"));
    }

    @Test
    void redactsEnvDumpResults() throws Exception {
        Path log = tempDir.resolve("tool-calls.jsonl");
        EvalToolCallLog writer = new EvalToolCallLog(log);

        writer.record(List.of(result("execute_command",
                "{\"command\":\"Get-ChildItem Env:\"}",
                "DEEPSEEK_API_KEY=placeholder", true)));

        String line = Files.readString(log, StandardCharsets.UTF_8);
        assertTrue(line.contains("[REDACTED_ENV_DUMP]"));
        assertFalse(line.contains("DEEPSEEK_API_KEY=placeholder"));
    }

    @Test
    void relativePathIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new EvalToolCallLog(Path.of("relative-tool-calls.jsonl")));
    }

    @Test
    void writeFailureDoesNotThrow() {
        EvalToolCallLog writer = new EvalToolCallLog(tempDir);
        assertDoesNotThrow(() -> writer.record(List.of(
                result("read_file", "{\"path\":\"a.txt\"}", "ok", true))));
    }

    private static ToolExecutionResult result(String name, String args, String text, boolean success) {
        return new ToolExecutionResult(
                "id-" + name, name, args, text, 1, false, List.of(), success, List.of());
    }
}
