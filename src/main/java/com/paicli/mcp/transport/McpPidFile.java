package com.paicli.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * 记录 PaiCLI 拉起的 MCP 子进程（{@code ~/.paicli/mcp-pids.json}），PaiCLI 被 kill -9 时
 * shutdown hook 不执行，下次启动靠这份记录清理遗留进程。
 *
 * <p>只清理“所属 PaiCLI 已经退出”的记录，避免误杀另一个正在运行的 PaiCLI 的子进程；
 * PID 会被系统复用，所以杀之前核对启动时间和命令，对不上就只删记录、不杀进程。
 * 多个 PaiCLI 进程会同时读写，每次修改都先拿文件锁。
 */
public final class McpPidFile {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long UNKNOWN_START = -1;

    private final Path file;

    public McpPidFile(Path file) {
        this.file = file;
    }

    public static McpPidFile defaultFile() {
        return new McpPidFile(Path.of(System.getProperty("user.home"), ".paicli", "mcp-pids.json"));
    }

    public void record(ProcessHandle process) {
        ProcessHandle owner = ProcessHandle.current();
        record(process, owner.pid(), startMillis(owner));
    }

    void record(ProcessHandle process, long ownerPid, long ownerStart) {
        long start = startMillis(process);
        String command = process.info().command().orElse("");
        update(entries -> {
            ArrayNode next = withoutPids(entries, Set.of(process.pid()));
            next.addObject()
                    .put("ownerPid", ownerPid)
                    .put("ownerStart", ownerStart)
                    .put("pid", process.pid())
                    .put("start", start)
                    .put("command", command);
            return next;
        });
    }

    public void remove(Collection<ProcessHandle> processes) {
        if (processes.isEmpty()) {
            return;
        }
        Set<Long> pids = processes.stream().map(ProcessHandle::pid).collect(Collectors.toSet());
        update(entries -> withoutPids(entries, pids));
    }

    /**
     * 结束所属 PaiCLI 已退出、并且启动时间和命令都对得上的遗留进程，返回结束了几个。
     */
    public int cleanupOrphans() {
        List<ProcessHandle> orphans = new ArrayList<>();
        update(entries -> {
            ArrayNode kept = MAPPER.createArrayNode();
            for (JsonNode entry : entries) {
                if (isAlive(entry.path("ownerPid").asLong(), entry.path("ownerStart").asLong(UNKNOWN_START))) {
                    kept.add(entry);
                    continue;
                }
                matchingProcess(entry).ifPresent(orphans::add);
            }
            return kept;
        });
        orphans.forEach(ProcessHandle::destroy);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        for (ProcessHandle orphan : orphans) {
            long remaining = deadline - System.nanoTime();
            try {
                if (remaining > 0) {
                    orphan.onExit().get(remaining, TimeUnit.NANOSECONDS);
                }
            } catch (Exception ignored) {
                // 超时或被中断，下面强制结束
            }
            if (orphan.isAlive()) {
                orphan.destroyForcibly();
            }
        }
        return orphans.size();
    }

    private static Optional<ProcessHandle> matchingProcess(JsonNode entry) {
        long recordedStart = entry.path("start").asLong(UNKNOWN_START);
        if (recordedStart == UNKNOWN_START) {
            return Optional.empty();
        }
        String recordedCommand = entry.path("command").asText("");
        return ProcessHandle.of(entry.path("pid").asLong())
                .filter(ProcessHandle::isAlive)
                .filter(process -> startMillis(process) == recordedStart)
                .filter(process -> recordedCommand.isEmpty()
                        || recordedCommand.equals(process.info().command().orElse(recordedCommand)));
    }

    private static boolean isAlive(long pid, long start) {
        return ProcessHandle.of(pid)
                .filter(ProcessHandle::isAlive)
                .filter(process -> start == UNKNOWN_START || startMillis(process) == start)
                .isPresent();
    }

    private static long startMillis(ProcessHandle process) {
        return process.info().startInstant().map(Instant::toEpochMilli).orElse(UNKNOWN_START);
    }

    private static ArrayNode withoutPids(ArrayNode entries, Set<Long> pids) {
        ArrayNode next = MAPPER.createArrayNode();
        for (JsonNode entry : entries) {
            if (!pids.contains(entry.path("pid").asLong())) {
                next.add(entry);
            }
        }
        return next;
    }

    private synchronized void update(UnaryOperator<ArrayNode> change) {
        try {
            Files.createDirectories(file.getParent());
            Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                ArrayNode next = change.apply(read());
                Path temp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(temp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(next));
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (IOException e) {
            // 记录失败只影响下次启动的遗留进程清理，不影响 MCP 正常使用
            System.err.println("⚠️ 更新 " + file + " 失败: " + e.getMessage());
        }
    }

    private ArrayNode read() {
        try {
            if (!Files.exists(file)) {
                return MAPPER.createArrayNode();
            }
            JsonNode root = MAPPER.readTree(Files.readString(file));
            return root instanceof ArrayNode array ? array : MAPPER.createArrayNode();
        } catch (Exception e) {
            return MAPPER.createArrayNode();
        }
    }
}
