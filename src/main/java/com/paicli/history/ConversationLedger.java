package com.paicli.history;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Append-only ledger for the original LLM messages produced during one PaiCLI session.
 *
 * <p>The mutable {@code conversationHistory} lists used by Agent implementations are
 * delivery views: image pruning, {@code /clear}, and context compaction may replace or
 * remove entries from those lists. This ledger is deliberately independent from those
 * views and only ever appends JSONL records.
 *
 * <p>Raw records may contain complete prompts, tool arguments/results, reasoning text,
 * and image payloads. The default directory and file permissions are therefore restricted
 * to the current user on POSIX file systems.
 */
public final class ConversationLedger {
    private static final Logger log = LoggerFactory.getLogger(ConversationLedger.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final int SCHEMA_VERSION = 1;
    /** CLI 启动时写入，记录项目路径，/resume 据此只列出当前项目的会话。 */
    public static final String SESSION_START_EVENT = "session_start";
    /** 压缩、清理或恢复改写了发给模型的对话后写入，metadata.messages 是改写后的完整对话（不含 system）。 */
    public static final String VIEW_CHECKPOINT_EVENT = "view_checkpoint";
    public static final String HISTORY_CLEAR_EVENT = "history_clear";
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    private static final ConversationLedger DISABLED =
            new ConversationLedger(null, "disabled", Clock.systemUTC(), false, 0L);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(
            int schemaVersion,
            String sessionId,
            long sequence,
            long timestamp,
            String event,
            String mode,
            String actor,
            String source,
            LlmClient.Message message,
            Map<String, Object> metadata
    ) {
    }

    private final Path file;
    private final String sessionId;
    private final Clock clock;
    private final boolean enabled;
    private final AtomicLong nextSequence;

    private ConversationLedger(Path file, String sessionId, Clock clock, boolean enabled,
                               long initialSequence) {
        this.file = file;
        this.sessionId = sessionId;
        this.clock = clock;
        this.enabled = enabled;
        this.nextSequence = new AtomicLong(initialSequence);
    }

    /**
     * Opens a new session ledger below {@code ~/.paicli/history/raw/}.
     */
    public static ConversationLedger openDefault(Path userHome) throws IOException {
        Objects.requireNonNull(userHome, "userHome");
        return open(userHome.resolve(".paicli").resolve("history"), generateSessionId());
    }

    /**
     * Opens a new ledger below {@code historyDirectory/raw/}. Exposed for tests and
     * alternate front ends that want to control the session identifier.
     */
    public static ConversationLedger open(Path historyDirectory, String sessionId) throws IOException {
        Objects.requireNonNull(historyDirectory, "historyDirectory");
        String safeSessionId = requireSafeSessionId(sessionId);
        Path directory = historyDirectory.toAbsolutePath().normalize().resolve("raw");
        createSecureDirectory(directory);
        Path file = directory.resolve(safeSessionId + ".jsonl");
        createSecureFile(file);
        return new ConversationLedger(
                file,
                safeSessionId,
                Clock.systemUTC(),
                true,
                nextSequence(file));
    }

    /**
     * No-op ledger used by unit tests and entry points that have not attached persistence.
     */
    public static ConversationLedger disabled() {
        return DISABLED;
    }

    public void appendMessage(String mode, String actor, String source, LlmClient.Message message) {
        if (!enabled || message == null) {
            return;
        }
        append(classify(message), mode, actor, source, message, Map.of());
    }

    public void appendEvent(String event, String mode, String actor, String source,
                            Map<String, Object> metadata) {
        if (!enabled) {
            return;
        }
        append(normalize(event, "event"), mode, actor, source, null,
                metadata == null ? Map.of() : Map.copyOf(metadata));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Path file() {
        return file;
    }

    public String sessionId() {
        return sessionId;
    }

    public List<Entry> readAll() throws IOException {
        if (!enabled || file == null) {
            return List.of();
        }
        return readEntries(file);
    }

    /**
     * 读取任意一个会话记录文件，跳过无法解析的行（例如进程在写入中途退出留下的半行），
     * 供 /resume 读取以前的会话。
     */
    public static List<Entry> readEntriesSkippingBrokenLines(Path file) throws IOException {
        if (file == null || !Files.exists(file)) {
            return List.of();
        }
        List<Entry> entries = new java.util.ArrayList<>();
        int skipped = 0;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    entries.add(MAPPER.readValue(line, Entry.class));
                } catch (IOException e) {
                    skipped++;
                }
            }
        }
        if (skipped > 0) {
            log.warn("Skipped {} unreadable line(s) in conversation ledger {}", skipped, file);
        }
        return entries;
    }

    private static List<Entry> readEntries(Path file) throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.filter(line -> !line.isBlank())
                    .map(line -> {
                        try {
                            return MAPPER.readValue(line, Entry.class);
                        } catch (IOException e) {
                            throw new LedgerReadException(e);
                        }
                    })
                    .toList();
        } catch (LedgerReadException e) {
            throw e.ioException;
        }
    }

    /** 把 metadata 里序列化后的消息列表还原成 {@link LlmClient.Message}。 */
    public static List<LlmClient.Message> messagesFromMetadata(Object value) {
        if (value == null) {
            return List.of();
        }
        return MAPPER.convertValue(value, new TypeReference<List<LlmClient.Message>>() {
        });
    }

    private synchronized void append(String event, String mode, String actor, String source,
                                     LlmClient.Message message, Map<String, Object> metadata) {
        long sequence = nextSequence.getAndIncrement();
        Entry entry = new Entry(
                SCHEMA_VERSION,
                sessionId,
                sequence,
                clock.millis(),
                event,
                normalize(mode, "unknown"),
                normalize(actor, "unknown"),
                normalize(source, "unknown"),
                message,
                metadata
        );
        try {
            byte[] json = MAPPER.writeValueAsBytes(entry);
            ByteBuffer buffer = ByteBuffer.allocate(json.length + 1);
            buffer.put(json).put((byte) '\n').flip();
            try (FileChannel channel = FileChannel.open(
                    file,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND)) {
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(false);
            }
        } catch (IOException e) {
            log.error("Failed to append conversation ledger event: session={}, sequence={}, event={}",
                    sessionId, sequence, event, e);
        }
    }

    private static String classify(LlmClient.Message message) {
        if ("system".equals(message.role())) {
            return "system";
        }
        if ("user".equals(message.role())) {
            return "user";
        }
        if ("tool".equals(message.role())) {
            return "tool_result";
        }
        if ("assistant".equals(message.role())
                && message.toolCalls() != null
                && !message.toolCalls().isEmpty()) {
            return "tool_call";
        }
        if ("assistant".equals(message.role())) {
            return "assistant";
        }
        return "message";
    }

    private static void createSecureDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        setPosixPermissionsIfSupported(directory, DIRECTORY_PERMISSIONS);
    }

    private static void createSecureFile(Path file) throws IOException {
        if (Files.notExists(file)) {
            try {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
            } catch (UnsupportedOperationException e) {
                Files.createFile(file);
            }
        }
        setPosixPermissionsIfSupported(file, FILE_PERMISSIONS);
    }

    private static void setPosixPermissionsIfSupported(Path path,
                                                        Set<PosixFilePermission> permissions)
            throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows and other non-POSIX file systems use their platform defaults.
        }
    }

    private static String generateSessionId() {
        return "session-" + System.currentTimeMillis() + "-" + UUID.randomUUID();
    }

    private static long nextSequence(Path file) throws IOException {
        long max = -1L;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                max = Math.max(max, MAPPER.readTree(line).path("sequence").asLong(max));
            }
        }
        return max + 1L;
    }

    private static String requireSafeSessionId(String sessionId) {
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("sessionId must contain only letters, numbers, '.', '_' or '-'");
        }
        return sessionId;
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static final class LedgerReadException extends RuntimeException {
        private final IOException ioException;

        private LedgerReadException(IOException ioException) {
            super(ioException);
            this.ioException = ioException;
        }
    }
}
