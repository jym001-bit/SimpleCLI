package com.paicli.eval.benchmark.formal;

import com.paicli.eval.benchmark.scoring.ScoringContract;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Test-only adapter that gives the existing 28/252 synthetic preflight fixture its real A1 id. */
public final class FormalA1TestPlan {
    private FormalA1TestPlan() {
    }

    public static FormalExecutionPlan create(Path base) throws Exception {
        FormalBenchmarkPreflightTest.Fixture fixture =
                FormalBenchmarkPreflightTest.Fixture.create(base);
        FormalExecutionPlan source = FormalExecutionPlan.from(
                fixture.verify(fixture.preflight()));

        FormalExecutionPlan.CasePlan original = source.cases().get(0);
        ScoringContract originalScoring = original.scoringContract();
        ScoringContract a1Scoring = new ScoringContract(
                originalScoring.schemaVersion(),
                "A1",
                originalScoring.strictSuccessMinimum(),
                List.of(new ScoringContract.AssertionRule(
                        "A1.assertion",
                        originalScoring.assertions().get(0).componentId(),
                        true)),
                List.of(new ScoringContract.HardGateRule("A1.hard_gate")),
                originalScoring.components(),
                originalScoring.verifierSha256(),
                originalScoring.toolchainSha256());

        FinalExecutableSuiteContract.CaseContract oldContract = original.contract();
        FinalExecutableSuiteContract.CaseContract a1Contract =
                new FinalExecutableSuiteContract.CaseContract(
                        "A1",
                        oldContract.category(),
                        oldContract.level(),
                        oldContract.weight(),
                        oldContract.mode(),
                        oldContract.toolProfile(),
                        oldContract.timeoutSeconds(),
                        oldContract.tokenBudget(),
                        oldContract.hardMaxIterations(),
                        oldContract.stagnationWindow(),
                        oldContract.mockProfile(),
                        oldContract.episodeEvents(),
                        List.of("A1.assertion"),
                        List.of("A1.hard_gate"),
                        oldContract.evidenceRequirements(),
                        oldContract.scoringContractPath(),
                        oldContract.scoringContractSha256(),
                        oldContract.verifierEntryPath(),
                        oldContract.verifierSha256(),
                        oldContract.verifierDependencyPaths(),
                        oldContract.verifierBundleSha256());
        FormalExecutionPlan.CasePlan a1Case = new FormalExecutionPlan.CasePlan(
                original.ordinal(),
                a1Contract,
                a1Scoring,
                original.scoringContractFile(),
                original.scoringContractSha256(),
                original.prompt(),
                original.promptSha256(),
                original.fixture(),
                original.verifier());

        List<FormalExecutionPlan.CasePlan> cases = new ArrayList<>(source.cases());
        cases.set(0, a1Case);
        List<FinalExecutableSuiteContract.CaseContract> contracts = cases.stream()
                .map(FormalExecutionPlan.CasePlan::contract)
                .toList();
        FinalExecutableSuiteContract oldSuite = source.executableSuiteContract();
        FinalExecutableSuiteContract suite = new FinalExecutableSuiteContract(
                oldSuite.contractVersion(), oldSuite.format(), oldSuite.suiteId(),
                oldSuite.suiteVersion(), oldSuite.blueprintPath(), oldSuite.blueprintSha256(),
                oldSuite.suitePath(), oldSuite.suiteSha256(), contracts);

        FormalBatchContract oldBatch = source.formalBatchContract();
        FormalBatchContract batch = new FormalBatchContract(
                oldBatch.contractVersion(), oldBatch.format(),
                oldBatch.executableSuiteContractSha256(), oldBatch.freezeManifestSha256(),
                oldBatch.contentTreeSha256(), oldBatch.suiteSha256(),
                oldBatch.validatorTreeSha256(), oldBatch.candidateJarSha256(),
                oldBatch.candidateCommit(), oldBatch.runnerJarSha256(),
                oldBatch.runnerInventorySha256(), oldBatch.runnerCommit(),
                oldBatch.workerImageId(), oldBatch.verifierImageId(),
                oldBatch.dockerExecutablePath(), oldBatch.dockerExecutableSha256(),
                oldBatch.models(), oldBatch.repeats(),
                contracts.stream().map(FinalExecutableSuiteContract.CaseContract::id).toList(),
                oldBatch.timezone(), oldBatch.runtimeDate(),
                oldBatch.commonContextCapTokens(), oldBatch.maxOutputTokensPerCall(),
                oldBatch.invalidRunPolicy(),
                oldBatch.bestOfN(), oldBatch.dirty());

        List<FormalExecutionPlan.EpisodePlan> episodes = new ArrayList<>(252);
        int ordinal = 1;
        for (int modelIndex = 0; modelIndex < batch.models().size(); modelIndex++) {
            for (int repeat = 1; repeat <= batch.repeats(); repeat++) {
                for (FormalExecutionPlan.CasePlan casePlan : cases) {
                    episodes.add(new FormalExecutionPlan.EpisodePlan(
                            ordinal++, modelIndex + 1, batch.models().get(modelIndex), repeat,
                            casePlan.ordinal(), casePlan));
                }
            }
        }
        return new FormalExecutionPlan(
                source.planVersion(), source.format(), source.executionOrder(), false,
                source.freezeManifest(), suite, batch, source.artifacts(), cases, episodes);
    }
}
