package com.paicli.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BubblewrapArgumentsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void buildsReadOnlySystemWritableWorkspaceAndNoNetwork(@TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("work space")).toRealPath();
        Path workingDirectory = Files.createDirectory(workspace.resolve("sub")).toRealPath();
        Path runtime = Path.of("/opt/runtime/bin");
        String command = "echo 'a b' && touch \"$HOME/x\"; rm -rf ./tmp";

        List<String> args = BubblewrapArguments.build(Path.of("/usr/bin/bwrap"), workspace, workingDirectory,
                List.of(workspace, runtime), command);

        assertEquals("/usr/bin/bwrap", args.get(0));
        assertTrue(args.contains("--unshare-all"), "network namespace must be unshared");
        assertTrue(args.contains("--die-with-parent"));
        assertTrue(args.contains("--new-session"));
        assertEquals(1, Collections.frequency(args, "--bind"), "only the workspace is writable");
        int bind = args.indexOf("--bind");
        assertEquals(workspace.toString(), args.get(bind + 1));
        assertEquals(workspace.toString(), args.get(bind + 2));
        int chdir = args.indexOf("--chdir");
        assertEquals(workingDirectory.toString(), args.get(chdir + 1));
        int runtimeBind = args.indexOf(runtime.toString());
        assertEquals("--ro-bind-try", args.get(runtimeBind - 1));
        assertFalse(args.subList(0, bind).contains(workspace.toString()),
                "workspace is not also mounted read-only before the writable bind");
        assertEquals("/tmp", args.get(args.indexOf("--tmpfs") + 1));

        int separator = args.indexOf("--");
        assertEquals(List.of("--", "/bin/bash", "-c", command), args.subList(separator, args.size()));
        assertEquals(1, args.stream().filter(arg -> arg.contains("rm -rf")).count(),
                "the command is one argv item and is never interpolated elsewhere");
        String home = System.getProperty("user.home");
        assertFalse(args.contains(home), "the user's HOME is never mounted");
    }

    @Test
    void sandboxPrepareUsesBubblewrapArgvWithWorkingDirectory(@TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace")).toRealPath();
        Path fakeBwrap = Files.writeString(tempDir.resolve("bwrap"), "#!/bin/sh\nexit 0\n");
        assumeTrue(fakeBwrap.toFile().setExecutable(true), "cannot mark fake bwrap executable");
        Path sub = Files.createDirectory(workspace.resolve("module")).toRealPath();
        CommandSandbox sandbox = new CommandSandbox(CommandSandbox.Backend.BUBBLEWRAP, workspace, fakeBwrap,
                Map.of("PATH", "/usr/bin"), Path.of(System.getProperty("java.home")));

        CommandSandbox.Invocation invocation = sandbox.prepare(sub, "printf ok");

        assertEquals(fakeBwrap.toAbsolutePath().normalize().toString(), invocation.arguments().get(0));
        assertEquals(sub.toString(), invocation.arguments().get(invocation.arguments().indexOf("--chdir") + 1));
        assertEquals("printf ok", invocation.arguments().get(invocation.arguments().size() - 1));
        assertEquals("", sandbox.profile(), "Seatbelt profile is not built for bubblewrap");
        assertTrue(Files.isDirectory(workspace.resolve(".paicli-command-sandbox/home")));
    }

    @Test
    void linuxBubblewrapIsolatesFilesystemAndNetwork(@TempDir Path tempDir) throws Exception {
        String osName = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        assumeTrue(osName.contains("linux"), "Linux only");
        Path bwrap = findOnPath("bwrap");
        assumeTrue(bwrap != null, "bwrap is not installed");
        Path workspace = Files.createDirectory(tempDir.resolve("workspace")).toRealPath();
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(workspace.toString());
        CommandSandboxStatus status = registry.configureCommandSandbox(CommandSandboxMode.AUTO, workspace);
        assumeTrue(status.active(), "bubblewrap cannot run here: " + status.message());

        String inside = run(registry, "echo ok > inside.txt && cat inside.txt");
        Path outside = tempDir.resolve("outside.txt");
        String escape = run(registry, "echo bad > '" + outside + "'");
        String network = run(registry, "exec 3<>/dev/tcp/1.1.1.1/80");

        assertEquals("Linux bubblewrap", status.backend());
        assertTrue(inside.contains("exit code: 0") && inside.contains("ok"), inside);
        assertFalse(Files.exists(outside), escape);
        assertFalse(network.contains("exit code: 0"), network);
    }

    private static String run(ToolRegistry registry, String command) throws Exception {
        return registry.executeTool("execute_command", MAPPER.writeValueAsString(Map.of("command", command)));
    }

    private static Path findOnPath(String command) throws Exception {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String entry : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (entry.isBlank()) continue;
            Path candidate = Path.of(entry).resolve(command);
            if (Files.isExecutable(candidate)) {
                Process probe = new ProcessBuilder(candidate.toString(), "--version").start();
                return probe.waitFor(3, TimeUnit.SECONDS) ? candidate : null;
            }
        }
        return null;
    }
}
