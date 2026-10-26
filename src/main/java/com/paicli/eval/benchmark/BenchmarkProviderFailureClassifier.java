package com.paicli.eval.benchmark;

import com.paicli.llm.LlmFailureClassifier;

import java.io.IOException;

/** Maps provider/cap failures to stable benchmark policy categories without retaining messages. */
public final class BenchmarkProviderFailureClassifier {
    static final String CONTEXT_BUDGET_EXCEEDED = "CONTEXT_BUDGET_EXCEEDED";

    private BenchmarkProviderFailureClassifier() {
    }

    public static String classify(IOException failure) {
        ContextWindowCappedLlmClient.ContextWindowCapExceededException cap =
                findContextCapFailure(failure);
        if (cap != null) {
            return switch (cap.kind()) {
                case REQUEST_ESTIMATE_EXCEEDED -> CONTEXT_BUDGET_EXCEEDED;
                case PROVIDER_USAGE_MISSING, PROVIDER_USAGE_INVALID ->
                        BenchmarkProviderEvidenceGate.USAGE_UNPROVEN;
                case PROVIDER_OUTPUT_EXCEEDED ->
                        BenchmarkProviderEvidenceGate.OUTPUT_POLICY_UNPROVEN;
                case PROVIDER_CONTEXT_EXCEEDED ->
                        BenchmarkProviderEvidenceGate.CONTEXT_CAP_UNPROVEN;
            };
        }
        return LlmFailureClassifier.classify(failure)
                == LlmFailureClassifier.Category.TRANSIENT
                ? "PROVIDER_TRANSIENT"
                : "LLM_API_ERROR";
    }

    private static ContextWindowCappedLlmClient.ContextWindowCapExceededException
            findContextCapFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof ContextWindowCappedLlmClient.ContextWindowCapExceededException cap) {
                return cap;
            }
        }
        return null;
    }
}
