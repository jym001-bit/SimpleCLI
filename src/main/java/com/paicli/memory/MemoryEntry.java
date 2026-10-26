package com.paicli.memory;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 记忆条目 - Memory 系统的基础数据单元
 *
 * <p>{@code timestamp} 是写入时间；{@code lastVerifiedAt} 是用户最近一次确认它仍然成立的时间
 * （写入、同内容重复保存或 {@code /memory verify}）。两者分开记录，检索注入时才能判断
 * 一条记忆是否太久没被核实过。</p>
 */
public class MemoryEntry {
    private final String id;
    private final String content;
    private final MemoryType type;
    private final Instant timestamp;
    private final Instant lastVerifiedAt;
    private final Map<String, String> metadata;
    private final int tokenCount;

    public enum MemoryType {
        CONVERSATION,  // 对话记忆
        FACT,          // 事实记忆（用户偏好、项目信息等）
        SUMMARY,       // 摘要记忆
        TOOL_RESULT    // 工具执行结果
    }

    public MemoryEntry(String id, String content, MemoryType type, Map<String, String> metadata, int tokenCount) {
        this(id, content, type, Instant.now(), metadata, tokenCount);
    }

    public MemoryEntry(String id, String content, MemoryType type, Instant timestamp,
                       Map<String, String> metadata, int tokenCount) {
        this(id, content, type, timestamp, null, metadata, tokenCount);
    }

    public MemoryEntry(String id, String content, MemoryType type, Instant timestamp, Instant lastVerifiedAt,
                       Map<String, String> metadata, int tokenCount) {
        this.id = id;
        this.content = content;
        this.type = type;
        this.timestamp = timestamp != null ? timestamp : Instant.now();
        // 旧数据没有核实时间时，以写入时间作为最后一次确认时间。
        this.lastVerifiedAt = lastVerifiedAt != null ? lastVerifiedAt : this.timestamp;
        this.metadata = metadata != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(metadata))
                : Map.of();
        this.tokenCount = tokenCount;
    }

    public String getId() { return id; }
    public String getContent() { return content; }
    public MemoryType getType() { return type; }
    public Instant getTimestamp() { return timestamp; }
    public Instant getLastVerifiedAt() { return lastVerifiedAt; }
    public Map<String, String> getMetadata() { return metadata; }
    public int getTokenCount() { return tokenCount; }

    /** 返回核实时间更新后的新条目，原条目保持不变。 */
    public MemoryEntry withLastVerifiedAt(Instant verifiedAt) {
        Map<String, String> verifiedMetadata = new LinkedHashMap<>(metadata);
        verifiedMetadata.remove("verification_pending");
        return new MemoryEntry(id, content, type, timestamp, verifiedAt, verifiedMetadata, tokenCount);
    }

    /**
     * 粗略估算 token 数（中文约 1.5 字/token，英文约 4 字符/token）
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        long chineseChars = text.chars().filter(c -> c > 0x4E00 && c < 0x9FFF).count();
        long otherChars = text.length() - chineseChars;
        return (int) Math.ceil(chineseChars / 1.5 + otherChars / 4.0);
    }

    @Override
    public String toString() {
        return "[%s] %s: %s".formatted(type, id,
                content.length() > 80 ? content.substring(0, 80) + "..." : content);
    }
}
