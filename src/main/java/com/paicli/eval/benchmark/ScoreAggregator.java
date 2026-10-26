package com.paicli.eval.benchmark;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministically aggregates one complete model/repeat result for a suite. */
public final class ScoreAggregator {
    private ScoreAggregator() {
    }

    public static AggregateResult aggregate(SuiteDefinition suite, List<CaseScore> results) {
        if (suite == null) {
            throw new IllegalArgumentException("suite must not be null");
        }
        if (results == null) {
            throw new IllegalArgumentException("results must not be null");
        }

        List<CaseDefinition> activeCases = suite.activeCases();
        Map<String, CaseDefinition> definitions = new LinkedHashMap<>();
        for (CaseDefinition definition : activeCases) {
            definitions.put(definition.id(), definition);
        }

        Map<String, CaseScore> resultByCase = new HashMap<>();
        for (CaseScore result : results) {
            validateResult(result);
            if (!definitions.containsKey(result.caseId())) {
                throw new IllegalArgumentException("result references an unknown or inactive case: " + result.caseId());
            }
            if (resultByCase.put(result.caseId(), result) != null) {
                throw new IllegalArgumentException("duplicate result for case: " + result.caseId());
            }
        }
        if (resultByCase.size() != definitions.size()) {
            List<String> missing = definitions.keySet().stream()
                    .filter(id -> !resultByCase.containsKey(id))
                    .toList();
            throw new IllegalArgumentException("missing results for active cases: " + missing);
        }

        MutableSummary overall = new MutableSummary();
        EnumMap<CaseDefinition.Level, MutableSummary> byLevel =
                new EnumMap<>(CaseDefinition.Level.class);
        for (CaseDefinition.Level level : CaseDefinition.Level.values()) {
            byLevel.put(level, new MutableSummary());
        }

        for (CaseDefinition definition : activeCases) {
            CaseScore result = resultByCase.get(definition.id());
            overall.add(definition, result);
            byLevel.get(definition.level()).add(definition, result);
        }

        EnumMap<CaseDefinition.Level, LevelSummary> levels =
                new EnumMap<>(CaseDefinition.Level.class);
        for (CaseDefinition.Level level : CaseDefinition.Level.values()) {
            levels.put(level, byLevel.get(level).finishLevel(level));
        }
        return overall.finish(Collections.unmodifiableMap(levels));
    }

    private static void validateResult(CaseScore result) {
        if (result == null || result.caseId() == null || result.caseId().isBlank()) {
            throw new IllegalArgumentException("case result id must not be blank");
        }
        if (result.status() == null) {
            throw new IllegalArgumentException("case result status must not be null: " + result.caseId());
        }
        if (result.status() == ResultStatus.INFRA_ERROR) {
            if (result.score() != null) {
                throw new IllegalArgumentException("infra errors must not carry a score: " + result.caseId());
            }
            if (result.strictSuccess()) {
                throw new IllegalArgumentException("infra errors cannot be strict successes: " + result.caseId());
            }
            return;
        }
        if (result.score() == null || !Double.isFinite(result.score())
                || result.score() < 0 || result.score() > 100) {
            throw new IllegalArgumentException("scored results must be between 0 and 100: " + result.caseId());
        }
    }

    public enum ResultStatus {
        SCORED,
        INFRA_ERROR
    }

    public record CaseScore(String caseId,
                            Double score,
                            boolean strictSuccess,
                            ResultStatus status,
                            String detail) {
        public static CaseScore scored(String caseId, double score, boolean strictSuccess) {
            return new CaseScore(caseId, score, strictSuccess, ResultStatus.SCORED, "");
        }

        public static CaseScore infraError(String caseId, String detail) {
            return new CaseScore(caseId, null, false, ResultStatus.INFRA_ERROR,
                    detail == null ? "" : detail);
        }
    }

    public record AggregateResult(Double weightedScore,
                                  int totalCases,
                                  int scoredCases,
                                  int infraErrorCases,
                                  int configuredWeight,
                                  int scoredWeight,
                                  double weightedCoveragePercent,
                                  int strictSuccessCases,
                                  double strictSuccessPercent,
                                  int strictSuccessWeight,
                                  double weightedStrictSuccessPercent,
                                  boolean strictSuccess,
                                  Map<CaseDefinition.Level, LevelSummary> levels) {
    }

    public record LevelSummary(CaseDefinition.Level level,
                               Double weightedScore,
                               int totalCases,
                               int scoredCases,
                               int infraErrorCases,
                               int configuredWeight,
                               int scoredWeight,
                               double weightedCoveragePercent,
                               int strictSuccessCases,
                               double strictSuccessPercent,
                               int strictSuccessWeight,
                               double weightedStrictSuccessPercent,
                               boolean strictSuccess) {
    }

    private static final class MutableSummary {
        private int totalCases;
        private int scoredCases;
        private int infraErrors;
        private int configuredWeight;
        private int scoredWeight;
        private double weightedScoreSum;
        private int strictCases;
        private int strictWeight;

        void add(CaseDefinition definition, CaseScore result) {
            totalCases++;
            configuredWeight += definition.weight();
            if (result.status() == ResultStatus.INFRA_ERROR) {
                infraErrors++;
                return;
            }
            scoredCases++;
            scoredWeight += definition.weight();
            weightedScoreSum += result.score() * definition.weight();
            if (result.strictSuccess()) {
                strictCases++;
                strictWeight += definition.weight();
            }
        }

        AggregateResult finish(Map<CaseDefinition.Level, LevelSummary> levels) {
            return new AggregateResult(
                    score(),
                    totalCases,
                    scoredCases,
                    infraErrors,
                    configuredWeight,
                    scoredWeight,
                    percent(scoredWeight, configuredWeight),
                    strictCases,
                    percent(strictCases, totalCases),
                    strictWeight,
                    percent(strictWeight, configuredWeight),
                    totalCases > 0 && infraErrors == 0 && strictCases == totalCases,
                    levels);
        }

        LevelSummary finishLevel(CaseDefinition.Level level) {
            return new LevelSummary(
                    level,
                    score(),
                    totalCases,
                    scoredCases,
                    infraErrors,
                    configuredWeight,
                    scoredWeight,
                    percent(scoredWeight, configuredWeight),
                    strictCases,
                    percent(strictCases, totalCases),
                    strictWeight,
                    percent(strictWeight, configuredWeight),
                    totalCases > 0 && infraErrors == 0 && strictCases == totalCases);
        }

        private Double score() {
            return scoredWeight == 0 ? null : weightedScoreSum / scoredWeight;
        }

        private static double percent(int numerator, int denominator) {
            return denominator == 0 ? 0.0 : numerator * 100.0 / denominator;
        }
    }
}
