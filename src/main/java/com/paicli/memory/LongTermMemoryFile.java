package com.paicli.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 长期记忆 JSON 文件的并发安全读写。
 *
 * <p>同一个文件可能同时被多个 {@link LongTermMemory} 实例（ReAct / Plan / Team 各自持有）
 * 和多个 PaiCLI 进程访问。变更在“进程内路径锁 + 跨进程文件锁”下执行；写入先落临时文件再原子改名，
 * 读取方永远看不到写了一半的 JSON。解析失败的文件会先备份，调用方据此保留内存中的可用数据。</p>
 */
final class LongTermMemoryFile {
    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryFile.class);
    private static final Map<String, ReentrantLock> PATH_LOCKS = new ConcurrentHashMap<>();

    private final File storageFile;
    private final ObjectMapper mapper;
    private long syncedModified = -1L;
    private long syncedLength = -1L;

    LongTermMemoryFile(File storageFile, ObjectMapper mapper) {
        this.storageFile = storageFile;
        this.mapper = mapper;
    }

    /** 在进程内路径锁和跨进程文件锁下执行一次“重读 → 修改 → 写回”。 */
    <T> T withLock(Supplier<T> mutation) {
        ReentrantLock pathLock = PATH_LOCKS.computeIfAbsent(
                storageFile.getAbsolutePath(), ignored -> new ReentrantLock());
        pathLock.lock();
        try (FileChannel channel = openLockChannel(); FileLock ignored = lockQuietly(channel)) {
            return mutation.get();
        } catch (IOException e) {
            throw new IllegalStateException("长期记忆文件锁释放失败: " + e.getMessage(), e);
        } finally {
            pathLock.unlock();
        }
    }

    /** 自上次读写以来，文件是否被其他实例或进程改过。 */
    boolean changedSinceLastSync() {
        return storageFile.lastModified() != syncedModified || storageFile.length() != syncedLength;
    }

    /**
     * 读取全部记录。文件不存在返回空列表；解析失败时把原文件另存为
     * {@code <文件名>.corrupt-<时间戳>} 并返回 empty，调用方应保留内存中的最后一份可用数据。
     */
    @SuppressWarnings("unchecked")
    Optional<List<Map<String, Object>>> read() {
        if (!storageFile.exists()) {
            rememberSync();
            return Optional.of(List.of());
        }
        try {
            List<Map<String, Object>> data = mapper.readValue(storageFile, List.class);
            rememberSync();
            return Optional.of(data == null ? List.of() : data);
        } catch (IOException | RuntimeException e) {
            backupCorruptFile(e);
            rememberSync();
            return Optional.empty();
        }
    }

    /** 写临时文件后原子改名；失败只记日志，不抛给对话主流程。 */
    void write(List<Map<String, Object>> data) {
        Path target = storageFile.toPath();
        Path temp = null;
        try {
            temp = Files.createTempFile(target.getParent(), storageFile.getName(), ".tmp");
            mapper.writeValue(temp.toFile(), data);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
            rememberSync();
        } catch (IOException e) {
            log.warn("长期记忆持久化失败: {}", e.getMessage(), e);
        } finally {
            deleteQuietly(temp);
        }
    }

    private FileChannel openLockChannel() {
        Path lockPath = storageFile.toPath().resolveSibling(storageFile.getName() + ".lock");
        try {
            return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (IOException e) {
            log.warn("长期记忆锁文件不可用，退化为进程内锁: {}", e.getMessage());
            return null;
        }
    }

    private static FileLock lockQuietly(FileChannel channel) {
        if (channel == null) {
            return null;
        }
        try {
            return channel.lock();
        } catch (IOException | OverlappingFileLockException e) {
            log.warn("长期记忆跨进程锁获取失败，退化为进程内锁: {}", e.getMessage());
            return null;
        }
    }

    private void backupCorruptFile(Exception cause) {
        Path source = storageFile.toPath();
        Path backup = source.resolveSibling(storageFile.getName() + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.copy(source, backup, StandardCopyOption.REPLACE_EXISTING);
            log.warn("长期记忆文件解析失败，已备份到 {}: {}", backup, cause.getMessage());
        } catch (IOException e) {
            log.warn("长期记忆文件解析失败且备份失败: {} / {}", cause.getMessage(), e.getMessage());
        }
    }

    private void rememberSync() {
        syncedModified = storageFile.lastModified();
        syncedLength = storageFile.length();
    }

    private static void deleteQuietly(Path temp) {
        if (temp == null) {
            return;
        }
        try {
            Files.deleteIfExists(temp);
        } catch (IOException ignored) {
            // 临时文件清理失败不影响主流程
        }
    }
}
