package com.paicli.eval.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkProcessEnvironmentTest {
    @Test
    void replacesInheritedEnvironmentWithMinimalAllowlist(@TempDir Path tempDir) throws Exception {
        Path home = Files.createDirectory(tempDir.resolve("home"));
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path isolatedTemp = BenchmarkProcessEnvironment.prepareIsolatedTemp(workspace);
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("PATH", "/usr/bin:/bin");
        environment.put("DEEPSEEK_API_KEY", "secret");
        environment.put("GITHUB_TOKEN", "token");
        environment.put("MAVEN_OPTS", "-Dleak=yes");
        environment.put("NODE_OPTIONS", "--require=/tmp/inject.js");
        environment.put("PYTHONPATH", "/tmp/inject");
        environment.put("JAVA_TOOL_OPTIONS", "-Dleak=yes");

        BenchmarkProcessEnvironment.sanitize(environment, home, isolatedTemp);

        assertEquals(BenchmarkProcessEnvironment.fixedPath(), environment.get("PATH"));
        assertFalse(environment.get("PATH").contains(System.getProperty("user.home")));
        assertEquals(home.toString(), environment.get("HOME"));
        assertEquals(isolatedTemp.toString(), environment.get("TMPDIR"));
        assertEquals("UTC", environment.get("TZ"));
        assertFalse(environment.containsKey("DEEPSEEK_API_KEY"));
        assertFalse(environment.containsKey("GITHUB_TOKEN"));
        assertFalse(environment.containsKey("MAVEN_OPTS"));
        assertFalse(environment.containsKey("NODE_OPTIONS"));
        assertFalse(environment.containsKey("PYTHONPATH"));
        assertFalse(environment.containsKey("JAVA_TOOL_OPTIONS"));
    }

    @Test
    void workerCommandUsesAbsoluteClasspathAndNoCredential(@TempDir Path tempDir) throws Exception {
        Path coordinator = Files.createDirectory(tempDir.resolve("coordinator"));
        Path relativeClasses = Files.createDirectory(coordinator.resolve("classes"));
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path home = Files.createDirectory(tempDir.resolve("home"));
        String rawClassPath = "classes" + File.pathSeparator + relativeClasses;
        BenchmarkWorkerProcess process = new BenchmarkWorkerProcess(rawClassPath, coordinator);
        BenchmarkProtocol.WorkerRequest request = new BenchmarkProtocol.WorkerRequest(
                BenchmarkProtocol.VERSION,
                "glm",
                "glm-5.3-flash",
                null,
                "provider-secret",
                "react",
                BenchmarkToolProfile.FILE_ONLY,
                new BenchmarkProtocol.AgentLimits(77_777, 23, 5, 456_789, 16_384),
                "2026-08-31",
                "task",
                workspace.toString(),
                home.toString(),
                tempDir.toString());

        List<String> command = process.command(request, workspace, home);

        int classPathIndex = command.indexOf("-cp") + 1;
        for (String entry : command.get(classPathIndex).split(
                java.util.regex.Pattern.quote(File.pathSeparator))) {
            assertTrue(Path.of(entry).isAbsolute());
        }
        assertTrue(command.stream().anyMatch(value -> value.equals(
                "-Dpaicli.react.token.budget=77777")));
        assertTrue(command.stream().anyMatch(value -> value.equals(
                "-Dpaicli.react.hard.max.iterations=23")));
        assertTrue(command.stream().anyMatch(value -> value.equals(
                "-Dpaicli.react.stagnation.window=5")));
        assertTrue(command.contains("-Dpaicli.prompt.runtime.zone=UTC"));
        assertTrue(command.contains("-Dpaicli.prompt.runtime.date=2026-08-31"));
        assertFalse(String.join("\n", command).contains("provider-secret"));
    }
}
