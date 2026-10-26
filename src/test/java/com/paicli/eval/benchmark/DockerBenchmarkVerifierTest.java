package com.paicli.eval.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerBenchmarkVerifierTest {
    private static final String IMAGE_ID = "sha256:" + "a".repeat(64);
    private static final Path DOCKER = Path.of("/opt/test/bin/docker");

    @Test
    void acceptsOnlyAbsoluteDockerPathAndFrozenLowercaseImageId() {
        DockerBenchmarkVerifier.ProcessExecutor unused = (builder, stdin, timeout, out, err) -> {
            throw new AssertionError("process executor must not run");
        };
        DockerBenchmarkVerifier.HostIdentityResolver identity =
                workspace -> new DockerBenchmarkVerifier.HostIdentity(501, 20);

        new DockerBenchmarkVerifier(DOCKER, IMAGE_ID, unused, identity);

        assertThrows(IllegalArgumentException.class,
                () -> new DockerBenchmarkVerifier(Path.of("docker"), IMAGE_ID, unused, identity));
        assertThrows(IllegalArgumentException.class,
                () -> new DockerBenchmarkVerifier(DOCKER, "python:latest", unused, identity));
        assertThrows(IllegalArgumentException.class,
                () -> new DockerBenchmarkVerifier(
                        DOCKER, "repo@example/validator@" + IMAGE_ID, unused, identity));
        assertThrows(IllegalArgumentException.class,
                () -> new DockerBenchmarkVerifier(
                        DOCKER, "sha256:" + "A".repeat(64), unused, identity));
        assertThrows(IllegalArgumentException.class,
                () -> new DockerBenchmarkVerifier(
                        DOCKER, "sha256:" + "a".repeat(63), unused, identity));
    }

    @Test
    void buildsHardenedDirectArgvMaterializesMountsAndCleansCid(
            @TempDir Path tempDir) throws Exception {
        Fixture fixture = fixture(tempDir);
        Path evidence = evidence(fixture, "safe answer");
        String cid = "b".repeat(64);
        List<List<String>> commands = new ArrayList<>();
        List<Path> workingDirectories = new ArrayList<>();
        List<Integer> stdoutLimits = new ArrayList<>();
        List<Integer> stderrLimits = new ArrayList<>();
        List<Duration> timeouts = new ArrayList<>();
        List<Map<String, String>> environments = new ArrayList<>();
        DockerBenchmarkVerifier.ProcessExecutor executor = (builder, stdin, timeout, out, err) -> {
            List<String> command = List.copyOf(builder.command());
            commands.add(command);
            workingDirectories.add(builder.directory().toPath());
            stdoutLimits.add(out);
            stderrLimits.add(err);
            timeouts.add(timeout);
            environments.add(Map.copyOf(builder.environment()));
            if ("run".equals(command.get(1))) {
                String evidenceMount = command.stream()
                        .filter(value -> value.endsWith(",target=/evidence/envelope.json,readonly"))
                        .findFirst()
                        .orElseThrow();
                Path mountedEvidence = Path.of(evidenceMount
                        .substring("type=bind,source=".length(), evidenceMount.indexOf(",target=")));
                String evidenceJson = Files.readString(mountedEvidence);
                assertTrue(evidenceJson.contains("\"answer\":\"safe answer\""));
                assertTrue(evidenceJson.contains("\"calls\":1"));
                assertFalse(evidenceJson.contains("exact-secret-value"));
                Path cidFile = Path.of(valueAfter(command, "--cidfile"));
                Files.writeString(cidFile, cid);
                return new BenchmarkSubprocess.Result(
                        false,
                        0,
                        "Authorization: Bearer must-not-survive\nevidence=" + evidence,
                        "API_KEY=must-not-survive\nworkspace=" + fixture.workspace(),
                        false,
                        false,
                        17L);
            }
            return new BenchmarkSubprocess.Result(false, 0, "", "", false, false, 2L);
        };
        DockerBenchmarkVerifier verifier = verifier(executor, 501, 20);
        CaseDefinition.VerifierInvocation invocation = new CaseDefinition.VerifierInvocation(
                fixture.suite(),
                List.of("bash", "validators/check.sh", "{workspace}", "{evidence}"));

        BenchmarkVerifier.Result result = verifier.verify(
                invocation, fixture.workspace(), fixture.home(), evidence, Duration.ofSeconds(30));

        assertEquals(BenchmarkVerifier.Status.PASSED, result.status());
        assertEquals(0, result.exitCode());
        assertEquals(17L, result.elapsedMillis());
        assertEquals(
                List.of("bash", "validators/check.sh", "/workspace", "/evidence/envelope.json"),
                result.arguments());
        assertFalse(String.join("\n", result.arguments()).contains(tempDir.toString()));
        assertFalse(result.stdout().contains("must-not-survive"));
        assertFalse(result.stderr().contains("must-not-survive"));
        assertTrue(result.stdout().contains(SecretRedactor.REDACTED));
        assertTrue(result.stderr().contains(SecretRedactor.REDACTED));
        assertTrue(result.stdout().contains("/evidence/envelope.json"), result.stdout());
        assertTrue(result.stderr().contains("/workspace"), result.stderr());
        assertFalse(result.stdout().contains(tempDir.toString()), result.stdout());
        assertFalse(result.stderr().contains(tempDir.toString()), result.stderr());
        assertTrue(result.sandboxed());
        assertTrue(result.sandboxProfileFingerprint().startsWith("docker-"));

        assertEquals(2, commands.size());
        assertEquals(List.of(256 * 1024, 16 * 1024), stdoutLimits);
        assertEquals(List.of(256 * 1024, 16 * 1024), stderrLimits);
        List<String> run = commands.get(0);
        assertEquals(DOCKER.toString(), run.get(0));
        assertEquals("run", run.get(1));
        assertOption(run, "--pull=never");
        assertFalse(run.contains("--rm"));
        assertOptionValue(run, "--network", "none");
        assertOption(run, "--read-only");
        assertOptionValue(run, "--user", "501:20");
        assertOptionValue(run, "--cap-drop", "ALL");
        assertOptionValue(run, "--security-opt", "no-new-privileges:true");
        assertOptionValue(run, "--pids-limit", "64");
        assertOptionValue(run, "--memory", "256m");
        assertOptionValue(run, "--memory-swap", "256m");
        assertOptionValue(run, "--cpus", "0.5");
        assertOptionValue(run, "--ulimit", "nofile=256:256");
        assertOptionValue(run, "--tmpfs", "/tmp:rw,nosuid,nodev,noexec,size=64m,mode=1777");
        assertOptionValue(run, "--workdir", "/suite");
        assertOptionValue(run, "--entrypoint", "bash");
        assertTrue(run.contains("type=bind,source=" + fixture.suite().toRealPath()
                + ",target=/suite,readonly"));
        assertTrue(run.contains("type=bind,source=" + fixture.workspace().toRealPath()
                + ",target=/workspace,readonly"));
        assertTrue(run.contains("type=bind,source=" + evidence.toRealPath()
                + ",target=/evidence/envelope.json,readonly"));
        int image = run.indexOf(IMAGE_ID);
        assertTrue(image > 0);
        assertEquals(
                List.of("validators/check.sh", "/workspace", "/evidence/envelope.json"),
                run.subList(image + 1, run.size()));
        Path expectedTemp = fixture.episode().resolve("verifier-docker-tmp").toRealPath();
        assertEquals(expectedTemp, workingDirectories.get(0).toRealPath());

        assertEquals(List.of(DOCKER.toString(), "rm", "-f", cid), commands.get(1));
        assertEquals(Duration.ofSeconds(30), timeouts.get(0));
        assertEquals(Duration.ofSeconds(10), timeouts.get(1));
        assertSanitizedEnvironment(environments.get(0), fixture.home().toRealPath(), expectedTemp);
        assertSanitizedEnvironment(environments.get(1), expectedTemp, expectedTemp);
        assertFalse(Files.exists(expectedTemp.resolve("container.cid")));
    }

    @Test
    void mapsFailureAndTimeoutAndBoundsInjectedOutput(@TempDir Path tempDir) throws Exception {
        Fixture failedFixture = fixture(Files.createDirectory(tempDir.resolve("failed")));
        DockerBenchmarkVerifier failedVerifier = verifier(
                completingExecutor(new BenchmarkSubprocess.Result(
                        false, 7, "failure", "diagnostic", false, false, 21L)),
                1000,
                1000);
        BenchmarkVerifier.Result failed = failedVerifier.verify(
                invocation(failedFixture),
                failedFixture.workspace(),
                failedFixture.home(),
                Duration.ofSeconds(5));

        assertEquals(BenchmarkVerifier.Status.FAILED, failed.status());
        assertEquals(7, failed.exitCode());
        assertFalse(failed.passed());

        Fixture timeoutFixture = fixture(Files.createDirectory(tempDir.resolve("timeout")));
        String oversized = "ordinary verifier output line\n".repeat(12_000);
        DockerBenchmarkVerifier timeoutVerifier = verifier(
                completingExecutor(new BenchmarkSubprocess.Result(
                        true, null, oversized, oversized, false, false, 99L)),
                1000,
                1000);
        BenchmarkVerifier.Result timedOut = timeoutVerifier.verify(
                invocation(timeoutFixture),
                timeoutFixture.workspace(),
                timeoutFixture.home(),
                Duration.ofSeconds(5));

        assertEquals(BenchmarkVerifier.Status.TIMEOUT, timedOut.status());
        assertNull(timedOut.exitCode());
        assertTrue(timedOut.stdoutTruncated());
        assertTrue(timedOut.stderrTruncated());
        assertTrue(timedOut.stdout().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                <= 256 * 1024);
        assertTrue(timedOut.stderr().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                <= 256 * 1024);
    }

    @Test
    void cleanupFailureAndTimeoutRetainCidAndSuppressVerifierResult(@TempDir Path tempDir)
            throws Exception {
        Fixture failedCleanup = fixture(Files.createDirectory(tempDir.resolve("cleanup-failed")));
        Path[] failedCid = new Path[1];
        DockerBenchmarkVerifier.ProcessExecutor nonzero = (builder, stdin, timeout, out, err) -> {
            if ("run".equals(builder.command().get(1))) {
                failedCid[0] = Path.of(valueAfter(builder.command(), "--cidfile"));
                Files.writeString(failedCid[0], "c".repeat(64));
                return new BenchmarkSubprocess.Result(false, 0, "PASS", "", false, false, 5L);
            }
            return new BenchmarkSubprocess.Result(false, 1, "", "cleanup failed", false, false, 2L);
        };
        DockerBenchmarkVerifier.InfrastructureException nonzeroFailure = assertThrows(
                DockerBenchmarkVerifier.InfrastructureException.class,
                () -> verifier(nonzero, 501, 20).verify(
                        invocation(failedCleanup), failedCleanup.workspace(), failedCleanup.home(),
                        Duration.ofSeconds(5)));
        assertTrue(nonzeroFailure.getMessage().contains("cleanup failed"));
        assertTrue(Files.isRegularFile(failedCid[0]));

        Fixture timedCleanup = fixture(Files.createDirectory(tempDir.resolve("cleanup-timeout")));
        Path[] timedCid = new Path[1];
        DockerBenchmarkVerifier.ProcessExecutor timeout = (builder, stdin, duration, out, err) -> {
            if ("run".equals(builder.command().get(1))) {
                timedCid[0] = Path.of(valueAfter(builder.command(), "--cidfile"));
                Files.writeString(timedCid[0], "d".repeat(64));
                return new BenchmarkSubprocess.Result(false, 7, "", "failed", false, false, 5L);
            }
            assertEquals(Duration.ofSeconds(10), duration);
            assertEquals(16 * 1024, out);
            assertEquals(16 * 1024, err);
            return new BenchmarkSubprocess.Result(true, null, "", "", false, false, 10_000L);
        };
        DockerBenchmarkVerifier.InfrastructureException timeoutFailure = assertThrows(
                DockerBenchmarkVerifier.InfrastructureException.class,
                () -> verifier(timeout, 501, 20).verify(
                        invocation(timedCleanup), timedCleanup.workspace(), timedCleanup.home(),
                        Duration.ofSeconds(5)));
        assertTrue(timeoutFailure.getMessage().contains("cleanup timed out"));
        assertTrue(Files.isRegularFile(timedCid[0]));
    }

    @Test
    void missingInvalidOrUndrainedCidCleanupIsTypedInfrastructureFailure(
            @TempDir Path tempDir) throws Exception {
        Fixture missing = fixture(Files.createDirectory(tempDir.resolve("missing")));
        DockerBenchmarkVerifier.ProcessExecutor exit125 = (builder, stdin, timeout, out, err) ->
                new BenchmarkSubprocess.Result(false, 125, "", "docker failed", false, false, 1L);
        DockerBenchmarkVerifier.InfrastructureException missingFailure = assertThrows(
                DockerBenchmarkVerifier.InfrastructureException.class,
                () -> verifier(exit125, 501, 20).verify(
                        invocation(missing), missing.workspace(), missing.home(), Duration.ofSeconds(5)));
        assertTrue(missingFailure.getMessage().contains("cidfile"));

        Fixture exit125WithCid = fixture(Files.createDirectory(tempDir.resolve("exit-125-with-cid")));
        Path[] exit125Cid = new Path[1];
        DockerBenchmarkVerifier.ProcessExecutor exit125Cleanup = (builder, stdin, timeout, out, err) -> {
            if ("run".equals(builder.command().get(1))) {
                exit125Cid[0] = Path.of(valueAfter(builder.command(), "--cidfile"));
                Files.writeString(exit125Cid[0], "a".repeat(64));
                return new BenchmarkSubprocess.Result(
                        false, 125, "", "docker failed", false, false, 1L);
            }
            return new BenchmarkSubprocess.Result(false, 0, "", "", false, false, 1L);
        };
        DockerBenchmarkVerifier.InfrastructureException exit125Failure = assertThrows(
                DockerBenchmarkVerifier.InfrastructureException.class,
                () -> verifier(exit125Cleanup, 501, 20).verify(
                        invocation(exit125WithCid), exit125WithCid.workspace(),
                        exit125WithCid.home(), Duration.ofSeconds(5)));
        assertTrue(exit125Failure.getMessage().contains("could not start"));
        assertFalse(Files.exists(exit125Cid[0]));

        Fixture invalid = fixture(Files.createDirectory(tempDir.resolve("invalid")));
        Path[] invalidCid = new Path[1];
        DockerBenchmarkVerifier.ProcessExecutor invalidExecutor = (builder, stdin, timeout, out, err) -> {
            invalidCid[0] = Path.of(valueAfter(builder.command(), "--cidfile"));
            Files.writeString(invalidCid[0], "not-a-container-id");
            return new BenchmarkSubprocess.Result(false, 0, "PASS", "", false, false, 1L);
        };
        assertThrows(DockerBenchmarkVerifier.InfrastructureException.class,
                () -> verifier(invalidExecutor, 501, 20).verify(
                        invocation(invalid), invalid.workspace(), invalid.home(), Duration.ofSeconds(5)));
        assertTrue(Files.isRegularFile(invalidCid[0]));

        Fixture undrained = fixture(Files.createDirectory(tempDir.resolve("undrained")));
        Path[] undrainedCid = new Path[1];
        DockerBenchmarkVerifier.ProcessExecutor undrainedExecutor = (builder, stdin, timeout, out, err) -> {
            if ("run".equals(builder.command().get(1))) {
                undrainedCid[0] = Path.of(valueAfter(builder.command(), "--cidfile"));
                Files.writeString(undrainedCid[0], "e".repeat(64));
                return new BenchmarkSubprocess.Result(false, 0, "PASS", "", false, false, 1L);
            }
            return new BenchmarkSubprocess.Result(false, 0, null, "", false, false, 1L);
        };
        DockerBenchmarkVerifier.InfrastructureException undrainedFailure = assertThrows(
                DockerBenchmarkVerifier.InfrastructureException.class,
                () -> verifier(undrainedExecutor, 501, 20).verify(
                        invocation(undrained), undrained.workspace(), undrained.home(),
                        Duration.ofSeconds(5)));
        assertTrue(undrainedFailure.getMessage().contains("not fully drained"));
        assertTrue(Files.isRegularFile(undrainedCid[0]));
    }

    @Test
    void rejectsRootIdentityAndMismatchedEpisodeBeforeStartingProcess(
            @TempDir Path tempDir) throws Exception {
        Fixture fixture = fixture(tempDir);
        DockerBenchmarkVerifier.ProcessExecutor unused = (builder, stdin, timeout, out, err) -> {
            throw new AssertionError("process executor must not run");
        };

        DockerBenchmarkVerifier rootVerifier = verifier(unused, 0, 20);
        assertThrows(IOException.class, () -> rootVerifier.verify(
                invocation(fixture), fixture.workspace(), fixture.home(), Duration.ofSeconds(5)));

        Fixture rootGroupFixture = fixture(Files.createDirectory(tempDir.resolve("root-group")));
        DockerBenchmarkVerifier rootGroupVerifier = verifier(
                completingExecutor(new BenchmarkSubprocess.Result(
                        false, 0, "PASS", "", false, false, 1L)),
                501,
                0);
        assertTrue(rootGroupVerifier.verify(
                invocation(rootGroupFixture),
                rootGroupFixture.workspace(),
                rootGroupFixture.home(),
                Duration.ofSeconds(5)).passed());

        Path otherParent = Files.createDirectory(tempDir.resolve("other"));
        Path otherHome = Files.createDirectory(otherParent.resolve("home"));
        DockerBenchmarkVerifier normalVerifier = verifier(unused, 501, 20);
        assertThrows(IOException.class, () -> normalVerifier.verify(
                invocation(fixture), fixture.workspace(), otherHome, Duration.ofSeconds(5)));
    }

    private static DockerBenchmarkVerifier verifier(DockerBenchmarkVerifier.ProcessExecutor executor,
                                                     long uid,
                                                     long gid) {
        return new DockerBenchmarkVerifier(
                DOCKER,
                IMAGE_ID,
                executor,
                workspace -> new DockerBenchmarkVerifier.HostIdentity(uid, gid));
    }

    private static DockerBenchmarkVerifier.ProcessExecutor completingExecutor(
            BenchmarkSubprocess.Result verifierResult) {
        return (builder, stdin, timeout, out, err) -> {
            if ("run".equals(builder.command().get(1))) {
                Path cidFile = Path.of(valueAfter(builder.command(), "--cidfile"));
                Files.writeString(cidFile, "f".repeat(64));
                return verifierResult;
            }
            return new BenchmarkSubprocess.Result(false, 0, "", "", false, false, 1L);
        };
    }

    private static CaseDefinition.VerifierInvocation invocation(Fixture fixture) {
        return new CaseDefinition.VerifierInvocation(
                fixture.suite(), List.of("bash", "validators/check.sh", "{workspace}"));
    }

    private static Fixture fixture(Path root) throws IOException {
        Path episode = Files.createDirectory(root.resolve("episode"));
        Path workspace = Files.createDirectory(episode.resolve("workspace"));
        Path home = Files.createDirectory(episode.resolve("home"));
        Path suite = Files.createDirectory(root.resolve("suite"));
        Path validators = Files.createDirectory(suite.resolve("validators"));
        Files.writeString(validators.resolve("check.sh"), "#!/usr/bin/env bash\nexit 0\n");
        return new Fixture(episode, workspace, home, suite);
    }

    private static Path evidence(Fixture fixture, String answer) throws IOException {
        return BenchmarkEvidenceEnvelope.write(
                fixture.episode(),
                fixture.workspace(),
                fixture.home(),
                "case-one",
                1,
                CaseDefinition.Mode.REACT,
                BenchmarkToolProfile.FILE_ONLY,
                answer,
                new TracingLlmClient.Metrics(1, 10, 5, 0, 1, 20),
                "exact-secret-value");
    }

    private static String valueAfter(List<String> command, String option) {
        int index = command.indexOf(option);
        assertTrue(index >= 0 && index + 1 < command.size(), "missing option: " + option);
        return command.get(index + 1);
    }

    private static void assertOption(List<String> command, String option) {
        assertTrue(command.contains(option), "missing option: " + option);
    }

    private static void assertOptionValue(List<String> command, String option, String expected) {
        assertEquals(expected, valueAfter(command, option), "unexpected value for " + option);
    }

    private static void assertSanitizedEnvironment(Map<String, String> environment,
                                                   Path home,
                                                   Path temp) {
        assertEquals(Set.of(
                "PATH", "HOME", "USERPROFILE", "XDG_CONFIG_HOME", "XDG_CACHE_HOME",
                "XDG_DATA_HOME", "TMPDIR", "TMP", "TEMP", "TZ", "LANG", "LC_ALL"),
                environment.keySet());
        assertEquals(home.toString(), environment.get("HOME"));
        assertEquals(temp.toString(), environment.get("TMPDIR"));
        assertEquals("UTC", environment.get("TZ"));
        assertTrue(environment.keySet().stream()
                .noneMatch(BenchmarkProcessEnvironment::isCredentialLike));
    }

    private record Fixture(Path episode, Path workspace, Path home, Path suite) {
    }
}
