package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.eval.benchmark.formal.AttemptKey;
import com.paicli.eval.benchmark.formal.FormalEpisodeOutcome;
import com.paicli.eval.benchmark.formal.FormalExecutionPlan;
import com.paicli.eval.benchmark.formal.FormalTestAdmission;
import com.paicli.eval.benchmark.scoring.VerifierScoringReport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FormalBatchRunnerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-04T08:00:00Z"), ZoneOffset.UTC);
    @TempDir Path temp;

    @AfterEach
    void makeOnlyTestOwnedArtifactsRemovable() throws Exception {
        try (var walk = Files.walk(temp)) {
            for (Path p : walk.toList()) if (!Files.isSymbolicLink(p))
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(
                        Files.isDirectory(p) ? "rwx------" : "rw-------"));
        }
    }

    @Test
    void runsAll168TwoModelEpisodesInOrderAndRetainsLowScoresWithoutHy4() throws Exception {
        var ready = readyTwoModels("two-full");
        AtomicInteger workers = new AtomicInteger(), verifiers = new AtomicInteger();
        Path output = temp.toRealPath().resolve("two-output");
        var report = FormalBatchRunner.run(ready, output, "two-full-run",
                (request, workspace, home, timeout) -> {
                    int index = workers.getAndIncrement();
                    var expected = ready.plan().episodes().get(index);
                    assertEquals(index < 84 ? "deepseek" : "glm", request.provider());
                    assertEquals(index < 84 ? "deepseek-v4-flash" : "glm-5.3-flash", request.model());
                    assertEquals(index % 84 / 28 + 1, expected.repeat());
                    assertEquals(ready.plan().cases().get(index % 28).id(), expected.caseId());
                    assertEquals(expected.casePlan().prompt(), request.prompt());
                    assertEquals(expected.casePlan().mode().toJson(), request.mode());
                    assertFalse(Files.exists(workspace.getParent().resolve("trusted-verifier")));
                    if (index == 0) {
                        var progress = JSON.readTree(output.resolve("two-full-run/aggregate.json").toFile());
                        assertEquals(168, progress.path("plannedEpisodes").asInt());
                        assertEquals(0, progress.path("attemptedEpisodes").asInt());
                        assertTwoModelManifest(output.resolve("two-full-run/manifest.json"), "RUNNING");
                    }
                    return successfulWorker(request, index == 2);
                }, (invocation, workspace, home, evidence, timeout) -> {
                    verifiers.incrementAndGet();
                    int index = workers.get() - 1;
                    var expected = ready.plan().episodes().get(index);
                    assertEquals(expected.caseId(), JSON.readTree(evidence.toFile()).path("caseId").asText());
                    assertTrue(Files.exists(invocation.workingDirectory().resolve(expected.casePlan().verifier().entryPath())));
                    return verification(expected.casePlan(), index == 0 ? 50 : 100, index == 1, 0);
                }, CLOCK);
        assertEquals(168, workers.get()); assertEquals(167, verifiers.get());
        assertEquals(168, report.summary().plannedEpisodes()); assertEquals(168, report.summary().attemptedEpisodes());
        assertEquals(168, report.summary().scoredEpisodes()); assertTrue(report.summary().completeValidCoverage());
        assertEquals("EXECUTED_NOT_RELEASED", report.summary().status());
        assertFalse(report.summary().publishable()); assertNull(report.summary().formalScores());
        assertEquals(Set.of("deepseek", "glm"), report.summary().observedMeanWeightedScores().keySet());
        double deepseek = 100.0 - ready.plan().cases().get(0).weight() / 6.0
                - ready.plan().cases().get(1).weight() / 3.0 - ready.plan().cases().get(2).weight() / 3.0;
        assertEquals(deepseek, report.summary().observedMeanWeightedScores().get("deepseek"), 0.0001);
        assertEquals(100.0, report.summary().observedMeanWeightedScores().get("glm"), 0.0001);
        assertEquals(List.of(50, 0, 0), report.summary().episodes().subList(0, 3).stream()
                .map(e -> ((FormalEpisodeOutcome.Scored)e.outcome()).score()).toList());
        assertTrue(((FormalEpisodeOutcome.Scored)report.summary().episodes().get(1).outcome()).result().hardGate());
        for (int i = 0; i < 168; i++)
            assertEquals(AttemptKey.from(ready.plan(), ready.plan().episodes().get(i), 1), report.summary().episodes().get(i).key());
        assertTwoModelManifest(report.directory().resolve("manifest.json"), "EXECUTED_NOT_RELEASED");
        var aggregate = JSON.readTree(report.directory().resolve("aggregate.json").toFile());
        assertEquals(168, aggregate.path("plannedEpisodes").asInt());
        assertEquals(168, aggregate.path("attemptedEpisodes").asInt());
        assertEquals(2, aggregate.path("observedMeanWeightedScores").size());
        assertFalse(aggregate.path("observedMeanWeightedScores").has("hunyuan"));
        assertTrue(aggregate.path("formalScores").isNull()); assertFalse(aggregate.path("publishable").asBoolean());
    }

    @Test
    void twoModelEvidenceInvalidStopsAfterPriorValidScoresAndClearsBatchTotals() throws Exception {
        var ready = readyTwoModels("two-invalid");
        AtomicInteger workers = new AtomicInteger(), verifiers = new AtomicInteger();
        var report = FormalBatchRunner.run(ready, temp.toRealPath().resolve("two-invalid-output"), "two-invalid-run",
                (request, workspace, home, timeout) -> {
                    int index = workers.getAndIncrement();
                    assertEquals("deepseek", request.provider(), "invalid batch must not continue to GLM");
                    if (index == 3) return BenchmarkCoordinatorMain.WorkerExecution.completed(
                            BenchmarkProtocol.WorkerResponse.failure("USAGE_UNPROVEN", "synthetic missing usage",
                                    successfulWorker(request, false).response().metrics()), 0, 1, "");
                    return successfulWorker(request, false);
                }, (invocation, workspace, home, evidence, timeout) -> {
                    int index = verifiers.getAndIncrement();
                    assertTrue(index < 3, "the invalid episode must never reach a verifier");
                    String id = JSON.readTree(evidence.toFile()).path("caseId").asText();
                    return verification(ready.plan().requireCase(id), index == 0 ? 50 : 100, false, 0);
                }, CLOCK);
        assertEquals(4, workers.get()); assertEquals(3, verifiers.get());
        assertEquals("INVALID_REQUIRES_SYMMETRIC_RERUN", report.summary().status());
        assertEquals(168, report.summary().plannedEpisodes()); assertEquals(4, report.summary().attemptedEpisodes());
        assertEquals(3, report.summary().scoredEpisodes()); assertFalse(report.summary().completeValidCoverage());
        assertEquals(50, ((FormalEpisodeOutcome.Scored)report.summary().episodes().get(0).outcome()).score());
        assertInstanceOf(FormalEpisodeOutcome.EvaluationDefect.class, report.summary().episodes().get(3).outcome());
        assertNull(report.summary().observedMeanWeightedScores()); assertNull(report.summary().formalScores());
        assertFalse(report.summary().publishable());
        assertTwoModelManifest(report.directory().resolve("manifest.json"), "INVALID_REQUIRES_SYMMETRIC_RERUN");
        var aggregate = JSON.readTree(report.directory().resolve("aggregate.json").toFile());
        assertEquals(168, aggregate.path("plannedEpisodes").asInt()); assertEquals(4, aggregate.path("attemptedEpisodes").asInt());
        assertEquals(4, aggregate.path("episodes").size());
        assertTrue(aggregate.path("observedMeanWeightedScores").isNull()); assertTrue(aggregate.path("formalScores").isNull());
    }

    @Test
    void twoModelAttemptKeyCannotBeReusedInAnotherBatchWithTheSameEpisodeCoordinates() throws Exception {
        var first = readyTwoModels("two-identity-first");
        var second = readyTwoModels("two-identity-second");
        var firstKey = first.requests().get(0).key(); var secondKey = second.requests().get(0).key();
        assertNotEquals(first.batchSha256(), second.batchSha256());
        assertEquals(firstKey.episodeOrdinal(), secondKey.episodeOrdinal());
        assertEquals(firstKey.provider(), secondKey.provider()); assertEquals(firstKey.model(), secondKey.model());
        assertEquals(firstKey.repeat(), secondKey.repeat()); assertEquals(firstKey.caseId(), secondKey.caseId());
        assertEquals(firstKey.attempt(), secondKey.attempt()); assertNotEquals(firstKey, secondKey);
        var result = new FormalBatchRunner.EpisodeResult(firstKey, "INFRA",
                new FormalEpisodeOutcome.Infra(firstKey, FormalEpisodeOutcome.InfraCode.WORKER_UNAVAILABLE, List.of("synthetic:identity-only")),
                null, null, null, null, null, null, List.of(), null, null, null);
        assertEquals(first.batchSha256(), FormalBatchRunner.aggregate(first.plan(), List.of(result)).batchSha256());
        assertThrows(IllegalArgumentException.class, () -> FormalBatchRunner.aggregate(second.plan(), List.of(result)));
    }

    @Test
    void runsAll252InFrozenOrderRetainsPartialCreditAndSeparatesVerifierDependencies() throws Exception {
        var ready = ready("full");
        AtomicInteger workers = new AtomicInteger();
        AtomicInteger verifiers = new AtomicInteger();
        var report = FormalBatchRunner.run(ready, temp.toRealPath().resolve("output"), "full-run",
                (request, workspace, home, timeout) -> {
                    int index = workers.getAndIncrement();
                    var expected = ready.plan().episodes().get(index);
                    assertEquals(expected.model().model(), request.model());
                    assertEquals(expected.casePlan().prompt(), request.prompt());
                    assertFalse(Files.exists(workspace.getParent().resolve("trusted-verifier")));
                    assertFalse(Files.exists(workspace.resolve("validators")));
                    assertTrue(Files.isWritable(workspace.resolve("shared.txt")));
                    return successfulWorker(request, false);
                }, (invocation, workspace, home, evidence, timeout) -> {
                    var episode = ready.plan().episodes().get(verifiers.getAndIncrement());
                    var envelope = JSON.readTree(evidence.toFile());
                    assertEquals(2, envelope.path("schemaVersion").asInt());
                    assertEquals(episode.caseId(), envelope.path("caseId").asText());
                    assertEquals(episode.casePlan().verifier().bundleSha256(),
                            envelope.path("verifierBundleTreeSha256").asText());
                    assertTrue(Files.exists(invocation.workingDirectory().resolve(
                            episode.casePlan().verifier().entryPath())));
                    assertFalse(invocation.workingDirectory().startsWith(workspace));
                    int score = episode.caseOrdinal() == 1 && episode.repeat() == 1 ? 50 : 100;
                    boolean hardGate = episode.caseOrdinal() == 2 && episode.repeat() == 1;
                    return verification(episode.casePlan(), score, hardGate, 0);
                }, CLOCK);
        assertEquals(252, workers.get()); assertEquals(252, verifiers.get());
        assertEquals("EXECUTED_NOT_RELEASED", report.summary().status());
        assertTrue(report.summary().completeValidCoverage());
        assertFalse(report.summary().publishable()); assertNull(report.summary().formalScores());
        double expected = 100.0 - ready.plan().cases().get(0).weight() / 6.0
                - ready.plan().cases().get(1).weight() / 3.0;
        for (double score : report.summary().observedMeanWeightedScores().values())
            assertEquals(expected, score, 0.0001);
        assertEquals(50, ((FormalEpisodeOutcome.Scored) report.summary().episodes().get(0).outcome()).score());
        var hardGateScore = (FormalEpisodeOutcome.Scored) report.summary().episodes().get(1).outcome();
        assertEquals(0, hardGateScore.score());
        assertTrue(hardGateScore.result().hardGate());
        assertTrue(report.summary().episodes().stream().allMatch(e ->
                e.key().batchSha256().equals(ready.batchSha256()) && e.key().attempt() == 1));
        assertEquals(ready.plan().cases().get(0).episodeEvents(),
                report.summary().episodes().get(0).events().stream().map(FormalBatchRunner.Event::event).toList());
        assertThrows(IOException.class, () -> FormalBatchRunner.run(ready,
                temp.toRealPath().resolve("output"), "full-run",
                (a,b,c,d) -> { fail("old run must never be overwritten"); return null; },
                (a,b,c,d,e) -> null, CLOCK));
    }

    @Test
    void validZeroCallCandidateFailureContinuesWithoutDroppingTheCase() throws Exception {
        var ready = ready("zero");
        AtomicInteger workers = new AtomicInteger();
        AtomicInteger verifiers = new AtomicInteger();
        var report = FormalBatchRunner.run(ready, temp.toRealPath().resolve("output"), "zero-run",
                (request, a,b,c) -> successfulWorker(request, workers.getAndIncrement() == 0),
                (a,b,c,evidence,d) -> {
                    verifiers.incrementAndGet();
                    String id = JSON.readTree(evidence.toFile()).path("caseId").asText();
                    return verification(ready.plan().requireCase(id), 100, false, 0);
                }, CLOCK);
        assertEquals(252, workers.get()); assertEquals(251, verifiers.get());
        assertEquals(0, ((FormalEpisodeOutcome.Scored) report.summary().episodes().get(0).outcome()).score());
        assertEquals(100.0 - ready.plan().cases().get(0).weight() / 3.0,
                report.summary().observedMeanWeightedScores().get("deepseek"), 0.0001);
        assertTrue(report.summary().completeValidCoverage());
    }

    @Test
    void stickyProviderEvidenceFailureStopsWithNoNumericBatchScore() throws Exception {
        var ready = ready("usage");
        var report = FormalBatchRunner.run(ready, temp.toRealPath().resolve("output"), "usage-run",
                (request,a,b,c) -> BenchmarkCoordinatorMain.WorkerExecution.completed(
                        BenchmarkProtocol.WorkerResponse.failure("USAGE_UNPROVEN", "missing usage",
                                successfulWorker(request, false).response().metrics()), 0, 1, ""),
                (a,b,c,d,e) -> { fail("invalid provider evidence must skip verifier"); return null; }, CLOCK);
        assertInvalid(report, FormalEpisodeOutcome.EvaluationDefect.class);
    }

    @Test
    void verifierSetupExitOneIsInfraNotCandidateZeroEvenWithValidLookingStdout() throws Exception {
        var ready = ready("verifier-exit");
        var report = FormalBatchRunner.run(ready, temp.toRealPath().resolve("output"), "verifier-exit-run",
                (request,a,b,c) -> successfulWorker(request, false),
                (a,b,c,d,e) -> verification(ready.plan().cases().get(0), 100, false, 1), CLOCK);
        assertInvalid(report, FormalEpisodeOutcome.Infra.class);
    }

    @Test
    void bundleTamperingWinsOverVerifierSuccess() throws Exception {
        var ready = ready("tamper");
        var report = FormalBatchRunner.run(ready, temp.toRealPath().resolve("output"), "tamper-run",
                (request,a,b,c) -> successfulWorker(request, false),
                (invocation,b,c,d,e) -> {
                    Path entry = invocation.workingDirectory().resolve(ready.plan().cases().get(0).verifier().entryPath());
                    Files.setPosixFilePermissions(entry, PosixFilePermissions.fromString("rw-------"));
                    Files.writeString(entry, "changed");
                    return verification(ready.plan().cases().get(0), 100, false, 0);
                }, CLOCK);
        assertInvalid(report, FormalEpisodeOutcome.Security.class);
    }

    @Test
    void workerStartFailureIsInfraAndDoesNotInventACompletedProcess() throws Exception {
        var ready = ready("launch");
        var report = FormalBatchRunner.run(ready, temp.toRealPath().resolve("output"), "launch-run",
                (a,b,c,d) -> BenchmarkCoordinatorMain.WorkerExecution.startFailure("unavailable"),
                (a,b,c,d,e) -> { fail("must not verify"); return null; }, CLOCK);
        assertInvalid(report, FormalEpisodeOutcome.Infra.class);
        assertEquals(FormalEpisodeOutcome.InfraCode.WORKER_UNAVAILABLE,
                ((FormalEpisodeOutcome.Infra) report.summary().episodes().get(0).outcome()).code());
        assertEquals(List.of("worker_dispatch_started", "worker_dispatch_finished"),
                report.summary().episodes().get(0).events().stream().map(FormalBatchRunner.Event::event).toList());
    }

    @Test
    void credentialCanaryIsSecurityAndNeverSerializedAsMetrics() throws Exception {
        var ready = ready("secret");
        var report = FormalBatchRunner.run(ready, temp.toRealPath().resolve("output"), "secret-run",
                (request,a,b,c) -> BenchmarkCoordinatorMain.WorkerExecution.completed(
                        BenchmarkProtocol.WorkerResponse.success(request.apiKey(),
                                successfulWorker(request, false).response().metrics()), 0, 1, ""),
                (a,b,c,d,e) -> { fail("must not verify"); return null; }, CLOCK);
        assertInvalid(report, FormalEpisodeOutcome.Security.class);
        assertNull(report.summary().episodes().get(0).metrics());
        assertFalse(Files.readString(report.directory().resolve("aggregate.json")).contains("test-private-secret"));
    }

    @Test
    void evidenceTamperingIsDetectedEvenIfVerifierThrows() throws Exception {
        var ready = ready("evidence");
        var report = FormalBatchRunner.run(ready, temp.toRealPath().resolve("output"), "evidence-run",
                (request,a,b,c) -> successfulWorker(request, false),
                (a,b,c,evidence,e) -> {
                    Files.setPosixFilePermissions(evidence, PosixFilePermissions.fromString("rw-------"));
                    Files.writeString(evidence, "changed");
                    throw new IOException("verifier failed");
                }, CLOCK);
        assertInvalid(report, FormalEpisodeOutcome.Security.class);
    }

    @Test
    void interruptedWorkerPreservesTerminalCheckpointAndDoesNotContinue() throws Exception {
        var ready = ready("interrupt");
        Path output = temp.toRealPath().resolve("output");
        assertThrows(InterruptedException.class, () -> FormalBatchRunner.run(ready, output, "interrupt-run",
                (request,a,b,c) -> { throw new InterruptedException("stop"); },
                (a,b,c,d,e) -> { fail("must not verify"); return null; }, CLOCK));
        Thread.interrupted(); // Clear only the test's deliberate interruption.
        assertEquals("INTERRUPTED", JSON.readTree(output.resolve("interrupt-run/manifest.json").toFile())
                .path("status").asText());
        assertTrue(JSON.readTree(output.resolve("interrupt-run/aggregate.json").toFile())
                .path("observedMeanWeightedScores").isNull());
    }

    private FormalBatchPreparation.ReadyBatch ready(String id) throws Exception {
        return FormalBatchPreparation.prepare(FormalTestAdmission.createForExecution(
                temp.toRealPath().resolve(id)), provider ->
                new FormalEpisodeRequestFactory.HostCredential(provider, null, "test-private-secret-" + provider));
    }

    private FormalBatchPreparation.ReadyBatch readyTwoModels(String id) throws Exception {
        return FormalBatchPreparation.prepare(FormalTestAdmission.createForTwoModelExecution(
                temp.toRealPath().resolve(id)), provider -> {
            assertNotEquals("hunyuan", provider, "two-model execution must not request Hy4 credentials");
            return new FormalEpisodeRequestFactory.HostCredential(provider, null, "test-private-secret-" + provider);
        });
    }

    private static void assertTwoModelManifest(Path file, String status) throws IOException {
        var manifest = JSON.readTree(file.toFile());
        assertEquals(status, manifest.path("status").asText());
        assertEquals(168, manifest.path("plannedEpisodes").asInt()); assertEquals(28, manifest.path("caseCount").asInt());
        assertEquals(4, manifest.path("batchContractVersion").asInt()); assertEquals(5, manifest.path("planVersion").asInt());
        assertEquals(3, manifest.path("repeats").asInt()); assertEquals(2, manifest.path("models").size());
        assertEquals("deepseek", manifest.path("models").get(0).path("provider").asText());
        assertEquals("deepseek-v4-flash", manifest.path("models").get(0).path("model").asText());
        assertEquals("glm", manifest.path("models").get(1).path("provider").asText());
        assertEquals("glm-5.3-flash", manifest.path("models").get(1).path("model").asText());
        assertFalse(manifest.path("publishable").asBoolean());
    }

    private static void assertInvalid(FormalBatchRunner.RunReport report, Class<?> type) {
        assertEquals("INVALID_REQUIRES_SYMMETRIC_RERUN", report.summary().status());
        assertEquals(252, report.summary().plannedEpisodes());
        assertEquals(1, report.summary().attemptedEpisodes());
        assertNull(report.summary().observedMeanWeightedScores()); assertNull(report.summary().formalScores());
        assertFalse(report.summary().completeValidCoverage());
        assertInstanceOf(type, report.summary().episodes().get(0).outcome());
    }

    static BenchmarkCoordinatorMain.WorkerExecution successfulWorker(
            BenchmarkProtocol.WorkerRequest request, boolean zeroCalls) {
        var limits = request.agentLimits();
        var metrics = new TracingLlmClient.Metrics(zeroCalls ? 0 : 1, zeroCalls ? 0 : 10,
                zeroCalls ? 0 : 5, 0, 0, 1,
                zeroCalls ? 0 : 1, zeroCalls ? "" : request.model(), !zeroCalls, !zeroCalls,
                zeroCalls ? "" : "a".repeat(64), zeroCalls ? "" : "b".repeat(64), !zeroCalls,
                limits.contextWindowCapTokens(), limits.contextWindowCapTokens(),
                limits.maxOutputTokensPerCall(), zeroCalls ? 0 : 10, zeroCalls ? 0 : 15, !zeroCalls);
        return BenchmarkCoordinatorMain.WorkerExecution.completed(
                BenchmarkProtocol.WorkerResponse.success("answer", metrics), 0, 1, "");
    }

    static BenchmarkVerifier.Result verification(FormalExecutionPlan.CasePlan plan,
            int score, boolean hardGate, int exit) throws IOException {
        var c = plan.scoringContract();
        var report = new VerifierScoringReport(1, plan.id(),
                c.assertions().stream().map(a -> new VerifierScoringReport.AssertionResult(
                        a.id(), score == 100, List.of("check:assertion"))).toList(),
                c.hardGates().stream().map(g -> new VerifierScoringReport.HardGateResult(
                        g.id(), hardGate, List.of("check:gate"))).toList(),
                c.components().stream().map(component -> new VerifierScoringReport.ComponentResult(
                        component.id(), score * component.maxPoints() / 100, component.maxPoints(),
                        component.source(), List.of("check:component"))).toList(),
                c.verifierSha256(), c.toolchainSha256());
        return new BenchmarkVerifier.Result(exit == 0 ? BenchmarkVerifier.Status.PASSED : BenchmarkVerifier.Status.FAILED,
                exit, 1, List.of("verifier"), JSON.writeValueAsString(report), "", false, false, true, "test-policy");
    }
}
