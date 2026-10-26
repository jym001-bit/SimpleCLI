package com.paicli.eval.benchmark;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BenchmarkProviderFailureClassifierTest {
    @Test
    void distinguishesCandidateContextBudgetFromInvalidProviderEvidence() {
        assertEquals(BenchmarkProviderFailureClassifier.CONTEXT_BUDGET_EXCEEDED,
                BenchmarkProviderFailureClassifier.classify(
                        ContextWindowCappedLlmClient.ContextWindowCapExceededException
                                .requestEstimate(100_000, 100_000, 90_000, 16_384)));
        assertEquals(BenchmarkProviderEvidenceGate.USAGE_UNPROVEN,
                BenchmarkProviderFailureClassifier.classify(
                        ContextWindowCappedLlmClient.ContextWindowCapExceededException
                                .missingProviderUsage(1_000_000, 1_000_000, 16_384)));
        assertEquals(BenchmarkProviderEvidenceGate.USAGE_UNPROVEN,
                BenchmarkProviderFailureClassifier.classify(
                        ContextWindowCappedLlmClient.ContextWindowCapExceededException
                                .invalidProviderUsage(
                                        1_000_000, 1_000_000, 16_384, -1, 2)));
        assertEquals(BenchmarkProviderEvidenceGate.OUTPUT_POLICY_UNPROVEN,
                BenchmarkProviderFailureClassifier.classify(
                        ContextWindowCappedLlmClient.ContextWindowCapExceededException
                                .providerOutputExceeded(
                                        1_000_000, 1_000_000, 16_384,
                                        10, 16_385, 16_395)));
        assertEquals(BenchmarkProviderEvidenceGate.CONTEXT_CAP_UNPROVEN,
                BenchmarkProviderFailureClassifier.classify(
                        ContextWindowCappedLlmClient.ContextWindowCapExceededException
                                .providerContextExceeded(
                                        1_000_000, 1_000_000, 16_384,
                                        999_000, 2_000, 1_001_000)));
    }

    @Test
    void preservesTransientAndOrdinaryProviderCategories() {
        assertEquals("PROVIDER_TRANSIENT",
                BenchmarkProviderFailureClassifier.classify(
                        new SocketTimeoutException("provider read timed out")));
        assertEquals("LLM_API_ERROR",
                BenchmarkProviderFailureClassifier.classify(new IOException("bad response")));
    }
}
