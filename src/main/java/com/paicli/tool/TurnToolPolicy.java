package com.paicli.tool;
import com.paicli.observability.LangfuseTelemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import com.paicli.memory.ExplicitMemoryHints;
import com.paicli.memory.ExternalContextTracker;
import com.paicli.tool.ToolRegistry.ToolExecutionResult;
import com.paicli.tool.ToolRegistry.ToolInvocation;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, per-top-level-turn tool boundary.
 *
 * <p>The policy is built from the text the user actually submitted, never from
 * expanded {@code @path} / MCP resource bodies or planner-generated tasks. A URL
 * may be fetched or navigated only when it came from that submitted text or from
 * a {@code web_search} result completed in the same isolated execution branch.
 * Fetch and browser output never grant authority to visit additional URLs.</p>
 */
public final class TurnToolPolicy {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)https?://[^\\s<>\\\"'`]+");
    private static final Pattern EXPLICIT_NO_WEB = Pattern.compile(
            "(?i)(?:请\\s*)?(?:不要|不用|别|禁止|无需|请勿|不许|不需要|不必)"
                    + "(?:\\s*再)?(?:\\s*(?:使用|调用|用))?\\s*"
                    + "(?:联网|上网|搜索|搜|查找|访问网页|打开网页|浏览网页|浏览器)"
                    + "|(?:请\\s*)?(?:仅|只)(?:使用|用|根据|基于)?\\s*"
                    + "(?:我(?:所)?提供的|本地|已有|现有|提供的)"
                    + "(?:资料|内容|文本|文件|上下文)"
                    + "|(?:please\\s+)?(?:do\\s+not|don't)\\s+"
                    + "(?:browse|search|use\\s+(?:the\\s+)?(?:web|browser)|open\\s+the\\s+browser)"
                    + "|without\\s+(?:browsing|web\\s+search)|offline\\s+only|no\\s+web");
    private static final Pattern REQUEST_PREFIX = Pattern.compile(
            "^(?:请|请你|请帮忙|帮我|帮忙|麻烦|能否|能不能|可以帮我|我想让你|我需要你|给我)" );
    private static final Pattern CHINESE_WEB_ACTION = Pattern.compile(
            "^(?:先|再|然后|接着|顺便|同时|并且?)?(?:"
                    + "(?:搜索|搜)(?:一下|下|这个|这篇|该|标题|原文|资料|最新|关于|关键词|\\s|：|:|$)"
                    + "|(?:查一下|查查|查找|查询|查证|检索)(?:一下|下|这个|这篇|该|标题|原文|资料|最新|关于|关键词|后|\\s|：|:|$)"
                    + "|(?:打开|访问|浏览|阅读)(?:一下)?(?:这个|这篇|该|上面|上述|链接|网页|页面|网站|公众号文章|文章)"
                    + "|(?:打开|访问|浏览|阅读|fetch|browse)\\s*https?://)" );
    private static final Pattern HEADLINE_SUFFIX = Pattern.compile(
            "(?i)[（(【\\[].{0,12}(?:附|含).{0,24}(?:面试题|源码|答案|资料|清单|PDF|福利).*[）)】\\]]$");
    private static final Pattern NETWORK_COMMAND = Pattern.compile(
            "(?i)(?:^|[;&|\\s])(?:curl|wget|httpie|lynx|ssh|scp|sftp|telnet|nc)(?:$|\\s)"
                    + "|(?:^|[;&|\\s])git\\s+(?:clone|fetch|pull)(?:$|\\s)"
                    + "|(?:^|[;&|\\s])gh\\s+(?:api|repo\\s+clone)(?:$|\\s)"
                    + "|(?:^|[;&|\\s])(?:npm|pnpm|yarn|pip|pip3)\\s+(?:install|add|i)(?:$|\\s)"
                    + "|https?://|ssh://|git@");
    private static final Pattern WEB_COMMAND = Pattern.compile(
            "(?i)(?:^|[;&|\\s])(?:curl|wget|httpie|lynx)(?:$|\\s)");
    private static final Pattern BROWSER_TARGET = Pattern.compile(
            "(?i)(?:浏览器|当前页面|当前网页|当前标签页|已打开的页面|标签页|页签|chrome|browser|\\btab\\b)");
    private static final Pattern BROWSER_READ_REQUEST = Pattern.compile(
            "(?i)^(?:先|再|然后|接着)?(?:用|使用)?(?:浏览器|chrome|browser).{0,20}"
                    + "(?:打开|访问|浏览|查看|看看|读取|检查|分析|搜索|截图|列出|页面|标签页)"
                    + "|^(?:先|再|然后|接着)?(?:打开|访问|浏览|查看|看看|读取|检查|截图|搜索|列出).{0,20}"
                    + "(?:浏览器|当前页面|当前网页|当前标签页|标签页|页签|chrome|browser|\\btab\\b)" );
    private static final Pattern BROWSER_INTERACT_REQUEST = Pattern.compile(
            "(?i)^(?:先|再|然后|接着)?(?:用|使用)?(?:浏览器|chrome|browser).{0,20}"
                    + "(?:点击|填写|填入|提交|上传|操作|登录|关闭|切换|输入|新建|创建)"
                    + "|^(?:先|再|然后|接着)?(?:点击|填写|填入|提交|上传|操作|登录|关闭|切换|输入|新建|创建).{0,20}"
                    + "(?:浏览器|当前页面|当前网页|当前标签页|标签页|页签|chrome|browser|\\btab\\b)" );
    private static final Pattern BROWSER_HISTORY_REQUEST = Pattern.compile(
            "(?i)(?:浏览器|chrome|browser)?\\s*(?:后退|前进|刷新|重新加载|返回上一页|回到上一页)"
                    + "|(?:go\\s+back|go\\s+forward|reload|refresh)(?:\\s+(?:the\\s+)?(?:browser|page|tab))?");
    private static final Pattern BROWSER_TAB_REQUEST = Pattern.compile(
            "(?i)(?:列出|查看|看看|切换|选择|新建|创建|关闭).{0,16}(?:浏览器)?(?:标签页|页签|tab)"
                    + "|(?:标签页|页签|tab).{0,16}(?:列表|切换|选择|新建|创建|关闭)");
    private static final Pattern BROWSER_CODE_REFERENCE = Pattern.compile(
            "(?i)(?:browser|chrome|浏览器).{0,20}(?:模块|代码|源码|类|测试|MCP|接口|实现)"
                    + "|(?:模块|代码|源码|类|测试|MCP|接口|实现).{0,20}(?:browser|chrome|浏览器)" );
    private static final Pattern EXISTING_BROWSER_CONTEXT = Pattern.compile(
            "(?i)(?:当前|正在|已打开|现有|活动的)(?:的)?(?:浏览器)?(?:页面|网页|标签页|页签)"
                    + "|(?:current|active|already[ -]open|existing)\\s+(?:page|tab|browser)" );
    private static final Pattern BROWSER_CONNECT_REQUEST = Pattern.compile(
            "(?i)(?:连接|接入|复用|切换到).{0,16}(?:浏览器|chrome|shared)"
                    + "|(?:browser\\s+connect|connect.{0,16}(?:browser|chrome))" );
    private static final Pattern BROWSER_DISCONNECT_REQUEST = Pattern.compile(
            "(?i)(?:断开|退出|切回).{0,16}(?:浏览器|chrome|isolated)"
                    + "|(?:browser\\s+disconnect|disconnect.{0,16}(?:browser|chrome))" );
    private static final Pattern BROWSER_STATUS_REQUEST = Pattern.compile(
            "(?i)(?:浏览器|chrome|browser).{0,12}(?:状态|模式|status)"
                    + "|(?:状态|模式|status).{0,12}(?:浏览器|chrome|browser)" );
    private static final Pattern BROWSER_AUTH_REQUIRED = Pattern.compile(
            "(?i)^(?:#\\s*)?(?:(?:当前|该|此)?页面.{0,40}(?:请|需要|必须|尚未|未)(?:登录|登陆)"
                    + "|(?:请|需要|必须|尚未|未)(?:登录|登陆)(?:后)?(?:才能|以便)?(?:访问|继续)?"
                    + "|(?:登录|登陆)(?:页面|页)|权限不足|无权访问|未经授权"
                    + "|authentication required|unauthorized|forbidden|permission denied)" );
    private static final Set<String> BROWSER_NAVIGATION_TOOLS = Set.of(
            "navigate_page", "new_page", "navigate", "navigate_back", "open");
    private static final Set<String> BROWSER_NEW_PAGE_TOOLS = Set.of("new_page", "open");
    private static final Set<String> BROWSER_SESSION_TOOLS = Set.of(
            "list_pages", "select_page", "browser_tabs", "tabs");
    private static final Set<String> BROWSER_CURRENT_PAGE_READ_TOOLS = Set.of(
            "wait_for", "take_snapshot", "take_screenshot",
            "list_console_messages", "get_console_message", "list_network_requests", "get_network_request",
            "snapshot", "find", "console_messages", "network_requests", "network_request", "screenshot");
    private static final Set<String> BROWSER_INTERACTION_TOOLS = Set.of(
            "click", "drag", "fill", "fill_form", "handle_dialog", "hover", "press_key",
            "resize_page", "resize", "upload_file", "upload_files", "file_upload", "drop",
            "evaluate_script", "evaluate", "select_option", "type", "type_text", "click_at",
            "close_page", "close");

    /** 输入带明确的标题 / 摘录标记且没有提出请求：本轮只收掉联网工具，本地工具照常开放。 */
    private final boolean headlineOnly;
    private final boolean explicitNoWeb;
    private final boolean webForbidden;
    private final BrowserIntent browserIntent;
    private final boolean existingBrowserContextRequested;
    private final boolean browserTabManagementRequested;
    private final boolean explicitBrowserConnectRequested;
    private final boolean explicitBrowserDisconnectRequested;
    private final boolean explicitBrowserStatusRequested;
    private final Set<String> groundedUrls = ConcurrentHashMap.newKeySet();
    private final Set<String> searchResultUrls = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean browserContextEstablished = new AtomicBoolean(false);
    private final AtomicBoolean browserAuthenticationRequired = new AtomicBoolean(false);
    private final AtomicBoolean sharedBrowserConnected = new AtomicBoolean(false);
    private final AtomicBoolean agentOwnedBrowserPage = new AtomicBoolean(false);
    private final BrowserLeaseCoordinator browserLeaseCoordinator;
    private boolean browserLeaseHeld;
    /** 顶层用户原文是否明确要求记住/保存长期记忆（记一下、记住、以后记得等）。 */
    private final boolean explicitMemoryRequested;
    /** 本执行分支或所在会话是否已经把外部内容（Web / 浏览器 / MCP / curl）带进上下文。 */
    private final AtomicBoolean externalContentConsumed = new AtomicBoolean(false);
    private volatile boolean memoryGuardEnabled = ExternalContextTracker.guardEnabledByConfiguration();

    private TurnToolPolicy(String submittedUserInput, boolean explicitTask,
                           boolean sharedBrowserSession, boolean agentOwnedCurrentPage) {
        String input = submittedUserInput == null ? "" : submittedUserInput.trim();
        this.explicitNoWeb = EXPLICIT_NO_WEB.matcher(input).find();
        String headlineInput = explicitNoWeb ? stripNoWebConstraints(input) : input;
        // 自然语言判断只用来收紧：命中高精度的标题标记才收掉联网工具；没命中时保持完整工具集，
        // 漏判的代价是模型多一次只读搜索，而不是正常任务因为缺工具静默失败
        this.headlineOnly = !explicitTask && looksLikeHeadlineOnly(headlineInput);
        this.webForbidden = explicitNoWeb || headlineOnly || looksLikeLocalTextTransform(input);
        this.browserIntent = detectBrowserIntent(input);
        this.browserTabManagementRequested = BROWSER_TAB_REQUEST.matcher(input).find();
        this.existingBrowserContextRequested = EXISTING_BROWSER_CONTEXT.matcher(input).find()
                || BROWSER_HISTORY_REQUEST.matcher(input).find();
        this.explicitBrowserConnectRequested = BROWSER_CONNECT_REQUEST.matcher(input).find();
        this.explicitBrowserDisconnectRequested = BROWSER_DISCONNECT_REQUEST.matcher(input).find();
        this.explicitBrowserStatusRequested = BROWSER_STATUS_REQUEST.matcher(input).find();
        this.explicitMemoryRequested = ExplicitMemoryHints.hasExplicitRememberIntent(input);
        this.browserLeaseCoordinator = new BrowserLeaseCoordinator();
        this.sharedBrowserConnected.set(sharedBrowserSession);
        this.agentOwnedBrowserPage.set(sharedBrowserSession && agentOwnedCurrentPage);
        extractUrls(input).stream().map(TurnToolPolicy::normalizeUrl).forEach(groundedUrls::add);
    }

    private TurnToolPolicy(TurnToolPolicy source) {
        this.headlineOnly = source.headlineOnly;
        this.explicitNoWeb = source.explicitNoWeb;
        this.webForbidden = source.webForbidden;
        this.browserIntent = source.browserIntent;
        this.existingBrowserContextRequested = source.existingBrowserContextRequested;
        this.browserTabManagementRequested = source.browserTabManagementRequested;
        this.explicitBrowserConnectRequested = source.explicitBrowserConnectRequested;
        this.explicitBrowserDisconnectRequested = source.explicitBrowserDisconnectRequested;
        this.explicitBrowserStatusRequested = source.explicitBrowserStatusRequested;
        this.explicitMemoryRequested = source.explicitMemoryRequested;
        this.externalContentConsumed.set(source.externalContentConsumed.get());
        this.memoryGuardEnabled = source.memoryGuardEnabled;
        this.browserLeaseCoordinator = source.browserLeaseCoordinator;
        this.groundedUrls.addAll(source.groundedUrls);
        this.searchResultUrls.addAll(source.searchResultUrls);
        this.browserContextEstablished.set(source.browserContextEstablished.get());
        this.browserAuthenticationRequired.set(source.browserAuthenticationRequired.get());
        this.sharedBrowserConnected.set(source.sharedBrowserConnected.get());
        this.agentOwnedBrowserPage.set(source.agentOwnedBrowserPage.get());
    }

    /** Infer whether an ordinary ReAct input contains an actual task. */
    public static TurnToolPolicy fromUserInput(String submittedUserInput) {
        return fromUserInput(submittedUserInput, false, false);
    }

    public static TurnToolPolicy fromUserInput(String submittedUserInput,
                                               boolean sharedBrowserSession,
                                               boolean agentOwnedCurrentPage) {
        return new TurnToolPolicy(
                submittedUserInput, false, sharedBrowserSession, agentOwnedCurrentPage);
    }

    /** Use for an already explicit task envelope while retaining no-web and URL rules. */
    public static TurnToolPolicy forExplicitTask(String submittedUserInput) {
        return forExplicitTask(submittedUserInput, false, false);
    }

    public static TurnToolPolicy forExplicitTask(String submittedUserInput,
                                                 boolean sharedBrowserSession,
                                                 boolean agentOwnedCurrentPage) {
        return new TurnToolPolicy(
                submittedUserInput, true, sharedBrowserSession, agentOwnedCurrentPage);
    }

    /** Isolates URL discovery state for a parallel plan task or team worker. */
    public TurnToolPolicy fork() {
        return new TurnToolPolicy(this);
    }

    /**
     * Creates an isolated child branch that inherits only typed URL provenance
     * from completed dependency branches, never URLs parsed from their prose.
     */
    public TurnToolPolicy forkWithTrustedUrls(Collection<TrustedUrlContext> dependencyContexts) {
        TurnToolPolicy child = fork();
        if (dependencyContexts != null) {
            dependencyContexts.stream()
                    .filter(Objects::nonNull)
                    .flatMap(context -> context.urls().stream())
                    .forEach(url -> {
                        child.groundedUrls.add(url);
                        child.searchResultUrls.add(url);
                    });
        }
        return child;
    }

    /** Opaque, immutable provenance produced by successful web_search calls. */
    public TrustedUrlContext trustedUrlContext() {
        return new TrustedUrlContext(searchResultUrls);
    }

    /**
     * Filters the tool schema before the LLM call and snapshots the exact names,
     * grounded URLs, and browser state advertised for the matching response.
     */
    public ToolExposure expose(List<LlmClient.Tool> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return ToolExposure.none();
        }

        boolean hasGroundedUrl = !groundedUrls.isEmpty();
        boolean hasBrowserContext = browserContextEstablished.get();
        List<LlmClient.Tool> visible = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (LlmClient.Tool definition : definitions) {
            if (definition == null || definition.name() == null || definition.name().isBlank()) {
                continue;
            }
            String name = definition.name();
            if (explicitNoWeb && isPotentialExternalTool(definition)) {
                continue;
            }
            if (webForbidden && isExternalWebTool(name)) {
                continue;
            }
            if (!hasGroundedUrl && isWebFetchTool(name)) {
                continue;
            }
            if (isBrowserTool(name)
                    && !browserToolVisible(name, hasGroundedUrl, hasBrowserContext)) {
                continue;
            }
            visible.add(definition);
            names.add(name);
        }
        return new ToolExposure(
                visible,
                names,
                Set.copyOf(groundedUrls),
                hasBrowserContext);
    }

    /**
     * Returns only calls that pass the same pre-execution gate. Renderers use this
     * view so a rejected hallucinated call is not presented as an actual fetch.
     */
    public List<LlmClient.ToolCall> visibleToolCalls(List<LlmClient.ToolCall> toolCalls,
                                                     ToolExposure exposure) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }
        ToolExposure effectiveExposure = exposure == null ? ToolExposure.none() : exposure;
        return toolCalls.stream()
                .filter(Objects::nonNull)
                .filter(toolCall -> toolCall.function() != null)
                .filter(toolCall -> authorize(new ToolInvocation(
                        toolCall.id(),
                        toolCall.function().name(),
                        toolCall.function().arguments()), effectiveExposure).allowed())
                .toList();
    }

    /** Execute allowed calls, return synthetic tool results for denials, and learn typed search-result URLs. */
    public List<ToolExecutionResult> execute(ToolRegistry registry,
                                             List<ToolInvocation> invocations,
                                             ToolExposure exposure) {
        Objects.requireNonNull(registry, "registry");
        if (invocations == null || invocations.isEmpty()) {
            return List.of();
        }
        ToolExposure effectiveExposure = exposure == null ? ToolExposure.none() : exposure;
        ExternalContextTracker sessionTracker = registry.getExternalContextTracker();
        if (sessionTracker != null) {
            memoryGuardEnabled = sessionTracker.guardEnabled();
            if (sessionTracker.hasExternalContext()) {
                externalContentConsumed.set(true);
            }
        }

        List<ToolExecutionResult> merged = new ArrayList<>(java.util.Collections.nCopies(invocations.size(), null));
        List<ToolInvocation> allowed = new ArrayList<>();
        List<Integer> allowedIndexes = new ArrayList<>();
        for (int i = 0; i < invocations.size(); i++) {
            ToolInvocation invocation = invocations.get(i);
            Decision decision = authorize(invocation, effectiveExposure);
            if (decision.allowed()) {
                allowed.add(invocation);
                allowedIndexes.add(i);
            } else {
                merged.set(i, blockedResult(invocation, decision));
            }
        }

        if (!allowed.isEmpty()) {
            if (allowed.stream().anyMatch(invocation -> isBrowserTool(invocation.name()))) {
                acquireBrowserLease();
            }
            List<ToolExecutionResult> executed = registry.executeTools(allowed);
            int resultCount = executed == null ? 0 : executed.size();
            for (int i = 0; i < allowed.size(); i++) {
                ToolExecutionResult result = i < resultCount
                        ? executed.get(i)
                        : missingResult(allowed.get(i));
                // 先让策略观察原始结果，再换成可能已卸载的上下文视图。
                observe(result);
                observeExternalContent(allowed.get(i), result, sessionTracker);
                merged.set(allowedIndexes.get(i), registry.offloadForContext(result));
            }
        }
        List<ToolExecutionResult> completed = List.copyOf(merged);
        registry.onPolicyToolResults(completed);
        LangfuseTelemetry.recordTools(completed);
        return completed;
    }

    /** Release this branch's browser lease after its complete task lifecycle. */
    public void releaseBrowserLease() {
        if (!browserLeaseHeld) {
            return;
        }
        browserLeaseCoordinator.lock.unlock();
        browserLeaseHeld = false;
    }

    private void acquireBrowserLease() {
        if (browserLeaseHeld) {
            return;
        }
        browserLeaseCoordinator.lock.lock();
        browserLeaseHeld = true;
    }

    private Decision authorize(ToolInvocation invocation, ToolExposure exposure) {
        if (invocation == null || invocation.name() == null || invocation.name().isBlank()) {
            return Decision.deny(ReasonCode.TOOL_NOT_ADVERTISED, "模型返回了无效工具调用。");
        }
        String name = invocation.name();
        boolean networkCommand = isNetworkCommand(invocation);
        if (headlineOnly && (isExternalWebTool(name) || networkCommand)) {
            return Decision.deny(ReasonCode.NO_ACTION,
                    "当前输入看起来只是一个标题或摘录，没有说明要做什么。本轮不开放联网工具；请先询问用户希望如何处理。");
        }
        if (webForbidden && (isExternalWebTool(name) || networkCommand)) {
            return Decision.deny(ReasonCode.WEB_FORBIDDEN,
                    "用户明确要求不要联网，或当前请求只需要处理已有文本。请仅使用本地上下文。");
        }

        List<String> argumentUrls = extractInvocationUrls(invocation.argumentsJson());
        if (isBrowserHistoryNavigation(invocation)
                && !exposure.browserContextEstablished()
                && !existingBrowserContextRequested) {
            return Decision.deny(ReasonCode.TOOL_NOT_ADVERTISED,
                    "当前执行分支尚未建立可操作的浏览器页面上下文。");
        }
        if (isBrowserTabsTool(name)) {
            String action = jsonText(invocation.argumentsJson(), "action").trim().toLowerCase(Locale.ROOT);
            if (!Set.of("list", "select", "new", "close").contains(action)) {
                return Decision.deny(ReasonCode.TOOL_NOT_ADVERTISED,
                        "浏览器标签页操作缺少可验证的 action。");
            }
            if (Set.of("new", "close").contains(action) && browserIntent != BrowserIntent.INTERACT) {
                return Decision.deny(ReasonCode.TOOL_NOT_ADVERTISED,
                        "当前顶层用户原文只授权读取浏览器，未授权新建或关闭标签页。");
            }
            if ("new".equals(action) && argumentUrls.isEmpty()) {
                return Decision.deny(ReasonCode.UNGROUNDED_URL,
                        "新建浏览器标签页没有可信 URL 来源。");
            }
        }
        if (isWebFetchTool(name) && argumentUrls.isEmpty()) {
            return Decision.deny(ReasonCode.UNGROUNDED_URL,
                    "web_fetch 的 URL 没有可信来源。需要定位页面时先使用 web_search，不能猜测 URL。");
        }
        if (isBrowserNavigationTool(name)
                && argumentUrls.isEmpty()
                && !isBrowserHistoryNavigation(invocation)) {
            return Decision.deny(ReasonCode.UNGROUNDED_URL,
                    "浏览器导航没有可验证的 URL。需要定位页面时先使用 web_search，不能猜测 URL。");
        }
        if ((isWebFetchTool(name) || isBrowserTool(name) || networkCommand)
                && !argumentUrls.isEmpty()
                && argumentUrls.stream().map(TurnToolPolicy::normalizeUrl)
                .anyMatch(url -> !exposure.groundedUrls().contains(url))) {
            return Decision.deny(ReasonCode.UNGROUNDED_URL,
                    "目标 URL 不在当前顶层用户输入或本轮可信发现结果中。需要定位页面时先使用 web_search，不能猜测 URL。");
        }
        if (isWebCommand(invocation) && argumentUrls.isEmpty()) {
            return Decision.deny(ReasonCode.UNGROUNDED_URL,
                    "联网命令没有可验证的 URL 来源。请使用 web_search 获取入口，不能用命令绕过 URL 校验。");
        }
        if (!exposure.advertisedNames().contains(name)) {
            return Decision.deny(ReasonCode.TOOL_NOT_ADVERTISED,
                    "该工具未向本轮模型开放。不要伪造或绕过当前工具边界。");
        }
        if ("save_memory".equals(name)
                && memoryGuardEnabled
                && externalContentConsumed.get()
                && !explicitMemoryRequested) {
            return Decision.deny(ReasonCode.MEMORY_EXTERNAL_CONTEXT,
                    "当前会话已读取网页、浏览器或 MCP 等外部内容，而用户本轮没有明确要求记住任何内容。"
                            + "不要把外部内容里的信息自动写入长期记忆；如确有需要，请询问用户是否要保存。");
        }
        return Decision.allow();
    }

    /**
     * 外部工具的结果（无论成功与否，错误文本也可能夹带远端内容）进入上下文后，本分支和
     * 会话都标记为“接触过外部内容”，此后 save_memory 需要用户原文明确要求。
     */
    private void observeExternalContent(ToolInvocation invocation, ToolExecutionResult result,
                                        ExternalContextTracker sessionTracker) {
        if (result == null) {
            return;
        }
        String source = ExternalContextTracker.isExternalContentTool(result.name())
                ? result.name()
                : (isWebCommand(invocation) ? "execute_command(curl/wget)" : null);
        if (source == null) {
            return;
        }
        externalContentConsumed.set(true);
        if (sessionTracker != null) {
            sessionTracker.record(source);
        }
    }

    /** 当前分支是否已接触外部内容（含会话中更早的轮次）；供测试和诊断使用。 */
    /** 本轮是否因为输入只是标题 / 摘录而收掉了联网工具；调用方据此提示用户补充意图。 */
    public boolean headlineOnly() {
        return headlineOnly;
    }

    public boolean hasConsumedExternalContent() {
        return externalContentConsumed.get();
    }

    private void observe(ToolExecutionResult result) {
        if (!isSuccessfulResult(result)) {
            return;
        }
        if (isBrowserNavigationTool(result.name())) {
            browserContextEstablished.set(true);
            if (isBrowserNewPageTool(result.name()) && sharedBrowserConnected.get()) {
                agentOwnedBrowserPage.set(true);
            }
        }
        if (isBrowserPageSelection(result)) {
            browserContextEstablished.set(true);
        }
        if (isBrowserCurrentPageReadTool(result.name())
                && BROWSER_AUTH_REQUIRED.matcher(result.result()).find()) {
            browserAuthenticationRequired.set(true);
        }
        if (isBrowserConnectTool(result.name()) && isSuccessfulBrowserControl(result.result())) {
            sharedBrowserConnected.set(true);
            agentOwnedBrowserPage.set(false);
            browserContextEstablished.set(false);
            browserAuthenticationRequired.set(false);
        } else if (isBrowserDisconnectTool(result.name()) && isSuccessfulBrowserControl(result.result())) {
            sharedBrowserConnected.set(false);
            agentOwnedBrowserPage.set(false);
            browserContextEstablished.set(false);
        }
        if (!isWebSearchTool(result.name())) {
            return;
        }
        observeSearchResultUrls(result);
    }

    /** Trust only URL metadata emitted from a structured search provider. */
    private void observeSearchResultUrls(ToolExecutionResult result) {
        result.discoveredUrls().stream()
                .filter(TurnToolPolicy::isHttpUrl)
                .map(TurnToolPolicy::normalizeUrl)
                .forEach(url -> {
                    groundedUrls.add(url);
                    searchResultUrls.add(url);
                });
    }

    private static boolean isSuccessfulResult(ToolExecutionResult result) {
        if (result == null || !result.successful() || result.timedOut()
                || result.result() == null || result.result().isBlank()) {
            return false;
        }
        String text = result.result().stripLeading();
        return !text.startsWith("🛡️")
                && !text.startsWith("❌")
                && !text.startsWith("[HITL]")
                && !text.startsWith("工具执行失败")
                && !text.startsWith("工具执行超时")
                && !text.startsWith("未知工具")
                && !text.startsWith("搜索失败")
                && !text.startsWith("抓取失败");
    }

    private static ToolExecutionResult blockedResult(ToolInvocation invocation, Decision decision) {
        return new ToolExecutionResult(
                invocation == null ? "" : invocation.id(),
                invocation == null ? "" : invocation.name(),
                invocation == null ? "{}" : invocation.argumentsJson(),
                "🛡️ 工具调用已拒绝 [" + decision.code() + "]: " + decision.message(),
                0,
                false,
                List.of());
    }

    private static ToolExecutionResult missingResult(ToolInvocation invocation) {
        return new ToolExecutionResult(
                invocation.id(),
                invocation.name(),
                invocation.argumentsJson(),
                "工具执行失败: 执行器未返回对应结果",
                0,
                false,
                List.of());
    }

    private static boolean looksLikeHeadlineOnly(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        if (!extractUrls(input).isEmpty() || REQUEST_PREFIX.matcher(input).find()) {
            return false;
        }
        return looksLikeHeadline(input);
    }

    private static String stripNoWebConstraints(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        return EXPLICIT_NO_WEB.matcher(input)
                .replaceAll(" ")
                .replaceFirst("^[\\s，,；;。：:]+", "")
                .trim();
    }

    private static boolean looksLikeHeadline(String input) {
        String text = input == null ? "" : input.trim();
        if (text.isEmpty()) {
            return false;
        }
        if (HEADLINE_SUFFIX.matcher(text).find()
                || text.matches("^[《【].+[》】]$")
                || text.matches("^#{1,6}\\s+.+$")) {
            return true;
        }
        return text.length() <= 220
                && !text.contains("\n")
                && text.matches("^.{2,48}[：:].{4,180}[？?]$");
    }

    private static boolean looksLikeLocalTextTransform(String input) {
        if (input == null || input.isBlank() || hasExplicitWebRequest(input)) {
            return false;
        }
        String lower = input.toLowerCase(Locale.ROOT);
        boolean textTarget = lower.contains("标题") || lower.contains("这段") || lower.contains("文本")
                || lower.contains("文案") || lower.contains("句子") || lower.contains("headline")
                || lower.contains("title") || lower.contains("this text");
        boolean transform = Pattern.compile("(?i)(polish|rewrite|edit|translate|remove|delete|rate)")
                .matcher(lower).find();
        transform = transform || lower.contains("润色") || lower.contains("改写") || lower.contains("翻译")
                || lower.contains("删除") || lower.contains("去掉") || lower.contains("替换")
                || lower.contains("打分") || lower.contains("评价") || lower.contains("点评");
        return textTarget && transform;
    }

    private static boolean hasExplicitWebRequest(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        for (String clause : input.split("[，,；;。\\n]+")) {
            String body = stripCommandLead(clause);
            if (CHINESE_WEB_ACTION.matcher(body).find()
                    || Pattern.compile("(?i)^(?:search(?:\\s+the\\s+web)?|browse|fetch|look\\s+up|find\\s+online)\\b")
                    .matcher(body).find()
                    || body.matches("^(?:先|再|然后|接着)?(?:联网|上网)(?:搜索|查找|查询|查证|检索|浏览)?.*$")) {
                return true;
            }
        }
        return false;
    }

    private static String stripCommandLead(String input) {
        String body = input == null ? "" : input.trim();
        body = body.replaceFirst("^(?:补充要求|用户补充|追加要求|要求)[：:]\\s*", "");
        Matcher request = REQUEST_PREFIX.matcher(body);
        if (request.find()) {
            body = body.substring(request.end()).stripLeading();
        }
        return body.replaceFirst("^(?:先|再|然后|接着|顺便|同时|并且?)+", "").stripLeading();
    }

    private static BrowserIntent detectBrowserIntent(String input) {
        if (input == null || input.isBlank()) {
            return BrowserIntent.NONE;
        }
        if (BROWSER_HISTORY_REQUEST.matcher(input).find()) {
            return BrowserIntent.READ;
        }
        if (!BROWSER_TARGET.matcher(input).find()) {
            return BrowserIntent.NONE;
        }
        boolean explicitUse = Pattern.compile("(?i)(?:用|使用|通过)\\s*(?:浏览器|chrome|browser)")
                .matcher(input).find();
        if (!explicitUse && BROWSER_CODE_REFERENCE.matcher(input).find()) {
            return BrowserIntent.NONE;
        }
        for (String clause : input.split("[，,；;。\\n]+")) {
            String body = stripCommandLead(clause);
            if (BROWSER_INTERACT_REQUEST.matcher(body).find()) {
                return BrowserIntent.INTERACT;
            }
            if (BROWSER_READ_REQUEST.matcher(body).find()) {
                return BrowserIntent.READ;
            }
        }
        return explicitUse ? BrowserIntent.READ : BrowserIntent.NONE;
    }

    private boolean browserToolVisible(String name, boolean hasGroundedUrl, boolean hasBrowserContext) {
        boolean sharedChrome = sharedBrowserConnected.get()
                && normalizedName(name).startsWith("mcp__chrome-devtools__");
        boolean ownedSharedPage = agentOwnedBrowserPage.get();
        boolean readablePage = hasBrowserContext || existingBrowserContextRequested;
        boolean mutablePage = sharedChrome
                ? ownedSharedPage && (hasBrowserContext || existingBrowserContextRequested)
                : hasBrowserContext || existingBrowserContextRequested;
        if (isBrowserStatusTool(name)) {
            return explicitBrowserStatusRequested
                    || hasBrowserContext
                    || sharedBrowserConnected.get();
        }
        if (isBrowserConnectTool(name)) {
            return !sharedBrowserConnected.get()
                    && (explicitBrowserConnectRequested || browserAuthenticationRequired.get());
        }
        if (isBrowserDisconnectTool(name)) {
            return explicitBrowserDisconnectRequested || sharedBrowserConnected.get();
        }
        if (isBrowserNavigationTool(name)) {
            if (isBrowserNewPageTool(name)) {
                return hasGroundedUrl;
            }
            if ("navigate_back".equals(browserLocalName(name))) {
                return mutablePage;
            }
            return (hasGroundedUrl || mutablePage) && (!sharedChrome || ownedSharedPage || hasBrowserContext);
        }
        if (browserIntent == BrowserIntent.NONE) {
            return hasBrowserContext && isBrowserCurrentPageReadTool(name);
        }
        if (isBrowserSessionTool(name)) {
            return existingBrowserContextRequested || browserTabManagementRequested;
        }
        if (isBrowserCurrentPageReadTool(name)) {
            return readablePage;
        }
        return browserIntent == BrowserIntent.INTERACT
                && mutablePage
                && isBrowserInteractionTool(name);
    }

    private static boolean isPotentialExternalTool(LlmClient.Tool definition) {
        String name = normalizedName(definition.name());
        if (isExternalWebTool(name)) {
            return true;
        }
        String description = definition.description() == null
                ? ""
                : definition.description().toLowerCase(Locale.ROOT);
        return description.contains("互联网") || description.contains("联网")
                || description.contains("网页") || description.contains("browser")
                || description.contains("http") || description.contains(" url");
    }

    private static boolean isExternalWebTool(String name) {
        return isWebSearchTool(name) || isWebFetchTool(name) || isBrowserTool(name);
    }

    private static boolean isWebSearchTool(String name) {
        String lower = normalizedName(name);
        return "web_search".equals(lower) || lower.endsWith("__web_search");
    }

    private static boolean isWebFetchTool(String name) {
        String lower = normalizedName(name);
        return "web_fetch".equals(lower) || lower.endsWith("__web_fetch");
    }

    private static boolean isBrowserTool(String name) {
        String lower = normalizedName(name);
        return lower.startsWith("browser_")
                || lower.startsWith("mcp__chrome-devtools__")
                || lower.startsWith("mcp__browser__")
                || lower.startsWith("mcp__playwright__");
    }

    static boolean isBrowserToolName(String name) {
        return isBrowserTool(name);
    }

    private static boolean isBrowserNavigationTool(String name) {
        return isBrowserTool(name) && BROWSER_NAVIGATION_TOOLS.contains(browserLocalName(name));
    }

    private static boolean isBrowserNewPageTool(String name) {
        return isBrowserTool(name) && BROWSER_NEW_PAGE_TOOLS.contains(browserLocalName(name));
    }

    private static boolean isBrowserConnectTool(String name) {
        return isBrowserTool(name) && "connect".equals(browserLocalName(name));
    }

    private static boolean isBrowserDisconnectTool(String name) {
        return isBrowserTool(name) && "disconnect".equals(browserLocalName(name));
    }

    private static boolean isBrowserStatusTool(String name) {
        return isBrowserTool(name) && "status".equals(browserLocalName(name));
    }

    private static boolean isBrowserSessionTool(String name) {
        return isBrowserTool(name) && BROWSER_SESSION_TOOLS.contains(browserLocalName(name));
    }

    private static boolean isBrowserTabsTool(String name) {
        return isBrowserTool(name) && "tabs".equals(browserLocalName(name));
    }

    private static boolean isBrowserCurrentPageReadTool(String name) {
        return isBrowserTool(name) && BROWSER_CURRENT_PAGE_READ_TOOLS.contains(browserLocalName(name));
    }

    private static boolean isBrowserInteractionTool(String name) {
        return isBrowserTool(name) && BROWSER_INTERACTION_TOOLS.contains(browserLocalName(name));
    }

    private static boolean isBrowserHistoryNavigation(ToolInvocation invocation) {
        if (invocation == null) {
            return false;
        }
        String localName = browserLocalName(invocation.name());
        if ("navigate_back".equals(localName)) {
            return true;
        }
        if (!"navigate_page".equals(localName)) {
            return false;
        }
        String type = jsonText(invocation.argumentsJson(), "type").trim().toLowerCase(Locale.ROOT);
        return Set.of("back", "forward", "reload").contains(type);
    }

    private static boolean isBrowserPageSelection(ToolExecutionResult result) {
        if (result == null || !isBrowserSessionTool(result.name())) {
            return false;
        }
        String localName = browserLocalName(result.name());
        return "select_page".equals(localName)
                || ("tabs".equals(localName)
                && "select".equals(jsonText(result.argumentsJson(), "action")
                .trim().toLowerCase(Locale.ROOT)));
    }

    private static boolean isSuccessfulBrowserControl(String result) {
        String text = result == null ? "" : result.toLowerCase(Locale.ROOT);
        return !text.contains("无法") && !text.contains("失败")
                && !text.contains("error") && !text.contains("failed");
    }

    private static String browserLocalName(String name) {
        String lower = normalizedName(name);
        int marker = lower.lastIndexOf("__");
        if (marker >= 0 && marker + 2 < lower.length()) {
            lower = lower.substring(marker + 2);
        }
        return lower.startsWith("browser_") ? lower.substring("browser_".length()) : lower;
    }

    private static String normalizedName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isNetworkCommand(ToolInvocation invocation) {
        if (invocation == null || !"execute_command".equals(invocation.name())) {
            return false;
        }
        return NETWORK_COMMAND.matcher(jsonText(invocation.argumentsJson(), "command", "cmd")).find();
    }

    private static boolean isWebCommand(ToolInvocation invocation) {
        if (invocation == null || !"execute_command".equals(invocation.name())) {
            return false;
        }
        return WEB_COMMAND.matcher(jsonText(invocation.argumentsJson(), "command", "cmd")).find();
    }

    private static String jsonText(String json, String... names) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            for (String name : names) {
                JsonNode value = root.path(name);
                if (value.isTextual()) {
                    return value.asText();
                }
            }
        } catch (Exception ignored) {
            // Invalid JSON is handled by the ordinary tool execution path.
        }
        return "";
    }

    /** Extract URLs from decoded JSON string values, not from their escaped wire representation. */
    private static List<String> extractInvocationUrls(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> urls = new ArrayList<>();
            collectJsonTextUrls(MAPPER.readTree(json), urls);
            return urls;
        } catch (Exception ignored) {
            return extractUrls(json);
        }
    }

    private static void collectJsonTextUrls(JsonNode node, List<String> urls) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            urls.addAll(extractUrls(node.asText()));
            return;
        }
        if (node.isContainerNode()) {
            node.elements().forEachRemaining(child -> collectJsonTextUrls(child, urls));
        }
    }

    private static List<String> extractUrls(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> urls = new ArrayList<>();
        Matcher matcher = URL_PATTERN.matcher(text);
        while (matcher.find()) {
            String candidate = stripTrailingPunctuation(matcher.group());
            if (!candidate.isBlank()) {
                urls.add(candidate);
            }
        }
        return urls;
    }

    private static String stripTrailingPunctuation(String value) {
        int end = value == null ? 0 : value.length();
        while (end > 0 && ").,;!?，。；！？）]}".indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value == null ? "" : value.substring(0, end);
    }

    private static String normalizeUrl(String value) {
        String candidate = stripTrailingPunctuation(value == null ? "" : value.trim());
        try {
            URI uri = URI.create(candidate);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (scheme.isBlank() || host.isBlank()) {
                return candidate;
            }
            URI normalized = new URI(
                    scheme,
                    uri.getUserInfo(),
                    host,
                    uri.getPort(),
                    uri.getPath(),
                    uri.getQuery(),
                    null);
            String result = normalized.toASCIIString();
            if (result.endsWith("/") && uri.getQuery() == null) {
                result = result.substring(0, result.length() - 1);
            }
            return result;
        } catch (Exception ignored) {
            return candidate;
        }
    }

    private static boolean isHttpUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value.trim());
            return ("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public enum ReasonCode {
        NO_ACTION,
        WEB_FORBIDDEN,
        UNGROUNDED_URL,
        TOOL_NOT_ADVERTISED,
        MEMORY_EXTERNAL_CONTEXT
    }

    /**
     * Cannot be instantiated by callers. Its URLs originate only from this
     * policy's sanitized web_search observation path.
     */
    public static final class TrustedUrlContext {
        private final Set<String> urls;

        private TrustedUrlContext(Collection<String> urls) {
            this.urls = urls == null ? Set.of() : Set.copyOf(urls);
        }

        public Set<String> urls() {
            return urls;
        }
    }

    private static final class BrowserLeaseCoordinator {
        private final ReentrantLock lock = new ReentrantLock(true);
    }

    private enum BrowserIntent {
        NONE,
        READ,
        INTERACT
    }

    private record Decision(boolean allowed, ReasonCode code, String message) {
        static Decision allow() {
            return new Decision(true, null, "");
        }

        static Decision deny(ReasonCode code, String message) {
            return new Decision(false, code, message);
        }
    }

    public record ToolExposure(List<LlmClient.Tool> definitions,
                               Set<String> advertisedNames,
                               Set<String> groundedUrls,
                               boolean browserContextEstablished) {
        public ToolExposure {
            definitions = definitions == null ? List.of() : List.copyOf(definitions);
            advertisedNames = advertisedNames == null ? Set.of() : Set.copyOf(advertisedNames);
            groundedUrls = groundedUrls == null ? Set.of() : Set.copyOf(groundedUrls);
        }

        public static ToolExposure none() {
            return new ToolExposure(List.of(), Set.of(), Set.of(), false);
        }
    }
}
