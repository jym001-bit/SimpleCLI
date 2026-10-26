package com.paicli.mcp.transport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisabledOnOs(OS.WINDOWS)
class McpPidFileTest {

    private final List<Process> spawned = new ArrayList<>();

    @AfterEach
    void killSpawned() {
        spawned.forEach(Process::destroyForcibly);
    }

    @Test
    void killsProcessWhoseOwnerIsGone(@TempDir Path dir) throws Exception {
        McpPidFile pidFile = new McpPidFile(dir.resolve("mcp-pids.json"));
        Process orphan = spawn("sleep", "60");
        ProcessHandle deadOwner = deadProcess();

        pidFile.record(orphan.toHandle(), deadOwner.pid(), startMillis(deadOwner));

        assertEquals(1, pidFile.cleanupOrphans());
        assertTrue(orphan.waitFor(3, TimeUnit.SECONDS));
        assertFalse(Files.readString(dir.resolve("mcp-pids.json")).contains("\"pid\" : " + orphan.pid()));
    }

    @Test
    void keepsProcessesOfAnotherRunningPaiCli(@TempDir Path dir) throws Exception {
        McpPidFile pidFile = new McpPidFile(dir.resolve("mcp-pids.json"));
        Process child = spawn("sleep", "60");

        pidFile.record(child.toHandle()); // 所属 PaiCLI 就是当前进程，还活着

        assertEquals(0, pidFile.cleanupOrphans());
        assertTrue(child.isAlive());
        assertTrue(Files.readString(dir.resolve("mcp-pids.json")).contains(String.valueOf(child.pid())));
    }

    @Test
    void doesNotKillReusedPid(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("mcp-pids.json");
        Process unrelated = spawn("sleep", "60");
        ProcessHandle deadOwner = deadProcess();
        // 记录里的启动时间对不上，说明这个 PID 已经被别的进程复用
        Files.writeString(file, "[{\"ownerPid\":" + deadOwner.pid() + ",\"ownerStart\":" + startMillis(deadOwner)
                + ",\"pid\":" + unrelated.pid() + ",\"start\":123,\"command\":\"\"}]");

        assertEquals(0, new McpPidFile(file).cleanupOrphans());
        assertTrue(unrelated.isAlive());
        assertEquals("[ ]", Files.readString(file).trim(), "过期记录要删掉");
    }

    private Process spawn(String... command) throws Exception {
        Process process = new ProcessBuilder(command).start();
        spawned.add(process);
        return process;
    }

    private static ProcessHandle deadProcess() throws Exception {
        Process process = new ProcessBuilder("true").start();
        ProcessHandle handle = process.toHandle();
        process.waitFor();
        return handle;
    }

    private static long startMillis(ProcessHandle handle) {
        return handle.info().startInstant().map(Instant::toEpochMilli).orElse(1L);
    }
}
