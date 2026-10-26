package com.paicli.eval.benchmark;

import com.paicli.config.PaiCliConfig;

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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Sequential development-suite coordinator. Every case/repeat runs in a fresh worker JVM. */
public final class BenchmarkCoordinatorMain {
    private static final int RUN_EVIDENCE_SCHEMA_VERSION = 3;
    private static final int DEFAULT_REPEATS = 1;
    private static final long DEFAULT_TIMEOUT_SECONDS = 1_800;
    private static final Path DEFAULT_DOCKER_EXECUTABLE = Path.of("/usr/local/bin/docker");
    private static final Pattern FROZEN_IMAGE_ID = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final String HUNYUAN_OFFICIAL_BASE_URL = "https://tokenhub.tencentmaas.com/v1";
    private static final Map<String, String> LOCKED_MODELS = Map.of(
            "deepseek", "deepseek-v4-flash",
            "hunyuan", "hy4-preview",
            "glm", "glm-5.3-flash");

    private BenchmarkCoordinatorMain() {
    }

    public static void main(String[] args) {
        if (containsHelp(args)) {
            System.out.println(usage());
            return;
        }
        try {
            Options options = Options.parse(args, Clock.systemUTC());
            RunReport report = run(
                    options,
                    new PaiCliProviderSettingsSource(),
                    workerFor(options),
                    verifierFor(options),
                    Clock.systemUTC());
            System.out.println("Benchmark artifacts: " + report.runDirectory());
            System.out.println("Diagnostic score (dev): "
                    + (report.aggregate().diagnosticMeanWeightedScore() == null
                    ? "n/a"
                    : String.format(Locale.ROOT, "%.2f", report.aggregate().diagnosticMeanWeightedScore())));
            System.out.println("Infrastructure errors: " + report.aggregate().infraErrorEpisodes()
                    + "/" + report.aggregate().totalEpisodes());
            System.out.println("Publishable: " + report.aggregate().publishable());
        } catch (Exception e) {
            System.err.println("Benchmark failed: " + safeMessage(e));
            System.err.println(usage());
            System.exit(2);
        }
    }

    static RunReport run(Options options,
                         ProviderSettingsSource settingsSource,
                         WorkerExecutor workerExecutor,
                         VerifierExecutor verifier,
                         Clock clock) throws IOException, InterruptedException {
        SuiteDefinition suite = SuiteDefinition.load(options.suite());
        List<CaseDefinition> selectedCases = selectCases(suite, options.caseIds());
        preflight(suite, selectedCases);
        validateOutputLocation(options.output(), suite.sourceDirectory());

        ProviderSettings settings = settingsSource.resolve(options.provider());
        if (settings == null || settings.apiKey() == null || settings.apiKey().isBlank()) {
            throw new IllegalArgumentException(
                    "missing API key for provider " + options.provider() + " in PaiCliConfig/.env");
        }
        EndpointProfile endpoint = officialEndpoint(options.provider(), options.model(), settings.baseUrl());
        String baseUrl = endpoint.workerBaseUrl();
        Map<String, String> toolchainFingerprints = BenchmarkProcessEnvironment.toolchainFingerprints();

        BenchmarkArtifactStore store = BenchmarkArtifactStore.create(options.output(), options.runId());
        validateOutputLocation(options.output(), suite.sourceDirectory());
        Instant createdInstant = clock.instant();
        String createdAt = createdInstant.toString();
        String runtimeDate = java.time.LocalDate.ofInstant(
                createdInstant, java.time.ZoneOffset.UTC).toString();
        BenchmarkProtocol.AgentLimits agentLimits =
                BenchmarkProtocol.AgentLimits.developmentDefaults();
        RunManifest manifest = new RunManifest(
                RUN_EVIDENCE_SCHEMA_VERSION,
                suite.version(),
                suite.name(),
                sha256(options.suite()),
                options.provider(),
                options.model(),
                true,
                "UNAVAILABLE",
                false,
                false,
                options.toolProfile(),
                options.workerIsolation(),
                options.workerIsolation() == WorkerIsolation.DOCKER_RELAY
                        ? options.dockerWorkerImage()
                        : null,
                options.candidateJarSha256(),
                options.runnerJarSha256(),
                options.runnerContentManifestSha256(),
                options.verifierIsolation(),
                options.verifierIsolation() == VerifierIsolation.DOCKER
                        ? options.dockerVerifierImage()
                        : null,
                options.repeats(),
                selectedCases.stream().map(CaseDefinition::id).toList(),
                !options.caseIds().isEmpty(),
                options.timeout().toSeconds(),
                BenchmarkWorkerProcess.TOKEN_BUDGET,
                BenchmarkWorkerProcess.HARD_MAX_ITERATIONS,
                BenchmarkWorkerProcess.STAGNATION_WINDOW,
                agentLimits.contextWindowCapTokens(),
                null,
                false,
                agentLimits.maxOutputTokensPerCall(),
                null,
                false,
                false,
                false,
                "UTC",
                runtimeDate,
                endpoint.fingerprint(),
                endpoint.official(),
                "PRIVATE_RAW_DO_NOT_PUBLISH",
                toolchainFingerprints,
                isolationLevel(options),
                false,
                residualIsolationNote(options),
                true,
                false,
                createdAt);
        store.writeManifest(manifest);

        String modelAlias = modelAlias(options.provider(), options.model());
        List<EpisodeOutcome> outcomes = new ArrayList<>();
        for (int repeat = 1; repeat <= options.repeats(); repeat++) {
            for (CaseDefinition definition : selectedCases) {
                BenchmarkArtifactStore.EpisodeArtifacts episode =
                        store.episode(definition.id(), modelAlias, repeat);
                Path verifierBundleDirectory = episode.createPrivateDirectory("verifier-bundle");
                BenchmarkVerifierBundle.Bundle verifierBundle = BenchmarkVerifierBundle.create(
                        suite.resolveVerifier(definition), verifierBundleDirectory);
                Path workspace = episode.createPrivateDirectory("workspace");
                Path isolatedHome = episode.createPrivateDirectory("home");
                BenchmarkFixtureCopier.copy(suite.resolveFixture(definition), workspace);

                EpisodeOutcome outcome = runEpisode(
                        options,
                        definition,
                        verifierBundle,
                        repeat,
                        episode,
                        workspace,
                        isolatedHome,
                        runtimeDate,
                        settings.apiKey(),
                        baseUrl,
                        workerExecutor,
                        verifier,
                        clock);
                outcomes.add(outcome);
            }
        }

        ModelUsageEvidence evidence = ModelUsageEvidence.from(
                outcomes,
                selectedCases.size() * options.repeats(),
                options.model(),
                agentLimits);
        RunAggregate aggregate = aggregate(
                suite,
                selectedCases,
                options.repeats(),
                !options.caseIds().isEmpty(),
                outcomes,
                evidence);
        store.writeManifest(manifest.withEvidence(evidence));
        store.writeAggregate(aggregate);
        return new RunReport(store.runDirectory(), aggregate, List.copyOf(outcomes));
    }

