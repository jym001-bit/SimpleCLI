package com.paicli.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class StdioTransport implements McpTransport {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_STDERR_LINES = 200;
    private static final int MAX_IGNORED_LINE_CHARS = 500;
    private static final long TREE_REFRESH_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final Process process;
    private final BufferedWriter stdin;
    private final List<Consumer<JsonNode>> listeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<String> stderrRing = new ArrayDeque<>();
    private final Object stderrLock = new Object();
    private volatile boolean closed;
    // npx / uvx 会再拉起 node / python 子进程；记下见过的整棵进程树，根进程退出后也能把它们找回来结束掉
    private final Set<ProcessHandle> tracked = ConcurrentHashMap.newKeySet();
    private final McpPidFile pidFile;
    private volatile long lastTreeRefreshNanos;
    private final Object exitLock = new Object();
    private final List<Consumer<String>> exitListeners = new ArrayList<>();
    private String exitReason;

    public StdioTransport(String command, List<String> args, Map<String, String> env, Path workingDir) throws IOException {
        this(command, args, env, workingDir, null);
    }

    public StdioTransport(String command, List<String> args, Map<String, String> env, Path workingDir,
                          McpPidFile pidFile) throws IOException {
        this.pidFile = pidFile;
        List<String> commandLine = new ArrayList<>();
        commandLine.add(command);
        if (args != null) {
            commandLine.addAll(args);
        }
        ProcessBuilder builder = new ProcessBuilder(commandLine);
        if (workingDir != null) {
            builder.directory(workingDir.toFile());
        }
        // 不继承 PaiCLI 的全部环境变量，避免把用户的各种 API Key 交给 MCP 服务
        Map<String, String> childEnv = builder.environment();
        Map<String, String> allowed = McpChildEnvironment.build(System.getenv(), env);
        childEnv.clear();
        childEnv.putAll(allowed);
        this.process = builder.start();
        lastTreeRefreshNanos = System.nanoTime(); // nanoTime 可能是负数，不能用 0 当“从没刷新过”
        track(process.toHandle());
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        startStdoutReader();
        startStderrReader();
        process.onExit().thenRun(this::handleExit);
    }

    @Override
    public synchronized void send(JsonNode message) throws IOException {
        if (closed) {
            throw new IOException("MCP stdio transport already closed");
        }
        stdin.write(MAPPER.writeValueAsString(message));
        stdin.newLine();
        stdin.flush();
        if (System.nanoTime() - lastTreeRefreshNanos > TREE_REFRESH_INTERVAL_NANOS) {
            refreshProcessTree();
        }
    }

    @Override
    public void onUnexpectedClose(Consumer<String> listener) {
        if (listener == null) {
            return;
        }
        String reason;
        synchronized (exitLock) {
            if (exitReason == null) {
                exitListeners.add(listener);
                return;
            }
            reason = exitReason;
        }
        listener.accept(reason);
    }

    private void handleExit() {
        if (closed) {
            return;
        }
        List<Consumer<String>> listeners;
        String reason = "MCP server 进程已退出（exit code " + process.exitValue() + "）";
        synchronized (exitLock) {
            exitReason = reason;
            listeners = List.copyOf(exitListeners);
            exitListeners.clear();
        }
        for (Consumer<String> listener : listeners) {
            try {
                listener.accept(reason);
            } catch (RuntimeException e) {
                appendStderr("[paicli] 处理进程退出失败: " + e.getMessage());
            }
        }
    }

    private void track(ProcessHandle handle) {
        if (tracked.add(handle) && pidFile != null) {
            pidFile.record(handle);
        }
    }

    private void refreshProcessTree() {
        lastTreeRefreshNanos = System.nanoTime();
        try {
            process.descendants().forEach(this::track);
        } catch (RuntimeException ignored) {
            // 个别受限环境不允许枚举进程树，只能结束根进程
        }
    }

    @Override
    public void onReceive(Consumer<JsonNode> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public List<String> stderrLines() {
        synchronized (stderrLock) {
            return List.copyOf(stderrRing);
        }
    }

    @Override
    public Long processId() {
        return process.pid();
    }

    @Override
    public String transportName() {
        return "stdio";
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // 先记下整棵进程树：根进程一退出，它的子进程就挂到 init 下，再也查不到了
        refreshProcessTree();
        // 关 stdin 让子进程读到 EOF，给一次优雅退出窗口（1 秒）
        try {
            stdin.close();
        } catch (IOException ignored) {
        }
        try {
            process.waitFor(1, TimeUnit.SECONDS);
            refreshProcessTree();
            terminateTree();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            tracked.forEach(ProcessHandle::destroyForcibly);
        }
        if (pidFile != null) {
            pidFile.remove(tracked);
        }
    }

    /** 从叶子进程开始发 SIGTERM，最多等 2 秒，还活着的强制结束。 */
    private void terminateTree() throws InterruptedException {
        List<ProcessHandle> alive = leafFirst(tracked.stream().filter(ProcessHandle::isAlive).toList());
        alive.forEach(ProcessHandle::destroy);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        for (ProcessHandle handle : alive) {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                try {
                    handle.onExit().get(remaining, TimeUnit.NANOSECONDS);
                } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) {
                    // 下面强制结束
                }
            }
        }
        alive.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }

    /** 按在树里的深度从深到浅排；不能按 PID 大小排，PID 会回绕。 */
    static List<ProcessHandle> leafFirst(List<ProcessHandle> handles) {
        Map<Long, ProcessHandle> byPid = new HashMap<>();
        handles.forEach(handle -> byPid.put(handle.pid(), handle));
        Map<Long, Integer> depth = new HashMap<>();
        for (ProcessHandle handle : handles) {
            int d = 0;
            Optional<ProcessHandle> parent = handle.parent();
            while (parent.isPresent() && byPid.containsKey(parent.get().pid()) && d < handles.size()) {
                d++;
                parent = byPid.get(parent.get().pid()).parent();
            }
            depth.put(handle.pid(), d);
        }
        List<ProcessHandle> sorted = new ArrayList<>(handles);
        sorted.sort(Comparator.comparingInt((ProcessHandle handle) -> depth.get(handle.pid())).reversed());
        return sorted;
    }

    private void startStdoutReader() {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        handleStdoutLine(line);
                    }
                }
            } catch (Exception e) {
                appendStderr("[paicli] stdout reader stopped: " + e.getMessage());
            }
        }, "paicli-mcp-stdio-stdout");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 每行单独处理：server 往 stdout 打了一行日志（不是 JSON）时只记进 stderr 缓冲并跳过，
     * 读线程继续工作；否则后面所有请求都读不到回复，只能等超时。
     */
    private void handleStdoutLine(String line) {
        JsonNode message;
        try {
            message = MAPPER.readTree(line);
        } catch (Exception e) {
            appendStderr("[paicli] stdout 非 JSON 行已忽略: " + abbreviate(line));
            return;
        }
        if (message != null && message.isArray()) {
            message.forEach(this::dispatch);
        } else {
            dispatch(message);
        }
    }

    private void dispatch(JsonNode message) {
        if (message == null || !message.isObject()) {
            appendStderr("[paicli] stdout 非 JSON-RPC 消息已忽略: " + abbreviate(String.valueOf(message)));
            return;
        }
        for (Consumer<JsonNode> listener : listeners) {
            try {
                listener.accept(message);
            } catch (RuntimeException e) {
                appendStderr("[paicli] 处理 MCP 消息失败: " + e.getMessage());
            }
        }
    }

    private static String abbreviate(String line) {
        return line.length() <= MAX_IGNORED_LINE_CHARS ? line : line.substring(0, MAX_IGNORED_LINE_CHARS) + "...";
    }

    private void startStderrReader() {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendStderr(line);
                }
            } catch (IOException e) {
                appendStderr("[paicli] stderr reader stopped: " + e.getMessage());
            }
        }, "paicli-mcp-stdio-stderr");
        thread.setDaemon(true);
        thread.start();
    }

    private void appendStderr(String line) {
        synchronized (stderrLock) {
            while (stderrRing.size() >= MAX_STDERR_LINES) {
                stderrRing.removeFirst();
            }
            stderrRing.addLast(line);
        }
    }
}
