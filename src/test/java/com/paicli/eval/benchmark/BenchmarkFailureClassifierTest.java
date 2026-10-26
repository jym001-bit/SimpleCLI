package com.paicli.eval.benchmark;

import org.junit.jupiter.api.Test;

import static com.paicli.eval.benchmark.BenchmarkFailureClassifier.Disposition.CONTINUE_TO_VERIFIER;
import static com.paicli.eval.benchmark.BenchmarkFailureClassifier.Disposition.INFRA_ERROR;
import static com.paicli.eval.benchmark.BenchmarkFailureClassifier.Disposition.PASSED;
import static com.paicli.eval.benchmark.BenchmarkFailureClassifier.Disposition.SCORED_FAILURE;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BenchmarkFailureClassifierTest {
    @Test
    void modelTimeoutAndAdapterOrHttp4xxFailuresScoreZero() {
        assertEquals(SCORED_FAILURE, BenchmarkFailureClassifier.classifyWorker(completedFailure(
                "CANDIDATE_WORKSPACE_DAMAGED", "registered F2 fixture permissions changed")));
        assertEquals(SCORED_FAILURE, BenchmarkFailureClassifier.classifyWorker(
                BenchmarkCoordinatorMain.WorkerExecution.timeout(1_000, "model timeout")));
        assertEquals(SCORED_FAILURE, BenchmarkFailureClassifier.classifyWorker(completedFailure(
                "LLM_API_ERROR", "API请求失败: 401")));
        assertEquals(SCORED_FAILURE, BenchmarkFailureClassifier.classifyWorker(completedFailure(
                "LLM_API_ERROR", "API请求失败: 400")));
        assertEquals(SCORED_FAILURE, BenchmarkFailureClassifier.classifyWorker(
                BenchmarkCoordinatorMain.WorkerExecution.processFailure(3, 10, "adapter crashed")));
    }

    @Test
    void onlyAllowlistedRunnerAndConfirmedTransientFailuresAreInfra() {
        assertEquals(INFRA_ERROR, BenchmarkFailureClassifier.classifyWorker(
                BenchmarkCoordinatorMain.WorkerExecution.startFailure("java missing")));
        assertEquals(INFRA_ERROR, BenchmarkFailureClassifier.classifyWorker(completedFailure(
                "PROVIDER_TRANSIENT", "API请求失败: 503")));
        assertEquals(INFRA_ERROR, BenchmarkFailureClassifier.classifyWorker(completedFailure(
                "RUNNER_SANDBOX_UNAVAILABLE", "probe failed")));
        assertEquals(INFRA_ERROR, BenchmarkFailureClassifier.classifyWorker(completedFailure(
                "FROZEN_MOCK_FAILURE", "mock host fault")));
        assertEquals(INFRA_ERROR, BenchmarkFailureClassifier.classifyWorker(completedFailure(
                "CONTEXT_CAP_UNAVAILABLE", "provider window is too small")));
        assertEquals(INFRA_ERROR, BenchmarkFailureClassifier.classifyWorker(completedFailure(
                BenchmarkProviderEvidenceGate.FAILURE_TYPE,
                "provider evidence is incomplete")));
        assertEquals(INFRA_ERROR, BenchmarkFailureClassifier.classifyWorker(completedFailure(
                BenchmarkProviderEvidenceGate.MODEL_IDENTITY_UNPROVEN,
                "model identity is incomplete")));
        assertEquals(INFRA_ERROR, BenchmarkFailureClassifier.classifyWorker(completedFailure(
                BenchmarkProviderEvidenceGate.USAGE_UNPROVEN,
                "usage is incomplete")));
        assertEquals(INFRA_ERROR, BenchmarkFailureClassifier.classifyWorker(completedFailure(
                BenchmarkProviderEvidenceGate.OUTPUT_POLICY_UNPROVEN,
                "output policy is incomplete")));
        assertEquals(INFRA_ERROR, BenchmarkFailureClassifier.classifyWorker(completedFailure(
                BenchmarkProviderEvidenceGate.CONTEXT_CAP_UNPROVEN,
                "context policy is incomplete")));
        assertEquals(INFRA_ERROR, BenchmarkFailureClassifier.classifyWorker(completedFailure(
                BenchmarkProviderEvidenceGate.REQUEST_FINGERPRINT_UNPROVEN,
                "request fingerprint is incomplete")));
        assertEquals(SCORED_FAILURE, BenchmarkFailureClassifier.classifyWorker(completedFailure(
                BenchmarkProviderEvidenceGate.NO_PROVIDER_CALL,
                "candidate did not make a provider call")));
        assertEquals(SCORED_FAILURE, BenchmarkFailureClassifier.classifyWorker(completedFailure(
                BenchmarkProviderFailureClassifier.CONTEXT_BUDGET_EXCEEDED,
                "candidate exceeded frozen context budget")));
    }

    @Test
    void verifierTimeoutAndNonZeroScoreZero() {
        assertEquals(PASSED,
                BenchmarkFailureClassifier.classifyVerifier(BenchmarkVerifier.Status.PASSED));
        assertEquals(SCORED_FAILURE,
                BenchmarkFailureClassifier.classifyVerifier(BenchmarkVerifier.Status.FAILED));
        assertEquals(SCORED_FAILURE,
                BenchmarkFailureClassifier.classifyVerifier(BenchmarkVerifier.Status.TIMEOUT));
    }

    @Test
    void successfulWorkerContinuesToVerifier() {
        BenchmarkProtocol.WorkerResponse response = BenchmarkProtocol.WorkerResponse.success(
                "done", new TracingLlmClient.Metrics(1, 2, 3, 0, 0, 4));
        assertEquals(CONTINUE_TO_VERIFIER, BenchmarkFailureClassifier.classifyWorker(
                BenchmarkCoordinatorMain.WorkerExecution.completed(response, 0, 4, "")));
    }

    private static BenchmarkCoordinatorMain.WorkerExecution completedFailure(String type, String message) {
        return BenchmarkCoordinatorMain.WorkerExecution.completed(
                BenchmarkProtocol.WorkerResponse.failure(type, message, null), 0, 10, "");
    }
}
