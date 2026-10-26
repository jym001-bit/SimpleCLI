package com.minblog;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 简易 Markdown 渲染器（面向博客常用语法）：
 * - # ## ### 标题
 * - ``` 围栏代码块
 * - - / * 无序列表
 * - &gt; 引用
 * - 空行分段
 * - 行内：`code`、**加粗**、[文本](链接)
 * 输出前统一做 HTML 转义，防止 XSS。
 */
public final class MarkdownRenderer {

    private MarkdownRenderer() {
    }

    public static String render(String markdown) {
        if (markdown == null) {
            return "";
        }
        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean inCode = false;
        boolean inList = false;
        boolean inQuote = false;

        for (String raw : lines) {
            String line = raw;

            if (line.trim().startsWith("```")) {
                if (inCode) {
                    out.append("</code></pre>\n");
                    inCode = false;
                } else {
                    closeBlocks(out, inList, inQuote);
                    inList = false;
                    inQuote = false;
                    out.append("<pre><code>");
                    inCode = true;
                }
                continue;
            }

            if (inCode) {
                out.append(escape(line)).append('\n');
                continue;
            }

            // 空行：结束列表 / 引用 / 段落
            if (line.trim().isEmpty()) {
                closeBlocks(out, inList, inQuote);
                inList = false;
                inQuote = false;
                continue;
            }

            // 引用
            if (line.startsWith(">")) {
                if (!inQuote) {
                    closeBlocks(out, inList, false);
                    inList = false;
                    out.append("<blockquote>");
                    inQuote = true;
                } else {
                    out.append("<br>");
                }
                out.append(escapeInline(line.substring(1).trim())).append('\n');
                continue;
            }

            // 标题
            Matcher h = Pattern.compile("^(#{1,6})\\s+(.*)$").matcher(line);
            if (h.matches()) {
                closeBlocks(out, inList, inQuote);
                inList = false;
                inQuote = false;
                int level = h.group(1).length();
                out.append("<h").append(level).append('>')
                        .append(escapeInline(h.group(2).trim()))
                        .append("</h").append(level).append(">\n");
                continue;
            }

            // 无序列表
            Matcher li = Pattern.compile("^[-*]\\s+(.*)$").matcher(line);
            if (li.matches()) {
                if (!inList) {
                    closeBlocks(out, false, inQuote);
                    inQuote = false;
                    out.append("<ul>\n");
                    inList = true;
                }
                out.append("<li>").append(escapeInline(li.group(1).trim())).append("</li>\n");
                continue;
            }

            // 普通段落
            if (inList || inQuote) {
                closeBlocks(out, inList, inQuote);
                inList = false;
                inQuote = false;
            }
            out.append("<p>").append(escapeInline(line.trim())).append("</p>\n");
        }

        closeBlocks(out, inList, inQuote);
        if (inCode) {
            out.append("</code></pre>\n");
        }
        return out.toString();
    }

    private static void closeBlocks(StringBuilder out, boolean inList, boolean inQuote) {
        if (inList) {
            out.append("</ul>\n");
        }
        if (inQuote) {
            out.append("</blockquote>\n");
        }
    }

    /** HTML 转义（整段）。 */
    static String escape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** 转义后再应用行内格式：`code`、**bold**、[text](url)。 */
    static String escapeInline(String s) {
        String escaped = escape(s);
        escaped = escaped.replaceAll("`([^`]+)`", "<code>$1</code>");
        escaped = escaped.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
        escaped = escaped.replaceAll("\\[([^\\]]+)\\]\\(([^)]+)\\)", "<a href=\"$2\">$1</a>");
        return escaped;
    }
}
