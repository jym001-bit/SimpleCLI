package com.paicli.tool;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 运行时探测当前平台可用的命令沙箱后端：macOS 找 {@code /usr/bin/sandbox-exec}，
 * Linux 在 PATH 和 {@code /usr/bin} 里找 {@code bwrap}。找到可执行文件只是第一步，
 * 真正启用前还要由 {@link CommandSandbox#enable} 跑一次探针（例如容器里禁用了
 * user namespace 时 bwrap 会探针失败）。
 */
final class CommandSandboxDetector {

    private CommandSandboxDetector() {
    }

    record Candidate(CommandSandbox.Backend backend, Path executable) {
    }

    static Optional<Candidate> detect() {
        return detect(System.getProperty("os.name", ""), System.getenv(), Files::isExecutable);
    }

    static Optional<Candidate> detect(String osName, Map<String, String> environment, Predicate<Path> isExecutable) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            Path executable = CommandSandbox.Backend.SEATBELT.defaultExecutable;
            return isExecutable.test(executable)
                    ? Optional.of(new Candidate(CommandSandbox.Backend.SEATBELT, executable))
                    : Optional.empty();
        }
        if (os.contains("linux")) {
            return findOnPath("bwrap", environment, isExecutable)
                    .or(() -> isExecutable.test(CommandSandbox.Backend.BUBBLEWRAP.defaultExecutable)
                            ? Optional.of(CommandSandbox.Backend.BUBBLEWRAP.defaultExecutable)
                            : Optional.empty())
                    .map(path -> new Candidate(CommandSandbox.Backend.BUBBLEWRAP, path));
        }
        return Optional.empty();
    }

    /** 探测 + 探针 + 启用的结果；{@code sandbox} 为 null 表示未启用。 */
    record Activation(CommandSandbox sandbox, CommandSandboxStatus status, String requiredFailure) {
    }

    static Activation activate(CommandSandboxMode mode, Path workspaceRoot) {
        return activate(mode, workspaceRoot, System.getProperty("os.name", ""), System.getenv(),
                Files::isExecutable, CommandSandbox.configuredJavaHome());
    }

    static Activation activate(CommandSandboxMode mode,
                               Path workspaceRoot,
                               String osName,
                               Map<String, String> environment,
                               Predicate<Path> isExecutable,
                               Path javaHome) {
        if (mode == null || mode == CommandSandboxMode.OFF) {
            return new Activation(null, CommandSandboxStatus.off(), null);
        }
        String reason;
        Optional<Candidate> candidate = detect(osName, environment, isExecutable);
        if (candidate.isEmpty()) {
            reason = unavailableReason(osName);
        } else {
            try {
                CommandSandbox sandbox = CommandSandbox.enable(candidate.get().backend(), workspaceRoot,
                        candidate.get().executable(), environment, javaHome);
                sandbox.ignoreStateDirectoryInGit();
                String backend = candidate.get().backend().displayName;
                return new Activation(sandbox, new CommandSandboxStatus(mode, true, backend,
                        "🔒 命令沙箱已启用（" + backend + "）：execute_command 只能写工作区、无网络，"
                                + "HOME/TMPDIR 位于工作区 .paicli-command-sandbox/"), null);
            } catch (RuntimeException | java.io.IOException e) {
                reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
        }
        if (mode == CommandSandboxMode.REQUIRED) {
            return new Activation(null, new CommandSandboxStatus(mode, false, "",
                    "⛔ 命令沙箱不可用（" + reason + "）；" + CommandSandboxMode.ENV
                            + "=required，execute_command 将拒绝执行"), reason);
        }
        return new Activation(null, new CommandSandboxStatus(mode, false, "",
                "⚠️ 命令沙箱不可用（" + reason + "），execute_command 按原方式直接在宿主执行"), null);
    }

    /** 当前平台没有候选后端时给用户看的原因。 */
    static String unavailableReason(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return "未找到 /usr/bin/sandbox-exec";
        }
        if (os.contains("linux")) {
            return "未找到 bwrap（可安装 bubblewrap 软件包）";
        }
        return "当前操作系统（" + osName + "）没有受支持的命令沙箱";
    }

    private static Optional<Path> findOnPath(String command, Map<String, String> environment,
                                             Predicate<Path> isExecutable) {
        String pathValue = environment == null ? null : environment.get("PATH");
        if (pathValue == null || pathValue.isBlank()) {
            return Optional.empty();
        }
        for (String entry : pathValue.split(Pattern.quote(File.pathSeparator))) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                Path directory = Path.of(entry);
                if (!directory.isAbsolute()) {
                    continue;
                }
                Path candidate = directory.resolve(command);
                if (isExecutable.test(candidate)) {
                    return Optional.of(candidate);
                }
            } catch (RuntimeException ignored) {
                // 非法 PATH 项直接跳过。
            }
        }
        return Optional.empty();
    }
}
