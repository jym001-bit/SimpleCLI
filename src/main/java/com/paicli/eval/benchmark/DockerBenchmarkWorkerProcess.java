package com.paicli.eval.benchmark;

import com.paicli.eval.benchmark.relay.BenchmarkFramedChannel;
import com.paicli.eval.benchmark.relay.BenchmarkProviderRelay;
import com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol;
import com.paicli.llm.DeepSeekClient;
import com.paicli.llm.GLMClient;
import com.paicli.llm.HunyuanClient;
import com.paicli.llm.LlmClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Runs the Candidate Agent in a networkless Docker container while retaining the
 * real provider client, credential, endpoint and trace on the trusted host.
 * This is intentionally separate from the legacy host-side dev Worker.
 */
final class DockerBenchmarkWorkerProcess implements BenchmarkCoordinatorMain.WorkerExecutor {
    private static final Pattern FROZEN_IMAGE_ID = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern CONTAINER_ID = Pattern.compile("[0-9a-f]{12,64}");
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CLEANUP_KILL_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration PROCESS_STOP_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration PIPE_DRAIN_TIMEOUT = Duration.ofSeconds(2);
    private static final int STDERR_LIMIT = 128 * 1024;
    private static final int CLEANUP_OUTPUT_LIMIT = 16 * 1024;
    private static final String CONTAINER_WORKSPACE = "/workspace";
    private static final String CONTAINER_RUNNER_JAR = "/opt/paicli/runner.jar";
    private static final String CONTAINER_CANDIDATE_JAR = "/opt/paicli/candidate.jar";

    private final Path dockerExecutable;
    private final String imageId;
    private final Path candidateJar;
    private final String candidateJarSha256;
    private final Path runnerJar;
    private final String runnerJarSha256;
    private final String runnerContentManifestSha256;
    private final ProcessLauncher launcher;
    private final HostIdentityResolver identityResolver;
    private final ProviderFactory providerFactory;

    DockerBenchmarkWorkerProcess(
            Path dockerExecutable, String imageId, Path candidateJar, Path runnerJar)
            throws IOException {
        this(dockerExecutable, imageId, candidateJar, runnerJar, DockerBenchmarkWorkerProcess::createProvider);
    }

    /** Real container launcher with an explicitly injected host provider (also used by unscored controls). */
    DockerBenchmarkWorkerProcess(
            Path dockerExecutable, String imageId, Path candidateJar, Path runnerJar, ProviderFactory providerFactory)
            throws IOException {
        this(dockerExecutable, imageId, candidateJar, runnerJar,
                builder -> new JavaManagedProcess(builder.start()),
                DockerBenchmarkWorkerProcess::unixIdentity,
                providerFactory);
    }

    DockerBenchmarkWorkerProcess(Path dockerExecutable,
                                 String imageId,
                                 Path candidateJar,
                                 Path runnerJar,
                                 ProcessLauncher launcher,
                                 HostIdentityResolver identityResolver,
                                 ProviderFactory providerFactory) throws IOException {
        if (dockerExecutable == null || !dockerExecutable.isAbsolute()) {
            throw new IllegalArgumentException("docker executable must be absolute");
        }
        if (dockerExecutable.toString().indexOf('\n') >= 0
                || dockerExecutable.toString().indexOf('\r') >= 0) {
            throw new IllegalArgumentException("docker executable contains a control character");
        }
        if (imageId == null || !FROZEN_IMAGE_ID.matcher(imageId).matches()) {
            throw new IllegalArgumentException("worker image must be a frozen sha256 image ID");
        }
        if (launcher == null || identityResolver == null || providerFactory == null) {
            throw new IllegalArgumentException("worker process dependencies must not be null");
        }
        this.dockerExecutable = dockerExecutable.normalize();
        this.imageId = imageId;
        this.candidateJar = canonicalFile(candidateJar, "candidate jar");
        this.candidateJarSha256 = sha256(this.candidateJar);
        this.runnerJar = canonicalFile(runnerJar, "trusted runner jar");
        String runnerShaBeforeInspection = sha256(this.runnerJar);
        BenchmarkRunnerArtifactPolicy.Inspection runnerInspection =
                BenchmarkRunnerArtifactPolicy.inspect(this.runnerJar);
        String runnerShaAfterInspection = sha256(this.runnerJar);
        if (!runnerShaBeforeInspection.equals(runnerShaAfterInspection)) {
            throw new IOException("trusted runner jar changed during artifact inspection");
        }
        this.runnerJarSha256 = runnerShaAfterInspection;
        this.runnerContentManifestSha256 = runnerInspection.inventorySha256();
        this.launcher = launcher;
        this.identityResolver = identityResolver;
        this.providerFactory = providerFactory;
    }

    String candidateJarSha256() {
        return candidateJarSha256;
    }

    String runnerJarSha256() {
        return runnerJarSha256;
    }

    String runnerContentManifestSha256() {
        return runnerContentManifestSha256;
    }

    @Override
    public BenchmarkCoordinatorMain.WorkerExecution execute(
            BenchmarkProtocol.WorkerRequest request,
            Path workspace,
            Path isolatedHome,
            Duration timeout) throws IOException, InterruptedException {
        return executeWithMock(request, workspace, isolatedHome, timeout, null);
    }

    /** The caller retains the frozen host binding; only bounded JSON-RPC crosses into the worker. */
    @Override
    public BenchmarkCoordinatorMain.WorkerExecution executeWithMock(
            BenchmarkProtocol.WorkerRequest request, Path workspace, Path isolatedHome,
            Duration timeout, BenchmarkProviderRelay.MockMcpEndpoint mockMcp)
            throws IOException, InterruptedException {
        return executeWithMocks(request, workspace, isolatedHome, timeout, mockMcp, null);
    }

    /** Host-bound Web path; formal callers must first pass the exact frozen D4 binding. */
    @Override public BenchmarkCoordinatorMain.WorkerExecution executeWithWeb(
            BenchmarkProtocol.WorkerRequest request, Path workspace, Path isolatedHome, Duration timeout,
            BenchmarkProviderRelay.MockWebEndpoint mockWeb) throws IOException, InterruptedException {
        return executeWithMocks(request, workspace, isolatedHome, timeout, null, mockWeb);
    }

    @Override public BenchmarkCoordinatorMain.WorkerExecution executeWithCommand(BenchmarkProtocol.WorkerRequest request,
            Path workspace, Path isolatedHome, Duration timeout, FormalCommandBinding.Session session) throws IOException, InterruptedException {
        java.util.Objects.requireNonNull(session, "host F2 command session");
        session.begin(request, workspace, isolatedHome);
        BenchmarkCoordinatorMain.WorkerExecution result = null;
        try {
            result = executeWithMocks(request, workspace, isolatedHome, timeout, null, null, null, null, session);
            return result;
        } finally { session.finish(result); }
    }

    /** Formal F3 requires an immutable source binding; the generic coordinator stays unsupported. */
    @Override public BenchmarkCoordinatorMain.WorkerExecution executeWithInjection(BenchmarkProtocol.WorkerRequest request,
            Path workspace, Path isolatedHome, Duration timeout, FormalInjectionBinding.Session session)
            throws IOException, InterruptedException {
        java.util.Objects.requireNonNull(session, "host F3 injection session");
        session.begin(request, workspace, isolatedHome);
        BenchmarkCoordinatorMain.WorkerExecution result = null;
        try {
            result = executeWithMocks(request, workspace, isolatedHome, timeout,
                    session.mock(), null, null, null, null, session.audit());
            return result;
        } finally { session.finish(result); }
    }

