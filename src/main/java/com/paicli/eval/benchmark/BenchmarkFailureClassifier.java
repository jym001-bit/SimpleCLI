package com.paicli.eval.benchmark;

/** Central scoring boundary: candidate/model failures score zero; only allowlisted harness faults are infra. */
final class BenchmarkFailureClassifier {
    private BenchmarkFailureClassifier() {
    }

    static Disposition classifyWorker(BenchmarkCoordinatorMain.WorkerExecution worker) {
        if (worker == null) {
            return Disposition.SCORED_FAILURE;
        }
        return switch (worker.status()) {
            case START_FAILURE -> Disposition.INFRA_ERROR;
            case SECURITY_ERROR -> Disposition.SECURITY_HARD_GATE;
            case TIMEOUT, PROCESS_ERROR -> Disposition.SCORED_FAILURE;
            case COMPLETED -> classifyResponse(worker.response());
        };
    }

    static Disposition classifyResponse(BenchmarkProtocol.WorkerResponse response) {
        if (response == null || !response.success()) {
            String errorType = response == null ? "" : response.errorType();
            return switch (errorType) {
                case "PROVIDER_TRANSIENT", "FROZEN_MOCK_FAILURE", "RUNNER_SANDBOX_UNAVAILABLE",
                        "RUNNER_IO_ERROR", "CONTEXT_CAP_UNAVAILABLE",
                        BenchmarkProviderEvidenceGate.FAILURE_TYPE,
                        BenchmarkProviderEvidenceGate.MODEL_IDENTITY_UNPROVEN,
                        BenchmarkProviderEvidenceGate.USAGE_UNPROVEN,
                        BenchmarkProviderEvidenceGate.OUTPUT_POLICY_UNPROVEN,
                        BenchmarkProviderEvidenceGate.CONTEXT_CAP_UNPROVEN,
                        BenchmarkProviderEvidenceGate.REQUEST_FINGERPRINT_UNPROVEN ->
                        Disposition.INFRA_ERROR;
                default -> Disposition.SCORED_FAILURE;
            };
        }
        return Disposition.CONTINUE_TO_VERIFIER;
    }

    static Disposition classifyVerifier(BenchmarkVerifier.Status status) {
        if (status == null) {
            return Disposition.INFRA_ERROR;
        }
        return status == BenchmarkVerifier.Status.PASSED
                ? Disposition.PASSED
                : Disposition.SCORED_FAILURE;
    }

    enum Disposition {
        CONTINUE_TO_VERIFIER,
        PASSED,
        SCORED_FAILURE,
        INFRA_ERROR,
        SECURITY_HARD_GATE
    }
}
