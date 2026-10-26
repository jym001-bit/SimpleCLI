package com.paicli.tool;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.paicli.llm.LlmClient;
import com.paicli.tool.ToolRegistry.ToolExecutionResult;
import com.paicli.tool.ToolRegistry.ToolInvocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnToolPolicyTest {
    private static final String INCIDENT_TITLE =
            "阿里员工：作为一名合格的375员工，老板在的时候9点走，老板不在的时候6点半走，老板9点前走了那就跟着走（附Agent面试题）";
    private static final String HALLUCINATED_URL =
            "https://mp.weixin.qq.com/s/X1kQ_5tHZgO-zJfQrN1z2g";

    @Test
    void markedHeadlineHidesWebToolsButKeepsLocalTools() {
        TurnToolPolicy policy = TurnToolPolicy.fromUserInput(INCIDENT_TITLE);
        TurnToolPolicy.ToolExposure exposure = policy.expose(definitions());
        RecordingRegistry registry = new RecordingRegistry();

        List<ToolExecutionResult> results = policy.execute(registry, List.of(
                invocation("fetch", "web_fetch", "{\"url\":\"" + HALLUCINATED_URL + "\"}"),
                invocation("search", "web_search", "{\"query\":\"375员工\"}"),
                invocation("file", "read_file", "{\"path\":\"README.md\"}")), exposure);

        assertTrue(policy.headlineOnly());
        assertTrue(exposure.advertisedNames().contains("read_file"));
        assertFalse(exposure.advertisedNames().contains("web_search"));
        assertFalse(exposure.advertisedNames().contains("web_fetch"));
        assertTrue(policy.visibleToolCalls(List.of(
                toolCall("fetch", "web_fetch", "{\"url\":\"" + HALLUCINATED_URL + "\"}")), exposure).isEmpty());
        assertEquals(List.of("read_file"), registry.executed.stream().map(ToolInvocation::name).toList());
        assertTrue(results.get(0).result().contains("[NO_ACTION]"), results.get(0).result());
        assertTrue(results.get(1).result().contains("[NO_ACTION]"), results.get(1).result());
    }

    @Test
    void headlinesNeverLoseLocalToolsOrGainUngroundedFetch() {
        // 自然语言判断只能收紧：标题再像命令，也只影响联网工具；没有可信 URL 时 web_fetch 始终不可见
        List<String> headlines = List.of(
                "打开 Agent 世界的大门：从 Tool Calling 到 MCP",
                "搜索改变编程的方式：AI Agent 实战",
                "最新 Agent 面试题 50 道（2026版）",
                "DeepSeek V4 发布：百万上下文真的有用吗？（附面试题）",
                "DeepSeek V4 发布：百万上下文真的有用吗？");

        for (String headline : headlines) {
            TurnToolPolicy.ToolExposure exposure = TurnToolPolicy.fromUserInput(headline).expose(definitions());
            assertTrue(exposure.advertisedNames().contains("read_file"), headline);
            assertTrue(exposure.advertisedNames().contains("write_file"), headline);
            assertFalse(exposure.advertisedNames().contains("web_fetch"), headline);
        }
    }

    @Test
    void onlyHighPrecisionHeadlineMarkersHideWebSearch() {
        for (String marked : List.of(
                "DeepSeek V4 发布：百万上下文真的有用吗？（附面试题）",
                "DeepSeek V4 发布：百万上下文真的有用吗？",
                "《Agent 工程实践》",
                "# 第一章 ReAct 循环")) {
            TurnToolPolicy policy = TurnToolPolicy.fromUserInput(marked);
            assertTrue(policy.headlineOnly(), marked);
            assertFalse(policy.expose(definitions()).advertisedNames().contains("web_search"), marked);
        }
        for (String task : List.of(
                "进入 demo 目录，编译并运行 Hello.java",
                "1",
                "是的",
                "继续",
                "把 Hello.java 里的 Hello World 改成 Hello PaiCLI",
                "帮我搜一下《Agent 工程实践》的作者")) {
            TurnToolPolicy policy = TurnToolPolicy.fromUserInput(task);
            assertFalse(policy.headlineOnly(), task);
            assertTrue(policy.expose(definitions()).advertisedNames().contains("execute_command"), task);
        }
    }

    @Test
    void explicitSearchCanGroundOnlyTheExactUrlForTheNextIteration() {
        TurnToolPolicy policy = TurnToolPolicy.fromUserInput("帮我搜索这个标题的原文");
        RecordingRegistry registry = new RecordingRegistry();
        registry.searchResult = "1. 目标文章\n   https://example.com/article";
        registry.discoveredUrls = List.of("https://example.com/article");

        TurnToolPolicy.ToolExposure firstExposure = policy.expose(definitions());
        assertTrue(firstExposure.advertisedNames().contains("web_search"));
        assertFalse(firstExposure.advertisedNames().contains("web_fetch"));

        List<ToolExecutionResult> searchResults = policy.execute(registry, List.of(
                invocation("search", "web_search", "{\"query\":\"目标文章\"}")), firstExposure);
        assertEquals(1, searchResults.size());
        assertEquals(1, registry.executed.size());

        TurnToolPolicy.ToolExposure secondExposure = policy.expose(definitions());
        assertTrue(secondExposure.advertisedNames().contains("web_fetch"));

        List<ToolExecutionResult> allowedFetch = policy.execute(registry, List.of(
                invocation("fetch-ok", "web_fetch", "{\"url\":\"https://example.com/article\"}")), secondExposure);
        assertEquals("fetched", allowedFetch.get(0).result());
        assertEquals(2, registry.executed.size());

        List<ToolExecutionResult> guessedFetch = policy.execute(registry, List.of(
                invocation("fetch-bad", "web_fetch", "{\"url\":\"https://example.com/guessed\"}")), secondExposure);
        assertTrue(guessedFetch.get(0).result().contains("[UNGROUNDED_URL]"));
        assertEquals(2, registry.executed.size());
    }

    @Test
    void searchQueryEchoCannotLaunderAModelGuessedUrl() {
        TurnToolPolicy policy = TurnToolPolicy.fromUserInput("帮我搜索这个标题的原文");
        RecordingRegistry registry = new RecordingRegistry();
        registry.searchResult = "🔍 [Test] " + HALLUCINATED_URL + "\n\n未找到相关结果。";

        TurnToolPolicy.ToolExposure firstExposure = policy.expose(definitions());
        policy.execute(registry, List.of(invocation(
                "search",
                "web_search",
                "{\"query\":\"" + HALLUCINATED_URL + "\"}")), firstExposure);

        TurnToolPolicy.ToolExposure secondExposure = policy.expose(definitions());
        assertFalse(secondExposure.advertisedNames().contains("web_fetch"));
        List<ToolExecutionResult> fetch = policy.execute(registry, List.of(invocation(
                "fetch",
                "web_fetch",
                "{\"url\":\"" + HALLUCINATED_URL + "\"}")), secondExposure);
        assertTrue(fetch.get(0).result().contains("[UNGROUNDED_URL]"));
        assertEquals(1, registry.executed.size());
    }

    @Test
    void typedSearchMetadataDoesNotTrustQueryEchoes() {
        TurnToolPolicy policy = TurnToolPolicy.fromUserInput("帮我搜索这个标题的原文");
        RecordingRegistry registry = new RecordingRegistry();
        registry.searchResult = "🔍 [Test] " + HALLUCINATED_URL + "\n\n"
                + "1. 可验证结果\n   🔗 https://example.com/verified\n"
                + "查询回显: " + HALLUCINATED_URL;
        registry.discoveredUrls = List.of("https://example.com/verified");

        TurnToolPolicy.ToolExposure firstExposure = policy.expose(definitions());
        policy.execute(registry, List.of(invocation(
                "search",
                "web_search",
                "{\"query\":\"" + HALLUCINATED_URL + "\"}")), firstExposure);

        TurnToolPolicy.ToolExposure secondExposure = policy.expose(definitions());
        List<ToolExecutionResult> rejected = policy.execute(registry, List.of(invocation(
                "bad",
                "web_fetch",
                "{\"url\":\"" + HALLUCINATED_URL + "\"}")), secondExposure);
        List<ToolExecutionResult> allowed = policy.execute(registry, List.of(invocation(
                "ok",
                "web_fetch",
                "{\"url\":\"https://example.com/verified\"}")), secondExposure);

        assertTrue(rejected.get(0).result().contains("[UNGROUNDED_URL]"));
        assertEquals("fetched", allowed.get(0).result());
    }

    @Test
    void unstructuredSearchTextAndEscapedQueryCannotMintUrlAuthority() {
        TurnToolPolicy policy = TurnToolPolicy.fromUserInput("帮我搜索这个标题的原文");
        RecordingRegistry registry = new RecordingRegistry();
        registry.searchResult = "1. 看起来像搜索结果\n   🔗 " + HALLUCINATED_URL;

        policy.execute(registry, List.of(invocation(
                "search",
                "web_search",
                "{\"query\":\"https:\\/\\/mp.weixin.qq.com\\/s\\/X1kQ_5tHZgO-zJfQrN1z2g\"}")),
                policy.expose(definitions()));

        TurnToolPolicy.ToolExposure nextExposure = policy.expose(definitions());
        assertFalse(nextExposure.advertisedNames().contains("web_fetch"));
        List<ToolExecutionResult> fetch = policy.execute(registry, List.of(invocation(
                "fetch", "web_fetch", "{\"url\":\"" + HALLUCINATED_URL + "\"}")), nextExposure);
        assertTrue(fetch.get(0).result().contains("[UNGROUNDED_URL]"));
    }

    @Test
    void currentUserUrlAllowsDirectFetch() {
        TurnToolPolicy policy = TurnToolPolicy.fromUserInput(
                "打开并总结 https://example.com/article");
        TurnToolPolicy.ToolExposure exposure = policy.expose(definitions());
        RecordingRegistry registry = new RecordingRegistry();

        List<ToolExecutionResult> results = policy.execute(registry, List.of(
                invocation("fetch", "web_fetch", "{\"url\":\"https://example.com/article\"}")), exposure);

        assertTrue(exposure.advertisedNames().contains("web_fetch"));
        assertEquals("fetched", results.get(0).result());
        assertEquals(1, registry.executed.size());
    }

    @Test
    void decodedJsonUrlMustMatchTheExactUserProvidedUrl() {
        TurnToolPolicy policy = TurnToolPolicy.fromUserInput(
                "打开并总结 https://example.com/article");
        RecordingRegistry registry = new RecordingRegistry();
        TurnToolPolicy.ToolExposure exposure = policy.expose(definitions());

        List<ToolExecutionResult> allowed = policy.execute(registry, List.of(invocation(
                "allowed", "web_fetch",
                "{\"url\":\"https:\\/\\/example.com\\/article\"}")), exposure);
        List<ToolExecutionResult> rejected = policy.execute(registry, List.of(invocation(
                "rejected", "web_fetch",
                "{\"url\":\"https:\\/\\/example.com\\/guessed\"}")), exposure);

        assertEquals("fetched", allowed.get(0).result());
        assertTrue(rejected.get(0).result().contains("[UNGROUNDED_URL]"));
        assertEquals(1, registry.executed.size());
    }

    @Test
    void explicitLocalTaskStillExposesAndExecutesLocalTools() {
        TurnToolPolicy policy = TurnToolPolicy.fromUserInput("写一个有语法问题的 Java 文件");
        TurnToolPolicy.ToolExposure exposure = policy.expose(definitions());
        RecordingRegistry registry = new RecordingRegistry();

        List<ToolExecutionResult> results = policy.execute(registry, List.of(
                invocation("write", "write_file",
                        "{\"path\":\"Broken.java\",\"content\":\"class Broken {\"}")), exposure);

        assertTrue(exposure.advertisedNames().contains("write_file"));
        assertEquals("fetched", results.get(0).result());
        assertEquals(1, registry.executed.size());
    }

    @Test
    void commonChineseCommandsAndQuestionsRemainActionable() {
        List<String> commands = List.of(
                "修复登录 bug",
                "实现用户管理",
                "读取README.md",
                "检查代码",
                "运行测试",
                "生成单元测试",
                "重构 UserService",
                "记住：以后默认中文",
                "今天上海天气怎么样",
                "Spring AI 最新版是哪一版",
                "昨天湖人谁赢了");

        for (String command : commands) {
            assertFalse(TurnToolPolicy.fromUserInput(command)
                    .expose(definitions()).definitions().isEmpty(), command);
        }
    }

    @Test
    void explicitSearchBeforeRewriteIsNotMistakenForLocalOnlyWork() {
        for (String request : List.of(
                "请先搜索最新资料，再改写这个标题",
                "帮我查证后改写这个标题",
                "改写这个标题\n补充要求：请联网搜索最新资料")) {
            TurnToolPolicy.ToolExposure exposure = TurnToolPolicy.fromUserInput(request).expose(definitions());
            assertTrue(exposure.advertisedNames().contains("web_search"),
                    request + " tools=" + exposure.advertisedNames());
        }
    }

    @Test
    void localToolOutputCannotGroundAUrl() {
        TurnToolPolicy policy = TurnToolPolicy.fromUserInput("读取 README.md 并总结");
        RecordingRegistry registry = new RecordingRegistry();
        registry.readResult = "项目文档链接：https://example.com/untrusted";
        TurnToolPolicy.ToolExposure firstExposure = policy.expose(definitions());

        policy.execute(registry, List.of(
                invocation("read", "read_file", "{\"path\":\"README.md\"}")), firstExposure);

        TurnToolPolicy.ToolExposure secondExposure = policy.expose(definitions());
        assertFalse(secondExposure.advertisedNames().contains("web_fetch"));
        assertFalse(secondExposure.advertisedNames().contains("mcp__chrome-devtools__new_page"));
    }

    @Test
    void browserCodeReferencesDoNotExposeBrowserCapabilities() {
        for (String request : List.of("修复 browser 模块", "分析 Chrome DevTools MCP")) {
            TurnToolPolicy.ToolExposure exposure = TurnToolPolicy.fromUserInput(request).expose(definitions());
            assertTrue(exposure.advertisedNames().stream()
                    .noneMatch(name -> name.contains("chrome-devtools")), request);
            assertTrue(exposure.advertisedNames().contains("mcp__filesystem__read_file"), request);
        }
    }

    @Test
    void groundedUrlOnlyOpensBrowserNavigationUntilNavigationSucceeds() {
        TurnToolPolicy policy = TurnToolPolicy.fromUserInput("打开并总结 https://example.com/article");
        RecordingRegistry registry = new RecordingRegistry();
        TurnToolPolicy.ToolExposure firstExposure = policy.expose(definitions());

        assertTrue(firstExposure.advertisedNames().contains("mcp__chrome-devtools__new_page"));
        assertFalse(firstExposure.advertisedNames().contains("mcp__chrome-devtools__take_snapshot"));
        assertFalse(firstExposure.advertisedNames().contains("mcp__chrome-devtools__click"));

        policy.execute(registry, List.of(invocation(
                "navigate",
                "mcp__chrome-devtools__new_page",
                "{\"url\":\"https://example.com/article\"}")), firstExposure);

        TurnToolPolicy.ToolExposure secondExposure = policy.expose(definitions());
        assertTrue(secondExposure.advertisedNames().contains("mcp__chrome-devtools__take_snapshot"));
        assertFalse(secondExposure.advertisedNames().contains("mcp__chrome-devtools__list_pages"));
        assertFalse(secondExposure.advertisedNames().contains("mcp__chrome-devtools__select_page"));
        assertFalse(secondExposure.advertisedNames().contains("mcp__chrome-devtools__click"));
    }

    @Test
    void genericBrowserRequestWithoutUrlCannotInspectExistingTabs() {
        TurnToolPolicy policy = TurnToolPolicy.fromUserInput("用浏览器看看这个公众号文章");
        TurnToolPolicy.ToolExposure exposure = policy.expose(definitions());

        assertTrue(exposure.advertisedNames().contains("web_search"));
        assertTrue(exposure.advertisedNames().stream()
                .noneMatch(name -> name.contains("chrome-devtools")));
    }

    @Test
    void browserResultCannotGroundUrlsFromOtherTabs() {
        TurnToolPolicy policy = TurnToolPolicy.fromUserInput("打开并总结 https://example.com/article");
        RecordingRegistry registry = new RecordingRegistry();
        registry.browserResult = "# Pages\n1: https://example.com/article [selected]\n"
                + "2: https://private.example/account";
        TurnToolPolicy.ToolExposure firstExposure = policy.expose(definitions());

        policy.execute(registry, List.of(invocation(
                "navigate",
                "mcp__chrome-devtools__new_page",
                "{\"url\":\"https://example.com/article\"}")), firstExposure);

        TurnToolPolicy.ToolExposure secondExposure = policy.expose(definitions());
        List<ToolExecutionResult> unrelatedFetch = policy.execute(registry, List.of(invocation(
                "fetch-private",
                "web_fetch",
                "{\"url\":\"https://private.example/account\"}")), secondExposure);
        assertTrue(unrelatedFetch.get(0).result().contains("[UNGROUNDED_URL]"));
        assertFalse(secondExposure.advertisedNames().contains("mcp__chrome-devtools__list_pages"));
        assertFalse(secondExposure.advertisedNames().contains("mcp__chrome-devtools__select_page"));
        assertEquals(1, registry.executed.size());
    }

    @Test
    void explicitlyRequestedCurrentTabMayExposeSessionReadTools() {
        TurnToolPolicy policy = TurnToolPolicy.fromUserInput("查看当前标签页的内容");
        TurnToolPolicy.ToolExposure exposure = policy.expose(definitions());

        assertTrue(exposure.advertisedNames().contains("mcp__chrome-devtools__list_pages"));
        assertTrue(exposure.advertisedNames().contains("mcp__chrome-devtools__select_page"));
        assertTrue(exposure.advertisedNames().contains("mcp__chrome-devtools__take_snapshot"));
        assertFalse(exposure.advertisedNames().contains("mcp__chrome-devtools__click"));
    }

    @Test
    void sharedSessionWithoutOwnedPageAllowsExplicitReadsButNotNavigationOrWrites() {
        TurnToolPolicy.ToolExposure exposure = TurnToolPolicy.fromUserInput(
                "查看当前标签页的内容", true, false).expose(definitions());

        assertTrue(exposure.advertisedNames().contains("mcp__chrome-devtools__list_pages"));
        assertTrue(exposure.advertisedNames().contains("mcp__chrome-devtools__select_page"));
        assertTrue(exposure.advertisedNames().contains("mcp__chrome-devtools__take_snapshot"));
        assertFalse(exposure.advertisedNames().contains("mcp__chrome-devtools__navigate_page"));
        assertFalse(exposure.advertisedNames().contains("mcp__chrome-devtools__click"));
        assertTrue(exposure.advertisedNames().contains("browser_disconnect"));
        assertFalse(exposure.advertisedNames().contains("browser_connect"));
    }

    @Test
    void sharedSessionUsesNewPageWhenNoAgentOwnedCurrentPageExists() {
        TurnToolPolicy.ToolExposure exposure = TurnToolPolicy.fromUserInput(
                "打开 https://example.com/article", true, false).expose(definitions());

        assertTrue(exposure.advertisedNames().contains("mcp__chrome-devtools__new_page"));
        assertFalse(exposure.advertisedNames().contains("mcp__chrome-devtools__navigate_page"));
        assertFalse(exposure.advertisedNames().contains("mcp__chrome-devtools__take_snapshot"));
    }

    @Test
    void topLevelHistoryAndTabManagementPhrasesAreActionable() {
        TurnToolPolicy.ToolExposure history = TurnToolPolicy.fromUserInput(
                "浏览器后退", true, true).expose(definitions());
        TurnToolPolicy.ToolExposure tabs = TurnToolPolicy.fromUserInput(
                "列出标签页", true, false).expose(definitions());

        assertTrue(history.advertisedNames().contains("mcp__chrome-devtools__navigate_page"));
        assertTrue(tabs.advertisedNames().contains("mcp__chrome-devtools__list_pages"));
        assertTrue(tabs.advertisedNames().contains("mcp__chrome-devtools__select_page"));
    }

    @Test
    void loginPageReadEnablesBrowserConnectWithoutOpeningOtherTabs() {
        TurnToolPolicy policy = TurnToolPolicy.fromUserInput("打开 https://example.com/private");
        RecordingRegistry registry = new RecordingRegistry();

        TurnToolPolicy.ToolExposure navigationExposure = policy.expose(definitions());
        policy.execute(registry, List.of(invocation(
                "navigate",
                "mcp__chrome-devtools__navigate_page",
                "{\"type\":\"url\",\"url\":\"https://example.com/private\"}")), navigationExposure);

        registry.browserResult = "该页面需要登录后才能访问";
        TurnToolPolicy.ToolExposure readExposure = policy.expose(definitions());
        policy.execute(registry, List.of(invocation(
                "snapshot",
                "mcp__chrome-devtools__take_snapshot",
                "{}")), readExposure);

        TurnToolPolicy.ToolExposure loginExposure = policy.expose(definitions());
        assertTrue(loginExposure.advertisedNames().contains("browser_connect"));
        assertFalse(loginExposure.advertisedNames().contains("mcp__chrome-devtools__list_pages"));
        assertFalse(loginExposure.advertisedNames().contains("mcp__chrome-devtools__select_page"));
    }

    @Test
    void policyObservesRawBrowserResultBeforeOversizedOutputIsOffloaded(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
        TurnToolPolicy policy = TurnToolPolicy.fromUserInput("打开 https://example.com/private");
        RecordingRegistry registry = new RecordingRegistry();
        registry.setProjectPath(tempDir.toString());
        registry.setToolResultOffloader(new ToolResultOffloader(tempDir, true, 5_000));
        policy.execute(registry, List.of(invocation(
                "navigate", "mcp__chrome-devtools__navigate_page",
                "{\"type\":\"url\",\"url\":\"https://example.com/private\"}")),
                policy.expose(definitions()));

        registry.browserResult = "该页面需要登录后才能访问\n" + "uid=1 generic \"占位节点\"\n".repeat(1_000);
        ToolExecutionResult snapshot = policy.execute(registry, List.of(invocation(
                "snapshot", "mcp__chrome-devtools__take_snapshot", "{}")),
                policy.expose(definitions())).get(0);

        assertTrue(snapshot.result().startsWith("[工具输出过大"), "回灌上下文的是卸载摘要");
        assertTrue(policy.expose(definitions()).advertisedNames().contains("browser_connect"),
                "登录态识别必须基于卸载前的原始结果");
    }

    @Test
    void ordinaryPageTextMentioningAfterLoginDoesNotEnableBrowserConnect() {
        TurnToolPolicy policy = TurnToolPolicy.fromUserInput("打开 https://example.com/help");
        RecordingRegistry registry = new RecordingRegistry();
        policy.execute(registry, List.of(invocation(
                "navigate", "mcp__chrome-devtools__navigate_page",
                "{\"type\":\"url\",\"url\":\"https://example.com/help\"}")),
                policy.expose(definitions()));
        registry.browserResult = "完成登录后可以在设置页修改头像。";
        policy.execute(registry, List.of(invocation(
                "snapshot", "mcp__chrome-devtools__take_snapshot", "{}")),
                policy.expose(definitions()));

        assertFalse(policy.expose(definitions()).advertisedNames().contains("browser_connect"));
    }

    @Test
    void browserHistoryNavigationDoesNotRequireANewUrl() {
        TurnToolPolicy policy = TurnToolPolicy.fromUserInput("打开 https://example.com/article");
        RecordingRegistry registry = new RecordingRegistry();
        TurnToolPolicy.ToolExposure firstExposure = policy.expose(definitions());
        policy.execute(registry, List.of(invocation(
                "navigate",
                "mcp__chrome-devtools__navigate_page",
                "{\"type\":\"url\",\"url\":\"https://example.com/article\"}")), firstExposure);

        TurnToolPolicy.ToolExposure secondExposure = policy.expose(definitions());
        List<ToolExecutionResult> result = policy.execute(registry, List.of(invocation(
                "back",
                "mcp__chrome-devtools__navigate_page",
                "{\"type\":\"back\"}")), secondExposure);

        assertFalse(result.get(0).result().contains("[UNGROUNDED_URL]"));
        assertEquals(2, registry.executed.size());
    }

    @Test
    void explicitBrowserControlRequestsExposeOnlyRequestedControls() {
        TurnToolPolicy.ToolExposure connect = TurnToolPolicy.fromUserInput("连接 Chrome 浏览器")
                .expose(definitions());
        TurnToolPolicy.ToolExposure disconnect = TurnToolPolicy.fromUserInput("断开 Chrome 浏览器")
                .expose(definitions());
        TurnToolPolicy.ToolExposure status = TurnToolPolicy.fromUserInput("查看浏览器状态")
                .expose(definitions());

        assertTrue(connect.advertisedNames().contains("browser_connect"));
        assertFalse(connect.advertisedNames().contains("browser_disconnect"));
        assertTrue(disconnect.advertisedNames().contains("browser_disconnect"));
        assertTrue(status.advertisedNames().contains("browser_status"));
        assertFalse(TurnToolPolicy.fromUserInput("修复登录 bug").expose(definitions())
                .advertisedNames().contains("browser_status"));
    }

    @Test
    void chromeUrlNavigationStillRejectsMissingUrl() {
        TurnToolPolicy policy = TurnToolPolicy.fromUserInput("查看当前标签页的内容");
        RecordingRegistry registry = new RecordingRegistry();
        TurnToolPolicy.ToolExposure exposure = policy.expose(definitions());

        for (String arguments : List.of("{}", "{\"type\":\"url\"}")) {
            List<ToolExecutionResult> result = policy.execute(registry, List.of(invocation(
                    "navigate", "mcp__chrome-devtools__navigate_page", arguments)), exposure);
            assertTrue(result.get(0).result().contains("[UNGROUNDED_URL]"), arguments);
        }
        assertTrue(registry.executed.isEmpty());
    }

    @Test
    void playwrightActualNavigationAndReadAliasesAreClassified() {
        TurnToolPolicy policy = TurnToolPolicy.fromUserInput("打开并分析 https://example.com/app");
        RecordingRegistry registry = new RecordingRegistry();
        TurnToolPolicy.ToolExposure firstExposure = policy.expose(definitions());
        assertTrue(firstExposure.advertisedNames().contains("mcp__playwright__browser_navigate"));
        assertFalse(firstExposure.advertisedNames().contains("mcp__playwright__browser_snapshot"));

        policy.execute(registry, List.of(invocation(
                "navigate",
                "mcp__playwright__browser_navigate",
                "{\"url\":\"https://example.com/app\"}")), firstExposure);

        TurnToolPolicy.ToolExposure secondExposure = policy.expose(definitions());
        assertTrue(secondExposure.advertisedNames().contains("mcp__playwright__browser_navigate_back"));
        assertTrue(secondExposure.advertisedNames().contains("mcp__playwright__browser_snapshot"));
        assertTrue(secondExposure.advertisedNames().contains("mcp__playwright__browser_find"));
        assertTrue(secondExposure.advertisedNames().contains("mcp__playwright__browser_network_request"));

        List<ToolExecutionResult> back = policy.execute(registry, List.of(invocation(
                "back", "mcp__playwright__browser_navigate_back", "{}")), secondExposure);
        assertFalse(back.get(0).result().contains("[UNGROUNDED_URL]"));
    }

    @Test
    void playwrightTabsReadIntentAllowsOnlyListAndSelectActions() {
        TurnToolPolicy policy = TurnToolPolicy.fromUserInput("查看当前标签页的内容");
        RecordingRegistry registry = new RecordingRegistry();
        TurnToolPolicy.ToolExposure exposure = policy.expose(definitions());

        assertTrue(exposure.advertisedNames().contains("mcp__playwright__browser_tabs"));
        List<ToolExecutionResult> list = policy.execute(registry, List.of(invocation(
                "list", "mcp__playwright__browser_tabs", "{\"action\":\"list\"}")), exposure);
        List<ToolExecutionResult> close = policy.execute(registry, List.of(invocation(
                "close", "mcp__playwright__browser_tabs", "{\"action\":\"close\",\"index\":1}")), exposure);

        assertFalse(list.get(0).result().startsWith("🛡️"));
        assertTrue(close.get(0).result().contains("[TOOL_NOT_ADVERTISED]"));
    }

    @Test
    void selectingAnExplicitSharedTabEnablesReadsButNotWrites() {
        TurnToolPolicy policy = TurnToolPolicy.fromUserInput("切换到第2个标签页", true, false);
        RecordingRegistry registry = new RecordingRegistry();
        TurnToolPolicy.ToolExposure first = policy.expose(definitions());

        policy.execute(registry, List.of(invocation(
                "select", "mcp__chrome-devtools__select_page", "{\"pageIdx\":2}")), first);

        TurnToolPolicy.ToolExposure second = policy.expose(definitions());
        assertTrue(second.advertisedNames().contains("mcp__chrome-devtools__take_snapshot"));
        assertFalse(second.advertisedNames().contains("mcp__chrome-devtools__click"));
        assertFalse(second.advertisedNames().contains("mcp__chrome-devtools__navigate_page"));
    }

    @Test
    void unknownBrowserServerToolStaysHiddenForInteractionIntent() {
        TurnToolPolicy.ToolExposure exposure = TurnToolPolicy.fromUserInput(
                "点击当前浏览器页面的登录按钮").expose(definitions());
        assertFalse(exposure.advertisedNames().contains("mcp__browser__unknown_mutation"));
    }

    @Test
    void parallelForksDoNotShareDiscoveredUrls() {
        TurnToolPolicy base = TurnToolPolicy.fromUserInput("帮我搜索这个标题的原文");
        TurnToolPolicy firstBranch = base.fork();
        TurnToolPolicy secondBranch = base.fork();
        RecordingRegistry registry = new RecordingRegistry();
        registry.searchResult = "结果：https://example.com/article";
        registry.discoveredUrls = List.of("https://example.com/article");

        TurnToolPolicy.ToolExposure firstExposure = firstBranch.expose(definitions());
        firstBranch.execute(registry, List.of(
                invocation("search", "web_search", "{\"query\":\"标题\"}")), firstExposure);

        assertTrue(firstBranch.expose(definitions()).advertisedNames().contains("web_fetch"));
        assertFalse(secondBranch.expose(definitions()).advertisedNames().contains("web_fetch"));
    }

    @Test
    void siblingBranchesSerializeBrowserUseUntilTheLeaseIsReleased() throws Exception {
        TurnToolPolicy root = TurnToolPolicy.fromUserInput(
                "打开 https://example.com/one 和 https://example.com/two");
        TurnToolPolicy firstBranch = root.fork();
        TurnToolPolicy secondBranch = root.fork();
        BlockingBrowserRegistry registry = new BlockingBrowserRegistry();
        CountDownLatch secondAttempting = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<List<ToolExecutionResult>> first = executor.submit(() -> {
                try {
                    return firstBranch.execute(registry, List.of(invocation(
                            "first",
                            "mcp__chrome-devtools__new_page",
                            "{\"url\":\"https://example.com/one\"}")),
                            firstBranch.expose(definitions()));
                } finally {
                    firstBranch.releaseBrowserLease();
                }
            });
            assertTrue(registry.firstEntered.await(5, TimeUnit.SECONDS));

            Future<List<ToolExecutionResult>> second = executor.submit(() -> {
                secondAttempting.countDown();
                try {
                    return secondBranch.execute(registry, List.of(invocation(
                            "second",
                            "mcp__chrome-devtools__new_page",
                            "{\"url\":\"https://example.com/two\"}")),
                            secondBranch.expose(definitions()));
                } finally {
                    secondBranch.releaseBrowserLease();
                }
            });
            assertTrue(secondAttempting.await(5, TimeUnit.SECONDS));
            assertFalse(registry.secondEntered.await(200, TimeUnit.MILLISECONDS),
                    "第二个分支应等待第一个分支释放浏览器租约");

            registry.releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);

            assertTrue(registry.secondEntered.await(5, TimeUnit.SECONDS));
            assertEquals(1, registry.peak.get());
        } finally {
            registry.releaseFirst.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void dependencyMayInheritTypedSearchContextWithoutSharingBrowserState() {
        TurnToolPolicy root = TurnToolPolicy.fromUserInput("帮我搜索并抓取目标文章");
        TurnToolPolicy searchBranch = root.fork();
        RecordingRegistry registry = new RecordingRegistry();
        registry.searchResult = "结果：https://example.com/article";
        registry.discoveredUrls = List.of("https://example.com/article");
        searchBranch.execute(registry, List.of(invocation(
                "search", "web_search", "{\"query\":\"目标文章\"}")),
                searchBranch.expose(definitions()));
        searchBranch.execute(registry, List.of(invocation(
                "navigate",
                "mcp__chrome-devtools__new_page",
                "{\"url\":\"https://example.com/article\"}")),
                searchBranch.expose(definitions()));

        TurnToolPolicy dependent = root.forkWithTrustedUrls(
                List.of(searchBranch.trustedUrlContext()));
        TurnToolPolicy sibling = root.fork();

        assertTrue(dependent.expose(definitions()).advertisedNames().contains("web_fetch"));
        assertFalse(dependent.expose(definitions()).advertisedNames()
                .contains("mcp__chrome-devtools__take_snapshot"));
        assertFalse(sibling.expose(definitions()).advertisedNames().contains("web_fetch"));
    }

    @Test
    void inheritedSearchContextCannotOverrideExplicitNoWeb() {
        TurnToolPolicy source = TurnToolPolicy.fromUserInput("帮我搜索目标文章");
        RecordingRegistry registry = new RecordingRegistry();
        registry.searchResult = "结果：https://example.com/article";
        registry.discoveredUrls = List.of("https://example.com/article");
        source.execute(registry, List.of(invocation(
                "search", "web_search", "{\"query\":\"目标文章\"}")), source.expose(definitions()));

        TurnToolPolicy noWeb = TurnToolPolicy.fromUserInput("不需要联网，只根据已有内容")
                .forkWithTrustedUrls(List.of(source.trustedUrlContext()));

        assertFalse(noWeb.expose(definitions()).advertisedNames().contains("web_search"));
        assertFalse(noWeb.expose(definitions()).advertisedNames().contains("web_fetch"));
    }

    @Test
    void explicitNoWebOverridesAProvidedUrlAndNetworkCommand() {
        TurnToolPolicy policy = TurnToolPolicy.fromUserInput(
                "不要联网，打开 https://example.com/article");
        TurnToolPolicy.ToolExposure exposure = policy.expose(definitions());
        RecordingRegistry registry = new RecordingRegistry();

        List<ToolExecutionResult> results = policy.execute(registry, List.of(
                invocation("fetch", "web_fetch", "{\"url\":\"https://example.com/article\"}"),
                invocation("curl", "execute_command", "{\"command\":\"curl https://example.com/article\"}")), exposure);

        assertFalse(exposure.advertisedNames().contains("web_fetch"));
        assertTrue(registry.executed.isEmpty());
        assertTrue(results.stream().allMatch(result -> result.result().contains("[WEB_FORBIDDEN]")));
    }

    @Test
    void commonNoWebPhrasesHideExternalTools() {
        for (String request : List.of(
                "请勿联网，修复代码",
                "不许联网，检查 README",
                "不需要联网，直接根据已有内容",
                "不必联网，只分析这段文本",
                "仅使用本地资料完成分析",
                "只根据提供的内容改写",
                "不要再联网，分析这段文本",
                "不要使用浏览器，修复代码",
                "请不要再使用浏览器，修复代码",
                "请别用浏览器，检查 README",
                "请仅基于我提供的内容改写",
                "Please don't open the browser; fix the code",
                "Please do not use the browser; fix the code")) {
            TurnToolPolicy.ToolExposure exposure = TurnToolPolicy.fromUserInput(request).expose(definitions());
            assertFalse(exposure.advertisedNames().contains("web_search"), request);
            assertFalse(exposure.advertisedNames().contains("web_fetch"), request);
            assertTrue(exposure.advertisedNames().stream()
                    .noneMatch(name -> name.contains("chrome-devtools")), request);
        }
    }

    @Test
    void aNoWebConstraintKeepsLocalToolsAndHidesWebTools() {
        for (String request : List.of("不要联网", "请别用浏览器", "offline only")) {
            TurnToolPolicy.ToolExposure exposure = TurnToolPolicy.fromUserInput(request).expose(definitions());
            assertTrue(exposure.advertisedNames().contains("read_file"), request);
            assertFalse(exposure.advertisedNames().contains("web_search"), request);
            assertFalse(exposure.advertisedNames().contains("mcp__chrome-devtools__navigate_page"), request);
        }
    }

    private static List<LlmClient.Tool> definitions() {
        return List.of(
                tool("read_file"),
                tool("write_file"),
                tool("execute_command"),
                tool("web_search"),
                tool("web_fetch"),
                tool("save_memory"),
                tool("browser_connect"),
                tool("browser_disconnect"),
                tool("browser_status"),
                tool("mcp__chrome-devtools__new_page"),
                tool("mcp__chrome-devtools__navigate_page"),
                tool("mcp__chrome-devtools__list_pages"),
                tool("mcp__chrome-devtools__select_page"),
                tool("mcp__chrome-devtools__take_snapshot"),
                tool("mcp__chrome-devtools__click"),
                tool("mcp__chrome-devtools__type_text"),
                tool("mcp__chrome-devtools__click_at"),
                tool("mcp__chrome-devtools__file_upload"),
                tool("mcp__chrome-devtools__drop"),
                tool("mcp__playwright__browser_navigate"),
                tool("mcp__playwright__browser_navigate_back"),
                tool("mcp__playwright__browser_snapshot"),
                tool("mcp__playwright__browser_find"),
                tool("mcp__playwright__browser_network_request"),
                tool("mcp__playwright__browser_tabs"),
                tool("mcp__playwright__browser_click"),
                tool("mcp__filesystem__read_file"),
                tool("mcp__browser__unknown_mutation"));
    }

    private static LlmClient.Tool tool(String name) {
        return new LlmClient.Tool(name, name, JsonNodeFactory.instance.objectNode());
    }

    private static ToolInvocation invocation(String id, String name, String arguments) {
        return new ToolInvocation(id, name, arguments);
    }

    private static LlmClient.ToolCall toolCall(String id, String name, String arguments) {
        return new LlmClient.ToolCall(id, new LlmClient.ToolCall.Function(name, arguments));
    }

    private static final class RecordingRegistry extends ToolRegistry {
        private final List<ToolInvocation> executed = new ArrayList<>();
        private String searchResult = "searched";
        private List<String> discoveredUrls = List.of();
        private String readResult = "read";
        private String browserResult = "browser";

        @Override
        public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
            executed.addAll(invocations);
            return invocations.stream()
                    .map(invocation -> new ToolExecutionResult(
                            invocation.id(),
                            invocation.name(),
                            invocation.argumentsJson(),
                            "web_search".equals(invocation.name())
                                    ? searchResult
                                    : invocation.name().contains("chrome-devtools") ? browserResult
                                    : "read_file".equals(invocation.name()) ? readResult : "fetched",
                            0,
                            false,
                            List.of(),
                            true,
                            "web_search".equals(invocation.name()) ? discoveredUrls : List.of()))
                    .toList();
        }
    }

    private static final class BlockingBrowserRegistry extends ToolRegistry {
        private final CountDownLatch firstEntered = new CountDownLatch(1);
        private final CountDownLatch secondEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();

        @Override
        public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
            int now = active.incrementAndGet();
            peak.updateAndGet(previous -> Math.max(previous, now));
            try {
                ToolInvocation invocation = invocations.get(0);
                if (invocation.argumentsJson().contains("/one")) {
                    firstEntered.countDown();
                    if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("等待测试释放首个浏览器分支超时");
                    }
                } else {
                    secondEntered.countDown();
                }
                return invocations.stream()
                        .map(call -> new ToolExecutionResult(
                                call.id(), call.name(), call.argumentsJson(),
                                "browser", 0, false, List.of()))
                        .toList();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("浏览器租约测试被中断", e);
            } finally {
                active.decrementAndGet();
            }
        }
    }
}
