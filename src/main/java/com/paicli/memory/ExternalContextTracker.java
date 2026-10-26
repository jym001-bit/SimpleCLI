package com.paicli.memory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 记录当前会话是否已经把外部不可信内容带进上下文（参考 Codex 的
 * {@code memories.disable_on_external_context}）。
 *
 * <p>联网搜索、网页抓取、浏览器和任意 MCP 工具的返回都算外部内容。一旦会话接触过外部内容：
 * 自动写长期记忆的路径（如 {@link ExplicitMemoryHints} 的登录态提示）不再写入；
 * {@code save_memory} 只有在用户当前轮原文明确要求记住时才放行；显式保存的条目在
 * metadata 里标记 {@code external_context=true} 和来源工具，方便事后审计。</p>
 *
 * <p>同一个 ToolRegistry 在 ReAct / Plan / Team 三条路径间共享，所以本对象代表整个会话；
 * {@code /clear} 清空对话历史时一并重置。</p>
 */
public final class ExternalContextTracker {
    public static final String GUARD_PROPERTY = "paicli.memory.disable.on.external.context";
    public static final String GUARD_ENV = "PAICLI_MEMORY_DISABLE_ON_EXTERNAL_CONTEXT";
    public static final String METADATA_FLAG = "external_context";
    public static final String METADATA_SOURCES = "external_context_sources";
    private static final int MAX_RECORDED_SOURCES = 8;

    private final boolean guardEnabled;
    private final Set<String> sources = new LinkedHashSet<>();

    public ExternalContextTracker(boolean guardEnabled) {
        this.guardEnabled = guardEnabled;
    }

    public static ExternalContextTracker fromConfiguration() {
        return new ExternalContextTracker(guardEnabledByConfiguration());
    }

    /** 自动记忆防护开关，默认开启；显式配置 false/0/no/off 才关闭。 */
    public boolean guardEnabled() {
        return guardEnabled;
    }

    /** 工具名属于外部内容来源时记录；返回是否记录。 */
    public boolean recordToolResult(String toolName) {
        if (!isExternalContentTool(toolName)) return false;
        record(toolName);
        return true;
    }

    /** 记录非工具途径进入上下文的外部内容，例如用户输入里展开的 MCP resource。 */
    public synchronized void record(String source) {
        if (source == null || source.isBlank()) return;
        if (sources.size() < MAX_RECORDED_SOURCES) {
            sources.add(source.trim());
        }
    }

    public synchronized boolean hasExternalContext() {
        return !sources.isEmpty();
    }

    public synchronized List<String> sources() {
        return List.copyOf(sources);
    }

    public synchronized void reset() {
        sources.clear();
    }

    /** 防护开启且会话接触过外部内容时，自动写记忆的路径必须跳过。 */
    public boolean blocksAutomaticMemory() {
        return guardEnabled && hasExternalContext();
    }

    /**
     * 外部内容来源：联网搜索 / 网页抓取 / 浏览器工具，以及任意 {@code mcp__*} 工具
     * （含 MCP resource 虚拟工具）。本地文件和命令工具不在此列。
     */
    public static boolean isExternalContentTool(String toolName) {
        if (toolName == null || toolName.isBlank()) return false;
        String name = toolName.trim().toLowerCase(Locale.ROOT);
        return name.startsWith("mcp__")
                || name.startsWith("browser_")
                || "web_search".equals(name)
                || "web_fetch".equals(name);
    }

    public static boolean guardEnabledByConfiguration() {
        String raw = System.getProperty(GUARD_PROPERTY);
        if (raw == null || raw.isBlank()) raw = System.getenv(GUARD_ENV);
        if (raw == null || raw.isBlank()) return true;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return !("false".equals(value) || "0".equals(value) || "no".equals(value) || "off".equals(value));
    }
}
