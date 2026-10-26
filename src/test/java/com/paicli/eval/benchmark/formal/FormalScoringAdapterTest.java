package com.paicli.eval.benchmark.formal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.eval.benchmark.scoring.ScoreCalculator;
import com.paicli.eval.benchmark.scoring.ScoreSource;
import com.paicli.eval.benchmark.scoring.ScoringContract;
import com.paicli.eval.benchmark.scoring.VerifierScoringReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class FormalScoringAdapterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void runnerHardGateCanOnlyOrIntoVerifierResult(@TempDir Path tempDir) throws Exception {
        FormalExecutionPlan plan = FormalA1TestPlan.create(tempDir.resolve("plan"));
        FormalExecutionPlan.CasePlan a1 = plan.cases().get(0);
        AttemptKey key = AttemptKey.from(plan, plan.episodes().get(0), 1);
        VerifierScoringReport report = report(a1.scoringContract(), 100, false);

        FormalEpisodeOutcome outcome = FormalScoringAdapter.adapt(
                key,
                a1,
                MAPPER.writeValueAsBytes(report),
                List.of(new FormalScoringAdapter.RunnerHardGateViolation(
                        "A1.hard_gate", List.of("runner:gate-observed"))),
                ScoreCalculator.JudgeAvailability.AVAILABLE);

        FormalEpisodeOutcome.Scored scored =
                assertInstanceOf(FormalEpisodeOutcome.Scored.class, outcome);
        assertEquals(0, scored.score());
        assertEquals(ScoreCalculator.FailureClass.HARD_GATE_VIOLATION,
                scored.result().failureClass());
    }

    @Test
    void unavailableRequiredJudgeIsInfraWithoutReweighting(@TempDir Path tempDir)
            throws Exception {
        FormalExecutionPlan plan = FormalA1TestPlan.create(tempDir.resolve("judge-plan"));
        FormalExecutionPlan.CasePlan original = plan.cases().get(0);
        ScoringContract base = original.scoringContract();
        ScoringContract withJudge = new ScoringContract(
                base.schemaVersion(), base.caseId(), base.strictSuccessMinimum(),
                base.assertions(), base.hardGates(),
                List.of(
                        new ScoringContract.ComponentRule(
                                base.components().get(0).id(), 50, ScoreSource.DETERMINISTIC),
                        new ScoringContract.ComponentRule("judge", 50, ScoreSource.JUDGE)),
                base.verifierSha256(), base.toolchainSha256());
        FormalExecutionPlan.CasePlan judgeCase = new FormalExecutionPlan.CasePlan(
                original.ordinal(), original.contract(), withJudge,
                original.scoringContractFile(), original.scoringContractSha256(),
                original.prompt(), original.promptSha256(), original.fixture(),
                original.verifier());
        VerifierScoringReport report = new VerifierScoringReport(
                VerifierScoringReport.CURRENT_SCHEMA_VERSION,
                withJudge.caseId(),
                List.of(new VerifierScoringReport.AssertionResult(
                        withJudge.assertions().get(0).id(), true, List.of("workspace:source"))),
                List.of(new VerifierScoringReport.HardGateResult(
                        withJudge.hardGates().get(0).id(), false, List.of("runner:no-gate"))),
                List.of(
                        new VerifierScoringReport.ComponentResult(
                                withJudge.components().get(0).id(), 50, 50,
                                ScoreSource.DETERMINISTIC, List.of("workspace:source")),
                        new VerifierScoringReport.ComponentResult(
                                "judge", 0, 50, ScoreSource.JUDGE,
                                List.of("judge:unavailable"))),
                withJudge.verifierSha256(), withJudge.toolchainSha256());

        FormalEpisodeOutcome outcome = FormalScoringAdapter.adapt(
                AttemptKey.from(plan, plan.episodes().get(0), 1), judgeCase,
                MAPPER.writeValueAsBytes(report),
                ScoreCalculator.JudgeAvailability.UNAVAILABLE);

        FormalEpisodeOutcome.Infra infra =
                assertInstanceOf(FormalEpisodeOutcome.Infra.class, outcome);
        assertEquals(FormalEpisodeOutcome.InfraCode.JUDGE_UNAVAILABLE, infra.code());
    }

    @Test
    void malformedOrUnknownRunnerGateIsEvaluationDefect(@TempDir Path tempDir)
            throws Exception {
        FormalExecutionPlan plan = FormalA1TestPlan.create(tempDir.resolve("invalid-plan"));
        FormalExecutionPlan.CasePlan a1 = plan.cases().get(0);
        AttemptKey key = AttemptKey.from(plan, plan.episodes().get(0), 1);

        FormalEpisodeOutcome malformed = FormalScoringAdapter.adapt(
                key, a1, new byte[]{'{', '}'}, ScoreCalculator.JudgeAvailability.AVAILABLE);
        assertEquals(FormalEpisodeOutcome.EvaluationDefectCode.INVALID_VERIFIER_REPORT,
                assertInstanceOf(FormalEpisodeOutcome.EvaluationDefect.class, malformed).code());

        FormalEpisodeOutcome unknownGate = FormalScoringAdapter.adapt(
                key, a1, MAPPER.writeValueAsBytes(report(a1.scoringContract(), 80, false)),
                List.of(new FormalScoringAdapter.RunnerHardGateViolation(
                        "unknown.gate", List.of("runner:unknown"))),
                ScoreCalculator.JudgeAvailability.AVAILABLE);
        assertEquals(FormalEpisodeOutcome.EvaluationDefectCode.INVALID_RUNNER_HARD_GATE,
                assertInstanceOf(FormalEpisodeOutcome.EvaluationDefect.class, unknownGate).code());
    }

    private static VerifierScoringReport report(
            ScoringContract contract, int points, boolean violated) {
        ScoringContract.ComponentRule component = contract.components().get(0);
        return new VerifierScoringReport(
                VerifierScoringReport.CURRENT_SCHEMA_VERSION,
                contract.caseId(),
                List.of(new VerifierScoringReport.AssertionResult(
                        contract.assertions().get(0).id(), points == component.maxPoints(),
                        List.of("workspace:source"))),
                List.of(new VerifierScoringReport.HardGateResult(
                        contract.hardGates().get(0).id(), violated,
                        List.of("runner:gate-check"))),
                List.of(new VerifierScoringReport.ComponentResult(
                        component.id(), points, component.maxPoints(), component.source(),
                        List.of("workspace:source"))),
                contract.verifierSha256(), contract.toolchainSha256());
    }
}