    private static EpisodeOutcome runEpisode(
            Options options,
            CaseDefinition definition,
            BenchmarkVerifierBundle.Bundle verifierBundle,
            int repeat,
            BenchmarkArtifactStore.EpisodeArtifacts episode,
            Path workspace,
            Path isolatedHome,
            String runtimeDate,
            String apiKey,
            String baseUrl,
            WorkerExecutor workerExecutor,
            VerifierExecutor verifier,
            Clock clock) throws IOException, InterruptedException {
        String startedAt = Instant.now(clock).toString();
        episode.writeRun(runArtifact(
                options, definition, repeat, "RUNNING", startedAt, null,
                null, null, null, ""));

        BenchmarkProtocol.WorkerRequest request = new BenchmarkProtocol.WorkerRequest(
                BenchmarkProtocol.VERSION,
                options.provider(),
                options.model(),
                baseUrl,
                apiKey,
                definition.mode().toJson(),
                options.toolProfile(),
                BenchmarkProtocol.AgentLimits.developmentDefaults(),
                runtimeDate,
                definition.prompt(),
                workspace.toString(),
                isolatedHome.toString(),
                episode.directory().toString());

        WorkerExecution worker;
        try {
            worker = workerExecutor.execute(request, workspace, isolatedHome, options.timeout());
        } catch (IOException e) {
            worker = WorkerExecution.startFailure(
                    "worker process could not be started: " + safeMessage(e));
        }

        if (worker.status() == WorkerStatus.SECURITY_ERROR
                || workerContainsCanary(worker, apiKey)
                || BenchmarkSecretCanary.containsInTree(episode.directory(), apiKey)) {
            String securityDetail = worker.status() == WorkerStatus.SECURITY_ERROR
                    && worker.diagnostic().contains("unsafe candidate workspace")
                    ? "unsafe candidate workspace entry detected"
                    : "provider credential canary detected";
            return securityFailure(
                    options, definition, repeat, episode, startedAt, securityDetail, clock);
        }

        BenchmarkFailureClassifier.Disposition workerDisposition =
                BenchmarkFailureClassifier.classifyWorker(worker);
        if (workerDisposition != BenchmarkFailureClassifier.Disposition.CONTINUE_TO_VERIFIER) {
            String detail = scrub(apiKey, workerFailureDetail(worker));
            episode.writeAnswer("");
            episode.writeVerifier(skippedVerifierArtifact(detail));
            boolean infra = workerDisposition == BenchmarkFailureClassifier.Disposition.INFRA_ERROR;
            episode.writeRun(runArtifact(
                    options, definition, repeat, infra ? "INFRA_ERROR" : "SCORED_FAIL", startedAt,
                    Instant.now(clock).toString(), worker, workerMetrics(worker), null, detail));
            if (BenchmarkSecretCanary.containsInTree(episode.directory(), apiKey)) {
                return securityFailure(options, definition, repeat, episode, startedAt, clock);
            }
            return infra
                    ? EpisodeOutcome.infra(definition.id(), repeat, detail, workerMetrics(worker))
                            .withFailureType(worker.response() == null ? worker.status().name()
                                    : scrub(apiKey, worker.response().errorType()))
                    : EpisodeOutcome.scored(
                            definition.id(), repeat, 0.0, false, detail, workerMetrics(worker))
                            .withFailureType(worker.response() == null ? worker.status().name()
                                    : scrub(apiKey, worker.response().errorType()));
        }

        String evidenceFailureType =
                BenchmarkProviderEvidenceGate.failureType(request, workerMetrics(worker));
        if (evidenceFailureType != null) {
            BenchmarkFailureClassifier.Disposition evidenceDisposition =
                    BenchmarkFailureClassifier.classifyResponse(
                            BenchmarkProtocol.WorkerResponse.failure(
                                    evidenceFailureType, "provider evidence gate failed",
                                    workerMetrics(worker)));
            boolean infra = evidenceDisposition == BenchmarkFailureClassifier.Disposition.INFRA_ERROR;
            String detail = evidenceFailureType
                    + ": provider identity, usage, request fingerprint, and context capacity were not proven";
            episode.writeAnswer("");
            episode.writeVerifier(skippedVerifierArtifact(detail));
            episode.writeRun(runArtifact(
                    options, definition, repeat, infra ? "INFRA_ERROR" : "SCORED_FAIL", startedAt,
                    Instant.now(clock).toString(), worker, workerMetrics(worker), null, detail));
            return infra
                    ? EpisodeOutcome.infra(
                            definition.id(), repeat, detail, workerMetrics(worker))
                            .withFailureType(evidenceFailureType)
                    : EpisodeOutcome.scored(
                            definition.id(), repeat, 0.0, false, detail,
                            workerMetrics(worker)).withFailureType(evidenceFailureType);
        }

        if (BenchmarkSecretCanary.contains(apiKey, worker.response().answer())) {
            return securityFailure(options, definition, repeat, episode, startedAt, clock);
        }
        String answer = scrub(apiKey, worker.response().answer());
        episode.writeAnswer(answer);

        BenchmarkVerifierWorkspaceSnapshot.Snapshot workspaceSnapshot;
        try {
            Path verifierWorkspace = episode.createPrivateDirectory("verifier-workspace");
            workspaceSnapshot = BenchmarkVerifierWorkspaceSnapshot.create(
                    workspace, verifierWorkspace);
        } catch (IOException | RuntimeException e) {
            return securityFailure(
                    options,
                    definition,
                    repeat,
                    episode,
                    startedAt,
                    "unsafe candidate workspace prevented verifier snapshot",
                    clock);
        }

        Path evidence;
        try {
            evidence = BenchmarkEvidenceEnvelope.write(
                    episode.directory(),
                    workspace,
                    isolatedHome,
                    definition.id(),
                    repeat,
                    definition.mode(),
                    options.toolProfile(),
                    answer,
                    workerMetrics(worker),
                    workspaceSnapshot,
                    verifierBundle,
                    worker.toolExecutions(),
                    apiKey);
        } catch (IOException | RuntimeException e) {
            String detail = scrub(apiKey, "verifier evidence preparation failed: " + safeMessage(e));
            episode.writeVerifier(skippedVerifierArtifact(detail));
            episode.writeRun(runArtifact(
                    options, definition, repeat, "SCORED_FAIL", startedAt,
                    Instant.now(clock).toString(), worker, workerMetrics(worker), null, detail));
            if (BenchmarkSecretCanary.containsInTree(episode.directory(), apiKey)) {
                return securityFailure(options, definition, repeat, episode, startedAt, clock);
            }
            return EpisodeOutcome.scored(
                    definition.id(), repeat, 0.0, false, detail, workerMetrics(worker));
        }

        BenchmarkVerifier.Result verification;
        try {
            verifierBundle.verifyUnchanged();
            verification = verifier.verify(
                    verifierBundle.invocation(), workspaceSnapshot.directory(), isolatedHome,
                    evidence, options.timeout());
        } catch (DockerBenchmarkVerifier.InfrastructureException e) {
            try {
                workspaceSnapshot.verifyUnchanged();
                verifierBundle.verifyUnchanged();
            } catch (IOException snapshotError) {
                return securityFailure(
                        options, definition, repeat, episode, startedAt,
                        "verifier input changed during Docker infrastructure failure", clock);
            }
            String detail = scrub(
                    apiKey, "Docker verifier infrastructure unavailable: " + safeMessage(e));
            episode.writeVerifier(skippedVerifierArtifact(detail));
            episode.writeRun(runArtifact(
                    options, definition, repeat, "INFRA_ERROR", startedAt,
                    Instant.now(clock).toString(), worker, workerMetrics(worker), null, detail));
            if (BenchmarkSecretCanary.containsInTree(episode.directory(), apiKey)) {
                return securityFailure(options, definition, repeat, episode, startedAt, clock);
            }
            return EpisodeOutcome.infra(definition.id(), repeat, detail, workerMetrics(worker));
        } catch (BenchmarkVerifierSandbox.SandboxUnavailableException e) {
            try {
                workspaceSnapshot.verifyUnchanged();
                verifierBundle.verifyUnchanged();
            } catch (IOException snapshotError) {
                return securityFailure(
                        options, definition, repeat, episode, startedAt,
                        "verifier input changed during sandbox failure", clock);
            }
            String detail = scrub(apiKey, "verifier sandbox unavailable: " + safeMessage(e));
            episode.writeVerifier(skippedVerifierArtifact(detail));
            episode.writeRun(runArtifact(
                    options, definition, repeat, "INFRA_ERROR", startedAt,
                    Instant.now(clock).toString(), worker, workerMetrics(worker), null, detail));
            if (BenchmarkSecretCanary.containsInTree(episode.directory(), apiKey)) {
                return securityFailure(options, definition, repeat, episode, startedAt, clock);
            }
            return EpisodeOutcome.infra(definition.id(), repeat, detail, workerMetrics(worker));
        } catch (IOException e) {
            try {
                workspaceSnapshot.verifyUnchanged();
                verifierBundle.verifyUnchanged();
            } catch (IOException snapshotError) {
                return securityFailure(
                        options, definition, repeat, episode, startedAt,
                        "verifier input changed during verifier failure", clock);
            }
            String detail = scrub(apiKey, "verifier execution failed: " + safeMessage(e));
            episode.writeVerifier(skippedVerifierArtifact(detail));
            episode.writeRun(runArtifact(
                    options, definition, repeat, "SCORED_FAIL", startedAt,
                    Instant.now(clock).toString(), worker, workerMetrics(worker), null, detail));
            if (BenchmarkSecretCanary.containsInTree(episode.directory(), apiKey)) {
                return securityFailure(options, definition, repeat, episode, startedAt, clock);
            }
            return EpisodeOutcome.scored(
                    definition.id(), repeat, 0.0, false, detail, workerMetrics(worker));
        }

        try {
            workspaceSnapshot.verifyUnchanged();
            verifierBundle.verifyUnchanged();
        } catch (IOException e) {
            return securityFailure(
                    options, definition, repeat, episode, startedAt,
                    "verifier input changed during verification", clock);
        }

        if (BenchmarkSecretCanary.contains(
                apiKey,
                verification.stdout(),
                verification.stderr(),
                String.join("\n", verification.arguments()))) {
            return securityFailure(options, definition, repeat, episode, startedAt, clock);
        }
        episode.writeVerifier(verification);

        BenchmarkFailureClassifier.Disposition verifierDisposition =
                BenchmarkFailureClassifier.classifyVerifier(verification.status());
        boolean strictSuccess = verifierDisposition == BenchmarkFailureClassifier.Disposition.PASSED;
        double score = strictSuccess ? 100.0 : 0.0;
        String status = strictSuccess ? "SCORED_PASS" : "SCORED_FAIL";
        String detail;
        if (strictSuccess) {
            detail = "verifier exited 0";
        } else if (verification.status() == BenchmarkVerifier.Status.TIMEOUT) {
            detail = "verifier timed out after " + options.timeout().toSeconds() + " seconds";
        } else {
            detail = "verifier exited " + verification.exitCode();
        }
        episode.writeRun(runArtifact(
                options, definition, repeat, status, startedAt,
                Instant.now(clock).toString(), worker, workerMetrics(worker), verification, detail));
        if (BenchmarkSecretCanary.containsInTree(episode.directory(), apiKey)) {
            return securityFailure(options, definition, repeat, episode, startedAt, clock);
        }
        return EpisodeOutcome.scored(
                definition.id(), repeat, score, strictSuccess, detail, workerMetrics(worker));
    }

