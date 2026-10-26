package com.paicli.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用 unix shell 子进程做 mock，避免依赖真实 MCP server。
 *
 * 跳过 Windows：依赖 sh/cat/echo 等命令；Windows 支持留作后续。
 */
@DisabledOnOs(OS.WINDOWS)
class StdioTransportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void echoesJsonMessageBackToListener() throws Exception {
        // sh 子进程把每行 stdin 原样回到 stdout，模拟一个最简响应器
        StdioTransport transport = new StdioTransport(
                "sh", List.of("-c", "while IFS= read -r line; do echo \"$line\"; done"),
                Map.of(), null);
        try {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<JsonNode> messageHolder = new AtomicReference<>();
            transport.onReceive(node -> {
                messageHolder.set(node);
                received.countDown();
            });

            JsonNode payload = MAPPER.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");
            transport.send(payload);

            assertTrue(received.await(2, TimeUnit.SECONDS), "应能在 2 秒内收到回显");
            assertEquals("ping", messageHolder.get().path("method").asText());
            assertEquals("stdio", transport.transportName());
            assertNotNull(transport.processId());
        } finally {
            transport.close();
        }
    }

    @Test
    void stderrLinesAreCapturedWithoutBlockingStdout() throws Exception {
        // 子进程同时往 stdout 输出可解析 JSON 并往 stderr 写 50 行
        String script = "for i in $(seq 1 50); do echo error-$i 1>&2; done; echo '{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"ok\"}'";
        StdioTransport transport = new StdioTransport(
                "sh", List.of("-c", script), Map.of(), null);
        try {
            CountDownLatch received = new CountDownLatch(1);
            transport.onReceive(node -> {
                if ("ok".equals(node.path("method").asText())) {
                    received.countDown();
                }
            });
            assertTrue(received.await(3, TimeUnit.SECONDS),
                    "stderr 大量输出不应阻塞 stdout 路径");
            // 等待 stderr 全部 drain
            Thread.sleep(200);
            List<String> lines = transport.stderrLines();
            assertFalse(lines.isEmpty());
            assertTrue(lines.size() <= 200, "stderr 环形 buffer 不能超过 200 行");
        } finally {
            transport.close();
        }
    }

    @Test
    void stderrRingBufferTruncatesOldLinesBeyondLimit() throws Exception {
        // 输出 250 行 stderr，预期只保留最近 200 行
        String script = "for i in $(seq 1 250); do echo line-$i 1>&2; done";
        StdioTransport transport = new StdioTransport(
                "sh", List.of("-c", script), Map.of(), null);
        try {
            // 子进程会自然结束，等 drain
            Thread.sleep(500);
            List<String> lines = transport.stderrLines();
            assertEquals(200, lines.size(), "环形 buffer 上限是 200");
            assertEquals("line-51", lines.get(0), "最早保留的应是第 51 行");
            assertEquals("line-250", lines.get(lines.size() - 1));
        } finally {
            transport.close();
        }
    }

    @Test
    void closeTerminatesLongRunningProcess() throws Exception {
        // 故意起一个不会自动退出的 sleep 进程
        StdioTransport transport = new StdioTransport(
                "sh", List.of("-c", "sleep 30"), Map.of(), null);
        Long pid = transport.processId();
        assertNotNull(pid);

        long start = System.currentTimeMillis();
        transport.close();
        long elapsed = System.currentTimeMillis() - start;

        // 优雅 1s + SIGTERM 2s 上限 = 3s 多一点
        assertTrue(elapsed < 5000, "close 应在 5 秒内完成（实际 " + elapsed + "ms）");
    }

    @Test
    void sendAfterCloseFailsExplicitly() throws Exception {
        StdioTransport transport = new StdioTransport(
                "sh", List.of("-c", "cat"), Map.of(), null);
        transport.close();

        IOException ex = assertThrows(IOException.class,
                () -> transport.send(MAPPER.readTree("{}")));
        assertTrue(ex.getMessage().contains("closed"), "错误消息应明示已关闭: " + ex.getMessage());
    }

    @Test
    void nonJsonStdoutLineIsSkippedAndReaderKeepsWorking() throws Exception {
        // 先等一行 stdin 再输出，保证 listener 已注册；日志行和非对象 JSON 都要跳过，后面的消息照常收到
        String script = "read x; echo 'Server started on stdio'; echo '42'; "
                + "echo '{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ok\"}'; sleep 1";
        StdioTransport transport = new StdioTransport(
                "sh", List.of("-c", script), Map.of(), null);
        try {
            CountDownLatch received = new CountDownLatch(1);
            transport.onReceive(node -> {
                if ("ok".equals(node.path("method").asText())) {
                    received.countDown();
                }
            });
            transport.send(MAPPER.readTree("{}"));

            assertTrue(received.await(3, TimeUnit.SECONDS), "非 JSON 行之后的消息应照常收到");
            String logs = String.join("\n", transport.stderrLines());
            assertTrue(logs.contains("Server started on stdio"), logs);
            assertFalse(logs.contains("stdout reader stopped"), logs);
        } finally {
            transport.close();
        }
    }

    @Test
    void listenerExceptionDoesNotStopReader() throws Exception {
        String script = "read x; echo '{\"id\":1,\"method\":\"boom\"}'; echo '{\"id\":2,\"method\":\"ok\"}'; sleep 1";
        StdioTransport transport = new StdioTransport(
                "sh", List.of("-c", script), Map.of(), null);
        try {
            CountDownLatch received = new CountDownLatch(1);
            transport.onReceive(node -> {
                if ("boom".equals(node.path("method").asText())) {
                    throw new IllegalStateException("handler failed");
                }
                received.countDown();
            });
            transport.send(MAPPER.readTree("{}"));
            assertTrue(received.await(3, TimeUnit.SECONDS));
        } finally {
            transport.close();
        }
    }

    @Test
    void childGetsWhitelistedAndConfiguredEnvOnly() throws Exception {
        String script = "read x; printf '{\"path\":\"%s\",\"token\":\"%s\"}\\n' \"$PATH\" \"$MY_TOKEN\"; sleep 1";
        StdioTransport transport = new StdioTransport(
                "sh", List.of("-c", script), Map.of("MY_TOKEN", "abc"), null);
        try {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<JsonNode> holder = new AtomicReference<>();
            transport.onReceive(node -> {
                holder.set(node);
                received.countDown();
            });
            transport.send(MAPPER.readTree("{}"));
            assertTrue(received.await(3, TimeUnit.SECONDS));
            assertFalse(holder.get().path("path").asText().isBlank(), "PATH 应保留");
            assertEquals("abc", holder.get().path("token").asText(), "配置里的 env 应传给子进程");
        } finally {
            transport.close();
        }
    }

    @Test
    void closeAlsoKillsChildThatOutlivesTheRootProcess() throws Exception {
        // 模拟 npx：根进程拉起一个子进程，自己在 stdin EOF 后退出，子进程不管 EOF 继续跑
        String script = "read x; sleep 60 & echo \"{\\\"pid\\\":$!}\"; cat";
        StdioTransport transport = new StdioTransport("sh", List.of("-c", script), Map.of(), null);
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<Long> childPid = new AtomicReference<>();
        transport.onReceive(node -> {
            childPid.set(node.path("pid").asLong());
            received.countDown();
        });
        transport.send(MAPPER.readTree("{}"));
        assertTrue(received.await(3, TimeUnit.SECONDS));
        ProcessHandle child = ProcessHandle.of(childPid.get()).orElseThrow();
        assertTrue(child.isAlive());

        transport.close();

        child.onExit().get(3, TimeUnit.SECONDS);
        assertFalse(child.isAlive(), "根进程退出后，它拉起的子进程也要被结束");
    }

    @Test
    void unexpectedExitNotifiesListenersButCloseDoesNot() throws Exception {
        StdioTransport crashing = new StdioTransport("sh", List.of("-c", "read x; exit 3"), Map.of(), null);
        CompletableFuture<String> reason = new CompletableFuture<>();
        crashing.onUnexpectedClose(reason::complete);
        crashing.send(MAPPER.readTree("{}"));
        assertTrue(reason.get(3, TimeUnit.SECONDS).contains("exit code 3"));
        // 已经退出后再注册，立即回调
        CompletableFuture<String> late = new CompletableFuture<>();
        crashing.onUnexpectedClose(late::complete);
        assertTrue(late.isDone());
        crashing.close();

        StdioTransport normal = new StdioTransport("sh", List.of("-c", "cat"), Map.of(), null);
        CompletableFuture<String> closed = new CompletableFuture<>();
        normal.onUnexpectedClose(closed::complete);
        normal.close();
        Thread.sleep(200);
        assertFalse(closed.isDone(), "主动关闭不算意外断开");
    }

    @Test
    void pendingRequestFailsAsSoonAsProcessExits() throws Exception {
        StdioTransport transport = new StdioTransport("sh", List.of("-c", "read x; exit 3"), Map.of(), null);
        com.paicli.mcp.jsonrpc.JsonRpcClient client = new com.paicli.mcp.jsonrpc.JsonRpcClient(transport);
        long start = System.currentTimeMillis();

        IOException error = assertThrows(IOException.class,
                () -> client.request("tools/list", MAPPER.createObjectNode(), 30));

        assertTrue(System.currentTimeMillis() - start < 5000, "不能等到 30 秒超时");
        assertTrue(error.getMessage().contains("exit code 3"), error.getMessage());
        assertThrows(IOException.class, () -> client.request("ping", MAPPER.createObjectNode(), 30),
                "断开后的新请求直接失败");
        client.close();
    }

    @Test
    void processTreeIsRecordedInPidFileAndRemovedOnClose(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path file = dir.resolve("mcp-pids.json");
        StdioTransport transport = new StdioTransport("sh", List.of("-c", "cat"), Map.of(), null, new McpPidFile(file));
        assertTrue(java.nio.file.Files.readString(file).contains(String.valueOf(transport.processId())));

        transport.close();

        assertFalse(java.nio.file.Files.readString(file).contains(String.valueOf(transport.processId())));
    }
}
