package com.paicli.eval.benchmark.formal;

import com.paicli.eval.benchmark.scoring.ScoreCalculator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FormalEpisodeOutcomeTest {
    private static final AttemptKey KEY =
            new AttemptKey("a".repeat(64), 1, "deepseek", "deepseek-v4-flash", 1, "A1", 1);

    @Test
    void zeroIsStillScoredAndNeverPublishable() {
        ScoreCalculator.Result hardGateZero = new ScoreCalculator.Result(
                0, false, true, ScoreCalculator.FailureClass.HARD_GATE_VIOLATION,
                List.of("runner:hard-gate"));
        FormalEpisodeOutcome.Scored outcome =
                new FormalEpisodeOutcome.Scored(KEY, hardGateZero);

        assertEquals(0, outcome.score());
        assertFalse(outcome.publishable());
    }

    @Test
    void infrastructureUnavailableCannotMasqueradeAsScored() {
        ScoreCalculator.Result unavailable = new ScoreCalculator.Result(
                null, false, false, ScoreCalculator.FailureClass.INFRA_UNAVAILABLE,
                List.of("judge:unavailable"));
        assertThrows(IllegalArgumentException.class,
                () -> new FormalEpisodeOutcome.Scored(KEY, unavailable));
        assertFalse(new FormalEpisodeOutcome.Infra(
                KEY, FormalEpisodeOutcome.InfraCode.JUDGE_UNAVAILABLE,
                unavailable.evidenceRefs()).publishable());
    }

    @Test
    void otherwiseIdenticalAttemptsBelongingToDifferentBatchesAreDistinct() {
        AttemptKey other = new AttemptKey("b".repeat(64), KEY.episodeOrdinal(),
                KEY.provider(), KEY.model(), KEY.repeat(), KEY.caseId(), KEY.attempt());
        org.junit.jupiter.api.Assertions.assertNotEquals(KEY, other);
        assertThrows(IllegalArgumentException.class, () -> new AttemptKey("", 1,
                KEY.provider(), KEY.model(), 1, KEY.caseId(), 1));
    }
}
