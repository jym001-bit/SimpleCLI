package com.paicli.eval.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BenchmarkVerifierTest {
    @Test
    void executesArgvDirectlyAndMaterializesWorkspace(@TempDir Path tempDir) throws Exception {
        assumeSeatbeltCanRun(tempDir);
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path home = Files.createDirectory(tempDir.resolve("home"));
        Path validator = tempDir.resolve("validator.sh");
        Files.writeString(validator, """
                #!/bin/bash
                test "$1" = "$2"
                """);
        CaseDefinition.VerifierInvocation invocation = new CaseDefinition.VerifierInvocation(
                tempDir,
                List.of("bash", "validator.sh", "{workspace}", workspace.toString()));

        BenchmarkVerifier.Result result = new BenchmarkVerifier().verify(
                invocation, workspace, home, Duration.ofSeconds(5));

        assertEquals(BenchmarkVerifier.Status.PASSED, result.status());
        assertEquals(0, result.exitCode());
        assertTrue(result.arguments().contains(workspace.toAbsolutePath().normalize().toString()));
    }

    @Test
    void nonZeroAndTimeoutRemainDeterministicVerifierFailures(@TempDir Path tempDir) throws Exception {
        assumeSeatbeltCanRun(tempDir);
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path home = Files.createDirectory(tempDir.resolve("home"));
        Files.writeString(tempDir.resolve("fail.sh"), "#!/bin/bash\nexit 7\n");
        Files.writeString(tempDir.resolve("slow.sh"), "#!/bin/bash\nexec sleep 5\n");

        BenchmarkVerifier.Result failed = new BenchmarkVerifier().verify(
                new CaseDefinition.VerifierInvocation(tempDir, List.of("bash", "fail.sh")),
                workspace, home, Duration.ofSeconds(5));
        BenchmarkVerifier.Result timedOut = new BenchmarkVerifier().verify(
                new CaseDefinition.VerifierInvocation(tempDir, List.of("bash", "slow.sh")),
                workspace, home, Duration.ofMillis(100));

        assertEquals(BenchmarkVerifier.Status.FAILED, failed.status());
        assertEquals(7, failed.exitCode());
        assertFalse(failed.passed());
        assertEquals(BenchmarkVerifier.Status.TIMEOUT, timedOut.status());
        assertFalse(timedOut.passed());
    }

    @Test
    void evidencePlaceholderFailsClosedBeforeSandboxWhenEvidenceIsMissing(@TempDir Path tempDir)
            throws Exception {
        Path episode = Files.createDirectory(tempDir.resolve("episode"));
        Path workspace = Files.createDirectory(episode.resolve("workspace"));
        Path home = Files.createDirectory(episode.resolve("home"));

        java.io.IOException error = org.junit.jupiter.api.Assertions.assertThrows(
                java.io.IOException.class,
                () -> new BenchmarkVerifier().verify(
                        new CaseDefinition.VerifierInvocation(
                                tempDir, List.of("bash", "missing.sh", "{evidence}")),
                        workspace,
                        home,
                        null,
                        Duration.ofSeconds(1)));

        assertTrue(error.getMessage().contains("required"));
    }

    @Test
    void seatbeltGrantsLiteralReadOnlyEvidenceWithoutGrantingEpisode(@TempDir Path tempDir)
            throws Exception {
        assumeSeatbeltCanRun(tempDir);
        Path episode = Files.createDirectory(tempDir.resolve("episode"));
        Path workspace = Files.createDirectory(episode.resolve("workspace"));
        Path home = Files.createDirectory(episode.resolve("home"));
        Files.writeString(episode.resolve("not-evidence.txt"), "must-not-read");
        Path evidence = BenchmarkEvidenceEnvelope.write(
                episode,
                workspace,
                home,
                "case-one",
                1,
                CaseDefinition.Mode.REACT,
                BenchmarkToolProfile.FILE_ONLY,
                "visible answer",
                new TracingLlmClient.Metrics(1, 7, 3, 0, 0, 11),
                "provider-key");
        Files.writeString(tempDir.resolve("evidence.sh"), """
                #!/bin/bash
                set -eu
                python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); assert d["answer"] == "visible answer"; assert d["llmMetrics"]["calls"] == 1' "$1"
                ! cat "$(dirname "$(dirname "$1")")/not-evidence.txt" >/dev/null 2>&1
                ! echo changed > "$1"
                """);

        BenchmarkVerifier.Result result = new BenchmarkVerifier().verify(
                new CaseDefinition.VerifierInvocation(
                        tempDir, List.of("bash", "evidence.sh", "{evidence}")),
                workspace,
                home,
                evidence,
                Duration.ofSeconds(10));

        assertEquals(BenchmarkVerifier.Status.PASSED, result.status());
        assertEquals("visible answer",
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(evidence.toFile())
                        .path("answer").asText());
    }

    @Test
    void seatbeltAllowsNormalRuntimeButDeniesOutsideReadWriteWorkspaceMutationAndNetwork(
            @TempDir Path tempDir) throws Exception {
        assumeSeatbeltCanRun(tempDir);
        Path episode = Files.createDirectory(tempDir.resolve("episode"));
        Path workspace = Files.createDirectory(episode.resolve("workspace"));
        Path home = Files.createDirectory(episode.resolve("home"));
        Files.writeString(workspace.resolve("input.txt"), "workspace-data");
        Files.writeString(episode.resolve(".env"), "HOST_SECRET=must-not-read");
        Path validators = Files.createDirectory(tempDir.resolve("validators"));
        Files.writeString(validators.resolve("normal.sh"), """
                #!/bin/bash
                set -eu
                test "$(cat "$1/input.txt")" = "workspace-data"
                python3 -c 'print("runtime-ok")' >/dev/null
                tmp_file="$(mktemp "${TMPDIR}/normal.XXXXXX")"
                echo ok > "$tmp_file"
                """);
        Files.writeString(validators.resolve("outside-read.sh"), """
                #!/bin/bash
                set -eu
                cat "$1/../.env" >/dev/null
                """);
        Files.writeString(validators.resolve("outside-write.sh"), """
                #!/bin/bash
                set -eu
                echo escaped > "$1/../escape.txt"
                """);
        Files.writeString(validators.resolve("workspace-write.sh"), """
                #!/bin/bash
                set -eu
                echo mutated > "$1/input.txt"
                """);
        Files.writeString(validators.resolve("network.sh"), """
                #!/bin/bash
                set -eu
                python3 - <<'PY'
                import socket
                sock = socket.socket()
                sock.bind(("127.0.0.1", 0))
                PY
                """);

        BenchmarkVerifier verifier = new BenchmarkVerifier();
        assertEquals(BenchmarkVerifier.Status.PASSED,
                verifyScript(verifier, validators, "normal.sh", workspace, home).status());
        assertEquals(BenchmarkVerifier.Status.FAILED,
                verifyScript(verifier, validators, "outside-read.sh", workspace, home).status());
        assertEquals(BenchmarkVerifier.Status.FAILED,
                verifyScript(verifier, validators, "outside-write.sh", workspace, home).status());
        assertEquals(BenchmarkVerifier.Status.FAILED,
                verifyScript(verifier, validators, "workspace-write.sh", workspace, home).status());
        assertEquals(BenchmarkVerifier.Status.FAILED,
                verifyScript(verifier, validators, "network.sh", workspace, home).status());

        assertFalse(Files.exists(episode.resolve("escape.txt")));
        assertEquals("workspace-data", Files.readString(workspace.resolve("input.txt")));
        assertEquals("HOST_SECRET=must-not-read", Files.readString(episode.resolve(".env")));
    }

    private static BenchmarkVerifier.Result verifyScript(BenchmarkVerifier verifier,
                                                         Path validators,
                                                         String script,
                                                         Path workspace,
                                                         Path home) throws Exception {
        return verifier.verify(
                new CaseDefinition.VerifierInvocation(
                        validators, List.of("bash", script, "{workspace}")),
                workspace,
                home,
                Duration.ofSeconds(10));
    }

    private static void assumeSeatbeltCanRun(Path tempDir) throws Exception {
        Path executable = Path.of("/usr/bin/sandbox-exec");
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("mac")
                        && Files.isExecutable(executable),
                "macOS sandbox-exec is unavailable");

        Path probe = Files.createDirectory(tempDir.resolve(".seatbelt-probe"));
        Path workspace = Files.createDirectory(probe.resolve("workspace"));
        Path verifierTemp = Files.createDirectory(probe.resolve("verifier-tmp"));
        Files.writeString(probe.resolve("probe.sh"), "#!/bin/bash\nexit 0\n");
        try {
            BenchmarkVerifierSandbox.prepare(
                    new CaseDefinition.VerifierInvocation(probe, List.of("bash", "probe.sh")),
                    workspace,
                    verifierTemp);
        } catch (BenchmarkVerifierSandbox.SandboxUnavailableException unavailable) {
            assumeTrue(false, unavailable.getMessage());
        }
    }
}