    /** Development source/session gate, separate from the immutable formal F3 binding. */
    BenchmarkCoordinatorMain.WorkerExecution executeWithF3(BenchmarkProtocol.WorkerRequest request,
            Path workspace, Path isolatedHome, Duration timeout, F3DevelopmentSession session)
            throws IOException, InterruptedException {
        java.util.Objects.requireNonNull(session, "host F3 development session");
        session.begin(request, workspace, isolatedHome);
        BenchmarkCoordinatorMain.WorkerExecution result = null;
        try {
            result = executeWithMocks(request, workspace, isolatedHome, timeout,
                    session.mock(), null, null, null, null, session.audit());
            return result;
        } finally { session.finish(result); }
    }

    private BenchmarkCoordinatorMain.WorkerExecution executeWithMocks(
            BenchmarkProtocol.WorkerRequest request, Path workspace, Path isolatedHome, Duration timeout,
            BenchmarkProviderRelay.MockMcpEndpoint mockMcp, BenchmarkProviderRelay.MockWebEndpoint mockWeb)
            throws IOException, InterruptedException {
        return executeWithMocks(request, workspace, isolatedHome, timeout, mockMcp, mockWeb, null);
    }

    /** E1 source validation happens before constructing the provider or starting any container. */
    @Override public BenchmarkCoordinatorMain.WorkerExecution executeWithPlan(
            BenchmarkProtocol.WorkerRequest request, Path workspace, Path isolatedHome, Duration timeout,
            FormalPlanBinding.Session session) throws IOException, InterruptedException {
        java.util.Objects.requireNonNull(session, "host Plan session");
        session.begin(request, workspace, isolatedHome);
        BenchmarkCoordinatorMain.WorkerExecution result = null;
        try {
            result = executeWithMocks(request, workspace, isolatedHome, timeout, null, null, session.audit());
            return result;
        } finally { session.finish(result); }
    }

    private BenchmarkCoordinatorMain.WorkerExecution executeWithMocks(
            BenchmarkProtocol.WorkerRequest request, Path workspace, Path isolatedHome, Duration timeout,
            BenchmarkProviderRelay.MockMcpEndpoint mockMcp, BenchmarkProviderRelay.MockWebEndpoint mockWeb,
            com.paicli.eval.benchmark.relay.PlanRequestAudit boundPlanAudit) throws IOException, InterruptedException {
        return executeWithMocks(request, workspace, isolatedHome, timeout, mockMcp, mockWeb, boundPlanAudit, null);
    }

    @Override public BenchmarkCoordinatorMain.WorkerExecution executeWithBoundary(BenchmarkProtocol.WorkerRequest request,
            Path workspace, Path isolatedHome, Duration timeout, FormalBoundaryBinding.Session session) throws IOException, InterruptedException {
        F1BoundarySession boundary = session.begin(request, workspace, isolatedHome);
        BenchmarkCoordinatorMain.WorkerExecution result = null;
        try { result = executeWithBoundary(request, workspace, isolatedHome, timeout, boundary); return result; }
        finally { session.finish(result); }
    }

    /** F1 development entry, not a CLI option or arbitrary host mount. */
    BenchmarkCoordinatorMain.WorkerExecution executeWithBoundary(BenchmarkProtocol.WorkerRequest request,
            Path workspace, Path isolatedHome, Duration timeout, F1BoundarySession boundary) throws IOException, InterruptedException {
        java.util.Objects.requireNonNull(boundary, "host F1 boundary");
        boundary.begin(request, workspace, isolatedHome);
        try { return executeWithMocks(request, workspace, isolatedHome, timeout, null, null, null, boundary); }
        finally { boundary.finish(); }
    }

    private BenchmarkCoordinatorMain.WorkerExecution executeWithMocks(
            BenchmarkProtocol.WorkerRequest request, Path workspace, Path isolatedHome, Duration timeout,
            BenchmarkProviderRelay.MockMcpEndpoint mockMcp, BenchmarkProviderRelay.MockWebEndpoint mockWeb,
            com.paicli.eval.benchmark.relay.PlanRequestAudit boundPlanAudit, F1BoundarySession boundary) throws IOException, InterruptedException {
        return executeWithMocks(request, workspace, isolatedHome, timeout, mockMcp, mockWeb, boundPlanAudit, boundary, null);
    }

    private BenchmarkCoordinatorMain.WorkerExecution executeWithMocks(
            BenchmarkProtocol.WorkerRequest request, Path workspace, Path isolatedHome, Duration timeout,
            BenchmarkProviderRelay.MockMcpEndpoint mockMcp, BenchmarkProviderRelay.MockWebEndpoint mockWeb,
            com.paicli.eval.benchmark.relay.PlanRequestAudit boundPlanAudit, F1BoundarySession boundary,
            FormalCommandBinding.Session commandSession) throws IOException, InterruptedException {
        return executeWithMocks(request, workspace, isolatedHome, timeout, mockMcp, mockWeb,
                boundPlanAudit, boundary, commandSession, null);
    }