    private static boolean workerContainsCanary(WorkerExecution worker, String apiKey) {
        if (worker == null) {
            return false;
        }
        BenchmarkProtocol.WorkerResponse response = worker.response();
        return BenchmarkSecretCanary.contains(
                apiKey,
                worker.diagnostic(),
                response == null ? null : response.answer(),
                response == null ? null : response.errorType(),
                response == null ? null : response.errorMessage());
    }

    private static EpisodeOutcome securityFailure(
            Options options,
            CaseDefinition definition,
            int repeat,
            BenchmarkArtifactStore.EpisodeArtifacts episode,
            String startedAt,
            Clock clock) {
        return securityFailure(
                options, definition, repeat, episode, startedAt,
                "provider credential canary detected", clock);
    }

    private static EpisodeOutcome securityFailure(
            Options options,
            CaseDefinition definition,
            int repeat,
            BenchmarkArtifactStore.EpisodeArtifacts episode,
            String startedAt,
            String securityDetail,
            Clock clock) {
        String detail = securityDetail == null || securityDetail.isBlank()
                ? "security hard gate violation"
                : securityDetail;
        try {
            episode.purgeContentsForSecurityFailure();
            detail += "; private episode data was purged";
        } catch (IOException e) {
            detail += "; private episode purge failed (" + e.getClass().getSimpleName()
                    + "); hard gate remains active";
        }

        List<String> evidenceWriteFailures = new ArrayList<>();
        try {
            episode.writeAnswer("");
        } catch (IOException e) {
            evidenceWriteFailures.add("answer:" + e.getClass().getSimpleName());
        }
        try {
            episode.writeVerifier(skippedVerifierArtifact(detail));
        } catch (IOException e) {
            evidenceWriteFailures.add("verifier:" + e.getClass().getSimpleName());
        }
        if (!evidenceWriteFailures.isEmpty()) {
            detail += "; episode evidence write failures=" + evidenceWriteFailures;
        }
        try {
            episode.writeRun(runArtifact(
                    options,
                    definition,
                    repeat,
                    "SECURITY_HARD_GATE",
                    startedAt,
                    Instant.now(clock).toString(),
                    null,
                    null,
                    null,
                    detail));
        } catch (IOException ignored) {
            // The returned scored hard-gate outcome is still persisted in aggregate.json.
        }
        return EpisodeOutcome.hardGate(definition.id(), repeat, detail);
    }

    private static void preflight(SuiteDefinition suite, List<CaseDefinition> cases) throws IOException {
        for (CaseDefinition definition : cases) {
            BenchmarkFixtureCopier.verifySafe(suite.resolveFixture(definition));
            if (definition.verifierType() != CaseDefinition.VerifierType.COMMAND) {
                throw new IllegalArgumentException(
                        "active runnable case requires verifierType=command: " + definition.id());
            }
            CaseDefinition.VerifierInvocation invocation = suite.resolveVerifier(definition);
            if (!Files.isDirectory(invocation.workingDirectory())) {
                throw new IOException("verifier working directory does not exist: "
                        + invocation.workingDirectory());
            }
        }
    }

    private static void validateOutputLocation(Path output, Path suiteDirectory) throws IOException {
        Path normalized = BenchmarkArtifactStore.resolveAgainstRealAncestor(output);
        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (Files.exists(project)) {
            project = project.toRealPath();
        }
        Path suiteRoot = suiteDirectory.toAbsolutePath().normalize().toRealPath();
        if (normalized.startsWith(project) || normalized.startsWith(suiteRoot)) {
            throw new IOException(
                    "private benchmark output must be outside the project and suite directories: "
                            + normalized);
        }
    }

    private static List<CaseDefinition> selectCases(SuiteDefinition suite, Set<String> caseIds) {
        List<CaseDefinition> active = suite.activeCases();
        if (caseIds.isEmpty()) {
            return active;
        }
        Map<String, CaseDefinition> byId = new LinkedHashMap<>();
        for (CaseDefinition definition : active) {
            byId.put(definition.id(), definition);
        }
        List<String> unknown = caseIds.stream().filter(id -> !byId.containsKey(id)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("unknown or inactive benchmark case(s): " + unknown);
        }
        return active.stream().filter(definition -> caseIds.contains(definition.id())).toList();
    }

    private static RunAggregate aggregate(SuiteDefinition suite,
                                          List<CaseDefinition> selectedCases,
                                          int repeats,
                                          boolean subset,
                                          List<EpisodeOutcome> outcomes,
                                          ModelUsageEvidence evidence) {
        SuiteDefinition selectedSuite = new SuiteDefinition(
                suite.version(), suite.name(), selectedCases, suite.sourceDirectory());
        List<RepeatAggregate> byRepeat = new ArrayList<>();
        int infraErrors = 0;
        boolean strict = true;
        List<Double> repeatScores = new ArrayList<>();
        for (int repeat = 1; repeat <= repeats; repeat++) {
            int currentRepeat = repeat;
            List<ScoreAggregator.CaseScore> scores = outcomes.stream()
                    .filter(outcome -> outcome.repeat() == currentRepeat)
                    .map(EpisodeOutcome::score)
                    .toList();
            ScoreAggregator.AggregateResult result = ScoreAggregator.aggregate(selectedSuite, scores);
            byRepeat.add(new RepeatAggregate(repeat, result));
            infraErrors += result.infraErrorCases();
            strict &= result.strictSuccess();
            if (result.weightedScore() != null) {
                repeatScores.add(result.weightedScore());
            }
        }
        int total = selectedCases.size() * repeats;
        Distribution distribution = Distribution.of(repeatScores);
        boolean hardGateViolation = outcomes.stream().anyMatch(EpisodeOutcome::hardGateViolation);
        List<String> hardGateEvidence = outcomes.stream()
                .filter(EpisodeOutcome::hardGateViolation)
                .map(EpisodeOutcome::detail)
                .toList();
        boolean completeProtocolResult = infraErrors == 0 && !subset && !hardGateViolation;
        boolean completeScoredCoverage = infraErrors == 0;
        boolean residualProcessIsolationGuaranteed = false;
        boolean publishable = completeProtocolResult && residualProcessIsolationGuaranteed;
        String reason;
        if (subset) {
            reason = "subset diagnostic run";
        } else if (hardGateViolation) {
            reason = "credential hard gate violation";
        } else if (infraErrors > 0) {
            reason = "coverage below 100 percent";
        } else if (!residualProcessIsolationGuaranteed) {
            reason = "dev pilot residual process isolation is not formally proven";
        } else {
            reason = "publishable";
        }
        return new RunAggregate(
                RUN_EVIDENCE_SCHEMA_VERSION,
                publishable ? distribution.mean() : null,
                publishable ? distribution.populationStddev() : null,
                publishable ? distribution.min() : null,
                publishable ? distribution.max() : null,
                completeScoredCoverage ? distribution.mean() : null,
                completeScoredCoverage ? distribution.populationStddev() : null,
                completeScoredCoverage ? distribution.min() : null,
                completeScoredCoverage ? distribution.max() : null,
                subset && completeScoredCoverage ? distribution.mean() : null,
                total,
                infraErrors,
                total == 0 ? 0.0 : (total - infraErrors) * 100.0 / total,
                evidence.requestedContextWindowCapTokens(),
                evidence.effectiveContextWindowCapTokens(),
                evidence.contextCapSatisfied(),
                evidence.maxOutputTokensPerCall(),
                evidence.maximumObservedTotalTokensPerCall(),
                evidence.outputPolicySatisfied(),
                evidence.requestFingerprintComplete(),
                evidence.fullProviderEvidenceSatisfied(),
                publishable && strict && total > 0,
                strict && total > 0,
                subset,
                hardGateViolation,
                hardGateEvidence,
                publishable,
                reason,
                List.copyOf(byRepeat));
    }

