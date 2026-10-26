package com.paicli.mcp;

import com.paicli.mcp.config.McpConfigLoader;
import com.paicli.mcp.config.McpServerConfig;
import com.paicli.mcp.config.McpTrustStore;
import com.paicli.mcp.notifications.NotificationRouter;
import com.paicli.mcp.protocol.McpToolDescriptor;
import com.paicli.mcp.resources.McpResourceCache;
import com.paicli.mcp.resources.McpResourceContent;
import com.paicli.mcp.resources.McpResourceDescriptor;
import com.paicli.mcp.resources.McpResourceTool;
import com.paicli.policy.AuditLog;
import com.paicli.mcp.transport.McpPidFile;
import com.paicli.mcp.transport.McpTransport;
import com.paicli.util.AnsiStyle;
import com.paicli.util.TerminalTable;
import com.paicli.mcp.transport.StdioTransport;
import com.paicli.mcp.transport.StreamableHttpTransport;
import com.paicli.tool.ToolOutput;
import com.paicli.tool.ToolRegistry;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class McpServerManager implements AutoCloseable {
    private static final Duration STARTUP_PROGRESS_INTERVAL = Duration.ofSeconds(5);
    static final int MAX_AUTO_RESTARTS = 3;
    /** 上次启动后稳定运行超过这么久再退出，按一次新的意外处理，重启计数从头算 */
    private static final Duration STABLE_UPTIME = Duration.ofSeconds(60);
    private static final int PING_FAILURES_BEFORE_RESTART = 2;
    private static final long DEFAULT_PING_INTERVAL_SECONDS = 30;

    private final ToolRegistry toolRegistry;
    private final Path projectDir;
    private final McpConfigLoader configLoader;
    private final McpTrustStore trustStore;
    private final McpPidFile pidFile;
    private final ScheduledExecutorService supervisor = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "paicli-mcp-supervisor");
        t.setDaemon(true);
        return t;
    });
    // 后台发生的崩溃 / 自动重启，等下一次显示输入框前再打印，避免打断正在输入的那一行
    private final Queue<String> notices = new ConcurrentLinkedQueue<>();
    private volatile Duration restartBaseDelay = Duration.ofSeconds(1);
    private volatile Duration pingTimeout = Duration.ofSeconds(10);
    private boolean orphansCleaned;
    private boolean heartbeatStarted;
    private final Map<String, McpServer> servers = new ConcurrentHashMap<>();
    private final McpResourceCache resourceCache = new McpResourceCache();

    public McpServerManager(ToolRegistry toolRegistry, Path projectDir) {
        this(toolRegistry, projectDir, new McpConfigLoader(projectDir));
    }

    public McpServerManager(ToolRegistry toolRegistry, Path projectDir, McpConfigLoader configLoader) {
        this(toolRegistry, projectDir, configLoader, McpTrustStore.defaultStore());
    }

    public McpServerManager(ToolRegistry toolRegistry, Path projectDir, McpConfigLoader configLoader,
                            McpTrustStore trustStore) {
        this(toolRegistry, projectDir, configLoader, trustStore, McpPidFile.defaultFile());
    }

    public McpServerManager(ToolRegistry toolRegistry, Path projectDir, McpConfigLoader configLoader,
                            McpTrustStore trustStore, McpPidFile pidFile) {
        this.toolRegistry = toolRegistry;
        this.projectDir = projectDir.toAbsolutePath().normalize();
        this.configLoader = configLoader;
        this.trustStore = trustStore;
        this.pidFile = pidFile;
    }

    public void loadConfiguredServers() throws IOException {
        Map<String, McpServerConfig> configs = configLoader.load();
        servers.clear();
        configs.forEach((name, config) -> servers.put(name, new McpServer(name, config)));
    }

    public void startAll() {
        startAll(null);
    }

    public void startAll(PrintStream progressOut) {
        startAll(progressOut, null);
    }

    /**
     * Start all configured servers.
     *
     * <p>When {@code maxWait} is {@code null}, this method preserves the historical
     * blocking behavior and waits until every server reaches READY/ERROR. When a
     * bounded wait is supplied, unfinished servers continue starting on daemon
     * threads so the CLI can render the first prompt instead of being held hostage
     * by a slow stdio/http server.
     */
    public void startAll(PrintStream progressOut, Duration maxWait) {
        cleanupOrphansOnce();
        startHeartbeatOnce();
        List<McpServer> targets = servers.values().stream()
                .filter(server -> !server.config().isDisabled())
                .toList();
        if (targets.isEmpty()) {
            return;
        }
        // 用专属 daemon executor，避免 npx/uvx 冷启动期间占满 ForkJoinPool.commonPool 影响其他并发任务。
        AtomicInteger threadId = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(targets.size(), 8),
                r -> {
                    Thread t = new Thread(r, "paicli-mcp-startup-" + threadId.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
        Thread progressPrinter = startProgressPrinter(targets, progressOut, STARTUP_PROGRESS_INTERVAL);
        try {
            List<CompletableFuture<Void>> futures = targets.stream()
                    .map(server -> CompletableFuture.runAsync(() -> start(server), executor))
                    .toList();
            CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
            if (maxWait == null || maxWait.isZero() || maxWait.isNegative()) {
                all.join();
            } else {
                try {
                    all.get(Math.max(1, maxWait.toMillis()), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    printStartupTimeout(targets, progressOut, maxWait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    printStartupTimeout(targets, progressOut, maxWait);
                } catch (Exception e) {
                    all.join();
                }
            }
        } finally {
            if (progressPrinter != null) {
                progressPrinter.interrupt();
            }
            executor.shutdown();
        }
    }

    private void printStartupTimeout(List<McpServer> targets, PrintStream out, Duration maxWait) {
        if (out == null) {
            return;
        }
        List<McpServer> stillStarting = targets.stream()
                .filter(server -> server.status() == McpServerStatus.STARTING)
                .sorted(Comparator.comparing(McpServer::name))
                .toList();
        if (stillStarting.isEmpty()) {
            return;
        }
        String names = stillStarting.stream()
                .map(McpServer::name)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        long displaySeconds = Math.max(1, (long) Math.ceil(maxWait.toMillis() / 1000.0));
        out.printf("⚠️ MCP 启动超过 %ds，先进入 CLI；后台继续启动: %s%n",
                displaySeconds, names);
        out.println("   可用 /mcp 查看最新状态，或 /mcp logs <name> 查看日志。");
        out.flush();
    }

    private Thread startProgressPrinter(List<McpServer> targets, PrintStream out, Duration interval) {
        if (out == null || targets.isEmpty()) {
            return null;
        }
        Map<String, Instant> startedAt = new ConcurrentHashMap<>();
        targets.forEach(server -> startedAt.put(server.name(), Instant.now()));
        Thread thread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    TimeUnit.MILLISECONDS.sleep(interval.toMillis());
                    List<McpServer> starting = targets.stream()
                            .filter(server -> server.status() == McpServerStatus.STARTING)
                            .sorted(Comparator.comparing(McpServer::name))
                            .toList();
                    if (starting.isEmpty()) {
                        continue;
                    }
                    for (McpServer server : starting) {
                        long waited = Duration.between(startedAt.get(server.name()), Instant.now()).toSeconds();
                        out.printf("   ⏳ %-16s %-6s 启动中...（已等待 %ds）%n",
                                server.name(), server.transportName(), waited);
                    }
                    out.flush();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "paicli-mcp-startup-progress");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    public synchronized String restart(String name) {
        McpServer server = servers.get(name);
        if (server == null) {
            return "未找到 MCP server: " + name;
        }
        server.cancelAutoRestart();
        unregisterTools(server);
        server.close();
        server.config().setDisabled(false);
        start(server);
        if (server.status() == McpServerStatus.UNTRUSTED) {
            return untrustedMessage(server);
        }
        return server.status() == McpServerStatus.READY
                ? "✅ MCP server 已重启: " + name
                : "❌ MCP server 重启失败: " + name + " - " + server.errorMessage();
    }

    public synchronized String restartWithArgs(String name, List<String> args) {
        McpServer server = servers.get(name);
        if (server == null) {
            return "未找到 MCP server: " + name;
        }
        server.config().setArgs(args);
        return restart(name);
    }

    public McpServer server(String name) {
        return servers.get(name);
    }

    public synchronized String disable(String name) {
        McpServer server = servers.get(name);
        if (server == null) {
            return "未找到 MCP server: " + name;
        }
        server.cancelAutoRestart();
        unregisterTools(server);
        server.close();
        server.config().setDisabled(true);
        server.status(McpServerStatus.DISABLED);
        server.errorMessage(null);
        return "⏸️ MCP server 已禁用: " + name;
    }

    public synchronized String enable(String name) {
        McpServer server = servers.get(name);
        if (server == null) {
            return "未找到 MCP server: " + name;
        }
        server.cancelAutoRestart();
        server.config().setDisabled(false);
        start(server);
        if (server.status() == McpServerStatus.UNTRUSTED) {
            return untrustedMessage(server);
        }
        return server.status() == McpServerStatus.READY
                ? "▶️ MCP server 已启用: " + name
                : "❌ MCP server 启用失败: " + name + " - " + server.errorMessage();
    }

    /**
     * 确认项目级 server 并启动。确认绑定当前配置内容的指纹，仓库更新改了配置后要重新确认。
     */
    public synchronized String trust(String name) {
        if (name == null || name.isBlank()) {
            return "❌ 请提供 MCP server 名称，例如 /mcp trust filesystem（用 /mcp 查看待确认的 server）";
        }
        McpServer server = servers.get(name);
        if (server == null) {
            return "未找到 MCP server: " + name;
        }
        McpServerConfig config = server.config();
        if (!config.projectScoped()) {
            return "ℹ️ " + name + " 来自你自己的 ~/.paicli/mcp.json，无需确认";
        }
        if (isTrusted(server) && server.status() != McpServerStatus.UNTRUSTED) {
            return "ℹ️ 项目级 MCP server " + name + " 的当前配置已确认";
        }
        try {
            trustStore.trust(config.projectConfigFile(), name, config.projectFingerprint());
        } catch (IOException e) {
            return "❌ 保存确认记录失败: " + e.getMessage();
        }
        server.cancelAutoRestart();
        start(server);
        String prefix = "🔓 已确认项目级 MCP server: " + name + "（配置改动后需要重新确认）\n";
        if (server.status() == McpServerStatus.READY) {
            return prefix + "✅ 已启动，" + server.tools().size() + " 个工具";
        }
        if (server.status() == McpServerStatus.DISABLED) {
            return prefix + "⏸️ 配置里是 disabled，未启动";
        }
        return prefix + "❌ 启动失败: " + server.errorMessage();
    }

    /** 启动时提示：项目配置里有哪些 server 因为没确认而没有启动。 */
    public String untrustedStartupNote() {
        List<String> names = servers().stream()
                .filter(server -> server.status() == McpServerStatus.UNTRUSTED)
                .map(McpServer::name)
                .toList();
        if (names.isEmpty()) {
            return "";
        }
        return "项目 .paicli/mcp.json 里有 " + names.size() + " 个 MCP server 还没确认，没有启动："
                + String.join("、", names) + "。用 /mcp 查看要执行的命令，确认后输入 /mcp trust <name>";
    }

    private boolean isTrusted(McpServer server) {
        McpServerConfig config = server.config();
        if (!config.projectScoped()) {
            return true;
        }
        return trustStore != null
                && trustStore.isTrusted(config.projectConfigFile(), server.name(), config.projectFingerprint());
    }

    private static String untrustedMessage(McpServer server) {
        return "🔒 " + server.name() + " 来自项目 .paicli/mcp.json，还没确认，未启动。\n"
                + "   要执行的内容：" + describeLaunch(server.config()) + "\n"
                + "   确认后输入 /mcp trust " + server.name();
    }

    /** 展示原始配置（未展开 ${VAR}），env / headers 只列名字不列值。 */
    static String describeLaunch(McpServerConfig config) {
        StringBuilder sb = new StringBuilder();
        if (config.isHttp()) {
            sb.append("连接 ").append(config.getUrl());
            if (!config.getHeaders().isEmpty()) {
                sb.append("，headers: ").append(String.join(", ", config.getHeaders().keySet()));
            }
        } else {
            sb.append(config.getCommand());
            for (String arg : config.getArgs()) {
                sb.append(' ').append(arg);
            }
        }
        if (!config.getEnv().isEmpty()) {
            sb.append("，env: ").append(String.join(", ", config.getEnv().keySet()));
        }
        return sb.toString();
    }

    public String logs(String name) {
        McpServer server = servers.get(name);
        if (server == null) {
            return "未找到 MCP server: " + name;
        }
        List<String> lines = server.logs();
        if (lines.isEmpty()) {
            return "📭 MCP server 暂无 stderr 日志: " + name;
        }
        return String.join(System.lineSeparator(), lines);
    }

    public Collection<McpServer> servers() {
        return servers.values().stream()
                .sorted(java.util.Comparator.comparing(McpServer::name))
                .toList();
    }

    public String formatStatus() {
        if (servers.isEmpty()) {
            return TerminalTable.wrap("MCP · 未配置\n配置文件：~/.paicli/mcp.json 或 .paicli/mcp.json", TerminalTable.columns());
        }
        List<List<String>> rows = new java.util.ArrayList<>();
        List<String> errors = new java.util.ArrayList<>();
        List<String> untrusted = new java.util.ArrayList<>();
        long ready = 0;
        for (McpServer server : servers()) {
            String status = switch (server.status()) {
                case READY -> AnsiStyle.section("● 就绪");
                case STARTING -> "… 启动中";
                case DISABLED -> AnsiStyle.subtle("○ 停用");
                case ERROR -> AnsiStyle.error("✗ 失败");
                case UNTRUSTED -> AnsiStyle.emphasis("🔒 待确认");
            };
            boolean isReady = server.status() == McpServerStatus.READY;
            if (isReady) ready++;
            String runtime = isReady ? formatDuration(server.uptime()) : "—";
            if (server.processId() != null) runtime += " · PID " + server.processId();
            rows.add(List.of(server.name(), status, server.transportName(),
                    isReady ? Integer.toString(server.tools().size()) : "—", runtime));
            if (server.status() == McpServerStatus.ERROR && server.errorMessage() != null)
                errors.add(server.name() + "：" + server.errorMessage());
            if (server.status() == McpServerStatus.UNTRUSTED)
                untrusted.add(server.name() + "：" + describeLaunch(server.config()));
        }
        String out = AnsiStyle.emphasis("MCP · " + ready + "/" + servers.size() + " 就绪") + "\n\n"
                + TerminalTable.render(List.of("服务", "状态", "连接", "工具数", "运行"), rows, TerminalTable.columns());
        if (!errors.isEmpty()) out += "\n\n" + TerminalTable.wrap(String.join("\n", errors), TerminalTable.columns());
        if (!untrusted.isEmpty()) {
            out += "\n\n" + TerminalTable.wrap("待确认（来自项目 .paicli/mcp.json，确认前不会执行）：\n"
                    + String.join("\n", untrusted) + "\n确认后启动：/mcp trust <name>", TerminalTable.columns());
        }
        return out + "\n\n" + TerminalTable.wrap("日志：/mcp logs <name>", TerminalTable.columns());
    }

    public String startupSummary() {
        if (servers.isEmpty()) {
            return "🔌 MCP server：未配置（可创建 ~/.paicli/mcp.json 或 .paicli/mcp.json）";
        }
        long ready = servers.values().stream().filter(s -> s.status() == McpServerStatus.READY).count();
        int tools = servers.values().stream().mapToInt(s -> s.tools().size()).sum();
        StringBuilder sb = new StringBuilder("🔌 启动 MCP server（" + servers.size() + " 个）...\n");
        for (McpServer server : servers()) {
            if (server.status() == McpServerStatus.READY) {
                sb.append(String.format("   ✓ %-14s %-6s %3d 工具%n",
                        server.name(), server.transportName(), server.tools().size()));
            } else if (server.status() == McpServerStatus.DISABLED) {
                sb.append(String.format("   ○ %-14s %-6s disabled%n", server.name(), server.transportName()));
            } else if (server.status() == McpServerStatus.UNTRUSTED) {
                sb.append(String.format("   🔒 %-14s %-6s 待确认（/mcp trust %s）%n",
                        server.name(), server.transportName(), server.name()));
            } else if (server.status() == McpServerStatus.STARTING) {
                sb.append(String.format("   … %-14s %-6s starting%n", server.name(), server.transportName()));
            } else {
                sb.append(String.format("   ✗ %-14s %-6s 启动失败: %s%n",
                        server.name(), server.transportName(), server.errorMessage()));
            }
        }
        sb.append("   ").append(ready).append("/").append(servers.size())
                .append(" 就绪，共 ").append(tools).append(" 个 MCP 工具");
        return sb.toString();
    }

    public List<McpResourceDescriptor> resourceCandidates() {
        return resourceCache.all();
    }

    public String resourceIndexForPrompt() {
        List<McpResourceDescriptor> resources = resourceCache.all().stream()
                .sorted(Comparator.comparing(McpResourceDescriptor::serverName)
                        .thenComparing(McpResourceDescriptor::uri))
                .limit(200)
                .toList();
        if (resources.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("## MCP Resources 索引（仅 URI / 描述，不含正文）\n\n");
        sb.append("长上下文模式下可参考以下资源索引判断是否需要读取 resource；需要正文时再调用对应 MCP resource 工具或使用用户显式 @-mention。\n\n");
        for (McpResourceDescriptor resource : resources) {
            sb.append("- @").append(resource.serverName()).append(':').append(resource.uri());
            String displayName = resource.displayName();
            if (!displayName.equals(resource.uri())) {
                sb.append(" — ").append(displayName);
            }
            if (resource.description() != null && !resource.description().isBlank()) {
                sb.append("：").append(resource.description());
            }
            if (resource.mimeType() != null && !resource.mimeType().isBlank()) {
                sb.append(" [").append(resource.mimeType()).append(']');
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    public String resources(String serverName) {
        McpServer server = servers.get(serverName);
        if (server == null) {
            return "未找到 MCP server: " + serverName;
        }
        if (server.client() == null || server.status() != McpServerStatus.READY) {
            return "MCP server 未就绪: " + serverName + " (" + server.status() + ")";
        }
        try {
            List<McpResourceDescriptor> resources = refreshResources(server);
            return McpClient.formatResources(resources);
        } catch (Exception e) {
            return "读取 MCP resources 失败: " + e.getMessage();
        }
    }

    public String prompts(String serverName) {
        McpServer server = servers.get(serverName);
        if (server == null) {
            return "未找到 MCP server: " + serverName;
        }
        if (server.client() == null || server.status() != McpServerStatus.READY) {
            return "MCP server 未就绪: " + serverName + " (" + server.status() + ")";
        }
        try {
            List<String> prompts = server.client().listPrompts();
            if (prompts.isEmpty()) {
                return "📭 该 MCP server 暂无 prompts: " + serverName;
            }
            StringBuilder sb = new StringBuilder("🧩 MCP prompts - ").append(serverName).append('\n');
            for (String prompt : prompts) {
                sb.append("- ").append(prompt).append('\n');
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "读取 MCP prompts 失败: " + e.getMessage();
        }
    }

    public ResourceReadResult readResourceForMention(String serverName, String uri) throws IOException {
        McpServer server = servers.get(serverName);
        if (server == null) {
            throw new IOException("未找到 MCP server: " + serverName);
        }
        if (server.client() == null || server.status() != McpServerStatus.READY) {
            throw new IOException("MCP server 未就绪: " + serverName + " (" + server.status() + ")");
        }
        long start = System.nanoTime();
        String toolName = McpToolDescriptor.namespaced(serverName, McpResourceTool.READ_RESOURCE);
        String args = "{\"uri\":\"" + uri.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        try {
            if (resourceCache.isServerStale(serverName)) {
                refreshResources(server);
            }
            List<McpResourceContent> contents = server.client().readResource(uri);
            resourceCache.markResourceFresh(serverName, uri);
            toolRegistry.getAuditLog().record(AuditLog.AuditEntry.allowByMention(
                    toolName, args, elapsedMillis(start)));
            return ResourceReadResult.from(contents);
        } catch (Exception e) {
            toolRegistry.getAuditLog().record(AuditLog.AuditEntry.error(
                    toolName, args, e.getMessage(), elapsedMillis(start)));
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException(e.getMessage(), e);
        }
    }

    private void start(McpServer server) {
        unregisterTools(server);
        server.close();
        if (server.config().isDisabled()) {
            server.status(McpServerStatus.DISABLED);
            return;
        }
        // 项目级配置随仓库分发，没确认前不展开 ${VAR}、不起进程、不连 url
        if (!isTrusted(server)) {
            server.errorMessage(null);
            server.status(McpServerStatus.UNTRUSTED);
            return;
        }
        server.status(McpServerStatus.STARTING);
        server.errorMessage(null);
        try {
            // 在单 server 启动路径里展开 ${VAR} 与校验 transport，
            // 单个失败仅标 ERROR，不会阻塞其他 server。
            configLoader.prepare(server.config());
            McpTransport transport = createTransport(server.config());
            McpClient client = new McpClient(server.name(), transport);
            client.initialize();
            registerNotificationHandlers(server, client);
            List<McpToolDescriptor> tools = buildToolList(server, client);
            replaceTools(server, client, tools);
            server.client(client);
            server.tools(tools);
            server.markStarted();
            server.status(McpServerStatus.READY);
            // 放在 READY 之后注册：启动过程中进程退出由 initialize 失败处理，不触发自动重启
            client.onUnexpectedClose(reason -> onServerDied(server, client, reason));
        } catch (Exception e) {
            server.close();
            server.errorMessage(e.getMessage());
            server.status(McpServerStatus.ERROR);
        }
    }

    private List<McpToolDescriptor> buildToolList(McpServer server, McpClient client) throws IOException {
        List<McpToolDescriptor> tools = new ArrayList<>(client.listTools());
        if (client.supportsResources()) {
            List<McpResourceDescriptor> resources = client.listResources();
            resourceCache.put(server.name(), resources);
            tools.addAll(McpResourceTool.descriptors(server.name()));
        }
        validateNoDuplicateTools(server.name(), tools);
        return tools;
    }

    private void replaceTools(McpServer server, McpClient client, List<McpToolDescriptor> tools) {
        toolRegistry.replaceMcpToolOutputsForServer(server.name(), tools,
                descriptor -> isResourceVirtualTool(descriptor)
                        ? args -> ToolOutput.text(McpResourceTool.invoker(client, descriptor).apply(args))
                        : args -> invokeMcpToolOutput(client, descriptor, args));
    }

    private boolean isResourceVirtualTool(McpToolDescriptor descriptor) {
        return McpResourceTool.LIST_RESOURCES.equals(descriptor.name())
                || McpResourceTool.READ_RESOURCE.equals(descriptor.name());
    }

    private void registerNotificationHandlers(McpServer server, McpClient client) {
        NotificationRouter router = new NotificationRouter();
        router.on("notifications/tools/list_changed", ignored -> {
            try {
                List<McpToolDescriptor> tools = buildToolList(server, client);
                replaceTools(server, client, tools);
                server.tools(tools);
            } catch (Exception e) {
                server.errorMessage("tools/list_changed 处理失败: " + e.getMessage());
            }
        });
        router.on("notifications/resources/list_changed", ignored -> resourceCache.invalidateServer(server.name()));
        router.on("notifications/resources/updated", params -> {
            String uri = params.path("uri").asText("");
            if (!uri.isBlank()) {
                resourceCache.invalidateResource(server.name(), uri);
            }
        });
        client.onNotification(router);
        server.notificationRouter(router);
    }

    private List<McpResourceDescriptor> refreshResources(McpServer server) throws IOException {
        List<McpResourceDescriptor> resources = server.client().listResources();
        resources = resources.stream()
                .sorted(Comparator.comparing(McpResourceDescriptor::uri))
                .toList();
        resourceCache.put(server.name(), resources);
        return resources;
    }

    /**
     * MCP 工具执行入口：把 LLM 给的 JSON 参数透传给 server 的 tools/call，并把异常转成 LLM 可读字符串。
     * 提取成独立方法是为了让 server 维度的错误信息（serverName/toolName）在堆栈和日志里清晰可见。
     */
    private static ToolOutput invokeMcpToolOutput(McpClient client, McpToolDescriptor descriptor, String argumentsJson) {
        try {
            return client.callToolOutput(descriptor.name(), argumentsJson);
        } catch (Exception e) {
            return ToolOutput.failure("MCP 工具调用失败 (" + descriptor.serverName() + "/" + descriptor.name() + "): "
                    + e.getMessage());
        }
    }

    private McpTransport createTransport(McpServerConfig config) throws IOException {
        if (config.isHttp()) {
            return new StreamableHttpTransport(config.getUrl(), config.getHeaders());
        }
        return new StdioTransport(config.getCommand(), config.getArgs(), config.getEnv(), projectDir, pidFile);
    }

    /**
     * 进程意外退出或判定卡死：移除工具、结束进程树，然后按 1s、2s、4s 退避自动重启；
     * 连续 3 次没救回来就停下，提示用户手动 /mcp restart。
     */
    private synchronized void onServerDied(McpServer server, McpClient client, String reason) {
        if (server.client() != client) {
            return; // 已经被主动关闭或换成新进程，旧进程的退出不用管
        }
        boolean wasStable = server.uptime().compareTo(STABLE_UPTIME) >= 0;
        unregisterTools(server);
        server.close();
        server.status(McpServerStatus.ERROR);
        server.errorMessage(reason);
        notices.add("⚠️ MCP server " + server.name() + " 断开：" + reason);
        scheduleRestartOrGiveUp(server, reason, wasStable);
    }

    private void scheduleRestartOrGiveUp(McpServer server, String reason, boolean resetStreak) {
        int attempt = server.nextCrashAttempt(resetStreak);
        if (attempt > MAX_AUTO_RESTARTS) {
            server.errorMessage(reason + "；自动重启 " + MAX_AUTO_RESTARTS + " 次都没成功，已停止");
            notices.add("❌ MCP server " + server.name() + " 自动重启 " + MAX_AUTO_RESTARTS
                    + " 次都没成功，已停止。查看日志：/mcp logs " + server.name()
                    + "，手动重启：/mcp restart " + server.name());
            return;
        }
        long delayMillis = restartBaseDelay.toMillis() << (attempt - 1);
        server.errorMessage(reason + "；" + formatMillis(delayMillis) + "后第 " + attempt + " 次自动重启");
        try {
            server.pendingRestart(supervisor.schedule(() -> autoRestart(server),
                    delayMillis, TimeUnit.MILLISECONDS));
        } catch (RejectedExecutionException ignored) {
            // PaiCLI 正在退出
        }
    }

    private synchronized void autoRestart(McpServer server) {
        server.pendingRestart(null);
        if (server.status() != McpServerStatus.ERROR || server.config().isDisabled()) {
            return; // 等待期间用户已经手动处理过
        }
        start(server);
        if (server.status() == McpServerStatus.READY) {
            notices.add("✅ MCP server " + server.name() + " 已自动重启");
        } else if (server.status() == McpServerStatus.ERROR) {
            scheduleRestartOrGiveUp(server, server.errorMessage(), false);
        }
    }

    /**
     * 定期 ping stdio server，进程还在但不读不写（卡死）时也能发现。server 正在处理请求时跳过，
     * 单线程 server 忙着跑一个长工具调用时回不了 ping，不能因此被误杀；那次调用有自己的超时。
     */
    void pingAll() {
        for (McpServer server : servers.values()) {
            McpClient client = server.client();
            if (server.status() != McpServerStatus.READY || client == null
                    || !"stdio".equals(client.transportName()) || client.hasPendingRequests()) {
                continue;
            }
            try {
                client.ping(pingTimeout.toMillis());
                server.resetPingFailures();
            } catch (IOException e) {
                if (server.recordPingFailure() >= PING_FAILURES_BEFORE_RESTART) {
                    onServerDied(server, client, "连续 " + PING_FAILURES_BEFORE_RESTART + " 次 ping 没有回应，判定卡死");
                }
            }
        }
    }

    private synchronized void startHeartbeatOnce() {
        if (heartbeatStarted) {
            return;
        }
        heartbeatStarted = true;
        long interval = pingIntervalSeconds();
        if (interval <= 0) {
            return;
        }
        supervisor.scheduleWithFixedDelay(() -> {
            try {
                pingAll();
            } catch (RuntimeException e) {
                // 心跳失败不能让定时任务停掉
            }
        }, interval, interval, TimeUnit.SECONDS);
    }

    static long pingIntervalSeconds() {
        String configured = System.getProperty("paicli.mcp.ping.interval.seconds");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("PAICLI_MCP_PING_INTERVAL_SECONDS");
        }
        if (configured == null || configured.isBlank()) {
            return DEFAULT_PING_INTERVAL_SECONDS;
        }
        try {
            return Long.parseLong(configured.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_PING_INTERVAL_SECONDS;
        }
    }

    /** 上次 PaiCLI 被 kill -9 时留下的 MCP 子进程，启动时清理一次。 */
    private synchronized void cleanupOrphansOnce() {
        if (orphansCleaned || pidFile == null) {
            return;
        }
        orphansCleaned = true;
        int cleaned = pidFile.cleanupOrphans();
        if (cleaned > 0) {
            notices.add("🧹 已结束 " + cleaned + " 个上次 PaiCLI 异常退出时遗留的 MCP 子进程");
        }
    }

    /** 取出后台积累的提示，由 CLI 在显示下一个输入框前打印。 */
    public List<String> drainNotices() {
        List<String> drained = new ArrayList<>();
        String notice;
        while ((notice = notices.poll()) != null) {
            drained.add(notice);
        }
        return drained;
    }

    void restartBaseDelay(Duration delay) {
        this.restartBaseDelay = delay;
    }

    void pingTimeout(Duration timeout) {
        this.pingTimeout = timeout;
    }

    private static String formatMillis(long millis) {
        return millis % 1000 == 0 ? (millis / 1000) + " 秒" : millis + " 毫秒";
    }

    private void validateNoDuplicateTools(String serverName, List<McpToolDescriptor> tools) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (McpToolDescriptor tool : tools) {
            counts.merge(tool.name(), 1, Integer::sum);
        }
        List<String> duplicates = new ArrayList<>();
        counts.forEach((name, count) -> {
            if (count > 1) duplicates.add(name);
        });
        if (!duplicates.isEmpty()) {
            throw new IllegalArgumentException("MCP server " + serverName + " 返回重复工具名: " + duplicates);
        }
    }

    private void unregisterTools(McpServer server) {
        for (McpToolDescriptor tool : server.tools()) {
            toolRegistry.unregisterMcpTool(tool.namespacedName());
        }
        server.tools(List.of());
    }

    private static long elapsedMillis(long startedAtNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    public record ResourceReadResult(String content, String mimeType) {
        static ResourceReadResult from(List<McpResourceContent> contents) {
            if (contents == null || contents.isEmpty()) {
                return new ResourceReadResult("", "text/plain");
            }
            StringBuilder text = new StringBuilder();
            String firstMimeType = null;
            for (McpResourceContent content : contents) {
                if (firstMimeType == null || firstMimeType.isBlank()) {
                    firstMimeType = content.mimeType();
                }
                if (content.isText()) {
                    text.append(content.text());
                } else {
                    text.append("[binary resource blob omitted, base64 length=")
                            .append(content.blob() == null ? 0 : content.blob().length())
                            .append(']');
                }
                text.append(System.lineSeparator());
            }
            return new ResourceReadResult(text.toString().trim(), firstMimeType);
        }
    }

    private static String formatDuration(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m";
        return (minutes / 60) + "h";
    }

    @Override
    public void close() {
        supervisor.shutdownNow();
        for (McpServer server : servers.values()) {
            server.cancelAutoRestart();
            unregisterTools(server);
            server.close();
        }
    }
}
