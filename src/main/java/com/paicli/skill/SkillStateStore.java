package com.paicli.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Skill 启用状态与项目级 Skill 信任记录的持久化。
 *
 * 设计：仅持久化 disabled 列表，启用为隐式默认——这样新加的 skill 不会被遗漏。
 * 项目级 Skill 随仓库分发，信任按 SKILL.md 绝对路径 + 正文 SHA-256 记录，正文一改就要重新信任。
 *
 * 文件不存在或解析失败一律视为空状态，并在 stderr 警告，不阻塞主流程。
 */
public final class SkillStateStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DISABLED = "disabled";
    private static final String TRUSTED_PROJECT_SKILLS = "trustedProjectSkills";

    private final Path file;

    public SkillStateStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public synchronized Set<String> disabled() {
        Set<String> result = new LinkedHashSet<>();
        JsonNode disabled = readRoot().path(DISABLED);
        if (disabled.isArray()) {
            disabled.forEach(node -> {
                if (node.isTextual() && !node.asText().isBlank()) {
                    result.add(node.asText());
                }
            });
        }
        return result;
    }

    public synchronized void disable(String name) {
        Set<String> set = new LinkedHashSet<>(disabled());
        set.add(name);
        writeDisabled(set);
    }

    public synchronized void enable(String name) {
        Set<String> set = new LinkedHashSet<>(disabled());
        set.remove(name);
        writeDisabled(set);
    }

    public synchronized boolean isTrusted(Path skillMdPath, String sha256) {
        if (skillMdPath == null || sha256 == null) {
            return false;
        }
        String path = skillMdPath.toAbsolutePath().normalize().toString();
        for (JsonNode entry : readRoot().path(TRUSTED_PROJECT_SKILLS)) {
            if (path.equals(entry.path("path").asText()) && sha256.equals(entry.path("sha256").asText())) {
                return true;
            }
        }
        return false;
    }

    /** 记录信任；同一路径只保留最新一次信任的正文指纹。 */
    public synchronized void trust(Path skillMdPath, String sha256) {
        String path = skillMdPath.toAbsolutePath().normalize().toString();
        ObjectNode root = readRoot();
        ArrayNode next = MAPPER.createArrayNode();
        for (JsonNode entry : root.path(TRUSTED_PROJECT_SKILLS)) {
            if (!path.equals(entry.path("path").asText())) {
                next.add(entry);
            }
        }
        next.addObject().put("path", path).put("sha256", sha256);
        root.set(TRUSTED_PROJECT_SKILLS, next);
        write(root);
    }

    private void writeDisabled(Set<String> disabled) {
        ObjectNode root = readRoot();
        root.putPOJO(DISABLED, disabled);
        write(root);
    }

    private ObjectNode readRoot() {
        if (file == null || !Files.exists(file)) {
            return MAPPER.createObjectNode();
        }
        try {
            String content = Files.readString(file);
            if (content.isBlank()) {
                return MAPPER.createObjectNode();
            }
            JsonNode root = MAPPER.readTree(content);
            return root instanceof ObjectNode object ? object : MAPPER.createObjectNode();
        } catch (Exception e) {
            System.err.println("⚠️ skills.json 解析失败，忽略已保存的 Skill 状态: " + e.getMessage());
            return MAPPER.createObjectNode();
        }
    }

    private void write(ObjectNode root) {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (IOException e) {
            System.err.println("⚠️ skills.json 写入失败: " + e.getMessage());
        }
    }
}
