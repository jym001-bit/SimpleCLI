package com.paicli.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/**
 * macOS Seatbelt 后端：把 {@link CommandSandbox} 的统一策略（系统目录只读、仅工作区可写、
 * 无网络）翻译成 {@code sandbox-exec -p} 的 profile 文本。
 *
 * <p>profile 作为单独一个 argv 传给 {@code sandbox-exec}，命令本身也作为单独一个 argv 交给
 * {@code /bin/bash -c}，两者都不会拼进另一层 shell 字符串。</p>
 */
final class SeatbeltProfile {
    private static final Path DEV_NULL = Path.of("/dev/null");
    /**
     * 只放行这些路径本身（literal），不含子树。根目录 "/" 本身必须可读：较新的 macOS 上
     * dyld / bash 启动时会读取根目录条目，缺了它探针直接以 SIGABRT（exit 134）退出。
     */
    private static final List<Path> SYSTEM_READ_LITERAL_PATHS = List.of(
            Path.of("/"),
            Path.of("/dev/null"),
            Path.of("/dev/random"),
            Path.of("/dev/urandom"),
            Path.of("/dev/zero")
    );

    private SeatbeltProfile() {
    }

    static List<String> arguments(Path executable, String profile, String command) {
        return List.of(
                executable.toString(),
                "-p",
                profile,
                "/bin/bash",
                "-c",
                command);
    }

    static String build(Path workspace, Collection<Path> readableRoots) {
        StringBuilder profile = new StringBuilder();
        profile.append("(version 1)\n")
                .append("(deny default)\n")
                .append("(deny network*)\n")
                .append("(allow process*)\n")
                .append("(allow sysctl-read)\n")
                // macOS language runtimes use Mach services for basic process/runtime setup.
                // This remains broader than container/VM IPC isolation; network and filesystem
                // policy are still independently denied/restricted by this Seatbelt profile.
                .append("(allow mach-lookup)\n")
                .append("(allow file-read*\n");
        for (Path readableRoot : readableRoots) {
            appendPathFilters(profile, readableRoot);
        }
        for (Path readablePath : SYSTEM_READ_LITERAL_PATHS) {
            if (Files.exists(readablePath)) {
                appendLiteralFilter(profile, readablePath);
            }
        }
        profile.append(")\n")
                .append("(allow file-write*\n");
        appendPathFilters(profile, workspace);
        profile.append("  (literal ").append(seatbeltString(DEV_NULL)).append(")\n");
        profile.append(")\n");
        return profile.toString();
    }

    private static void appendPathFilters(StringBuilder profile, Path path) {
        String literal = seatbeltString(path);
        profile.append("  (literal ").append(literal).append(")\n")
                .append("  (subpath ").append(literal).append(")\n");
    }

    private static void appendLiteralFilter(StringBuilder profile, Path path) {
        profile.append("  (literal ").append(seatbeltString(path)).append(")\n");
    }

    private static String seatbeltString(Path path) {
        String value = path.toString();
        CommandSandbox.rejectControlCharacters(path, "sandbox profile path");
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"";
    }
}
