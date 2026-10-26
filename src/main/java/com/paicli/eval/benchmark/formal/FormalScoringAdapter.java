package com.paicli.eval.benchmark.formal;

import com.paicli.eval.benchmark.scoring.ScoreCalculator;
import com.paicli.eval.benchmark.scoring.ScoringContract;
import com.paicli.eval.benchmark.scoring.VerifierScoringReport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Converts bounded verifier-report bytes into the closed formal episode outcome taxonomy. */
public final class FormalScoringAdapter {
    private FormalScoringAdapter() {
    }

    /**
     * Parses and scores one report against the scoring object already retained in the case plan.
     *
     * <p>Runner hard gates are represented only as positive violations. They are OR-ed into the
     * verifier report and therefore can never clear a verifier-observed violation. A required but
     * unavailable judge remains unscored; component points are never re-normalized.</p>
     */
    public static FormalEpisodeOutcome adapt(
            AttemptKey key,
            FormalExecutionPlan.CasePlan casePlan,
            byte[] reportBytes,
            List<RunnerHardGateViolation> runnerViolations,
            ScoreCalculator.JudgeAvailability judgeAvailability) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(casePlan, "casePlan");
        Objects.requireNonNull(judgeAvailability, "judgeAvailability");
        if (!key.caseId().equals(casePlan.id())) {
            return defect(key, FormalEpisodeOutcome.EvaluationDefectCode.INVALID_VERIFIER_REPORT,
                    "verifier:case-binding-invalid");
        }

        final VerifierScoringReport parsed;
        try {
            parsed = VerifierScoringReport.parse(reportBytes);
        } catch (IOException | IllegalArgumentException error) {
            return defect(key, FormalEpisodeOutcome.EvaluationDefectCode.INVALID_VERIFIER_REPORT,
                    "verifier:report-invalid");
        }

        final VerifierScoringReport merged;
        try {
            merged = mergeRunnerViolations(
                    casePlan.scoringContract(), parsed, runnerViolations);
        } catch (RunnerGateException error) {
            return defect(key,
                    FormalEpisodeOutcome.EvaluationDefectCode.INVALID_RUNNER_HARD_GATE,
                    "runner:hard-gate-invalid");
        } catch (IllegalArgumentException error) {
            return defect(key, FormalEpisodeOutcome.EvaluationDefectCode.INVALID_VERIFIER_REPORT,
                    "verifier:report-invalid");
        }

        final ScoreCalculator.Result result;
        try {
            result = ScoreCalculator.calculate(
                    casePlan.scoringContract(), merged, judgeAvailability);
        } catch (IllegalArgumentException error) {
            return defect(key, FormalEpisodeOutcome.EvaluationDefectCode.INVALID_VERIFIER_REPORT,
                    "verifier:report-contract-mismatch");
        }
        if (result.failureClass() == ScoreCalculator.FailureClass.INFRA_UNAVAILABLE) {
            return new FormalEpisodeOutcome.Infra(
                    key,
                    FormalEpisodeOutcome.InfraCode.JUDGE_UNAVAILABLE,
                    result.evidenceRefs());
        }
        // A hard-gated or otherwise valid score of zero remains a genuine scored result.
        return new FormalEpisodeOutcome.Scored(key, result);
    }

    public static FormalEpisodeOutcome adapt(
            AttemptKey key,
            FormalExecutionPlan.CasePlan casePlan,
            byte[] reportBytes,
            ScoreCalculator.JudgeAvailability judgeAvailability) {
        return adapt(key, casePlan, reportBytes, List.of(), judgeAvailability);
    }

    private static VerifierScoringReport mergeRunnerViolations(
            ScoringContract contract,
            VerifierScoringReport report,
            List<RunnerHardGateViolation> rawViolations) {
        List<RunnerHardGateViolation> violations;
        try {
            violations = rawViolations == null ? null : List.copyOf(rawViolations);
        } catch (NullPointerException error) {
            throw new RunnerGateException();
        }
        if (violations == null) {
            throw new RunnerGateException();
        }
        Set<String> registered = new LinkedHashSet<>();
        for (ScoringContract.HardGateRule rule : contract.hardGates()) {
            registered.add(rule.id());
        }
        Map<String, RunnerHardGateViolation> byId = new LinkedHashMap<>();
        for (RunnerHardGateViolation violation : violations) {
            if (violation == null || !registered.contains(violation.id())
                    || byId.put(violation.id(), violation) != null) {
                throw new RunnerGateException();
            }
        }

        List<VerifierScoringReport.HardGateResult> merged = new ArrayList<>(
                report.hardGates().size());
        for (VerifierScoringReport.HardGateResult verifierGate : report.hardGates()) {
            RunnerHardGateViolation runnerGate = byId.get(verifierGate.id());
            if (runnerGate == null) {
                merged.add(verifierGate);
                continue;
            }
            LinkedHashSet<String> evidence = new LinkedHashSet<>(verifierGate.evidenceRefs());
            evidence.addAll(runnerGate.evidenceRefs());
            merged.add(new VerifierScoringReport.HardGateResult(
                    verifierGate.id(),
                    true,
                    List.copyOf(evidence)));
        }
        return new VerifierScoringReport(
                report.schemaVersion(),
                report.caseId(),
                report.assertions(),
                merged,
                report.components(),
                report.verifierSha256(),
                report.toolchainSha256());
    }

    private static FormalEpisodeOutcome.EvaluationDefect defect(
            AttemptKey key,
            FormalEpisodeOutcome.EvaluationDefectCode code,
            String evidence) {
        return new FormalEpisodeOutcome.EvaluationDefect(key, code, List.of(evidence));
    }

    /** A runner-owned hard-gate observation. This type cannot represent a clearing value. */
    public record RunnerHardGateViolation(String id, List<String> evidenceRefs) {
        public RunnerHardGateViolation {
            // Reuse the strict report schema validation for identifier and evidence syntax.
            VerifierScoringReport.HardGateResult validated =
                    new VerifierScoringReport.HardGateResult(id, true, evidenceRefs);
            id = validated.id();
            evidenceRefs = validated.evidenceRefs();
        }
    }

    @SuppressWarnings("serial") // Private in-process validation sentinel.
    private static final class RunnerGateException extends IllegalArgumentException {
        private RunnerGateException() {
            super("invalid runner hard gate");
        }
    }
}