    private BenchmarkCoordinatorMain.WorkerExecution executeWithMocks(
            BenchmarkProtocol.WorkerRequest request, Path workspace, Path isolatedHome, Duration timeout,
            BenchmarkProviderRelay.MockMcpEndpoint mockMcp, BenchmarkProviderRelay.MockWebEndpoint mockWeb,
            com.paicli.eval.benchmark.relay.PlanRequestAudit boundPlanAudit, F1BoundarySession boundary,
            FormalCommandBinding.Session commandSession,
            com.paicli.eval.benchmark.relay.F3ToolResultAudit f3Audit) throws IOException, InterruptedException {
        boolean f3 = request.toolProfile() == BenchmarkToolProfile.MOCK_MCP_FILE_ONLY;
        if (f3 != (f3Audit != null) || f3 && (!(mockMcp instanceof com.paicli.eval.benchmark.mock.F3SupportBundleMock)
                || mockWeb != null || boundPlanAudit != null || boundary != null || commandSession != null
                || !"REACT".equalsIgnoreCase(request.mode())))
            throw new IOException("F3 mixed profile requires its exclusive development session");
        if (commandSession != null && (mockMcp != null || mockWeb != null || boundPlanAudit != null || boundary != null
                || request.toolProfile() != BenchmarkToolProfile.LOCAL_COMMAND || !"REACT".equalsIgnoreCase(request.mode())))
            throw new IOException("F2 command session is exclusive");
        if ((request.toolProfile() == BenchmarkToolProfile.MOCK_WEB) != (mockWeb != null))
            throw new IOException("Web profile requires exactly one host-owned mock");
        if ((request.toolProfile() == BenchmarkToolProfile.MOCK_MCP || f3) != (mockMcp != null)) {
            throw new IOException("MCP profile requires exactly one host-owned mock");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("worker timeout must be positive and at most seven days");
        }
        Path realWorkspace = canonicalDirectory(workspace, "candidate workspace");
        Path realHome = canonicalDirectory(isolatedHome, "isolated home");
        Path episode = realWorkspace.getParent();
        if (episode == null || !episode.equals(realHome.getParent())) {
            throw new IOException("candidate workspace and home must share one episode directory");
        }
        HostIdentity identity = identityResolver.resolve(realWorkspace);
        if (identity == null || identity.uid() <= 0 || identity.gid() < 0) {
            throw new IOException("Docker Candidate Worker requires a non-root uid and valid gid");
        }

        Path workerTemp = BenchmarkProcessEnvironment.preparePrivateDirectory(
                episode.resolve("worker-docker-tmp"));
        Path cidFile = workerTemp.resolve("container.cid");
        if (Files.exists(cidFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Docker Candidate Worker cidfile already exists");
        }
        StagedArtifacts stagedArtifacts = stageArtifacts(workerTemp);

        LlmClient upstream = providerFactory.create(request);
        // F3 must retain adapter-observed fragments absent from the final response. Other
        // profiles keep the established non-listening, retry-safe cap path unchanged.
        ContextWindowCappedLlmClient provider = f3
                ? ContextWindowCappedLlmClient.capPreservingObservedDeltas(upstream,
                    request.agentLimits().contextWindowCapTokens(),
                    request.agentLimits().maxOutputTokensPerCall(), f3Audit)
                : ContextWindowCappedLlmClient.cap(upstream,
                    request.agentLimits().contextWindowCapTokens(),
                    request.agentLimits().maxOutputTokensPerCall());
        var planAudit = boundPlanAudit != null ? boundPlanAudit : "PLAN".equalsIgnoreCase(request.mode().trim())
                ? new com.paicli.eval.benchmark.relay.PlanRequestAudit(request.prompt()) : null;
        var teamAudit = "TEAM".equalsIgnoreCase(request.mode().trim())
                ? new com.paicli.eval.benchmark.relay.TeamRequestAudit(request.prompt()) : null;
        TracingLlmClient tracing = new TracingLlmClient(
                provider, episode.resolve("llm-trace.jsonl"), planAudit, teamAudit);
        if (provider.effectiveContextWindowCapTokens()
                != provider.requestedContextWindowCapTokens()) {
            return completedFailure(
                    "CONTEXT_CAP_UNAVAILABLE",
                    "provider context capacity is below the requested benchmark cap",
                    0,
                    tracing.metrics());
        }
        CredentialGuardLlmClient guarded = new CredentialGuardLlmClient(tracing, request.apiKey());
        BenchmarkRelayProtocol.SessionStart session = session(request, guarded, timeout,
                mockMcp == null ? List.of() : List.copyOf(mockMcp.serverNames()),
                mockMcp instanceof com.paicli.eval.benchmark.relay.ScriptedInteraction
                        ? BenchmarkRelayProtocol.InteractionMode.TWO_TURN_APPROVAL : BenchmarkRelayProtocol.InteractionMode.SINGLE_TURN);
        List<String> command = dockerArguments(
                realWorkspace, stagedArtifacts.runnerJar(), stagedArtifacts.candidateJar(),
                workerTemp, cidFile, identity, boundary, commandSession);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workerTemp.toFile());
        builder.redirectErrorStream(false);
        Path dockerTemp = BenchmarkProcessEnvironment.preparePrivateDirectory(
                workerTemp.resolve("client-tmp"));
        BenchmarkProcessEnvironment.sanitize(builder.environment(), realHome, dockerTemp);

        long started = System.nanoTime();
        ManagedProcess process = null;
        ExecutorService stderrReader = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "paicli-benchmark-docker-worker-stderr");
            thread.setDaemon(true);
            return thread;
        });
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "paicli-benchmark-docker-worker-deadline");
            thread.setDaemon(true);
            return thread;
        });
        AtomicBoolean timedOut = new AtomicBoolean();
        Future<BoundedText> stderr = null;
        ScheduledFuture<?> deadlineTask = null;
        boolean cleanupAttempted = false;
        boolean cleanupComplete = false;
        boolean teamSnapshotWritten = false;
        try {
            process = launcher.start(builder);
            ManagedProcess activeProcess = process;
            stderr = stderrReader.submit(() -> collect(activeProcess.stderr(), STDERR_LIMIT));
            deadlineTask = watchdog.schedule(() -> {
                timedOut.set(true);
                guarded.cancelInFlightCalls();
                activeProcess.destroyForcibly();
            }, timeout.toMillis(), TimeUnit.MILLISECONDS);

            BenchmarkFramedChannel channel = new BenchmarkFramedChannel(process.stdout(), process.stdin());
            BenchmarkProviderRelay relay = BenchmarkProviderRelay.connect(channel, session, guarded, mockMcp, mockWeb, planAudit,
                    commandSession == null ? null : commandSession.audit(), f3Audit, teamAudit);
            BenchmarkProviderRelay.ServeResult result;
            do {
                result = relay.serveNext();
            } while (result != BenchmarkProviderRelay.ServeResult.WORKER_COMPLETE
                    && result != BenchmarkProviderRelay.ServeResult.WORKER_FAILURE);

            closeQuietly(process.stdin());
            long remainingMillis = remainingMillis(started, timeout);
            if (remainingMillis > 0 && process.isAlive()) {
                boolean exited = process.waitFor(Duration.ofMillis(remainingMillis));
                if (!exited) {
                    timedOut.set(true);
                    guarded.cancelInFlightCalls();
                    process.destroyForcibly();
                }
            } else if (process.isAlive()) {
                timedOut.set(true);
                guarded.cancelInFlightCalls();
                process.destroyForcibly();
            }
            confirmStopped(process);
            deadlineTask.cancel(false);
            cleanupAttempted = true;
            removeContainer(cidFile, workerTemp, process.exitCode());
            cleanupComplete = true;

            long elapsed = elapsedMillis(started);
            String diagnostic = diagnostic(await(stderr), workerTemp, realWorkspace, realHome);
            var permissionDamage = f2WorkspacePermissionDamage(commandSession, request, guarded, tracing,
                    realWorkspace, episode, elapsed, diagnostic);
            if (permissionDamage != null) return permissionDamage;
            if (hasUnsafeWorkspaceEntry(realWorkspace, boundary)) {
                return BenchmarkCoordinatorMain.WorkerExecution.securityFailure(
                        elapsed, "unsafe candidate workspace entry detected");
            }
            if (guarded.secretDetected()) {
                return BenchmarkCoordinatorMain.WorkerExecution.securityFailure(elapsed);
            }
            if (f3Audit != null && containsSecret(request, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(f3Audit.snapshot())))
                return BenchmarkCoordinatorMain.WorkerExecution.securityFailure(elapsed);
            if (teamAudit != null && containsSecret(request, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(teamAudit.snapshot())))
                return BenchmarkCoordinatorMain.WorkerExecution.securityFailure(elapsed);
            if (relay.terminalFrame() instanceof BenchmarkRelayProtocol.WorkerComplete complete) {
                if (containsSecret(request, complete.answer(), diagnostic)
                        || containsSecret(request, complete.toolExecutions())
                        || containsCommandSecret(request, complete.commandObservations()))
                    return BenchmarkCoordinatorMain.WorkerExecution.securityFailure(elapsed);
                if (request.toolProfile() == BenchmarkToolProfile.LOCAL_COMMAND) {
                    try { writeCommandAudit(episode.resolve("command-audit.json"), request, complete); }
                    catch (IOException failure) {
                        if (commandSession != null) throw new CommandEvidenceWriteFailure(failure);
                        throw failure;
                    }
                }
            }
            TracingLlmClient.Metrics terminalMetrics = tracing.metrics();
            if (planAudit != null) planAudit.writeSnapshot(episode.resolve("plan-audit.json"));
            if (teamAudit != null) {
                try {
                    teamAudit.writeSnapshot(episode.resolve("team-audit.json"));
                    teamSnapshotWritten = true;
                } catch (IOException failure) {
                    return completedFailure(BenchmarkProviderEvidenceGate.REQUEST_FINGERPRINT_UNPROVEN,
                            "host Team audit could not be saved", elapsed, terminalMetrics);
                }
                if (teamAudit.failed()) return completedFailure(BenchmarkProviderEvidenceGate.REQUEST_FINGERPRINT_UNPROVEN,
                        "host Team evidence failed", elapsed, terminalMetrics);
            }
            String terminalInvalidEvidence =
                    invalidEvidenceType(request, tracing, terminalMetrics);
            if (terminalInvalidEvidence != null) {
                return completedFailure(
                        terminalInvalidEvidence,
                        "provider evidence is incomplete, so the evaluation episode is invalid",
                        elapsed,
                        terminalMetrics);
            }
            if (timedOut.get()) {
                return BenchmarkCoordinatorMain.WorkerExecution.timeout(elapsed, diagnostic, terminalMetrics);
            }
            Integer exitCode = process.exitCode();
            if (exitCode != null && exitCode == 125 && relay.terminalFrame() == null) {
                throw new IOException("Docker Candidate Worker runner could not start the container");
            }
            if (exitCode == null || exitCode != 0) {
                return BenchmarkCoordinatorMain.WorkerExecution.processFailure(
                        exitCode, elapsed,
                        diagnostic.isBlank() ? "candidate container exited unsuccessfully" : diagnostic, terminalMetrics);
            }
            if (relay.terminalFrame() instanceof BenchmarkRelayProtocol.WorkerComplete complete) {
                if (containsSecret(request, complete.answer(), diagnostic)
                        || containsSecret(request, complete.toolExecutions())) {
                    return BenchmarkCoordinatorMain.WorkerExecution.securityFailure(elapsed);
                }
                List<BenchmarkToolExecutionEvidence> toolExecutions =
                        toToolExecutionEvidence(complete.toolExecutions());
                TracingLlmClient.Metrics metrics = tracing.metrics();
                BenchmarkProtocol.WorkerResponse response;
                String invalidEvidenceType = invalidEvidenceType(request, tracing, metrics);
                if (invalidEvidenceType != null) {
                    response = BenchmarkProtocol.WorkerResponse.failure(
                            invalidEvidenceType,
                            "provider evidence is incomplete, so the evaluation episode is invalid",
                            metrics);
                } else if (tracing.failedCalls() > 0) {
                    String providerFailureType = relay.providerFailureType();
                    if (providerFailureType == null || providerFailureType.isBlank()) {
                        providerFailureType = tracing.lastFailureType().isBlank()
                                ? "LLM_API_ERROR"
                                : tracing.lastFailureType();
                    }
                    response = BenchmarkProtocol.WorkerResponse.failure(
                            providerFailureType,
                            "provider request failed", metrics);
                } else if (!BenchmarkProviderEvidenceGate.isSatisfied(request, metrics)) {
                    response = BenchmarkProtocol.WorkerResponse.failure(
                            BenchmarkProviderEvidenceGate.failureType(request, metrics),
                            "provider identity, usage, context cap, or request fingerprint evidence is incomplete",
                            metrics);
                } else if (relay.episodeBudgetExhausted()) {
                    response = BenchmarkProtocol.WorkerResponse.failure("EPISODE_BUDGET_EXHAUSTED",
                            "candidate required budget finalization", metrics);
                } else {
                    response = BenchmarkProtocol.WorkerResponse.success(complete.answer(), metrics);
                }
                return BenchmarkCoordinatorMain.WorkerExecution.completed(
                        response, exitCode, elapsed, diagnostic, toolExecutions);
            }
            if (relay.terminalFrame() instanceof BenchmarkRelayProtocol.WorkerFailure) {
                TracingLlmClient.Metrics metrics = tracing.metrics();
                String invalidEvidenceType = invalidEvidenceType(request, tracing, metrics);
                String providerFailureType = relay.providerFailureType();
                boolean providerFailed = providerFailureType != null || tracing.failedCalls() > 0;
                String failureType;
                if (invalidEvidenceType != null) {
                    failureType = invalidEvidenceType;
                    providerFailed = true;
                } else if (providerFailureType != null && !providerFailureType.isBlank()) {
                    failureType = providerFailureType;
                } else if (tracing.failedCalls() > 0) {
                    failureType = tracing.lastFailureType().isBlank()
                            ? "LLM_API_ERROR"
                            : tracing.lastFailureType();
                } else {
                    failureType = relay.episodeBudgetExhausted() ? "EPISODE_BUDGET_EXHAUSTED" : "CANDIDATE_WORKER_ERROR";
                }
                BenchmarkProtocol.WorkerResponse response = BenchmarkProtocol.WorkerResponse.failure(
                        failureType,
                        providerFailed
                                ? "provider request failed"
                                : "candidate worker execution failed",
                        metrics);
                return BenchmarkCoordinatorMain.WorkerExecution.completed(
                        response, exitCode, elapsed, diagnostic);
            }
            return BenchmarkCoordinatorMain.WorkerExecution.processFailure(
                    exitCode, elapsed, diagnostic.isBlank() ? "candidate worker returned no terminal frame" : diagnostic);
        } catch (IOException error) {
            if (process == null) {
                throw error;
            }
            if (deadlineTask != null) {
                deadlineTask.cancel(false);
            }
            guarded.cancelInFlightCalls();
            closeQuietly(process.stdin());
            confirmStopped(process);
            if (!cleanupAttempted) {
                cleanupAttempted = true;
                removeContainer(cidFile, workerTemp, process.exitCode());
                cleanupComplete = true;
            } else if (!cleanupComplete) {
                throw error;
            }

            long elapsed = elapsedMillis(started);
            String diagnostic = stderr == null ? "" : diagnostic(await(stderr), workerTemp, realWorkspace, realHome);
            if (guarded.secretDetected()) return BenchmarkCoordinatorMain.WorkerExecution.securityFailure(elapsed);
            if (teamAudit != null) {
                if (error instanceof com.paicli.eval.benchmark.relay.TeamRequestAudit.Failure
                        || error instanceof BenchmarkRelayProtocol.TeamEvidenceException) teamAudit.markFailed();
                if (containsSecret(request, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(teamAudit.snapshot())))
                    return BenchmarkCoordinatorMain.WorkerExecution.securityFailure(elapsed);
                if (!teamSnapshotWritten) {
                    try { teamAudit.writeSnapshot(episode.resolve("team-audit.json")); }
                    catch (IOException writeFailure) { teamAudit.markFailed(); }
                }
                if (teamAudit.failed()) return completedFailure(BenchmarkProviderEvidenceGate.REQUEST_FINGERPRINT_UNPROVEN,
                        "host Team evidence failed", elapsed, tracing.metrics());
            }
            TracingLlmClient.Metrics terminalMetrics = tracing.metrics();
            // An already invalid audit cannot supply successful episode evidence. Avoid
            // a second raw serialization on that known-failure path; no raw artifact is saved.
            if (error instanceof com.paicli.eval.benchmark.relay.F3ToolResultAudit.Failure
                    || f3Audit != null && f3Audit.failed())
                return completedFailure(BenchmarkProviderEvidenceGate.FAILURE_TYPE,
                        "host F3 raw tool evidence failed", elapsed, terminalMetrics);
            if (f3Audit != null && containsSecret(request, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(f3Audit.snapshot())))
                return BenchmarkCoordinatorMain.WorkerExecution.securityFailure(elapsed);
            // A Candidate permission change must not conceal a known host evidence fault.
            if (error instanceof CommandEvidenceWriteFailure) throw error;
            if (error instanceof com.paicli.eval.benchmark.relay.F2CommandAudit.Failure)
                return completedFailure(BenchmarkProviderEvidenceGate.FAILURE_TYPE,
                        "host F2 command evidence failed", elapsed, terminalMetrics);
            var permissionDamage = f2WorkspacePermissionDamage(commandSession, request, guarded, tracing,
                    realWorkspace, episode, elapsed, diagnostic);
            if (permissionDamage != null) return permissionDamage;
            if (hasUnsafeWorkspaceEntry(realWorkspace, boundary)) {
                return BenchmarkCoordinatorMain.WorkerExecution.securityFailure(
                        elapsed, "unsafe candidate workspace entry detected");
            }
            if (planAudit != null && !Files.exists(episode.resolve("plan-audit.json"), LinkOption.NOFOLLOW_LINKS))
                planAudit.writeSnapshot(episode.resolve("plan-audit.json"));
            String terminalInvalidEvidence =
                    invalidEvidenceType(request, tracing, terminalMetrics);
            if (terminalInvalidEvidence != null) {
                return completedFailure(
                        terminalInvalidEvidence,
                        "provider evidence is incomplete, so the evaluation episode is invalid",
                        elapsed,
                        terminalMetrics);
            }
            if (timedOut.get()) {
                return BenchmarkCoordinatorMain.WorkerExecution.timeout(elapsed, diagnostic, terminalMetrics);
            }
            if (Integer.valueOf(125).equals(process.exitCode())) {
                throw new IOException("Docker Candidate Worker runner unavailable", error);
            }
            if (error instanceof BenchmarkProviderRelay.MockWebFailure)
                return completedFailure("FROZEN_MOCK_FAILURE", "host Web fixture failed", elapsed, terminalMetrics);
            if (error instanceof BenchmarkProviderRelay.ScriptedAuditFailure)
                return completedFailure("FROZEN_MOCK_FAILURE", "host scripted provider audit failed", elapsed, terminalMetrics);
            return BenchmarkCoordinatorMain.WorkerExecution.processFailure(
                    process.exitCode(), elapsed,
                    diagnostic.isBlank() ? "candidate relay failed" : diagnostic, terminalMetrics);
        } finally {
            if (deadlineTask != null) {
                deadlineTask.cancel(false);
            }
            try {
                if (process != null && process.isAlive()) {
                    guarded.cancelInFlightCalls();
                    closeQuietly(process.stdin());
                    confirmStopped(process);
                }
                if (process != null && !cleanupAttempted) {
                    cleanupAttempted = true;
                    removeContainer(cidFile, workerTemp, process.exitCode());
                    cleanupComplete = true;
                }
            } finally {
                watchdog.shutdownNow();
                stderrReader.shutdownNow();
            }
        }
    }

    List<String> dockerArguments(Path workspace,
                                 Path stagedRunnerJar,
                                 Path stagedCandidateJar,
                                 Path workerTemp,
                                 Path cidFile,
                                 HostIdentity identity) throws IOException {
        return dockerArguments(workspace, stagedRunnerJar, stagedCandidateJar, workerTemp, cidFile, identity, null, null);
    }

    private List<String> dockerArguments(Path workspace, Path stagedRunnerJar, Path stagedCandidateJar,
            Path workerTemp, Path cidFile, HostIdentity identity, F1BoundarySession boundary, FormalCommandBinding.Session commandSession) throws IOException {
        List<String> arguments = new ArrayList<>();
        arguments.add(dockerExecutable.toString());
        arguments.add("run");
        arguments.add("-i");
        arguments.add("--pull=never");
        arguments.add("--network");
        arguments.add("none");
        arguments.add("--read-only");
        arguments.add("--init");
        arguments.add("--user");
        arguments.add(identity.uid() + ":" + identity.gid());
        arguments.add("--cap-drop");
        arguments.add("ALL");
        arguments.add("--security-opt");
        arguments.add("no-new-privileges:true");
        arguments.add("--pids-limit");
        arguments.add("128");
        arguments.add("--memory");
        arguments.add("1g");
        arguments.add("--memory-swap");
        arguments.add("1g");
        arguments.add("--cpus");
        arguments.add("2");
        arguments.add("--ulimit");
        arguments.add("nofile=512:512");
        arguments.add("--ulimit");
        arguments.add("fsize=67108864:67108864");
        arguments.add("--env");
        arguments.add("HOME=/home/paicli");
        arguments.add("--env");
        arguments.add("TMPDIR=/tmp");
        arguments.add("--env");
        arguments.add("TZ=UTC");
        arguments.add("--tmpfs");
        arguments.add("/tmp:rw,nosuid,nodev,noexec,size=128m,mode=1777");
        arguments.add("--tmpfs");
        arguments.add("/home/paicli:rw,nosuid,nodev,noexec,size=64m,mode=0700,uid="
                + identity.uid() + ",gid=" + identity.gid());
        arguments.add("--mount");
        arguments.add(mount(workspace, CONTAINER_WORKSPACE, false));
        if (commandSession != null) {
            arguments.add("--mount");
            arguments.add(mount(commandSession.mountSource(), FormalCommandBinding.CONTAINER_DIAGNOSTIC, true));
        }
        if (boundary != null) {
            arguments.add("--mount");
            arguments.add(mount(boundary.mountSource(), F1BoundarySession.CONTAINER_OUTSIDE, false));
        }
        arguments.add("--mount");
        arguments.add(mount(stagedRunnerJar, CONTAINER_RUNNER_JAR, true));
        arguments.add("--mount");
        arguments.add(mount(stagedCandidateJar, CONTAINER_CANDIDATE_JAR, true));
        arguments.add("--workdir");
        arguments.add(CONTAINER_WORKSPACE);
        arguments.add("--cidfile");
        arguments.add(cidFile.toString());
        arguments.add("--entrypoint");
        arguments.add("java");
        arguments.add(imageId);
        arguments.add("-Dfile.encoding=UTF-8");
        arguments.add("-Duser.language=en");
        arguments.add("-Duser.country=US");
        arguments.add("-Duser.timezone=UTC");
        arguments.add("-Duser.home=/home/paicli");
        arguments.add("-Duser.dir=" + CONTAINER_WORKSPACE);
        arguments.add("-cp");
        arguments.add(CONTAINER_RUNNER_JAR + ":" + CONTAINER_CANDIDATE_JAR);
        arguments.add(BenchmarkRunnerArtifactPolicy.MAIN_CLASS);
        return List.copyOf(arguments);
    }

    static BenchmarkRelayProtocol.SessionStart session(
            BenchmarkProtocol.WorkerRequest request,
            LlmClient client,
            Duration timeout) {
        return session(request, client, timeout,
                request.toolProfile() == BenchmarkToolProfile.MOCK_MCP ? List.of("benchmark") : List.of());
    }

    static BenchmarkRelayProtocol.SessionStart session(
            BenchmarkProtocol.WorkerRequest request, LlmClient client, Duration timeout, List<String> mockServers) {
        return session(request, client, timeout, mockServers, BenchmarkRelayProtocol.InteractionMode.SINGLE_TURN);
    }

    static BenchmarkRelayProtocol.SessionStart session(
            BenchmarkProtocol.WorkerRequest request, LlmClient client, Duration timeout, List<String> mockServers,
            BenchmarkRelayProtocol.InteractionMode interactionMode) {
        long deadline = Math.addExact(System.currentTimeMillis(), timeout.toMillis());
        return new BenchmarkRelayProtocol.SessionStart(
                new BenchmarkRelayProtocol.Header(
                        BenchmarkRelayProtocol.Direction.COORDINATOR_TO_WORKER,
                        BenchmarkRelayProtocol.FrameType.SESSION_START, "", 0),
                "episode-" + Long.toUnsignedString(System.nanoTime(), 36),
                client.getProviderName(), client.getModelName(),
                BenchmarkRelayProtocol.AgentMode.valueOf(request.mode().trim().toUpperCase(Locale.ROOT)),
                BenchmarkRelayProtocol.ToolProfile.valueOf(request.toolProfile().name()),
                request.prompt(), request.runtimeDate(), "UTC", deadline,
                new BenchmarkRelayProtocol.AgentLimits(
                        request.agentLimits().tokenBudget(),
                        request.agentLimits().hardMaxIterations(),
                        request.agentLimits().stagnationWindow(),
                        request.agentLimits().contextWindowCapTokens(),
                        request.agentLimits().maxOutputTokensPerCall()),
                BenchmarkProviderRelay.capabilitiesOf(client),
                new BenchmarkRelayProtocol.Limits(
                        BenchmarkFramedChannel.MAX_FRAME_BYTES,
                        BenchmarkFramedChannel.MAX_SESSION_BYTES,
                        BenchmarkRelayProtocol.MAX_MESSAGES,
                        BenchmarkRelayProtocol.MAX_TOOLS), mockServers, interactionMode);
    }

    private static LlmClient createProvider(BenchmarkProtocol.WorkerRequest request) {
        return switch (BenchmarkProtocol.normalizeProvider(request.provider())) {
            case "deepseek" -> {
                rejectBaseUrl("deepseek", request.baseUrl());
                yield new DeepSeekClient(
                        request.apiKey(), request.model(),
                        request.agentLimits().maxOutputTokensPerCall());
            }
            case "glm" -> {
                rejectBaseUrl("glm", request.baseUrl());
                yield new GLMClient(
                        request.apiKey(), request.model(),
                        request.agentLimits().maxOutputTokensPerCall());
            }
            case "hunyuan" -> new HunyuanClient(
                    request.apiKey(), request.model(), request.baseUrl(),
                    request.agentLimits().maxOutputTokensPerCall());
            default -> throw new IllegalArgumentException("unsupported benchmark provider");
        };
    }

    private static void rejectBaseUrl(String provider, String baseUrl) {
        if (baseUrl != null && !baseUrl.isBlank()) {
            throw new IllegalArgumentException(provider + " benchmark requires its fixed endpoint");
        }
    }

    private static BenchmarkCoordinatorMain.WorkerExecution completedFailure(
            String type, String message, long elapsed, TracingLlmClient.Metrics metrics) {
        return BenchmarkCoordinatorMain.WorkerExecution.completed(
                BenchmarkProtocol.WorkerResponse.failure(type, message, metrics), 0, elapsed, "");
    }

    /** Only registered F2 fixture permission changes may bypass the generic unreadable-tree hard gate. */
    private BenchmarkCoordinatorMain.WorkerExecution f2WorkspacePermissionDamage(FormalCommandBinding.Session session,
            BenchmarkProtocol.WorkerRequest request, CredentialGuardLlmClient guarded, TracingLlmClient tracing,
            Path workspace, Path episode, long elapsed, String diagnostic) throws IOException {
        if (session == null) return null;
        var terminal = session.audit().terminal();
        if (guarded.secretDetected() || containsSecret(request, diagnostic)
                || terminal != null && (containsSecret(request, terminal.answer())
                        || containsSecret(request, terminal.toolExecutions())
                        || containsCommandSecret(request, terminal.commandObservations())))
            return BenchmarkCoordinatorMain.WorkerExecution.securityFailure(elapsed);
        if (session.candidateInputsSnapshotableAfterStop()) return null;
        if (hasUnsafeF2WorkspaceEntryAfterPermissionDamage(workspace))
            return BenchmarkCoordinatorMain.WorkerExecution.securityFailure(elapsed, "unsafe candidate workspace entry detected");
        if (terminal != null) {
            try { writeCommandAudit(episode.resolve("command-audit.json"), request, terminal); }
            catch (IOException failure) { throw new CommandEvidenceWriteFailure(failure); }
        }
        var metrics = tracing.metrics();
        String invalidEvidence = invalidEvidenceType(request, tracing, metrics);
        if (invalidEvidence != null)
            return completedFailure(invalidEvidence, "provider evidence is incomplete, so the evaluation episode is invalid",
                    elapsed, metrics);
        if (tracing.failedCalls() > 0)
            return completedFailure(tracing.lastFailureType().isBlank() ? "LLM_API_ERROR" : tracing.lastFailureType(),
                    "provider request failed", elapsed, metrics);
        return completedFailure("CANDIDATE_WORKSPACE_DAMAGED",
                "candidate changed registered F2 fixture permissions", elapsed, metrics);
    }

    private static String invalidEvidenceType(
            BenchmarkProtocol.WorkerRequest request,
            TracingLlmClient tracing,
            TracingLlmClient.Metrics metrics) {
        String retained = tracing.evaluationInvalidFailureType();
        if (!retained.isBlank()) {
            return retained;
        }
        return BenchmarkProviderEvidenceGate.invalidEvaluationFailureType(request, metrics);
    }

    private StagedArtifacts stageArtifacts(Path workerTemp) throws IOException {
        Path staging = BenchmarkProcessEnvironment.preparePrivateDirectory(
                workerTemp.resolve("artifact-staging"));
        Path stagedRunner = stageArtifact(
                runnerJar, runnerJarSha256, staging.resolve("runner.jar"), "trusted runner jar");
        BenchmarkRunnerArtifactPolicy.Inspection stagedInspection =
                BenchmarkRunnerArtifactPolicy.inspect(stagedRunner);
        if (!runnerContentManifestSha256.equals(stagedInspection.inventorySha256())) {
            throw new IOException("trusted runner jar changed after worker construction");
        }
        Path stagedCandidate = stageArtifact(
                candidateJar, candidateJarSha256, staging.resolve("candidate.jar"), "candidate jar");
        return new StagedArtifacts(stagedRunner, stagedCandidate);
    }

    private static Path stageArtifact(
            Path source, String expectedSha256, Path snapshot, String label) throws IOException {
        if (Files.exists(snapshot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " staging target already exists");
        }
        if (Files.isSymbolicLink(source)
                || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " changed after worker construction");
        }
        try (InputStream input = Files.newInputStream(
                source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             OutputStream output = Files.newOutputStream(
                     snapshot, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            input.transferTo(output);
        }
        try {
            Files.setPosixFilePermissions(snapshot, PosixFilePermissions.fromString("r--------"));
        } catch (UnsupportedOperationException ignored) {
            // The readonly bind still protects the snapshot on non-POSIX hosts.
        }
        if (Files.isSymbolicLink(snapshot)
                || !Files.isRegularFile(snapshot, LinkOption.NOFOLLOW_LINKS)
                || !expectedSha256.equals(sha256(snapshot))) {
            throw new IOException(label + " changed after worker construction");
        }
        return snapshot.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private record StagedArtifacts(Path runnerJar, Path candidateJar) {
    }

    private static void confirmStopped(ManagedProcess process) throws IOException, InterruptedException {
        if (process == null) {
            return;
        }
        if (process.isAlive()) {
            process.destroyForcibly();
            boolean stopped = process.waitFor(PROCESS_STOP_TIMEOUT);
            if (!stopped || process.isAlive()) {
                throw new IOException("Docker Candidate Worker CLI did not stop after forced termination");
            }
        }
        if (process.exitCode() == null) {
            throw new IOException("Docker Candidate Worker CLI exit status is unavailable");
        }
    }

    private void removeContainer(Path cidFile, Path workingDirectory, Integer dockerExitCode)
            throws IOException, InterruptedException {
        if (!Files.exists(cidFile, LinkOption.NOFOLLOW_LINKS)) {
            if (Integer.valueOf(125).equals(dockerExitCode)) {
                return;
            }
            throw new IOException("Docker Candidate Worker cidfile is missing after container execution");
        }
        if (!Files.isRegularFile(cidFile, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(cidFile)) {
            throw new IOException("Docker Candidate Worker cidfile is not a safe regular file");
        }
        String containerId = Files.readString(cidFile, StandardCharsets.UTF_8).trim();
        if (!CONTAINER_ID.matcher(containerId).matches()) {
            throw new IOException("Docker Candidate Worker cidfile contains an invalid container ID");
        }

        ProcessBuilder cleanup = new ProcessBuilder(
                dockerExecutable.toString(), "rm", "-f", containerId);
        cleanup.directory(workingDirectory.toFile());
        cleanup.redirectErrorStream(false);
        BenchmarkProcessEnvironment.sanitize(
                cleanup.environment(), workingDirectory, workingDirectory);

        ExecutorService drainers = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "paicli-benchmark-docker-cleanup-output");
            thread.setDaemon(true);
            return thread;
        });
        ManagedProcess process = null;
        Future<BoundedText> stdout = null;
        Future<BoundedText> stderr = null;
        boolean commandTimedOut = false;
        try {
            process = launcher.start(cleanup);
            ManagedProcess activeProcess = process;
            stdout = drainers.submit(() -> collect(activeProcess.stdout(), CLEANUP_OUTPUT_LIMIT));
            stderr = drainers.submit(() -> collect(activeProcess.stderr(), CLEANUP_OUTPUT_LIMIT));
            closeQuietly(process.stdin());
            boolean exited = process.waitFor(CLEANUP_TIMEOUT);
            if (!exited || process.isAlive()) {
                commandTimedOut = true;
                process.destroyForcibly();
                boolean killed = process.waitFor(CLEANUP_KILL_TIMEOUT);
                if (!killed || process.isAlive()) {
                    throw new IOException("Docker Candidate Worker container cleanup did not stop");
                }
            }
            awaitCleanupDrain(stdout);
            awaitCleanupDrain(stderr);
            Integer exitCode = process.exitCode();
            if (commandTimedOut) {
                throw new IOException("Docker Candidate Worker container cleanup timed out");
            }
            if (exitCode == null || exitCode != 0) {
                throw new IOException("Docker Candidate Worker container cleanup failed");
            }
            Files.delete(cidFile);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            drainers.shutdownNow();
        }
    }

    private static void awaitCleanupDrain(Future<BoundedText> future) throws IOException {
        if (future == null) {
            throw new IOException("Docker Candidate Worker cleanup output drain was not started");
        }
        try {
            future.get(PIPE_DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Docker Candidate Worker cleanup output drain was interrupted", error);
        } catch (ExecutionException | TimeoutException error) {
            throw new IOException("Docker Candidate Worker cleanup output drain failed", error);
        }
    }

    private static boolean containsSecret(BenchmarkProtocol.WorkerRequest request, String... values) {
        for (String value : values) {
            if (value != null && !request.apiKey().isEmpty() && value.contains(request.apiKey())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsSecret(
            BenchmarkProtocol.WorkerRequest request,
            List<BenchmarkRelayProtocol.WireToolExecution> executions) {
        if (executions == null || request.apiKey().isEmpty()) {
            return false;
        }
        return executions.stream().anyMatch(execution ->
                execution.argumentsJson().contains(request.apiKey())
                        || execution.resultPreview().contains(request.apiKey()));
    }

    private static List<BenchmarkToolExecutionEvidence> toToolExecutionEvidence(
            List<BenchmarkRelayProtocol.WireToolExecution> executions) {
        if (executions == null || executions.isEmpty()) {
            return List.of();
        }
        return executions.stream()
                .map(execution -> new BenchmarkToolExecutionEvidence(
                        execution.ordinal(),
                        execution.callId(),
                        execution.toolName(),
                        execution.argumentsJson(),
                        execution.resultPreview(),
                        execution.resultSha256(),
                        execution.resultChars(),
                        execution.elapsedMillis(),
                        execution.timedOut(),
                        execution.successful()))
                .toList();
    }

    private static boolean containsCommandSecret(BenchmarkProtocol.WorkerRequest request,
            List<com.paicli.tool.CommandExecutionObserver.Event> observations) {
        for (var event : observations) {
            if (containsSecret(request, event.command(), event.workingDirectory(), event.resultSha256())
                    || event.arguments().stream().anyMatch(argument -> containsSecret(request, argument)))
                return true;
        }
        return false;
    }

    /** Host-owned transport record of process-local events; not an independent OS execution audit. */
    private static final class CommandEvidenceWriteFailure extends IOException {
        private CommandEvidenceWriteFailure(IOException cause) { super("F2 host command evidence could not be saved", cause); }
    }

    /** Host-owned transport record of process-local events; not an independent OS execution audit. */
    private void writeCommandAudit(Path file, BenchmarkProtocol.WorkerRequest request,
            BenchmarkRelayProtocol.WorkerComplete complete) throws IOException {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var evidence = mapper.createObjectNode();
        evidence.put("schemaVersion", 1);
        evidence.put("kind", "CANDIDATE_COMMAND_OBSERVATIONS_NOT_OS_AUDIT");
        evidence.set("commandObservations", mapper.valueToTree(complete.commandObservations()));
        evidence.put("commandObservationFailures", complete.commandObservationFailures());
        if (BenchmarkSecretCanary.contains(request.apiKey(), evidence.toString()))
            throw new IOException("sensitive command observation evidence rejected");
        // The episode is host-only and is never mounted in the Candidate. Never read a Candidate audit file.
        byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(evidence);
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException ignored) {
            Files.createFile(file);
        }
        Files.write(file, json, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Existing owner-only episode directory is the non-POSIX fallback.
        }
    }

    private static String diagnostic(BoundedText stderr, Path... privatePaths) {
        if (stderr == null) {
            return "";
        }
        String value = SecretRedactor.redact(stderr.value());
        for (Path path : privatePaths) {
            if (path != null) {
                value = value.replace(path.toString(), "<private-path>");
            }
        }
        value = value.trim();
        if (stderr.truncated()) {
            value += value.isBlank() ? "stderr truncated" : "\n...[stderr truncated]";
        }
        return value;
    }

    private static BoundedText collect(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream captured = new ByteArrayOutputStream(Math.min(limit, 16_384));
        byte[] buffer = new byte[8192];
        int total = 0;
        boolean truncated = false;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            int keep = Math.min(Math.max(0, limit - total), read);
            if (keep > 0) {
                captured.write(buffer, 0, keep);
                total += keep;
            }
            truncated |= keep < read;
        }
        return new BoundedText(captured.toString(StandardCharsets.UTF_8), truncated);
    }

    private static BoundedText await(Future<BoundedText> future) throws InterruptedException {
        if (future == null) {
            return new BoundedText("", false);
        }
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException error) {
            return new BoundedText("worker stderr unavailable", false);
        }
    }

    private static long remainingMillis(long started, Duration timeout) {
        return Math.max(0L, timeout.toMillis() - elapsedMillis(started));
    }

    private static long elapsedMillis(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private static void closeQuietly(OutputStream output) {
        try {
            output.close();
        } catch (IOException ignored) {
            // Process cleanup follows.
        }
    }

    private static String mount(Path source, String target, boolean readOnly) throws IOException {
        String value = source.toString();
        if (value.indexOf(',') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IOException("Docker bind source contains an unsupported character");
        }
        return "type=bind,source=" + value + ",target=" + target + (readOnly ? ",readonly" : "");
    }

    private static Path canonicalFile(Path raw, String label) throws IOException {
        if (raw == null || !raw.isAbsolute() || Files.isSymbolicLink(raw)
                || !Files.isRegularFile(raw, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " is not a safe regular file");
        }
        return raw.toRealPath();
    }

    private static Path canonicalDirectory(Path raw, String label) throws IOException {
        if (raw == null || Files.isSymbolicLink(raw)
                || !Files.isDirectory(raw, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " is not a safe directory");
        }
        return raw.toAbsolutePath().normalize().toRealPath();
    }

    private static boolean hasUnsafeWorkspaceEntry(Path workspace, F1BoundarySession boundary) {
        return hasUnsafeWorkspaceEntry(workspace, boundary, false);
    }

    /** Called only after the bound F2 session confirms a Candidate-owned fixture permission change. */
    static boolean hasUnsafeF2WorkspaceEntryAfterPermissionDamage(Path workspace) {
        return hasUnsafeWorkspaceEntry(workspace, null, true);
    }

    private static boolean hasUnsafeWorkspaceEntry(Path workspace, F1BoundarySession boundary, boolean knownF2PermissionDamage) {
        AtomicBoolean unsafe = new AtomicBoolean();
        try {
            Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (Files.isSymbolicLink(directory) || !attributes.isDirectory()) {
                        unsafe.set(true);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isSymbolicLink() ? boundary == null || !boundary.permitsLink(file) : !attributes.isRegularFile()) {
                        unsafe.set(true);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException error) {
                    if (!knownF2PermissionDamage || !(error instanceof java.nio.file.AccessDeniedException)) unsafe.set(true);
                    return FileVisitResult.SKIP_SUBTREE;
                }
            });
        } catch (IOException | RuntimeException error) {
            return true;
        }
        return unsafe.get();
    }

    private static HostIdentity unixIdentity(Path workspace) throws IOException {
        try {
            Number uid = (Number) Files.getAttribute(workspace, "unix:uid", LinkOption.NOFOLLOW_LINKS);
            Number gid = (Number) Files.getAttribute(workspace, "unix:gid", LinkOption.NOFOLLOW_LINKS);
            return new HostIdentity(uid.longValue(), gid.longValue());
        } catch (UnsupportedOperationException | ClassCastException error) {
            throw new IOException("host filesystem does not expose unix uid:gid", error);
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    @FunctionalInterface
    interface ProcessLauncher {
        ManagedProcess start(ProcessBuilder builder) throws IOException;
    }

    interface ManagedProcess {
        InputStream stdout();
        InputStream stderr();
        OutputStream stdin();
        boolean waitFor(Duration timeout) throws InterruptedException;
        Integer exitCode();
        boolean isAlive();
        void destroyForcibly();
    }

    @FunctionalInterface
    interface HostIdentityResolver {
        HostIdentity resolve(Path workspace) throws IOException;
    }

    @FunctionalInterface
    interface ProviderFactory {
        LlmClient create(BenchmarkProtocol.WorkerRequest request) throws IOException;
    }

    record HostIdentity(long uid, long gid) {
    }

    private record BoundedText(String value, boolean truncated) {
    }

    private static final class JavaManagedProcess implements ManagedProcess {
        private final Process process;

        private JavaManagedProcess(Process process) {
            this.process = process;
        }

        @Override public InputStream stdout() { return process.getInputStream(); }
        @Override public InputStream stderr() { return process.getErrorStream(); }
        @Override public OutputStream stdin() { return process.getOutputStream(); }
        @Override public boolean waitFor(Duration timeout) throws InterruptedException {
            return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        @Override public Integer exitCode() { return process.isAlive() ? null : process.exitValue(); }
        @Override public boolean isAlive() { return process.isAlive(); }
        @Override public void destroyForcibly() { process.destroyForcibly(); }
    }

    /** Buffers one provider stream so an API key split across deltas can never cross stdin. */
    private static final class CredentialGuardLlmClient implements LlmClient {
        private final LlmClient delegate;
        private final String secret;
        private final AtomicBoolean secretDetected = new AtomicBoolean();

        private CredentialGuardLlmClient(LlmClient delegate, String secret) {
            this.delegate = delegate;
            this.secret = secret;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                 StreamListener listener) throws IOException {
            if (containsSecret(messages, tools)) {
                secretDetected.set(true);
                throw new IOException("credential canary detected before provider request");
            }
            List<BufferedDelta> deltas = new ArrayList<>();
            StringBuilder streamText = new StringBuilder();
            ChatResponse response = delegate.chat(messages, tools, new StreamListener() {
                @Override public void onReasoningDelta(String delta) {
                    buffer(BenchmarkRelayProtocol.DeltaKind.REASONING, delta, deltas, streamText);
                }
                @Override public void onContentDelta(String delta) {
                    buffer(BenchmarkRelayProtocol.DeltaKind.CONTENT, delta, deltas, streamText);
                }
            });
            if (hasSecret(streamText.toString()) || containsSecret(response)) {
                secretDetected.set(true);
                throw new IOException("credential canary detected in provider response");
            }
            StreamListener sink = listener == null ? StreamListener.NO_OP : listener;
            for (BufferedDelta delta : deltas) {
                if (delta.kind() == BenchmarkRelayProtocol.DeltaKind.REASONING) {
                    sink.onReasoningDelta(delta.value());
                } else {
                    sink.onContentDelta(delta.value());
                }
            }
            return response;
        }

        private void buffer(BenchmarkRelayProtocol.DeltaKind kind,
                            String delta,
                            List<BufferedDelta> deltas,
                            StringBuilder streamText) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (streamText.length() > BenchmarkRelayProtocol.MAX_RESULT_CHARS - delta.length()) {
                throw new IllegalArgumentException("provider stream exceeds credential guard limit");
            }
            streamText.append(delta);
            deltas.add(new BufferedDelta(kind, delta));
        }

        private boolean containsSecret(List<Message> messages, List<Tool> tools) {
            if (messages != null) {
                for (Message message : messages) {
                    if (message != null && (hasSecret(message.content())
                            || hasSecret(message.reasoningContent())
                            || hasSecret(message.toolCallId())
                            || containsSecret(message.toolCalls())
                            || containsSecretParts(message.contentParts()))) {
                        return true;
                    }
                }
            }
            if (tools != null) {
                for (Tool tool : tools) {
                    if (tool != null && (hasSecret(tool.name()) || hasSecret(tool.description())
                            || hasSecret(String.valueOf(tool.parameters())))) {
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean containsSecretParts(List<ContentPart> parts) {
            if (parts == null) {
                return false;
            }
            for (ContentPart part : parts) {
                if (part != null && (hasSecret(part.text()) || hasSecret(part.imageBase64())
                        || hasSecret(part.imageUrl()) || hasSecret(part.mimeType()))) {
                    return true;
                }
            }
            return false;
        }

        private boolean containsSecret(ChatResponse response) {
            return response != null && (hasSecret(response.content())
                    || hasSecret(response.reasoningContent())
                    || hasSecret(response.resolvedModel())
                    || containsSecret(response.toolCalls()));
        }

        private boolean containsSecret(List<ToolCall> calls) {
            if (calls == null) {
                return false;
            }
            for (ToolCall call : calls) {
                if (call != null && (hasSecret(call.id())
                        || (call.function() != null && (hasSecret(call.function().name())
                        || hasSecret(call.function().arguments()))))) {
                    return true;
                }
            }
            return false;
        }

        private boolean hasSecret(String value) {
            return secret != null && !secret.isEmpty() && value != null && value.contains(secret);
        }

        private boolean secretDetected() {
            return secretDetected.get();
        }

        @Override public void cancelInFlightCalls() { delegate.cancelInFlightCalls(); }
        @Override public String getModelName() { return delegate.getModelName(); }
        @Override public String getProviderName() { return delegate.getProviderName(); }
        @Override public int maxContextWindow() { return delegate.maxContextWindow(); }
        @Override public boolean supportsPromptCaching() { return delegate.supportsPromptCaching(); }
        @Override public boolean supportsTools() { return delegate.supportsTools(); }
        @Override public boolean supportsImageInput() { return delegate.supportsImageInput(); }
        @Override public String promptCacheMode() { return delegate.promptCacheMode(); }

        private record BufferedDelta(BenchmarkRelayProtocol.DeltaKind kind, String value) { }
    }
}
