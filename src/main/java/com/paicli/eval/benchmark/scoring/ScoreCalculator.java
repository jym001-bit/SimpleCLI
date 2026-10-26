package com.paicli.eval.benchmark.scoring;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Deterministically derives the only publishable case-level scoring fields. */
public final class ScoreCalculator {
    private ScoreCalculator() {
    }

    /**
     * Validates the verifier report against its frozen contract and calculates the case score.
     * A required unavailable judge is an infrastructure outcome with no component reweighting.
     */
    public static Result calculate(ScoringContract contract,
                                   VerifierScoringReport report,
                                   JudgeAvailability judgeAvailability) {
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(judgeAvailability, "judgeAvailability");
        validateBinding(contract, report);

        Map<String, ScoringContract.AssertionRule> assertionRules = byId(
                contract.assertions(), ScoringContract.AssertionRule::id);
        Map<String, VerifierScoringReport.AssertionResult> assertionResults = byId(
                report.assertions(), VerifierScoringReport.AssertionResult::id);
        Map<String, ScoringContract.ComponentRule> componentRules = byId(
                contract.components(), ScoringContract.ComponentRule::id);
        Map<String, VerifierScoringReport.ComponentResult> componentResults = byId(
                report.components(), VerifierScoringReport.ComponentResult::id);

        requireExactIds("assertions", assertionRules.keySet(), assertionResults.keySet());
        requireExactIds("hardGates",
                contract.hardGates().stream().map(ScoringContract.HardGateRule::id)
                        .collect(Collectors.toCollection(LinkedHashSet::new)),
                report.hardGates().stream().map(VerifierScoringReport.HardGateResult::id)
                        .collect(Collectors.toCollection(LinkedHashSet::new)));
        requireExactIds("components", componentRules.keySet(), componentResults.keySet());

        for (ScoringContract.ComponentRule rule : contract.components()) {
            VerifierScoringReport.ComponentResult result = componentResults.get(rule.id());
            if (result.maxPoints() != rule.maxPoints() || result.source() != rule.source()) {
                throw new IllegalArgumentException(
                        "component does not match frozen max/source: " + rule.id());
            }
        }
        for (ScoringContract.AssertionRule rule : contract.assertions()) {
            if (!rule.mandatory()) {
                continue;
            }
            VerifierScoringReport.AssertionResult assertion = assertionResults.get(rule.id());
            VerifierScoringReport.ComponentResult component = componentResults.get(rule.componentId());
            if (!assertion.pass() && component.earnedPoints() == component.maxPoints()) {
                throw new IllegalArgumentException(
                        "failed mandatory assertion cannot leave its component at full points: "
                                + rule.id());
            }
        }

        List<String> evidenceReferences = collectEvidence(report);
        if (contract.requiresJudge() && judgeAvailability == JudgeAvailability.UNAVAILABLE) {
            LinkedHashSet<String> unavailableEvidence = new LinkedHashSet<>(evidenceReferences);
            unavailableEvidence.add("judge:unavailable");
            return new Result(null, false, false, FailureClass.INFRA_UNAVAILABLE,
                    List.copyOf(unavailableEvidence));
        }

        boolean hardGate = report.hardGates().stream()
                .anyMatch(VerifierScoringReport.HardGateResult::violated);
        boolean allMandatoryAssertionsPass = contract.assertions().stream()
                .filter(ScoringContract.AssertionRule::mandatory)
                .allMatch(rule -> assertionResults.get(rule.id()).pass());
        int earned = report.components().stream()
                .mapToInt(VerifierScoringReport.ComponentResult::earnedPoints)
                .sum();
        int score = hardGate ? 0 : earned;
        boolean strictSuccess = !hardGate
                && allMandatoryAssertionsPass
                && score >= contract.strictSuccessMinimum();

        FailureClass failureClass;
        if (hardGate) {
            failureClass = FailureClass.HARD_GATE_VIOLATION;
        } else if (!allMandatoryAssertionsPass) {
            failureClass = FailureClass.ASSERTION_FAILURE;
        } else if (score < contract.strictSuccessMinimum()) {
            failureClass = FailureClass.SCORE_BELOW_THRESHOLD;
        } else {
            failureClass = FailureClass.NONE;
        }
        return new Result(score, strictSuccess, hardGate, failureClass, evidenceReferences);
    }

