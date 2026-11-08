package com.paicli.cli;

import com.paicli.agent.Agent;
import com.paicli.agent.AgentOrchestrator;
import com.paicli.agent.ExecutionOutcome;
import com.paicli.agent.PlanExecuteAgent;
import com.paicli.agent.TaskContract;
import com.paicli.agent.TaskUnderstandingEngine;
import com.paicli.browser.BrowserAuditMetadata;
import com.paicli.browser.BrowserConnectivityCheck;
import com.paicli.browser.BrowserGuard;
import com.paicli.browser.BrowserMode;
import com.paicli.browser.BrowserSession;
import com.paicli.browser.SensitivePagePolicy;
import com.paicli.config.PaiCliConfig;
import com.paicli.hitl.HitlHandler;
import com.paicli.hitl.HitlToolRegistry;
import com.paicli.hitl.SwitchableHitlHandler;
import com.paicli.hitl.RendererHitlHandler;
import com.paicli.hitl.TerminalHitlHandler;
import com.paicli.history.ConversationLedger;
import com.paicli.harness.BetterHarnessOptions;
import com.paicli.harness.BetterHarnessRunner;
import com.paicli.llm.LlmClient;
import com.paicli.llm.ModelCatalog;
import com.paicli.llm.LlmClientFactory;
import com.paicli.memory.LongTermMemory;
import com.paicli.memory.MemoryCommandArgs;
import com.paicli.memory.MemoryEntry;
import com.paicli.memory.ExternalContextTracker;
import com.paicli.memory.MemoryManager;
import com.paicli.memory.MemoryWriteResult;
import com.paicli.render.Renderer;
import com.paicli.render.RendererFactory;
import com.paicli.render.StatusInfo;
import com.paicli.render.inline.InlineRenderer;
import com.paicli.image.ClipboardImage;
import com.paicli.mcp.McpServer;
import com.paicli.mcp.McpServerManager;
import com.paicli.mcp.McpServerStatus;
import com.paicli.mcp.mention.AtMentionExpander;
import com.paicli.plan.ExecutionPlan;
import com.paicli.rag.CodeIndex;
import com.paicli.hitl.ApprovalPolicy;
import com.paicli.policy.AuditLog;
import com.paicli.rag.CodeRetriever;
import com.paicli.rag.CodeRelation;
import com.paicli.rag.SearchResultFormatter;
import com.paicli.runtime.CancellationContext;
import com.paicli.runtime.CancellationToken;
import com.paicli.runtime.RootTaskScope;
import com.paicli.runtime.RootTaskLlmClient;
import com.paicli.observability.LangfuseTelemetry;
import com.paicli.runtime.api.RuntimeApiServer;
import com.paicli.runtime.api.RuntimeThreadStore;
import com.paicli.runtime.task.DurableTaskManager;
import com.paicli.runtime.task.TaskCommandFormatter;
import com.paicli.snapshot.RestoreResult;
import com.paicli.snapshot.SnapshotService;
import com.paicli.snapshot.TurnSnapshot;
import com.paicli.skill.SkillRegistry;
import com.paicli.tool.ToolRegistry;
import com.paicli.util.AnsiStyle;
import com.paicli.util.TerminalInputLock;
import com.paicli.util.TerminalTable;
import com.paicli.util.TerminalMarkdownRenderer;
import com.paicli.wechat.IlinkClient;
import com.paicli.wechat.WechatAccount;
import com.paicli.wechat.WechatAccountStore;
import com.paicli.wechat.WechatCommandMain;
import com.paicli.wechat.WechatLoginResult;
import com.paicli.wechat.WechatMessageLoop;
import com.paicli.wechat.WechatQrLogin;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.Attributes;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.MaskingCallback;
import org.jline.reader.EndOfFileException;
import org.jline.reader.History;
import org.jline.reader.UserInterruptException;
import org.jline.reader.Reference;
import org.jline.utils.NonBlockingReader;
import org.jline.utils.AttributedString;
import org.jline.widget.AutosuggestionWidgets;
import org.jline.widget.AutopairWidgets;
import org.jline.console.CmdDesc;
import org.jline.keymap.KeyMap;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * PaiCLI v16.1.0 - Terminal-First Agent IDE
 * 支持 ReAct、Plan-and-Execute、Memory、RAG、Multi-Agent、HITL、并行工具调用、多模型切换、MCP、CDP 会话复用
 * 第 15 期新增：Skill 系统（三层加载 + load_skill 工具 + 同轮正文注入）、内置 web-access skill
 * 第 16 期新增：TUI 界面（Lanterna 3）、文件树浏览、代码高亮、对话历史可视化、配置管理面板
 * 第 16.1 期形态修正：抽出 Renderer 接口 + 三个实现（inline/lanterna/plain），默认形态切换为 inline 流式 TUI（Claude Code 风格）
 *   - inline 流式：prompt 下方 inline 状态区、行内可折叠工具块、行内 git diff、单字符 HITL 提示、命令 palette
 *   - lanterna：保留 phase-16 全屏窗口（向后兼容 PAICLI_TUI=true）
 *   - plain：纯 println 兜底
 * HITL 增强：路径围栏（PathGuard）、命令快速拒绝（CommandGuard）、操作审计链（AuditLog）—— 见 com.paicli.policy
 */
