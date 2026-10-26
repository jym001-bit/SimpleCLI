package com.paicli.eval.benchmark;

/** Shared fail-closed provider evidence gate for host and Docker benchmark workers. */
final class BenchmarkProviderEvidenceGate {
    static final String FAILURE_TYPE = "PROVIDER_EVIDENCE_UNAVAILABLE";
    static final String NO_PROVIDER_CALL = "NO_PROVIDER_CALL";
    static final String PROVIDER_CALL_FAILED = "PROVIDER_CALL_FAILED";
    static final String MODEL_IDENTITY_UNPROVEN = "MODEL_IDENTITY_UNPROVEN";
    static final String USAGE_UNPROVEN = "USAGE_UNPROVEN";
    static final String OUTPUT_POLICY_UNPROVEN = "OUTPUT_POLICY_UNPROVEN";
    static final String CONTEXT_CAP_UNPROVEN = "CONTEXT_CAP_UNPROVEN";
    static final String REQUEST_FINGERPRINT_UNPROVEN = "REQUEST_FINGERPRINT_UNPROVEN";

    private BenchmarkProviderEvidenceGate() {
    }

    static boolean isSatisfied(
            BenchmarkProtocol.WorkerRequest request,
            TracingLlmClient.Metrics metrics) {
        return failureType(request, metrics) == null;
    }

    /**
     * Returns a stable, message-free failure type, or {@code null} when all evidence is proven.
     * A zero-call Candidate is deliberately distinct from missing provider evidence: it is a
     * scored Candidate failure, while the UNPROVEN types invalidate the evaluation episode.
     */
    static String failureType(
            BenchmarkProtocol.WorkerRequest request,
            TracingLlmClient.Metrics metrics) {
        if (request == null) {
            return FAILURE_TYPE;
        }
        String invalid = invalidEvaluationFailureType(request, metrics);
        return invalid != null ? invalid : failureType(request.model(), request.agentLimits(), metrics);
    }

    /**
     * Secret-free form used by run-level evidence aggregation after worker requests have left
     * scope. This must remain the single policy boundary for episode and aggregate evidence.
     */
    static String failureType(
            String requestedModel,
            BenchmarkProtocol.AgentLimits limits,
            TracingLlmClient.Metrics metrics) {
        String invalidEvidence = invalidEvaluationFailureType(requestedModel, limits, metrics);
        if (invalidEvidence != null) {
            return invalidEvidence;
        }
        if (metrics.calls() <= 0) {
            return NO_PROVIDER_CALL;
        }
        if (metrics.successfulCalls() != metrics.calls()) {
            return PROVIDER_CALL_FAILED;
        }
        return null;
    }

    /**
     * Returns only evidence defects that invalidate an evaluation episode. Ordinary provider
     * failures are deliberately excluded so they retain their scored-failure semantics.
     */
    static String invalidEvaluationFailureType(
            BenchmarkProtocol.WorkerRequest request,
            TracingLlmClient.Metrics metrics) {
        if (request == null) {
            return FAILURE_TYPE;
        }
        if (metrics != null) {
            ScopedRequestFingerprints scoped = metrics.scopedRequestFingerprints();
            if (scoped != null && !scoped.mode().equalsIgnoreCase(request.mode().trim())
                    || metrics.calls() > 0 && "TEAM".equalsIgnoreCase(request.mode().trim()) && scoped == null)
                return REQUEST_FINGERPRINT_UNPROVEN;
        }
        return invalidEvaluationFailureType(request.model(), request.agentLimits(), metrics);
    }

    static String invalidEvaluationFailureType(
            String requestedModel,
            BenchmarkProtocol.AgentLimits limits,
            TracingLlmClient.Metrics metrics) {
        if (requestedModel == null || requestedModel.isBlank() || limits == null || metrics == null) {
            return FAILURE_TYPE;
        }
        if (metrics.successfulCalls() > 0
                && (!metrics.resolvedModelConsistent()
                || !requestedModel.equals(metrics.resolvedModel()))) {
            return MODEL_IDENTITY_UNPROVEN;
        }
        if (metrics.successfulCalls() > 0 && !metrics.usageComplete()) {
            return USAGE_UNPROVEN;
        }
        if (metrics.maxOutputTokensPerCall() != limits.maxOutputTokensPerCall()) {
            return OUTPUT_POLICY_UNPROVEN;
        }
        if (metrics.requestedContextWindowCapTokens() != limits.contextWindowCapTokens()
                || metrics.effectiveContextWindowCapTokens() != limits.contextWindowCapTokens()) {
            return CONTEXT_CAP_UNPROVEN;
        }
        if (metrics.calls() > 0 && (!metrics.requestFingerprintComplete()
                || metrics.scopedRequestFingerprints() != null
                && !metrics.scopedRequestFingerprints().completeFor(metrics.calls()))) {
            return REQUEST_FINGERPRINT_UNPROVEN;
        }
        if (metrics.calls() > 0
                && metrics.successfulCalls() == metrics.calls()
                && !metrics.contextCapSatisfied()) {
            return CONTEXT_CAP_UNPROVEN;
        }
        return null;
    }

    static boolean isEvaluationInvalidFailureType(String failureType) {
        return FAILURE_TYPE.equals(failureType)
                || MODEL_IDENTITY_UNPROVEN.equals(failureType)
                || USAGE_UNPROVEN.equals(failureType)
                || OUTPUT_POLICY_UNPROVEN.equals(failureType)
                || CONTEXT_CAP_UNPROVEN.equals(failureType)
                || REQUEST_FINGERPRINT_UNPROVEN.equals(failureType);
    }
}
