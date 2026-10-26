package com.paicli.skill;

import com.paicli.policy.PolicyException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Skill 参考资料（references/）的用户级补充目录：定位补充文件与写回。
 *
 * <p>load_skill(file) 读 references/ 时合并两处：Skill 自身目录（内置 skill 即 ~/.paicli/skills-cache/&lt;name&gt;/），
 * 以及用户级补充目录 ~/.paicli/skills/&lt;name&gt;/references/。补充目录不需要 SKILL.md，
 * 所以不会被 {@link SkillRegistry} 当成同名 skill 覆盖内置版本。</p>
 *
 * <p>写入只落在用户级补充目录的 references/ 下，只写 .md，拒绝 ..、隐藏段和符号链接，
 * 不经过也不放宽 PathGuard；内置缓存目录永远只读。</p>
 */
public final class SkillReferences {

    static final String REFERENCES_DIR = "references";
    static final int MAX_WRITE_CHARS = 8_000;
    static final int MAX_OVERLAY_FILE_BYTES = 64 * 1024;
    private static final int MAX_DEPTH = 4;
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern SKILL_NAME = Pattern.compile("[a-z0-9][a-z0-9-]*");

    private SkillReferences() {
    }

    /**
     * load_skill(file) 读取 references/ 下的路径时，返回用户级补充目录里对应的真实路径；
     * 路径不在 references/ 下、不合法、不存在或被符号链接带出补充目录时返回 null。
     *
     * @param overlayDir 用户级补充目录 ~/.paicli/skills/&lt;name&gt;/，可为 null
     */
    public static Path resolveOverlay(Path overlayDir, String file) {
        if (overlayDir == null) return null;
        List<String> segments;
        try {
            segments = parseFile(file);
        } catch (RuntimeException e) {
            return null;
        }
        Path root = overlayDir.resolve(REFERENCES_DIR);
        if (!Files.isDirectory(root)) return null;
        Path target = root;
        for (String segment : segments) {
            target = target.resolve(segment);
        }
        if (!Files.exists(target)) return null;
        try {
            Path realRoot = root.toRealPath();
            Path realTarget = target.toRealPath();
            return realTarget.startsWith(realRoot) ? realTarget : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 把 content 写进用户级补充目录的 references/ 下；默认追加，overwrite=true 时整体替换该补充文件。
     */
    public static String write(Skill skill, Path overlayDir, String file, String content, boolean overwrite)
            throws IOException {
        if (!SKILL_NAME.matcher(skill.name()).matches()) {
            throw new PolicyException("Skill 名不是 kebab-case，拒绝写回: " + skill.name());
        }
        if (overlayDir == null) {
            throw new IllegalArgumentException("用户级 Skill 目录未配置，无法写回参考资料");
        }
        List<String> segments = parseFile(file);
        if (segments.isEmpty() || !segments.get(segments.size() - 1).endsWith(".md")) {
            throw new PolicyException("只能写 references/ 下的 .md 文件: " + file);
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        String body = content.strip();
        if (body.length() > MAX_WRITE_CHARS) {
            throw new PolicyException("单次写回最多 " + MAX_WRITE_CHARS + " 字符，当前 " + body.length()
                    + "，只写新发现的陷阱和有效模式");
        }
        String normalized = body + "\n";

        Path referencesRoot = overlayDir.resolve(REFERENCES_DIR);
        Path parent = ensureDirectories(referencesRoot, segments.subList(0, segments.size() - 1));
        Path target = parent.resolve(segments.get(segments.size() - 1));
        boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (exists && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new PolicyException("目标不是普通文件，拒绝写回: " + target);
        }

        byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
        String display = displayPath(segments);
        if (overwrite || !exists) {
            if (bytes.length > MAX_OVERLAY_FILE_BYTES) {
                throw new PolicyException("补充文件上限 " + MAX_OVERLAY_FILE_BYTES + " 字节");
            }
            Path temp = Files.createTempFile(parent, ".write-", ".tmp");
            try {
                Files.write(temp, bytes);
                moveIntoPlace(temp, target);
            } finally {
                Files.deleteIfExists(temp);
            }
            return "已" + (exists ? "覆盖" : "新建") + "用户级参考资料 " + display + "（" + target + "，"
                    + normalized.length() + " 字符）。之后 load_skill(name=\"" + skill.name() + "\", file=\""
                    + display + "\") 会连同内置版本一起返回。";
        }

        long existingSize = Files.size(target);
        if (existingSize + bytes.length + 1 > MAX_OVERLAY_FILE_BYTES) {
            throw new PolicyException("补充文件将超过 " + MAX_OVERLAY_FILE_BYTES
                    + " 字节上限，请先读出整理后用 overwrite=true 重写");
        }
        // APPEND 单次写入，不做读-改-写，多个 PaiCLI 进程同时写回也不会互相覆盖
        byte[] separator = existingSize > 0 ? "\n".getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] payload = new byte[separator.length + bytes.length];
        System.arraycopy(separator, 0, payload, 0, separator.length);
        System.arraycopy(bytes, 0, payload, separator.length, bytes.length);
        Files.write(target, payload, StandardOpenOption.APPEND);
        return "已追加到用户级参考资料 " + display + "（" + target + "，" + normalized.length()
                + " 字符）。之后 load_skill(name=\"" + skill.name() + "\", file=\"" + display
                + "\") 会连同内置版本一起返回。";
    }

    /** file 相对 Skill 目录，必须以 "references" 开头；返回 references 之下的路径段，空列表表示 references 根。 */
    static List<String> parseFile(String file) {
        if (file == null || file.isBlank()) {
            throw new IllegalArgumentException("file 不能为空");
        }
        String value = file.trim();
        if (value.startsWith("/") || value.startsWith("~") || value.contains("\\") || value.contains(":")) {
            throw new PolicyException("file 必须是 Skill 内 references/ 下的相对路径: " + file);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        List<String> segments = new ArrayList<>(List.of(value.split("/", -1)));
        if (!REFERENCES_DIR.equals(segments.get(0))) {
            throw new PolicyException("file 必须在 references/ 下: " + file);
        }
        segments.remove(0);
        if (segments.size() > MAX_DEPTH) {
            throw new PolicyException("file 层级过深: " + file);
        }
        for (String segment : segments) {
            // 段首必须是字母或数字，因此 ..、. 与隐藏文件都会被拒绝
            if (!SEGMENT.matcher(segment).matches()) {
                throw new PolicyException("file 含非法路径段 '" + segment + "': " + file);
            }
        }
        return List.copyOf(segments);
    }

    /** 逐级创建 references 及其子目录；已存在的每一级都必须是真实目录，不能是符号链接。 */
    private static Path ensureDirectories(Path referencesRoot, List<String> dirSegments) throws IOException {
        Files.createDirectories(referencesRoot.getParent());
        Path current = ensureDirectory(referencesRoot);
        for (String segment : dirSegments) {
            current = ensureDirectory(current.resolve(segment));
        }
        return current;
    }

    private static Path ensureDirectory(Path dir) throws IOException {
        if (Files.isSymbolicLink(dir)) {
            throw new PolicyException("用户级参考资料目录不能是符号链接: " + dir);
        }
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(dir);
        } else if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new PolicyException("路径已存在但不是目录: " + dir);
        }
        return dir;
    }

    private static void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String displayPath(List<String> segments) {
        return segments.isEmpty() ? REFERENCES_DIR : REFERENCES_DIR + "/" + String.join("/", segments);
    }

}