    private static void validateBinding(ScoringContract contract, VerifierScoringReport report) {
        if (!contract.caseId().equals(report.caseId())) {
            throw new IllegalArgumentException("report caseId does not match scoring contract");
        }
        if (!contract.verifierSha256().equals(report.verifierSha256())) {
            throw new IllegalArgumentException("report verifierSha256 does not match scoring contract");
        }
        if (!contract.toolchainSha256().equals(report.toolchainSha256())) {
            throw new IllegalArgumentException("report toolchainSha256 does not match scoring contract");
        }
    }

    private static void requireExactIds(String label, Set<String> expected, Set<String> actual) {
        if (!expected.equals(actual)) {
            Set<String> missing = new LinkedHashSet<>(expected);
            missing.removeAll(actual);
            Set<String> extra = new LinkedHashSet<>(actual);
            extra.removeAll(expected);
            throw new IllegalArgumentException(
                    label + " do not exactly match contract; missing=" + missing + ", extra=" + extra);
        }
    }

    private static <T> Map<String, T> byId(List<T> values, Function<T, String> idFunction) {
        Map<String, T> result = new LinkedHashMap<>();
        for (T value : values) {
            String id = idFunction.apply(value);
            if (result.put(id, value) != null) {
                throw new IllegalArgumentException("duplicate scoring id: " + id);
            }
        }
        return result;
    }

    private static List<String> collectEvidence(VerifierScoringReport report) {
        LinkedHashSet<String> references = new LinkedHashSet<>();
        for (VerifierScoringReport.AssertionResult assertion : report.assertions()) {
            references.addAll(assertion.evidenceRefs());
        }
        for (VerifierScoringReport.HardGateResult hardGate : report.hardGates()) {
            references.addAll(hardGate.evidenceRefs());
        }
        for (VerifierScoringReport.ComponentResult component : report.components()) {
            references.addAll(component.evidenceRefs());
        }
        return List.copyOf(references);
    }

    public enum JudgeAvailability {
        AVAILABLE,
        UNAVAILABLE
    }

    public enum FailureClass {
        NONE,
        ASSERTION_FAILURE,
        SCORE_BELOW_THRESHOLD,
        HARD_GATE_VIOLATION,
        INFRA_UNAVAILABLE
    }

    /** Derived result only; no candidate- or judge-supplied total is accepted. */
    public record Result(Integer score,
                         boolean strictSuccess,
                         boolean hardGate,
                         FailureClass failureClass,
                         List<String> evidenceRefs) {
        public Result {
            Objects.requireNonNull(failureClass, "failureClass");
            evidenceRefs = ScoringValidation.nonEmptyCopy(evidenceRefs, "evidenceRefs");
            if (failureClass == FailureClass.INFRA_UNAVAILABLE) {
                if (score != null || strictSuccess || hardGate) {
                    throw new IllegalArgumentException(
                            "INFRA_UNAVAILABLE must be explicitly unscored and not a hard gate");
                }
            } else {
                if (score == null || score < 0 || score > ScoringContract.TOTAL_POINTS) {
                    throw new IllegalArgumentException("scored result must have a score between 0 and 100");
                }
                if (failureClass == FailureClass.NONE && (!strictSuccess || hardGate)) {
                    throw new IllegalArgumentException("NONE failureClass requires strict success without hard gate");
                }
                if (failureClass != FailureClass.NONE && strictSuccess) {
                    throw new IllegalArgumentException("strict success cannot carry a failure class");
                }
                if (hardGate && (score != 0 || failureClass != FailureClass.HARD_GATE_VIOLATION)) {
                    throw new IllegalArgumentException("hard gate must force score 0 and HARD_GATE_VIOLATION");
                }
                if (!hardGate && failureClass == FailureClass.HARD_GATE_VIOLATION) {
                    throw new IllegalArgumentException(
                            "HARD_GATE_VIOLATION requires hardGate=true");
                }
            }
        }

        /** Prevents infrastructure-unavailable outcomes from entering numeric aggregation. */
        public int scoreForAggregation() {
            if (score == null) {
                throw new IllegalStateException("INFRA_UNAVAILABLE result has no numeric score");
            }
            return score;
        }
    }
}
