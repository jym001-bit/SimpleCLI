package com.paicli.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * /resume 的读取侧：在会话记录目录里找出当前项目以前的 CLI 会话，并还原当时发给模型的 ReAct 对话。
 *
 * <p>还原规则：按顺序重放 react 模式下 Agent 追加的消息；遇到 /clear 清空，遇到检查点
 * （压缩、清理、恢复之后记下的完整对话）整体替换。system prompt 不还原，由当前项目重新生成。
 * 最后一个完整回答之后的消息属于没跑完的一轮，丢弃，避免把不成对的工具调用发给模型。
 */
public final class SessionResume {
    public static final int LIST_LIMIT = 10;
    private static final String CLI_CHANNEL = "cli";
    private static final int HEADER_LINES = 5;
    private static final int MAX_SCANNED_FILES = 200;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(SessionResume.class);

    public record SessionSummary(String sessionId, Path file, long lastActiveMillis,
                                 int messageCount, String lastQuestion) {
    }

    public record RestoredConversation(String sessionId, long lastActiveMillis,
                                       List<LlmClient.Message> messages,
                                       int droppedTrailingMessages,
                                       String lastQuestion) {
        public boolean isEmpty() {
            return messages.isEmpty();
        }
    }

    private SessionResume() {
    }

    /** CLI 启动时调用：记下项目路径，之后 /resume 只列出同一项目的会话。 */
    public static void recordSessionStart(ConversationLedger ledger, String projectPath) {
        if (ledger == null || !ledger.isEnabled()) {
            return;
        }
        ledger.appendEvent(
                ConversationLedger.SESSION_START_EVENT,
                "react",
                "cli",
                "startup",
                Map.of("channel", CLI_CHANNEL, "projectPath", normalizePath(projectPath)));
    }

