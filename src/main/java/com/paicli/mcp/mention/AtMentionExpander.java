package com.paicli.mcp.mention;

import com.paicli.mcp.McpServerManager;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AtMentionExpander {
    private static final int MAX_INLINE_RESOURCE_CHARS = 200_000;
    private static final Pattern FORGED_RESOURCE_TAG =
            Pattern.compile("<(/?)(\\s*)(resource)(?=\\s|>|/)", Pattern.CASE_INSENSITIVE);

    private final McpServerManager serverManager;

    public AtMentionExpander(McpServerManager serverManager) {
        this.serverManager = serverManager;
    }

    public String expand(String input) {
        List<AtMentionParser.MentionToken> tokens = AtMentionParser.parse(input);
        if (tokens.isEmpty()) {
            return input;
        }

        StringBuilder expanded = new StringBuilder(input);
        for (int i = tokens.size() - 1; i >= 0; i--) {
            AtMentionParser.MentionToken token = tokens.get(i);
            String replacement = expandToken(token);
            expanded.replace(token.start(), token.end(), replacement);
        }
        return expanded.toString();
    }

    private String expandToken(AtMentionParser.MentionToken token) {
        try {
            McpServerManager.ResourceReadResult result =
                    serverManager.readResourceForMention(token.serverName(), token.uri());
            String content = result.content();
            boolean truncated = false;
            if (content.length() > MAX_INLINE_RESOURCE_CHARS) {
                content = content.substring(0, MAX_INLINE_RESOURCE_CHARS);
                truncated = true;
            }
            String mimeType = result.mimeType() == null || result.mimeType().isBlank()
                    ? "text/plain"
                    : result.mimeType();
            String suffix = truncated
                    ? "\n[resource truncated by PaiCLI at " + MAX_INLINE_RESOURCE_CHARS + " chars]"
                    : "";
            return "<resource server=\"" + escapeXml(token.serverName()) +
                    "\" uri=\"" + escapeXml(token.uri()) +
                    "\" mimeType=\"" + escapeXml(mimeType) + "\">\n" +
                    escapeResourceBody(content) + suffix + "\n</resource>";
        } catch (Exception e) {
            return token.raw() + "\n<resource_error server=\"" + escapeXml(token.serverName()) +
                    "\" uri=\"" + escapeXml(token.uri()) + "\">" +
                    escapeResourceBody(escapeXml(e.getMessage())) + "</resource_error>";
        }
    }

    /** 与 ToolResultBoundary 同一思路：正文里伪造的 <resource / </resource 前缀不能提前闭合/伪造块。 */
    private static String escapeResourceBody(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        Matcher matcher = FORGED_RESOURCE_TAG.matcher(content);
        StringBuilder escaped = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(escaped, Matcher.quoteReplacement(
                    "&lt;" + matcher.group(1) + matcher.group(2) + matcher.group(3)));
        }
        matcher.appendTail(escaped);
        return escaped.toString();
    }

    private static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
