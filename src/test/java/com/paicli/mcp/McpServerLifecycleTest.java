package com.paicli.mcp;

import com.paicli.mcp.config.McpConfigLoader;
import com.paicli.mcp.config.McpServerConfig;
import com.paicli.mcp.config.McpTrustStore;
import com.paicli.mcp.transport.McpPidFile;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用一个 shell 写的假 MCP server 验证进程生命周期：意外退出后自动重启、连续失败后停止、卡死检测。
 */
@DisabledOnOs(OS.WINDOWS)
class McpServerLifecycleTest {

    // 只认 initialize / tools/list / tools/call / ping 四个方法，行为由环境变量控制
    private static final String FAKE_SERVER = """
            if [ -n "$ONCE_MARKER" ]; then
              [ -e "$ONCE_MARKER" ] && exit 1
              touch "$ONCE_MARKER"
            fi
            while IFS= read -r line; do
              id=$(printf '%s' "$line" | sed -n 's/.*"id":\\([0-9]*\\).*/\\1/p')
              case "$line" in
                *'"method":"initialize"'*)
                  echo "{\\"jsonrpc\\":\\"2.0\\",\\"id\\":$id,\\"result\\":{\\"protocolVersion\\":\\"2025-03-26\\",\\"capabilities\\":{}}}";;
                *'"method":"tools/list"'*)
                  echo "{\\"jsonrpc\\":\\"2.0\\",\\"id\\":$id,\\"result\\":{\\"tools\\":[{\\"name\\":\\"echo\\",\\"inputSchema\\":{\\"type\\":\\"object\\"}}]}}";;
                *'"method":"tools/call"'*)
                  [ -n "$CRASH_ON_CALL" ] && exit 3
                  echo "{\\"jsonrpc\\":\\"2.0\\",\\"id\\":$id,\\"result\\":{\\"content\\":[{\\"type\\":\\"text\\",\\"text\\":\\"ok\\"}]}}";;
                *'"method":"ping"'*)
                  [ -n "$HANG_PING" ] || echo "{\\"jsonrpc\\":\\"2.0\\",\\"id\\":$id,\\"result\\":{}}";;
              esac
            done
            """;

    @TempDir
    Path dir;
    private ToolRegistry registry;
    private McpServerManager manager;
    private Path script;
    private Path pidFile;

    @BeforeEach
    void setUp() throws IOException {
        script = dir.resolve("fake-mcp.sh");
        Files.writeString(script, FAKE_SERVER);
        pidFile = dir.resolve("mcp-pids.json");
        registry = new ToolRegistry();
        manager = new McpServerManager(registry, dir,
                new McpConfigLoader(dir.resolve("user.json"), dir.resolve("project.json"), dir),
                new McpTrustStore(dir.resolve("mcp-trust.json")),
                new McpPidFile(pidFile));
        manager.restartBaseDelay(Duration.ofMillis(50));
        manager.pingTimeout(Duration.ofMillis(300));
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Test
    void crashedServerIsRestartedAutomatically() throws Exception {
        McpServer server = startFake(Map.of("CRASH_ON_CALL", "1"));
        Long firstPid = server.processId();

        long start = System.currentTimeMillis();
        assertThrows(IOException.class, () -> server.client().callTool("echo", "{}"));
        assertTrue(System.currentTimeMillis() - start < 5000, "进程退出后调用要立即失败");

        waitUntil(() -> server.status() == McpServerStatus.READY && !firstPid.equals(server.processId()));
        assertTrue(registry.hasTool("mcp__fake__echo"), "重启后工具重新注册");
        String notices = String.join("\n", manager.drainNotices());
        assertTrue(notices.contains("断开"), notices);
        assertTrue(notices.contains("已自动重启"), notices);
    }

    @Test
    void stopsAfterThreeFailedRestarts() throws Exception {
        McpServer server = startFake(Map.of("CRASH_ON_CALL", "1", "ONCE_MARKER", dir.resolve("started").toString()));

        assertThrows(IOException.class, () -> server.client().callTool("echo", "{}"));

        waitUntil(() -> server.errorMessage() != null && server.errorMessage().contains("已停止"));
        assertEquals(McpServerStatus.ERROR, server.status());
        assertFalse(registry.hasTool("mcp__fake__echo"));
        assertTrue(String.join("\n", manager.drainNotices()).contains("/mcp restart fake"));
    }

    @Test
    void stuckServerIsDetectedByPingAndRestarted() throws Exception {
        McpServer server = startFake(Map.of("HANG_PING", "1"));
        Long firstPid = server.processId();

        manager.pingAll();
        assertEquals(McpServerStatus.READY, server.status(), "一次 ping 超时不算卡死");
        assertEquals(firstPid, server.processId());

        manager.pingAll();
        waitUntil(() -> server.status() == McpServerStatus.READY && !firstPid.equals(server.processId()));
        assertFalse(ProcessHandle.of(firstPid).map(ProcessHandle::isAlive).orElse(false), "卡死的旧进程要被结束");
    }

    @Test
    void healthyServerAnswersPing() throws Exception {
        McpServer server = startFake(Map.of());
        Long pid = server.processId();

        manager.pingAll();
        manager.pingAll();

        assertEquals(McpServerStatus.READY, server.status());
        assertEquals(pid, server.processId());
    }

    @Test
    void manualRestartCancelsPendingAutoRestart() throws Exception {
        manager.restartBaseDelay(Duration.ofSeconds(30));
        McpServer server = startFake(Map.of("CRASH_ON_CALL", "1"));
        assertThrows(IOException.class, () -> server.client().callTool("echo", "{}"));
        waitUntil(() -> server.status() == McpServerStatus.ERROR);

        assertTrue(manager.restart("fake").contains("已重启"));
        assertEquals(McpServerStatus.READY, server.status());
    }

    @Test
    void pidFileIsEmptyAfterClose() throws Exception {
        McpServer server = startFake(Map.of());
        assertTrue(Files.readString(pidFile).contains(String.valueOf(server.processId())));

        manager.close();

        assertFalse(Files.readString(pidFile).contains(String.valueOf(server.processId())));
    }

    private McpServer startFake(Map<String, String> env) throws Exception {
        McpServerConfig config = new McpServerConfig();
        config.setCommand("sh");
        config.setArgs(List.of(script.toString()));
        config.setEnv(new LinkedHashMap<>(env));
        java.lang.reflect.Field field = McpServerManager.class.getDeclaredField("servers");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, McpServer> servers = (Map<String, McpServer>) field.get(manager);
        servers.put("fake", new McpServer("fake", config));
        manager.startAll();
        McpServer server = manager.server("fake");
        assertEquals(McpServerStatus.READY, server.status(), "假 server 应能启动: " + server.errorMessage());
        return server;
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("10 秒内没有达到预期状态");
            }
            Thread.sleep(20);
        }
    }
}