    private record Distribution(Double mean, Double populationStddev, Double min, Double max) {
        static Distribution of(List<Double> scores) {
            if (scores == null || scores.isEmpty()) {
                return new Distribution(null, null, null, null);
            }
            double mean = scores.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            double variance = scores.stream()
                    .mapToDouble(score -> {
                        double delta = score - mean;
                        return delta * delta;
                    })
                    .average()
                    .orElse(0.0);
            double min = scores.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
            double max = scores.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
            return new Distribution(mean, Math.sqrt(variance), min, max);
        }
    }

    private static Map<String, Object> runArtifact(
            Options options,
            CaseDefinition definition,
            int repeat,
            String status,
            String startedAt,
            String finishedAt,
            WorkerExecution worker,
            TracingLlmClient.Metrics metrics,
            BenchmarkVerifier.Result verifier,
            String detail) {
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("caseId", definition.id());
        artifact.put("title", definition.title());
        artifact.put("category", definition.category());
        artifact.put("level", definition.level());
        artifact.put("weight", definition.weight());
        artifact.put("mode", definition.mode());
        artifact.put("provider", options.provider());
        artifact.put("model", options.model());
        artifact.put("toolProfile", options.toolProfile());
        artifact.put("workerIsolation", options.workerIsolation());
        artifact.put("workerImageId", options.workerIsolation() == WorkerIsolation.DOCKER_RELAY
                ? options.dockerWorkerImage()
                : null);
        artifact.put("candidateJarSha256", options.candidateJar() == null
                ? null
                : options.candidateJarSha256());
        artifact.put("runnerJarSha256", options.runnerJarSha256());
        artifact.put("runnerContentManifestSha256", options.runnerContentManifestSha256());
        artifact.put("verifierIsolation", options.verifierIsolation());
        artifact.put("repeat", repeat);
        artifact.put("status", status);
        artifact.put("hardGateViolation", "SECURITY_HARD_GATE".equals(status));
        artifact.put("startedAt", startedAt);
        artifact.put("finishedAt", finishedAt);
        artifact.put("workerStatus", worker == null ? null : worker.status());
        artifact.put("workerExitCode", worker == null ? null : worker.exitCode());
        artifact.put("workerElapsedMillis", worker == null ? null : worker.elapsedMillis());
        artifact.put("toolExecutions", worker == null ? List.of() : worker.toolExecutions());
        artifact.put("llmMetrics", metrics);
        artifact.put("requestedContextWindowCapTokens",
                metrics == null
                        ? BenchmarkProtocol.AgentLimits.developmentDefaults()
                        .contextWindowCapTokens()
                        : metrics.requestedContextWindowCapTokens());
        artifact.put("effectiveContextWindowCapTokens",
                metrics == null ? null : metrics.effectiveContextWindowCapTokens());
        artifact.put("verifierStatus", verifier == null ? null : verifier.status());
        artifact.put("verifierElapsedMillis", verifier == null ? null : verifier.elapsedMillis());
        artifact.put("detail", detail == null ? "" : detail);
        return artifact;
    }

    private static Map<String, Object> skippedVerifierArtifact(String detail) {
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("status", "SKIPPED");
        artifact.put("detail", detail == null ? "" : detail);
        return artifact;
    }

    private static TracingLlmClient.Metrics workerMetrics(WorkerExecution worker) {
        return worker != null && worker.response() != null ? worker.response().metrics() : null;
    }

    private static String workerFailureDetail(WorkerExecution worker) {
        if (worker == null) {
            return "worker returned no execution result";
        }
        if (worker.status() == WorkerStatus.TIMEOUT) {
            return "worker timed out";
        }
        if (worker.response() != null && !worker.response().success()) {
            return worker.response().errorType() + ": " + worker.response().errorMessage();
        }
        if (worker.diagnostic() != null && !worker.diagnostic().isBlank()) {
            return worker.diagnostic();
        }
        return "worker process failed";
    }

