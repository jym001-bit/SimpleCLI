package com.paicli.eval.benchmark;

import com.paicli.eval.benchmark.relay.BenchmarkFramedChannel;
import com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol;
import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerBenchmarkWorkerProcessTest {
    private static final Path DOCKER = Path.of("/opt/test/bin/docker");
    private static final String IMAGE = "sha256:" + "7".repeat(64);
    private static final String KEY = "test-provider-key-never-cross-boundary";
    private static final String BASE_URL = "https://private-provider.example.invalid/v1";

    @Test
    void f2PermissionDamageDoesNotDowngradeVisibleSymlinksOrOtherIoFaults(@TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path denied = Files.createDirectory(workspace.resolve("archive"));
        Files.writeString(denied.resolve("sentinel.txt"), "synthetic fixture only");
        Files.setPosixFilePermissions(denied, java.nio.file.attribute.PosixFilePermissions.fromString("---------"));
        try {
            assertFalse(DockerBenchmarkWorkerProcess.hasUnsafeF2WorkspaceEntryAfterPermissionDamage(workspace));
            Files.createSymbolicLink(workspace.resolve("unexpected-link"), Path.of("archive/sentinel.txt"));
            assertTrue(DockerBenchmarkWorkerProcess.hasUnsafeF2WorkspaceEntryAfterPermissionDamage(workspace));
        } finally {
            Files.setPosixFilePermissions(denied, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
        assertTrue(DockerBenchmarkWorkerProcess.hasUnsafeF2WorkspaceEntryAfterPermissionDamage(tempDir.resolve("missing")),
                "unrelated IO failures must not become permission-damage allowances");
    }

    @Test
    void localCommandRelaysRealProcessStartAndFinishIntoPrivateHostAudit(@TempDir Path tempDir) throws Exception {
        Fixture f = fixture(tempDir);
        var launcher = new FakeDockerLauncher(f.workspace(), false);
        var provider = new CommandCallingProvider("printf 'command diagnostic 中文\\n'");
        var result = worker(f, launcher, provider).execute(request(f, BenchmarkToolProfile.LOCAL_COMMAND),
                f.workspace(), f.home(), Duration.ofSeconds(10));
        assertEquals(BenchmarkCoordinatorMain.WorkerStatus.COMPLETED, result.status());
        assertTrue(result.response().success(), result.response().errorMessage());
        assertEquals(1, result.toolExecutions().size());
        Path audit = f.episode().resolve("command-audit.json");
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(audit));
        assertEquals(4, json.size());
        assertEquals(1, json.path("schemaVersion").intValue());
        assertEquals("CANDIDATE_COMMAND_OBSERVATIONS_NOT_OS_AUDIT", json.path("kind").textValue());
        assertEquals(0, json.path("commandObservationFailures").longValue());
        var events = json.path("commandObservations");
        assertEquals(2, events.size());
        assertEquals("STARTED", events.get(0).path("phase").textValue());
        assertEquals("FINISHED", events.get(1).path("phase").textValue());
        assertTrue(events.get(0).path("processId").longValue() > 0);
        assertEquals(0, events.get(1).path("exitCode").intValue());
        assertEquals("EXITED", events.get(1).path("outcome").textValue());
        assertEquals(result.toolExecutions().get(0).resultSha256(), events.get(1).path("resultSha256").textValue());
        assertEquals(result.toolExecutions().get(0).resultChars(), events.get(1).path("resultChars").intValue());
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(audit));
        assertFalse(Files.exists(f.workspace().resolve("command-audit.json")));
    }

    @Test
    void commandAuditCanaryIsRejectedBeforeWritingAndNoCandidateFileIsRead(@TempDir Path tempDir) throws Exception {
        Fixture f = fixture(tempDir);
        Files.writeString(f.workspace().resolve("command-audit.json"), "fake candidate audit");
        var launcher = new FakeDockerLauncher(f.workspace(), false, false, new ImmediateProcess(), true);
        launcher.terminalCommandObservations = List.of(new com.paicli.tool.CommandExecutionObserver.Event(1,
                com.paicli.tool.CommandExecutionObserver.Phase.REJECTED, KEY, "/workspace", List.of(),
                0, 1_800_000_000_000L, null, com.paicli.tool.CommandExecutionObserver.Outcome.POLICY_DENIED, "", 0));
        var result = worker(f, launcher, new FakeProvider("unused")).execute(request(f, BenchmarkToolProfile.LOCAL_COMMAND),
                f.workspace(), f.home(), Duration.ofSeconds(10));
        assertEquals(BenchmarkCoordinatorMain.WorkerStatus.SECURITY_ERROR, result.status());
        assertFalse(Files.exists(f.episode().resolve("command-audit.json")));
        assertEquals("fake candidate audit", Files.readString(f.workspace().resolve("command-audit.json")));
    }

    @Test
    void commandAuditNeverOverwritesAnExistingHostRecord(@TempDir Path tempDir) throws Exception {
        Fixture f = fixture(tempDir);
        Path audit = f.episode().resolve("command-audit.json");
        Files.writeString(audit, "existing record");
        var result = worker(f, new FakeDockerLauncher(f.workspace(), false), new FakeProvider("done"))
                .execute(request(f, BenchmarkToolProfile.LOCAL_COMMAND), f.workspace(), f.home(), Duration.ofSeconds(10));
        assertEquals(BenchmarkCoordinatorMain.WorkerStatus.PROCESS_ERROR, result.status());
        assertEquals("existing record", Files.readString(audit));
    }

    @Test void incompleteWebRelayRejectsHostAndDockerBeforeExecution(@TempDir Path tempDir) throws Exception {
        Fixture fixture = fixture(tempDir);
        var request = request(fixture, BenchmarkToolProfile.MOCK_WEB);
        assertEquals("UNSUPPORTED_TOOL_PROFILE", BenchmarkWorkerMain.execute(request).errorType());
        var launcher = new FakeDockerLauncher(fixture.workspace(), false);
        var worker = worker(fixture, launcher, new FakeProvider("must not be called"));
        assertEquals("Web profile requires exactly one host-owned mock", assertThrows(IOException.class,
                () -> worker.execute(request, fixture.workspace(), fixture.home(), Duration.ofSeconds(10))).getMessage());
        assertTrue(launcher.commands.isEmpty(), "no Docker command or relay is started for an unintegrated profile");
    }

    @Test
    void forwardsFrozenPerCaseLimitsIntoRelaySession(@TempDir Path tempDir) throws Exception {
        Fixture fixture = fixture(tempDir);
        BenchmarkRelayProtocol.SessionStart session = DockerBenchmarkWorkerProcess.session(
                request(fixture, BenchmarkToolProfile.FILE_ONLY),
                ContextWindowCappedLlmClient.cap(
                        new FakeProvider("safe answer"), 321_000, 16_384),
                Duration.ofSeconds(10));

        assertEquals(91_337, session.agentLimits().tokenBudget());
        assertEquals(29, session.agentLimits().hardMaxIterations());
        assertEquals(7, session.agentLimits().stagnationWindow());
        assertEquals(321_000, session.agentLimits().contextWindowCapTokens());
        assertEquals(16_384, session.agentLimits().maxOutputTokensPerCall());
        assertEquals(321_000, session.capabilities().maxContextWindow());
    }

    @Test
    void runsRelayEndToEndWithHardenedArgvNoCredentialAndHostMetrics(@TempDir Path tempDir)
            throws Exception {
        Fixture fixture = fixture(tempDir);
        FakeDockerLauncher launcher = new FakeDockerLauncher(fixture.workspace(), false);
        DockerBenchmarkWorkerProcess worker = worker(fixture, launcher, new FakeProvider("safe answer"));

        BenchmarkCoordinatorMain.WorkerExecution execution = worker.execute(
                request(fixture, BenchmarkToolProfile.FILE_ONLY),
                fixture.workspace(), fixture.home(), Duration.ofSeconds(10));

        assertEquals(BenchmarkCoordinatorMain.WorkerStatus.COMPLETED, execution.status());
        assertTrue(execution.response().success(), execution.response().errorMessage());
        assertEquals("safe answer", execution.response().answer());
        assertNotNull(execution.response().metrics());
        assertEquals(1, execution.response().metrics().calls());
        assertEquals("hy4-preview", execution.response().metrics().resolvedModel());
        assertTrue(execution.response().metrics().resolvedModelConsistent());
        assertTrue(execution.response().metrics().usageComplete());
        assertTrue(execution.response().metrics().requestFingerprintComplete());
        assertEquals(16_384, execution.response().metrics().maxOutputTokensPerCall());
        assertEquals(30, execution.response().metrics().maxObservedTotalTokensPerCall());
        assertEquals(64, execution.response().metrics().systemPromptSha256().length());
        assertEquals(64, execution.response().metrics().initialToolSchemaSha256().length());
        assertEquals(sha256(fixture.jar()), worker.candidateJarSha256());
        assertEquals(sha256(fixture.runnerJar()), worker.runnerJarSha256());
        assertEquals(64, worker.runnerContentManifestSha256().length());

        assertEquals(2, launcher.commands.size());
        List<String> run = launcher.commands.get(0);
        assertEquals(List.of(DOCKER.toString(), "rm", "-f", "a".repeat(64)), launcher.commands.get(1));
        assertOption(run, "-i");
        assertFalse(run.contains("--rm"));
        assertOption(run, "--pull=never");
        assertOptionValue(run, "--network", "none");
        assertOption(run, "--read-only");
        assertOption(run, "--init");
        assertOptionValue(run, "--cap-drop", "ALL");
        assertOptionValue(run, "--security-opt", "no-new-privileges:true");
        assertOptionValue(run, "--pids-limit", "128");
        assertOptionValue(run, "--memory", "1g");
        assertOptionValue(run, "--memory-swap", "1g");
        assertOptionValue(run, "--cpus", "2");
        assertTrue(run.contains("nofile=512:512"));
        assertTrue(run.contains("fsize=67108864:67108864"));
        assertTrue(run.contains("type=bind,source=" + fixture.workspace().toRealPath()
                + ",target=/workspace"));
        String stagedJarMount = run.stream()
                .filter(value -> value.contains("target=/opt/paicli/candidate.jar,readonly"))
                .findFirst()
                .orElseThrow();
        Path stagedJar = Path.of(stagedJarMount.substring(
                "type=bind,source=".length(), stagedJarMount.indexOf(",target=")));
        assertTrue(stagedJar.startsWith(fixture.episode().toRealPath().resolve("worker-docker-tmp")));
        assertFalse(stagedJar.equals(fixture.jar().toRealPath()));
        assertEquals(sha256(fixture.jar()), sha256(stagedJar));
        String stagedRunnerMount = run.stream()
                .filter(value -> value.contains("target=/opt/paicli/runner.jar,readonly"))
                .findFirst()
                .orElseThrow();
        Path stagedRunner = Path.of(stagedRunnerMount.substring(
                "type=bind,source=".length(), stagedRunnerMount.indexOf(",target=")));
        assertFalse(stagedRunner.equals(fixture.runnerJar().toRealPath()));
        assertEquals(sha256(fixture.runnerJar()), sha256(stagedRunner));
        assertEquals(3, run.stream().filter("--mount"::equals).count());
        assertOptionValue(run, "-cp",
                "/opt/paicli/runner.jar:/opt/paicli/candidate.jar");
        assertFalse(join(run).contains(fixture.home().toString()));
        assertFalse(join(run).contains(KEY));
        assertFalse(join(run).contains(BASE_URL));

        Map<String, String> environment = launcher.environments.get(0);
        assertFalse(join(environment.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue()).toList()).contains(KEY));
        assertFalse(join(environment.values().stream().toList()).contains(BASE_URL));
        assertFalse(environment.keySet().stream().anyMatch(BenchmarkProcessEnvironment::isCredentialLike));

        String coordinatorToWorker = launcher.relayInput.toString(StandardCharsets.UTF_8);
        assertFalse(coordinatorToWorker.contains(KEY));
        assertFalse(coordinatorToWorker.contains(BASE_URL));
        assertFalse(coordinatorToWorker.contains(fixture.episode().toString()));
        assertFalse(coordinatorToWorker.contains(fixture.home().toString()));
        assertFalse(coordinatorToWorker.contains(fixture.jar().toString()));
        assertFalse(coordinatorToWorker.contains(fixture.runnerJar().toString()));
        assertFalse(Files.exists(fixture.episode().resolve("worker-docker-tmp/container.cid")));
        assertFalse(Files.exists(fixture.episode().resolve("command-audit.json")), "non-command profiles have no command audit");
    }

    @Test
    void carriesTrustedToolExecutionEvidenceAcrossTheDockerRelay(@TempDir Path tempDir)
            throws Exception {
        Fixture fixture = fixture(tempDir);
        FakeDockerLauncher launcher = new FakeDockerLauncher(fixture.workspace(), false);
        ToolCallingProvider provider = new ToolCallingProvider();
        DockerBenchmarkWorkerProcess worker = worker(fixture, launcher, provider);

        BenchmarkCoordinatorMain.WorkerExecution execution = worker.execute(
                request(fixture, BenchmarkToolProfile.FILE_ONLY),
                fixture.workspace(), fixture.home(), Duration.ofSeconds(10));

        assertEquals(BenchmarkCoordinatorMain.WorkerStatus.COMPLETED, execution.status());
        assertTrue(execution.response().success(), execution.response().errorMessage());
        assertEquals("tool evidence captured", execution.response().answer());
        assertEquals(2, provider.calls.get());
        assertEquals(1, execution.toolExecutions().size());
        BenchmarkToolExecutionEvidence evidence = execution.toolExecutions().get(0);
        assertEquals(1, evidence.ordinal());
        assertEquals("call-list-1", evidence.callId());
        assertEquals("list_dir", evidence.toolName());
        assertEquals("{\"path\":\".\"}", evidence.argumentsJson());
        assertEquals(64, evidence.resultSha256().length());
        assertEquals(evidence.resultChars(), evidence.resultPreview().length());
        assertTrue(evidence.successful());
        assertFalse(evidence.timedOut());
    }

    @Test
    void blocksProviderCredentialBeforeItCanEnterContainerStdin(@TempDir Path tempDir)
            throws Exception {
        Fixture fixture = fixture(tempDir);
        FakeDockerLauncher launcher = new FakeDockerLauncher(fixture.workspace(), false);
        DockerBenchmarkWorkerProcess worker = worker(fixture, launcher, new FakeProvider(KEY));

        BenchmarkCoordinatorMain.WorkerExecution execution = worker.execute(
                request(fixture, BenchmarkToolProfile.REASONING_ONLY),
                fixture.workspace(), fixture.home(), Duration.ofSeconds(10));

        assertEquals(BenchmarkCoordinatorMain.WorkerStatus.SECURITY_ERROR, execution.status());
        assertFalse(launcher.relayInput.toString(StandardCharsets.UTF_8).contains(KEY));
    }

    @Test
    void localCommandRunsInsideTheHardenedContainer(@TempDir Path tempDir) throws Exception {
        Fixture fixture = fixture(tempDir);
        FakeDockerLauncher launcher = new FakeDockerLauncher(fixture.workspace(), false);
        DockerBenchmarkWorkerProcess worker = worker(fixture, launcher, new FakeProvider("safe answer"));

        BenchmarkCoordinatorMain.WorkerExecution execution = worker.execute(
                request(fixture, BenchmarkToolProfile.LOCAL_COMMAND),
                fixture.workspace(), fixture.home(), Duration.ofSeconds(5));

        assertEquals(BenchmarkCoordinatorMain.WorkerStatus.COMPLETED, execution.status());
        assertTrue(execution.response().success());
        assertEquals("safe answer", execution.response().answer());
        assertEquals(2, launcher.commands.size());
        assertTrue(launcher.commands.get(0).contains("none"));
        assertTrue(launcher.commands.get(0).contains("--read-only"));
    }

    @Test
    void symlinkCreatedByCandidateIsTypedAsSecurityFailure(@TempDir Path tempDir) throws Exception {
        Fixture fixture = fixture(tempDir);
        FakeDockerLauncher launcher = new FakeDockerLauncher(fixture.workspace(), false, true);
        DockerBenchmarkWorkerProcess worker = worker(fixture, launcher, new FakeProvider("safe answer"));

        BenchmarkCoordinatorMain.WorkerExecution execution = worker.execute(
                request(fixture, BenchmarkToolProfile.FILE_ONLY),
                fixture.workspace(), fixture.home(), Duration.ofSeconds(10));

        assertEquals(BenchmarkCoordinatorMain.WorkerStatus.SECURITY_ERROR, execution.status());
        assertTrue(execution.diagnostic().contains("unsafe candidate workspace"));
        assertFalse(execution.diagnostic().contains("/suite"));
    }

    @Test
    void unsafeWorkspaceRemainsHardGateWhenCandidateRelayAlsoFails(@TempDir Path tempDir)
            throws Exception {
        Fixture fixture = fixture(tempDir);
        FakeDockerLauncher launcher = new FakeDockerLauncher(fixture.workspace(), true, true);
        DockerBenchmarkWorkerProcess worker = worker(fixture, launcher, new FakeProvider("unused"));

        BenchmarkCoordinatorMain.WorkerExecution execution = worker.execute(
                request(fixture, BenchmarkToolProfile.FILE_ONLY),
                fixture.workspace(), fixture.home(), Duration.ofSeconds(10));

        assertEquals(BenchmarkCoordinatorMain.WorkerStatus.SECURITY_ERROR, execution.status());
        assertTrue(execution.diagnostic().contains("unsafe candidate workspace"));
    }

    @Test
    void deadlineDestroysHungContainerAndScoresTimeout(@TempDir Path tempDir) throws Exception {
        Fixture fixture = fixture(tempDir);
        HangingLauncher launcher = new HangingLauncher();
        DockerBenchmarkWorkerProcess worker = worker(fixture, launcher, new FakeProvider("unused"));

        BenchmarkCoordinatorMain.WorkerExecution execution = worker.execute(
                request(fixture, BenchmarkToolProfile.READ_ONLY),
                fixture.workspace(), fixture.home(), Duration.ofMillis(50));

        assertEquals(BenchmarkCoordinatorMain.WorkerStatus.TIMEOUT, execution.status());
        assertTrue(launcher.process.destroyed.get());
        assertNotNull(execution.response().metrics());
        assertNull(BenchmarkProviderEvidenceGate.invalidEvaluationFailureType(request(fixture, BenchmarkToolProfile.READ_ONLY), execution.response().metrics()));
        assertEquals(BenchmarkFailureClassifier.Disposition.SCORED_FAILURE, BenchmarkFailureClassifier.classifyWorker(execution));
    }

    @Test
    void deadlineCancelsBlockedProviderAndReturnsPromptly(@TempDir Path tempDir) throws Exception {
        Fixture fixture = fixture(tempDir);
        FakeDockerLauncher launcher = new FakeDockerLauncher(fixture.workspace(), false);
        CancellableBlockingProvider provider = new CancellableBlockingProvider();
        DockerBenchmarkWorkerProcess worker = worker(fixture, launcher, provider);

        long started = System.nanoTime();
        BenchmarkCoordinatorMain.WorkerExecution execution = worker.execute(
                request(fixture, BenchmarkToolProfile.REASONING_ONLY),
                fixture.workspace(), fixture.home(), Duration.ofMillis(500));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(BenchmarkCoordinatorMain.WorkerStatus.TIMEOUT, execution.status());
        assertNotNull(execution.response().metrics());
        assertNull(BenchmarkProviderEvidenceGate.invalidEvaluationFailureType(request(fixture, BenchmarkToolProfile.REASONING_ONLY), execution.response().metrics()));
        assertTrue(provider.chatStarted.get());
        assertTrue(provider.cancelled.get());
        assertTrue(elapsedMillis < 3_000, "provider cancellation must bound episode latency");
    }

    @Test
    void cleanupTimeoutRetainsCidfileAndFailsEpisodeAsInfra(@TempDir Path tempDir) throws Exception {
        Fixture fixture = fixture(tempDir);
        FakeDockerLauncher launcher = new FakeDockerLauncher(
                fixture.workspace(), false, false, new CleanupTimeoutProcess(), false);
        DockerBenchmarkWorkerProcess worker = worker(fixture, launcher, new FakeProvider("safe answer"));

        IOException error = assertThrows(IOException.class, () -> worker.execute(
                request(fixture, BenchmarkToolProfile.FILE_ONLY),
                fixture.workspace(), fixture.home(), Duration.ofSeconds(10)));

        assertTrue(error.getMessage().contains("cleanup timed out"));
        assertTrue(Files.isRegularFile(
                fixture.episode().resolve("worker-docker-tmp/container.cid")));
    }

    @Test
    void cleanupFailureRetainsCidfileAndFailsEpisodeAsInfra(@TempDir Path tempDir) throws Exception {
        Fixture fixture = fixture(tempDir);
        FakeDockerLauncher launcher = new FakeDockerLauncher(
                fixture.workspace(), false, false, new ExitProcess(1), false);
        DockerBenchmarkWorkerProcess worker = worker(fixture, launcher, new FakeProvider("safe answer"));

        IOException error = assertThrows(IOException.class, () -> worker.execute(
                request(fixture, BenchmarkToolProfile.FILE_ONLY),
                fixture.workspace(), fixture.home(), Duration.ofSeconds(10)));

        assertTrue(error.getMessage().contains("cleanup failed"));
        assertTrue(Files.isRegularFile(
                fixture.episode().resolve("worker-docker-tmp/container.cid")));
    }

    @Test
    void rejectsCandidateJarReplacementBeforeDockerStarts(@TempDir Path tempDir) throws Exception {
        Fixture fixture = fixture(tempDir);
        FakeDockerLauncher launcher = new FakeDockerLauncher(fixture.workspace(), false);
        DockerBenchmarkWorkerProcess worker = worker(fixture, launcher, new FakeProvider("unused"));
        Files.writeString(fixture.jar(), "replacement-fat-jar", StandardCharsets.UTF_8);

        IOException error = assertThrows(IOException.class, () -> worker.execute(
                request(fixture, BenchmarkToolProfile.FILE_ONLY),
                fixture.workspace(), fixture.home(), Duration.ofSeconds(10)));

        assertTrue(error.getMessage().contains("candidate jar changed"));
        assertTrue(launcher.commands.isEmpty());
    }

    @Test
    void rejectsRunnerJarReplacementBeforeDockerStarts(@TempDir Path tempDir) throws Exception {
        Fixture fixture = fixture(tempDir);
        FakeDockerLauncher launcher = new FakeDockerLauncher(fixture.workspace(), false);
        DockerBenchmarkWorkerProcess worker = worker(fixture, launcher, new FakeProvider("unused"));
        BenchmarkRunnerTestArtifact.create(fixture.runnerJar());
        Files.writeString(fixture.runnerJar(), "replacement-runner", StandardCharsets.UTF_8);

        IOException error = assertThrows(IOException.class, () -> worker.execute(
                request(fixture, BenchmarkToolProfile.FILE_ONLY),
                fixture.workspace(), fixture.home(), Duration.ofSeconds(10)));

        assertTrue(error.getMessage().contains("trusted runner jar changed"));
        assertTrue(launcher.commands.isEmpty());
    }

    @Test
    void zeroProviderCallTerminalFrameFailsEvidenceGate(@TempDir Path tempDir) throws Exception {
        Fixture fixture = fixture(tempDir);
        FakeDockerLauncher launcher = new FakeDockerLauncher(
                fixture.workspace(), false, false, new ImmediateProcess(), true);
        DockerBenchmarkWorkerProcess worker = worker(fixture, launcher, new FakeProvider("unused"));

        BenchmarkCoordinatorMain.WorkerExecution execution = worker.execute(
                request(fixture, BenchmarkToolProfile.REASONING_ONLY),
                fixture.workspace(), fixture.home(), Duration.ofSeconds(10));

        assertEquals(BenchmarkCoordinatorMain.WorkerStatus.COMPLETED, execution.status());
        assertFalse(execution.response().success());
        assertEquals(BenchmarkProviderEvidenceGate.NO_PROVIDER_CALL,
                execution.response().errorType());
        assertEquals(0, execution.response().metrics().successfulCalls());
    }

    @Test
    void wrongOrMissingResolvedModelInvalidatesDockerEpisode(@TempDir Path tempDir)
            throws Exception {
        for (String resolvedModel : java.util.Arrays.asList("glm-5.3-flash", null)) {
            Path root = Files.createDirectory(tempDir.resolve(
                    resolvedModel == null ? "missing-model" : "wrong-model"));
            Fixture fixture = fixture(root);
            FakeDockerLauncher launcher = new FakeDockerLauncher(fixture.workspace(), false);
            DockerBenchmarkWorkerProcess worker = worker(
                    fixture, launcher,
                    new EvidenceProvider(resolvedModel, true, 25, 5, 1_000_000));

            BenchmarkCoordinatorMain.WorkerExecution execution = worker.execute(
                    request(fixture, BenchmarkToolProfile.REASONING_ONLY),
                    fixture.workspace(), fixture.home(), Duration.ofSeconds(10));

            assertEquals(BenchmarkCoordinatorMain.WorkerStatus.COMPLETED, execution.status());
            assertFalse(execution.response().success());
            assertEquals(BenchmarkProviderEvidenceGate.MODEL_IDENTITY_UNPROVEN,
                    execution.response().errorType());
            assertEquals(BenchmarkFailureClassifier.Disposition.INFRA_ERROR,
                    BenchmarkFailureClassifier.classifyWorker(execution));
        }
    }

    @Test
    void missingUsageAndProviderOutputOverflowInvalidateDockerEpisode(@TempDir Path tempDir)
            throws Exception {
        record Scenario(String name, EvidenceProvider provider, String expectedType) { }
        List<Scenario> scenarios = List.of(
                new Scenario(
                        "missing-usage",
                        new EvidenceProvider("hy4-preview", false, 0, 0, 1_000_000),
                        BenchmarkProviderEvidenceGate.USAGE_UNPROVEN),
                new Scenario(
                        "output-overflow",
                        new EvidenceProvider("hy4-preview", true, 25, 16_385, 1_000_000),
                        BenchmarkProviderEvidenceGate.OUTPUT_POLICY_UNPROVEN));
        for (Scenario scenario : scenarios) {
            Fixture fixture = fixture(Files.createDirectory(tempDir.resolve(scenario.name())));
            FakeDockerLauncher launcher = new FakeDockerLauncher(fixture.workspace(), false);
            DockerBenchmarkWorkerProcess worker = worker(fixture, launcher, scenario.provider());

            BenchmarkCoordinatorMain.WorkerExecution execution = worker.execute(
                    request(fixture, BenchmarkToolProfile.REASONING_ONLY),
                    fixture.workspace(), fixture.home(), Duration.ofSeconds(10));

            assertEquals(BenchmarkCoordinatorMain.WorkerStatus.COMPLETED, execution.status());
            assertFalse(execution.response().success());
            assertEquals(scenario.expectedType(), execution.response().errorType());
            assertEquals(BenchmarkFailureClassifier.Disposition.INFRA_ERROR,
                    BenchmarkFailureClassifier.classifyWorker(execution));
        }
    }

    @Test
    void smallerProviderWindowIsTypedAsUnavailableBeforeDockerLaunch(@TempDir Path tempDir)
            throws Exception {
        Fixture fixture = fixture(tempDir);
        FakeDockerLauncher launcher = new FakeDockerLauncher(fixture.workspace(), false);
        DockerBenchmarkWorkerProcess worker = worker(
                fixture, launcher,
                new EvidenceProvider("hy4-preview", true, 25, 5, 128_000));

        BenchmarkCoordinatorMain.WorkerExecution execution = worker.execute(
                request(fixture, BenchmarkToolProfile.REASONING_ONLY),
                fixture.workspace(), fixture.home(), Duration.ofSeconds(10));

        assertFalse(execution.response().success());
        assertEquals("CONTEXT_CAP_UNAVAILABLE", execution.response().errorType());
        assertEquals(BenchmarkFailureClassifier.Disposition.INFRA_ERROR,
                BenchmarkFailureClassifier.classifyWorker(execution));
        assertTrue(launcher.commands.isEmpty());
    }

    @Test
    void launcherFailurePropagatesAsRunnerUnavailable(@TempDir Path tempDir) throws Exception {
        Fixture fixture = fixture(tempDir);
        DockerBenchmarkWorkerProcess worker = worker(
                fixture,
                builder -> { throw new IOException("docker daemon unavailable"); },
                new FakeProvider("unused"));

        IOException error = assertThrows(IOException.class, () -> worker.execute(
                request(fixture, BenchmarkToolProfile.FILE_ONLY),
                fixture.workspace(), fixture.home(), Duration.ofSeconds(5)));
        assertTrue(error.getMessage().contains("docker daemon unavailable"));
    }

    @Test
    void webBudgetFinalizationRetainsProviderEvidenceAndIsNotSentToCompletedTranscriptGrading(@TempDir Path tempDir) throws Exception {
        Fixture f = fixture(tempDir);
        var mock = new com.paicli.eval.benchmark.mock.D4WebMock(D4NativeWebTest.entropy(47));
        var delegate = new D4NativeWebTest.ScriptedClient(mock.definition(), D4NativeWebTest.Control.CORRECT, "hunyuan", "hy4-preview");
        LlmClient provider = new LlmClient() {
            public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) { return chat(messages, tools); }
            public ChatResponse chat(List<Message> messages, List<Tool> tools) {
                var r = delegate.chat(messages, tools);
                return new ChatResponse(r.role(), r.content(), r.reasoningContent(), r.toolCalls(), 60_000, 30, 0, getModelName(), true);
            }
            public String getModelName() { return "hy4-preview"; }
            public String getProviderName() { return "hunyuan"; }
            public int maxContextWindow() { return 1_000_000; }
        };
        var base = request(f, BenchmarkToolProfile.MOCK_WEB);
        var request = new BenchmarkProtocol.WorkerRequest(base.protocolVersion(), base.provider(), base.model(), base.baseUrl(), base.apiKey(),
                base.mode(), base.toolProfile(), base.agentLimits(), base.runtimeDate(), mock.prompt(), base.workspace(), base.home(), base.episodeDirectory());
        var result = worker(f, new FakeDockerLauncher(f.workspace(), false), provider)
                .executeWithWeb(request, f.workspace(), f.home(), Duration.ofSeconds(10), mock);
        assertEquals(BenchmarkCoordinatorMain.WorkerStatus.COMPLETED, result.status()); assertFalse(result.response().success());
        assertEquals("EPISODE_BUDGET_EXHAUSTED", result.response().errorType());
        assertEquals(3, result.response().metrics().calls()); assertEquals(3, mock.providerAudit().size());
        assertNull(BenchmarkProviderEvidenceGate.invalidEvaluationFailureType(request, result.response().metrics()));
        assertEquals(BenchmarkFailureClassifier.Disposition.SCORED_FAILURE, BenchmarkFailureClassifier.classifyWorker(result));
    }

    private static DockerBenchmarkWorkerProcess worker(
            Fixture fixture,
            DockerBenchmarkWorkerProcess.ProcessLauncher launcher,
            LlmClient provider) throws IOException {
        return new DockerBenchmarkWorkerProcess(
                DOCKER, IMAGE, fixture.jar(), fixture.runnerJar(), launcher,
                workspace -> new DockerBenchmarkWorkerProcess.HostIdentity(501, 20),
                request -> provider);
    }

    private static BenchmarkProtocol.WorkerRequest request(Fixture fixture, BenchmarkToolProfile profile) {
        return new BenchmarkProtocol.WorkerRequest(
                BenchmarkProtocol.VERSION,
                "hunyuan",
                "hy4-preview",
                BASE_URL,
                KEY,
                "react",
                profile,
                new BenchmarkProtocol.AgentLimits(91_337, 29, 7, 321_000, 16_384),
                "2026-08-31",
                "Return a short safe answer.",
                fixture.workspace().toString(),
                fixture.home().toString(),
                fixture.episode().toString());
    }

    private static Fixture fixture(Path root) throws IOException {
        Path episode = Files.createDirectory(root.resolve("episode"));
        Path workspace = Files.createDirectory(episode.resolve("workspace"));
        Path home = Files.createDirectory(episode.resolve("home"));
        Path jar = root.resolve("candidate.jar").toAbsolutePath();
        Files.writeString(jar, "candidate-fat-jar", StandardCharsets.UTF_8);
        Path runnerJar = BenchmarkRunnerTestArtifact.create(
                root.resolve("runner.jar").toAbsolutePath());
        return new Fixture(episode, workspace, home, jar, runnerJar);
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static void assertOption(List<String> command, String option) {
        assertTrue(command.contains(option), "missing option " + option);
    }

    private static void assertOptionValue(List<String> command, String option, String value) {
        int index = command.indexOf(option);
        assertTrue(index >= 0 && index + 1 < command.size(), "missing option " + option);
        assertEquals(value, command.get(index + 1));
    }

    private static String join(List<String> values) {
        return String.join("\n", values);
    }

    private record Fixture(Path episode, Path workspace, Path home, Path jar, Path runnerJar) { }

    private static final class FakeProvider implements LlmClient {
        private final String answer;

        private FakeProvider(String answer) {
            this.answer = answer;
        }

        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                           StreamListener listener) {
            listener.onReasoningDelta("safe reasoning");
            listener.onContentDelta(answer);
            return new ChatResponse(
                    "assistant", answer, "safe reasoning", List.of(),
                    25, 5, 10, "hy4-preview", true);
        }

        @Override public String getModelName() { return "hy4-preview"; }
        @Override public String getProviderName() { return "hunyuan"; }
        @Override public int maxContextWindow() { return 1_000_000; }
        @Override public boolean supportsPromptCaching() { return true; }
        @Override public String promptCacheMode() { return "automatic-prefix-cache"; }
    }

    private static final class EvidenceProvider implements LlmClient {
        private final String resolvedModel;
        private final boolean usagePresent;
        private final int inputTokens;
        private final int outputTokens;
        private final int maxContextWindow;

        private EvidenceProvider(String resolvedModel,
                                 boolean usagePresent,
                                 int inputTokens,
                                 int outputTokens,
                                 int maxContextWindow) {
            this.resolvedModel = resolvedModel;
            this.usagePresent = usagePresent;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.maxContextWindow = maxContextWindow;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                 StreamListener listener) {
            listener.onReasoningDelta("reasoning");
            listener.onContentDelta("answer");
            return new ChatResponse(
                    "assistant", "answer", "reasoning", List.of(),
                    inputTokens, outputTokens, 0, resolvedModel, usagePresent);
        }

        @Override public String getModelName() { return "hy4-preview"; }
        @Override public String getProviderName() { return "hunyuan"; }
        @Override public int maxContextWindow() { return maxContextWindow; }
    }

    private static final class CommandCallingProvider implements LlmClient {
        private final AtomicInteger calls = new AtomicInteger();
        private final String command;
        private CommandCallingProvider(String command) { this.command = command; }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            return chat(messages, tools);
        }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            if (calls.incrementAndGet() == 1) {
                String args = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("command", command).toString();
                return new ChatResponse("assistant", "", "diagnose", List.of(new ToolCall("command-1",
                        new ToolCall.Function("execute_command", args))), 25, 5, 0, "hy4-preview", true);
            }
            return new ChatResponse("assistant", "command observed", "", List.of(), 20, 4, 0, "hy4-preview", true);
        }
        @Override public String getModelName() { return "hy4-preview"; }
        @Override public String getProviderName() { return "hunyuan"; }
        @Override public int maxContextWindow() { return 1_000_000; }
    }

    private static final class ToolCallingProvider implements LlmClient {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                 StreamListener listener) {
            int call = calls.incrementAndGet();
            if (call == 1) {
                return new ChatResponse(
                        "assistant", "", "inspect the workspace",
                        List.of(new ToolCall(
                                "call-list-1",
                                new ToolCall.Function("list_dir", "{\"path\":\".\"}"))),
                        25, 5, 10, "hy4-preview", true);
            }
            String answer = "tool evidence captured";
            listener.onContentDelta(answer);
            return new ChatResponse(
                    "assistant", answer, "done", List.of(),
                    20, 4, 8, "hy4-preview", true);
        }

        @Override public String getModelName() { return "hy4-preview"; }
        @Override public String getProviderName() { return "hunyuan"; }
        @Override public int maxContextWindow() { return 1_000_000; }
        @Override public boolean supportsPromptCaching() { return true; }
        @Override public String promptCacheMode() { return "automatic-prefix-cache"; }
    }

    private static final class CancellableBlockingProvider implements LlmClient {
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicBoolean chatStarted = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                 StreamListener listener) throws IOException {
            chatStarted.set(true);
            try {
                if (!released.await(30, TimeUnit.SECONDS)) {
                    throw new IOException("test provider was not cancelled");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("test provider interrupted", error);
            }
            throw new IOException(cancelled.get() ? "test provider cancelled" : "test provider released");
        }

        @Override
        public void cancelInFlightCalls() {
            cancelled.set(true);
            released.countDown();
        }

        @Override public String getModelName() { return "hy4-preview"; }
        @Override public String getProviderName() { return "hunyuan"; }
        @Override public int maxContextWindow() { return 1_000_000; }
    }

    private static final class FakeDockerLauncher implements DockerBenchmarkWorkerProcess.ProcessLauncher {
        private final Path workspace;
        private final boolean failWorker;
        private final boolean createUnsafeSymlink;
        private final DockerBenchmarkWorkerProcess.ManagedProcess cleanupProcess;
        private final boolean completeWithoutProviderCall;
        private final List<List<String>> commands = new ArrayList<>();
        private final List<Map<String, String>> environments = new ArrayList<>();
        private final ByteArrayOutputStream relayInput = new ByteArrayOutputStream();
        private List<com.paicli.tool.CommandExecutionObserver.Event> terminalCommandObservations = List.of();

        private FakeDockerLauncher(Path workspace, boolean failWorker) {
            this(workspace, failWorker, false, new ImmediateProcess(), false);
        }

        private FakeDockerLauncher(Path workspace, boolean failWorker, boolean createUnsafeSymlink) {
            this(workspace, failWorker, createUnsafeSymlink, new ImmediateProcess(), false);
        }

        private FakeDockerLauncher(Path workspace,
                                   boolean failWorker,
                                   boolean createUnsafeSymlink,
                                   DockerBenchmarkWorkerProcess.ManagedProcess cleanupProcess,
                                   boolean completeWithoutProviderCall) {
            this.workspace = workspace;
            this.failWorker = failWorker;
            this.createUnsafeSymlink = createUnsafeSymlink;
            this.cleanupProcess = cleanupProcess;
            this.completeWithoutProviderCall = completeWithoutProviderCall;
        }

        @Override
        public DockerBenchmarkWorkerProcess.ManagedProcess start(ProcessBuilder builder) throws IOException {
            commands.add(List.copyOf(builder.command()));
            environments.add(Map.copyOf(builder.environment()));
            if ("rm".equals(builder.command().get(1))) {
                return cleanupProcess;
            }
            Path cidFile = Path.of(valueAfter(builder.command(), "--cidfile"));
            Files.writeString(cidFile, "a".repeat(64), StandardCharsets.UTF_8);
            return new RelayProcess(
                    workspace, relayInput, failWorker, createUnsafeSymlink,
                    completeWithoutProviderCall, terminalCommandObservations);
        }
    }

    private static final class RelayProcess implements DockerBenchmarkWorkerProcess.ManagedProcess {
        private final PipedInputStream hostStdout = new PipedInputStream(1 << 20);
        private final PipedInputStream workerStdin = new PipedInputStream(1 << 20);
        private final PipedOutputStream workerStdout;
        private final PipedOutputStream rawHostStdin;
        private final OutputStream hostStdin;
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private volatile int exitCode;

        private RelayProcess(Path workspace,
                             ByteArrayOutputStream capture,
                             boolean failWorker,
                             boolean createUnsafeSymlink,
                             boolean completeWithoutProviderCall,
                             List<com.paicli.tool.CommandExecutionObserver.Event> terminalCommandObservations)
                throws IOException {
            workerStdout = new PipedOutputStream(hostStdout);
            rawHostStdin = new PipedOutputStream(workerStdin);
            hostStdin = new TeeOutputStream(rawHostStdin, capture);
            Thread worker = new Thread(() -> {
                try {
                    if (failWorker) {
                        throw new IOException("injected candidate failure");
                    }
                    if (completeWithoutProviderCall) {
                        completeWithoutProviderCall(workerStdin, workerStdout, terminalCommandObservations);
                    } else {
                        BenchmarkRelayWorkerMain.run(workerStdin, workerStdout, workspace);
                    }
                    exitCode = 0;
                } catch (Exception error) {
                    exitCode = 1;
                } finally {
                    if (createUnsafeSymlink) {
                        try {
                            Files.createSymbolicLink(
                                    workspace.resolve("oracle-link"),
                                    Path.of("/suite/hidden-answer.txt"));
                        } catch (IOException | UnsupportedOperationException ignored) {
                            exitCode = 1;
                        }
                    }
                    alive.set(false);
                    close(workerStdout);
                    close(workerStdin);
                    finished.countDown();
                }
            }, "fake-docker-candidate-worker");
            worker.setDaemon(true);
            worker.start();
        }

        private static void completeWithoutProviderCall(InputStream input, OutputStream output,
                List<com.paicli.tool.CommandExecutionObserver.Event> terminalCommandObservations)
                throws IOException {
            BenchmarkFramedChannel channel = new BenchmarkFramedChannel(input, output);
            BenchmarkRelayProtocol.Frame frame = channel.read();
            if (!(frame instanceof BenchmarkRelayProtocol.SessionStart start)) {
                throw new IOException("missing session start");
            }
            channel.write(new BenchmarkRelayProtocol.WorkerReady(
                    new BenchmarkRelayProtocol.Header(
                            BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR,
                            BenchmarkRelayProtocol.FrameType.WORKER_READY, "", 0),
                    start.capabilities()));
            channel.write(new BenchmarkRelayProtocol.WorkerComplete(
                    new BenchmarkRelayProtocol.Header(
                            BenchmarkRelayProtocol.Direction.WORKER_TO_COORDINATOR,
                            BenchmarkRelayProtocol.FrameType.WORKER_COMPLETE, "", 0),
                    "zero-call answer", List.of(), terminalCommandObservations, 0));
        }

        @Override public InputStream stdout() { return hostStdout; }
        @Override public InputStream stderr() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream stdin() { return hostStdin; }
        @Override public boolean waitFor(Duration timeout) throws InterruptedException {
            return finished.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        @Override public Integer exitCode() { return alive.get() ? null : exitCode; }
        @Override public boolean isAlive() { return alive.get(); }
        @Override public void destroyForcibly() {
            alive.set(false);
            close(rawHostStdin);
            close(workerStdin);
            close(workerStdout);
            close(hostStdout);
            finished.countDown();
        }
    }

    private static final class HangingLauncher implements DockerBenchmarkWorkerProcess.ProcessLauncher {
        private final HangingProcess process = new HangingProcess();
        @Override public DockerBenchmarkWorkerProcess.ManagedProcess start(ProcessBuilder builder)
                throws IOException {
            if ("rm".equals(builder.command().get(1))) {
                return new ImmediateProcess();
            }
            Path cidFile = Path.of(valueAfter(builder.command(), "--cidfile"));
            Files.writeString(cidFile, "b".repeat(64), StandardCharsets.UTF_8);
            return process;
        }
    }

    private static final class HangingProcess implements DockerBenchmarkWorkerProcess.ManagedProcess {
        private final BlockingInput stdout = new BlockingInput();
        private final AtomicBoolean destroyed = new AtomicBoolean();
        @Override public InputStream stdout() { return stdout; }
        @Override public InputStream stderr() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream stdin() { return new ByteArrayOutputStream(); }
        @Override public boolean waitFor(Duration timeout) { return destroyed.get(); }
        @Override public Integer exitCode() { return destroyed.get() ? 137 : null; }
        @Override public boolean isAlive() { return !destroyed.get(); }
        @Override public void destroyForcibly() { destroyed.set(true); stdout.release(); }
    }

    private static final class BlockingInput extends InputStream {
        private boolean released;
        @Override public synchronized int read() throws IOException {
            while (!released) {
                try {
                    wait();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", error);
                }
            }
            return -1;
        }
        private synchronized void release() { released = true; notifyAll(); }
    }

    private static final class ImmediateProcess implements DockerBenchmarkWorkerProcess.ManagedProcess {
        @Override public InputStream stdout() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream stderr() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream stdin() { return OutputStream.nullOutputStream(); }
        @Override public boolean waitFor(Duration timeout) { return true; }
        @Override public Integer exitCode() { return 0; }
        @Override public boolean isAlive() { return false; }
        @Override public void destroyForcibly() { }
    }

    private static final class ExitProcess implements DockerBenchmarkWorkerProcess.ManagedProcess {
        private final int exitCode;

        private ExitProcess(int exitCode) {
            this.exitCode = exitCode;
        }

        @Override public InputStream stdout() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream stderr() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream stdin() { return OutputStream.nullOutputStream(); }
        @Override public boolean waitFor(Duration timeout) { return true; }
        @Override public Integer exitCode() { return exitCode; }
        @Override public boolean isAlive() { return false; }
        @Override public void destroyForcibly() { }
    }

    private static final class CleanupTimeoutProcess implements DockerBenchmarkWorkerProcess.ManagedProcess {
        private final AtomicBoolean destroyed = new AtomicBoolean();

        @Override public InputStream stdout() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream stderr() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream stdin() { return OutputStream.nullOutputStream(); }
        @Override public boolean waitFor(Duration timeout) { return destroyed.get(); }
        @Override public Integer exitCode() { return destroyed.get() ? 137 : null; }
        @Override public boolean isAlive() { return !destroyed.get(); }
        @Override public void destroyForcibly() { destroyed.set(true); }
    }

    private static final class TeeOutputStream extends OutputStream {
        private final OutputStream first;
        private final OutputStream second;
        private TeeOutputStream(OutputStream first, OutputStream second) {
            this.first = first;
            this.second = second;
        }
        @Override public synchronized void write(int value) throws IOException {
            first.write(value); second.write(value);
        }
        @Override public synchronized void write(byte[] value, int offset, int length) throws IOException {
            first.write(value, offset, length); second.write(value, offset, length);
        }
        @Override public synchronized void flush() throws IOException { first.flush(); second.flush(); }
        @Override public synchronized void close() throws IOException { first.close(); second.close(); }
    }

    private static String valueAfter(List<String> command, String option) {
        int index = command.indexOf(option);
        return command.get(index + 1);
    }

    private static void close(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
