package com.paicli.eval.benchmark;

import com.paicli.eval.benchmark.formal.AttemptKey;
import com.paicli.eval.benchmark.formal.FormalEpisodeOutcome;
import com.paicli.eval.benchmark.formal.FormalExecutionPlan;
import com.paicli.eval.benchmark.formal.FormalFixtureMaterializer;
import com.paicli.eval.benchmark.formal.FormalScoringAdapter;
import com.paicli.eval.benchmark.formal.FormalVerifierBundleMaterializer;
import com.paicli.eval.benchmark.scoring.ScoreCalculator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Executes the entire admitted schedule, or stops with an explicit unscored invalid batch. */
public final class FormalBatchRunner {
    private FormalBatchRunner() {}

    /** Production entry: only Docker relay is available; no HOST_DEV/worker override parameter. */
    public static RunReport run(FormalBatchPreparation.ReadyBatch batch, Path output, String runId)
            throws IOException, InterruptedException {
        Objects.requireNonNull(batch, "batch");
        batch.verifyBeforeExecution();
        var identity = batch.plan().artifacts();
        var worker = new DockerBenchmarkWorkerProcess(identity.dockerExecutable(),
                identity.workerImageId(), identity.candidateJar(), identity.runnerJar());
        if (!identity.candidateJarSha256().equals(worker.candidateJarSha256())
                || !identity.runnerJarSha256().equals(worker.runnerJarSha256())
                || !identity.runnerInventorySha256().equals(worker.runnerContentManifestSha256()))
            throw new IOException("formal worker artifacts changed after admission");
        var verifier = new DockerBenchmarkVerifier(identity.dockerExecutable(), identity.verifierImageId());
        return run(batch, output, runId, worker, verifier, Clock.systemUTC());
    }

