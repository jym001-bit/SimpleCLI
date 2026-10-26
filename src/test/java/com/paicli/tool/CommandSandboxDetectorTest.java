package com.paicli.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CommandSandboxDetectorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void macOsUsesSeatbeltWhenSandboxExecIsExecutable() {
        Optional<CommandSandboxDetector.Candidate> candidate = CommandSandboxDetector.detect(
                "Mac OS X", Map.of("PATH", "/usr/bin"), path -> path.equals(Path.of("/usr/bin/sandbox-exec")));

        assertEquals(CommandSandbox.Backend.SEATBELT, candidate.orElseThrow().backend());
        assertTrue(CommandSandboxDetector.detect("Mac OS X", Map.of(), path -> false).isEmpty());
    }

    @Test
    void linuxFindsBwrapOnPathBeforeDefaultLocation() {
        Path custom = Path.of("/opt/tools/bin/bwrap");
        Optional<CommandSandboxDetector.Candidate> fromPath = CommandSandboxDetector.detect(
                "Linux", Map.of("PATH", "relative/bin:/opt/tools/bin:/usr/bin"),
                path -> path.equals(custom) || path.equals(Path.of("/usr/bin/bwrap")));
        Optional<CommandSandboxDetector.Candidate> fallback = CommandSandboxDetector.detect(
                "Linux", Map.of(), path -> path.equals(Path.of("/usr/bin/bwrap")));

        assertEquals(CommandSandbox.Backend.BUBBLEWRAP, fromPath.orElseThrow().backend());
        assertEquals(custom, fromPath.orElseThrow().executable());
        assertEquals(Path.of("/usr/bin/bwrap"), fallback.orElseThrow().executable());
        assertTrue(CommandSandboxDetector.detect("Linux", Map.of("PATH", "/usr/bin"), path -> false).isEmpty());
        assertTrue(CommandSandboxDetector.detect("Windows 11", Map.of(), path -> true).isEmpty());
    }

    @Test
    void modeParsingDefaultsToOff() {
        assertEquals(CommandSandboxMode.OFF, CommandSandboxMode.parse(null));
        assertEquals(CommandSandboxMode.OFF, CommandSandboxMode.parse("false"));
        assertEquals(CommandSandboxMode.OFF, CommandSandboxMode.parse("something-else"));
        assertEquals(CommandSandboxMode.AUTO, CommandSandboxMode.parse("auto"));
        assertEquals(CommandSandboxMode.AUTO, CommandSandboxMode.parse(" ON "));
        assertEquals(CommandSandboxMode.REQUIRED, CommandSandboxMode.parse("required"));
    }

    @Test
    void offModeKeepsLegacyBehaviorAndPrintsNothing(@TempDir Path workspace) {
        CommandSandboxDetector.Activation activation = CommandSandboxDetector.activate(
                CommandSandboxMode.OFF, workspace, "Linux", Map.of(), path -> true, null);

        assertNull(activation.sandbox());
        assertNull(activation.requiredFailure());
        assertEquals("", activation.status().message());
    }

    @Test
    void autoModeFallsBackWithNoticeWhenNoBackendExists(@TempDir Path workspace) {
        CommandSandboxDetector.Activation activation = CommandSandboxDetector.activate(
                CommandSandboxMode.AUTO, workspace, "Plan 9", Map.of(), path -> false, null);

        assertNull(activation.sandbox());
        assertNull(activation.requiredFailure());
        assertFalse(activation.status().active());
        assertTrue(activation.status().message().contains("按原方式直接在宿主执行"), activation.status().message());
    }

    @Test
    void autoModeTreatsFailedProbeAsUnavailable(@TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path binDir = Files.createDirectory(tempDir.resolve("bin"));
        Path failingBwrap = Files.writeString(binDir.resolve("bwrap"), "#!/bin/sh\nexit 3\n");
        assumeTrue(failingBwrap.toFile().setExecutable(true), "cannot mark fake bwrap executable");

        CommandSandboxDetector.Activation activation = CommandSandboxDetector.activate(
                CommandSandboxMode.AUTO, workspace, "Linux", Map.of("PATH", binDir.toString()),
                Files::isExecutable, null);

        assertNull(activation.sandbox());
        assertNull(activation.requiredFailure());
        assertTrue(activation.status().message().contains("bubblewrap probe exit code=3"),
                activation.status().message());
    }

    @Test
    void requiredModeRefusesCommandsWhenSandboxIsUnavailable(@TempDir Path workspace) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(workspace.toString());
        CommandSandboxStatus status = registry.applyCommandSandbox(CommandSandboxMode.REQUIRED,
                CommandSandboxDetector.activate(CommandSandboxMode.REQUIRED, workspace, "Plan 9", Map.of(),
                        path -> false, null));

        String result = registry.executeTool("execute_command",
                MAPPER.writeValueAsString(Map.of("command", "touch should-not-exist")));

        assertTrue(status.message().contains("required"), status.message());
        assertTrue(result.contains("命令沙箱不可用"), result);
        assertFalse(Files.exists(workspace.resolve("should-not-exist")));
    }

    @Test
    void autoModeOnMacEnablesSeatbeltAndIgnoresStateDirectory(@TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace")).toRealPath();
        String osName = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        assumeTrue(osName.contains("mac"), "macOS only");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(workspace.toString());
        CommandSandboxStatus status = registry.configureCommandSandbox(CommandSandboxMode.AUTO, workspace);
        assumeTrue(status.active(), "sandbox-exec cannot run here: " + status.message());

        String inside = registry.executeTool("execute_command",
                MAPPER.writeValueAsString(Map.of("command", "touch inside.txt")));

        assertEquals("macOS Seatbelt", status.backend());
        assertTrue(inside.contains("exit code: 0"), inside);
        assertTrue(Files.exists(workspace.resolve("inside.txt")));
        assertEquals("*\n", Files.readString(workspace.resolve(".paicli-command-sandbox/.gitignore")));
        assertTrue(registry.isCommandSandboxEnabled());
    }
}
