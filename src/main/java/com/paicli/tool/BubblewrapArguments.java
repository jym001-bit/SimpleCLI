package com.paicli.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Linux bubblewrap 后端：把 {@link CommandSandbox} 的统一策略翻译成 {@code bwrap} 参数。
 *
 * <ul>
 *   <li>新根文件系统从空 tmpfs 开始，只按需只读挂载系统目录（/usr、/bin、/lib*、/etc 等）
 *       和运行时目录（JAVA_HOME、PATH 里的 bin/sbin/shims），不挂载用户 HOME；</li>
 *   <li>只有工作区以读写方式 {@code --bind}，/tmp 是沙箱私有 tmpfs；</li>
 *   <li>{@code --unshare-all} 包含独立 network namespace，沙箱内只有 loopback，没有外网；</li>
 *   <li>{@code --die-with-parent} / {@code --new-session} 防止子进程脱离 PaiCLI 或向终端注入输入。</li>
 * </ul>
 *
 * <p>每个路径和命令都是独立 argv，不拼 shell 字符串；命令只作为 {@code /bin/bash -c} 的一个参数。</p>
 */
final class BubblewrapArguments {
    /** Linux 常见的系统只读目录；不存在的自动跳过（--ro-bind-try）。 */
    static final List<Path> SYSTEM_READ_ROOTS = List.of(
            Path.of("/usr"),
            Path.of("/bin"),
            Path.of("/sbin"),
            Path.of("/lib"),
            Path.of("/lib32"),
            Path.of("/lib64"),
            Path.of("/libx32"),
            Path.of("/etc")
    );

    private BubblewrapArguments() {
    }

    static List<String> build(Path executable,
                              Path workspace,
                              Path workingDirectory,
                              Collection<Path> runtimeReadRoots,
                              String command) {
        List<String> args = new ArrayList<>();
        args.add(executable.toString());
        args.add("--die-with-parent");
        args.add("--new-session");
        args.add("--unshare-all");
        for (Path systemRoot : SYSTEM_READ_ROOTS) {
            addSystemRoot(args, systemRoot);
        }
        for (Path runtimeRoot : runtimeReadRoots) {
            if (runtimeRoot.startsWith(workspace) || isUnderSystemRoot(runtimeRoot)) {
                continue;
            }
            addReadOnly(args, runtimeRoot);
        }
        args.add("--dev");
        args.add("/dev");
        args.add("--proc");
        args.add("/proc");
        args.add("--tmpfs");
        args.add("/tmp");
        args.add("--bind");
        args.add(workspace.toString());
        args.add(workspace.toString());
        args.add("--chdir");
        args.add(workingDirectory.toString());
        args.add("--");
        args.add("/bin/bash");
        args.add("-c");
        args.add(command);
        return List.copyOf(args);
    }

    private static void addSystemRoot(List<String> args, Path systemRoot) {
        if (Files.isSymbolicLink(systemRoot)) {
            // merged-usr 发行版上 /bin -> usr/bin，照样建一个符号链接，保持与宿主一致的布局。
            try {
                Path target = Files.readSymbolicLink(systemRoot);
                CommandSandbox.rejectControlCharacters(target, "bubblewrap symlink target");
                args.add("--symlink");
                args.add(target.toString());
                args.add(systemRoot.toString());
                return;
            } catch (IOException | UnsupportedOperationException ignored) {
                // 读不到链接目标时退回只读绑定。
            }
        }
        if (Files.exists(systemRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(systemRoot)) {
            addReadOnly(args, systemRoot);
        }
    }

    private static void addReadOnly(List<String> args, Path path) {
        CommandSandbox.rejectControlCharacters(path, "bubblewrap read-only path");
        args.add("--ro-bind-try");
        args.add(path.toString());
        args.add(path.toString());
    }

    private static boolean isUnderSystemRoot(Path path) {
        for (Path systemRoot : SYSTEM_READ_ROOTS) {
            if (path.startsWith(systemRoot) && !Files.isSymbolicLink(systemRoot)) {
                return true;
            }
        }
        return false;
    }
}
