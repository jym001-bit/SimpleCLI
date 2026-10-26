package com.paicli.eval.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScoreAggregatorTest {

    @Test
    void aggregatesWeightedOverallAndL1L2L3Scores(@TempDir Path tempDir) {
        SuiteDefinition suite = suite(tempDir);

        ScoreAggregator.AggregateResult result = ScoreAggregator.aggregate(suite, List.of(
                ScoreAggregator.CaseScore.scored("l1", 100, true),
                ScoreAggregator.CaseScore.scored("l2", 50, false),
                ScoreAggregator.CaseScore.scored("l3", 75, true)));

        assertEquals(75.0, result.weightedScore(), 0.001);
        assertEquals(3, result.scoredCases());
        assertEquals(0, result.infraErrorCases());
        assertEquals(100.0, result.weightedCoveragePercent(), 0.001);
        assertEquals(200.0 / 3.0, result.strictSuccessPercent(), 0.001);
        assertEquals(70.0, result.weightedStrictSuccessPercent(), 0.001);
        assertFalse(result.strictSuccess());
        assertEquals(100.0, result.levels().get(CaseDefinition.Level.L1).weightedScore(), 0.001);
        assertEquals(50.0, result.levels().get(CaseDefinition.Level.L2).weightedScore(), 0.001);
        assertEquals(75.0, result.levels().get(CaseDefinition.Level.L3).weightedScore(), 0.001);
    }

    @Test
    void separatesInfraErrorsFromScoredDenominator(@TempDir Path tempDir) {
        SuiteDefinition suite = suite(tempDir);

        ScoreAggregator.AggregateResult result = ScoreAggregator.aggregate(suite, List.of(
                ScoreAggregator.CaseScore.scored("l1", 100, true),
                ScoreAggregator.CaseScore.infraError("l2", "provider 503"),
                ScoreAggregator.CaseScore.scored("l3", 50, false)));

        assertEquals(5000.0 / 70.0, result.weightedScore(), 0.001);
        assertEquals(1, result.infraErrorCases());
        assertEquals(70, result.scoredWeight());
        assertEquals(70.0, result.weightedCoveragePercent(), 0.001);
        assertFalse(result.strictSuccess());
        assertNull(result.levels().get(CaseDefinition.Level.L2).weightedScore());
        assertEquals(1, result.levels().get(CaseDefinition.Level.L2).infraErrorCases());
    }

    @Test
    void requiresExactlyOneResultForEveryActiveCase(@TempDir Path tempDir) {
        SuiteDefinition suite = suite(tempDir);

        assertThrows(IllegalArgumentException.class, () -> ScoreAggregator.aggregate(suite, List.of(
                ScoreAggregator.CaseScore.scored("l1", 100, true))));
        assertThrows(IllegalArgumentException.class, () -> ScoreAggregator.aggregate(suite, List.of(
                ScoreAggregator.CaseScore.scored("l1", 100, true),
                ScoreAggregator.CaseScore.scored("l1", 90, false),
                ScoreAggregator.CaseScore.scored("l3", 80, false))));
        assertThrows(IllegalArgumentException.class, () -> ScoreAggregator.aggregate(suite, List.of(
                ScoreAggregator.CaseScore.scored("l1", 100, true),
                ScoreAggregator.CaseScore.scored("l2", 90, false),
                ScoreAggregator.CaseScore.scored("unknown", 80, false))));
    }

    @Test
    void infraErrorCannotCarryScoreOrStrictSuccess(@TempDir Path tempDir) {
        SuiteDefinition suite = suite(tempDir);
        ScoreAggregator.CaseScore invalid = new ScoreAggregator.CaseScore(
                "l1", 99.0, true, ScoreAggregator.ResultStatus.INFRA_ERROR, "bad");

        assertThrows(IllegalArgumentException.class,
                () -> ScoreAggregator.aggregate(suite, List.of(
                        invalid,
                        ScoreAggregator.CaseScore.scored("l2", 90, true),
                        ScoreAggregator.CaseScore.scored("l3", 90, true))));
    }

    private static SuiteDefinition suite(Path root) {
        return new SuiteDefinition("1", "suite", List.of(
                definition("l1", CaseDefinition.Level.L1, 30),
                definition("l2", CaseDefinition.Level.L2, 30),
                definition("l3", CaseDefinition.Level.L3, 40)), root);
    }

    private static CaseDefinition definition(String id, CaseDefinition.Level level, int weight) {
        return new CaseDefinition(
                id,
                id,
                "coding",
                level,
                weight,
                CaseDefinition.Mode.REACT,
                "fixtures/" + id,
                "prompt",
                CaseDefinition.VerifierType.NONE,
                List.of(),
                CaseDefinition.Status.ACTIVE);
    }
}
