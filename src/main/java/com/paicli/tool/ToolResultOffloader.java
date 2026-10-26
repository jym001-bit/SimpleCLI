package com.paicli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可恢复的工具输出卸载。
 *
 * <p>超过阈值的工具结果完整写入项目内 {@code .paicli/tool-outputs/<session>/} 下的会话文件，
 * 上下文里只保留大小、文件路径和首尾预览。模型需要细节时用 {@code read_file offset/limit}
 * 按段读回——信息没有丢，只是换了个地方放。放在项目根内是因为 {@code read_file} 受
 * PathGuard 限制只能读项目根之内的文件。</p>
 */
public final class ToolResultOffloader {
    private static final Logger log = LoggerFactory.getLogger(ToolResultOffloader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String ENABLED_PROPERTY = "paicli.tool.offload.enabled";
    static final String ENABLED_ENV = "PAICLI_TOOL_OFFLOAD_ENABLED";
    static final String THRESHOLD_PROPERTY = "paicli.tool.offload.threshold.chars";
    static final String THRESHOLD_ENV = "PAICLI_TOOL_OFFLOAD_THRESHOLD_CHARS";
    static final int DEFAULT_THRESHOLD_CHARS = 32_000;
    static final int MIN_THRESHOLD_CHARS = 2_000;
    static final String OUTPUT_DIR = ".paicli/tool-outputs";

    private static final int HEAD_PREVIEW_CHARS = 2_000;
    private static final int TAIL_PREVIEW_CHARS = 800;
    private static final int SUGGESTED_READ_LINES = 200;

    private final boolean enabled;
    private final int thresholdChars;
    private final String sessionId;
    private final AtomicInteger sequence = new AtomicInteger();
    private volatile Path projectRoot;

    public ToolResultOffloader(Path projectRoot, boolean enabled, int thresholdChars) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.enabled = enabled;
        this.thresholdChars = Math.max(MIN_THRESHOLD_CHARS, thresholdChars);
        this.sessionId = "session-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    public static ToolResultOffloader fromConfiguration(Path projectRoot) {
        return new ToolResultOffloader(projectRoot, configuredEnabled(), configuredThreshold());
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int thresholdChars() {
        return thresholdChars;
    }

    public void setProjectRoot(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
    }

    /** 当前会话的卸载目录（相对项目根），用于提示词和测试。 */
    public String sessionDirectory() {
        return OUTPUT_DIR + "/" + sessionId;
    }

    /**
     * 结果超过阈值时写入会话文件并返回摘要；否则原样返回。
     *
     * @param argumentsJson 用于识别 read_file 的显式分段读取，避免“卸载 → 读回 → 再卸载”的循环
     */
    public String offloadIfOversized(String toolName, String argumentsJson, String content) {
        if (!enabled || content == null || content.length() <= thresholdChars || isExempt(toolName, argumentsJson)) {
            return content;
        }
        return offload(toolName, content, 0);
    }

    /**
     * 无条件卸载，供自带截断预算的工具（如 execute_command）在截断时保留完整输出。
     *
     * @param inlineHeadChars 摘要中保留的开头字符数；0 表示使用默认预览长度
     */
    public String offload(String toolName, String content, int inlineHeadChars) {
        try {
            Path file = write(toolName, content);
            return summary(toolName, content, file, inlineHeadChars > 0 ? inlineHeadChars : HEAD_PREVIEW_CHARS);
        } catch (IOException | RuntimeException e) {
            log.warn("工具输出卸载失败，回退为截断: tool={}, error={}", toolName, e.getMessage());
            return content.substring(0, Math.min(content.length(), thresholdChars))
                    + "\n...(输出过大且写入会话文件失败，已截断，原始 " + content.length() + " 字符)";
        }
    }

    private boolean isExempt(String toolName, String argumentsJson) {
        if (!"read_file".equals(toolName)) {
            return false;
        }
        JsonNode args = parseArgs(argumentsJson);
        if (args.hasNonNull("offset") || args.hasNonNull("limit")) {
            return true;
        }
        String path = args.path("path").asText("").replace('\\', '/');
        return path.startsWith(OUTPUT_DIR) || path.startsWith("./" + OUTPUT_DIR);
    }

    private Path write(String toolName, String content) throws IOException {
        Path outputRoot = projectRoot.resolve(OUTPUT_DIR);
        Path sessionDir = outputRoot.resolve(sessionId);
        Files.createDirectories(sessionDir);
        Path gitignore = outputRoot.resolve(".gitignore");
        if (!Files.exists(gitignore)) {
            // 卸载文件可能包含网页正文、命令输出等敏感内容，默认不进版本库。
            Files.writeString(gitignore, "*\n", StandardCharsets.UTF_8);
        }
        String fileName = String.format("%03d-%s.txt", sequence.incrementAndGet(), sanitize(toolName));
        Path file = sessionDir.resolve(fileName);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private String summary(String toolName, String content, Path file, int headChars) {
        String relative = projectRoot.relativize(file).toString().replace('\\', '/');
        long lines = content.lines().count();
        StringBuilder sb = new StringBuilder();
        sb.append("[工具输出过大，完整内容已卸载到会话文件]\n")
                .append("tool: ").append(toolName).append("\n")
                .append("原始大小: ").append(content.length()).append(" 字符 / ").append(lines).append(" 行\n")
                .append("文件: ").append(relative).append("\n")
                .append("读回: read_file {\"path\":\"").append(relative)
                .append("\",\"offset\":1,\"limit\":").append(SUGGESTED_READ_LINES).append("}，按需调整 offset 分段读取\n")
                .append("--- 开头预览 ---\n")
                .append(head(content, headChars));
        if (content.length() > headChars + TAIL_PREVIEW_CHARS) {
            sb.append("\n--- 结尾预览 ---\n").append(tail(content, TAIL_PREVIEW_CHARS));
        }
        return sb.toString();
    }

    private static String head(String content, int chars) {
        if (content.length() <= chars) {
            return content;
        }
        String cut = content.substring(0, chars);
        int newline = cut.lastIndexOf('\n');
        return newline > chars / 2 ? cut.substring(0, newline) : cut;
    }

    private static String tail(String content, int chars) {
        String cut = content.substring(content.length() - chars);
        int newline = cut.indexOf('\n');
        return newline >= 0 && newline < chars / 2 ? cut.substring(newline + 1) : cut;
    }

    private static String sanitize(String toolName) {
        String name = toolName == null || toolName.isBlank() ? "tool" : toolName;
        String safe = name.replaceAll("[^A-Za-z0-9_.-]", "_");
        return safe.length() > 60 ? safe.substring(0, 60) : safe;
    }

    private static JsonNode parseArgs(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(argumentsJson);
        } catch (IOException e) {
            return MAPPER.createObjectNode();
        }
    }

    private static boolean configuredEnabled() {
        String raw = configValue(ENABLED_PROPERTY, ENABLED_ENV);
        if (raw == null) {
            return true;
        }
        String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return !("false".equals(value) || "0".equals(value) || "no".equals(value) || "off".equals(value));
    }

    private static int configuredThreshold() {
        String raw = configValue(THRESHOLD_PROPERTY, THRESHOLD_ENV);
        if (raw == null) {
            return DEFAULT_THRESHOLD_CHARS;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_THRESHOLD_CHARS;
        }
    }

    private static String configValue(String property, String env) {
        String raw = System.getProperty(property);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(env);
        }
        return raw == null || raw.isBlank() ? null : raw;
    }
}
