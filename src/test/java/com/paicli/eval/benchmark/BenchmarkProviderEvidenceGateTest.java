package com.paicli.eval.benchmark;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkProviderEvidenceGateTest {
    @Test
    void acceptsOnlyCompleteExactProviderAndCapacityEvidence() {
        BenchmarkProtocol.WorkerRequest request = request();
        assertTrue(BenchmarkProviderEvidenceGate.isSatisfied(request, metrics(
                1, 1, request.model(), true, true, true,
                1_000_000, 1_000_000, 16_384, true)));
        assertTrue(BenchmarkProviderEvidenceGate.failureType(request, metrics(
                1, 1, request.model(), true, true, true,
                1_000_000, 1_000_000, 16_384, true)) == null);

        assertFalse(BenchmarkProviderEvidenceGate.isSatisfied(null, metrics(
                1, 1, request.model(), true, true, true,
                1_000_000, 1_000_000, 16_384, true)));
        assertFalse(BenchmarkProviderEvidenceGate.isSatisfied(request, null));
        assertFalse(BenchmarkProviderEvidenceGate.isSatisfied(request, metrics(
                0, 0, request.model(), true, true, true,
                1_000_000, 1_000_000, 16_384, true)));
        assertFalse(BenchmarkProviderEvidenceGate.isSatisfied(request, metrics(
                2, 1, request.model(), true, true, true,
                1_000_000, 1_000_000, 16_384, true)));
        assertFalse(BenchmarkProviderEvidenceGate.isSatisfied(request, metrics(
                1, 1, "glm-5.3-flash", true, true, true,
                1_000_000, 1_000_000, 16_384, true)));
        assertFalse(BenchmarkProviderEvidenceGate.isSatisfied(request, metrics(
                1, 1, request.model(), false, true, true,
                1_000_000, 1_000_000, 16_384, true)));
        assertFalse(BenchmarkProviderEvidenceGate.isSatisfied(request, metrics(
                1, 1, request.model(), true, false, true,
                1_000_000, 1_000_000, 16_384, true)));
        assertFalse(BenchmarkProviderEvidenceGate.isSatisfied(request, metrics(
                1, 1, request.model(), true, true, false,
                1_000_000, 1_000_000, 16_384, true)));
        assertFalse(BenchmarkProviderEvidenceGate.isSatisfied(request, metrics(
                1, 1, request.model(), true, true, true,
                999_999, 999_999, 16_384, true)));
        assertFalse(BenchmarkProviderEvidenceGate.isSatisfied(request, metrics(
                1, 1, request.model(), true, true, true,
                1_000_000, 999_999, 16_384, true)));
        assertFalse(BenchmarkProviderEvidenceGate.isSatisfied(request, metrics(
                1, 1, request.model(), true, true, true,
                1_000_000, 1_000_000, 8_192, true)));
        assertFalse(BenchmarkProviderEvidenceGate.isSatisfied(request, metrics(
                1, 1, request.model(), true, true, true,
                1_000_000, 1_000_000, 16_384, false)));
        assertTrue(BenchmarkProviderEvidenceGate.NO_PROVIDER_CALL.equals(
                BenchmarkProviderEvidenceGate.failureType(request, metrics(
                        0, 0, request.model(), true, true, true,
                        1_000_000, 1_000_000, 16_384, true))));
        assertTrue(BenchmarkProviderEvidenceGate.MODEL_IDENTITY_UNPROVEN.equals(
                BenchmarkProviderEvidenceGate.failureType(request, metrics(
                        1, 1, "glm-5.3-flash", true, true, true,
                        1_000_000, 1_000_000, 16_384, true))));
    }

    private static BenchmarkProtocol.WorkerRequest request() {
        return new BenchmarkProtocol.WorkerRequest(
                BenchmarkProtocol.VERSION,
                "deepseek",
                "deepseek-v4-flash",
                null,
                "secret",
                "react",
                BenchmarkToolProfile.REASONING_ONLY,
                new BenchmarkProtocol.AgentLimits(32_000, 16, 4, 1_000_000, 16_384),
                "2026-09-01",
                "reason carefully",
                "/tmp/workspace",
                "/tmp/home",
                "/tmp/artifact");
    }

    private static TracingLlmClient.Metrics metrics(
            int calls,
            int successfulCalls,
            String resolvedModel,
            boolean resolvedModelConsistent,
            boolean usageComplete,
            boolean requestFingerprintComplete,
            int requestedContextCap,
            int effectiveContextCap,
            int maxOutputTokens,
            boolean contextCapSatisfied) {
        return new TracingLlmClient.Metrics(
                calls, 10, 5, 0, 0, 20,
                successfulCalls, resolvedModel, resolvedModelConsistent, usageComplete,
                "a".repeat(64), "b".repeat(64), requestFingerprintComplete,
                requestedContextCap, effectiveContextCap, maxOutputTokens,
                10, 15, contextCapSatisfied);
    }
}
