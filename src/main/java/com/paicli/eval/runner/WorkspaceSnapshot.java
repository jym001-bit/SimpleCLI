package com.paicli.eval.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/** 工作区快照：用于判断 agent 到底改了哪些文件。 */
public final class WorkspaceSnapshot {

    private static final Set<String> IGNORED_TOP_LEVEL =
            Set.of("target", ".git", ".paicli", ".eval-home", ".idea", "node_modules");

    private WorkspaceSnapshot() {
    }

    public static Map<String, String> capture(Path root) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                Path relative = root.relativize(path);
                if (relative.getNameCount() > 0
                        && IGNORED_TOP_LEVEL.contains(relative.getName(0).toString())) {
                    continue;
                }
                files.put(relative.toString().replace('\\', '/'), sha256(path));
            }
        }
        return files;
    }

    /** 返回新增、修改、删除的相对路径（按字母序）。 */
    public static java.util.List<String> changed(Map<String, String> before, Map<String, String> after) {
        java.util.List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> entry : before.entrySet()) {
            if (!entry.getValue().equals(after.get(entry.getKey()))) {
                changed.add(entry.getKey());
            }
        }
        for (String path : after.keySet()) {
            if (!before.containsKey(path)) {
                changed.add(path);
            }
        }
        changed.sort(String::compareTo);
        return changed;
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
