package com.paicli.eval.benchmark.formal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormalExecutionPlanTest {
    @Test
    void buildsCompleteImmutable252EpisodePlanWithoutReloadingSuite(@TempDir Path tempDir)
            throws Exception {
        FormalBenchmarkPreflightTest.Fixture fixture =
                FormalBenchmarkPreflightTest.Fixture.create(
                        tempDir.toRealPath().resolve("execution-plan"));
        FormalBenchmarkPreflight.VerifiedPreflight verified =
                fixture.verify(fixture.preflight());

        // Admission already captured every execution-affecting suite value. If planning tried to
        // reload this mutable path, the replacement bytes below would make construction fail or
        // change the plan.
        Files.writeString(verified.suiteFile(), "replaced after successful preflight\n");
        Files.writeString(verified.blueprintFile(), "replaced blueprint after preflight\n");
        Files.writeString(verified.cases().get(0).scoringContractFile(),
                "replaced scoring contract after preflight\n");
        Files.writeString(verified.cases().get(0).verifierDependencies().get(1).canonicalPath(),
                "replaced verifier helper after preflight\n");
        FormalExecutionPlan plan = FormalExecutionPlan.from(verified);

        assertEquals(FormalExecutionPlan.EXECUTION_ORDER, plan.executionOrder());
        assertFalse(plan.publishable());
        assertEquals(28, plan.cases().size());
        assertEquals(252, plan.episodes().size());
        assertSame(verified.freezeManifest(), plan.freezeManifest());
        assertSame(verified.executableSuiteContract(), plan.executableSuiteContract());
        assertSame(verified.formalBatchContract(), plan.formalBatchContract());
        assertEquals(verified.commonContextCapTokens(),
                plan.formalBatchContract().commonContextCapTokens());
        assertEquals(verified.maxOutputTokensPerCall(),
                plan.formalBatchContract().maxOutputTokensPerCall());
        assertEquals(verified.runnerInventorySha256(),
                plan.artifacts().runnerInventorySha256());
        assertEquals(verified.blueprintFile(), plan.artifacts().blueprintFile());
        assertEquals(verified.blueprintSha256(), plan.artifacts().blueprintSha256());
        assertEquals("blueprint.json", plan.executableSuiteContract().blueprintPath());

        FormalExecutionPlan.CasePlan first = plan.cases().get(0);
        FinalExecutableSuiteContract.CaseContract contract =
                verified.executableSuiteContract().cases().get(0);
        assertEquals(contract, first.contract());
        assertEquals(contract.mode(), first.mode());
        assertEquals(contract.toolProfile(), first.toolProfile());
        assertEquals(contract.timeoutSeconds(), first.timeoutSeconds());
        assertEquals(contract.tokenBudget(), first.tokenBudget());
        assertEquals(contract.hardMaxIterations(), first.hardMaxIterations());
        assertEquals(contract.stagnationWindow(), first.stagnationWindow());
        assertEquals(contract.mockProfile(), first.mockProfile());
        assertEquals(contract.episodeEvents(), first.episodeEvents());
        assertEquals(contract.mandatoryAssertionIds(), first.mandatoryAssertionIds());
        assertEquals(contract.hardGateIds(), first.hardGateIds());
        assertEquals(contract.evidenceRequirements(), first.evidenceRequirements());
        assertEquals(contract.scoringContractPath(), first.scoringContractPath());
        assertEquals(contract.scoringContractSha256(), first.scoringContractSha256());
        assertEquals(verified.cases().get(0).scoringContractFile(),
                first.scoringContractFile());
        assertSame(verified.cases().get(0).scoringContract(), first.scoringContract());
        assertEquals(contract.id(), first.scoringContract().caseId());
        assertEquals(contract.verifierSha256(), first.scoringContract().verifierSha256());
        assertEquals("d".repeat(64), first.toolchainSha256());
        assertFalse(first.toolchainSha256().equals(plan.artifacts().verifierImageId()));
        assertEquals("Complete the registered task for case-01", first.prompt());
        assertEquals("fixtures/shared.txt", first.fixture().frozenPath());
        assertEquals(1, first.fixture().fileCount());
        assertEquals(FormalBenchmarkPreflight.FixtureKind.DIRECTORY,
                plan.cases().get(1).fixture().kind());
        assertEquals("fixtures", plan.cases().get(1).fixture().frozenPath());
        assertEquals(1, plan.cases().get(1).fixture().fileCount());
        assertEquals(List.of(
                        "validators/final/case-01.sh", "{workspace}", "{evidence}"),
                first.verifier().registeredArguments());
        assertEquals(contract.verifierDependencyPaths(),
                first.verifier().dependencies().stream()
                        .map(FormalExecutionPlan.VerifierDependency::frozenPath)
                        .toList());
        assertEquals(contract.verifierBundleSha256(), first.verifier().bundleSha256());
        assertEquals(2, first.verifier().dependencies().size());

        Path workspace = tempDir.resolve("episode/workspace").toAbsolutePath().normalize();
        Path evidence = tempDir.resolve("episode/evidence.json").toAbsolutePath().normalize();
        FormalExecutionPlan.MaterializedVerifier materialized =
                first.verifier().materialize(workspace, evidence);
        assertEquals(List.of(
                        "validators/final/case-01.sh", workspace.toString(), evidence.toString()),
                materialized.arguments());
        assertTrue(first.verifier().requiresEvidence());

        assertEpisode(plan.episodes().get(0), 1, 1,
                "deepseek", "deepseek-v4-flash", 1, 1, "case-01");
        assertEpisode(plan.episodes().get(27), 28, 1,
                "deepseek", "deepseek-v4-flash", 1, 28, "case-28");
        assertEpisode(plan.episodes().get(28), 29, 1,
                "deepseek", "deepseek-v4-flash", 2, 1, "case-01");
        assertEpisode(plan.episodes().get(84), 85, 2,
                "hunyuan", "hy4-preview", 1, 1, "case-01");
        assertEpisode(plan.episodes().get(168), 169, 3,
                "glm", "glm-5.3-flash", 1, 1, "case-01");
        assertEpisode(plan.episodes().get(251), 252, 3,
                "glm", "glm-5.3-flash", 3, 28, "case-28");

        Set<String> identities = new HashSet<>();
        for (FormalExecutionPlan.EpisodePlan episode : plan.episodes()) {
            identities.add(episode.model().provider() + "/" + episode.model().model()
                    + ":" + episode.repeat() + ":" + episode.caseId());
        }
        assertEquals(252, identities.size());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.cases().add(first));
        assertThrows(UnsupportedOperationException.class,
                () -> first.verifier().registeredArguments().add("unexpected"));
        assertThrows(UnsupportedOperationException.class,
                () -> first.verifier().dependencies().add(
                        first.verifier().dependencies().get(0)));

        String rendered = plan + " " + first + " " + first.fixture() + " " + first.verifier();
        assertFalse(rendered.contains(fixture.base().toString()));
        assertTrue(rendered.contains("publishable=false"));
    }

    @Test
    void rejectsPublishableOrReorderedEpisodePlan(@TempDir Path tempDir) throws Exception {
        FormalBenchmarkPreflightTest.Fixture fixture =
                FormalBenchmarkPreflightTest.Fixture.create(
                        tempDir.toRealPath().resolve("tamper"));
        FormalExecutionPlan valid = FormalExecutionPlan.from(
                fixture.verify(fixture.preflight()));

        assertThrows(IllegalArgumentException.class, () -> copy(valid, true, valid.episodes()));

        List<FormalExecutionPlan.EpisodePlan> reordered = new ArrayList<>(valid.episodes());
        java.util.Collections.swap(reordered, 0, 1);
        assertThrows(IllegalArgumentException.class, () -> copy(valid, false, reordered));
    }

    private static FormalExecutionPlan copy(
            FormalExecutionPlan source,
            boolean publishable,
            List<FormalExecutionPlan.EpisodePlan> episodes) {
        return new FormalExecutionPlan(
                source.planVersion(),
                source.format(),
                source.executionOrder(),
                publishable,
                source.freezeManifest(),
                source.executableSuiteContract(),
                source.formalBatchContract(),
                source.artifacts(),
                source.cases(),
                episodes);
    }

    private static void assertEpisode(
            FormalExecutionPlan.EpisodePlan episode,
            int ordinal,
            int modelOrdinal,
            String provider,
            String model,
            int repeat,
            int caseOrdinal,
            String caseId) {
        assertEquals(ordinal, episode.ordinal());
        assertEquals(modelOrdinal, episode.modelOrdinal());
        assertEquals(provider, episode.model().provider());
        assertEquals(model, episode.model().model());
        assertEquals(repeat, episode.repeat());
        assertEquals(caseOrdinal, episode.caseOrdinal());
        assertEquals(caseId, episode.caseId());
    }
}
