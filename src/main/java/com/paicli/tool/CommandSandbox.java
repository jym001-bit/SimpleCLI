package com.paicli.tool;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * {@code execute_command} 的操作系统级沙箱，一套策略、两个平台后端：
 *
 * <ul>
 *   <li>系统目录和运行时目录只读，用户 HOME 不可见；</li>
 *   <li>只有工作区可写，HOME / TMPDIR 重定向到工作区内的私有状态目录；</li>
 *   <li>没有网络。</li>
 * </ul>
 *
 * <p>macOS 用 Seatbelt（{@link SeatbeltProfile}），Linux 用 bubblewrap（{@link BubblewrapArguments}）。
 * 两个后端都把命令作为单独一个 argv 交给 {@code /bin/bash -c}，不拼接任何 shell 字符串。
 * 启用前会实际跑一次探针，探针失败即视为不可用（fail closed）。</p>
 */
final class CommandSandbox {

    enum Backend {
        SEATBELT("Seatbelt", "macOS Seatbelt", Path.of("/usr/bin/sandbox-exec")),
        BUBBLEWRAP("bubblewrap", "Linux bubblewrap", Path.of("/usr/bin/bwrap"));

        final String probeName;
        final String displayName;
        final Path defaultExecutable;

        Backend(String probeName, String displayName, Path defaultExecutable) {
            this.probeName = probeName;
            this.displayName = displayName;
            this.defaultExecutable = defaultExecutable;
        }
    }

    static final Path DEFAULT_EXECUTABLE = Backend.SEATBELT.defaultExecutable;
    private static final long PROBE_TIMEOUT_SECONDS = 3;
    private static final String STATE_DIRECTORY = ".paicli-command-sandbox";
    private static final List<Path> SYSTEM_READ_DIRECTORY_ROOTS = List.of(
            Path.of("/System"),
            Path.of("/bin"),
            Path.of("/sbin"),
            Path.of("/usr/bin"),
            Path.of("/usr/sbin"),
            Path.of("/usr/lib"),
            Path.of("/usr/libexec"),
            Path.of("/usr/share"),
            Path.of("/Library/Java"),
            Path.of("/Library/Developer"),
            Path.of("/Applications/Xcode.app/Contents/Developer"),
            Path.of("/opt/homebrew/bin"),
            Path.of("/opt/homebrew/sbin"),
            Path.of("/opt/homebrew/Cellar"),
            Path.of("/opt/homebrew/lib"),
            Path.of("/opt/homebrew/share"),
            Path.of("/usr/local/bin"),
            Path.of("/usr/local/sbin"),
            Path.of("/usr/local/Cellar"),
            Path.of("/usr/local/lib"),
            Path.of("/usr/local/share")
    );
    private final Backend backend;
    private final Path root;
    private final Path executable;
    private final Path homeDirectory;
    private final Path tempDirectory;
    private final Path javaHome;
    private final String inheritedPath;
    private final List<Path> readRoots;
    private final String profile;

    /** 基准评测入口：固定使用 macOS Seatbelt，不可用时抛异常（fail closed）。 */
    static CommandSandbox enable(Path root) {
        return enable(
                root,
                DEFAULT_EXECUTABLE,
                System.getenv(),
                configuredJavaHome());
    }

    static CommandSandbox enable(Path root,
                                 Path executable,
                                 Map<String, String> inheritedEnvironment,
                                 Path javaHome) {
        return enable(Backend.SEATBELT, root, executable, inheritedEnvironment, javaHome);
    }

    static CommandSandbox enable(Backend backend,
                                 Path root,
                                 Path executable,
                                 Map<String, String> inheritedEnvironment,
                                 Path javaHome) {
        CommandSandbox sandbox = new CommandSandbox(
                backend,
                root,
                executable,
                inheritedEnvironment,
                javaHome);
        if (!Files.isRegularFile(sandbox.executable) || !Files.isExecutable(sandbox.executable)) {
            throw new IllegalStateException(
                    "命令沙箱不可用: " + sandbox.executable + " 不存在或不可执行");
        }
        sandbox.probe();
        return sandbox;
    }

    CommandSandbox(Path root,
                   Path executable,
                   Map<String, String> inheritedEnvironment,
                   Path javaHome) {
        this(Backend.SEATBELT, root, executable, inheritedEnvironment, javaHome);
    }

