package com.paicli.eval.headless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.tool.ToolRegistry.ToolExecutionResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 评测侧本地 JSONL 工具调用日志。只在详细模式且给出绝对路径时启用。
 *
 * <p>日志是 Langfuse 之外的独立消费者：不创建 span、不修改工具结果、写失败只报 stderr。</p>
 */
final class EvalToolCallLog {

    static final int COMMAND_LIMIT = 300;
    static final int RESULT_PREVIEW_LIMIT = 200;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern BEARER =
            Pattern.compile("(?i)(bearer\\s+)[^\\s\"'}]+");
    private static final Pattern SHORT_KEY =
            Pattern.compile("(?i)\\b(sk-|pk-lf-|sk-lf-)[A-Za-z0-9_\\-]{8,}");
    private static final Pattern KEY_ASSIGNMENT =
            Pattern.compile("(?i)((?:[A-Za-z0-9_]*(?:API_KEY|TOKEN|SECRET|PASSWORD|ACCESS_KEY|PRIVATE_KEY)[A-Za-z0-9_]*)\\s*[:=]\\s*)(\"?)[^\\s,;\"'}]+");
    private static final Pattern GENERIC_ASSIGNMENT =
            Pattern.compile("(?i)((?:token|password|secret|authorization|api[_-]?key)\\s*[:=]\\s*)(\"?)[^\\s,;\"'}]+");
    private static final Pattern URL_TOKEN =
            Pattern.compile("(?i)([?&](?:api_key|token|access_token|key)=)[^&\\s]+");
    private static final Pattern ENV_DUMP =
            Pattern.compile("(?i)(^|[\\s;&|])(env|printenv|set)(\\s|$)|get-childitem\\s+env:|gci\\s+env:");

    private final Path logFile;
    private boolean failureReported;

    EvalToolCallLog(Path logFile) {
        if (logFile == null) {
            throw new IllegalArgumentException("tool log path 不能为空");
        }
        if (!logFile.isAbsolute()) {
            throw new IllegalArgumentException("tool log path 必须是绝对路径: " + logFile);
        }
        this.logFile = logFile.normalize();
    }

    synchronized void record(List<ToolExecutionResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        try {
            Path parent = logFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            StringBuilder lines = new StringBuilder();
            for (ToolExecutionResult result : results) {
                lines.append(toJson(result)).append(System.lineSeparator());
            }
            Files.writeString(logFile, lines.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            if (!failureReported) {
                failureReported = true;
                System.err.println("⚠️ 评测工具调用日志写入失败: " + e.getMessage());
            }
        }
    }

    private String toJson(ToolExecutionResult result) throws IOException {
        if (result == null) {
            return "{}";
        }
        JsonNode arguments = parseArguments(result.argumentsJson());
        String path = text(arguments, "path");
        String command = "execute_command".equals(result.name()) ? text(arguments, "command") : null;

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", Instant.now().toString());
        entry.put("tool", result.name());
        entry.put("path", path == null ? null : redact(path));
        entry.put("command", command == null ? null : truncate(redact(command), COMMAND_LIMIT));
        entry.put("success", result.successful());
        entry.put("result_preview", resultPreview(path, command, result.result()));
        entry.put("timed_out", result.timedOut());
        return MAPPER.writeValueAsString(entry);
    }

    private String resultPreview(String path, String command, String result) {
        if (isSensitiveFile(path)) {
            return "[REDACTED_SENSITIVE_FILE]";
        }
        if (command != null && ENV_DUMP.matcher(command).find()) {
            return "[REDACTED_ENV_DUMP]";
        }
        return truncate(redact(result == null ? "" : result), RESULT_PREVIEW_LIMIT);
    }

    static String redact(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        String redacted = value;
        redacted = BEARER.matcher(redacted).replaceAll("$1***");
        redacted = SHORT_KEY.matcher(redacted).replaceAll("***");
        redacted = KEY_ASSIGNMENT.matcher(redacted).replaceAll("$1$2***");
        redacted = GENERIC_ASSIGNMENT.matcher(redacted).replaceAll("$1$2***");
        redacted = URL_TOKEN.matcher(redacted).replaceAll("$1***");
        return redacted;
    }

    static String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private static boolean isSensitiveFile(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        return name.equals(".env") || name.startsWith(".env.")
                || name.endsWith(".pem") || name.endsWith(".key")
                || name.equals("id_rsa") || name.equals("id_ed25519")
                || name.equals(".netrc") || name.contains("credentials");
    }

    private static JsonNode parseArguments(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            return node != null && node.isObject() ? node : null;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
