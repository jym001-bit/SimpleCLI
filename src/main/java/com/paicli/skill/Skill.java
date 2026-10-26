package com.paicli.skill;

import java.nio.file.Path;
import java.util.List;

/**
 * 一个 Skill 是 PaiCLI 沉淀决策与经验的复用单元。
 *
 * 由 SKILL.md 文件解析得到：frontmatter 决定索引段元数据，body 在 LLM 调用 load_skill
 * 时由调用方紧跟工具结果注入同一轮上下文（见 LoadedSkillMessages）。
 *
 * source 标记加载来源，用于 /skill list 展示与三层覆盖的可观测性。
 */
public record Skill(
        String name,
        String description,
        String version,
        String author,
        List<String> tags,
        Source source,
        String body,
        Path skillMdPath,
        Path referencesDir
) {

    public enum Source {
        BUILTIN, USER, PROJECT
    }

    public Skill {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Skill name 不能为空");
        }
        if (description == null) {
            description = "";
        }
        if (tags == null) {
            tags = List.of();
        } else {
            tags = List.copyOf(tags);
        }
        if (body == null) {
            body = "";
        }
    }

    /** Skill 根目录；SKILL.md 里的 references/、scripts/ 相对路径都以它为基准。 */
    public Path directory() {
        return skillMdPath == null ? null : skillMdPath.toAbsolutePath().normalize().getParent();
    }

    public String displaySource() {
        return switch (source) {
            case BUILTIN -> "builtin";
            case USER -> "user";
            case PROJECT -> "project";
        };
    }
}
