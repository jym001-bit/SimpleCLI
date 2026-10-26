package com.paicli.skill;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 把启用 skill 渲染成 system prompt 索引段。
 *
 * 预算约束（命中即截断 + stderr 警告）：
 * - 单条 description ≤ 500 codepoint
 * - 启用 skill 数 ≤ 20（按 name 字典序保留前 20）
 * - 总段大小 ≤ 4096 UTF-8 字节
 *
 * 注入位置：每个 Agent / SubAgent 的 system prompt 中静态段之后、运行时日期和 Project Context 之前，
 * 只在 /skill 启停或重载时变化，不破坏前缀缓存。
 */
public final class SkillIndexFormatter {

    public static final int MAX_DESCRIPTION_CODEPOINTS = 500;
    public static final int MAX_ENABLED_SKILLS = 20;
    public static final int MAX_INDEX_BYTES = 4096;

    private SkillIndexFormatter() {
    }

    public static String format(List<Skill> enabled) {
        if (enabled == null || enabled.isEmpty()) {
            return "";
        }

        List<Skill> effective = enabled;
        if (enabled.size() > MAX_ENABLED_SKILLS) {
            effective = enabled.stream()
                    .sorted((a, b) -> a.name().compareTo(b.name()))
                    .limit(MAX_ENABLED_SKILLS)
                    .toList();
            System.err.println("⚠️ 已检测到 " + enabled.size()
                    + " 个 skill，仅前 " + MAX_ENABLED_SKILLS + " 个进入 system prompt 索引");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 可用 Skills（按需调用 load_skill 加载完整指引）\n\n");

        for (Skill skill : effective) {
            String desc = truncateByCodepoint(skill.description().trim(), MAX_DESCRIPTION_CODEPOINTS);
            sb.append("- **").append(skill.name()).append("**：").append(desc).append('\n');
        }

        sb.append('\n');
        sb.append("判断准则：当任务描述匹配某个 skill 的触发场景时，调用 load_skill(name) 加载完整指引；")
                .append("加载后完整指引会紧跟在 load_skill 工具结果之后，以 \"## 已加载 Skill\" 段落出现，先读指引再继续当前任务。")
                .append("正文还在上下文里时直接按它做；上下文压缩后正文会被移出，还需要时重新 load_skill。\n");

        if (sb.toString().getBytes(StandardCharsets.UTF_8).length > MAX_INDEX_BYTES) {
            String suffix = "\n...(skill 索引段被截断)\n";
            int bodyBudget = MAX_INDEX_BYTES - suffix.getBytes(StandardCharsets.UTF_8).length;
            String truncated = truncateUtf8(sb, bodyBudget) + suffix;
            System.err.println("⚠️ skill 索引段超过 " + MAX_INDEX_BYTES + " UTF-8 字节，已截断");
            return truncated;
        }
        return sb.toString();
    }

    private static String truncateUtf8(CharSequence text, int maxBytes) {
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = Character.codePointAt(text, i);
            int width = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + width > maxBytes) {
                break;
            }
            result.appendCodePoint(cp);
            bytes += width;
            i += Character.charCount(cp);
        }
        return result.toString();
    }

    static String truncateByCodepoint(String s, int limit) {
        if (s == null) return "";
        if (s.codePointCount(0, s.length()) <= limit) return s;
        StringBuilder sb = new StringBuilder();
        int count = 0;
        int i = 0;
        while (i < s.length() && count < limit) {
            int cp = s.codePointAt(i);
            sb.appendCodePoint(cp);
            i += Character.charCount(cp);
            count++;
        }
        return sb.toString() + "...";
    }
}
