package com.paicli.eval.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BenchmarkWorkerProcessTest {
    @Test
    void workerProtocolPersistsExplicitToolProfileAndDefaultsMissingProfileToFileOnly() throws Exception {
        BenchmarkProtocol.WorkerRequest localCommand = new BenchmarkProtocol.WorkerRequest(
                BenchmarkProtocol.VERSION,
                "glm",
                "glm-5.3-flash",
                null,
                "protocol-key-never-log-123456",
                "react",
                BenchmarkToolProfile.LOCAL_COMMAND,
                new BenchmarkProtocol.AgentLimits(12_345, 17, 4, 234_567, 16_384),
                "2026-08-31",
                "task",
                "/private/tmp/workspace",
                "/private/tmp/home",
                "/private/tmp/episode");

        byte[] encoded = BenchmarkProtocol.writeRequest(localCommand);
        BenchmarkProtocol.WorkerRequest decoded = BenchmarkProtocol.readRequest(encoded);
        assertEquals(BenchmarkToolProfile.LOCAL_COMMAND, decoded.toolProfile());
        assertEquals(new BenchmarkProtocol.AgentLimits(
                12_345, 17, 4, 234_567, 16_384), decoded.agentLimits());
        assertEquals(234_567, decoded.agentLimits().contextWindowCapTokens());
        assertEquals(16_384, decoded.agentLimits().maxOutputTokensPerCall());
        assertFalse(localCommand.toString().contains(localCommand.apiKey()));

        String withoutProfile = """
                {"protocolVersion":%d,"provider":"glm","model":"glm-5.3-flash",
                 "apiKey":"protocol-key-never-log-123456","mode":"react",
                 "agentLimits":{"tokenBudget":500000,"hardMaxIterations":80,"stagnationWindow":3,
                                "contextWindowCapTokens":1000000,"maxOutputTokensPerCall":16384},
                 "runtimeDate":"2026-08-31","prompt":"task",
                 "workspace":"/private/tmp/workspace","home":"/private/tmp/home",
                 "episodeDirectory":"/private/tmp/episode"}
                """.formatted(BenchmarkProtocol.VERSION);
        BenchmarkProtocol.WorkerRequest defaulted = BenchmarkProtocol.readRequest(
                withoutProfile.getBytes(StandardCharsets.UTF_8));
        assertEquals(BenchmarkToolProfile.FILE_ONLY, defaulted.toolProfile());

        String withoutLimits = withoutProfile.replaceFirst(
                "\"agentLimits\"\\s*:\\s*\\{[^}]*},\\s*", "");
        assertThrows(IOException.class, () -> BenchmarkProtocol.readRequest(
                withoutLimits.getBytes(StandardCharsets.UTF_8)));

        String withoutContextCap = withoutProfile.replaceFirst(
                ",\\s*\"contextWindowCapTokens\":1000000", "");
        assertThrows(IOException.class, () -> BenchmarkProtocol.readRequest(
                withoutContextCap.getBytes(StandardCharsets.UTF_8)));

        String withoutOutputCap = withoutProfile.replaceFirst(
                ",\\s*\"maxOutputTokensPerCall\":16384", "");
        assertThrows(IOException.class, () -> BenchmarkProtocol.readRequest(
                withoutOutputCap.getBytes(StandardCharsets.UTF_8)));

        for (int unsupportedVersion : new int[]{BenchmarkProtocol.VERSION - 1,
                BenchmarkProtocol.VERSION + 1}) {
            String wrongVersion = withoutProfile.replace(
                    "\"protocolVersion\":" + BenchmarkProtocol.VERSION,
                    "\"protocolVersion\":" + unsupportedVersion);
            assertThrows(IOException.class, () -> BenchmarkProtocol.readRequest(
                    wrongVersion.getBytes(StandardCharsets.UTF_8)));
        }
        String duplicateModel = withoutProfile.replace(
                "\"model\":\"glm-5.3-flash\"",
                "\"model\":\"glm-5.3-flash\",\"model\":\"glm-5.3-flash\"");
        assertThrows(IOException.class, () -> BenchmarkProtocol.readRequest(
                duplicateModel.getBytes(StandardCharsets.UTF_8)));
        String unknownField = withoutProfile.replace(
                "\"prompt\":\"task\"",
                "\"prompt\":\"task\",\"unregisteredOverride\":true");
        assertThrows(IOException.class, () -> BenchmarkProtocol.readRequest(
                unknownField.getBytes(StandardCharsets.UTF_8)));

        BenchmarkProtocol.WorkerRequest oversized = new BenchmarkProtocol.WorkerRequest(
                BenchmarkProtocol.VERSION,
                "glm",
                "glm-5.3-flash",
                null,
                "protocol-key-never-log-123456",
                "react",
                BenchmarkToolProfile.FILE_ONLY,
                BenchmarkProtocol.AgentLimits.developmentDefaults(),
                "2026-08-31",
                "x".repeat(BenchmarkProtocol.MAX_REQUEST_BYTES),
                "/private/tmp/workspace",
                "/private/tmp/home",
                "/private/tmp/episode");
        assertThrows(IOException.class, () -> BenchmarkProtocol.writeRequest(oversized));

        assertEquals(1_000_000,
                BenchmarkProtocol.AgentLimits.developmentDefaults().contextWindowCapTokens());
        assertEquals(16_384,
                BenchmarkProtocol.AgentLimits.developmentDefaults().maxOutputTokensPerCall());
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkProtocol.AgentLimits(0, 10, 2, 8_000, 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkProtocol.AgentLimits(10_000_001, 10, 2, 8_000, 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkProtocol.AgentLimits(10, 0, 2, 8_000, 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkProtocol.AgentLimits(10, 100_001, 2, 8_000, 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkProtocol.AgentLimits(10, 10, 1, 8_000, 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkProtocol.AgentLimits(10, 10, 11, 8_000, 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkProtocol.AgentLimits(
                        10, 100_000, 10_001, 8_000, 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkProtocol.AgentLimits(10, 10, 2, 7_999, 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkProtocol.AgentLimits(10, 10, 2, 10_000_001, 1_000));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkProtocol.AgentLimits(10, 10, 2, 8_000, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkProtocol.AgentLimits(10, 10, 2, 20_000, 16_385));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkProtocol.AgentLimits(10, 10, 2, 8_000, 8_001));
    }

    @Test
    void transfersRequestOverStdinToIndependentJvmWithoutNetwork(@TempDir Path tempDir) throws Exception {
        Path episode = Files.createDirectory(tempDir.resolve("episode"));
        Path workspace = Files.createDirectory(episode.resolve("workspace"));
        Path home = Files.createDirectory(episode.resolve("home"));
        String secret = "provider-key-stdin-only-123456789";
        BenchmarkProtocol.WorkerRequest request = new BenchmarkProtocol.WorkerRequest(
                BenchmarkProtocol.VERSION,
                "deepseek",
                "deepseek-v4-flash",
                null,
                secret,
                "unsupported-mode",
                BenchmarkToolProfile.FILE_ONLY,
                BenchmarkProtocol.AgentLimits.developmentDefaults(),
                "2026-08-31",
                "must not run",
                workspace.toString(),
                home.toString(),
                episode.toString());

        BenchmarkCoordinatorMain.WorkerExecution execution = new BenchmarkWorkerProcess().execute(
                request, workspace, home, Duration.ofSeconds(15));

        assertEquals(BenchmarkCoordinatorMain.WorkerStatus.COMPLETED, execution.status());
        assertNotNull(execution.response());
        assertFalse(execution.response().success());
        assertEquals("IllegalArgumentException", execution.response().errorType());
        assertFalse(execution.diagnostic().contains(secret));
        assertFalse(BenchmarkSecretCanary.containsInTree(episode, secret));
    }
}