    // Deterministic local tests inject workers/verifiers only from this trusted package.
    static RunReport run(FormalBatchPreparation.ReadyBatch batch, Path output, String runId,
                         BenchmarkCoordinatorMain.WorkerExecutor worker,
                         BenchmarkCoordinatorMain.VerifierExecutor verifier, Clock clock)
            throws IOException, InterruptedException {
        batch.verifyBeforeExecution();
        validateOutput(batch, output);
        var store = BenchmarkArtifactStore.create(output, runId);
        var plan = batch.plan();
        List<EpisodeResult> results = new ArrayList<>();
        store.writeManifest(manifest(plan, "RUNNING"));
        store.writeAggregate(progress(plan, results));
        for (int i = 0; i < plan.episodes().size(); i++) {
            var episode = plan.episodes().get(i);
            var request = batch.requests().get(i);
            if (!request.key().batchSha256().equals(batch.batchSha256())
                    || request.key().episodeOrdinal() != episode.ordinal())
                throw new IOException("formal prepared request binding changed");
            var artifact = store.episode(episode.caseId(), request.provider() + "-" + request.model(),
                    episode.repeat(), request.key().attempt());
            var context = new EpisodeContext(request.key(), artifact, clock);
            artifact.writeRun(context.record(null, "RUNNING"));
            FormalEpisodeOutcome outcome;
            try {
                outcome = executeEpisode(plan, episode, request, context, worker, verifier);
            } catch (InterruptedException interrupted) {
                context.event("runner_interrupted");
                outcome = new FormalEpisodeOutcome.Infra(request.key(),
                        FormalEpisodeOutcome.InfraCode.WORKER_UNAVAILABLE, List.of("runner:interrupted"));
                artifact.writeRun(context.record(outcome, "INTERRUPTED"));
                results.add(context.record(outcome, "INTERRUPTED"));
                store.writeAggregate(aggregate(plan, results));
                store.writeManifest(manifest(plan, "INTERRUPTED"));
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (IOException | RuntimeException error) {
                outcome = new FormalEpisodeOutcome.Infra(request.key(),
                        FormalEpisodeOutcome.InfraCode.ARTIFACT_IO_FAILURE,
                        List.of("runner:episode-io-failure"));
            }
            var result = context.record(outcome, outcome.getClass().getSimpleName().toUpperCase());
            artifact.writeRun(result);
            results.add(result);
            // Each full episode is already durable in its own run.json. Rewriting every
            // previous episode on each checkpoint makes serialization/redaction quadratic.
            store.writeAggregate(progress(plan, results));
            // Valid low scores continue. Invalid attempts stop spending and require a new
            // all-model run; there is deliberately no best-of, subset, or automatic retry loop.
            if (!(outcome instanceof FormalEpisodeOutcome.Scored)) break;
        }
        BatchSummary summary = aggregate(plan, results);
        store.writeManifest(manifest(plan, summary.status()));
        store.writeAggregate(summary);
        return new RunReport(store.runDirectory(), summary);
    }

    private static FormalEpisodeOutcome executeEpisode(FormalExecutionPlan plan,
            FormalExecutionPlan.EpisodePlan episode, FormalEpisodeRequestFactory.PreparedRequest prepared,
            EpisodeContext context, BenchmarkCoordinatorMain.WorkerExecutor executor,
            BenchmarkCoordinatorMain.VerifierExecutor verifier) throws IOException, InterruptedException {
        var key = prepared.key();
        var casePlan = episode.casePlan();
        var paths = FormalEpisodeRequestFactory.WorkerPaths.create(context.artifact);
        try {
            var fixture = FormalFixtureMaterializer.materialize(casePlan.fixture(), paths.workspace());
            fixture.verifyReady();
            context.fixtureSha256 = fixture.frozenSnapshotSha256();
        } catch (IOException error) {
            return new FormalEpisodeOutcome.Dataset(key, FormalEpisodeOutcome.DatasetCode.FIXTURE_DRIFT,
                    List.of("fixture:materialization-rejected"));
        }
        requireDockerUnchanged(plan);
        var request = prepared.toWorkerRequest(paths);
        var mockBinding = prepared.mockBinding();
        var webBinding = prepared.webBinding();
        var planBinding = prepared.planBinding();
        var boundaryBinding = prepared.boundaryBinding();
        var commandBinding = prepared.commandBinding();
        var injectionBinding = prepared.injectionBinding();
        com.paicli.eval.benchmark.mock.AuditedMockMcp mock;
        com.paicli.eval.benchmark.mock.D4WebMock web;
        FormalPlanBinding.Session planSession;
        FormalBoundaryBinding.Session boundarySession;
        FormalCommandBinding.Session commandSession;
        FormalInjectionBinding.Session injectionSession;
        try {
            mock = mockBinding == null ? null : mockBinding.newService();
            web = webBinding == null ? null : webBinding.newService();
            planSession = planBinding == null ? null : planBinding.newSession();
            boundarySession = boundaryBinding == null ? null : boundaryBinding.newSession();
            commandSession = commandBinding == null ? null : commandBinding.newSession();
            injectionSession = injectionBinding == null ? null : injectionBinding.newSession();
        } catch (IOException error) {
            return boundaryBinding != null || commandBinding != null || injectionBinding != null
                    ? evidenceDefect(key) : planBinding == null ? mockDrift(key) : planDrift(key);
        }
        Duration timeout = Duration.ofSeconds(prepared.timeoutSeconds());
        BenchmarkCoordinatorMain.WorkerExecution worker = null;
        context.event("worker_dispatch_started");
        try {
            worker = injectionSession != null ? executor.executeWithInjection(request, paths.workspace(), paths.home(), timeout, injectionSession)
                    : commandSession != null ? executor.executeWithCommand(request, paths.workspace(), paths.home(), timeout, commandSession)
                    : boundarySession != null ? executor.executeWithBoundary(request, paths.workspace(), paths.home(), timeout, boundarySession)
                    : planSession != null ? executor.executeWithPlan(request, paths.workspace(), paths.home(), timeout, planSession)
                    : web != null ? executor.executeWithWeb(request, paths.workspace(), paths.home(), timeout, web)
                    : mock == null ? executor.execute(request, paths.workspace(), paths.home(), timeout)
                    : executor.executeWithMock(request, paths.workspace(), paths.home(), timeout, mock);
        } catch (IOException error) {
            // Capture the retained host audit and check integrity before classifying launch failure.
        } finally {
            context.event("worker_dispatch_finished");
            if (injectionBinding != null) {
                try { injectionBinding.verifyUnchanged(); }
                catch (IOException error) { context.boundaryDependencyDrift = true; }
            }
            if (commandBinding != null) {
                try { commandBinding.verifyUnchanged(); }
                catch (IOException error) { context.boundaryDependencyDrift = true; }
            }
            if (boundaryBinding != null) {
                try { boundaryBinding.verifyUnchanged(); }
                catch (IOException error) { context.boundaryDependencyDrift = true; }
            }
            if (planSession != null) {
                context.planAudit = planSession.retainedAudit();
                if (BenchmarkSecretCanary.contains(request.apiKey(),
                        new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(context.planAudit))) {
                    context.planSecurityViolation = true; context.planAudit = null;
                }
                try { planBinding.verifyUnchanged(); }
                catch (IOException error) { context.planDependencyDrift = true; }
            }
            if (web != null) {
                context.webEvidence = webBinding.evidence(web);
                if (BenchmarkSecretCanary.contains(request.apiKey(),
                        new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(context.webEvidence))) {
                    context.mockSecurityViolation = true; context.webEvidence = null;
                }
                try { webBinding.verifyUnchanged(); }
                catch (IOException error) { context.mockDependencyDrift = true; }
            }
            if (mock != null) {
                context.mockEvidence = mockBinding.evidence(mock);
                if (BenchmarkSecretCanary.contains(request.apiKey(),
                        new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(context.mockEvidence))) {
                    context.mockSecurityViolation = true;
                    context.mockEvidence = null;
                }
                try { mockBinding.verifyUnchanged(); }
                catch (IOException error) { context.mockDependencyDrift = true; }
            }
        }
        if (context.mockSecurityViolation) return security(context, "mock:credential-canary");
        if (context.planSecurityViolation) return security(context, "plan:credential-canary");
        if (context.mockDependencyDrift) return mockDrift(key);
        if (context.planDependencyDrift) return planDrift(key);
        if (context.boundaryDependencyDrift) return evidenceDefect(key);
        if (worker == null)
            return new FormalEpisodeOutcome.Infra(key, FormalEpisodeOutcome.InfraCode.WORKER_UNAVAILABLE,
                    List.of("worker:launch-failed"));
        if (planSession != null) {
            try { planSession.requireReturned(worker); }
            catch (IOException error) { return evidenceDefect(key); }
        }
        if (boundarySession != null) {
            try { boundarySession.requireReturned(worker); }
            catch (IOException error) { return evidenceDefect(key); }
        }
        if (commandSession != null) {
            try { commandSession.requireReturned(worker); }
            catch (IOException error) { return evidenceDefect(key); }
        }
        if (injectionSession != null) {
            try { injectionSession.requireReturned(worker); }
            catch (IOException error) { return evidenceDefect(key); }
        }
        var response = worker.response();
        context.metrics = response == null ? null : response.metrics();
        String secret = request.apiKey();
        if (context.mockSecurityViolation || worker.status() == BenchmarkCoordinatorMain.WorkerStatus.SECURITY_ERROR
                || BenchmarkSecretCanary.contains(secret, worker.diagnostic(),
                        response == null ? null : response.answer(),
                        response == null ? null : response.errorType(),
                        response == null ? null : response.errorMessage(),
                        context.metrics == null ? null : context.metrics.resolvedModel(),
                        context.metrics == null ? null : context.metrics.systemPromptSha256(),
                        context.metrics == null ? null : context.metrics.initialToolSchemaSha256())
                || (commandSession != null && !commandSession.candidateInputsSnapshotableAfterStop()
                        ? commandSession.containsSecretsOutsideDamagedWorkspace(secret)
                        : BenchmarkSecretCanary.containsInTree(context.artifact.directory(), secret)))
            return security(context, "worker:security-boundary");

        String failureType = response == null ? "" : response.errorType();
        if (injectionSession != null && injectionSession.audit().failed()) return evidenceDefect(key);
        if (commandSession != null && commandSession.audit().terminal() != null
                && commandSession.audit().terminal().commandObservationFailures() != 0) return evidenceDefect(key);
        if (BenchmarkProviderEvidenceGate.isEvaluationInvalidFailureType(failureType))
            return evidenceDefect(key);
        var disposition = BenchmarkFailureClassifier.classifyWorker(worker);
        if (disposition == BenchmarkFailureClassifier.Disposition.INFRA_ERROR)
            return new FormalEpisodeOutcome.Infra(key,
                    worker.status() == BenchmarkCoordinatorMain.WorkerStatus.START_FAILURE
                            ? FormalEpisodeOutcome.InfraCode.WORKER_UNAVAILABLE
                            : FormalEpisodeOutcome.InfraCode.PROVIDER_UNAVAILABLE,
                    List.of("worker:infrastructure-unavailable"));
        if (BenchmarkProviderEvidenceGate.invalidEvaluationFailureType(request, context.metrics) != null)
            return evidenceDefect(key);
        if (disposition == BenchmarkFailureClassifier.Disposition.SCORED_FAILURE
                || BenchmarkProviderEvidenceGate.failureType(request, context.metrics) != null)
            return zero(key, "worker:valid-candidate-failure");
        if (planSession != null && context.planAudit.failed()) return evidenceDefect(key);
        if (commandSession != null && commandSession.audit().failed()) return evidenceDefect(key);
        if (commandSession != null && !commandSession.candidateInputsSnapshotable())
            return zero(key, "command:candidate-input-filesystem-damaged");

        String answer = SecretRedactor.redact(response.answer().replace(secret, SecretRedactor.REDACTED));
        context.artifact.writeAnswer(answer);
        BenchmarkVerifierWorkspaceSnapshot.Snapshot snapshot;
        try {
            Path snapshotTarget = context.artifact.createPrivateDirectory("verifier-workspace");
            snapshot = injectionSession != null ? injectionSession.snapshot(snapshotTarget)
                    : commandSession != null ? commandSession.snapshot(snapshotTarget)
                    : boundarySession != null ? boundarySession.snapshot(snapshotTarget)
                    : BenchmarkVerifierWorkspaceSnapshot.create(paths.workspace(), snapshotTarget);
        } catch (BenchmarkVerifierWorkspaceSnapshot.UnsafeWorkspaceException error) {
            return security(context, "workspace:unsafe-snapshot");
        } catch (IOException error) {
            return new FormalEpisodeOutcome.Infra(key, FormalEpisodeOutcome.InfraCode.ARTIFACT_IO_FAILURE,
                    List.of("workspace:snapshot-io-failure"));
        }
        context.workspaceSha256 = snapshot.treeSha256();
        FormalVerifierBundleMaterializer.TrustedVerifierBundle bundle;
        try {
            // Hidden verifier dependencies are materialized only after the Candidate exits.
            bundle = FormalVerifierBundleMaterializer.materialize(casePlan, context.artifact);
        } catch (FormalVerifierBundleMaterializer.MaterializationException error) {
            return switch (error.kind()) {
                case DATASET -> new FormalEpisodeOutcome.Dataset(key,
                        FormalEpisodeOutcome.DatasetCode.VERIFIER_DEPENDENCY_DRIFT,
                        List.of("verifier:dependency-drift"));
                case SECURITY -> security(context, "verifier:bundle-unsafe");
                case INFRA -> new FormalEpisodeOutcome.Infra(key,
                        FormalEpisodeOutcome.InfraCode.VERIFIER_UNAVAILABLE,
                        List.of("verifier:bundle-unavailable"));
            };
        }
        context.verifierBundleSha256 = bundle.bundleSha256();
        Path evidence = injectionSession != null ? BenchmarkEvidenceEnvelope.writeBoundInjection(context.artifact.directory(),
                paths.workspace(), paths.home(), episode.repeat(), injectionSession, snapshot, bundle, secret)
                : commandSession != null ? BenchmarkEvidenceEnvelope.writeBoundCommand(context.artifact.directory(),
                paths.workspace(), paths.home(), episode.repeat(), commandSession, snapshot, bundle, secret)
                : boundarySession != null ? BenchmarkEvidenceEnvelope.writeBoundBoundary(context.artifact.directory(),
                paths.workspace(), paths.home(), episode.repeat(), boundarySession, snapshot, bundle, secret)
                : planSession != null ? BenchmarkEvidenceEnvelope.writeBoundPlan(context.artifact.directory(),
                paths.workspace(), paths.home(), episode.repeat(), planSession, snapshot, bundle, secret)
                : BenchmarkEvidenceEnvelope.writeFormal(context.artifact.directory(),
                snapshot.directory(), paths.home(), episode.caseId(), episode.repeat(),
                CaseDefinition.Mode.valueOf(casePlan.mode().name()), prepared.toolProfile(),
                answer, context.metrics, snapshot, bundle, worker.toolExecutions(), context.mockEvidence, context.webEvidence, secret);
        context.evidenceSha256 = hash(evidence);
        BenchmarkVerifier.Result verification;
        context.event("verifier_dispatch_started");
        try {
            bundle.verifyUnchanged(); snapshot.verifyUnchanged(); requireDockerUnchanged(plan);
            if (boundarySession != null) boundarySession.verifyTerminalUnchanged();
            if (commandSession != null) commandSession.verifyTerminalUnchanged();
            if (injectionSession != null) injectionSession.verifyTerminalUnchanged();
            verification = verifier.verify(new CaseDefinition.VerifierInvocation(bundle.root(),
                            casePlan.verifier().registeredArguments()), snapshot.directory(), paths.home(),
                    evidence, timeout);
        } catch (IOException error) {
            try {
                bundle.verifyUnchanged(); snapshot.verifyUnchanged();
                if (boundarySession != null) boundarySession.verifyTerminalUnchanged();
                if (commandSession != null) commandSession.verifyTerminalUnchanged();
                if (injectionSession != null) injectionSession.verifyTerminalUnchanged();
                if (!context.evidenceSha256.equals(hash(evidence)))
                    return security(context, "verifier:evidence-changed");
            }
            catch (IOException changed) { return security(context, "verifier:input-changed"); }
            return new FormalEpisodeOutcome.Infra(key, FormalEpisodeOutcome.InfraCode.VERIFIER_UNAVAILABLE,
                    List.of("verifier:execution-unavailable"));
        } finally {
            context.event("verifier_dispatch_finished");
        }
        try {
            bundle.verifyUnchanged(); snapshot.verifyUnchanged();
            if (boundarySession != null) boundarySession.verifyTerminalUnchanged();
            if (commandSession != null) commandSession.verifyTerminalUnchanged();
            if (injectionSession != null) injectionSession.verifyTerminalUnchanged();
            if (!context.evidenceSha256.equals(hash(evidence))) return security(context, "verifier:evidence-changed");
        } catch (IOException changed) { return security(context, "verifier:input-changed"); }
        if (BenchmarkSecretCanary.contains(secret, verification.stdout(), verification.stderr())
                || BenchmarkSecretCanary.containsInTree(context.artifact.directory(), secret))
            return security(context, "verifier:credential-canary");
        context.artifact.writeVerifier(verification);
        // Formal assertions live in structured JSON, not the process exit code. A crashed
        // wrapper cannot silently turn a failed setup operation into a Candidate zero.
        if (verification.status() != BenchmarkVerifier.Status.PASSED)
            return new FormalEpisodeOutcome.Infra(key, FormalEpisodeOutcome.InfraCode.VERIFIER_UNAVAILABLE,
                    List.of("verifier:nonzero-or-timeout"));
        if (verification.stdoutTruncated() || verification.stderrTruncated())
            return new FormalEpisodeOutcome.EvaluationDefect(key,
                    FormalEpisodeOutcome.EvaluationDefectCode.INVALID_VERIFIER_REPORT,
                    List.of("verifier:truncated-report"));
        byte[] report = verification.stdout().getBytes(StandardCharsets.UTF_8);
        context.verifierReportSha256 = hash(report);
        return FormalScoringAdapter.adapt(key, casePlan, report, ScoreCalculator.JudgeAvailability.UNAVAILABLE);
    }

    private static FormalEpisodeOutcome evidenceDefect(AttemptKey key) {
        return new FormalEpisodeOutcome.EvaluationDefect(key,
                FormalEpisodeOutcome.EvaluationDefectCode.PROVIDER_EVIDENCE_UNAVAILABLE,
                List.of("provider:evidence-incomplete"));
    }

    private static FormalEpisodeOutcome zero(AttemptKey key, String evidence) {
        return new FormalEpisodeOutcome.Scored(key, new ScoreCalculator.Result(0, false, false,
                ScoreCalculator.FailureClass.SCORE_BELOW_THRESHOLD, List.of(evidence)));
    }

    private static FormalEpisodeOutcome mockDrift(AttemptKey key) {
        return new FormalEpisodeOutcome.Dataset(key,
                FormalEpisodeOutcome.DatasetCode.VERIFIER_DEPENDENCY_DRIFT,
                List.of("mock:frozen-dependency-drift"));
    }

    private static FormalEpisodeOutcome planDrift(AttemptKey key) {
        return new FormalEpisodeOutcome.Dataset(key, FormalEpisodeOutcome.DatasetCode.VERIFIER_DEPENDENCY_DRIFT,
                List.of("plan:frozen-dependency-drift"));
    }

    private static FormalEpisodeOutcome security(EpisodeContext context, String evidence) {
        // Metrics strings are provider-controlled too. Never serialize a canary in metadata.
        context.metrics = null;
        return new FormalEpisodeOutcome.Security(context.key,
                FormalEpisodeOutcome.SecurityCode.SANDBOX_BOUNDARY_VIOLATION, List.of(evidence));
    }

    private static ProgressCheckpoint progress(FormalExecutionPlan plan, List<EpisodeResult> results) {
        return new ProgressCheckpoint(1, "PROGRESS_ONLY", plan.artifacts().formalBatchContractSha256(),
                plan.episodes().size(), results.size(),
                (int) results.stream().filter(r -> r.outcome() instanceof FormalEpisodeOutcome.Scored).count(),
                true, false);
    }

    static BatchSummary aggregate(FormalExecutionPlan plan, List<EpisodeResult> results) {
        List<EpisodeResult> copy = List.copyOf(results);
        boolean valid = copy.size() == plan.episodes().size();
        int scored = 0;
        Map<String, Double> sums = new LinkedHashMap<>();
        for (int i = 0; i < copy.size(); i++) {
            var expected = plan.episodes().get(i);
            var actual = copy.get(i);
            if (!actual.key().equals(AttemptKey.from(plan, expected, 1)))
                throw new IllegalArgumentException("formal aggregate episode binding mismatch");
            if (actual.outcome() instanceof FormalEpisodeOutcome.Scored score) {
                scored++;
                sums.merge(expected.model().provider(), (double) score.score() * expected.casePlan().weight(), Double::sum);
            } else valid = false;
        }
        Map<String, Double> means = new LinkedHashMap<>();
        if (valid) sums.forEach((provider, sum) -> means.put(provider, sum / (100.0 * plan.formalBatchContract().repeats())));
        boolean invalid = copy.stream().anyMatch(r -> !(r.outcome() instanceof FormalEpisodeOutcome.Scored));
        String status = invalid ? "INVALID_REQUIRES_SYMMETRIC_RERUN"
                : valid ? "EXECUTED_NOT_RELEASED" : "RUNNING";
        return new BatchSummary(1, plan.artifacts().formalBatchContractSha256(), status,
                plan.episodes().size(), copy.size(), scored, valid, false,
                valid ? Map.copyOf(means) : null, null, copy);
    }

    private static Map<String, Object> manifest(FormalExecutionPlan plan, String status) {
        var a = plan.artifacts();
        var batch = plan.formalBatchContract();
        return Map.ofEntries(Map.entry("schemaVersion", 2), Map.entry("batchSha256", a.formalBatchContractSha256()),
                Map.entry("status", status), Map.entry("plannedEpisodes", plan.episodes().size()),
                Map.entry("caseCount", plan.cases().size()),
                Map.entry("batchContractVersion", batch.contractVersion()), Map.entry("planVersion", plan.planVersion()),
                Map.entry("models", batch.models()), Map.entry("repeats", batch.repeats()),
                Map.entry("publishable", false), Map.entry("executionOrder", plan.executionOrder()),
                Map.entry("workerIsolation", "DOCKER_RELAY"), Map.entry("workerImageId", a.workerImageId()),
                Map.entry("verifierImageId", a.verifierImageId()), Map.entry("candidateJarSha256", a.candidateJarSha256()),
                Map.entry("runnerJarSha256", a.runnerJarSha256()), Map.entry("suiteSha256", a.suiteSha256()),
                Map.entry("freezeManifestSha256", a.freezeManifestSha256()),
                Map.entry("invalidRunPolicy", plan.formalBatchContract().invalidRunPolicy()));
    }

    private static void validateOutput(FormalBatchPreparation.ReadyBatch batch, Path output) throws IOException {
        if (output == null || !output.isAbsolute() || !output.normalize().equals(output))
            throw new IOException("formal output must be absolute and normalized");
        Path real = BenchmarkArtifactStore.resolveAgainstRealAncestor(output);
        batch.requireSeparateOutput(real);
        for (Path p = real; p != null; p = p.getParent())
            for (String vcs : List.of(".git", ".hg", ".svn"))
                if (Files.exists(p.resolve(vcs), LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("formal artifacts must remain outside version control");
    }

    private static void requireDockerUnchanged(FormalExecutionPlan plan) throws IOException {
        Path docker = plan.artifacts().dockerExecutable();
        if (!docker.equals(docker.toRealPath()) || !Files.isExecutable(docker)
                || !hash(docker).equals(plan.artifacts().dockerExecutableSha256()))
            throw new IOException("Docker executable differs from formal registration");
    }

    private static String hash(Path file) throws IOException {
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            MessageDigest digest = digest();
            byte[] buffer = new byte[16 * 1024]; int size;
            while ((size = input.read(buffer)) != -1) digest.update(buffer, 0, size);
            return HexFormat.of().formatHex(digest.digest());
        }
    }
    private static String hash(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public record Event(String event, String timestamp) {}
    public record ProgressCheckpoint(int schemaVersion, String checkpointKind, String batchSha256,
            int plannedEpisodes, int attemptedEpisodes, int scoredEpisodes,
            boolean episodesStoredSeparately, boolean publishable) {}
    public record EpisodeResult(AttemptKey key, String status, FormalEpisodeOutcome outcome,
            TracingLlmClient.Metrics metrics, String fixtureSha256, String workspaceSha256,
            String verifierBundleSha256, String evidenceSha256, String verifierReportSha256,
            List<Event> events, FormalMockMcpBinding.MockEvidence mockEvidence,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            FormalMockWebBinding.MockEvidence webEvidence,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            com.paicli.eval.benchmark.relay.PlanRequestAudit.Snapshot planAudit) {}
    public record BatchSummary(int schemaVersion, String batchSha256, String status,
            int plannedEpisodes, int attemptedEpisodes, int scoredEpisodes, boolean completeValidCoverage,
            boolean publishable, Map<String, Double> observedMeanWeightedScores,
            Map<String, Double> formalScores, List<EpisodeResult> episodes) {}
    public record RunReport(Path directory, BatchSummary summary) {}

    private static final class EpisodeContext {
        final AttemptKey key;
        final BenchmarkArtifactStore.EpisodeArtifacts artifact;
        final Clock clock;
        final List<Event> events = new ArrayList<>();
        TracingLlmClient.Metrics metrics;
        FormalMockMcpBinding.MockEvidence mockEvidence;
        FormalMockWebBinding.MockEvidence webEvidence;
        com.paicli.eval.benchmark.relay.PlanRequestAudit.Snapshot planAudit;
        boolean mockSecurityViolation;
        boolean mockDependencyDrift;
        boolean planSecurityViolation;
        boolean planDependencyDrift;
        boolean boundaryDependencyDrift;
        String fixtureSha256, workspaceSha256, verifierBundleSha256, evidenceSha256, verifierReportSha256;
        EpisodeContext(AttemptKey key, BenchmarkArtifactStore.EpisodeArtifacts artifact, Clock clock) {
            this.key = key; this.artifact = artifact; this.clock = clock;
        }
        void event(String name) { events.add(new Event(name, Instant.now(clock).toString())); }
        EpisodeResult record(FormalEpisodeOutcome outcome, String status) {
            return new EpisodeResult(key, status, outcome, metrics, fixtureSha256, workspaceSha256,
                    verifierBundleSha256, evidenceSha256, verifierReportSha256, List.copyOf(events), mockEvidence, webEvidence, planAudit);
        }
    }
}