    /** 按最后活动时间倒序列出当前项目可以恢复的会话，排除正在使用的会话。 */
    public static List<SessionSummary> listSessions(Path directory, String projectPath,
                                                    String excludeSessionId, int limit) throws IOException {
        if (directory == null || !Files.isDirectory(directory) || limit <= 0) {
            return List.of();
        }
        String project = normalizePath(projectPath);
        List<SessionSummary> result = new ArrayList<>();
        for (Path file : recentFiles(directory)) {
            String sessionId = sessionId(file);
            if (sessionId.equals(excludeSessionId) || !isCliSessionOf(file, project)) {
                continue;
            }
            List<ConversationLedger.Entry> entries = ConversationLedger.readEntriesSkippingBrokenLines(file);
            RestoredConversation restored = rebuild(sessionId, entries);
            if (restored.isEmpty()) {
                continue;
            }
            result.add(new SessionSummary(
                    sessionId,
                    file,
                    restored.lastActiveMillis(),
                    restored.messages().size(),
                    restored.lastQuestion()));
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    /**
     * 按 /resume 的参数找到会话并还原：数字是 {@link #listSessions} 里的序号（从 1 开始），
     * 否则当作会话 ID。只接受当前项目的 CLI 会话。
     */
    public static Optional<RestoredConversation> load(Path directory, String projectPath,
                                                      String excludeSessionId, String selector) throws IOException {
        if (selector == null || selector.isBlank()) {
            return Optional.empty();
        }
        String value = selector.trim();
        if (value.matches("\\d+")) {
            int index = Integer.parseInt(value);
            List<SessionSummary> sessions = listSessions(directory, projectPath, excludeSessionId, LIST_LIMIT);
            if (index < 1 || index > sessions.size()) {
                return Optional.empty();
            }
            SessionSummary summary = sessions.get(index - 1);
            return Optional.of(rebuild(summary.sessionId(), ConversationLedger.readEntriesSkippingBrokenLines(summary.file())));
        }
        if (directory == null || !value.matches("[A-Za-z0-9._-]+") || value.equals(excludeSessionId)) {
            return Optional.empty();
        }
        Path file = directory.resolve(value + ".jsonl");
        if (!Files.isRegularFile(file) || !isCliSessionOf(file, normalizePath(projectPath))) {
            return Optional.empty();
        }
        RestoredConversation restored = rebuild(value, ConversationLedger.readEntriesSkippingBrokenLines(file));
        return restored.isEmpty() ? Optional.empty() : Optional.of(restored);
    }

    static RestoredConversation rebuild(String sessionId, List<ConversationLedger.Entry> entries) {
        List<LlmClient.Message> view = new ArrayList<>();
        long lastActive = 0L;
        for (ConversationLedger.Entry entry : entries) {
            lastActive = Math.max(lastActive, entry.timestamp());
            if (!"react".equals(entry.mode())) {
                continue;
            }
            if (ConversationLedger.HISTORY_CLEAR_EVENT.equals(entry.event())) {
                view.clear();
                continue;
            }
            if (ConversationLedger.VIEW_CHECKPOINT_EVENT.equals(entry.event())) {
                view.clear();
                Object messages = entry.metadata() == null ? null : entry.metadata().get("messages");
                ConversationLedger.messagesFromMetadata(messages).stream()
                        .filter(message -> message != null && !"system".equals(message.role()))
                        .forEach(view::add);
                continue;
            }
            LlmClient.Message message = entry.message();
            if (message == null || !"agent".equals(entry.actor()) || "system".equals(message.role())) {
                continue;
            }
            view.add(message);
        }

        int lastAnswer = -1;
        for (int i = view.size() - 1; i >= 0; i--) {
            if (isFinalAnswer(view.get(i))) {
                lastAnswer = i;
                break;
            }
        }
        List<LlmClient.Message> kept = view.subList(0, lastAnswer + 1).stream()
                .map(SessionResume::asSent)
                .toList();
        return new RestoredConversation(
                sessionId, lastActive, kept, view.size() - kept.size(), lastQuestion(entries, kept));
    }

    /** 与实时对话保持一致：最终回答不带思考内容，历史图片只留文字说明。 */
    private static LlmClient.Message asSent(LlmClient.Message message) {
        LlmClient.Message withoutImages = message.withoutImageContent();
        return isFinalAnswer(withoutImages) ? withoutImages.withoutReasoningContent() : withoutImages;
    }

    private static boolean isFinalAnswer(LlmClient.Message message) {
        return "assistant".equals(message.role())
                && (message.toolCalls() == null || message.toolCalls().isEmpty());
    }

    private static String lastQuestion(List<ConversationLedger.Entry> entries,
                                       List<LlmClient.Message> messages) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            ConversationLedger.Entry entry = entries.get(i);
            if ("react".equals(entry.mode())
                    && "user_input".equals(entry.source())
                    && entry.message() != null) {
                return entry.message().content();
            }
        }
        // 恢复后还没提过新问题的会话，退回到对话里最后一条用户消息
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role())) {
                return messages.get(i).content();
            }
        }
        return "";
    }

    private static List<Path> recentFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".jsonl"))
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparingLong(SessionResume::lastModified).reversed())
                    .limit(MAX_SCANNED_FILES)
                    .toList();
        }
    }

    /** session_start 紧跟在第一条 system 消息后面，只读文件开头几行判断，避免整份读入。 */
    private static boolean isCliSessionOf(Path file, String projectPath) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (int i = 0; i < HEADER_LINES; i++) {
                String line = reader.readLine();
                if (line == null) {
                    return false;
                }
                if (line.isBlank() || !line.contains(ConversationLedger.SESSION_START_EVENT)) {
                    continue;
                }
                JsonNode node = MAPPER.readTree(line);
                if (ConversationLedger.SESSION_START_EVENT.equals(node.path("event").asText())) {
                    JsonNode metadata = node.path("metadata");
                    return CLI_CHANNEL.equals(metadata.path("channel").asText())
                            && projectPath.equals(metadata.path("projectPath").asText());
                }
            }
        } catch (IOException e) {
            log.warn("Skip unreadable session file while listing /resume candidates: {}", file, e);
            return false;
        }
        return false;
    }

    private static String sessionId(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.length() - ".jsonl".length());
    }

    private static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        return Path.of(path).toAbsolutePath().normalize().toString();
    }
}
