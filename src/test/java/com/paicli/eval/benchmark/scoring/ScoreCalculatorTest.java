package com.paicli.eval.benchmark.scoring;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.paicli.eval.benchmark.scoring.ScoreCalculator.FailureClass.ASSERTION_FAILURE;
import static com.paicli.eval.benchmark.scoring.ScoreCalculator.FailureClass.HARD_GATE_VIOLATION;
import static com.paicli.eval.benchmark.scoring.ScoreCalculator.FailureClass.INFRA_UNAVAILABLE;
import static com.paicli.eval.benchmark.scoring.ScoreCalculator.FailureClass.NONE;
import static com.paicli.eval.benchmark.scoring.ScoreCalculator.JudgeAvailability.AVAILABLE;
import static com.paicli.eval.benchmark.scoring.ScoreCalculator.JudgeAvailability.UNAVAILABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoreCalculatorTest {
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);

    @Test
    void derivesNumericScoreAndStrictSuccessWithoutTrustingAReportedTotal() {
        ScoreCalculator.Result result = ScoreCalculator.calculate(contract(), report(), AVAILABLE);

        assertEquals(95, result.score());
        assertTrue(result.strictSuccess());
        assertFalse(result.hardGate());
        assertEquals(NONE, result.failureClass());
        assertEquals(List.of(
                "assertion:correct", "judge:calibrated", "audit:safe",
                "component:facts", "component:judge"), result.evidenceRefs());
    }

    @Test
    void anyHardGateForcesEffectiveScoreToZero() {
        VerifierScoringReport original = report();
        VerifierScoringReport gated = copy(original, original.assertions(),
                List.of(new VerifierScoringReport.HardGateResult(
                        "gate.safe", true, List.of("audit:violation"))), original.components());

        ScoreCalculator.Result result = ScoreCalculator.calculate(contract(), gated, AVAILABLE);
        assertEquals(0, result.score());
        assertEquals(0, result.scoreForAggregation());
        assertFalse(result.strictSuccess());
        assertTrue(result.hardGate());
        assertEquals(HARD_GATE_VIOLATION, result.failureClass());
    }

    @Test
    void failedMandatoryAssertionCannotRetainFullMappedComponent() {
        VerifierScoringReport original = report();
        List<VerifierScoringReport.AssertionResult> failedAssertions = List.of(
                new VerifierScoringReport.AssertionResult(
                        "assert.correct", false, List.of("assertion:failed")),
                original.assertions().get(1));
        assertThrows(IllegalArgumentException.class, () -> ScoreCalculator.calculate(
                contract(), copy(original, failedAssertions, original.hardGates(), original.components()),
                AVAILABLE));

        List<VerifierScoringReport.ComponentResult> reduced = List.of(
                new VerifierScoringReport.ComponentResult(
                        "facts", 69, 70, ScoreSource.DETERMINISTIC, List.of("component:facts")),
                original.components().get(1));
        ScoreCalculator.Result result = ScoreCalculator.calculate(
                contract(), copy(original, failedAssertions, original.hardGates(), reduced), AVAILABLE);
        assertEquals(94, result.score());
        assertFalse(result.strictSuccess());
        assertEquals(ASSERTION_FAILURE, result.failureClass());
    }

    @Test
    void requiredUnavailableJudgeReturnsTypedInfraWithoutReweighting() {
        ScoreCalculator.Result result = ScoreCalculator.calculate(contract(), report(), UNAVAILABLE);

        assertNull(result.score());
        assertFalse(result.strictSuccess());
        assertFalse(result.hardGate());
        assertEquals(INFRA_UNAVAILABLE, result.failureClass());
        assertTrue(result.evidenceRefs().contains("judge:unavailable"));
        assertThrows(IllegalStateException.class, result::scoreForAggregation);
    }

    @Test
    void availableJudgeZeroIsNotRenormalizedOntoDeterministicPoints() {
        VerifierScoringReport original = report();
        List<VerifierScoringReport.ComponentResult> components = List.of(
                original.components().get(0),
                new VerifierScoringReport.ComponentResult(
                        "judge", 0, 30, ScoreSource.JUDGE, List.of("judge:zero")));
        VerifierScoringReport scored = copy(original, original.assertions(),
                original.hardGates(), components);

        ScoreCalculator.Result result = ScoreCalculator.calculate(contract(), scored, AVAILABLE);
        assertEquals(70, result.score());
        assertFalse(result.strictSuccess());
    }

    @Test
    void rejectsMissingExtraAndMismatchedContractBindings() {
        VerifierScoringReport valid = report();
        assertThrows(IllegalArgumentException.class, () -> ScoreCalculator.calculate(
                contract(), copy(valid, List.of(valid.assertions().get(0)),
                        valid.hardGates(), valid.components()), AVAILABLE));

        List<VerifierScoringReport.AssertionResult> extra = new ArrayList<>(valid.assertions());
        extra.add(new VerifierScoringReport.AssertionResult(
                "assert.extra", true, List.of("assertion:extra")));
        assertThrows(IllegalArgumentException.class, () -> ScoreCalculator.calculate(
                contract(), copy(valid, extra, valid.hardGates(), valid.components()), AVAILABLE));

        List<VerifierScoringReport.ComponentResult> wrongSource = List.of(
                new VerifierScoringReport.ComponentResult(
                        "facts", 70, 70, ScoreSource.JUDGE, List.of("component:facts")),
                valid.components().get(1));
        assertThrows(IllegalArgumentException.class, () -> ScoreCalculator.calculate(
                contract(), copy(valid, valid.assertions(), valid.hardGates(), wrongSource), AVAILABLE));

        VerifierScoringReport wrongDigest = new VerifierScoringReport(
                1, "A1", valid.assertions(), valid.hardGates(), valid.components(),
                "c".repeat(64), SHA_B);
        assertThrows(IllegalArgumentException.class,
                () -> ScoreCalculator.calculate(contract(), wrongDigest, AVAILABLE));
    }

    private static ScoringContract contract() {
        return new ScoringContract(
                1, "A1", 80,
                List.of(
                        new ScoringContract.AssertionRule("assert.correct", "facts", true),
                        new ScoringContract.AssertionRule("assert.semantic", "judge", true)),
                List.of(new ScoringContract.HardGateRule("gate.safe")),
                List.of(
                        new ScoringContract.ComponentRule("facts", 70, ScoreSource.DETERMINISTIC),
                        new ScoringContract.ComponentRule("judge", 30, ScoreSource.JUDGE)),
                SHA_A, SHA_B);
    }

    private static VerifierScoringReport report() {
        return VerifierScoringReportTest.report();
    }

    private static VerifierScoringReport copy(
            VerifierScoringReport source,
            List<VerifierScoringReport.AssertionResult> assertions,
            List<VerifierScoringReport.HardGateResult> hardGates,
            List<VerifierScoringReport.ComponentResult> components) {
        return new VerifierScoringReport(
                source.schemaVersion(), source.caseId(), assertions, hardGates, components,
                source.verifierSha256(), source.toolchainSha256());
    }
}
