package com.paicli.cli;

import com.paicli.skill.Skill;
import com.paicli.skill.SkillRegistry;
import com.paicli.skill.SkillStateStore;

import java.util.List;
import java.util.ArrayList;
import com.paicli.util.AnsiStyle;
import com.paicli.util.TerminalTable;

/**
 * /skill 命令组的展示与状态切换逻辑。
 * 抽出独立类便于单测；Main.java 只负责 dispatch + 打印。
 */
final class SkillCommandHandler {

    private SkillCommandHandler() {
    }

    static String startupSummary(SkillRegistry registry) {
        List<Skill> all = registry.allSkills();
        if (all.isEmpty()) {
            return "📚 Skills: 未发现可用 skill";
        }
        List<Skill> enabled = registry.enabledSkills();
        return "📚 Skills: " + enabled.size() + "/" + all.size() + " 启用";
    }

    static String list(SkillRegistry registry) {
        List<Skill> all = registry.allSkills();
        if (all.isEmpty()) {
            return "📚 Skills: 未发现可用 skill\n   /skill reload 重新扫描";
        }
        List<Skill> enabled = registry.enabledSkills();
        List<List<String>> rows = new ArrayList<>();
        for (Skill skill : all) {
            boolean isEnabled = enabled.contains(skill);
            String name = (isEnabled ? "● " : "○ ") + skill.name();
            rows.add(List.of(isEnabled ? AnsiStyle.section(name) : AnsiStyle.subtle(name),
                    sourceLabel(registry, skill), skill.version() == null ? "—" : skill.version(),
                    abbreviate(skill.description(), 48)));
        }
        return AnsiStyle.emphasis("Skills · " + enabled.size() + "/" + all.size() + " 启用") + "\n\n"
                + TerminalTable.render(List.of("Skill", "来源", "版本", "摘要"), rows, TerminalTable.columns())
                + "\n\n" + AnsiStyle.subtle("● 启用 · ○ 停用 · project（未信任）的正文只作为不可信数据交给模型") + "\n"
                + TerminalTable.wrap("详情：/skill show <name>\n启停：/skill on/off <name>\n信任项目级：/skill trust <name>",
                TerminalTable.columns());
    }

    static String show(SkillRegistry registry, String name) {
        if (name == null || name.isBlank()) {
            return "❌ 请提供 skill 名称，例如 /skill show web-access";
        }
        Skill skill = registry.findAnySkill(name);
        if (skill == null) {
            return "❌ Skill 未找到: " + name + "（用 /skill list 查看可用 skill）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("📖 Skill: ").append(skill.name())
                .append(" (").append(skill.displaySource())
                .append(skill.version() == null ? "" : ", v" + skill.version())
                .append(")\n");
        sb.append("  路径: ").append(skill.skillMdPath()).append('\n');
        if (skill.referencesDir() != null) {
            sb.append("  references/: ").append(skill.referencesDir()).append('\n');
        }
        sb.append('\n');
        sb.append("---\n");
        sb.append("name: ").append(skill.name()).append('\n');
        sb.append("description: ").append(skill.description()).append('\n');
        if (skill.version() != null) sb.append("version: \"").append(skill.version()).append("\"\n");
        if (skill.author() != null) sb.append("author: ").append(skill.author()).append('\n');
        if (!skill.tags().isEmpty()) sb.append("tags: ").append(skill.tags()).append('\n');
        sb.append("---\n\n");
        sb.append(skill.body());
        return sb.toString();
    }

    static String enable(SkillRegistry registry, SkillStateStore stateStore, String name) {
        if (name == null || name.isBlank()) {
            return "❌ 请提供 skill 名称，例如 /skill on web-access";
        }
        if (registry.findAnySkill(name) == null) {
            return "❌ Skill 未找到: " + name + "（用 /skill list 查看可用 skill）";
        }
        stateStore.enable(name);
        return "▶️ 已启用 skill: " + name + "（下一轮 LLM 调用生效）";
    }

    static String disable(SkillRegistry registry, SkillStateStore stateStore, String name) {
        if (name == null || name.isBlank()) {
            return "❌ 请提供 skill 名称，例如 /skill off web-access";
        }
        if (registry.findAnySkill(name) == null) {
            return "❌ Skill 未找到: " + name;
        }
        stateStore.disable(name);
        return "⏸️ 已禁用 skill: " + name + "（已写入 ~/.paicli/skills.json，下一轮 LLM 调用生效）";
    }

    /**
     * 项目级 Skill 随仓库分发，信任前正文只作为不可信数据交给模型。用户先用 /skill show 看过正文再信任，
     * 信任绑定当前正文指纹，仓库更新改动正文后需要重新信任。
     */
    static String trust(SkillRegistry registry, String name) {
        if (name == null || name.isBlank()) {
            return "❌ 请提供 skill 名称，例如 /skill trust deploy-checklist";
        }
        Skill skill = registry.findAnySkill(name);
        if (skill == null) {
            return "❌ Skill 未找到: " + name + "（用 /skill list 查看可用 skill）";
        }
        if (skill.source() != Skill.Source.PROJECT) {
            return "ℹ️ " + name + " 是" + skill.displaySource() + " skill，由你自己安装，无需信任";
        }
        if (registry.isTrusted(skill)) {
            return "ℹ️ 项目级 skill " + name + " 的当前正文已信任";
        }
        registry.trust(skill);
        return "🔓 已信任项目级 skill: " + name + "（" + skill.skillMdPath() + "）\n"
                + "   之后加载时正文作为可信指引注入；SKILL.md 正文改动后需要重新信任";
    }

    private static String sourceLabel(SkillRegistry registry, Skill skill) {
        if (skill.source() != Skill.Source.PROJECT) {
            return skill.displaySource();
        }
        return registry.isTrusted(skill) ? "project（已信任）" : "project（未信任）";
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
