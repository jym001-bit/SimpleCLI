package com.paicli.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 长期记忆 - 跨对话持久化的关键信息
 *
 * 职责：
 * 1. 持久化用户偏好、项目事实、关键决策等
 * 2. 支持关键词检索
 * 3. 在相同类型和作用域内，基于规范化内容近似匹配自动去重
 * 4. 持久化到磁盘
 *
 * 并发约定：同一个 JSON 文件可能同时被多个实例和多个 PaiCLI 进程读写。每次变更都在
 * {@link LongTermMemoryFile} 的锁内先重读磁盘最新状态再修改并原子写回；读取前检测外部变更并刷新。
 * 文件损坏时保留内存中最后一份可用数据，不会因为一次解析失败清空全部记忆。
 */
public class LongTermMemory implements Memory {
    private static final Logger log = LoggerFactory.getLogger(LongTermMemory.class);
    private static final String STORAGE_DIR_PROPERTY = "paicli.memory.dir";
    private static final String STORAGE_DIR_ENV = "PAICLI_MEMORY_DIR";
    private static final String STORAGE_FILE = "long_term_memory.json";
    private final Map<String, MemoryEntry> entries;
    private final AtomicInteger tokenCounter;
    private final ObjectMapper mapper;
    private final LongTermMemoryFile storage;
    private final MemoryConflictDetector conflictDetector;

    public LongTermMemory() {
        this(resolveStorageDir());
    }

    public LongTermMemory(File storageDir) {
        this(storageDir, MemoryConflictDetector.fromConfiguration());
    }

    LongTermMemory(File storageDir, MemoryConflictDetector conflictDetector) {
        this.conflictDetector = conflictDetector;
        this.entries = new ConcurrentHashMap<>();
        this.tokenCounter = new AtomicInteger(0);
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);

        // 确保存储目录存在
        File dir = storageDir;
        if (!dir.exists()) {
            dir.mkdirs();
        }
        this.storage = new LongTermMemoryFile(new File(dir, STORAGE_FILE), mapper);

