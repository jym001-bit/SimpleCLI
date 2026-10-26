package com.paicli.eval.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.LinkedHashMap;
import static org.junit.jupiter.api.Assertions.*;

class F3LiveLifecycleTest {
    @TempDir Path temp;
    private static BenchmarkProtocol.WorkerRequest request() {
        return new BenchmarkProtocol.WorkerRequest(BenchmarkProtocol.VERSION, "deepseek", "deepseek-v4-flash", null,
                "synthetic-private-lifecycle", "REACT", BenchmarkToolProfile.MOCK_MCP_FILE_ONLY,
                new BenchmarkProtocol.AgentLimits(100_000, 32, 8, 1_000_000, 16_384), "2026-09-05", "synthetic task",
                "/private/tmp/f3-lifecycle/workspace", "/private/tmp/f3-lifecycle/home", "/private/tmp/f3-lifecycle");
    }
    @Test void completeEvidenceDefersBusinessScoreToIndependentVerifier() {
        var r = request(); var worker = FormalBatchRunnerTest.successfulWorker(r, false);
        assertEquals("VERIFY", F3LiveDockerDiagnosticTest.diagnosticDisposition(r, worker, false));
        assertEquals("INVALID", F3LiveDockerDiagnosticTest.diagnosticDisposition(r, worker, true));
    }
    @Test void zeroCallIsStillAnEffectiveCandidateFailure() {
        var r = request();
        assertEquals("VALID_FAILURE", F3LiveDockerDiagnosticTest.diagnosticDisposition(r, FormalBatchRunnerTest.successfulWorker(r, true), false));
    }
    @Test void ordinaryCandidateAndBudgetFailuresDoNotNeedSuccessfulTerminal() {
        var r = request(); var metrics = FormalBatchRunnerTest.successfulWorker(r, false).response().metrics();
        for (String type : new String[]{"CANDIDATE_WORKER_ERROR", "EPISODE_BUDGET_EXHAUSTED", "PROVIDER_CALL_FAILED"}) {
            var worker = BenchmarkCoordinatorMain.WorkerExecution.completed(BenchmarkProtocol.WorkerResponse.failure(type, "synthetic", metrics), 0, 0, "");
            assertEquals("VALID_FAILURE", F3LiveDockerDiagnosticTest.diagnosticDisposition(r, worker, false), type);
            assertEquals("INVALID", F3LiveDockerDiagnosticTest.diagnosticDisposition(r, worker, true), type);
        }
    }
    @Test void evidenceFaultsRemainUnscoredEvenWhenMetricsLookComplete() {
        var r = request(); var metrics = FormalBatchRunnerTest.successfulWorker(r, false).response().metrics();
        for (String type : new String[]{"PROVIDER_TRANSIENT", "USAGE_UNPROVEN", "PROVIDER_EVIDENCE_UNAVAILABLE", "MODEL_IDENTITY_UNPROVEN", "REQUEST_FINGERPRINT_UNPROVEN"}) {
            var worker = BenchmarkCoordinatorMain.WorkerExecution.completed(BenchmarkProtocol.WorkerResponse.failure(type, "synthetic", metrics), 0, 0, "");
            assertEquals("INVALID", F3LiveDockerDiagnosticTest.diagnosticDisposition(r, worker, false), type);
        }
        assertEquals("INVALID", F3LiveDockerDiagnosticTest.diagnosticDisposition(r, null, false));
        var missing = BenchmarkCoordinatorMain.WorkerExecution.completed(BenchmarkProtocol.WorkerResponse.success("done", null), 0, 0, "");
        assertEquals("INVALID", F3LiveDockerDiagnosticTest.diagnosticDisposition(r, missing, false));
    }
    @Test void lateDriftClearsPreviouslyComputedScoreAndStrictSuccess() {
        var report = new LinkedHashMap<String, Object>(); report.put("diagnosticScore", 100);
        report.put("diagnosticSatisfied", true); report.put("scoreResult", "prior complete report");
        F3LiveDockerDiagnosticTest.invalidate(report, "FROZEN_SOURCE_DRIFT");
        assertEquals("EVALUATION_INVALID", report.get("status")); assertNull(report.get("diagnosticScore"));
        assertFalse((Boolean)report.get("diagnosticSatisfied")); assertFalse(report.containsKey("scoreResult"));
    }
    @Test void interruptedFinalResultRemainsParseableAndRestoresTheInterrupt() throws Exception {
        var report = new LinkedHashMap<String, Object>(); F3LiveDockerDiagnosticTest.invalidate(report, "INTERRUPTED");
        try {
            Thread.currentThread().interrupt();
            F3LiveDockerDiagnosticTest.persistResult(temp.resolve("result.json"), report, null);
            assertTrue(Thread.currentThread().isInterrupted()); Thread.interrupted();
            var saved = new com.fasterxml.jackson.databind.ObjectMapper().readTree(temp.resolve("result.json").toFile());
            assertEquals("EVALUATION_INVALID", saved.path("status").asText()); assertTrue(saved.path("diagnosticScore").isNull());
        } finally { Thread.interrupted(); }
    }
    @Test void credentialBearingReportFallsBackWithoutRawFieldsOrNumericScore() throws Exception {
        String secret = "synthetic-credential-private-retention";
        var report = new LinkedHashMap<String, Object>(); report.put("metrics", "unsafe " + secret); report.put("diagnosticScore", 100);
        F3LiveDockerDiagnosticTest.persistResult(temp.resolve("result.json"), report, secret);
        String bytes = Files.readString(temp.resolve("result.json")); assertFalse(bytes.contains(secret));
        var saved = new com.fasterxml.jackson.databind.ObjectMapper().readTree(bytes);
        assertEquals("CREDENTIAL_IN_REPORT", saved.path("failureCode").asText());
        assertEquals("EVALUATION_INVALID", saved.path("status").asText()); assertTrue(saved.path("diagnosticScore").isNull());
        assertFalse(saved.has("metrics")); assertFalse(saved.has("verifier"));
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(temp.resolve("result.json")));
    }
}
