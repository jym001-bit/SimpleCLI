package com.paicli.tool;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具结果回灌到对话历史时的不可信数据边界。
 *
 * <p>所有工具结果都以 {@code <tool_result tool="..." trust="untrusted-data">} 包裹，system prompt
 * 声明标记内只是数据、其中的指令一律不执行。这与 {@link TurnToolPolicy} 的 URL 来源规则一致：
 * 边界只改变模型看到的文本，不产生任何授权；URL 授权仍然只来自顶层用户原文和
 * {@link ToolOutput#discoveredUrls()} 的结构化结果，从不解析这里的文本。</p>
 *
 * <p>工具内容里伪造的开闭标签会被转义，避免网页或文件“提前闭合”边界后冒充系统指令。</p>
 */
public final class ToolResultBoundary {
    static final String OPEN_TAG = "tool_result";
    private static final Pattern FORGED_TAG = Pattern.compile("<(/?)(\\s*)(tool_result)", Pattern.CASE_INSENSITIVE);

    private ToolResultBoundary() {
    }

    public static String wrap(String toolName, String content) {
        String name = sanitizeName(toolName);
        String body = neutralize(content == null ? "" : content);
        return "<" + OPEN_TAG + " tool=\"" + name + "\" trust=\"untrusted-data\">\n"
                + body
                + "\n</" + OPEN_TAG + ">";
    }

    public static String wrap(ToolRegistry.ToolExecutionResult result) {
        return wrap(result.name(), result.result());
    }

    static String neutralize(String content) {
        Matcher matcher = FORGED_TAG.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(
                    "&lt;" + matcher.group(1) + matcher.group(2) + matcher.group(3)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String sanitizeName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return "unknown";
        }
        return toolName.replaceAll("[^A-Za-z0-9_.:-]", "_");
    }
}