        // 启动时加载已有记忆
        loadFromDisk();
    }

    @Override
    public synchronized void store(MemoryEntry entry) {
        withStorageLock(() -> {
            // type + scope + project 共同限定去重域，避免不同项目或不同可见性的记忆互相误杀。
            boolean duplicate = entries.values().stream()
                    .anyMatch(existing -> MemoryDeduplicator.isDuplicate(existing, entry));
            if (!duplicate) {
                putEntry(entry);
                saveToDisk();
            }
            return null;
        });
    }

    /**
     * 用户/模型显式保存记忆时的写入入口：去重 → 冲突检测 → 写入或替换，整体原子执行。
     *
     * <p>{@link #store(MemoryEntry)} 仍是底层存储语义（只做去重）；这里额外负责：
     * 等价条目只刷新核实时间；高度相似但不一致的条目不写入，交给用户决定；
     * {@code replaceId} 表示用户已选择用新内容替换某条旧记忆。</p>
     *
     * @param replaceId  用户选择替换的旧条目 id，可为 null
     * @param allowConflict 用户明确要求两条都保留时为 true
     * @param projectKey 当前项目，用于限制 replaceId 只能指向当前可见的条目
     */
    public synchronized MemoryWriteResult write(MemoryEntry entry, String replaceId,
                                                boolean allowConflict, String projectKey) {
        return withStorageLock(() -> writeLocked(entry, replaceId, allowConflict, projectKey));
    }

    private MemoryWriteResult writeLocked(MemoryEntry entry, String replaceId,
                                          boolean allowConflict, String projectKey) {
        String scope = scopeOf(entry);
        MemoryEntry target = null;
        if (replaceId != null && !replaceId.isBlank()) {
            target = entries.get(replaceId.trim());
            if (target == null || !isVisibleInProject(target, projectKey)) {
                return MemoryWriteResult.replaceTargetNotFound(entry, replaceId.trim(), scope);
            }
        }
        String excludedId = target == null ? null : target.getId();

        Optional<MemoryEntry> duplicate = entries.values().stream()
                .filter(existing -> !existing.getId().equals(excludedId))
                .filter(existing -> MemoryDeduplicator.isDuplicate(existing, entry))
                .findFirst();
        if (duplicate.isPresent()) {
            MemoryEntry verified = duplicate.get().withLastVerifiedAt(entry.getLastVerifiedAt());
            entries.put(verified.getId(), verified);
            if (target != null) {
                removeEntry(target.getId());
            }
            saveToDisk();
            return MemoryWriteResult.duplicate(verified, scope);
        }

        if (!allowConflict) {
            List<MemoryEntry> conflicts = entries.values().stream()
                    .filter(existing -> !existing.getId().equals(excludedId))
                    .filter(existing -> conflictDetector.isConflict(existing, entry))
                    .sorted(Comparator.comparing(MemoryEntry::getTimestamp))
                    .toList();
            if (!conflicts.isEmpty()) {
                return MemoryWriteResult.conflict(entry, conflicts, scope);
            }
        }

        if (target != null) {
            removeEntry(target.getId());
        }
        putEntry(entry);
        saveToDisk();
        return target == null
                ? MemoryWriteResult.stored(entry, scope)
                : MemoryWriteResult.replaced(entry, target, scope);
    }

    /** Automatic candidates never refresh verification time, replace entries, or bypass conflicts. */
    public synchronized boolean writeAutomatic(MemoryEntry entry) {
        return withStorageLock(() -> {
            if (entries.containsKey(entry.getId()) || entries.values().stream().anyMatch(existing ->
                    MemoryDeduplicator.isDuplicate(existing, entry)
                            || conflictDetector.isConflict(existing, entry))) {
                return false;
            }
            putEntry(entry);
            saveToDisk();
            return true;
        });
    }

    /** 用户确认某条记忆仍然成立：刷新最后核实时间。 */
    public synchronized Optional<MemoryEntry> markVerified(String id, Instant verifiedAt) {
        return withStorageLock(() -> {
            MemoryEntry existing = id == null ? null : entries.get(id.trim());
            if (existing == null) {
                return Optional.<MemoryEntry>empty();
            }
            MemoryEntry verified = existing.withLastVerifiedAt(verifiedAt);
            entries.put(verified.getId(), verified);
            saveToDisk();
            return Optional.of(verified);
        });
    }

    private void putEntry(MemoryEntry entry) {
        MemoryEntry previous = entries.put(entry.getId(), entry);
        if (previous != null) {
            tokenCounter.addAndGet(-previous.getTokenCount());
        }
        tokenCounter.addAndGet(entry.getTokenCount());
    }

    private void removeEntry(String id) {
        MemoryEntry removed = entries.remove(id);
        if (removed != null) {
            tokenCounter.addAndGet(-removed.getTokenCount());
        }
    }

    @Override
    public Optional<MemoryEntry> retrieve(String id) {
        refreshIfChangedOnDisk();
        return Optional.ofNullable(entries.get(id));
    }

    @Override
    public List<MemoryEntry> search(String query, int limit) {
        return search(query, limit, null);
    }

    public List<MemoryEntry> search(String query, int limit, String projectKey) {
        refreshIfChangedOnDisk();
        Set<String> queryTokens = MemoryQueryTokenizer.tokenize(query);

        return entries.values().stream()
                .filter(entry -> isVisibleInProject(entry, projectKey))
                .filter(entry -> {
                    if (MemoryQueryTokenizer.matches(entry.getContent(), queryTokens)) {
                        return true;
                    }
                    return entry.getMetadata().values().stream()
                            .anyMatch(value -> MemoryQueryTokenizer.matches(value, queryTokens));
                })
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public List<MemoryEntry> getAll() {
        refreshIfChangedOnDisk();
        return new ArrayList<>(entries.values());
    }

    public List<MemoryEntry> getAll(String projectKey) {
        refreshIfChangedOnDisk();
        return entries.values().stream()
                .filter(entry -> isVisibleInProject(entry, projectKey))
                .collect(Collectors.toList());
    }

    @Override
    public synchronized boolean delete(String id) {
        return withStorageLock(() -> {
            MemoryEntry removed = entries.remove(id);
            if (removed == null) {
                return false;
            }
            tokenCounter.addAndGet(-removed.getTokenCount());
            saveToDisk();
            return true;
        });
    }

    @Override
    public synchronized void clear() {
        withStorageLock(() -> {
            entries.clear();
            tokenCounter.set(0);
            saveToDisk();
            return null;
        });
    }

    @Override
    public int getTokenCount() {
        refreshIfChangedOnDisk();
        return tokenCounter.get();
    }

    @Override
    public int size() {
        refreshIfChangedOnDisk();
        return entries.size();
    }

    /**
     * 按类型筛选记忆
     */
    public List<MemoryEntry> getByType(MemoryEntry.MemoryType type) {
        refreshIfChangedOnDisk();
        return entries.values().stream()
                .filter(entry -> entry.getType() == type)
                .collect(Collectors.toList());
    }

    public static boolean isVisibleInProject(MemoryEntry entry, String projectKey) {
        String scope = scopeOf(entry);
        if ("global".equals(scope)) {
            return true;
        }
        String entryProject = entry.getMetadata().get("project");
        return projectKey != null && !projectKey.isBlank() && Objects.equals(entryProject, projectKey);
    }

    public static String scopeOf(MemoryEntry entry) {
        String scope = entry.getMetadata().get("scope");
        if ("project".equalsIgnoreCase(scope)) {
            return "project";
        }
        return "global";
    }

    /** 在文件锁内先重读磁盘最新状态再执行变更，避免各实例拿旧快照整文件覆盖。 */
    private <T> T withStorageLock(Supplier<T> mutation) {
        return storage.withLock(() -> {
            reloadFromDisk();
            return mutation.get();
        });
    }

    private void saveToDisk() {
        storage.write(entries.values().stream()
                .map(this::entryToMap)
                .collect(Collectors.toList()));
    }

    /** 其他实例或进程改过文件时，读取前刷新内存视图。 */
    private synchronized void refreshIfChangedOnDisk() {
        if (storage.changedSinceLastSync()) {
            reloadFromDisk();
        }
    }

    private static File resolveStorageDir() {
        String configuredDir = System.getProperty(STORAGE_DIR_PROPERTY);
        if (configuredDir == null || configuredDir.isBlank()) {
            configuredDir = System.getenv(STORAGE_DIR_ENV);
        }
        if (configuredDir != null && !configuredDir.isBlank()) {
            return new File(configuredDir);
        }
        return new File(new File(System.getProperty("user.home"), ".paicli"), "memory");
    }

    /**
     * 从磁盘加载
     */
    private void loadFromDisk() {
        reloadFromDisk();
        log.info("加载了 {} 条长期记忆", entries.size());
    }

    /** 用磁盘内容替换内存视图；文件损坏时保留当前内存数据，下一次写入会据此重建文件。 */
    private void reloadFromDisk() {
        storage.read().ifPresent(dataList -> {
            Map<String, MemoryEntry> loaded = new LinkedHashMap<>();
            for (Map<String, Object> data : dataList) {
                MemoryEntry entry = mapToEntry(data);
                if (entry != null) {
                    loaded.put(entry.getId(), entry);
                }
            }
            entries.clear();
            entries.putAll(loaded);
            tokenCounter.set(loaded.values().stream().mapToInt(MemoryEntry::getTokenCount).sum());
        });
    }

    private Map<String, Object> entryToMap(MemoryEntry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entry.getId());
        map.put("content", entry.getContent());
        map.put("type", entry.getType().name());
        map.put("timestamp", entry.getTimestamp().toString());
        map.put("lastVerifiedAt", entry.getLastVerifiedAt().toString());
        map.put("metadata", entry.getMetadata());
        map.put("tokenCount", entry.getTokenCount());
        return map;
    }

    @SuppressWarnings("unchecked")
    private MemoryEntry mapToEntry(Map<String, Object> map) {
        try {
            String id = (String) map.get("id");
            String content = (String) map.get("content");
            MemoryEntry.MemoryType type = MemoryEntry.MemoryType.valueOf((String) map.get("type"));
            Instant timestamp = null;
            Object timestampObj = map.get("timestamp");
            if (timestampObj instanceof String timestampValue && !timestampValue.isBlank()) {
                timestamp = Instant.parse(timestampValue);
            }
            Instant lastVerifiedAt = null;
            if (map.get("lastVerifiedAt") instanceof String verifiedValue && !verifiedValue.isBlank()) {
                lastVerifiedAt = Instant.parse(verifiedValue);
            }
            Map<String, String> metadata = new HashMap<>();
            Object metaObj = map.get("metadata");
            if (metaObj instanceof Map) {
                ((Map<String, Object>) metaObj).forEach((k, v) -> metadata.put(k, String.valueOf(v)));
            }
            int tokenCount = map.get("tokenCount") instanceof Number n ? n.intValue() : MemoryEntry.estimateTokens(content);
            return new MemoryEntry(id, content, type, timestamp, lastVerifiedAt, metadata, tokenCount);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 生成记忆状态摘要
     */
    public String getStatusSummary() {
        refreshIfChangedOnDisk();
        Map<MemoryEntry.MemoryType, Long> typeCounts = entries.values().stream()
                .collect(Collectors.groupingBy(MemoryEntry::getType, Collectors.counting()));

        return String.format("长期记忆: %d条 / %d tokens (事实: %d, 摘要: %d, 工具结果: %d)",
                entries.size(), tokenCounter.get(),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.FACT, 0L),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.SUMMARY, 0L),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.TOOL_RESULT, 0L));
    }
}