    private static String scrub(String secret, String text) {
        String value = text == null ? "" : text;
        if (secret != null && !secret.isBlank()) {
            value = value.replace(secret, SecretRedactor.REDACTED);
        }
        return SecretRedactor.redact(value);
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String sha256ForOption(Path file, String label) {
        try {
            return sha256(file);
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to hash " + label, e);
        }
    }

    private static BenchmarkRunnerArtifactPolicy.Inspection inspectRunnerForOption(Path runnerJar) {
        try {
            return BenchmarkRunnerArtifactPolicy.inspect(runnerJar);
        } catch (IOException e) {
            throw new IllegalArgumentException("runner-jar failed trusted artifact policy", e);
        }
    }

    private static EndpointProfile officialEndpoint(String provider, String model, String configuredBaseUrl) {
        requireLockedModel(provider, model);
        String endpoint;
        String workerBaseUrl = null;
        switch (provider) {
            case "deepseek" -> endpoint = "https://api.deepseek.com/chat/completions";
            case "glm" -> endpoint = "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions";
            case "hunyuan" -> {
                String normalized = configuredBaseUrl == null || configuredBaseUrl.isBlank()
                        ? HUNYUAN_OFFICIAL_BASE_URL
                        : configuredBaseUrl.trim().replaceAll("/+$", "");
                if (!HUNYUAN_OFFICIAL_BASE_URL.equals(normalized)) {
                    throw new IllegalArgumentException(
                            "benchmark refuses non-official Hunyuan endpoint; expected "
                                    + HUNYUAN_OFFICIAL_BASE_URL);
                }
                endpoint = HUNYUAN_OFFICIAL_BASE_URL + "/chat/completions";
                workerBaseUrl = HUNYUAN_OFFICIAL_BASE_URL;
            }
            default -> throw new IllegalArgumentException("unsupported benchmark provider: " + provider);
        }
        return new EndpointProfile(workerBaseUrl, shortHash(endpoint), true);
    }

    private static void requireLockedModel(String provider, String model) {
        String expected = LOCKED_MODELS.get(provider);
        if (expected == null) {
            throw new IllegalArgumentException("unsupported benchmark provider: " + provider);
        }
        if (!expected.equals(model)) {
            throw new IllegalArgumentException(
                    "benchmark model is locked: provider " + provider + " requires model " + expected);
        }
    }

    private static String modelAlias(String provider, String model) {
        String normalized = (provider + "-" + model).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (normalized.isBlank()) {
            normalized = "model";
        }
        String hash = shortHash(provider + "\n" + model);
        int maxPrefix = 110;
        if (normalized.length() > maxPrefix) {
            normalized = normalized.substring(0, maxPrefix);
        }
        return normalized + "-" + hash;
    }

    private static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)), 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean containsHelp(String[] args) {
        if (args == null) {
            return false;
        }
        for (String argument : args) {
            if ("--help".equals(argument) || "-h".equals(argument)) {
                return true;
            }
        }
        return false;
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return SecretRedactor.redact(
                message == null || message.isBlank() ? error.getClass().getSimpleName() : message);
    }

    private static VerifierExecutor verifierFor(Options options) {
        return switch (options.verifierIsolation()) {
            case SEATBELT -> new BenchmarkVerifier();
            case DOCKER -> new DockerBenchmarkVerifier(
                    options.dockerExecutable(), options.dockerVerifierImage());
        };
    }

    static WorkerExecutor workerFor(Options options) throws IOException {
        return switch (options.workerIsolation()) {
            case HOST_DEV -> new BenchmarkWorkerProcess();
            case DOCKER_RELAY -> {
                DockerBenchmarkWorkerProcess worker = new DockerBenchmarkWorkerProcess(
                        options.dockerExecutable(), options.dockerWorkerImage(),
                        options.candidateJar(), options.runnerJar());
                if (!options.candidateJarSha256().equals(worker.candidateJarSha256())
                        || !options.runnerJarSha256().equals(worker.runnerJarSha256())
                        || !options.runnerContentManifestSha256().equals(
                                worker.runnerContentManifestSha256())) {
                    throw new IOException(
                            "candidate or trusted runner jar changed after option validation");
                }
                yield worker;
            }
        };
    }

    private static String isolationLevel(Options options) {
        String worker = options.workerIsolation() == WorkerIsolation.DOCKER_RELAY
                ? "networkless-readonly-docker-candidate-with-host-provider-relay"
                : "host-dev-worker-with-explicit-tool-profile";
        String verifier = options.verifierIsolation() == VerifierIsolation.DOCKER
                ? "networkless-readonly-docker-verifier"
                : "seatbelt-verifier";
        return "devPilot-" + worker + "+" + verifier;
    }

    private static String residualIsolationNote(Options options) {
        if (options.workerIsolation() == WorkerIsolation.DOCKER_RELAY) {
            return "Candidate runtime is a networkless, read-only Docker container with only its "
                    + "episode workspace writable; the credential and provider endpoint remain in "
                    + "the host relay. Container cleanup and the development dataset trust root are "
                    + "not yet formal publication guarantees";
        }
        if (options.verifierIsolation() == VerifierIsolation.DOCKER) {
            return "Worker JVM remains host-side and is limited to the explicit tool profile; "
                    + "the verifier is networkless/read-only Docker, but trusted verifier and "
                    + "candidate runtime are not yet split into separate containers";
        }
        return "25ms descendant enumeration is best effort; double-fork residual isolation is not proven";
    }

    static String usage() {
        return "Usage: java -cp <paicli.jar> " + BenchmarkCoordinatorMain.class.getName()
                + " --suite <suite.json> --provider <deepseek|hunyuan|glm> --model <model>"
                + " [--tool-profile <REASONING_ONLY|READ_ONLY|CODE_RAG|FILE_ONLY|LOCAL_COMMAND>]"
                + " [--worker-isolation <HOST_DEV|DOCKER_RELAY>]"
                + " [--docker-worker-image sha256:<64hex>]"
                + " [--candidate-jar /absolute/path/to/paicli.jar]"
                + " [--runner-jar /absolute/path/to/paicli-agentbench-runner.jar]"
                + " [--verifier-isolation <SEATBELT|DOCKER>]"
                + " [--docker-verifier-image sha256:<64hex>]"
                + " [--docker-executable /absolute/path/to/docker]"
                + " [--repeats N] [--output DIR] [--run-id ID] [--case ID[,ID...]]"
                + " [--timeout-seconds N]";
    }

    interface ProviderSettingsSource {
        ProviderSettings resolve(String provider);
    }

    @FunctionalInterface
    interface WorkerExecutor {
        default WorkerExecution executeWithInjection(BenchmarkProtocol.WorkerRequest request,
                Path workspace, Path home, Duration timeout, FormalInjectionBinding.Session session)
                throws IOException, InterruptedException {
            throw new IOException("worker does not support a frozen host F3 injection session");
        }
        default WorkerExecution executeWithCommand(BenchmarkProtocol.WorkerRequest request,
                Path workspace, Path home, Duration timeout, FormalCommandBinding.Session session)
                throws IOException, InterruptedException {
            throw new IOException("worker does not support a frozen host F2 command session");
        }
        default WorkerExecution executeWithBoundary(BenchmarkProtocol.WorkerRequest request,
                Path workspace, Path home, Duration timeout, FormalBoundaryBinding.Session session)
                throws IOException, InterruptedException {
            throw new IOException("worker does not support a frozen host F1 boundary");
        }
        default WorkerExecution executeWithPlan(BenchmarkProtocol.WorkerRequest request,
                Path workspace, Path home, Duration timeout, FormalPlanBinding.Session session)
                throws IOException, InterruptedException {
            throw new IOException("worker does not support a frozen host Plan session");
        }
        default WorkerExecution executeWithWeb(BenchmarkProtocol.WorkerRequest request,
                Path workspace, Path home, Duration timeout,
                com.paicli.eval.benchmark.relay.BenchmarkProviderRelay.MockWebEndpoint mock)
                throws IOException, InterruptedException {
            throw new IOException("worker does not support an isolated host Web mock");
        }
        default WorkerExecution executeWithMock(BenchmarkProtocol.WorkerRequest request,
                Path workspace, Path home, Duration timeout,
                com.paicli.eval.benchmark.relay.BenchmarkProviderRelay.MockMcpEndpoint mock)
                throws IOException, InterruptedException {
            throw new IOException("worker does not support an isolated host MCP mock");
        }
        WorkerExecution execute(BenchmarkProtocol.WorkerRequest request,
                                Path workspace,
                                Path isolatedHome,
                                Duration timeout) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface VerifierExecutor {
        BenchmarkVerifier.Result verify(CaseDefinition.VerifierInvocation invocation,
                                        Path workspace,
                                        Path isolatedHome,
                                        Path evidence,
                                        Duration timeout) throws IOException, InterruptedException;
    }

    record ProviderSettings(String apiKey, String baseUrl) {
        @Override
        public String toString() {
            return "ProviderSettings[apiKey=" + SecretRedactor.REDACTED
                    + ", baseUrl=" + (baseUrl == null || baseUrl.isBlank() ? "<default>" : "<configured>")
                    + "]";
        }
    }

    enum WorkerStatus {
        COMPLETED,
        TIMEOUT,
        PROCESS_ERROR,
        START_FAILURE,
        SECURITY_ERROR
    }

    enum WorkerIsolation {
        HOST_DEV,
        DOCKER_RELAY;

        static WorkerIsolation parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return HOST_DEV;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "worker-isolation must be HOST_DEV or DOCKER_RELAY");
            }
        }
    }

    enum VerifierIsolation {
        SEATBELT,
        DOCKER;

        static VerifierIsolation parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return SEATBELT;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "verifier-isolation must be SEATBELT or DOCKER");
            }
        }
    }

    record WorkerExecution(WorkerStatus status,
                           BenchmarkProtocol.WorkerResponse response,
                           Integer exitCode,
                           long elapsedMillis,
                           String diagnostic,
                           List<BenchmarkToolExecutionEvidence> toolExecutions) {
        WorkerExecution {
            if (status == null) {
                throw new IllegalArgumentException("worker status must not be null");
            }
            diagnostic = diagnostic == null ? "" : SecretRedactor.redact(diagnostic);
            toolExecutions = toolExecutions == null ? List.of() : List.copyOf(toolExecutions);
            if (toolExecutions.size() > BenchmarkToolExecutionEvidence.MAX_EVENTS) {
                throw new IllegalArgumentException("worker tool execution evidence exceeds its limit");
            }
            for (int index = 0; index < toolExecutions.size(); index++) {
                if (toolExecutions.get(index).ordinal() != index + 1) {
                    throw new IllegalArgumentException(
                            "worker tool execution evidence must use contiguous one-based ordinals");
                }
            }
        }

        static WorkerExecution completed(BenchmarkProtocol.WorkerResponse response,
                                         Integer exitCode,
                                         long elapsedMillis,
                                         String diagnostic) {
            return completed(response, exitCode, elapsedMillis, diagnostic, List.of());
        }

        static WorkerExecution completed(BenchmarkProtocol.WorkerResponse response,
                                         Integer exitCode,
                                         long elapsedMillis,
                                         String diagnostic,
                                         List<BenchmarkToolExecutionEvidence> toolExecutions) {
            return new WorkerExecution(
                    WorkerStatus.COMPLETED, response, exitCode, elapsedMillis,
                    diagnostic, toolExecutions);
        }

        static WorkerExecution timeout(long elapsedMillis, String diagnostic) {
            return new WorkerExecution(
                    WorkerStatus.TIMEOUT, null, null, elapsedMillis, diagnostic, List.of());
        }

        static WorkerExecution timeout(long elapsedMillis, String diagnostic, TracingLlmClient.Metrics metrics) {
            return new WorkerExecution(WorkerStatus.TIMEOUT,
                    BenchmarkProtocol.WorkerResponse.failure("CANDIDATE_TIMEOUT", "candidate exceeded deadline", metrics),
                    null, elapsedMillis, diagnostic, List.of());
        }

        static WorkerExecution processFailure(Integer exitCode, long elapsedMillis, String diagnostic) {
            return new WorkerExecution(
                    WorkerStatus.PROCESS_ERROR, null, exitCode, elapsedMillis, diagnostic, List.of());
        }

        static WorkerExecution processFailure(Integer exitCode, long elapsedMillis, String diagnostic, TracingLlmClient.Metrics metrics) {
            return new WorkerExecution(WorkerStatus.PROCESS_ERROR,
                    BenchmarkProtocol.WorkerResponse.failure("CANDIDATE_PROCESS_ERROR", "candidate process failed", metrics),
                    exitCode, elapsedMillis, diagnostic, List.of());
        }

        static WorkerExecution startFailure(String diagnostic) {
            return new WorkerExecution(
                    WorkerStatus.START_FAILURE, null, null, 0L, diagnostic, List.of());
        }

        static WorkerExecution securityFailure(long elapsedMillis) {
            return securityFailure(
                    elapsedMillis,
                    "provider credential canary detected in worker process output");
        }

        static WorkerExecution securityFailure(long elapsedMillis, String diagnostic) {
            return new WorkerExecution(
                    WorkerStatus.SECURITY_ERROR,
                    null,
                    null,
                    elapsedMillis,
                    diagnostic,
                    List.of());
        }
    }

    record EpisodeOutcome(String caseId,
                          int repeat,
                          ScoreAggregator.CaseScore score,
                          String detail,
                          boolean hardGateViolation,
                          TracingLlmClient.Metrics metrics,
                          String failureType) {
        EpisodeOutcome withFailureType(String type) {
            // Persist a bounded type, never arbitrary provider error text.
            String safeType = type != null && type.matches("[A-Za-z0-9_]{1,80}")
                    ? type : "UNKNOWN_FAILURE";
            return new EpisodeOutcome(caseId, repeat, score, detail, hardGateViolation,
                    metrics, safeType);
        }

        static EpisodeOutcome scored(String caseId,
                                     int repeat,
                                     double score,
                                     boolean strictSuccess,
                                     String detail) {
            return scored(caseId, repeat, score, strictSuccess, detail, null);
        }

        static EpisodeOutcome scored(String caseId,
                                     int repeat,
                                     double score,
                                     boolean strictSuccess,
                                     String detail,
                                     TracingLlmClient.Metrics metrics) {
            return new EpisodeOutcome(
                    caseId, repeat,
                    new ScoreAggregator.CaseScore(
                            caseId, score, strictSuccess, ScoreAggregator.ResultStatus.SCORED, detail),
                    detail,
                    false,
                    metrics,
                    "");
        }

        static EpisodeOutcome infra(String caseId, int repeat, String detail) {
            return infra(caseId, repeat, detail, null);
        }

        static EpisodeOutcome infra(String caseId,
                                    int repeat,
                                    String detail,
                                    TracingLlmClient.Metrics metrics) {
            return new EpisodeOutcome(
                    caseId,
                    repeat,
                    ScoreAggregator.CaseScore.infraError(caseId, detail),
                    detail,
                    false,
                    metrics,
                    "");
        }

        static EpisodeOutcome hardGate(String caseId, int repeat, String detail) {
            return new EpisodeOutcome(
                    caseId,
                    repeat,
                    new ScoreAggregator.CaseScore(
                            caseId, 0.0, false, ScoreAggregator.ResultStatus.SCORED, detail),
                    detail,
                    true,
                    null,
                    "SECURITY_ERROR");
        }
    }

    private record ModelUsageEvidence(String resolvedModel,
                                      boolean matchesRequestedModel,
                                      boolean usageComplete,
                                      int requestedContextWindowCapTokens,
                                      Integer effectiveContextWindowCapTokens,
                                      boolean contextCapSatisfied,
                                      int maxOutputTokensPerCall,
                                      Long maximumObservedTotalTokensPerCall,
                                      boolean outputPolicySatisfied,
                                      boolean requestFingerprintComplete,
                                      boolean fullProviderEvidenceSatisfied) {
        static ModelUsageEvidence from(List<EpisodeOutcome> outcomes,
                                       int expectedEpisodes,
                                       String requestedModel,
                                       BenchmarkProtocol.AgentLimits limits) {
            Objects.requireNonNull(limits, "limits");
            int requestedContextWindowCapTokens = limits.contextWindowCapTokens();
            int maxOutputTokensPerCall = limits.maxOutputTokensPerCall();
            boolean completeOutcomeSet = outcomes != null
                    && expectedEpisodes > 0
                    && outcomes.size() == expectedEpisodes;
            if (!completeOutcomeSet) {
                return unavailable(requestedContextWindowCapTokens, maxOutputTokensPerCall);
            }
            String resolved = null;
            Set<Integer> effectiveContextCaps = new LinkedHashSet<>();
            boolean identityComplete = true;
            boolean usageComplete = true;
            boolean contextCapSatisfied = true;
            boolean outputPolicySatisfied = true;
            boolean requestFingerprintComplete = true;
            boolean fullProviderEvidenceSatisfied = true;
            boolean observedMetrics = false;
            long maximumObservedTotalTokensPerCall = 0;
            for (EpisodeOutcome outcome : outcomes) {
                // A capped client may reject a provider response before metrics can contain
                // its usage. Keep the sticky typed verdict, even after later valid calls.
                if (BenchmarkProviderEvidenceGate.isEvaluationInvalidFailureType(
                        outcome.failureType())) {
                    fullProviderEvidenceSatisfied = false;
                }
                TracingLlmClient.Metrics metrics = outcome.metrics();
                if (metrics == null) {
                    identityComplete = false;
                    usageComplete = false;
                    contextCapSatisfied = false;
                    outputPolicySatisfied = false;
                    requestFingerprintComplete = false;
                    fullProviderEvidenceSatisfied = false;
                    continue;
                }
                observedMetrics = true;
                maximumObservedTotalTokensPerCall = Math.max(
                        maximumObservedTotalTokensPerCall,
                        metrics.maxObservedTotalTokensPerCall());
                if (metrics.effectiveContextWindowCapTokens() > 0) {
                    effectiveContextCaps.add(metrics.effectiveContextWindowCapTokens());
                }
                if (!metrics.contextCapSatisfied()
                        || metrics.calls() <= 0
                        || metrics.requestedContextWindowCapTokens()
                        != requestedContextWindowCapTokens
                        || metrics.effectiveContextWindowCapTokens()
                        != requestedContextWindowCapTokens) {
                    contextCapSatisfied = false;
                }
                if (metrics.maxOutputTokensPerCall() != maxOutputTokensPerCall) {
                    outputPolicySatisfied = false;
                }
                if (metrics.calls() <= 0 || !metrics.requestFingerprintComplete()) {
                    requestFingerprintComplete = false;
                }
                if (BenchmarkProviderEvidenceGate.invalidEvaluationFailureType(
                        requestedModel, limits, metrics) != null) {
                    fullProviderEvidenceSatisfied = false;
                }
                if (metrics.successfulCalls() <= 0) {
                    identityComplete = false;
                    usageComplete = false;
                    continue;
                }
                if (metrics.successfulCalls() != metrics.calls()) {
                    identityComplete = false;
                    usageComplete = false;
                }
                if (!metrics.resolvedModelConsistent()
                        || metrics.resolvedModel() == null
                        || metrics.resolvedModel().isBlank()) {
                    identityComplete = false;
                } else {
                    String current = metrics.resolvedModel().trim();
                    if (resolved == null) {
                        resolved = current;
                    } else if (!resolved.equals(current)) {
                        identityComplete = false;
                    }
                }
                if (!metrics.usageComplete()) {
                    usageComplete = false;
                }
            }
            Integer effectiveContextWindowCapTokens = effectiveContextCaps.size() == 1
                    ? effectiveContextCaps.iterator().next()
                    : null;
            if (effectiveContextCaps.size() != 1) {
                contextCapSatisfied = false;
            }
            Long maximumObserved = observedMetrics
                    ? maximumObservedTotalTokensPerCall
                    : null;
            if (!identityComplete || resolved == null) {
                return new ModelUsageEvidence(
                        "UNAVAILABLE",
                        false,
                        usageComplete,
                        requestedContextWindowCapTokens,
                        effectiveContextWindowCapTokens,
                        contextCapSatisfied,
                        maxOutputTokensPerCall,
                        maximumObserved,
                        outputPolicySatisfied,
                        requestFingerprintComplete,
                        fullProviderEvidenceSatisfied);
            }
            boolean matches = resolved.equals(requestedModel);
            return new ModelUsageEvidence(
                    resolved,
                    matches,
                    usageComplete,
                    requestedContextWindowCapTokens,
                    effectiveContextWindowCapTokens,
                    contextCapSatisfied,
                    maxOutputTokensPerCall,
                    maximumObserved,
                    outputPolicySatisfied,
                    requestFingerprintComplete,
                    fullProviderEvidenceSatisfied);
        }

        private static ModelUsageEvidence unavailable(
                int requestedContextWindowCapTokens,
                int maxOutputTokensPerCall) {
            return new ModelUsageEvidence(
                    "UNAVAILABLE",
                    false,
                    false,
                    requestedContextWindowCapTokens,
                    null,
                    false,
                    maxOutputTokensPerCall,
                    null,
                    false,
                    false,
                    false);
        }
    }

    record RunManifest(int manifestVersion,
                       String suiteVersion,
                       String suiteName,
                       String suiteSha256,
                       String provider,
                       String model,
                       boolean requestedModelLocked,
                       String serverResolvedModel,
                       boolean serverResolvedModelMatchesRequest,
                       boolean usageCompletenessGate,
                       BenchmarkToolProfile toolProfile,
                       WorkerIsolation workerIsolation,
                       String workerImageId,
                       String candidateJarSha256,
                       String runnerJarSha256,
                       String runnerContentManifestSha256,
                       VerifierIsolation verifierIsolation,
                       String verifierImageId,
                       int repeats,
                       List<String> selectedCases,
                       boolean subset,
                       long timeoutSeconds,
                       int tokenBudget,
                       int hardMaxIterations,
                       int stagnationWindow,
                       int requestedContextWindowCapTokens,
                       Integer effectiveContextWindowCapTokens,
                       boolean contextCapSatisfiedGate,
                       int maxOutputTokensPerCall,
                       Long maximumObservedTotalTokensPerCall,
                       boolean outputPolicySatisfiedGate,
                       boolean requestFingerprintCompleteGate,
                       boolean fullProviderEvidenceGate,
                       String timezone,
                       String runtimeDate,
                       String endpointFingerprint,
                       boolean officialEndpoint,
                       String artifactVisibility,
                       Map<String, String> toolchainFingerprints,
                       String isolationLevel,
                       boolean residualProcessIsolationGuaranteed,
                       String residualProcessIsolationNote,
                       boolean verifierWorkspaceReadOnly,
                       boolean publishable,
                       String createdAt) {
        RunManifest withEvidence(ModelUsageEvidence evidence) {
            return new RunManifest(
                    manifestVersion,
                    suiteVersion,
                    suiteName,
                    suiteSha256,
                    provider,
                    model,
                    requestedModelLocked,
                    evidence.resolvedModel(),
                    evidence.matchesRequestedModel(),
                    evidence.usageComplete(),
                    toolProfile,
                    workerIsolation,
                    workerImageId,
                    candidateJarSha256,
                    runnerJarSha256,
                    runnerContentManifestSha256,
                    verifierIsolation,
                    verifierImageId,
                    repeats,
                    selectedCases,
                    subset,
                    timeoutSeconds,
                    tokenBudget,
                    hardMaxIterations,
                    stagnationWindow,
                    requestedContextWindowCapTokens,
                    evidence.effectiveContextWindowCapTokens(),
                    evidence.contextCapSatisfied(),
                    maxOutputTokensPerCall,
                    evidence.maximumObservedTotalTokensPerCall(),
                    evidence.outputPolicySatisfied(),
                    evidence.requestFingerprintComplete(),
                    evidence.fullProviderEvidenceSatisfied(),
                    timezone,
                    runtimeDate,
                    endpointFingerprint,
                    officialEndpoint,
                    artifactVisibility,
                    toolchainFingerprints,
                    isolationLevel,
                    residualProcessIsolationGuaranteed,
                    residualProcessIsolationNote,
                    verifierWorkspaceReadOnly,
                    publishable,
                    createdAt);
        }
    }

    private record EndpointProfile(String workerBaseUrl, String fingerprint, boolean official) {
    }

    record RepeatAggregate(int repeat, ScoreAggregator.AggregateResult result) {
    }

    record RunAggregate(int schemaVersion,
                        Double meanWeightedScore,
                        Double populationStddev,
                        Double minWeightedScore,
                        Double maxWeightedScore,
                        Double diagnosticMeanWeightedScore,
                        Double diagnosticPopulationStddev,
                        Double diagnosticMinWeightedScore,
                        Double diagnosticMaxWeightedScore,
                        Double subsetDiagnosticScore,
                        int totalEpisodes,
                        int infraErrorEpisodes,
                        double scoredEpisodeCoveragePercent,
                        int requestedContextWindowCapTokens,
                        Integer effectiveContextWindowCapTokens,
                        boolean contextCapSatisfied,
                        int maxOutputTokensPerCall,
                        Long maximumObservedTotalTokensPerCall,
                        boolean outputPolicySatisfiedGate,
                        boolean requestFingerprintCompleteGate,
                        boolean fullProviderEvidenceGate,
                        boolean strictSuccess,
                        boolean diagnosticStrictSuccess,
                        boolean subset,
                        boolean hardGateViolation,
                        List<String> hardGateEvidence,
                        boolean publishable,
                        String publishabilityReason,
                        List<RepeatAggregate> repeats) {
    }

    record RunReport(Path runDirectory,
                     RunAggregate aggregate,
                     List<EpisodeOutcome> outcomes) {
    }

    record Options(Path suite,
                   String provider,
                   String model,
                   int repeats,
                   Path output,
                   String runId,
                   Set<String> caseIds,
                   BenchmarkToolProfile toolProfile,
                   WorkerIsolation workerIsolation,
                   String dockerWorkerImage,
                   Path candidateJar,
                   Path runnerJar,
                   String candidateJarSha256,
                   String runnerJarSha256,
                   String runnerContentManifestSha256,
                   VerifierIsolation verifierIsolation,
                   String dockerVerifierImage,
                   Path dockerExecutable,
                   Duration timeout) {
        Options(Path suite,
                String provider,
                String model,
                int repeats,
                Path output,
                String runId,
                Set<String> caseIds,
                Duration timeout) {
            this(suite, provider, model, repeats, output, runId, caseIds,
                    BenchmarkToolProfile.FILE_ONLY, WorkerIsolation.HOST_DEV,
                    null, null, null, null, null, null,
                    VerifierIsolation.SEATBELT,
                    null, DEFAULT_DOCKER_EXECUTABLE, timeout);
        }

        Options(Path suite,
                String provider,
                String model,
                int repeats,
                Path output,
                String runId,
                Set<String> caseIds,
                BenchmarkToolProfile toolProfile,
                Duration timeout) {
            this(suite, provider, model, repeats, output, runId, caseIds,
                    toolProfile, WorkerIsolation.HOST_DEV,
                    null, null, null, null, null, null,
                    VerifierIsolation.SEATBELT,
                    null, DEFAULT_DOCKER_EXECUTABLE, timeout);
        }

        Options(Path suite,
                String provider,
                String model,
                int repeats,
                Path output,
                String runId,
                Set<String> caseIds,
                BenchmarkToolProfile toolProfile,
                WorkerIsolation workerIsolation,
                String dockerWorkerImage,
                Path candidateJar,
                VerifierIsolation verifierIsolation,
                String dockerVerifierImage,
                Path dockerExecutable,
                Duration timeout) {
            this(suite, provider, model, repeats, output, runId, caseIds,
                    toolProfile, workerIsolation, dockerWorkerImage, candidateJar,
                    null, null, null, null, verifierIsolation, dockerVerifierImage,
                    dockerExecutable, timeout);
        }

        Options(Path suite,
                String provider,
                String model,
                int repeats,
                Path output,
                String runId,
                Set<String> caseIds,
                BenchmarkToolProfile toolProfile,
                WorkerIsolation workerIsolation,
                String dockerWorkerImage,
                Path candidateJar,
                Path runnerJar,
                VerifierIsolation verifierIsolation,
                String dockerVerifierImage,
                Path dockerExecutable,
                Duration timeout) {
            this(suite, provider, model, repeats, output, runId, caseIds,
                    toolProfile, workerIsolation, dockerWorkerImage, candidateJar,
                    runnerJar, null, null, null, verifierIsolation, dockerVerifierImage,
                    dockerExecutable, timeout);
        }

        Options {
            suite = suite.toAbsolutePath().normalize();
            provider = BenchmarkProtocol.normalizeProvider(provider);
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("model must not be blank");
            }
            model = model.trim();
            requireLockedModel(provider, model);
            if (repeats <= 0 || repeats > 100) {
                throw new IllegalArgumentException("repeats must be between 1 and 100");
            }
            output = output.toAbsolutePath().normalize();
            CaseDefinition.requireSafeIdentifier(runId, "run id");
            caseIds = Set.copyOf(caseIds);
            toolProfile = toolProfile == null ? BenchmarkToolProfile.FILE_ONLY : toolProfile;
            if (toolProfile == BenchmarkToolProfile.MOCK_WEB)
                throw new IllegalArgumentException("dev Coordinator has no frozen Web binding; use formal admission or explicit diagnostic controls");
            if (toolProfile == BenchmarkToolProfile.MOCK_MCP_FILE_ONLY)
                throw new IllegalArgumentException("dev Coordinator has no frozen F3 binding; use explicit diagnostic controls");
            workerIsolation = workerIsolation == null ? WorkerIsolation.HOST_DEV : workerIsolation;
            verifierIsolation = verifierIsolation == null
                    ? VerifierIsolation.SEATBELT
                    : verifierIsolation;
            dockerExecutable = dockerExecutable == null ? DEFAULT_DOCKER_EXECUTABLE : dockerExecutable;
            if (!dockerExecutable.isAbsolute()) {
                throw new IllegalArgumentException("docker executable must be absolute");
            }
            dockerExecutable = dockerExecutable.normalize();
            if (workerIsolation == WorkerIsolation.DOCKER_RELAY) {
                if (dockerWorkerImage == null || !FROZEN_IMAGE_ID.matcher(dockerWorkerImage).matches()) {
                    throw new IllegalArgumentException(
                            "docker-worker-image must be a frozen sha256 image ID for DOCKER_RELAY");
                }
                if (candidateJar == null || !candidateJar.isAbsolute()) {
                    throw new IllegalArgumentException(
                            "candidate-jar must be an absolute regular non-symlink file for DOCKER_RELAY");
                }
                candidateJar = candidateJar.normalize();
                if (Files.isSymbolicLink(candidateJar)
                        || !Files.isRegularFile(candidateJar, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalArgumentException(
                            "candidate-jar must be an absolute regular non-symlink file for DOCKER_RELAY");
                }
                if (runnerJar == null || !runnerJar.isAbsolute()) {
                    throw new IllegalArgumentException(
                            "runner-jar must be an absolute regular non-symlink file for DOCKER_RELAY");
                }
                runnerJar = runnerJar.normalize();
                if (Files.isSymbolicLink(runnerJar)
                        || !Files.isRegularFile(runnerJar, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalArgumentException(
                            "runner-jar must be an absolute regular non-symlink file for DOCKER_RELAY");
                }
                candidateJarSha256 = sha256ForOption(candidateJar, "candidate-jar");
                String runnerShaBeforeInspection = sha256ForOption(runnerJar, "runner-jar");
                BenchmarkRunnerArtifactPolicy.Inspection runnerInspection =
                        inspectRunnerForOption(runnerJar);
                runnerJarSha256 = sha256ForOption(runnerJar, "runner-jar");
                if (!runnerShaBeforeInspection.equals(runnerJarSha256)) {
                    throw new IllegalArgumentException(
                            "runner-jar changed during trusted artifact inspection");
                }
                runnerContentManifestSha256 = runnerInspection.inventorySha256();
            } else {
                if (dockerWorkerImage != null && !dockerWorkerImage.isBlank()) {
                    throw new IllegalArgumentException(
                            "docker-worker-image requires worker-isolation=DOCKER_RELAY");
                }
                if (candidateJar != null) {
                    throw new IllegalArgumentException(
                            "candidate-jar requires worker-isolation=DOCKER_RELAY");
                }
                if (runnerJar != null) {
                    throw new IllegalArgumentException(
                            "runner-jar requires worker-isolation=DOCKER_RELAY");
                }
                candidateJarSha256 = null;
                runnerJarSha256 = null;
                runnerContentManifestSha256 = null;
            }
            if (verifierIsolation == VerifierIsolation.DOCKER) {
                new DockerBenchmarkVerifier(dockerExecutable, dockerVerifierImage);
            } else if (dockerVerifierImage != null && !dockerVerifierImage.isBlank()) {
                throw new IllegalArgumentException(
                        "docker-verifier-image requires verifier-isolation=DOCKER");
            }
            if (timeout == null || timeout.isZero() || timeout.isNegative()
                    || timeout.compareTo(Duration.ofDays(1)) > 0) {
                throw new IllegalArgumentException("timeout-seconds must be between 1 and 86400");
            }
        }

        static Options parse(String[] args, Clock clock) {
            Map<String, String> values = new LinkedHashMap<>();
            LinkedHashSet<String> cases = new LinkedHashSet<>();
            if (args == null) {
                args = new String[0];
            }
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if (argument == null || !argument.startsWith("--")) {
                    throw new IllegalArgumentException("unexpected argument: " + argument);
                }
                int equals = argument.indexOf('=');
                String name = equals > 0 ? argument.substring(0, equals) : argument;
                String value;
                if (equals > 0) {
                    value = argument.substring(equals + 1);
                } else {
                    if (++index >= args.length || args[index].startsWith("--")) {
                        throw new IllegalArgumentException("missing value for " + name);
                    }
                    value = args[index];
                }
                if (value.isBlank()) {
                    throw new IllegalArgumentException("blank value for " + name);
                }
                if ("--case".equals(name)) {
                    for (String id : value.split(",")) {
                        String trimmed = id.trim();
                        CaseDefinition.requireSafeIdentifier(trimmed, "case id");
                        cases.add(trimmed);
                    }
                } else {
                    if (!Set.of("--suite", "--provider", "--model", "--repeats", "--output",
                            "--tool-profile",
                            "--worker-isolation", "--docker-worker-image", "--candidate-jar",
                            "--runner-jar",
                            "--verifier-isolation", "--docker-verifier-image", "--docker-executable",
                            "--run-id", "--timeout-seconds").contains(name)) {
                        throw new IllegalArgumentException("unknown option: " + name);
                    }
                    if (values.put(name, value) != null) {
                        throw new IllegalArgumentException("duplicate option: " + name);
                    }
                }
            }

            String runId = values.getOrDefault("--run-id", generatedRunId(clock));
            return new Options(
                    Path.of(required(values, "--suite")),
                    required(values, "--provider"),
                    required(values, "--model"),
                    positiveInt(values.getOrDefault("--repeats", String.valueOf(DEFAULT_REPEATS)), "--repeats"),
                    values.containsKey("--output")
                            ? Path.of(values.get("--output"))
                            : defaultOutputPath(),
                    runId,
                    cases,
                    BenchmarkToolProfile.parse(values.get("--tool-profile")),
                    WorkerIsolation.parse(values.get("--worker-isolation")),
                    values.get("--docker-worker-image"),
                    values.containsKey("--candidate-jar")
                            ? Path.of(values.get("--candidate-jar"))
                            : null,
                    values.containsKey("--runner-jar")
                            ? Path.of(values.get("--runner-jar"))
                            : null,
                    null,
                    null,
                    null,
                    VerifierIsolation.parse(values.get("--verifier-isolation")),
                    values.get("--docker-verifier-image"),
                    values.containsKey("--docker-executable")
                            ? Path.of(values.get("--docker-executable"))
                            : DEFAULT_DOCKER_EXECUTABLE,
                    Duration.ofSeconds(positiveLong(
                            values.getOrDefault("--timeout-seconds", String.valueOf(DEFAULT_TIMEOUT_SECONDS)),
                            "--timeout-seconds")));
        }

        private static String required(Map<String, String> values, String key) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("missing required option " + key);
            }
            return value;
        }

        private static int positiveInt(String raw, String label) {
            try {
                int value = Integer.parseInt(raw);
                if (value <= 0) {
                    throw new NumberFormatException();
                }
                return value;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(label + " must be a positive integer");
            }
        }

        private static long positiveLong(String raw, String label) {
            try {
                long value = Long.parseLong(raw);
                if (value <= 0) {
                    throw new NumberFormatException();
                }
                return value;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(label + " must be a positive integer");
            }
        }

        private static String generatedRunId(Clock clock) {
            String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                    .withZone(ZoneOffset.UTC)
                    .format(Instant.now(clock));
            return "run-" + timestamp + "-" + UUID.randomUUID().toString().substring(0, 8);
        }

        private static Path defaultOutputPath() {
            return Path.of(System.getProperty("user.home"), ".paicli-benchmark-results");
        }
    }

    private static final class PaiCliProviderSettingsSource implements ProviderSettingsSource {
        @Override
        public ProviderSettings resolve(String provider) {
            PaiCliConfig config = PaiCliConfig.load();
            return new ProviderSettings(config.getApiKey(provider), config.getBaseUrl(provider));
        }
    }
}
