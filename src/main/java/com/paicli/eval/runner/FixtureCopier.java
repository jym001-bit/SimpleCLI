package com.paicli.eval.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** fixture 复制与临时目录清理。 */
public final class FixtureCopier {

    private FixtureCopier() {
    }

    /** 递归复制 fixture 到目标目录（跳过 .git），目标目录不存在时自动创建。 */
    public static void copy(Path source, Path target) throws IOException {
        if (source == null || !Files.isDirectory(source)) {
            throw new IOException("fixture 目录不存在: " + source);
        }
        Files.createDirectories(target);
        try (Stream<Path> paths = Files.walk(source)) {
            List<Path> entries = paths.toList();
            for (Path entry : entries) {
                Path relative = source.relativize(entry);
                if (relative.toString().isEmpty()) {
                    continue;
                }
                if (relative.getName(0).toString().equals(".git")) {
                    continue;
                }
                Path destination = target.resolve(relative);
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(destination);
                } else {
                    Path parent = destination.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(entry, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** 尽力删除临时目录；失败不抛出，避免掩盖真实评测结果。 */
    public static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> ordered = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path path : ordered) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Windows 上偶发文件占用，忽略即可
                }
            }
        } catch (IOException ignored) {
            // ignore
        }
    }
}