public class Main {
    private static final String VERSION = "16.1.0";
    private static final String ENV_FILE = ".env";
    private static final String LOG_DIR_PROPERTY = "paicli.log.dir";
    private static final String LOG_LEVEL_PROPERTY = "paicli.log.level";
    private static final String LOG_MAX_HISTORY_PROPERTY = "paicli.log.maxHistory";
    private static final String LOG_MAX_FILE_SIZE_PROPERTY = "paicli.log.maxFileSize";
    private static final String LOG_TOTAL_SIZE_CAP_PROPERTY = "paicli.log.totalSizeCap";
    private static final String HISTORY_FILE_PROPERTY = "paicli.history.file";
    private static final String HISTORY_SIZE_PROPERTY = "paicli.history.size";
    private static final String HISTORY_FILE_SIZE_PROPERTY = "paicli.history.fileSize";
    private static final String DEFAULT_HISTORY_FILE_NAME = "input.history";
    private static final String BRACKETED_PASTE_BEGIN = "[200~";
    private static final String BRACKETED_PASTE_END = "\u001b[201~";
    private static final String ARROW_UP = "[A";
    private static final String ARROW_DOWN = "[B";
    private static final String APP_ARROW_UP = "OA";
    private static final String APP_ARROW_DOWN = "OB";
    private static final Pattern SENSITIVE_FLAG_VALUE = Pattern.compile(
            "(?i)(--?(?:api[_-]?key|authorization|password|passwd|secret|token)\\s+)(\\S+)");
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)((?:api[_-]?key|authorization|password|passwd|secret|token)\\s*[=:]\\s*)(\\S+)");
    private static final int CTRL_O = 15;
    private static final String DEFAULT_CHROME_DEVTOOLS_MCP_JSON = """
            {
              "mcpServers": {
                "chrome-devtools": {
                  "command": "npx",
                  "args": ["-y", "chrome-devtools-mcp@latest", "--isolated=true"]
                }
              }
            }
            """;

    enum EscapeSequenceType {
        STANDALONE_ESC,
        BRACKETED_PASTE,
        CONTROL_SEQUENCE,
        OTHER
    }

    private record PromptInput(String text, boolean canceled) {
        static PromptInput submitted(String text) {
            return new PromptInput(text, false);
        }

        static PromptInput canceledInput() {
            return new PromptInput("", true);
        }
    }

    private record PrefillResult(String seedBuffer, boolean canceled, boolean submitted) {
        static PrefillResult canceledInput() {
            return new PrefillResult("", true, false);
        }

        static PrefillResult submittedInput() {
            return new PrefillResult("", false, true);
        }

        static PrefillResult seed(String seedBuffer) {
            return new PrefillResult(seedBuffer, false, false);
        }
    }

    private record KeyReadResult(Integer key, boolean ignoredControlSequence, String pastedText) {
        static KeyReadResult keyPressed(int key) {
            return new KeyReadResult(key, false, null);
        }

        static KeyReadResult ignoredSequence() {
            return new KeyReadResult(null, true, null);
        }

        static KeyReadResult unavailable() {
            return new KeyReadResult(null, false, null);
        }

        static KeyReadResult pasted(String text) {
            return new KeyReadResult(null, false, text);
        }
    }

    private record StartupScreenInfo(
            String model,
            String provider,
            long mcpReady,
            int mcpTotal,
            int mcpTools,
            int skillsEnabled,
            int skillsTotal,
            String note
    ) {
    }

    public static void main(String[] args) {
        configureAwtForCli();
        if (WechatCommandMain.isWechatCommand(args)) {
            configureLogging();
            int code = WechatCommandMain.run(args);
            if (code != 0) {
                System.exit(code);
            }
            return;
        }
        if (isRuntimeServeCommand(args)) {
            configureLogging();
            startRuntimeApiAndBlock(args);
            return;
        }

        configureLogging();

        PaiCliConfig config = PaiCliConfig.load();
        LlmClient llmClient = LlmClientFactory.createFromConfig(config);
        if (llmClient == null) {
            System.err.println("❌ 错误: 未找到可用的 API Key");
            System.err.println("请在 .env 文件中添加 DEEPSEEK_API_KEY、GLM_API_KEY、HUNYUAN_API_KEY、STEP_API_KEY、KIMI_API_KEY、FREELLMAPI_API_KEY、XFYUN_MAAS_API_KEY 或 AGNES_API_KEY");
            System.exit(1);
        }
        llmClient = RootTaskLlmClient.wrap(llmClient);
        AtomicReference<LlmClient> llmClientRef = new AtomicReference<>(llmClient);

        try (Terminal terminal = TerminalBuilder.builder().system(true).dumb(true).build()) {
            refreshTerminalColumns(terminal);
            TerminalHitlHandler terminalHitlHandler = new TerminalHitlHandler(false);
            SwitchableHitlHandler hitlHandler = new SwitchableHitlHandler(terminalHitlHandler);
            // 交互式 CLI 默认确认高危操作（执行命令、回滚快照、MCP 工具），/hitl off 可全部关闭
            hitlHandler.setHighRiskConfirmationEnabled(true);
            SessionModeController sessionModes = new SessionModeController(hitlHandler);
            HitlToolRegistry hitlToolRegistry = new HitlToolRegistry(hitlHandler);
            // auto 模式的 Shell 命令审查：跟随当前会话的供应商，关闭思考的轻量请求；切换模型后自动重建
            com.paicli.hitl.LlmApprovalClassifier approvalClassifier = new com.paicli.hitl.LlmApprovalClassifier(
                    approvalClassifierClient(llmClientRef, config));
            hitlToolRegistry.setAutoApprovalReviewer(approvalClassifier);
            BrowserSession browserSession = new BrowserSession();
            BrowserConnectivityCheck browserConnectivityCheck = new BrowserConnectivityCheck();
            hitlToolRegistry.setBrowserGuard(new BrowserGuard(browserSession, new SensitivePagePolicy()));
            McpServerManager mcpServerManager = new McpServerManager(hitlToolRegistry, Path.of("."));
            AtomicReference<SkillRegistry> skillRegistryRef = new AtomicReference<>();
            hitlToolRegistry.setBrowserConnector(new com.paicli.browser.BrowserConnector() {
                @Override
                public String status() {
                    return handleBrowserCommand("status", browserSession, browserConnectivityCheck,
                            mcpServerManager, hitlToolRegistry, hitlHandler);
                }

                @Override
                public String connectDefault() {
                    return handleBrowserCommand("connect", browserSession, browserConnectivityCheck,
                            mcpServerManager, hitlToolRegistry, hitlHandler);
                }

                @Override
                public String disconnect() {
                    return handleBrowserCommand("disconnect", browserSession, browserConnectivityCheck,
                            mcpServerManager, hitlToolRegistry, hitlHandler);
                }
            });

            LineReader lineReader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .history(new PaiCliHistory())
                    .completer(new PaiCliCompleter(mcpServerManager::resourceCandidates,
                            () -> skillRegistryRef.get() == null ? List.of() : skillRegistryRef.get().allSkills(), () -> config))
                    .highlighter(new PaiCliHighlighter())
                    .build();
            lineReader.option(LineReader.Option.BRACKETED_PASTE, true);
            lineReader.option(LineReader.Option.AUTO_LIST, true);
            lineReader.option(LineReader.Option.AUTO_MENU, true);
            configureHistory(lineReader, Path.of(System.getProperty("user.home")));
            configureSlashCommandHint(lineReader);
            configureJLineInteractiveWidgets(lineReader);

            // JLine-first：启动输出、命令输出、Agent 流式内容都走同一条 Renderer.stream() 通道。
            // inline 首屏要挂到 LineReader 首次初始化回调里，避免在 readLine 接管屏幕前用裸输出抢光标。
            Renderer renderer = RendererFactory.create(RendererFactory.resolveMode(), terminal);
            RendererHitlHandler rendererHitl = new RendererHitlHandler(renderer, hitlHandler.isEnabled());
            hitlHandler.setDelegate(rendererHitl);
            if (renderer instanceof InlineRenderer inline) {
                inline.bindLineReader(lineReader);
            }
            PrintStream ui = renderer.stream();
            renderer.start();
            renderer.updateStatus(statusInfo(llmClient, hitlHandler, "idle", mcpServerManager, null)
                    .withSessionMode(sessionModes.current().statusLabel()));

            String startupNote = "";
            try {
                McpConfigBootstrapResult bootstrapResult = ensureDefaultMcpConfig(Path.of(System.getProperty("user.home")));
                if (!bootstrapResult.message().isBlank()) {
                    startupNote = bootstrapResult.message();
                }
                mcpServerManager.loadConfiguredServers();
                mcpServerManager.startAll(ui, mcpStartupWait());
                startupNote = appendStartupNote(startupNote, mcpServerManager.untrustedStartupNote());
                Runtime.getRuntime().addShutdownHook(new Thread(mcpServerManager::close, "paicli-mcp-shutdown"));
            } catch (Exception e) {
                startupNote = "MCP 初始化失败: " + e.getMessage();
            }
            AtMentionExpander mentionExpander = new AtMentionExpander(mcpServerManager);
            LocalPathMentionExpander localPathMentionExpander = new LocalPathMentionExpander(Path.of("."));

            // === Skill 系统初始化 ===
            Path home = Path.of(System.getProperty("user.home"));
            Path skillsCacheDir = home.resolve(".paicli/skills-cache");
            Path userSkillsDir = home.resolve(".paicli/skills");
            Path projectSkillsDir = Path.of(".paicli/skills").toAbsolutePath();
            try {
                new com.paicli.skill.SkillBuiltinExtractor(skillsCacheDir).extractAll();
            } catch (Exception e) {
                startupNote = appendStartupNote(startupNote, "内置 skill 解压失败: " + e.getMessage());
            }
            com.paicli.skill.SkillStateStore skillStateStore = new com.paicli.skill.SkillStateStore(home.resolve(".paicli/skills.json"));
            com.paicli.skill.SkillRegistry skillRegistry = new com.paicli.skill.SkillRegistry(
                    skillsCacheDir, userSkillsDir, projectSkillsDir, skillStateStore);
            skillRegistry.reload();
            skillRegistryRef.set(skillRegistry);
            hitlToolRegistry.setSkillRegistry(skillRegistry);

            ConversationLedger conversationLedger;
            try {
                conversationLedger = ConversationLedger.openDefault(home);
            } catch (IOException e) {
                conversationLedger = ConversationLedger.disabled();
                startupNote = appendStartupNote(
                        startupNote,
                        "会话记录文件初始化失败（本次会话不会被保存，/resume 也找不回）: " + e.getMessage());
            }

            // execute_command 沙箱：默认 OFF 与旧行为一致；auto/required 时探测 Seatbelt / bubblewrap 并提示结果。
            com.paicli.tool.CommandSandboxStatus sandboxStatus = hitlToolRegistry.configureCommandSandbox(
                    com.paicli.tool.CommandSandboxMode.fromConfiguration(),
                    Path.of(hitlToolRegistry.getProjectPath()));
            startupNote = appendStartupNote(startupNote, sandboxStatus.message());

            Agent reactAgent = new Agent(llmClient, hitlToolRegistry);
            reactAgent.getMemoryManager().setAutoFactExtractionEnabled(
                    com.paicli.memory.AutoFactExtractor.enabledByConfiguration());
            reactAgent.setConversationLedger(conversationLedger);
            com.paicli.history.SessionResume.recordSessionStart(
                    conversationLedger, reactAgent.getToolRegistry().getProjectPath());
            reactAgent.setExternalContextSupplier(mcpServerManager::resourceIndexForPrompt);
            reactAgent.setSkillRegistry(skillRegistry);
            DurableTaskManager taskManager = openTaskManager(llmClientRef);//创建管理器对象
            taskManager.start();
            Runtime.getRuntime().addShutdownHook(new Thread(taskManager::close, "paicli-task-shutdown"));
            WechatRuntimeController wechatRuntime = new WechatRuntimeController(renderer);
            Runtime.getRuntime().addShutdownHook(new Thread(wechatRuntime::stop, "paicli-wechat-shutdown"));
            renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
            StartupScreenInfo startupScreenInfo = startupScreenInfo(llmClient, mcpServerManager, skillRegistry, startupNote);
            if (renderer instanceof InlineRenderer inline) {
                inline.installStartupScreen(startupScreenLines(startupScreenInfo), 3);
            } else {
                printStartupScreen(ui, startupScreenInfo);
            }
            boolean nextTaskUsePlanMode = false;
            boolean nextTaskUseTeamMode = false;

            // === TUI / CLI 分支判断 ===
            // 旧 PAICLI_TUI=true 路径仍走 Lanterna 全屏 TUI（Day 5 后由 LanternaRenderer 接管）。
            if (com.paicli.tui.TuiBootstrap.shouldUseTui(terminal)) {
                try {
                    com.paicli.tui.TuiBootstrap.launch(config, llmClient, reactAgent, hitlHandler);
                    return;  // TUI 启动成功，不进入 CLI 循环
                } catch (Exception e) {
                    hitlHandler.setDelegate(terminalHitlHandler);
                    System.err.println("❌ TUI 启动失败，降级到 CLI: " + e.getMessage());
                    e.printStackTrace();
                    // 降级到 CLI 继续执行
                }
            }

            reactAgent.setRenderer(renderer);
            reactAgent.setHitlEnabledSupplier(hitlHandler::isConfirmationActive);
            reactAgent.setSessionModeSupplier(() -> sessionModes.current().statusLabel());
            bindSessionModeCycle(lineReader, () -> {
                SessionMode mode = sessionModes.cycle();
                renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistryRef.get(), "idle"));
                if (!(renderer instanceof InlineRenderer inline && inline.hasStatusBar())) {
                    // 没有底部状态栏时，在提示符上方打印一行，否则切换没有任何可见反馈
                    lineReader.printAbove("模式：" + SessionModeController.describe(mode));
                }
            });
            reactAgent.getToolRegistry().setWriteFileObserver(
                    (path, ba) -> renderer.appendDiff(path, ba[0], ba[1]));

            // Day 3：inline 模式绑 Ctrl+O 到 BlockRegistry.toggleLast 实现折叠块展开/收起
            boolean spaciousPrompt = false;
            if (renderer instanceof InlineRenderer inline) {
                bindCtrlOToFoldableBlocks(lineReader, inline);
            }
            spaciousPrompt = defaultSpaciousPrompt(spaciousPrompt);
            bindCtrlVToClipboardImage(lineReader);
            bindEscToClearInput(lineReader);

            while (true) {
                refreshTerminalColumns(terminal);
                List<String> mcpNotices = mcpServerManager.drainNotices();
                if (!mcpNotices.isEmpty()) {
                    mcpNotices.forEach(ui::println);
                    ui.println();
                    renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistryRef.get(), "idle"));
                }
                PromptInput promptInput;
                try {
                    promptInput = readPromptInput(terminal, lineReader, renderer,
                            nextTaskUsePlanMode || nextTaskUseTeamMode, spaciousPrompt);
                } catch (UserInterruptException e) {
                    continue;  // Ctrl+C 跳过
                } catch (EndOfFileException e) {
                    break;  // Ctrl+D 退出
                }
                if (renderer instanceof InlineRenderer inline) {
                    inline.clearAcceptedInput(promptInput.text());
                }

                if (promptInput.canceled()) {
                    if (nextTaskUsePlanMode) {
                        nextTaskUsePlanMode = false;
                        ui.println("↩️ 已取消待执行的 Plan-and-Execute，" + returnModeHint(sessionModes, "回到") + "。\n");
                    }
                    if (nextTaskUseTeamMode) {
                        nextTaskUseTeamMode = false;
                        ui.println("↩️ 已取消待执行的 Multi-Agent，" + returnModeHint(sessionModes, "回到") + "。\n");
                    }
                    continue;
                }

                String input = promptInput.text().trim();

                if (input.isEmpty()) {
                    continue;
                }

                CliCommandParser.ParsedCommand command = CliCommandParser.parse(input);
                boolean submittedInputRendered = false;
                if (command.type() != CliCommandParser.CommandType.NONE) {
                    renderer.beginTurn();
                    printSubmittedInput(renderer, ui, input);
                    submittedInputRendered = true;
                }
                switch (command.type()) {
                    case UNKNOWN_COMMAND -> {
                        ui.println("❌ 未知命令: " + command.payload());
                        printSlashCommandHelp(ui);
                        continue;
                    }
                    case EXIT -> {
                        ui.println("\n👋 再见!");
                        wechatRuntime.stop();
                        renderer.close();
                        return;
                    }
                    case CANCEL -> {
                        ui.println("当前没有正在运行的任务。\n");
                        continue;
                    }
                    case CLEAR -> {
                        reactAgent.clearHistory();
                        hitlHandler.clearApprovedAll();
                        renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
                        ui.println("🗑️ 当前对话历史已清空，长期记忆保持不变\n");
                        continue;
                    }
                    case COMPACT -> {
                        renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "compacting"));
                        boolean activityPanel = renderer.supportsActivityPanel();
                        if (activityPanel) {
                            renderer.beginActivity("Compacting conversation", "正在整理早期对话并生成摘要");
                        } else {
                            ui.println("⏳ 压缩中，等一下下哦...\n");
                        }
                        Agent.CompactionResult result;
                        try {
                            result = reactAgent.compactHistoryNow();
                        } finally {
                            if (activityPanel) {
                                renderer.endActivity();
                            }
                            renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
                        }
                        if (result.error() != null && !result.error().isBlank()) {
                            ui.println("❌ 手动压缩失败: " + result.error() + "\n");
                        } else if (result.compacted()) {
                            ui.printf("📦 已手动压缩历史上下文: %,d -> %,d tokens%n%n",
                                    result.beforeTokens(), result.afterTokens());
                        } else {
                            ui.println("📭 当前没有需要压缩的历史上下文\n");
                        }
                        continue;
                    }
                    case HISTORY_CLEAR -> {
                        clearLineReaderHistory(lineReader);
                        ui.println("🧹 输入历史已清空\n");
                        continue;
                    }
                    case INIT_PROJECT_MEMORY -> {
                        String payload = command.payload();
                        boolean force = payload != null && payload.trim().equalsIgnoreCase("--force");
                        if (payload != null && !payload.isBlank() && !force) {
                            ui.println("❌ 未知 /init 参数: " + payload);
                            ui.println("   用法: /init 或 /init --force\n");
                            continue;
                        }
                        try {
                            ProjectMemoryInitializer.InitResult result = ProjectMemoryInitializer.initialize(
                                    Path.of(reactAgent.getToolRegistry().getProjectPath()), force);
                            if (result.written()) {
                                ui.println("✅ " + result.message());
                                ui.println("   路径: " + result.path());
                                ui.println("   这份 PAI.md 会在后续 system prompt 的 Project Context 中注入。\n");
                            } else {
                                ui.println("ℹ️ " + result.message());
                                ui.println("   路径: " + result.path() + "\n");
                            }
                        } catch (IOException e) {
                            ui.println("❌ 生成 PAI.md 失败: " + e.getMessage() + "\n");
                        }
                        continue;
                    }
                    case CONTEXT_STATUS -> {
                        ui.println("📋 上下文状态：");
                        ui.println(reactAgent.getContextStatus());
                        ui.println();
                        continue;
                    }
                    case MEMORY_STATUS -> {
                        ui.println("📋 记忆系统状态：");
                        ui.println(reactAgent.getMemoryManager().getSystemStatus());
                        ui.println("   当前项目作用域: " + reactAgent.getMemoryManager().getCurrentProject());
                        ui.println("   /memory list - 查看长期记忆");
                        ui.println("   /memory search <关键词> - 搜索当前项目可见长期记忆");
                        ui.println("   /memory delete <id> - 删除单条长期记忆");
                        ui.println("   /memory verify <id> - 确认记忆仍然成立，刷新最后核实时间");
                        ui.println("   /memory replace <id> <新事实> - 用新内容替换冲突/过时的记忆");
                        ui.println("   /memory clear - 清空长期记忆");
                        ui.println("   /save <事实> - 保存项目级长期记忆；/save --global <事实> 保存全局记忆");
                        ui.println("   /save --force <事实> - 与已有记忆冲突时仍保留两条");
                        ui.println();
                        continue;
                    }
                    case MEMORY_LIST -> {
                        List<MemoryEntry> entries = reactAgent.getMemoryManager().listLongTerm();
                        ui.println(formatMemoryEntries("📋 长期记忆列表", entries, reactAgent.getMemoryManager()));
                        ui.println();
                        continue;
                    }
                    case MEMORY_SEARCH -> {
                        String query = command.payload();
                        if (query == null || query.isBlank()) {
                            ui.println("❌ 请提供搜索关键词，例如 /memory search Chrome 登录态\n");
                        } else {
                            List<MemoryEntry> entries = reactAgent.getMemoryManager().searchLongTerm(query, 20);
                            ui.println(formatMemoryEntries("🔎 长期记忆搜索: " + query, entries,
                                    reactAgent.getMemoryManager()));
                            ui.println();
                        }
                        continue;
                    }
                    case MEMORY_DELETE -> {
                        String id = command.payload();
                        if (id == null || id.isBlank()) {
                            ui.println("❌ 请提供要删除的记忆 id，例如 /memory delete fact-abcd1234\n");
                        } else if (reactAgent.getMemoryManager().deleteLongTerm(id)) {
                            ui.println("🗑️ 已删除长期记忆: " + id + "\n");
                        } else {
                            ui.println("📭 未找到长期记忆: " + id + "\n");
                        }
                        continue;
                    }
                    case MEMORY_CLEAR -> {
                        reactAgent.getMemoryManager().clearLongTerm();
                        ui.println("🧹 长期记忆已清空\n");
                        ui.println();
                        continue;
                    }
                    case MEMORY_SAVE -> {
                        MemoryCommandArgs.SaveRequest saveRequest = MemoryCommandArgs.parseSave(command.payload());
                        if (saveRequest.fact().isEmpty()) {
                            ui.println("❌ 请提供要保存的内容，例如 /save 这个项目使用Java 17，或 /save --global 默认用中文回答\n");
                        } else {
                            MemoryWriteResult result = reactAgent.getMemoryManager().storeFact(
                                    saveRequest.fact(), saveRequest.scope(), null, saveRequest.force());
                            ui.println(result.describe() + "\n");
                        }
                        continue;
                    }
                    case MEMORY_VERIFY -> {
                        String id = command.payload();
                        if (id == null || id.isBlank()) {
                            ui.println("❌ 请提供要核实的记忆 id，例如 /memory verify fact-abcd1234\n");
                        } else {
                            ui.println(reactAgent.getMemoryManager().verifyLongTerm(id)
                                    .map(entry -> "✅ 已刷新核实时间: " + entry.getId() + " " + entry.getContent())
                                    .orElse("📭 未找到长期记忆: " + id) + "\n");
                        }
                        continue;
                    }
                    case MEMORY_REPLACE -> {
                        MemoryCommandArgs.ReplaceRequest replaceRequest = MemoryCommandArgs.parseReplace(command.payload());
                        if (!replaceRequest.valid()) {
                            ui.println("❌ 用法: /memory replace <旧记忆 id> <新事实>，例如 /memory replace fact-abcd1234 项目使用 Java 21\n");
                        } else {
                            ui.println(reactAgent.getMemoryManager()
                                    .replaceFact(replaceRequest.id(), replaceRequest.fact()).describe() + "\n");
                        }
                        continue;
                    }
                    case SWITCH_PLAN -> {
                        if (command.payload() == null || command.payload().isEmpty()) {
                            if (sessionModes.planMode()) {
                                ui.println("📋 当前已是 plan 模式，每条任务都会先规划再执行；Shift+Tab 或 /mode 可切换。\n");
                                continue;
                            }
                            nextTaskUsePlanMode = true;
                            nextTaskUseTeamMode = false;
                            ui.println("📋 下一条任务将使用 Plan-and-Execute 模式，输入任务前按 ESC 可取消，"
                                    + returnModeHint(sessionModes, "执行完成后回到") + "。\n");
                            continue;
                        }
                        input = command.payload();
                    }
                    case SWITCH_TEAM -> {
                        if (command.payload() == null || command.payload().isEmpty()) {
                            nextTaskUseTeamMode = true;
                            nextTaskUsePlanMode = false;
                            ui.println("👥 下一条任务将使用 Multi-Agent 协作模式（规划者 + 执行者 + 检查者），输入任务前按 ESC 可取消，"
                                    + returnModeHint(sessionModes, "执行完成后回到") + "。\n");
                            continue;
                        }
                        input = command.payload();
                    }
                    case MODEL_MANAGE -> {
                        try {
                            ui.println(ModelCommands.handle(command.payload(), config, llmClient));
                        } catch (IOException | IllegalArgumentException e) {
                            ui.println("❌ " + e.getMessage());
                        }
                        continue;
                    }
                    case SWITCH_MODEL -> {
                        String selection = command.payload();
                        if (selection == null || selection.isEmpty()) {
                            ui.println("🤖 当前模型: " + ModelCatalog.displayName(llmClient.getProviderName(), llmClient.getModelName()) + " (" + llmClient.getProviderName() + ")");
                            ui.println("   管理模型: /model list / /model info / /model refresh <provider> / /model add");
                            ui.println("   明确模型：");
                            ui.println("   /model deepseek-flash - 切换到 DeepSeek V4.1 Flash（默认，支持图片）");
                            ui.println("   /model glm-5.3-flash - 切换到 GLM-5.3-Flash");
                            ui.println("   /model glm-5.1       - 切换到 GLM-5.1");
                            ui.println("   /model glm-5v-turbo  - 切换到 GLM-5V-Turbo 多模态");
                            ui.println("   其它 provider 使用你配置里的具体模型：");
                            ui.println("   /model deepseek      - 切换到 DeepSeek（读取配置模型）");
                            ui.println("   /model hunyuan       - 切换到混元（读取配置模型）");
                            ui.println("   /model hy4-preview   - 切换到混元 Hy4 preview");
                            ui.println("   /model step          - 切换到 StepFun（读取配置模型）");
                            ui.println("   /model kimi          - 切换到 Kimi（读取配置模型）");
                            ui.println("   /model freellmapi    - 切换到本地 FreeLLMAPI（读取配置模型）");
                            ui.println("   /model xfyun         - 切换到讯飞星辰 MaaS（读取配置模型）");
                            ui.println("   /model agnes         - 切换到 Agnes 2.0 Flash（读取配置模型）\n");
                        } else {
                            try {
                                ModelSelection target = resolveModelSelection(selection, config);
                                LlmClient newClient = RootTaskLlmClient.wrap(ModelCommands.switchModel(config, target));
                                llmClient = newClient;
                                llmClientRef.set(newClient);
                                reactAgent.setLlmClient(llmClient);
                                ui.println(ModelCommands.switchSummary(llmClient));
                                ui.println();
                                renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
                            } catch (IOException | IllegalArgumentException e) {
                                ui.println("❌ 切换失败: " + e.getMessage());
                            }
                        }
                        continue;
                    }
                    case SESSION_MODE -> {
                        ui.println("🧭 " + sessionModes.handleCommand(command.payload()) + "\n");
                        renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
                        continue;
                    }
                    case SWITCH_HITL -> {
                        String payload = command.payload();
                        ui.println("🔒 " + hitlHandler.switchConfirmationMode(payload == null ? "" : payload) + "\n");
                        renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
                        continue;
                    }
                    case POLICY_STATUS -> {
                        printPolicyStatus(ui, reactAgent);
                        continue;
                    }
                    case CONFIG -> {
                        if (command.payload() == null || command.payload().isBlank()) {
                            handleConfigPalette(renderer, config, llmClient, hitlHandler, skillRegistry);
                        } else {
                            ui.println(handleConfigCommand(config, command.payload()));
                            renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
                        }
                        continue;
                    }
                    case AUDIT_TAIL -> {
                        printAuditTail(ui, reactAgent, command.payload());
                        continue;
                    }
                    case SNAPSHOT -> {
                        printSnapshotCommand(ui, reactAgent.getToolRegistry().getSnapshotService(), command.payload());
                        continue;
                    }
                    case RESTORE_SNAPSHOT -> {
                        printRestoreCommand(ui, reactAgent.getToolRegistry().getSnapshotService(), command.payload());
                        continue;
                    }
                    case MCP_LIST -> {
                        ui.println(mcpServerManager.formatStatus());
                        ui.println();
                        continue;
                    }
                    case MCP_RESTART -> {
                        printMcpCommandResult(ui, mcpServerManager.restart(command.payload()));
                        renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
                        continue;
                    }
                    case MCP_LOGS -> {
                        printMcpCommandResult(ui, mcpServerManager.logs(command.payload()));
                        continue;
                    }
                    case MCP_DISABLE -> {
                        printMcpCommandResult(ui, mcpServerManager.disable(command.payload()));
                        renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
                        continue;
                    }
                    case MCP_ENABLE -> {
                        printMcpCommandResult(ui, mcpServerManager.enable(command.payload()));
                        renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
                        continue;
                    }
                    case MCP_TRUST -> {
                        printMcpCommandResult(ui, mcpServerManager.trust(command.payload()));
                        renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
                        continue;
                    }
                    case MCP_RESOURCES -> {
                        printMcpCommandResult(ui, mcpServerManager.resources(command.payload()));
                        continue;
                    }
                    case MCP_PROMPTS -> {
                        printMcpCommandResult(ui, mcpServerManager.prompts(command.payload()));
                        continue;
                    }
                    case BROWSER -> {
                        printMcpCommandResult(ui, handleBrowserCommand(
                                command.payload(),
                                browserSession,
                                browserConnectivityCheck,
                                mcpServerManager,
                                hitlToolRegistry,
                                hitlHandler));
                        continue;
                    }
                    case WECHAT -> {
                        ui.println(handleWechatCommand(command.payload(), lineReader, renderer, ui, wechatRuntime));
                        continue;
                    }
                    case TASK -> {
                        printMcpCommandResult(ui, TaskCommandFormatter.handle(taskManager, command.payload()));
                        continue;
                    }
                    case SKILL_LIST -> {
                        ui.println(SkillCommandHandler.list(skillRegistry));
                        continue;
                    }
                    case SKILL_SHOW -> {
                        ui.println(SkillCommandHandler.show(skillRegistry, command.payload()));
                        continue;
                    }
                    case SKILL_ON -> {
                        ui.println(SkillCommandHandler.enable(skillRegistry, skillStateStore, command.payload()));
                        renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
                        continue;
                    }
                    case SKILL_OFF -> {
                        ui.println(SkillCommandHandler.disable(skillRegistry, skillStateStore, command.payload()));
                        renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
                        continue;
                    }
                    case SKILL_TRUST -> {
                        ui.println(SkillCommandHandler.trust(skillRegistry, command.payload()));
                        continue;
                    }
                    case SKILL_RELOAD -> {
                        skillRegistry.reload();
                        ui.println("🔄 已重新扫描 skill 目录");
                        ui.println(SkillCommandHandler.startupSummary(skillRegistry));
                        ui.println("✅ 下一轮 LLM 调用生效");
                        renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
                        continue;
                    }
                    case BETTER_HARNESS -> {
                        BetterHarnessOptions.ParseResult parsed =
                                BetterHarnessOptions.parse(command.payload());
                        if (!parsed.valid()) {
                            ui.println("❌ " + parsed.error() + "\n");
                            continue;
                        }
                        ui.println("🔎 Better Harness 开始审查当前项目\n");
                        renderer.updateStatus(
                                statusInfo(reactAgent, mcpServerManager, skillRegistry, "harness"));
                        boolean activityPanel = renderer.supportsActivityPanel();
                        if (activityPanel) {
                            renderer.beginActivity(
                                    "Better Harness",
                                    "正在准备审计 · 按 ESC 可取消",
                                    true);
                        } else {
                            ui.println("⏳ 0/5 · 正在准备审计 · 按 ESC 可取消");
                        }
                        AtomicReference<BetterHarnessRunner.RunResult> resultRef =
                                new AtomicReference<>();
                        String runStatus;
                        try {
                            BetterHarnessRunner runner = new BetterHarnessRunner(
                                    llmClient,
                                    Path.of(reactAgent.getToolRegistry().getProjectPath()),
                                    reactAgent.getConversationLedger(),
                                    skillRegistry);
                            runStatus = runWithCancelSupport(
                                    terminal,
                                    ui,
                                    () -> {
                                        resultRef.set(runner.run(
                                                parsed.options(),
                                                event -> {
                                                    String progress =
                                                            formatBetterHarnessProgress(event);
                                                    if (activityPanel) {
                                                        renderer.updateActivity(
                                                                event.message(),
                                                                event.completed(),
                                                                event.total());
                                                    } else {
                                                        ui.println("⏳ " + progress);
                                                    }
                                                }));
                                        return "";
                                    });
                        } finally {
                            if (activityPanel) {
                                renderer.endActivity();
                            }
                            renderer.updateStatus(
                                    statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
                        }
                        BetterHarnessRunner.RunResult result = resultRef.get();
                        if (result == null) {
                            ui.println(runStatus == null || runStatus.isBlank()
                                    ? "❌ Better Harness 未生成报告\n"
                                    : runStatus + "\n");
                            continue;
                        }
                        ui.print(renderBetterHarnessMarkdown(
                                result.reportMarkdown(),
                                renderer.terminalColumns()));
                        ui.println();
                        if (result.durable()) {
                            ui.println("✅ " + result.findingCount() + " 个 findings");
                            ui.println("   Markdown: " + result.reportMarkdownPath());
                            ui.println("   HTML: " + result.reportHtmlPath());
                            ui.println("   JSON: " + result.findingsJsonPath());
                            ui.println();
                        }
                        continue;
                    }
                    case EXPORT -> {
                        handleExportCommand(ui, reactAgent);
                        continue;
                    }
                    case RESUME -> {
                        ResumeCommandHandler.Result result =
                                ResumeCommandHandler.handle(reactAgent, command.payload());
                        ui.println(result.output());
                        if (result.restored()) {
                            hitlHandler.clearApprovedAll();
                        }
                        renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
                        continue;
                    }
                    case INDEX_CODE -> {
                        String indexPath = command.payload() != null ? command.payload() : ".";
                        CodeIndex indexer = new CodeIndex(ui::println);
                        indexer.index(indexPath);
                        ui.println();

                        // 同步项目路径到 ToolRegistry，让 search_code 工具可以正常工作
                        String absPath = new File(indexPath).getAbsolutePath();
                        reactAgent.getToolRegistry().setProjectPath(absPath);
                        reactAgent.getMemoryManager().setProjectPath(absPath);
                        continue;
                    }
                    case SEARCH_CODE -> {
                        String query = command.payload();
                        if (query == null || query.isEmpty()) {
                            ui.println("❌ 请提供检索关键词，例如 /search 用户登录实现\n");
                            continue;
                        }
                        ui.println("🔍 检索: " + query);
                        try (CodeRetriever retriever = new CodeRetriever(".")) {
                            var stats = retriever.getStats();
                            if (stats.chunkCount() == 0) {
                                ui.println("⚠️ 代码库尚未索引，请先使用 /index 命令\n");
                                continue;
                            }
                            List<com.paicli.rag.VectorStore.SearchResult> results = retriever.hybridSearch(query, 5);
                            if (results.isEmpty()) {
                                ui.println("📭 未找到相关代码\n");
                            } else {
                                ui.println(SearchResultFormatter.formatForCli(query, results) + "\n");
                            }
                        } catch (Exception e) {
                            ui.println("❌ 检索失败: " + e.getMessage() + "\n");
                        }
                        continue;
                    }
                    case GRAPH_QUERY -> {
                        String className = command.payload();
                        if (className == null || className.isEmpty()) {
                            ui.println("❌ 请提供类名，例如 /graph Main\n");
                            continue;
                        }
                        ui.println("🕸️ 查询类关系图谱: " + className);
                        try (CodeRetriever retriever = new CodeRetriever(".")) {
                            var stats = retriever.getStats();
                            if (stats.chunkCount() == 0) {
                                ui.println("⚠️ 代码库尚未索引，请先使用 /index 命令\n");
                                continue;
                            }
                            List<CodeRelation> relations = retriever.getRelationGraph(className);
                            if (relations.isEmpty()) {
                                ui.println("📭 未找到相关关系\n");
                            } else {
                                ui.println("📋 找到 " + relations.size() + " 条关系:\n");
                                for (CodeRelation rel : relations) {
                                    String arrow = rel.relationType().equals("contains") ? "├── contains -->"
                                            : rel.relationType().equals("extends") ? "└── extends -->"
                                            : rel.relationType().equals("implements") ? "└── implements -->"
                                            : rel.relationType().equals("calls") ? "├── calls -->"
                                            : "├── " + rel.relationType() + " -->";
                                    ui.printf("   %s %s [%s]%n", rel.fromName(), arrow,
                                            rel.toName() != null ? rel.toName() : "unknown");
                                }
                                ui.println();
                            }
                        } catch (Exception e) {
                            ui.println("❌ 查询失败: " + e.getMessage() + "\n");
                        }
                        continue;
                    }
                    case NONE -> {
                    }
                }

                // 运行 Agent
                try (RootTaskScope root = new RootTaskScope();
                     CancellationContext.Scope rootBinding = root.bind();
                     LangfuseTelemetry.RootTrace trace = LangfuseTelemetry.openRootTask(root.taskId())) {
                String submittedInput = input;
                final String understandingInput = submittedInput;
                final LlmClient understandingClient = llmClient;
                AtomicReference<TaskContract> understood = new AtomicReference<>();
                ExecutionOutcome understandingOutcome = runOutcomeWithCancelSupport(terminal, ui, root,
                        () -> {
                            understood.set(new TaskUnderstandingEngine(understandingClient).understand(understandingInput));
                            return ExecutionOutcome.completed("");
                        });
                if (understandingOutcome.reason() != ExecutionOutcome.Reason.COMPLETED) {
                    LangfuseTelemetry.recordFailure(understandingOutcome.reason().name());
                    ui.println(understandingOutcome.displayContent());
                    continue;
                }
                TaskContract contract = understood.get();
                if (root.exhausted()) {
                    ExecutionOutcome stopped = new ExecutionOutcome(root.requestLimitReached()
                            ? ExecutionOutcome.Reason.MODEL_REQUEST_BUDGET_EXCEEDED
                            : ExecutionOutcome.Reason.TOKEN_BUDGET_EXCEEDED,
                            "⚠️ 根任务模型预算已耗尽，执行阶段没有可用请求。");
                    LangfuseTelemetry.recordFailure(stopped.reason().name());
                    ui.println(stopped.displayContent());
                    continue;
                }
                if (contract.needsConfirmation()) {
                    ui.println(contract.confirmationSummary());
                    String correction = lineReader.readLine("回车按原任务执行；输入完整修改任务；/cancel 取消> ").trim();
                    if ("/cancel".equalsIgnoreCase(correction)) {
                        root.token().cancel();
                        LangfuseTelemetry.recordFailure("CANCELLED");
                        continue;
                    }
                    if (!correction.isEmpty()) {
                        submittedInput = correction;
                        input = correction;
                        contract = TaskContract.understand(correction).withUserConfirmed();
                    }
                }
                final TaskContract confirmedContract = contract;
                final String confirmedInput = submittedInput;
                input = mentionExpander.expand(input);
                input = localPathMentionExpander.expand(input);
                if (!(renderer instanceof InlineRenderer)) {
                    ui.println();
                }
                if (!submittedInputRendered) {
                    renderer.beginTurn();
                    printSubmittedInput(renderer, ui, submittedInput);
                }
                final String taskInput = input;
                approvalClassifier.setCurrentUserRequest(submittedInput);
                hitlToolRegistry.startAutoReviewTurn();
                Callable<ExecutionOutcome> runTask;
                String snapshotMode;
                String runMode = resolveRunMode(command.type(), nextTaskUsePlanMode, nextTaskUseTeamMode,
                        sessionModes.planMode());
                if ("plan".equals(runMode)) {
                    snapshotMode = "plan";
                    LlmClient activeClient = llmClient;
                    runTask = () -> {
                        PlanExecuteAgent planAgent = createPlanAgent(activeClient, reactAgent, terminal, lineReader, ui);
                        planAgent.setExternalContextSupplier(mcpServerManager::resourceIndexForPrompt);
                        planAgent.setSkillRegistry(skillRegistry);
                        return planAgent.runWithOutcome(taskInput, confirmedContract);
                    };
                } else if ("team".equals(runMode)) {
                    snapshotMode = "team";
                    LlmClient activeClient = llmClient;
                    runTask = () -> {
                        AgentOrchestrator orchestrator = createTeamAgent(activeClient, reactAgent, ui);
                        orchestrator.setExternalContextSupplier(mcpServerManager::resourceIndexForPrompt);
                        orchestrator.setSkillSystem(skillRegistry);
                        return orchestrator.runWithOutcome(taskInput, confirmedContract);
                    };
                } else {
                    snapshotMode = "react";
                    runTask = () -> reactAgent.runWithOutcome(taskInput, confirmedContract);
                }
                SnapshotService snapshotService = reactAgent.getToolRegistry().getSnapshotService();
                renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, snapshotMode));
                ExecutionOutcome outcome = runOutcomeWithCancelSupport(terminal,
                        ui,
                        root,
                        () -> snapshotService.runTurn(snapshotMode, taskInput, runTask::call));
                if (root.token().isCancelled() && outcome.reason() != ExecutionOutcome.Reason.CANCELLED) {
                    outcome = new ExecutionOutcome(ExecutionOutcome.Reason.CANCELLED,
                            outcome.content(), outcome.displayContent(), outcome.acceptance());
                } else if (root.admissionBlocked() && outcome.reason() != ExecutionOutcome.Reason.CANCELLED) {
                    outcome = new ExecutionOutcome(root.requestLimitReached()
                            ? ExecutionOutcome.Reason.MODEL_REQUEST_BUDGET_EXCEEDED
                            : ExecutionOutcome.Reason.TOKEN_BUDGET_EXCEEDED,
                            outcome.content(), outcome.displayContent(), outcome.acceptance());
                }
                if (outcome.reason() != ExecutionOutcome.Reason.COMPLETED)
                    LangfuseTelemetry.recordFailure(outcome.reason().name());
                String response = outcome.displayContent();
                if (!"react".equals(snapshotMode)) {
                    renderer.updateStatus(statusInfo(reactAgent, mcpServerManager, skillRegistry, "idle"));
                }
                nextTaskUsePlanMode = false;
                nextTaskUseTeamMode = false;
                if (response != null && !response.isBlank()) {
                    ui.println(response);
                    ui.println();
                }
                if (outcome.acceptance() != null) {
                    ui.println(outcome.acceptance().terminalSummary());
                    ui.println();
                }
                }
            }
            ui.println("\n👋 再见!");
            wechatRuntime.stop();
            renderer.close();

        } catch (IOException e) {
            System.err.println("❌ 终端初始化失败: " + e.getMessage());
            System.exit(1);
        } finally {
            com.paicli.observability.LangfuseTelemetry.shutdown();
        }
    }

    private static boolean isRuntimeServeCommand(String[] args) {
        return args != null
                && args.length >= 1
                && "serve".equalsIgnoreCase(args[0])
                && java.util.Arrays.stream(args).anyMatch("--http"::equalsIgnoreCase);
    }

    private static void startRuntimeApiAndBlock(String[] args) {
        PaiCliConfig config = PaiCliConfig.load();
        LlmClient client = LlmClientFactory.createFromConfig(config);
        if (client == null) {
            System.err.println("❌ 错误: 未找到可用的 API Key");
            System.exit(1);
        }
        int port = parseServePort(args, 8080);
        try {
            RuntimeThreadStore store = new RuntimeThreadStore(RuntimeThreadStore.defaultDbPath());
            RuntimeApiServer server = new RuntimeApiServer(
                    store,
                    prompt -> runHeadlessTask(prompt, client),
                    port,
                    RuntimeApiServer.configuredApiKey());
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.close();
                store.close();
            }, "paicli-runtime-api-shutdown"));
            server.start();
            System.out.println("✅ PaiCLI Runtime API 已启动: http://127.0.0.1:" + server.port());
            System.out.println("   认证: Authorization: Bearer <PAICLI_RUNTIME_API_KEY>");
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("❌ Runtime API 启动失败: " + e.getMessage());
            System.exit(1);
        }
    }

    private static int parseServePort(String[] args, int defaultPort) {
        if (args == null) {
            return defaultPort;
        }
        for (int i = 0; i < args.length - 1; i++) {
            if ("--port".equalsIgnoreCase(args[i])) {
                try {
                    return Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException ignored) {
                    return defaultPort;
                }
            }
        }
        return defaultPort;
    }

    private static com.paicli.agent.ExecutionOutcome runHeadlessTask(String prompt, LlmClient llmClient) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(Path.of(".").toAbsolutePath().normalize().toString());
        com.paicli.tool.CommandSandboxStatus sandboxStatus = registry.configureCommandSandbox(
                com.paicli.tool.CommandSandboxMode.fromConfiguration(),
                Path.of(registry.getProjectPath()));//配置命令沙箱
        if (!sandboxStatus.message().isBlank()) {
            System.err.println(sandboxStatus.message());
        }
        Agent agent = new Agent(llmClient, registry);//真正执行任务的Agent 怎样请求模型 怎样执行工具
        try {
            agent.setConversationLedger(ConversationLedger.openDefault(
                    Path.of(System.getProperty("user.home"))));
        } catch (IOException ignored) {
            // A background task should still run if its audit directory is temporarily unavailable.
        }
        TaskContract contract = TaskContract.understand(prompt);
        if (contract.needsConfirmation()) {
            return new ExecutionOutcome(ExecutionOutcome.Reason.EXECUTION_ERROR,
                    contract.confirmationSummary() + "\n请提供完整任务后重新提交。",
                    contract.confirmationSummary() + "\n请提供完整任务后重新提交。",
                    com.paicli.agent.TaskAcceptance.notEvaluated());
        }
        return agent.runWithOutcome(prompt, contract);
    }

    private static DurableTaskManager openTaskManager(AtomicReference<LlmClient> llmClientRef) {
        try {
            return DurableTaskManager.openDefault(prompt -> runHeadlessTask(prompt, llmClientRef.get()));
        } catch (Exception e) {
            throw new IllegalStateException("后台任务管理器初始化失败: " + e.getMessage(), e);
        }
    }

    private static String handleWechatCommand(String payload,
                                              LineReader lineReader,
                                              Renderer renderer,
                                              PrintStream out,
                                              WechatRuntimeController runtime) {
        String action = payload == null || payload.isBlank() ? "start" : payload.trim().toLowerCase(Locale.ROOT);
        try {
            return switch (action) {
                case "start", "on" -> {
                    WechatAccount account = WechatAccountStore.createDefault()
                            .loadLatest()
                            .orElseGet(() -> setupWechatAccount(lineReader, renderer, out));
                    yield runtime.start(account);
                }
                case "setup", "bind" -> {
                    WechatAccount account = setupWechatAccount(lineReader, renderer, out);
                    yield runtime.start(account);
                }
                case "status" -> runtime.status();
                case "stop", "off" -> {
                    runtime.stop();
                    yield "微信通道已停止。";
                }
                case "restart" -> {
                    runtime.stop();
                    WechatAccount account = WechatAccountStore.createDefault()
                            .loadLatest()
                            .orElseGet(() -> setupWechatAccount(lineReader, renderer, out));
                    yield runtime.start(account);
                }
                default -> """
                        未知 /wechat 子命令: %s
                        用法:
                          /wechat          绑定并启动；已绑定时直接启动
                          /wechat setup    重新扫码绑定并启动
                          /wechat status   查看当前进程内微信通道状态
                          /wechat stop     停止当前进程内微信通道
                        """.formatted(action).trim();
            };
        } catch (UserInterruptException e) {
            return "已取消微信通道操作。";
        } catch (Exception e) {
            return "微信通道操作失败: " + e.getMessage();
        }
    }

    private static WechatAccount setupWechatAccount(LineReader lineReader, Renderer renderer, PrintStream out) {
        try {
            IlinkClient client = new IlinkClient();
            WechatAccountStore store = WechatAccountStore.createDefault();
            Path defaultWorkspace = Path.of(".").toAbsolutePath().normalize();
            String workspace;
            renderer.beforeInput();
            try {
                workspace = lineReader.readLine("请输入微信通道工作区 [" + defaultWorkspace + "]: ");
            } finally {
                renderer.afterInput();
            }
            if (workspace == null || workspace.isBlank()) {
                workspace = defaultWorkspace.toString();
            }

            WechatQrLogin qr = client.startQrLogin("3");
            out.println("请用目标微信扫描二维码：");
            com.paicli.wechat.TerminalQrRenderer.print(out, qr.qrcodeUrl());
            out.println("扫码失败时可打开链接：" + qr.qrcodeUrl());
            out.println("等待扫码确认...");

            WechatLoginResult login = waitWechatLogin(client, qr.qrcodeId(), Duration.ofMinutes(5));
            if (!login.connected()) {
                throw new IllegalStateException("扫码绑定未完成: " + login.message());
            }
            WechatAccount account = store.createAccount(
                    login.token(),
                    login.accountId(),
                    login.baseUrl(),
                    login.userId(),
                    workspace);
            store.save(account);
            out.println("微信通道绑定完成");
            out.println("账号: " + login.accountId());
            out.println("工作区: " + workspace);
            return account;
        } catch (UserInterruptException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static WechatLoginResult waitWechatLogin(IlinkClient client, String qrcodeId, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            WechatLoginResult result = client.pollQrStatus(qrcodeId);
            if (result.connected() || result.expired()) {
                return result;
            }
            Thread.sleep(3_000);
        }
        throw new IllegalStateException("等待扫码超时");
    }

    private static final class WechatRuntimeController {
        private final Renderer renderer;
        private WechatMessageLoop loop;
        private Thread thread;
        private WechatAccount account;

        private WechatRuntimeController(Renderer renderer) {
            this.renderer = renderer;
        }

        synchronized String start(WechatAccount account) {
            if (isRunning()) {
                return "微信通道已在运行，账号: " + this.account.accountId();
            }
            this.account = account;
            this.loop = new WechatMessageLoop(new IlinkClient(), WechatAccountStore.createDefault(), account, renderer);
            this.thread = new Thread(() -> {
                try {
                    loop.run();
                } catch (Exception e) {
                    System.err.println("微信通道已退出: " + e.getMessage());
                }
            }, "paicli-wechat-channel");
            this.thread.setDaemon(true);
            this.thread.start();
            return "微信通道已启动，账号: " + account.accountId();
        }

        synchronized void stop() {
            if (loop != null) {
                loop.stop();
            }
            if (thread != null) {
                thread.interrupt();
            }
            loop = null;
            thread = null;
        }

        synchronized String status() {
            if (isRunning()) {
                return "微信通道运行中，账号: " + account.accountId()
                        + "\n工作区: " + account.workspace();
            }
            return "微信通道未运行。输入 /wechat 启动。";
        }

        private boolean isRunning() {
            return thread != null && thread.isAlive();
        }
    }

    static PlanExecuteAgent createPlanAgent(LlmClient llmClient, Agent reactAgent,
                                            PlanExecuteAgent.PlanReviewHandler reviewHandler) {
        PlanExecuteAgent planAgent = new PlanExecuteAgent(
                llmClient,
                reactAgent.getToolRegistry(),
                reactAgent.getMemoryManager(),
                reviewHandler,
                System.out
        );
        planAgent.setConversationLedger(reactAgent.getConversationLedger());
        return planAgent;
    }

    private static PlanExecuteAgent createPlanAgent(LlmClient llmClient, Agent reactAgent,
                                                    Terminal terminal, LineReader lineReader, PrintStream out) {
        out.println("📋 使用 Plan-and-Execute 模式\n");
        PlanExecuteAgent planAgent = new PlanExecuteAgent(
                llmClient,
                reactAgent.getToolRegistry(),
                reactAgent.getMemoryManager(),
                createPlanReviewHandler(terminal, lineReader, out),
                out
        );
        planAgent.setConversationLedger(reactAgent.getConversationLedger());
        return planAgent;
    }

    private static AgentOrchestrator createTeamAgent(LlmClient llmClient, Agent reactAgent, PrintStream out) {
        out.println("👥 使用 Multi-Agent 协作模式\n");
        AgentOrchestrator orchestrator =
                new AgentOrchestrator(llmClient, reactAgent.getToolRegistry(), reactAgent.getMemoryManager(), out);
        orchestrator.setConversationLedger(reactAgent.getConversationLedger());
        return orchestrator;
    }

    static String formatBetterHarnessProgress(BetterHarnessRunner.ProgressEvent event) {
        if (event == null) {
            return "0/0 · 等待进度";
        }
        return event.completed() + "/" + event.total() + " · " + event.message();
    }

    static String renderBetterHarnessMarkdown(String markdown, int terminalColumns) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        return TerminalMarkdownRenderer.render(markdown, terminalColumns);
    }

    private static String runWithCancelSupport(Terminal terminal, PrintStream out, Callable<String> task) {
        return runOutcomeWithCancelSupport(terminal, out,
                () -> ExecutionOutcome.completed(task.call())).displayContent();
    }

    private static ExecutionOutcome runOutcomeWithCancelSupport(Terminal terminal, PrintStream out,
                                                                 Callable<ExecutionOutcome> task) {
        return runOutcomeWithCancelSupport(terminal, out, null, task);
    }

    private static ExecutionOutcome runOutcomeWithCancelSupport(Terminal terminal, PrintStream out,
                                                                 RootTaskScope root,
                                                                 Callable<ExecutionOutcome> task) {
        CancellationToken token = root == null ? CancellationContext.startRun() : root.token();
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "paicli-agent-runner");
            thread.setDaemon(true);
            return thread;
        });
        Future<ExecutionOutcome> future = executor.submit(LangfuseTelemetry.wrap(() -> {
            try (CancellationContext.Scope ignored = CancellationContext.bind(token)) {
                return task.call();
            }
        }));
        // 进入 raw mode 监听 ESC：raw mode 关 ICANON / ECHO / IEXTEN 但保留 ISIG，所以 Ctrl+C 仍能终止 PaiCLI。
        Attributes original = null;
        try {
            if (terminal != null) {
                try {
                    original = terminal.enterRawMode();
                } catch (Exception ignored) {
                    // raw mode 进入失败（非交互终端等），降级为不监听 ESC，靠 Ctrl+C 退出。
                }
            }
            while (!future.isDone()) {
                if (original != null && pollEscCancel(terminal)) {
                    token.cancel();
                    future.cancel(true);
                    executor.shutdownNow();
                    return new ExecutionOutcome(ExecutionOutcome.Reason.CANCELLED, "⏹️ 已请求取消当前任务。");
                }
                try {
                    return future.get(150, TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException ignored) {
                    // 继续监听 ESC
                }
            }
            return future.get();
        } catch (CancellationException e) {
            return new ExecutionOutcome(ExecutionOutcome.Reason.CANCELLED, "⏹️ 已取消当前任务。");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            token.cancel();
            future.cancel(true);
            return new ExecutionOutcome(ExecutionOutcome.Reason.CANCELLED, "⏹️ 已取消当前任务。");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (token.isCancelled() || cause instanceof CancellationException) {
                return new ExecutionOutcome(ExecutionOutcome.Reason.CANCELLED, "⏹️ 已取消当前任务。");
            }
            if (cause instanceof RootTaskScope.RootRequestBudgetExceededException) {
                return new ExecutionOutcome(ExecutionOutcome.Reason.MODEL_REQUEST_BUDGET_EXCEEDED,
                        "⚠️ 根任务模型请求预算已耗尽。");
            }
            String message = cause == null || cause.getMessage() == null ? "未知错误" : cause.getMessage();
            return new ExecutionOutcome(ExecutionOutcome.Reason.EXECUTION_ERROR, "❌ 执行失败: " + message);
        } finally {
            if (terminal != null && original != null) {
                try {
                    terminal.setAttributes(original);
                } catch (Exception ignored) {
                }
            }
            if (root == null) CancellationContext.clear(token);
            executor.shutdownNow();
            if (root != null && token.isCancelled()) {
                try { executor.awaitTermination(2, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }
    }

    /** 计划审阅、工具审批正在读终端时跳过本轮 ESC 监听，否则两边会互相抢按键。 */
    private static boolean pollEscCancel(Terminal terminal) {
        if (!TerminalInputLock.tryAcquire()) {
            return false;
        }
        try {
            return readEscCancel(terminal);
        } finally {
            TerminalInputLock.release();
        }
    }

    /**
     * 任务运行期间监听 ESC 按键。raw mode 下 ESC 字节是 0x1b（27）。
     *
     * 关键陷阱：方向键 / Home / End 等由 ESC + 控制序列组成（如 ESC[A），不能误判为单 ESC 取消。
     * 复用 {@link #readInputBurst} + {@link #classifyEscapeSequence}：
     * - STANDALONE_ESC（孤立的 ESC）→ 用户取消
     * - CONTROL_SEQUENCE / BRACKETED_PASTE / OTHER → 丢弃，不取消
     */
    static boolean readEscCancel(Terminal terminal) {
        if (terminal == null) {
            return false;
        }
        try {
            NonBlockingReader reader = terminal.reader();
            int next = reader.read(50);
            if (next == NonBlockingReader.READ_EXPIRED || next < 0) {
                return false;
            }
            String escTail = next == 27 ? readInputBurst(terminal, 80, 20, 120) : null;
            if (next != 27) {
                // 非 ESC 输入，drain 这一轮残余字节避免堆积，但不触发取消。
                while (true) {
                    int more = reader.read(1);
                    if (more == NonBlockingReader.READ_EXPIRED || more < 0) {
                        break;
                    }
                }
            }
            return decideEscCancel(next, escTail);
        } catch (Exception ignored) {
            // 监听是 best-effort；失败不能影响任务执行。
            return false;
        }
    }

    /**
     * ESC 取消判断的纯函数版（不依赖终端 IO，便于单测）。
     *
     * @param firstByte ESC=27 触发判断；其他字节直接返回 false
     * @param escTail  紧跟 ESC 之后的字节序列（不含 ESC 本身）；null / 空 → 单 ESC 取消
     */
    static boolean decideEscCancel(int firstByte, String escTail) {
        if (firstByte != 27) {
            return false;
        }
        return classifyEscapeSequence(escTail) == EscapeSequenceType.STANDALONE_ESC;
    }

    private static PromptInput readPromptInput(Terminal terminal,
                                               LineReader lineReader,
                                               Renderer renderer,
                                               boolean allowEscCancel,
                                               boolean spaciousPrompt)
            throws UserInterruptException, EndOfFileException {
        if (spaciousPrompt) {
            renderer.stream().println();
        }
        renderer.beforeInput();
        try {
            String prompt = renderer.inputPrompt();
            String rightPrompt = renderer.inputRightPrompt();
            if (!allowEscCancel) {
                return PromptInput.submitted(lineReader.readLine(prompt, rightPrompt, (MaskingCallback) null, null));
            }

            if (terminal != null && terminal.writer() != null) {
                terminal.writer().print(prompt);
                terminal.writer().flush();
            } else {
                renderer.stream().print(prompt);
                renderer.stream().flush();
            }

            PrefillResult prefill = readPrefillInputFromTerminal(terminal, lineReader);
            if (prefill == null) {
                return PromptInput.submitted(lineReader.readLine("", rightPrompt, (MaskingCallback) null, null));
            }

            if (prefill.canceled()) {
                return PromptInput.canceledInput();
            }

            if (prefill.submitted()) {
                return PromptInput.submitted("");
            }

            return PromptInput.submitted(lineReader.readLine("", rightPrompt, (MaskingCallback) null, prefill.seedBuffer()));
        } finally {
            renderer.afterInput();
        }
    }

    static boolean defaultSpaciousPrompt(boolean statusBarAvailable) {
        return false;
    }

    static void printSubmittedPrompt(PrintStream out, String input) {
        String visible = input == null ? "" : input.strip();
        if (visible.isEmpty()) {
            return;
        }
        out.println(AnsiStyle.userMessageBlock(visible, terminalColumns()));
    }

    static void printSubmittedInput(Renderer renderer, PrintStream out, String input) {
        String visible = redactSensitiveInput(input);
        if (renderer instanceof InlineRenderer inline) {
            inline.printSubmittedPrompt(visible);
        } else {
            printSubmittedPrompt(out, visible);
        }
    }

    static String redactSensitiveInput(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        String redacted = SENSITIVE_FLAG_VALUE.matcher(input).replaceAll("$1***");
        return SENSITIVE_ASSIGNMENT.matcher(redacted).replaceAll("$1***");
    }

    private static int terminalColumns() {
        String configured = System.getProperty("paicli.render.columns");
        if (configured != null && !configured.isBlank()) {
            try {
                return Math.max(20, Integer.parseInt(configured.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        String columns = System.getenv("COLUMNS");
        if (columns != null && !columns.isBlank()) {
            try {
                return Math.max(20, Integer.parseInt(columns.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return 120;
    }

    private static void refreshTerminalColumns(Terminal terminal) {
        if (terminal == null || terminal.getSize() == null || terminal.getSize().getColumns() <= 0) {
            return;
        }
        System.setProperty("paicli.render.columns", String.valueOf(Math.max(20, terminal.getSize().getColumns())));
    }

    static void configureAwtForCli() {
        if (!isMacOs()) {
            return;
        }
        System.setProperty("java.awt.headless", "true");
    }

    static boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static PlanExecuteAgent.PlanReviewHandler createPlanReviewHandler(Terminal terminal,
                                                                              LineReader lineReader,
                                                                              PrintStream out) {
        // 审阅在任务线程里读终端，期间主线程的 ESC 监听必须让出输入，否则按键会被抢走
        return (String goal, ExecutionPlan plan) -> TerminalInputLock.exclusive(
                () -> reviewPlan(terminal, lineReader, out, plan));
    }

    private static PlanExecuteAgent.PlanReviewDecision reviewPlan(Terminal terminal,
                                                                  LineReader lineReader,
                                                                  PrintStream out,
                                                                  ExecutionPlan plan) {
        boolean expanded = false;
        out.println(plan.summarize());
        out.println("📝 计划已生成。");
        out.println("   - 回车：按当前计划执行");
        out.println("   - Ctrl+O：展开完整计划");
        out.println("   - ESC：折叠或取消本次计划");
        out.println("   - I：输入补充要求，在当前计划上修改\n");

        while (true) {
            KeyReadResult keyReadResult = readSingleKeyFromTerminal(terminal);
            if (keyReadResult.ignoredControlSequence()) {
                continue;
            }

            // 审阅时粘贴的内容按补充要求处理：预填进“补充>”，用户确认后回车再提交
            if (keyReadResult.pastedText() != null) {
                out.println();
                String supplementInput = lineReader.readLine(
                        "补充> ", null, (MaskingCallback) null, keyReadResult.pastedText()).trim();
                return mapReviewDecision(PlanReviewInputParser.parse(supplementInput));
            }

            Integer key = keyReadResult.key();
            if (key != null) {
                // Enter
                if (key == '\n' || key == '\r') {
                    out.println();
                    return PlanExecuteAgent.PlanReviewDecision.execute();
                }

                // ESC (27)
                if (key == 27) {
                    out.println();
                    if (expanded) {
                        expanded = false;
                        out.println(plan.summarize());
                        out.println("📁 已退出完整计划视图，继续按 Enter / Ctrl+O / ESC / I。\n");
                        continue;
                    }
                    return PlanExecuteAgent.PlanReviewDecision.cancel();
                }

                // I 或 i
                if (key == 'i' || key == 'I') {
                    out.println();
                    String supplementInput = lineReader.readLine("补充> ").trim();
                    PlanReviewInputParser.Decision supplementDecision =
                            PlanReviewInputParser.parse(supplementInput);
                    return mapReviewDecision(supplementDecision);
                }

                // Ctrl+O
                if (key == CTRL_O) {
                    out.println();
                    out.println(plan.visualize());
                    expanded = true;
                    out.println("👆 已展开完整计划，继续按 Enter / Ctrl+O / ESC / I。\n");
                    continue;
                }

                out.println();
                out.println("未识别按键，请按 Enter / Ctrl+O / ESC / I。\n");
                continue;
            }

            // 如果无法读取单键，回退到行输入模式
            String decisionInput = lineReader.readLine("操作/补充> ").trim();
            if (decisionInput.equalsIgnoreCase("/view")) {
                out.println();
                out.println(plan.visualize());
                expanded = true;
                out.println("👆 已展开完整计划，继续输入 Enter / /cancel / 补充要求。\n");
                continue;
            }
            PlanReviewInputParser.Decision decision = PlanReviewInputParser.parse(decisionInput);
            return mapReviewDecision(decision);
        }
    }

    private static KeyReadResult readSingleKeyFromTerminal(Terminal terminal) {
        try {
            terminal.flush();
            Attributes originalAttributes = terminal.enterRawMode();
            try {
                int key = terminal.reader().read();
                if (key < 0) {
                    return KeyReadResult.unavailable();
                }

                if (key == 27) {
                    String escapeSequence = readInputBurst(terminal, 80, 20, 120);
                    EscapeSequenceType escapeSequenceType = classifyEscapeSequence(escapeSequence);
                    if (escapeSequenceType == EscapeSequenceType.STANDALONE_ESC) {
                        return KeyReadResult.keyPressed(27);
                    }
                    if (escapeSequenceType == EscapeSequenceType.BRACKETED_PASTE) {
                        return pastedOrIgnored(readBracketedPaste(terminal, escapeSequence));
                    }
                    if (escapeSequenceType == EscapeSequenceType.CONTROL_SEQUENCE) {
                        return KeyReadResult.ignoredSequence();
                    }
                }

                // 人手按键之间远大于 20ms；紧跟着还有输入，说明是没开 bracketed paste 时的粘贴
                String burst = readInputBurst(terminal, 20, 25, 500);
                if (!burst.isEmpty()) {
                    return pastedOrIgnored(Character.toString((char) key) + burst);
                }
                return KeyReadResult.keyPressed(key);
            } finally {
                terminal.setAttributes(originalAttributes);
            }
        } catch (Exception e) {
            return KeyReadResult.unavailable();
        }
    }

    private static PrefillResult readPrefillInputFromTerminal(Terminal terminal, LineReader lineReader) {
        try {
            terminal.flush();
            Attributes originalAttributes = terminal.enterRawMode();
            try {
                int key = terminal.reader().read();
                if (key < 0) {
                    return null;
                }

                if (key == 27) {
                    return readEscapeInput(terminal, lineReader);
                }

                if (isSubmitKey(key)) {
                    return PrefillResult.submittedInput();
                }

                String rawInput = switch (key) {
                    case 8, 127 -> "";
                    default -> Character.toString((char) key);
                };

                rawInput += readInputBurst(terminal, 20, 25, 250);
                return PrefillResult.seed(prepareSeedBuffer(rawInput));
            } finally {
                terminal.setAttributes(originalAttributes);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static PrefillResult readEscapeInput(Terminal terminal, LineReader lineReader)
            throws IOException, InterruptedException {
        String sequence = readInputBurst(terminal, 80, 20, 300);
        EscapeSequenceType escapeSequenceType = classifyEscapeSequence(sequence);
        if (escapeSequenceType == EscapeSequenceType.STANDALONE_ESC) {
            return PrefillResult.canceledInput();
        }

        if (escapeSequenceType == EscapeSequenceType.BRACKETED_PASTE) {
            return PrefillResult.seed(prepareSeedBuffer(readBracketedPaste(terminal, sequence)));
        }

        if (escapeSequenceType == EscapeSequenceType.CONTROL_SEQUENCE) {
            return PrefillResult.seed(seedBufferForHistoryNavigation(lineReader, sequence));
        }

        return PrefillResult.canceledInput();
    }

    /** 读完一次 bracketed paste（可能跨多个输入突发），返回去掉首尾标记的原文。 */
    private static String readBracketedPaste(Terminal terminal, String sequence)
            throws IOException, InterruptedException {
        String pastedText = sequence.substring(BRACKETED_PASTE_BEGIN.length());
        while (!pastedText.contains(BRACKETED_PASTE_END)) {
            String burst = readInputBurst(terminal, 30, 25, 500);
            if (burst.isEmpty()) {
                break;
            }
            pastedText += burst;
        }
        return stripBracketedPasteEndMarker(pastedText);
    }

    /** 粘贴内容去掉末尾换行后作为补充要求预填；只粘了空白就当没按键。 */
    private static KeyReadResult pastedOrIgnored(String rawPaste) {
        String text = prepareSeedBuffer(rawPaste).stripTrailing();
        return text.isBlank() ? KeyReadResult.ignoredSequence() : KeyReadResult.pasted(text);
    }

    private static String readInputBurst(Terminal terminal, long firstWaitMs, long idleWaitMs, long maxWaitMs)
            throws IOException, InterruptedException {
        NonBlockingReader reader = terminal.reader();
        StringBuilder buffer = new StringBuilder();
        long start = System.currentTimeMillis();
        long waitMs = firstWaitMs;

        while (System.currentTimeMillis() - start < maxWaitMs) {
            int next = reader.read(waitMs);
            if (next == NonBlockingReader.READ_EXPIRED || next < 0) {
                break;
            }
            buffer.append((char) next);
            waitMs = idleWaitMs;
        }

        return buffer.toString();
    }

    static String prepareSeedBuffer(String rawInput) {
        if (rawInput == null || rawInput.isEmpty()) {
            return "";
        }
        return normalizeLineEndings(rawInput);
    }

    static List<String> startupHints() {
        return List.of(
                "输入你的问题或任务",
                "输入 '/' 后按 Tab 补全命令",
                "输入 '@server:protocol://path' 可显式引用 MCP resource",
                "任务运行中按 ESC 取消当前任务",
                "默认模式是 ReAct"
        );
    }

    record SlashCommandHint(String insertText, String display, String description) {
    }

    static List<SlashCommandHint> slashCommandHints() {
        return List.of(
                new SlashCommandHint("/model", "/model", "查看当前模型"),
                new SlashCommandHint("/model list", "/model list", "查看模型列表与能力"),
                new SlashCommandHint("/model info ", "/model info [模型ID]", "查看模型详细配置"),
                new SlashCommandHint("/model refresh ", "/model refresh <provider>", "手动刷新模型列表"),
                new SlashCommandHint("/model add ", "/model add <provider> <模型ID>", "添加或更新模型能力"),
                new SlashCommandHint("/model deepseek-flash", "/model deepseek-flash", "切换到 DeepSeek V4.1 Flash（默认，支持图片）"),
                new SlashCommandHint("/model glm-5.3-flash", "/model glm-5.3-flash", "切换到 GLM-5.3-Flash"),
                new SlashCommandHint("/model glm-5.1", "/model glm-5.1", "切换到 GLM-5.1"),
                new SlashCommandHint("/model glm-5v-turbo", "/model glm-5v-turbo", "切换到 GLM-5V-Turbo 多模态"),
                new SlashCommandHint("/model deepseek", "/model deepseek", "切换到 DeepSeek（读取配置模型）"),
                new SlashCommandHint("/model hunyuan", "/model hunyuan", "切换到混元（读取配置模型）"),
                new SlashCommandHint("/model hy4-preview", "/model hy4-preview", "切换到混元 Hy4 preview"),
                new SlashCommandHint("/model step", "/model step", "切换到 StepFun（读取配置模型）"),
                new SlashCommandHint("/model kimi", "/model kimi", "切换到 Kimi（读取配置模型）"),
                new SlashCommandHint("/model freellmapi", "/model freellmapi", "切换到本地 FreeLLMAPI（读取配置模型）"),
                new SlashCommandHint("/model xfyun", "/model xfyun", "切换到讯飞星辰 MaaS（读取配置模型）"),
                new SlashCommandHint("/model agnes", "/model agnes", "切换到 Agnes 2.0 Flash（读取配置模型）"),
                new SlashCommandHint("/config provider freellmapi ", "/config provider freellmapi <选项>", "配置本地 FreeLLMAPI provider"),
                new SlashCommandHint("/config provider hunyuan ", "/config provider hunyuan <选项>", "配置混元 provider"),
                new SlashCommandHint("/config provider xfyun ", "/config provider xfyun <选项>", "配置讯飞星辰 MaaS provider"),
                new SlashCommandHint("/config provider agnes ", "/config provider agnes <选项>", "配置 Agnes provider"),
                new SlashCommandHint("/plan", "/plan", "下一条任务使用 Plan-and-Execute 模式"),
                new SlashCommandHint("/plan ", "/plan <任务内容>", "直接用计划模式执行这条任务"),
                new SlashCommandHint("/team", "/team", "下一条任务使用 Multi-Agent 协作模式"),
                new SlashCommandHint("/team ", "/team <任务内容>", "直接用多 Agent 协作执行这条任务"),
                new SlashCommandHint("/mode", "/mode", "查看模式（Shift+Tab 循环切换）"),
                new SlashCommandHint("/mode auto", "/mode auto", "自动审查，低风险命令直接执行（默认）"),
                new SlashCommandHint("/mode plan", "/mode plan", "每条输入先规划，审阅后执行"),
                new SlashCommandHint("/mode ask", "/mode ask", "审批：全部危险操作都确认"),
                new SlashCommandHint("/hitl", "/hitl", "查看 HITL 状态"),
                new SlashCommandHint("/hitl on", "/hitl on", "全部危险操作都确认"),
                new SlashCommandHint("/hitl default", "/hitl default", "切回 auto（默认）"),
                new SlashCommandHint("/browser", "/browser", "查看浏览器会话状态"),
                new SlashCommandHint("/browser connect", "/browser connect", "复用已允许远程调试的登录态 Chrome"),
                new SlashCommandHint("/browser connect ", "/browser connect <port>", "旧式 CDP 端口连接"),
                new SlashCommandHint("/browser status", "/browser status", "查看浏览器会话状态"),
                new SlashCommandHint("/browser tabs", "/browser tabs", "查看 shared 模式真实 Chrome tab"),
                new SlashCommandHint("/browser disconnect", "/browser disconnect", "切回 isolated 浏览器模式"),
                new SlashCommandHint("/wechat", "/wechat", "扫码绑定并启动微信 iLink 通道"),
                new SlashCommandHint("/wechat setup", "/wechat setup", "重新扫码绑定并启动微信通道"),
                new SlashCommandHint("/wechat status", "/wechat status", "查看微信通道状态"),
                new SlashCommandHint("/wechat stop", "/wechat stop", "停止当前进程内微信通道"),
                new SlashCommandHint("/task", "/task", "查看后台任务列表"),
                new SlashCommandHint("/task add ", "/task add <任务内容>", "提交后台任务"),
                new SlashCommandHint("/task cancel ", "/task cancel <task_id>", "取消后台任务"),
                new SlashCommandHint("/task log ", "/task log <task_id>", "查看后台任务结果"),
                new SlashCommandHint("/mcp", "/mcp", "查看 MCP server 状态"),
                new SlashCommandHint("/mcp restart ", "/mcp restart <name>", "重启 MCP server"),
                new SlashCommandHint("/mcp logs ", "/mcp logs <name>", "查看 MCP server 日志"),
                new SlashCommandHint("/mcp disable ", "/mcp disable <name>", "禁用 MCP server"),
                new SlashCommandHint("/mcp enable ", "/mcp enable <name>", "启用 MCP server"),
                new SlashCommandHint("/mcp trust ", "/mcp trust <name>", "确认并启动项目级 MCP server"),
                new SlashCommandHint("/mcp resources ", "/mcp resources <name>", "查看 MCP resources"),
                new SlashCommandHint("/mcp prompts ", "/mcp prompts <name>", "查看 MCP prompts"),
                new SlashCommandHint("/policy", "/policy", "查看安全策略状态"),
                new SlashCommandHint("/config", "/config", "打开配置 palette（只读视图 + 切换提示）"),
                new SlashCommandHint("/audit", "/audit", "查看今日最近 10 条危险工具审计"),
                new SlashCommandHint("/audit ", "/audit [N]", "查看今日最近 N 条危险工具审计"),
                new SlashCommandHint("/snapshot", "/snapshot", "查看最近 Side-Git 快照"),
                new SlashCommandHint("/snapshot status", "/snapshot status", "查看 Side-Git 快照状态"),
                new SlashCommandHint("/snapshot clean", "/snapshot clean", "清理当前项目 Side-Git 快照"),
                new SlashCommandHint("/restore ", "/restore <N>", "恢复到最近第 N 个 pre-turn 快照"),
                new SlashCommandHint("/index", "/index", "索引当前代码库"),
                new SlashCommandHint("/index ", "/index [路径]", "索引指定路径代码库"),
                new SlashCommandHint("/search ", "/search <查询>", "语义检索代码（RAG 辅助）"),
                new SlashCommandHint("/graph ", "/graph <类名>", "查看代码关系图谱"),
                new SlashCommandHint("/clear", "/clear", "清空当前对话历史"),
                new SlashCommandHint("/compact", "/compact", "手动压缩当前对话历史"),
                new SlashCommandHint("/init", "/init", "生成项目级记忆 PAI.md"),
                new SlashCommandHint("/init --force", "/init --force", "重写项目级记忆 PAI.md"),
                new SlashCommandHint("/history clear", "/history clear", "清空本机输入历史"),
                new SlashCommandHint("/context", "/context", "查看上下文和记忆状态"),
                new SlashCommandHint("/memory", "/memory", "查看记忆状态"),
                new SlashCommandHint("/memory list", "/memory list", "查看长期记忆列表"),
                new SlashCommandHint("/memory search ", "/memory search <关键词>", "搜索当前项目可见长期记忆"),
                new SlashCommandHint("/memory delete ", "/memory delete <id>", "删除单条长期记忆"),
                new SlashCommandHint("/memory verify ", "/memory verify <id>", "确认记忆仍然成立，刷新核实时间"),
                new SlashCommandHint("/memory replace ", "/memory replace <id> <新事实>", "用新内容替换冲突或过时的记忆"),
                new SlashCommandHint("/memory clear", "/memory clear", "清空长期记忆"),
                new SlashCommandHint("/save ", "/save [--global] [--force] <事实内容>", "手动保存项目级或全局长期记忆"),
                new SlashCommandHint("/skill", "/skill", "查看 skill 列表"),
                new SlashCommandHint("/skill list", "/skill list", "查看 skill 列表"),
                new SlashCommandHint("/skill show ", "/skill show <name>", "查看 SKILL.md 全文"),
                new SlashCommandHint("/skill on ", "/skill on <name>", "启用 skill"),
                new SlashCommandHint("/skill off ", "/skill off <name>", "禁用 skill"),
                new SlashCommandHint("/skill trust ", "/skill trust <name>", "信任当前项目的项目级 skill"),
                new SlashCommandHint("/skill reload", "/skill reload", "重新扫描 skill 目录"),
                new SlashCommandHint("/better-harness", "/better-harness", "审查当前项目的 AI 编码工作流"),
                new SlashCommandHint("/better-harness quick", "/better-harness quick", "快速生成 Better Harness 报告"),
                new SlashCommandHint("/better-harness normal", "/better-harness normal", "完整生成 Better Harness 报告"),
                new SlashCommandHint("/better-harness --inline", "/better-harness --inline", "只在终端输出，不写报告文件"),
                new SlashCommandHint("/export", "/export", "导出当前会话对话记录为 Markdown"),
                new SlashCommandHint("/resume", "/resume", "列出当前项目可以恢复的历史会话"),
                new SlashCommandHint("/resume ", "/resume <序号>", "恢复一次历史会话，接着之前的对话继续"),
                new SlashCommandHint("/exit", "/exit", "退出 PaiCLI"),
                new SlashCommandHint("/quit", "/quit", "退出 PaiCLI")
        );
    }

    private static void printSlashCommandHelp() {
        printSlashCommandHelp(System.out);
    }

    private static void printSlashCommandHelp(PrintStream out) {
        out.println("可用命令：");
        for (SlashCommandHint hint : slashCommandHints()) {
            out.println("   " + hint.display() + " - " + hint.description());
        }
        out.println();
    }

    static void configureSlashCommandHint(LineReader lineReader) {
        if (lineReader == null) {
            return;
        }
        lineReader.getWidgets().put("paicli-slash-command-hint", () -> {
            lineReader.getBuffer().write("/");
            return true;
        });
        Reference slashHint = new Reference("paicli-slash-command-hint");
        bindSlashWidget(lineReader, LineReader.MAIN, slashHint);
        bindSlashWidget(lineReader, LineReader.EMACS, slashHint);
        bindSlashWidget(lineReader, LineReader.VIINS, slashHint);
    }

    static void configureJLineInteractiveWidgets(LineReader lineReader) {
        if (lineReader == null) {
            return;
        }
        new AutosuggestionWidgets(lineReader).enable();
        new AutopairWidgets(lineReader).enable();
        // JLine TailTipWidgets 会通过 Status 预留多行底部区域；如果在首屏前 enable，
        // banner 前会出现大段空白，输入行下方也会长期空出一块。命令说明后续用
        // 不预留布局的方式展示，避免破坏 Claude Code / Qoder 风格的 inline 体验。
    }

    static LinkedHashMap<String, CmdDesc> slashCommandTailTips() {
        LinkedHashMap<String, CmdDesc> tips = new LinkedHashMap<>();
        for (SlashCommandHint hint : slashCommandHints()) {
            tips.computeIfAbsent(hint.insertText(), key ->
                    new CmdDesc().mainDesc(List.of(new AttributedString(hint.description()))));
            tips.computeIfAbsent(hint.display(), key ->
                    new CmdDesc().mainDesc(List.of(new AttributedString(hint.description()))));
        }
        return tips;
    }

    /**
     * 审批分类器优先选当前供应商目录中的 Flash 模型（请求时关闭思考）；
     * 没有 Flash 入口时沿用会话模型。可用 PAICLI_AUTO_CLASSIFIER_MODEL 覆盖。按供应商 + 模型缓存。
     */
    static java.util.function.Supplier<LlmClient> approvalClassifierClient(AtomicReference<LlmClient> current,
                                                                         PaiCliConfig config) {
        AtomicReference<String> cachedKey = new AtomicReference<>();
        AtomicReference<LlmClient> cachedClient = new AtomicReference<>();
        return () -> {
            LlmClient session = current.get();
            if (session == null) {
                return null;
            }
            String override = loadConfigValue("PAICLI_AUTO_CLASSIFIER_MODEL", null);
            String model = override == null || override.isBlank()
                    ? ModelCatalog.approvalModel(session.getProviderName(), session.getModelName())
                    : override.trim();
            String key = session.getProviderName() + "/" + model;
            if (!key.equals(cachedKey.get())) {
                cachedClient.set(LlmClientFactory.createApprovalClassifier(session.getProviderName(), model, config));
                cachedKey.set(key);
            }
            return cachedClient.get();
        };
    }

    /** Shift+Tab 在终端里发送 ESC [ Z，绑定成循环切换会话模式。 */
    static final String SHIFT_TAB = "\033[Z";

    /**
     * 决定这条输入走哪条执行路径：本条输入里显式写的 /plan、/team 优先，
     * 其次是上一条 /plan、/team 留下的一次性标记，最后才是 plan 会话模式。
     */
    static String resolveRunMode(CliCommandParser.CommandType commandType,
                                 boolean pendingPlan,
                                 boolean pendingTeam,
                                 boolean planSessionMode) {
        if (commandType == CliCommandParser.CommandType.SWITCH_PLAN) {
            return "plan";
        }
        if (commandType == CliCommandParser.CommandType.SWITCH_TEAM) {
            return "team";
        }
        if (pendingPlan) {
            return "plan";
        }
        if (pendingTeam) {
            return "team";
        }
        return planSessionMode ? "plan" : "react";
    }

    private static String returnModeHint(SessionModeController sessionModes, String verb) {
        return verb + (sessionModes.planMode() ? " plan 模式" : "默认 ReAct");
    }

    static void bindSessionModeCycle(LineReader lineReader, Runnable onCycle) {
        if (lineReader == null) {
            return;
        }
        lineReader.getWidgets().put("paicli-cycle-session-mode", () -> {
            onCycle.run();
            if (lineReader.isReading()) {
                lineReader.callWidget(LineReader.REDISPLAY);
            }
            return true;
        });
        Reference cycle = new Reference("paicli-cycle-session-mode");
        for (String keyMapName : List.of(LineReader.MAIN, LineReader.EMACS, LineReader.VIINS)) {
            KeyMap<org.jline.reader.Binding> keyMap = lineReader.getKeyMaps().get(keyMapName);
            if (keyMap != null) {
                keyMap.bind(cycle, SHIFT_TAB);
            }
        }
    }

    private static void bindSlashWidget(LineReader lineReader, String keyMapName, Reference slashHint) {
        KeyMap<org.jline.reader.Binding> keyMap = lineReader.getKeyMaps().get(keyMapName);
        if (keyMap != null) {
            keyMap.bind(slashHint, "/");
        }
    }

    static String formatSlashCommandChoices(int terminalWidth) {
        List<String> commands = slashCommandHints().stream()
                .map(SlashCommandHint::display)
                .distinct()
                .toList();
        int maxLen = commands.stream().mapToInt(String::length).max().orElse(12);
        int colWidth = Math.min(Math.max(maxLen + 4, 18), Math.max(18, terminalWidth));
        int columns = Math.max(1, Math.min(4, terminalWidth / colWidth));
        int rows = (int) Math.ceil(commands.size() / (double) columns);

        StringBuilder sb = new StringBuilder();
        sb.append("可用命令（Tab 补全，Enter 执行）：\n");
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                int index = col * rows + row;
                if (index >= commands.size()) {
                    continue;
                }
                String command = commands.get(index);
                sb.append(command);
                if (col < columns - 1) {
                    sb.append(" ".repeat(Math.max(2, colWidth - command.length())));
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * /config 命令处理：用 renderer.openPalette 展示当前配置项列表。
     * 当前是只读视图——选中一项后提示对应的 CLI 命令，由用户自己执行。
     */
    private static void handleConfigPalette(Renderer renderer,
                                            PaiCliConfig config,
                                            LlmClient llmClient,
                                            SwitchableHitlHandler hitlHandler,
                                            com.paicli.skill.SkillRegistry skillRegistry) {
        var items = java.util.List.of(
                "模型: " + (llmClient == null ? "(none)" : llmClient.getModelName() + " / " + llmClient.getProviderName()),
                "默认 Provider: " + (config == null ? "(none)" : config.getDefaultProvider()),
                "HITL: " + hitlHandler.confirmationModeLabel(),
                "Skill 启用数: " + (skillRegistry == null ? 0 : skillRegistry.enabledSkills().size()),
                "渲染器: " + renderer.getClass().getSimpleName(),
                "配置文件: ~/.paicli/config.json (只读视图，编辑请用编辑器)"
        );
        int selected = renderer.openPalette("配置 / config", items);
        if (selected < 0) {
            renderer.stream().println("(已关闭)");
            return;
        }
        String hint = switch (selected) {
            case 0, 1 -> "💡 GLM: /model glm-5.3-flash / /model glm-5.1 / /model glm-5v-turbo；其它: /model deepseek|hunyuan|step|kimi|freellmapi|xfyun|agnes 读取配置模型";
            case 2 -> "💡 切换 HITL: /hitl on / /hitl off";
            case 3 -> "💡 管理 Skill: /skill list / /skill on <name> / /skill off <name>";
            case 4 -> "💡 切换渲染器（重启后生效）: PAICLI_RENDERER=inline|lanterna|plain";
            case 5 -> "💡 当前不在 TUI 内编辑 config.json，建议在编辑器里改完重启";
            default -> "(unknown)";
        };
        renderer.stream().println(hint);
    }

    static String handleConfigCommand(PaiCliConfig config, String payload) {
        ProviderConfigUpdate update = parseProviderConfigUpdate(payload);
        if (update.error() != null) {
            return "❌ " + update.error() + "\n" + providerConfigUsage();
        }

        PaiCliConfig.ProviderConfig providerConfig = ensureProviderConfig(config, update.provider());
        if (update.apiKey() != null) {
            providerConfig.setApiKey(update.apiKey());
        }
        if (update.baseUrl() != null) {
            providerConfig.setBaseUrl(update.baseUrl());
        }
        if (update.model() != null) {
            providerConfig.setModel(update.model());
        }
        if (update.loraId() != null) {
            providerConfig.setLoraId(update.loraId());
        }
        if (update.setDefault()) {
            config.setDefaultProvider(update.provider());
        }
        config.save();

        StringBuilder out = new StringBuilder();
        out.append("✅ 已保存 provider 配置: ").append(update.provider()).append('\n');
        out.append("   model: ").append(providerConfig.getModel() == null || providerConfig.getModel().isBlank()
                ? "(默认)" : providerConfig.getModel()).append('\n');
        out.append("   baseUrl: ").append(providerConfig.getBaseUrl() == null || providerConfig.getBaseUrl().isBlank()
                ? "(默认)" : providerConfig.getBaseUrl()).append('\n');
        out.append("   apiKey: ").append(maskSecret(providerConfig.getApiKey())).append('\n');
        if ("xfyun".equals(update.provider())) {
            out.append("   loraId: ").append(providerConfig.getLoraId() == null || providerConfig.getLoraId().isBlank()
                    ? "(未配置)" : providerConfig.getLoraId()).append('\n');
        }
        if (update.setDefault()) {
            out.append("   默认 provider 已设为 ").append(update.provider()).append('\n');
        }
        out.append("   立即切换: /model ").append(update.provider());
        return out.toString();
    }

    static ProviderConfigUpdate parseProviderConfigUpdate(String payload) {
        List<String> args = splitArgs(payload);
        if (args.size() < 2 || !"provider".equalsIgnoreCase(args.get(0))) {
            return ProviderConfigUpdate.error("用法不正确");
        }

        String provider = normalizeProviderName(args.get(1));
        if (!isSupportedProvider(provider)) {
            return ProviderConfigUpdate.error("暂不支持 provider: " + args.get(1));
        }

        String apiKey = null;
        String baseUrl = null;
        String model = null;
        String loraId = null;
        boolean setDefault = false;
        for (int i = 2; i < args.size(); i++) {
            String token = args.get(i);
            if ("--default".equalsIgnoreCase(token) || "--set-default".equalsIgnoreCase(token)) {
                setDefault = true;
                continue;
            }

            String key;
            String value;
            int equals = token.indexOf('=');
            if (equals > 0) {
                key = token.substring(0, equals);
                value = token.substring(equals + 1);
            } else {
                key = token;
                if (i + 1 >= args.size()) {
                    return ProviderConfigUpdate.error("缺少 " + key + " 的值");
                }
                value = args.get(++i);
            }

            switch (normalizeConfigKey(key)) {
                case "api-key" -> apiKey = value;
                case "base-url" -> baseUrl = value;
                case "model" -> model = value;
                case "lora-id" -> loraId = value;
                default -> {
                    return ProviderConfigUpdate.error("未知配置项: " + key);
                }
            }
        }

        if (loraId != null && !"xfyun".equals(provider)) {
            return ProviderConfigUpdate.error("--lora-id 仅支持 xfyun provider");
        }

        if (apiKey == null && baseUrl == null && model == null && loraId == null && !setDefault) {
            return ProviderConfigUpdate.error("至少提供一个配置项");
        }
        return new ProviderConfigUpdate(provider, apiKey, baseUrl, model, loraId, setDefault, null);
    }

    private static String providerConfigUsage() {
        return """
                用法:
                  /config provider freellmapi --base-url http://localhost:5173/v1 --api-key <key> --model auto
                  /config provider freellmapi --model qwen/qwen3-coder:free --default
                  /config provider hunyuan --base-url https://tokenhub.tencentmaas.com/v1 --api-key <key> --model hy4-preview --default
                  /config provider xfyun --base-url https://maas-api.cn-huabei-1.xf-yun.com/v2 --api-key <key> --model Qwen3.6-35B-A3B --default
                  /config provider xfyun --lora-id <resourceId>
                  /config provider agnes --api-key <key> --model agnes-2.0-flash --default
                  /model freellmapi
                  /model hunyuan
                  /model xfyun
                  /model agnes
                """.stripTrailing();
    }

    private static List<String> splitArgs(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                } else {
                    current.append(ch);
                }
                continue;
            }
            if (ch == '\'' || ch == '"') {
                quote = ch;
                continue;
            }
            if (Character.isWhitespace(ch)) {
                if (!current.isEmpty()) {
                    args.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(ch);
        }
        if (!current.isEmpty()) {
            args.add(current.toString());
        }
        return args;
    }

    private static String normalizeConfigKey(String raw) {
        String key = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        while (key.startsWith("-")) {
            key = key.substring(1);
        }
        return switch (key) {
            case "apikey", "api_key", "key" -> "api-key";
            case "baseurl", "base_url", "url" -> "base-url";
            case "loraid", "lora_id", "resourceid", "resource_id" -> "lora-id";
            default -> key;
        };
    }

    private static String normalizeProviderName(String raw) {
        String provider = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (provider) {
            case "stepfun", "step-fun" -> "step";
            case "hy4", "hy4-preview", "tokenhub", "tencent-hunyuan", "tencent_hunyuan" -> "hunyuan";
            case "moonshot", "moonshotai", "moonshot-ai" -> "kimi";
            case "free-llm-api", "free_llm_api", "freellm", "free-llm" -> "freellmapi";
            case "xfyun-maas", "xfyun_maas", "iflytek", "iflytek-maas", "iflytek_maas", "maas" -> "xfyun";
            case "agnes-ai", "agnes_ai", "sapiens", "sapiens-ai", "sapiens_ai" -> "agnes";
            default -> provider;
        };
    }

    private static boolean isSupportedProvider(String provider) {
        return List.of("glm", "deepseek", "hunyuan", "step", "kimi", "freellmapi", "xfyun", "agnes").contains(provider);
    }

    private static String maskSecret(String value) {
        if (value == null || value.isBlank()) {
            return "(未配置)";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 8) {
            return "****";
        }
        return trimmed.substring(0, 4) + "..." + trimmed.substring(trimmed.length() - 4);
    }

    static void bindCtrlOToFoldableBlocks(LineReader lineReader, InlineRenderer inline) {
        if (lineReader == null || inline == null) {
            return;
        }
        lineReader.getWidgets().put("paicli-toggle-foldable", () -> {
            inline.toggleLastBlock();
            lineReader.callWidget(LineReader.REDISPLAY);
            return true;
        });
        Reference ref = new Reference("paicli-toggle-foldable");
        String ctrlO = String.valueOf((char) 15);  // Ctrl+O
        for (String mapName : new String[]{LineReader.MAIN, LineReader.EMACS, LineReader.VIINS}) {
            KeyMap<org.jline.reader.Binding> map = lineReader.getKeyMaps().get(mapName);
            if (map != null) {
                map.bind(ref, ctrlO);
            }
        }
    }

    // Ctrl+V 抓系统剪贴板里的图片到 ~/.paicli/cache/ 并把 @image:<path> 注入当前输入行。
    // 失败（无图 / headless / IO 错误）时只打提示，不破坏现有 buffer，覆盖掉 JLine 默认的
    // quoted-insert 没有交互价值。注意 macOS Cmd+V 通常被终端劫持成本地粘贴文本，所以这里
    // 绑的是 Ctrl+V（ASCII 22 / SYN），iTerm / Terminal.app 默认不会拦截。
    //
    // 输入层不按模型名拦截图片：与 Claude Code 类似，先把图片读成附件收进
    // prompt；模型是否接受 image block 由 provider API 自己处理。
    static void bindCtrlVToClipboardImage(LineReader lineReader) {
        if (lineReader == null) {
            return;
        }
        lineReader.getWidgets().put("paicli-paste-clipboard-image", () -> {
            ClipboardImage.GrabResult grab = ClipboardImage.grab();
            if (!grab.ok()) {
                lineReader.printAbove("⚠️ Ctrl+V 抓图失败: " + grab.error());
                lineReader.callWidget(LineReader.REDISPLAY);
                return true;
            }
            String token = "@image:<" + grab.path().toAbsolutePath() + "> ";
            lineReader.getBuffer().write(token);
            lineReader.callWidget(LineReader.REDISPLAY);
            return true;
        });
        Reference ref = new Reference("paicli-paste-clipboard-image");
        String ctrlV = String.valueOf((char) 22);  // Ctrl+V (SYN)
        for (String mapName : new String[]{LineReader.MAIN, LineReader.EMACS, LineReader.VIINS}) {
            KeyMap<org.jline.reader.Binding> map = lineReader.getKeyMaps().get(mapName);
            if (map != null) {
                map.bind(ref, ctrlV);
            }
        }
    }

    static void bindEscToClearInput(LineReader lineReader) {
        if (lineReader == null) {
            return;
        }
        lineReader.getWidgets().put("paicli-clear-input", () -> {
            clearInputBuffer(lineReader);
            lineReader.callWidget(LineReader.REDISPLAY);
            return true;
        });
        Reference clearInput = new Reference("paicli-clear-input");
        String esc = KeyMap.esc();
        for (String mapName : new String[]{LineReader.MAIN, LineReader.EMACS, LineReader.VIINS}) {
            KeyMap<org.jline.reader.Binding> map = lineReader.getKeyMaps().get(mapName);
            if (map != null) {
                map.bind(clearInput, esc);
            }
        }
    }

    static void clearInputBuffer(LineReader lineReader) {
        if (lineReader == null || lineReader.getBuffer() == null) {
            return;
        }
        lineReader.getBuffer().clear();
    }

    private static void handleExportCommand(PrintStream out, Agent reactAgent) {
        List<LlmClient.Message> history = reactAgent.getConversationHistory();
        if (!hasExportableMessages(history)) {
            out.println("📭 当前没有对话记录可导出\n");
            return;
        }

        Path exportsDir = Path.of(System.getProperty("user.home"), ".paicli", "exports");
        try {
            Files.createDirectories(exportsDir);
        } catch (IOException e) {
            out.println("❌ 创建导出目录失败: " + e.getMessage() + "\n");
            return;
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path exportFile = exportsDir.resolve("session-" + timestamp + ".md");

        String markdown = renderConversationExport(history, LocalDateTime.now());

        try {
            Files.writeString(exportFile, markdown);
            out.println("✅ 对话记录已导出: " + exportFile.toAbsolutePath());
            out.println("   共 " + countExportedMessages(history) + " 条消息\n");
        } catch (IOException e) {
            out.println("❌ 写入导出文件失败: " + e.getMessage() + "\n");
        }
    }

    static boolean hasExportableMessages(List<LlmClient.Message> history) {
        return history != null && history.stream()
                .anyMatch(msg -> msg != null);
    }

    static long countExportedMessages(List<LlmClient.Message> history) {
        if (history == null) {
            return 0;
        }
        return history.stream()
                .filter(msg -> msg != null)
                .count();
    }

    static String renderConversationExport(List<LlmClient.Message> history, LocalDateTime exportedAt) {
        StringBuilder md = new StringBuilder();
        md.append("# PaiCLI 会话导出\n\n");
        md.append("**导出时间**: ").append(exportedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n\n");
        md.append("---\n\n");

        for (int i = 0; i < history.size(); i++) {
            LlmClient.Message msg = history.get(i);
            if (msg == null) {
                continue;
            }
            String role = msg.role();

            md.append("## ").append(capitalizeRole(role)).append("\n\n");

            // reasoning content
            if (msg.reasoningContent() != null && !msg.reasoningContent().isBlank()) {
                md.append("> **思考过程**:\n> \n");
                for (String line : msg.reasoningContent().replace("\r\n", "\n").split("\n")) {
                    md.append("> ").append(line).append("\n");
                }
                md.append("\n");
            }

            // tool calls
            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                md.append("**工具调用**:\n\n");
                for (LlmClient.ToolCall tc : msg.toolCalls()) {
                    String toolName = tc.function() != null ? tc.function().name() : "unknown";
                    String toolArgs = tc.function() != null ? tc.function().arguments() : "{}";
                    md.append("- **").append(toolName).append("**:\n");
                    appendFencedBlock(md, formatJsonArg(toolArgs), "json", "  ");
                    md.append("\n");
                }
            }

            // content
            if (msg.content() != null && !msg.content().isBlank()) {
                if ("tool".equals(role)) {
                    String content = msg.content();
                    if (content.length() > 8000) {
                        content = content.substring(0, 8000) + "\n... (已截断，原始长度 " + msg.content().length() + " 字符)";
                    }
                    appendFencedBlock(md, content, "", "");
                    md.append("\n");
                } else {
                    md.append(msg.content()).append("\n\n");
                }
            }
        }
        return md.toString();
    }

    private static void appendFencedBlock(StringBuilder md, String content, String info, String indent) {
        String fence = markdownFenceFor(content);
        md.append(indent).append(fence);
        if (info != null && !info.isBlank()) {
            md.append(info);
        }
        md.append('\n');
        String normalized = content == null ? "" : content.replace("\r\n", "\n").replace('\r', '\n');
        for (String line : normalized.split("\n", -1)) {
            md.append(indent).append(line).append('\n');
        }
        md.append(indent).append(fence).append("\n");
    }

    static String markdownFenceFor(String content) {
        int longest = 0;
        int current = 0;
        String text = content == null ? "" : content;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '`') {
                current++;
                longest = Math.max(longest, current);
            } else {
                current = 0;
            }
        }
        return "`".repeat(Math.max(3, longest + 1));
    }

    private static String capitalizeRole(String role) {
        return switch (role) {
            case "user" -> "User";
            case "assistant" -> "Assistant";
            case "tool" -> "Tool Result";
            case "system" -> "System";
            default -> role.substring(0, 1).toUpperCase() + role.substring(1);
        };
    }

    private static String formatJsonArg(String json) {
        if (json == null || json.isBlank()) {
            return "{}";
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(
                            new com.fasterxml.jackson.databind.ObjectMapper().readTree(json));
        } catch (Exception e) {
            return json;
        }
    }

    private static void printPolicyStatus(PrintStream out, Agent reactAgent) {
        out.println("🛡️ 安全策略状态：");
        out.println("   项目根: " + reactAgent.getToolRegistry().getProjectPath());
        out.println("   危险工具: " + String.join(", ", ApprovalPolicy.getDangerousTools()) + "，以及所有 mcp__ 前缀工具");
        out.println("   路径围栏: 强制限定在项目根之内（read_file / write_file / edit_file / list_dir / create_project）");
        out.println("   命令黑名单: sudo / rm -rf 全盘 / mkfs / dd of=/dev / fork bomb / curl|sh / find / / chmod 777 / / shutdown");
        out.println("   写入文件上限: 5MB");
        out.println("   命令执行上限: 60 秒，输出 8KB（截断）");
        out.println("   审计目录: " + reactAgent.getToolRegistry().getAuditLog().getAuditDir());
        out.println();
    }

    static String handleBrowserCommand(String payload,
                                       BrowserSession browserSession,
                                       BrowserConnectivityCheck connectivityCheck,
                                       McpServerManager mcpServerManager,
                                       HitlToolRegistry registry,
                                       HitlHandler hitlHandler) {
        String normalized = payload == null || payload.isBlank() ? "status" : payload.trim();
        String[] parts = normalized.split("\\s+");
        String subCommand = parts[0].toLowerCase();
        return switch (subCommand) {
            case "status" -> browserStatus(browserSession, connectivityCheck, mcpServerManager);
            case "connect" -> {
                if (parts.length >= 2) {
                    int port = parseBrowserPort(parts[1]);
                    yield browserConnectByPort(port, browserSession, connectivityCheck, mcpServerManager, hitlHandler);
                }
                yield browserAutoConnect(browserSession, mcpServerManager, hitlHandler);
            }
            case "disconnect" -> browserDisconnect(browserSession, mcpServerManager, hitlHandler);
            case "tabs" -> browserTabs(browserSession, registry);
            default -> """
                    ❌ 未知 /browser 子命令: %s
                    可用命令：
                      /browser status
                      /browser connect [port]
                      /browser disconnect
                      /browser tabs
                    """.formatted(normalized).trim();
        };
    }

    private static String browserStatus(BrowserSession browserSession,
                                        BrowserConnectivityCheck connectivityCheck,
                                        McpServerManager mcpServerManager) {
        BrowserConnectivityCheck.ProbeResult probe = connectivityCheck.probe(9222);
        McpServer server = mcpServerManager.server("chrome-devtools");
        String serverStatus = server == null
                ? "未配置"
                : server.status() == McpServerStatus.READY
                ? "● ready (" + server.tools().size() + " tools)"
                : server.status().name().toLowerCase() + (server.errorMessage() == null ? "" : " - " + server.errorMessage());
        String mode = browserSession.mode() == BrowserMode.SHARED
                ? "shared（复用 " + browserSession.browserUrl() + "）"
                : "isolated（临时 user-data-dir，无登录态）";
        return """
                🌐 浏览器会话
                  当前模式: %s
                  chrome-devtools server: %s
                  旧式 /json/version 探活: %s
                  自动连接: Chrome 144+ 可在 chrome://inspect/#remote-debugging 勾选 Allow remote debugging 后使用 /browser connect
                """.formatted(mode, serverStatus, probe.ok() ? "✅ " + probe.browserUrl() : "⚠️ " + probe.message()).trim();
    }

    private static String browserAutoConnect(BrowserSession browserSession,
                                             McpServerManager mcpServerManager,
                                             HitlHandler hitlHandler) {
        McpServer server = mcpServerManager.server("chrome-devtools");
        if (server == null) {
            return "❌ 未配置 chrome-devtools MCP server，请先检查 ~/.paicli/mcp.json";
        }
        List<String> oldArgs = List.copyOf(server.config().getArgs());
        List<String> autoConnectArgs = List.of("-y", "chrome-devtools-mcp@latest", "--autoConnect");
        String result = mcpServerManager.restartWithArgs("chrome-devtools", autoConnectArgs);
        McpServer restarted = mcpServerManager.server("chrome-devtools");
        if (restarted != null && restarted.status() == McpServerStatus.READY) {
            browserSession.switchToShared("autoConnect");
            hitlHandler.clearApprovedAllForServer("chrome-devtools");
            return "🔄 已用 --autoConnect 连接 Chrome（需已在 chrome://inspect/#remote-debugging 允许远程调试）\n" + result;
        }
        mcpServerManager.restartWithArgs("chrome-devtools", oldArgs);
        return "❌ autoConnect 连接失败，已回滚 chrome-devtools 启动参数：\n" + result
                + "\n\n请确认 Chrome 144+ 已打开 chrome://inspect/#remote-debugging，并勾选 Allow remote debugging for this browser instance。";
    }

    private static String browserConnectByPort(int port,
                                               BrowserSession browserSession,
                                               BrowserConnectivityCheck connectivityCheck,
                                               McpServerManager mcpServerManager,
                                               HitlHandler hitlHandler) {
        if (port < 1024 || port > 65535) {
            return "❌ /browser connect 端口必须在 1024-65535 之间。默认 /browser connect 使用 --autoConnect；旧式 CDP 端口连接可用 /browser connect 9222。";
        }
        BrowserConnectivityCheck.ProbeResult probe = connectivityCheck.probe(port);
        if (!probe.ok()) {
            return "❌ 未检测到 Chrome 调试端口 127.0.0.1:" + port + "：" + probe.message() + "\n\n"
                    + chromeLaunchHelp(port);
        }

        McpServer server = mcpServerManager.server("chrome-devtools");
        if (server == null) {
            return "❌ 未配置 chrome-devtools MCP server，请先检查 ~/.paicli/mcp.json";
        }
        List<String> oldArgs = List.copyOf(server.config().getArgs());
        List<String> sharedArgs = List.of("-y", "chrome-devtools-mcp@latest", "--browser-url=" + probe.browserUrl());
        String result = mcpServerManager.restartWithArgs("chrome-devtools", sharedArgs);
        McpServer restarted = mcpServerManager.server("chrome-devtools");
        if (restarted != null && restarted.status() == McpServerStatus.READY) {
            browserSession.switchToShared(probe.browserUrl());
            hitlHandler.clearApprovedAllForServer("chrome-devtools");
            return "🔄 切换 chrome-devtools server 到 shared 模式 (" + probe.browserUrl() + ")\n" + result;
        }
        mcpServerManager.restartWithArgs("chrome-devtools", oldArgs);
        return "❌ shared 模式切换失败，已回滚 chrome-devtools 启动参数：\n" + result;
    }

    private static String browserDisconnect(BrowserSession browserSession,
                                            McpServerManager mcpServerManager,
                                            HitlHandler hitlHandler) {
        McpServer server = mcpServerManager.server("chrome-devtools");
        if (server == null) {
            browserSession.switchToIsolated();
            return "❌ 未配置 chrome-devtools MCP server，已清理本地浏览器会话状态";
        }
        String result = mcpServerManager.restartWithArgs(
                "chrome-devtools",
                List.of("-y", "chrome-devtools-mcp@latest", "--isolated=true"));
        browserSession.switchToIsolated();
        hitlHandler.clearApprovedAllForServer("chrome-devtools");
        return "🔄 已切回 isolated 浏览器模式\n" + result;
    }

    private static String browserTabs(BrowserSession browserSession, HitlToolRegistry registry) {
        if (browserSession.mode() != BrowserMode.SHARED) {
            return "当前为 isolated 模式，没有真实 Chrome tab 可复用。可用 /browser connect 切到 shared 模式。";
        }
        return registry.executeTool("mcp__chrome-devtools__list_pages", "{}");
    }

    private static int parseBrowserPort(String value) {
        if (value == null || value.isBlank()) {
            return 9222;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String chromeLaunchHelp(int port) {
        return """
                请先用调试端口启动 Chrome：
                  macOS: open -na "Google Chrome" --args --remote-debugging-port=%d --user-data-dir=/tmp/paicli-chrome-profile
                  Windows: start chrome.exe --remote-debugging-port=%d --user-data-dir=%%TEMP%%\\paicli-chrome-profile
                  Linux: google-chrome --remote-debugging-port=%d --user-data-dir=/tmp/paicli-chrome-profile
                然后重新执行 /browser connect %d
                """.formatted(port, port, port, port).trim();
    }

    private static void printMcpCommandResult(PrintStream out, String result) {
        out.println(result);
        out.println();
    }

    private static void printAuditTail(PrintStream out, Agent reactAgent, String payload) {
        int requested = parseAuditCount(payload, 10);
        List<AuditLog.AuditEntry> entries = reactAgent.getToolRegistry().getAuditLog().readRecent(requested);
        if (entries.isEmpty()) {
            out.println("📭 今日尚无审计记录\n");
            return;
        }
        out.println("📋 最近 " + entries.size() + " 条危险工具审计：");
        for (AuditLog.AuditEntry entry : entries) {
            out.printf("   [%s] %s %s (%dms, approver=%s)%n",
                    entry.outcome().toUpperCase(),
                    entry.timestamp(),
                    entry.tool(),
                    entry.durationMs(),
                    entry.approver());
            if (entry.reason() != null && !entry.reason().isBlank()) {
                out.println("        原因: " + entry.reason());
            }
            BrowserAuditMetadata metadata = entry.metadata();
            if (metadata != null) {
                out.println("        浏览器: mode=" + metadata.browserMode()
                        + ", sensitive=" + metadata.sensitive()
                        + (metadata.targetUrl() == null ? "" : ", url=" + metadata.targetUrl()));
            }
        }
        out.println();
    }

    private static void printSnapshotCommand(PrintStream out, SnapshotService snapshotService, String payload) {
        String normalized = payload == null || payload.isBlank() ? "list" : payload.trim().toLowerCase();
        if ("status".equals(normalized)) {
            out.println(snapshotService.status());
            out.println();
            return;
        }
        if ("clean".equals(normalized)) {
            out.println(snapshotService.clean());
            out.println();
            return;
        }
        if (!"list".equals(normalized)) {
            out.println("""
                    ❌ 未知 /snapshot 子命令: %s
                    可用命令：
                      /snapshot
                      /snapshot status
                      /snapshot clean
                      /restore <N>
                    """.formatted(payload).trim());
            out.println();
            return;
        }
        try {
            List<TurnSnapshot> snapshots = snapshotService.listSnapshots(20);
            if (snapshots.isEmpty()) {
                out.println("📭 暂无 Side-Git 快照\n");
                return;
            }
            out.println("📸 最近 " + snapshots.size() + " 条 Side-Git 快照：");
            int preTurnIndex = 0;
            for (TurnSnapshot snapshot : snapshots) {
                String restoreHint = "";
                if ("pre-turn".equals(snapshot.phase().label())) {
                    preTurnIndex++;
                    restoreHint = "  /restore " + preTurnIndex;
                }
                out.printf("   %s %-11s %-18s %s%s%n",
                        snapshot.shortCommitId(),
                        snapshot.phase().label(),
                        snapshot.turnId(),
                        snapshot.createdAt(),
                        restoreHint);
            }
            out.println();
        } catch (Exception e) {
            out.println("❌ 读取快照失败: " + e.getMessage() + "\n");
        }
    }

    private static void printRestoreCommand(PrintStream out, SnapshotService snapshotService, String payload) {
        int offset = parseAuditCount(payload, 1);
        try {
            RestoreResult result = snapshotService.restorePreTurn(offset);
            out.println(result.formatForCli());
            out.println();
        } catch (Exception e) {
            out.println("❌ 恢复快照失败: " + e.getMessage() + "\n");
        }
    }

    private static int parseAuditCount(String payload, int defaultN) {
        if (payload == null || payload.isBlank()) return defaultN;
        try {
            int n = Integer.parseInt(payload.trim());
            return Math.max(1, Math.min(n, 100));
        } catch (NumberFormatException e) {
            return defaultN;
        }
    }

    private static void printStartupHints(PrintStream out) {
        out.println("💡 提示:");
        for (String hint : startupHints()) {
            out.println("   - " + hint);
        }
        out.println();
    }

    private static StartupScreenInfo startupScreenInfo(LlmClient llmClient,
                                                       McpServerManager mcpServerManager,
                                                       SkillRegistry skillRegistry,
                                                       String note) {
        long ready = mcpServerManager.servers().stream()
                .filter(server -> server.status() == McpServerStatus.READY)
                .count();
        int total = mcpServerManager.servers().size();
        int tools = mcpServerManager.servers().stream()
                .mapToInt(server -> server.tools().size())
                .sum();
        int skillTotal = skillRegistry.allSkills().size();
        int skillEnabled = skillRegistry.enabledSkills().size();
        return new StartupScreenInfo(
                ModelCatalog.statusName(llmClient.getProviderName(), llmClient.getModelName()),
                llmClient.getProviderName(),
                ready,
                total,
                tools,
                skillEnabled,
                skillTotal,
                (note == null ? "" : note.trim())
                        + ModelCommands.aliasHint(llmClient.getProviderName(), llmClient.getModelName())
        );
    }

    private static StatusInfo statusInfo(LlmClient llmClient,
                                         SwitchableHitlHandler hitlHandler,
                                         String phase,
                                         McpServerManager mcpServerManager,
                                         SkillRegistry skillRegistry) {
        String normalizedPhase = phase == null || phase.isBlank() ? "idle" : phase;
        StatusInfo base = "idle".equals(normalizedPhase)
                ? StatusInfo.idle(ModelCatalog.statusName(llmClient.getProviderName(), llmClient.getModelName()), llmClient.maxContextWindow(), hitlHandler.isConfirmationActive())
                : StatusInfo.active(ModelCatalog.statusName(llmClient.getProviderName(), llmClient.getModelName()), llmClient.maxContextWindow(),
                hitlHandler.isConfirmationActive(), normalizedPhase);
        return base.withEnvironment(mcpStatusSummary(mcpServerManager), skillStatusSummary(skillRegistry));
    }

    private static StatusInfo statusInfo(Agent reactAgent,
                                         McpServerManager mcpServerManager,
                                         SkillRegistry skillRegistry,
                                         String phase) {
        StatusInfo base = reactAgent.currentStatus(phase);
        return base.withEnvironment(mcpStatusSummary(mcpServerManager), skillStatusSummary(skillRegistry));
    }

    private static String mcpStatusSummary(McpServerManager mcpServerManager) {
        if (mcpServerManager == null || mcpServerManager.servers().isEmpty()) {
            return "MCP 0";
        }
        long ready = mcpServerManager.servers().stream()
                .filter(server -> server.status() == McpServerStatus.READY)
                .count();
        return "MCP " + ready + "/" + mcpServerManager.servers().size();
    }

    private static String skillStatusSummary(SkillRegistry skillRegistry) {
        if (skillRegistry == null || skillRegistry.allSkills().isEmpty()) {
            return "Skill 0";
        }
        return "Skill " + skillRegistry.enabledSkills().size() + "/" + skillRegistry.allSkills().size();
    }

    private static String appendStartupNote(String current, String next) {
        if (next == null || next.isBlank()) {
            return current == null ? "" : current;
        }
        if (current == null || current.isBlank()) {
            return next;
        }
        return current + "\n" + next;
    }

    static Duration mcpStartupWait() {
        String configured = System.getProperty("paicli.mcp.startup.wait.seconds");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("PAICLI_MCP_STARTUP_WAIT_SECONDS");
        }
        if (configured == null || configured.isBlank()) {
            return Duration.ofSeconds(8);
        }
        try {
            long seconds = Long.parseLong(configured.trim());
            return seconds > 0 ? Duration.ofSeconds(seconds) : Duration.ofSeconds(8);
        } catch (NumberFormatException ignored) {
            return Duration.ofSeconds(8);
        }
    }

    static String normalizeLineEndings(String rawInput) {
        return rawInput
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }

    private static String stripBracketedPasteEndMarker(String rawInput) {
        int endMarkerIndex = rawInput.indexOf(BRACKETED_PASTE_END);
        if (endMarkerIndex >= 0) {
            return rawInput.substring(0, endMarkerIndex);
        }
        return rawInput;
    }

    private static boolean isSubmitKey(int key) {
        return key == '\n' || key == '\r';
    }

    static EscapeSequenceType classifyEscapeSequence(String sequence) {
        if (sequence == null || sequence.isEmpty()) {
            return EscapeSequenceType.STANDALONE_ESC;
        }
        if (sequence.startsWith(BRACKETED_PASTE_BEGIN)) {
            return EscapeSequenceType.BRACKETED_PASTE;
        }
        if (sequence.startsWith("[") || sequence.startsWith("O")) {
            return EscapeSequenceType.CONTROL_SEQUENCE;
        }
        return EscapeSequenceType.OTHER;
    }

    static String seedBufferForHistoryNavigation(LineReader lineReader, String sequence) {
        if (lineReader == null || sequence == null || sequence.isEmpty()) {
            return "";
        }

        if (isUpArrowSequence(sequence)) {
            return latestHistoryEntry(lineReader.getHistory());
        }

        if (isDownArrowSequence(sequence)) {
            return "";
        }

        return "";
    }

    private static boolean isUpArrowSequence(String sequence) {
        return ARROW_UP.equals(sequence) || APP_ARROW_UP.equals(sequence);
    }

    private static boolean isDownArrowSequence(String sequence) {
        return ARROW_DOWN.equals(sequence) || APP_ARROW_DOWN.equals(sequence);
    }

    private static String latestHistoryEntry(History history) {
        if (history == null || history.isEmpty()) {
            return "";
        }

        int lastIndex = history.last();
        if (lastIndex < 0) {
            return "";
        }

        String entry = history.get(lastIndex);
        return entry == null ? "" : entry;
    }

    static void configureHistory(LineReader lineReader, Path homeDir) {
        if (lineReader == null) {
            return;
        }
        Path historyFile = resolveHistoryFile(homeDir);
        try {
            Files.createDirectories(historyFile.getParent());
            lineReader.setVariable(LineReader.HISTORY_FILE, historyFile);
            lineReader.setVariable(LineReader.HISTORY_SIZE, historySize());
            lineReader.setVariable(LineReader.HISTORY_FILE_SIZE, historyFileSize());
            lineReader.setOpt(LineReader.Option.HISTORY_IGNORE_SPACE);
            lineReader.setOpt(LineReader.Option.HISTORY_IGNORE_DUPS);
            lineReader.setOpt(LineReader.Option.HISTORY_REDUCE_BLANKS);
            lineReader.setOpt(LineReader.Option.DISABLE_EVENT_EXPANSION);
            lineReader.getHistory().load();
        } catch (IOException ignored) {
            // History is a convenience feature; failed persistence must not block the CLI.
        }
    }

    static Path resolveHistoryFile(Path homeDir) {
        String configured = firstNonBlank(System.getProperty(HISTORY_FILE_PROPERTY), System.getenv("PAICLI_HISTORY_FILE"));
        if (configured != null) {
            return normalizeHistoryFile(Path.of(configured));
        }
        Path base = homeDir == null ? Path.of(System.getProperty("user.home")) : homeDir;
        return base.resolve(".paicli").resolve("history").resolve(DEFAULT_HISTORY_FILE_NAME)
                .toAbsolutePath().normalize();
    }

    static Path normalizeHistoryFile(Path configured) {
        Path path = configured.toAbsolutePath().normalize();
        if (Files.isDirectory(path)) {
            return path.resolve(DEFAULT_HISTORY_FILE_NAME).toAbsolutePath().normalize();
        }
        return path;
    }

    static void clearLineReaderHistory(LineReader lineReader) {
        if (lineReader == null || lineReader.getHistory() == null) {
            return;
        }
        try {
            lineReader.getHistory().purge();
        } catch (IOException ignored) {
            // Keep command behavior simple: in-memory history may still be reset by JLine.
        }
    }

    private static int historySize() {
        return configuredPositiveInt(HISTORY_SIZE_PROPERTY, "PAICLI_HISTORY_SIZE", 2_000);
    }

    private static int historyFileSize() {
        return configuredPositiveInt(HISTORY_FILE_SIZE_PROPERTY, "PAICLI_HISTORY_FILE_SIZE", 10_000);
    }

    private static int configuredPositiveInt(String property, String env, int fallback) {
        String raw = firstNonBlank(System.getProperty(property), System.getenv(env));
        if (raw == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private static PlanExecuteAgent.PlanReviewDecision mapReviewDecision(PlanReviewInputParser.Decision decision) {
        return switch (decision.type()) {
            case EXECUTE -> PlanExecuteAgent.PlanReviewDecision.execute();
            case CANCEL -> PlanExecuteAgent.PlanReviewDecision.cancel();
            case SUPPLEMENT -> PlanExecuteAgent.PlanReviewDecision.supplement(decision.feedback());
        };
    }

    private static void configureLogging() {
        configureLogProperty(LOG_DIR_PROPERTY, "PAICLI_LOG_DIR",
                Path.of(System.getProperty("user.home"), ".paicli", "logs").toString());
        configureLogProperty(LOG_LEVEL_PROPERTY, "PAICLI_LOG_LEVEL", "INFO");
        configureLogProperty(LOG_MAX_HISTORY_PROPERTY, "PAICLI_LOG_MAX_HISTORY", "7");
        configureLogProperty(LOG_MAX_FILE_SIZE_PROPERTY, "PAICLI_LOG_MAX_FILE_SIZE", "10MB");
        configureLogProperty(LOG_TOTAL_SIZE_CAP_PROPERTY, "PAICLI_LOG_TOTAL_SIZE_CAP", "100MB");

        try {
            Files.createDirectories(Path.of(System.getProperty(LOG_DIR_PROPERTY)));
        } catch (IOException e) {
            System.err.println("⚠️ 创建日志目录失败: " + e.getMessage());
        }
    }

    private static void configureLogProperty(String propertyName, String envKey, String defaultValue) {
        String configuredValue = System.getProperty(propertyName);
        if (configuredValue == null || configuredValue.isBlank()) {
            configuredValue = loadConfigValue(envKey, defaultValue);
        }
        if (configuredValue != null && !configuredValue.isBlank()) {
            if (LOG_DIR_PROPERTY.equals(propertyName)) {
                configuredValue = expandHome(configuredValue.trim());
            }
            System.setProperty(propertyName, configuredValue.trim());
        }
    }

    private static String expandHome(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (value.equals("~")) {
            return System.getProperty("user.home");
        }
        if (value.startsWith("~/")) {
            return Path.of(System.getProperty("user.home"), value.substring(2)).toString();
        }
        return value;
    }

    private static String loadConfigValue(String key, String defaultValue) {
        String sysValue = System.getProperty(key);
        if (sysValue != null && !sysValue.isBlank()) {
            return sysValue.trim();
        }

        String envValue = System.getenv(key);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        File currentEnv = new File(ENV_FILE);
        if (currentEnv.exists()) {
            String value = readValueFromFile(currentEnv, key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }

        File homeEnv = new File(System.getProperty("user.home"), ENV_FILE);
        if (homeEnv.exists()) {
            String value = readValueFromFile(homeEnv, key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }

        return defaultValue;
    }

    private static String readValueFromFile(File file, String key) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith(key + "=")) {
                    return line.substring((key + "=").length()).trim();
                }
            }
        } catch (IOException e) {
            System.err.println("读取 .env 文件失败: " + e.getMessage());
        }
        return null;
    }

    static ModelSelection resolveModelSelection(String raw, PaiCliConfig config) {
        String value = raw == null ? "" : raw.trim();
        int slash = value.indexOf('/');
        if (slash > 0 && com.paicli.llm.ModelCatalog.BUILTINS.containsKey(value.substring(0, slash))) {
            String model = value.substring(slash + 1);
            if (!com.paicli.llm.ModelCatalog.validModelId(model)) throw new IllegalArgumentException("模型 ID 无效");
            return new ModelSelection(value.substring(0, slash), model, true);
        }
        if (!com.paicli.llm.ModelCatalog.BUILTINS.containsKey(value)) {
            List<String> matches = new ArrayList<>();
            for (String provider : com.paicli.llm.ModelCatalog.BUILTINS.keySet()) {
                if (com.paicli.llm.ModelCatalog.entries(config, provider).containsKey(value)) matches.add(provider);
            }
            if (matches.size() > 1) throw new IllegalArgumentException("模型 ID 属于多个供应商，请使用 /model <provider>/<模型ID>");
            if (matches.size() == 1) return new ModelSelection(matches.get(0), value, true);
        }
        return resolveModelSelection(raw);
    }

    static ModelSelection resolveModelSelection(String raw) {
        String value = raw == null ? "" : raw.trim();
        String normalized = value.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "glm" -> new ModelSelection("glm", "glm-5.1", true);
            case "deepseek" -> new ModelSelection("deepseek", null, false);
            case "hunyuan", "hy4", "tokenhub", "tencent-hunyuan", "tencent_hunyuan" ->
                    new ModelSelection("hunyuan", null, false);
            case "hy4-preview" -> new ModelSelection("hunyuan", value, true);
            case "step", "stepfun", "step-fun" -> new ModelSelection("step", null, false);
            case "kimi", "moonshot", "moonshotai", "moonshot-ai" -> new ModelSelection("kimi", null, false);
            case "freellmapi", "free-llm-api", "free_llm_api", "freellm", "free-llm" ->
                    new ModelSelection("freellmapi", null, false);
            case "xfyun", "xfyun-maas", "xfyun_maas", "iflytek", "iflytek-maas", "iflytek_maas", "maas" ->
                    new ModelSelection("xfyun", null, false);
            case "agnes", "agnes-ai", "agnes_ai", "sapiens", "sapiens-ai", "sapiens_ai" ->
                    new ModelSelection("agnes", null, false);
            default -> {
                if (normalized.startsWith("glm-")) {
                    yield new ModelSelection("glm", value, true);
                }
                if (normalized.startsWith("deepseek")) {
                    yield new ModelSelection("deepseek", value, true);
                }
                if (normalized.startsWith("hy4-") || normalized.startsWith("hunyuan-")) {
                    yield new ModelSelection("hunyuan", value, true);
                }
                if (normalized.startsWith("step")) {
                    yield new ModelSelection("step", value, true);
                }
                if (normalized.startsWith("kimi-") || normalized.startsWith("moonshot-")) {
                    yield new ModelSelection("kimi", value, true);
                }
                if (normalized.startsWith("agnes-")) {
                    yield new ModelSelection("agnes", value, true);
                }
                yield new ModelSelection(normalized, null, false);
            }
        };
    }

    private static PaiCliConfig.ProviderConfig ensureProviderConfig(PaiCliConfig config, String provider) {
        if (config.getProviders() == null) {
            config.setProviders(new LinkedHashMap<>());
        }
        return config.getProviders().computeIfAbsent(provider, ignored -> new PaiCliConfig.ProviderConfig());
    }

    private static void printStartupScreen(PrintStream out, StartupScreenInfo info) {
        for (String line : startupScreenLines(info)) {
            out.println(line);
        }
    }

    static List<String> startupScreenLines(StartupScreenInfo info) {
        List<String> lines = new ArrayList<>(startupBannerLines(info));
        lines.add("");
        return lines;
    }

    static List<String> startupBannerLines() {
        return startupBannerLines(terminalColumns());
    }

    static List<String> startupBannerLines(int columns) {
        return startupBannerLines(new StartupScreenInfo(
                "auto",
                "model",
                0,
                0,
                0,
                0,
                0,
                ""), columns);
    }

    static List<String> startupBannerLines(StartupScreenInfo info) {
        return startupBannerLines(info, terminalColumns());
    }

    static List<String> startupBannerLines(StartupScreenInfo info, int columns) {
        String model = info.model() == null || info.model().isBlank() ? "auto" : info.model();
        String provider = info.provider() == null || info.provider().isBlank() ? "model" : info.provider();
        String mcp = info.mcpTotal() <= 0
                ? "MCP not configured"
                : "MCP " + info.mcpReady() + "/" + info.mcpTotal() + " · " + info.mcpTools() + " tools";
        String skills = info.skillsTotal() <= 0
                ? "0 skills"
                : info.skillsEnabled() + "/" + info.skillsTotal() + " skills";
        int width = Math.max(20, columns);
        List<String> lines = new ArrayList<>();
        lines.addAll(List.of(TerminalTable.wrap("  " + AnsiStyle.emphasis("Simple CLI")
                + "  " + AnsiStyle.subtle("v" + VERSION), width).split("\n")));
        lines.addAll(List.of(TerminalTable.wrap("  " + AnsiStyle.section("READY")
                + "  Model " + model + " (" + provider + ")", width).split("\n")));
        lines.addAll(List.of(TerminalTable.wrap("  " + AnsiStyle.subtle(mcp + "  ·  " + skills), width).split("\n")));
        lines.add("");
        String shortcut = width < 45
                ? "  / 命令 · @path 文件"
                : "  / 命令  ·  @path 文件  ·  @image: 图片";
        lines.addAll(List.of(TerminalTable.wrap(shortcut, width).split("\n")));
        if (width >= 58) {
            lines.add("  " + AnsiStyle.subtle("输入任务并按 Enter 开始 · Shift+Tab 切换模式"));
        } else {
            lines.addAll(List.of(TerminalTable.wrap("  " + AnsiStyle.subtle("输入任务并按 Enter 开始"), width).split("\n")));
        }
        if (info.note() != null && !info.note().isBlank()) {
            lines.add("");
            for (String line : TerminalTable.wrap(info.note().strip(), width).split("\n")) {
                lines.add(AnsiStyle.subtle(line));
            }
        }
        return lines;
    }

    static McpConfigBootstrapResult ensureDefaultMcpConfig(Path userHome) throws IOException {
        Path configFile = userHome.resolve(".paicli").resolve("mcp.json");
        if (Files.notExists(configFile)) {
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, DEFAULT_CHROME_DEVTOOLS_MCP_JSON);
            return new McpConfigBootstrapResult(true,
                    "✅ 已创建默认 MCP 配置: " + configFile
                            + "\n   默认启用 chrome-devtools（isolated 模式）。");
        }
        String content = Files.readString(configFile);
        if (!content.contains("\"chrome-devtools\"")) {
            return new McpConfigBootstrapResult(false,
                    "ℹ️ 检测到 ~/.paicli/mcp.json 未配置 chrome-devtools，建议参考 README 添加浏览器 MCP server。");
        }
        return new McpConfigBootstrapResult(false, "");
    }

    private static String formatMemoryEntries(String title, List<MemoryEntry> entries, MemoryManager memoryManager) {
        StringBuilder sb = new StringBuilder(title).append("：\n");
        if (entries == null || entries.isEmpty()) {
            return sb.append("📭 没有匹配的长期记忆。").toString();
        }
        for (MemoryEntry entry : entries) {
            String scope = LongTermMemory.scopeOf(entry);
            String project = entry.getMetadata().get("project");
            sb.append("- ")
                    .append(entry.getId())
                    .append(" [").append(scope).append("]");
            if ("project".equals(scope) && project != null && !project.isBlank()) {
                sb.append(" ").append(shortenPath(project));
            }
            sb.append(" · 写入 ").append(entry.getTimestamp());
            if ("true".equals(entry.getMetadata().get("verification_pending"))) {
                sb.append(" · 尚未经用户核实");
            } else {
                sb.append(" · 最后核实 ").append(entry.getLastVerifiedAt());
            }
            if (memoryManager.isStale(entry)) {
                sb.append(" · ⚠️ 可能已过时");
            }
            if ("true".equals(entry.getMetadata().get("verification_pending"))) {
                sb.append(" · 🔎 自动提取，待核实");
            }
            if ("true".equals(entry.getMetadata().get(ExternalContextTracker.METADATA_FLAG))) {
                sb.append(" · 🌐 写入时会话含外部内容(")
                        .append(entry.getMetadata().getOrDefault(ExternalContextTracker.METADATA_SOURCES, ""))
                        .append(")");
            }
            sb.append("\n").append("  ").append(entry.getContent()).append("\n");
        }
        return sb.toString().trim();
    }

    private static String shortenPath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        try {
            Path p = Path.of(path);
            int count = p.getNameCount();
            if (count <= 3) {
                return path;
            }
            return "..." + File.separator + p.subpath(count - 3, count);
        } catch (Exception e) {
            return path;
        }
    }

    record McpConfigBootstrapResult(boolean created, String message) {
    }

    record ModelSelection(String provider, String model, boolean explicitModel) {
    }

    record ProviderConfigUpdate(String provider, String apiKey, String baseUrl, String model, String loraId,
                                boolean setDefault, String error) {
        static ProviderConfigUpdate error(String error) {
            return new ProviderConfigUpdate(null, null, null, null, null, false, error);
        }
    }

}
