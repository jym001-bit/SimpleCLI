package com.paicli.eval.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkCoordinatorOptionsTest {
    private static final String DOCKER_IMAGE = "sha256:" + "a".repeat(64);
    private static final String DOCKER_WORKER_IMAGE = "sha256:" + "b".repeat(64);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-31T01:02:03Z"), ZoneOffset.UTC);

    @Test
    void parsesRequiredAndOptionalArguments() {
        BenchmarkCoordinatorMain.Options options = BenchmarkCoordinatorMain.Options.parse(new String[]{
                "--suite", "benchmarks/dev-suite.json",
                "--provider=hy4-preview",
                "--model", "hy4-preview",
                "--tool-profile", "local-command",
                "--verifier-isolation", "docker",
                "--docker-verifier-image", DOCKER_IMAGE,
                "--docker-executable", "/opt/homebrew/bin/docker",
                "--repeats", "3",
                "--output", "/tmp/paicli-benchmark-test",
                "--run-id", "run-001",
                "--case", "case-a,case-b",
                "--case", "case-c",
                "--timeout-seconds", "90"
        }, CLOCK);

        assertEquals("hunyuan", options.provider());
        assertEquals("hy4-preview", options.model());
        assertEquals(BenchmarkToolProfile.LOCAL_COMMAND, options.toolProfile());
        assertEquals(BenchmarkCoordinatorMain.VerifierIsolation.DOCKER,
                options.verifierIsolation());
        assertEquals(DOCKER_IMAGE, options.dockerVerifierImage());
        assertEquals(Path.of("/opt/homebrew/bin/docker"), options.dockerExecutable());
        assertEquals(3, options.repeats());
        assertEquals(Set.of("case-a", "case-b", "case-c"), options.caseIds());
        assertEquals(90, options.timeout().toSeconds());
        assertEquals(Path.of("/tmp/paicli-benchmark-test"), options.output());
    }

    @Test
    void parsesCodeRagProfileForFrozenSemanticRetrieval() {
        BenchmarkCoordinatorMain.Options options = BenchmarkCoordinatorMain.Options.parse(new String[]{
                "--suite", "suite.json",
                "--provider", "glm",
                "--model", "glm-5.3-flash",
                "--tool-profile", "code-rag"
        }, CLOCK);

        assertEquals(BenchmarkToolProfile.CODE_RAG, options.toolProfile());
    }

    @Test
    void defaultsPrivateOutputBelowRealUserHome() {
        BenchmarkCoordinatorMain.Options options = BenchmarkCoordinatorMain.Options.parse(new String[]{
                "--suite", "suite.json",
                "--provider", "glm",
                "--model", "glm-5.3-flash"
        }, CLOCK);

        Path expected = Path.of(System.getProperty("user.home"),
                ".paicli-benchmark-results").toAbsolutePath().normalize();
        assertEquals(expected, options.output());
        assertEquals(BenchmarkToolProfile.FILE_ONLY, options.toolProfile());
        assertEquals(BenchmarkCoordinatorMain.VerifierIsolation.SEATBELT,
                options.verifierIsolation());
        assertEquals(BenchmarkCoordinatorMain.WorkerIsolation.HOST_DEV,
                options.workerIsolation());
        assertTrue(options.runId().startsWith("run-20260831-010203-"));
    }

    @Test void unboundDevWebProfileFailsBeforeSuiteOrCredentialLoading() {
        for (String isolation : new String[]{"host-dev", "docker-relay"}) {
            var failure = assertThrows(IllegalArgumentException.class, () -> BenchmarkCoordinatorMain.Options.parse(new String[]{
                    "--suite", "does-not-exist.json", "--provider", "glm", "--model", "glm-5.3-flash",
                    "--tool-profile", "mock-web", "--worker-isolation", isolation}, CLOCK));
            assertEquals("dev Coordinator has no frozen Web binding; use formal admission or explicit diagnostic controls", failure.getMessage());
        }
    }

    @Test
    void parsesDockerRelayWorkerAndConstructsItsExecutor(@TempDir Path tempDir) throws Exception {
        Path candidateJar = tempDir.resolve("candidate.jar").toAbsolutePath();
        Files.writeString(candidateJar, "candidate");
        Path runnerJar = BenchmarkRunnerTestArtifact.create(
                tempDir.resolve("runner.jar").toAbsolutePath());

        BenchmarkCoordinatorMain.Options options = BenchmarkCoordinatorMain.Options.parse(new String[]{
                "--suite", "suite.json",
                "--provider", "deepseek",
                "--model", "deepseek-v4-flash",
                "--worker-isolation", "docker-relay",
                "--docker-worker-image", DOCKER_WORKER_IMAGE,
                "--candidate-jar", candidateJar.toString(),
                "--runner-jar", runnerJar.toString(),
                "--docker-executable", "/opt/homebrew/bin/docker"
        }, CLOCK);

        assertEquals(BenchmarkCoordinatorMain.WorkerIsolation.DOCKER_RELAY,
                options.workerIsolation());
        assertEquals(DOCKER_WORKER_IMAGE, options.dockerWorkerImage());
        assertEquals(candidateJar, options.candidateJar());
        assertEquals(runnerJar, options.runnerJar());
        assertEquals(64, options.candidateJarSha256().length());
        assertEquals(64, options.runnerJarSha256().length());
        assertEquals(64, options.runnerContentManifestSha256().length());
        assertTrue(BenchmarkCoordinatorMain.workerFor(options)
                instanceof DockerBenchmarkWorkerProcess);
    }

    @Test
    void rejectsUnknownDuplicateAndInvalidNumericOptions() {
        String[] base = {
                "--suite", "suite.json", "--provider", "glm", "--model", "glm-5.3-flash"
        };
        assertThrows(IllegalArgumentException.class, () -> BenchmarkCoordinatorMain.Options.parse(
                append(base, "--unknown", "x"), CLOCK));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkCoordinatorMain.Options.parse(
                append(base, "--model", "again"), CLOCK));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkCoordinatorMain.Options.parse(
                append(base, "--repeats", "0"), CLOCK));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkCoordinatorMain.Options.parse(
                append(base, "--timeout-seconds", "86401"), CLOCK));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkCoordinatorMain.Options.parse(
                append(base, "--tool-profile", "web-enabled"), CLOCK));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkCoordinatorMain.Options.parse(
                append(base, "--verifier-isolation", "vm"), CLOCK));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkCoordinatorMain.Options.parse(
                append(base, "--verifier-isolation", "docker"), CLOCK));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkCoordinatorMain.Options.parse(
                append(base, "--verifier-isolation", "docker",
                        "--docker-verifier-image", "python:latest"), CLOCK));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkCoordinatorMain.Options.parse(
                append(base, "--docker-verifier-image", DOCKER_IMAGE), CLOCK));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkCoordinatorMain.Options.parse(
                append(base, "--verifier-isolation", "docker",
                        "--docker-verifier-image", DOCKER_IMAGE,
                        "--docker-executable", "docker"), CLOCK));
    }

    @Test
    void rejectsIncompleteOrExcessDockerRelayArguments(@TempDir Path tempDir) throws Exception {
        Path candidateJar = tempDir.resolve("candidate.jar").toAbsolutePath();
        Files.writeString(candidateJar, "candidate");
        Path runnerJar = BenchmarkRunnerTestArtifact.create(
                tempDir.resolve("runner.jar").toAbsolutePath());
        String[] base = {
                "--suite", "suite.json", "--provider", "glm", "--model", "glm-5.3-flash"
        };

        assertThrows(IllegalArgumentException.class, () -> BenchmarkCoordinatorMain.Options.parse(
                append(base, "--worker-isolation", "vm"), CLOCK));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkCoordinatorMain.Options.parse(
                append(base, "--worker-isolation", "docker-relay",
                        "--candidate-jar", candidateJar.toString(),
                        "--runner-jar", runnerJar.toString()), CLOCK));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkCoordinatorMain.Options.parse(
                append(base, "--worker-isolation", "docker-relay",
                        "--docker-worker-image", DOCKER_WORKER_IMAGE,
                        "--runner-jar", runnerJar.toString()), CLOCK));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkCoordinatorMain.Options.parse(
                append(base, "--worker-isolation", "docker-relay",
                        "--docker-worker-image", "paicli-worker:latest",
                        "--candidate-jar", candidateJar.toString(),
                        "--runner-jar", runnerJar.toString()), CLOCK));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkCoordinatorMain.Options.parse(
                append(base, "--worker-isolation", "docker-relay",
                        "--docker-worker-image", DOCKER_WORKER_IMAGE,
                        "--candidate-jar", "candidate.jar",
                        "--runner-jar", runnerJar.toString()), CLOCK));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkCoordinatorMain.Options.parse(
                append(base, "--worker-isolation", "docker-relay",
                        "--docker-worker-image", DOCKER_WORKER_IMAGE,
                        "--candidate-jar", candidateJar.toString()), CLOCK));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkCoordinatorMain.Options.parse(
                append(base, "--worker-isolation", "docker-relay",
                        "--docker-worker-image", DOCKER_WORKER_IMAGE,
                        "--candidate-jar", candidateJar.toString(),
                        "--runner-jar", candidateJar.toString()), CLOCK));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkCoordinatorMain.Options.parse(
                append(base, "--docker-worker-image", DOCKER_WORKER_IMAGE), CLOCK));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkCoordinatorMain.Options.parse(
                append(base, "--candidate-jar", candidateJar.toString()), CLOCK));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkCoordinatorMain.Options.parse(
                append(base, "--runner-jar", runnerJar.toString()), CLOCK));
    }

    @Test
    void rejectsSymlinkCandidateJar(@TempDir Path tempDir) throws Exception {
        Path candidateJar = tempDir.resolve("candidate.jar").toAbsolutePath();
        Files.writeString(candidateJar, "candidate");
        Path runnerJar = BenchmarkRunnerTestArtifact.create(
                tempDir.resolve("runner.jar").toAbsolutePath());
        Path symlink = tempDir.resolve("candidate-link.jar").toAbsolutePath();
        try {
            Files.createSymbolicLink(symlink, candidateJar);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException error) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links unavailable on this test host");
        }

        assertThrows(IllegalArgumentException.class, () -> BenchmarkCoordinatorMain.Options.parse(
                new String[]{
                        "--suite", "suite.json",
                        "--provider", "glm",
                        "--model", "glm-5.3-flash",
                        "--worker-isolation", "docker-relay",
                        "--docker-worker-image", DOCKER_WORKER_IMAGE,
                        "--candidate-jar", symlink.toString(),
                        "--runner-jar", runnerJar.toString()
                }, CLOCK));
    }

    @Test
    void rejectsSymlinkRunnerJar(@TempDir Path tempDir) throws Exception {
        Path candidateJar = tempDir.resolve("candidate.jar").toAbsolutePath();
        Files.writeString(candidateJar, "candidate");
        Path runnerJar = BenchmarkRunnerTestArtifact.create(
                tempDir.resolve("runner.jar").toAbsolutePath());
        Path symlink = tempDir.resolve("runner-link.jar").toAbsolutePath();
        try {
            Files.createSymbolicLink(symlink, runnerJar);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException error) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links unavailable on this test host");
        }

        assertThrows(IllegalArgumentException.class, () -> BenchmarkCoordinatorMain.Options.parse(
                new String[]{
                        "--suite", "suite.json",
                        "--provider", "glm",
                        "--model", "glm-5.3-flash",
                        "--worker-isolation", "docker-relay",
                        "--docker-worker-image", DOCKER_WORKER_IMAGE,
                        "--candidate-jar", candidateJar.toString(),
                        "--runner-jar", symlink.toString()
                }, CLOCK));
    }

    @Test
    void workerConstructionRejectsArtifactReplacementAfterOptionValidation(@TempDir Path tempDir)
            throws Exception {
        Path candidateJar = tempDir.resolve("candidate.jar").toAbsolutePath();
        Files.writeString(candidateJar, "candidate-v1");
        Path runnerJar = BenchmarkRunnerTestArtifact.create(
                tempDir.resolve("runner.jar").toAbsolutePath());
        BenchmarkCoordinatorMain.Options options = BenchmarkCoordinatorMain.Options.parse(
                new String[]{
                        "--suite", "suite.json",
                        "--provider", "glm",
                        "--model", "glm-5.3-flash",
                        "--worker-isolation", "docker-relay",
                        "--docker-worker-image", DOCKER_WORKER_IMAGE,
                        "--candidate-jar", candidateJar.toString(),
                        "--runner-jar", runnerJar.toString()
                }, CLOCK);

        Files.writeString(candidateJar, "candidate-v2");

        java.io.IOException error = assertThrows(
                java.io.IOException.class, () -> BenchmarkCoordinatorMain.workerFor(options));
        assertTrue(error.getMessage().contains("changed after option validation"));
    }

    @Test
    void helpDocumentsDockerRelayWorkerArguments() {
        String usage = BenchmarkCoordinatorMain.usage();

        assertTrue(usage.contains("--worker-isolation <HOST_DEV|DOCKER_RELAY>"));
        assertTrue(usage.contains("--docker-worker-image sha256:<64hex>"));
        assertTrue(usage.contains("--candidate-jar /absolute/path/to/paicli.jar"));
        assertTrue(usage.contains("--runner-jar /absolute/path/to/paicli-agentbench-runner.jar"));
    }

    @Test
    void rejectsDeepseekModelOutsideFrozenProtocol() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> BenchmarkCoordinatorMain.Options.parse(new String[]{
                        "--suite", "suite.json",
                        "--provider", "deepseek",
                        "--model", "deepseek-chat"
                }, CLOCK));

        assertTrue(error.getMessage().contains("requires model deepseek-v4-flash"));
    }

    @Test
    void rejectsHunyuanModelOutsideFrozenProtocol() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> BenchmarkCoordinatorMain.Options.parse(new String[]{
                        "--suite", "suite.json",
                        "--provider", "hunyuan",
                        "--model", "hunyuan-turbo"
                }, CLOCK));

        assertTrue(error.getMessage().contains("requires model hy4-preview"));
    }

    @Test
    void rejectsGlmModelOutsideFrozenProtocol() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> BenchmarkCoordinatorMain.Options.parse(new String[]{
                        "--suite", "suite.json",
                        "--provider", "glm",
                        "--model", "glm-4-flash"
                }, CLOCK));

        assertTrue(error.getMessage().contains("requires model glm-5.3-flash"));
    }

    private static String[] append(String[] base, String... extra) {
        String[] combined = new String[base.length + extra.length];
        System.arraycopy(base, 0, combined, 0, base.length);
        System.arraycopy(extra, 0, combined, base.length, extra.length);
        return combined;
    }
}