    CommandSandbox(Backend backend,
                   Path root,
                   Path executable,
                   Map<String, String> inheritedEnvironment,
                   Path javaHome) {
        if (backend == null) {
            throw new IllegalArgumentException("sandbox backend must not be null");
        }
        this.backend = backend;
        this.root = canonicalDirectory(root, "command sandbox root");
        if (executable == null) {
            throw new IllegalArgumentException("sandbox executable must not be null");
        }
        this.executable = executable.toAbsolutePath().normalize();
        this.homeDirectory = this.root.resolve(STATE_DIRECTORY).resolve("home");
        this.tempDirectory = this.root.resolve(STATE_DIRECTORY).resolve("tmp");
        this.javaHome = canonicalOptionalDirectory(javaHome);
        this.inheritedPath = inheritedEnvironment == null
                ? ""
                : inheritedEnvironment.getOrDefault("PATH", "");
        this.readRoots = collectReadRoots(this.root, this.javaHome, inheritedEnvironment);
        this.profile = backend == Backend.SEATBELT
                ? SeatbeltProfile.build(this.root, this.readRoots)
                : "";
    }

    Invocation prepare(Path workingDirectory, String command) throws IOException {
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
            throw new IOException("命令沙箱不可用: " + executable + " 不存在或不可执行");
        }
        if (command == null || command.isBlank()) {
            throw new IOException("命令沙箱拒绝空命令");
        }

        Path workingRoot = requireInsideRoot(workingDirectory, "command working directory");
        ensurePrivateDirectory(root.resolve(STATE_DIRECTORY));
        ensurePrivateDirectory(homeDirectory);
        ensurePrivateDirectory(tempDirectory);

