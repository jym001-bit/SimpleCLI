package com.paicli.eval.benchmark.formal;

import java.util.Objects;

/** Stable append-only identity for one execution attempt of one preregistered episode. */
public record AttemptKey(
        String batchSha256,
        int episodeOrdinal,
        String provider,
        String model,
        int repeat,
        String caseId,
        int attempt) {

    public AttemptKey {
        FormalContractSupport.requireSha256(batchSha256, "attempt batchSha256");
        if (episodeOrdinal <= 0 || repeat <= 0 || attempt <= 0) {
            throw new IllegalArgumentException(
                    "episodeOrdinal, repeat and attempt must be positive");
        }
        FormalContractSupport.requireSafeIdentifier(provider, "attempt provider");
        FormalContractSupport.requireSafeIdentifier(model, "attempt model");
        FormalContractSupport.requireSafeIdentifier(caseId, "attempt caseId");
    }

    public static AttemptKey from(FormalExecutionPlan plan,
                                  FormalExecutionPlan.EpisodePlan episode, int attempt) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(episode, "episode");
        return new AttemptKey(
                plan.artifacts().formalBatchContractSha256(),
                episode.ordinal(),
                episode.model().provider(),
                episode.model().model(),
                episode.repeat(),
                episode.caseId(),
                attempt);
    }
}
