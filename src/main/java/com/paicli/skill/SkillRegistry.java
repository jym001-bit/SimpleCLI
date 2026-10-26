package com.paicli.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Skill 加载与运行时维护。
 *
 * 三层目录扫描顺序（后者整体覆盖前者同名 skill）：
 *   1. builtin（jar 内置，由 SkillBuiltinExtractor 解压到 cacheRoot）
 *   2. user：~/.paicli/skills/&lt;name&gt;/SKILL.md
 *   3. project：&lt;projectDir&gt;/.paicli/skills/&lt;name&gt;/SKILL.md
 *
 * 启用状态由 SkillStateStore 提供 disabled 列表过滤。
 *
 * 用户级目录下没有 SKILL.md 的 ~/.paicli/skills/&lt;name&gt;/ 不算 skill，只作为 references 补充目录，
 * 由 {@link SkillReferences} 与 skill 自带的 references/ 合并读取。
 */
public final class SkillRegistry {

    private final Path builtinCacheRoot;
    private final Path userSkillsDir;
    private final Path projectSkillsDir;
    private final SkillStateStore stateStore;

    private final Map<String, Skill> skillsByName = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();

    public SkillRegistry(Path builtinCacheRoot, Path userSkillsDir, Path projectSkillsDir, SkillStateStore stateStore) {
        this.builtinCacheRoot = builtinCacheRoot;
        this.userSkillsDir = userSkillsDir;
        this.projectSkillsDir = projectSkillsDir;
        this.stateStore = stateStore;
    }

    public synchronized void reload() {
        skillsByName.clear();
        warnings.clear();

        loadDirectory(builtinCacheRoot, Skill.Source.BUILTIN);
        loadDirectory(userSkillsDir, Skill.Source.USER);
        loadDirectory(projectSkillsDir, Skill.Source.PROJECT);
    }

    public synchronized List<Skill> allSkills() {
        return skillsByName.values().stream()
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .toList();
    }

    public synchronized List<Skill> enabledSkills() {
        Set<String> disabled = stateStore == null ? Set.of() : stateStore.disabled();
        return allSkills().stream()
                .filter(s -> !disabled.contains(s.name()))
                .toList();
    }

    public synchronized Skill findSkill(String name) {
        if (name == null) return null;
        Skill skill = skillsByName.get(name);
        if (skill == null) return null;
        Set<String> disabled = stateStore == null ? Set.of() : stateStore.disabled();
        if (disabled.contains(name)) return null;
        return skill;
    }

    public synchronized Skill findAnySkill(String name) {
        if (name == null) return null;
        return skillsByName.get(name);
    }

    public synchronized List<String> warnings() {
        return List.copyOf(warnings);
    }

    /**
     * 用户级 references 补充目录 ~/.paicli/skills/&lt;name&gt;/；未配置用户级目录，
     * 或 frontmatter 里的 name 不是 kebab-case（可能带路径分隔符）时返回 null。
     */
    public Path userOverlayDir(Skill skill) {
        if (userSkillsDir == null || skill == null || !skill.name().matches("[a-z0-9][a-z0-9-]*")) {
            return null;
        }
        return userSkillsDir.resolve(skill.name());
    }

    public SkillStateStore stateStore() {
        return stateStore;
    }

    /**
     * 内置和用户级 Skill 由用户自己安装，视为可信；项目级 Skill 随仓库分发，
     * 只有用户对当前这份正文执行过 /skill trust 才可信，正文改动后需要重新信任。
     */
    public boolean isTrusted(Skill skill) {
        if (skill == null) return false;
        if (skill.source() != Skill.Source.PROJECT) return true;
        return stateStore != null && stateStore.isTrusted(skill.skillMdPath(), contentHash(skill));
    }

    public void trust(Skill skill) {
        if (stateStore == null) {
            throw new IllegalStateException("Skill 状态存储未初始化");
        }
        stateStore.trust(skill.skillMdPath(), contentHash(skill));
    }

    static String contentHash(Skill skill) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(skill.body().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private void loadDirectory(Path dir, Skill.Source source) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            List<Path> entries = stream
                    .filter(Files::isDirectory)
                    .sorted()
                    .collect(Collectors.toList());
            for (Path entry : entries) {
                Path skillMd = entry.resolve("SKILL.md");
                if (!Files.isRegularFile(skillMd)) {
                    continue;
                }
                Skill skill = parseSkill(entry, skillMd, source);
                if (skill != null) {
                    skillsByName.put(skill.name(), skill);
                }
            }
        } catch (IOException e) {
            warnings.add("扫描 skill 目录失败 " + dir + ": " + e.getMessage());
            System.err.println("⚠️ 扫描 skill 目录失败 " + dir + ": " + e.getMessage());
        }
    }

    private Skill parseSkill(Path skillDir, Path skillMd, Skill.Source source) {
        String content;
        try {
            content = Files.readString(skillMd);
        } catch (IOException e) {
            warnings.add("读取 SKILL.md 失败 " + skillMd + ": " + e.getMessage());
            System.err.println("⚠️ 读取 SKILL.md 失败 " + skillMd + ": " + e.getMessage());
            return null;
        }

        SkillFrontmatterParser.ParseResult parsed = SkillFrontmatterParser.parse(content);
        for (String w : parsed.warnings()) {
            warnings.add(skillMd + ": " + w);
            System.err.println("⚠️ Skill " + skillMd + " frontmatter: " + w);
        }

        Map<String, Object> fm = parsed.frontmatter();
        String name = stringField(fm, "name");
        if (name == null || name.isBlank()) {
            name = skillDir.getFileName().toString();
        }
        String description = stringField(fm, "description");
        if (description == null) description = "";
        String version = stringField(fm, "version");
        String author = stringField(fm, "author");
        List<String> tags = listField(fm, "tags");

        Path referencesDir = skillDir.resolve("references");
        if (!Files.isDirectory(referencesDir)) {
            referencesDir = null;
        }

        return new Skill(
                name,
                description,
                version,
                author,
                tags,
                source,
                parsed.body(),
                skillMd,
                referencesDir
        );
    }

    private static String stringField(Map<String, Object> fm, String key) {
        Object v = fm.get(key);
        return v instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> listField(Map<String, Object> fm, String key) {
        Object v = fm.get(key);
        if (v instanceof List<?> list) {
            return list.stream().filter(x -> x instanceof String).map(x -> (String) x).toList();
        }
        return Collections.emptyList();
    }
}