        return new Invocation(arguments(workingRoot, command), workingRoot);
    }

    void configureEnvironment(Map<String, String> environment) {
        if (environment == null) {
            throw new IllegalArgumentException("command environment must not be null");
        }
        environment.put("HOME", homeDirectory.toString());
        environment.put("TMPDIR", tempDirectory.toString());
        environment.put("TMP", tempDirectory.toString());
        environment.put("TEMP", tempDirectory.toString());
        environment.put("XDG_CACHE_HOME", homeDirectory.resolve(".cache").toString());
        environment.put("XDG_CONFIG_HOME", homeDirectory.resolve(".config").toString());
        environment.put("PATH", commandPath());
    }

    Path root() {
        return root;
    }

    /** 交互式启用时把状态目录排除出版本库，避免在用户项目里留下未跟踪文件。 */
    void ignoreStateDirectoryInGit() throws IOException {
        Path stateDirectory = root.resolve(STATE_DIRECTORY);
        ensurePrivateDirectory(stateDirectory);
        Path gitignore = stateDirectory.resolve(".gitignore");
        if (!Files.exists(gitignore, LinkOption.NOFOLLOW_LINKS)) {
            Files.writeString(gitignore, "*\n", StandardCharsets.UTF_8);
        }
    }

    Path homeDirectory() {
        return homeDirectory;
    }

    Path tempDirectory() {
        return tempDirectory;
    }

    String profile() {
        return profile;
    }

    Backend backend() {
        return backend;
    }

    Path executable() {
        return executable;
    }

    List<Path> readRoots() {
        return readRoots;
    }

    List<String> arguments(String command) {
        return arguments(root, command);
    }

    List<String> arguments(Path workingDirectory, String command) {
        return backend == Backend.SEATBELT
                ? SeatbeltProfile.arguments(executable, profile, command)
                : BubblewrapArguments.build(executable, root, workingDirectory, readRoots, command);
    }

    private void probe() {
        Process process = null;
        try {
            Invocation invocation = prepare(root, "/usr/bin/true");
            ProcessBuilder builder = new ProcessBuilder(invocation.arguments());
            builder.directory(invocation.workingDirectory().toFile());
            builder.redirectErrorStream(true);
            Map<String, String> environment = builder.environment();
            environment.clear();
            configureEnvironment(environment);
            environment.put("LANG", "C");
            environment.put("LC_ALL", "C");
            process = builder.start();
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
                throw new IllegalStateException("命令沙箱不可用: " + backend.probeName + " probe 超时");
            }
            if (process.exitValue() != 0) {
                String diagnostic = new String(
                        process.getInputStream().readNBytes(4_096), StandardCharsets.UTF_8)
                        .replaceAll("[\\r\\n]+", " ")
                        .trim();
                throw new IllegalStateException(
                        "命令沙箱不可用: " + backend.probeName + " probe exit code=" + process.exitValue()
                                + (diagnostic.isBlank() ? "" : " (" + diagnostic + ")"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw new IllegalStateException("命令沙箱不可用: " + backend.probeName + " probe 被中断", e);
        } catch (IOException | SecurityException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            throw new IllegalStateException("命令沙箱不可用: " + backend.probeName + " probe 启动失败", e);
        }
    }

    private String commandPath() {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        if (javaHome != null) {
            entries.add(javaHome.resolve("bin").toString());
        }
        entries.add("/usr/bin");
        entries.add("/bin");
        entries.add("/usr/sbin");
        entries.add("/sbin");
        if (!inheritedPath.isBlank()) {
            for (String entry : inheritedPath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (!entry.isBlank()) {
                    entries.add(entry);
                }
            }
        }
        return String.join(File.pathSeparator, entries);
    }

    private Path requireInsideRoot(Path candidate, String label) throws IOException {
        if (candidate == null) {
            throw new IOException(label + " must not be null");
        }
        Path real = candidate.toAbsolutePath().normalize().toRealPath();
        if (!real.startsWith(root)) {
            throw new IOException(label + " is outside command sandbox root: " + real);
        }
        return real;
    }

    private void ensurePrivateDirectory(Path directory) throws IOException {
        Path relative = root.relativize(directory.toAbsolutePath().normalize());
        if (relative.startsWith("..")) {
            throw new IOException("sandbox state directory escapes workspace: " + directory);
        }
        Path current = root;
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)
                        || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("sandbox state path is not a private directory: " + current);
                }
            } else {
                Files.createDirectory(current);
            }
            Path real = current.toRealPath();
            if (!real.startsWith(root)) {
                throw new IOException("sandbox state directory escapes workspace: " + current);
            }
        }
    }

    static Path configuredJavaHome() {
        String value = System.getProperty("java.home");
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    private static Path canonicalDirectory(Path path, String label) {
        if (path == null) {
            throw new IllegalArgumentException(label + " must not be null");
        }
        try {
            Path real = path.toAbsolutePath().normalize().toRealPath();
            if (!Files.isDirectory(real)) {
                throw new IllegalArgumentException(label + " must be a directory: " + path);
            }
            rejectControlCharacters(real, label);
            return real;
        } catch (IOException e) {
            throw new IllegalArgumentException(label + " is unavailable: " + path, e);
        }
    }

    private static Path canonicalOptionalDirectory(Path path) {
        if (path == null) {
            return null;
        }
        try {
            Path real = path.toAbsolutePath().normalize().toRealPath();
            if (!Files.isDirectory(real)) {
                return null;
            }
            rejectControlCharacters(real, "runtime path");
            return real;
        } catch (IOException | IllegalArgumentException ignored) {
            return null;
        }
    }

    private static List<Path> collectReadRoots(Path workspace,
                                               Path javaHome,
                                               Map<String, String> environment) {
        Set<Path> roots = new LinkedHashSet<>();
        roots.add(workspace);
        for (Path systemRoot : SYSTEM_READ_DIRECTORY_ROOTS) {
            Path canonical = canonicalOptionalDirectory(systemRoot);
            if (canonical != null) {
                roots.add(canonical);
            }
        }
        if (javaHome != null) {
            roots.add(javaHome);
        }
        if (environment != null) {
            addConfiguredRuntimeRoot(roots, environment.get("JAVA_HOME"));
            addPathRuntimeRoots(roots, environment.get("PATH"));
        }
        return List.copyOf(roots);
    }

    private static void addConfiguredRuntimeRoot(Set<Path> roots, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        try {
            Path canonical = canonicalOptionalDirectory(Path.of(raw));
            if (canonical != null) {
                roots.add(canonical);
            }
        } catch (RuntimeException ignored) {
            // An invalid inherited runtime path is omitted; the sandbox stays closed.
        }
    }

    private static void addPathRuntimeRoots(Set<Path> roots, String pathValue) {
        if (pathValue == null || pathValue.isBlank()) {
            return;
        }
        for (String entry : pathValue.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                Path path = Path.of(entry);
                if (!path.isAbsolute()) {
                    continue;
                }
                Path canonical = canonicalOptionalDirectory(path);
                if (canonical != null && isNarrowRuntimeDirectory(canonical)) {
                    roots.add(canonical);
                }
            } catch (RuntimeException ignored) {
                // Invalid PATH entries remain unusable instead of widening the profile.
            }
        }
    }

    private static boolean isNarrowRuntimeDirectory(Path directory) {
        Path fileName = directory.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString();
        return name.equals("bin") || name.equals("sbin") || name.equals("shims");
    }

    static void rejectControlCharacters(Path path, String label) {
        String value = path.toString();
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                throw new IllegalArgumentException(label + " contains a control character");
            }
        }
    }

    record Invocation(List<String> arguments, Path workingDirectory) {
        Invocation {
            arguments = List.copyOf(new ArrayList<>(arguments));
            workingDirectory = workingDirectory.toAbsolutePath().normalize();
        }
    }
}
