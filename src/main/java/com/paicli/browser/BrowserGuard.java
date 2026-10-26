package com.paicli.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BrowserGuard {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SERVER_PREFIX = "mcp__chrome-devtools__";
    private static final Set<String> WRITE_TOOLS = Set.of(
            "click",
            "drag",
            "fill",
            "fill_form",
            "handle_dialog",
            "hover",
            "press_key",
            "resize_page",
            "upload_file",
            "file_upload",
            "drop",
            "select_option",
            "type",
            "type_text",
            "click_at",
            "evaluate_script"
    );
    private static final Pattern PAGE_ID_PATTERN = Pattern.compile("(page[-_][A-Za-z0-9_-]+)");
    private static final Pattern SELECTED_PAGE_PATTERN = Pattern.compile(
            "(?m)^\\s*(\\d+|page[-_][A-Za-z0-9_-]+):\\s+.*?\\[selected]\\s*$",
            Pattern.CASE_INSENSITIVE);

    private final BrowserSession session;
    private final SensitivePagePolicy sensitivePagePolicy;

    public BrowserGuard(BrowserSession session, SensitivePagePolicy sensitivePagePolicy) {
        this.session = session;
        this.sensitivePagePolicy = sensitivePagePolicy;
    }

    public BrowserCheckResult check(String toolName, String argsJson, boolean mutateSession) {
        if (!isChromeTool(toolName)) {
            return BrowserCheckResult.allow(null);
        }
        String localTool = localToolName(toolName);
        JsonNode args = parseArgs(argsJson);
        String targetUrl = targetUrl(localTool, args);
        String effectiveUrl = targetUrl == null ? session.lastNavigatedUrl() : targetUrl;
        SensitivePagePolicy.MatchResult match = sensitivePagePolicy.match(effectiveUrl);
        BrowserAuditMetadata metadata = BrowserAuditMetadata.of(session.mode(), match.matched(), effectiveUrl);

        if (session.mode() == BrowserMode.SHARED) {
            if ("close_page".equals(localTool)
                    && !session.isAgentOpenedTab(pageId(args))) {
                return BrowserCheckResult.block(
                        "shared 浏览器模式下拒绝关闭非 PaiCLI 创建的标签页，请在 Chrome 中手动操作",
                        metadata);
            }
            if (requiresAgentOwnedCurrentPage(localTool)
                    && !session.hasAgentOwnedCurrentPage()) {
                return BrowserCheckResult.block(
                        "shared 浏览器模式下当前标签页不是 PaiCLI 创建的页面；"
                                + "请先用 new_page 打开目标 URL，不能导航或改写用户原有标签页",
                        metadata);
            }
        }

        if (match.matched() && WRITE_TOOLS.contains(localTool)) {
            return BrowserCheckResult.requireApproval(
                    "敏感页面命中规则 " + match.pattern() + "，本次浏览器改写操作必须单步审批，不能复用全部放行。",
                    metadata);
        }

        // State is deliberately committed only after a typed successful result.
        // The parameter remains for source compatibility with preview callers.
        return BrowserCheckResult.allow(metadata);
    }

    public void applyAfterExecution(String toolName, String argsJson, String result, boolean successful) {
        if (!isChromeTool(toolName) || !successful) {
            return;
        }
        String localTool = localToolName(toolName);
        JsonNode args = parseArgs(argsJson);
        String targetUrl = targetUrl(localTool, args);
        if ("new_page".equals(localTool)) {
            String openedPageId = pageId(args);
            if (openedPageId == null || openedPageId.isBlank()) {
                openedPageId = extractPageId(result);
            }
            session.recordOpenedTab(openedPageId);
            session.rememberNavigation(targetUrl);
            return;
        }
        if ("select_page".equals(localTool)) {
            String selectedPageId = pageId(args);
            if (session.isAgentOpenedTab(selectedPageId)) {
                session.selectTab(selectedPageId);
            } else {
                session.clearCurrentPage();
            }
            return;
        }
        if ("close_page".equals(localTool)) {
            session.forgetTab(pageId(args));
            rememberSelectedOwnedPage(result);
            return;
        }
        if ("list_pages".equals(localTool)) {
            rememberSelectedOwnedPage(result);
            return;
        }
        if ("navigate_page".equals(localTool) && targetUrl != null) {
            session.rememberNavigation(targetUrl);
        }
    }

    /** Backward-compatible overload for callers that already know execution succeeded. */
    public void applyAfterExecution(String toolName, String argsJson, String result) {
        applyAfterExecution(toolName, argsJson, result, true);
    }

    /**
     * Removes shared-tab inventories from tool output before it is returned to the
     * model. Navigation tools receive a compact receipt instead of the MCP
     * server's global {@code # Pages} dump.
     */
    public String sanitizeResult(String toolName, String argsJson, String result, boolean successful) {
        String raw = result == null ? "" : result;
        if (!isChromeTool(toolName)) {
            return raw;
        }
        String localTool = localToolName(toolName);
        JsonNode args = parseArgs(argsJson);
        if ("new_page".equals(localTool) || "navigate_page".equals(localTool)) {
            if (!successful) {
                return "浏览器导航失败（原始页面清单已隐藏）。";
            }
            String targetUrl = targetUrl(localTool, args);
            String openedPageId = "new_page".equals(localTool) ? extractPageId(raw) : null;
            StringBuilder receipt = new StringBuilder(
                    "new_page".equals(localTool) ? "浏览器已新建页面" : "浏览器导航完成");
            if (targetUrl != null && !targetUrl.isBlank()) {
                receipt.append(": ").append(targetUrl);
            }
            if (openedPageId != null && !openedPageId.isBlank()) {
                receipt.append(" (page ").append(openedPageId).append(')');
            }
            return receipt.toString();
        }
        if (session.mode() == BrowserMode.SHARED) {
            if ("select_page".equals(localTool)) {
                String selectedPageId = pageId(args);
                return session.isAgentOpenedTab(selectedPageId)
                        ? "已切换到 PaiCLI 创建的标签页 " + safePageId(selectedPageId) + "。"
                        : "已切换到用户明确选择的共享标签页 " + safePageId(selectedPageId)
                                + "（仅开放只读操作）。";
            }
            if ("close_page".equals(localTool)) {
                return "已关闭 PaiCLI 创建的标签页 " + safePageId(pageId(args)) + "。";
            }
        }
        return raw;
    }

    public boolean isSharedMode() {
        return session.mode() == BrowserMode.SHARED;
    }

    public boolean hasAgentOwnedCurrentPage() {
        return session.hasAgentOwnedCurrentPage();
    }

    public static boolean isChromeTool(String toolName) {
        return toolName != null && toolName.startsWith(SERVER_PREFIX);
    }

    private static String localToolName(String toolName) {
        return toolName.substring(SERVER_PREFIX.length());
    }

    private static JsonNode parseArgs(String argsJson) {
        try {
            return MAPPER.readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private static String targetUrl(String localTool, JsonNode args) {
        if (!"navigate_page".equals(localTool) && !"new_page".equals(localTool)) {
            return null;
        }
        String url = text(args, "url");
        return url == null || url.isBlank() ? null : url;
    }

    private static String pageId(JsonNode args) {
        String pageIdx = text(args, "pageIdx");
        if (pageIdx != null && !pageIdx.isBlank()) {
            return pageIdx;
        }
        String pageId = text(args, "pageId");
        if (pageId != null && !pageId.isBlank()) {
            return pageId;
        }
        String uid = text(args, "uid");
        return uid == null || uid.isBlank() ? null : uid;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static boolean requiresAgentOwnedCurrentPage(String localTool) {
        if (Set.of("new_page", "list_pages", "select_page", "close_page").contains(localTool)) {
            return false;
        }
        if (Set.of(
                "wait_for", "take_snapshot", "take_screenshot",
                "list_console_messages", "get_console_message",
                "list_network_requests", "get_network_request",
                "performance_analyze_insight", "take_memory_snapshot"
        ).contains(localTool)) {
            return false;
        }
        return true;
    }

    private void rememberSelectedOwnedPage(String result) {
        String selectedPageId = extractSelectedPageId(result);
        if (session.isAgentOpenedTab(selectedPageId)) {
            session.selectTab(selectedPageId);
        } else {
            session.clearCurrentPage();
        }
    }

    private static String extractPageId(String result) {
        if (result == null) {
            return null;
        }
        String selectedPageId = extractSelectedPageId(result);
        if (selectedPageId != null) {
            return selectedPageId;
        }
        Matcher matcher = PAGE_ID_PATTERN.matcher(result);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String extractSelectedPageId(String result) {
        if (result == null) {
            return null;
        }
        Matcher matcher = SELECTED_PAGE_PATTERN.matcher(result);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String safePageId(String pageId) {
        return pageId == null || pageId.isBlank() ? "（未知）" : pageId;
    }
}
