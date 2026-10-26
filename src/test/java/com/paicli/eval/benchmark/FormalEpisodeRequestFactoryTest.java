package com.paicli.eval.benchmark;

import com.paicli.eval.benchmark.formal.FormalA1TestPlan;
import com.paicli.eval.benchmark.formal.FormalEpisodeOutcome;
import com.paicli.eval.benchmark.formal.FormalExecutionPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FormalEpisodeRequestFactoryTest {
    @Test
    void mapsOnlyFrozenA1FieldsIntoWorkerRequestAndRedactsCredential(@TempDir Path tempDir)
            throws Exception {
        FormalExecutionPlan plan = FormalA1TestPlan.create(tempDir.resolve("a1-plan"));
        FormalExecutionPlan.EpisodePlan episode = plan.episodes().get(0);
        String secret = "formal-secret-value";
        FormalEpisodeRequestFactory.HostCredential credential =
                new FormalEpisodeRequestFactory.HostCredential(
                        "deepseek", null, secret);

        FormalEpisodeRequestFactory.PreparedRequest prepared =
                FormalEpisodeRequestFactory.create(plan, episode, credential);

        assertEquals("deepseek", prepared.provider());
        assertEquals("deepseek-v4-flash", prepared.model());
        assertEquals("react", prepared.mode());
        assertEquals(BenchmarkToolProfile.READ_ONLY, prepared.toolProfile());
        assertEquals(episode.casePlan().tokenBudget(), prepared.limits().tokenBudget());
        assertEquals(episode.casePlan().hardMaxIterations(),
                prepared.limits().hardMaxIterations());
        assertEquals(episode.casePlan().stagnationWindow(),
                prepared.limits().stagnationWindow());
        assertEquals(plan.formalBatchContract().commonContextCapTokens(),
                prepared.limits().contextWindowCapTokens());
        assertEquals(plan.formalBatchContract().maxOutputTokensPerCall(),
                prepared.limits().maxOutputTokensPerCall());
        assertEquals(plan.formalBatchContract().runtimeDate(), prepared.runtimeDate());
        assertEquals(episode.casePlan().prompt(), prepared.prompt());
        assertEquals(episode.casePlan().timeoutSeconds(), prepared.timeoutSeconds());
        assertFalse(prepared.publishable());
        assertFalse(prepared.toString().contains(secret));
        assertFalse(credential.toString().contains(secret));

        BenchmarkArtifactStore.EpisodeArtifacts artifacts = BenchmarkArtifactStore.create(
                        tempDir.resolve("artifacts"), "formal-a1-dry-run")
                .episode("A1", "deepseek-v4-flash", 1, 1);
        BenchmarkProtocol.WorkerRequest worker = prepared.toWorkerRequest(
                FormalEpisodeRequestFactory.WorkerPaths.create(artifacts));
        assertEquals(prepared.provider(), worker.provider());
        assertEquals(prepared.model(), worker.model());
        assertEquals(prepared.mode(), worker.mode());
        assertEquals(prepared.toolProfile(), worker.toolProfile());
        assertEquals(prepared.limits().tokenBudget(), worker.agentLimits().tokenBudget());
        assertEquals(prepared.limits().hardMaxIterations(),
                worker.agentLimits().hardMaxIterations());
        assertEquals(prepared.limits().stagnationWindow(),
                worker.agentLimits().stagnationWindow());
        assertEquals(prepared.limits().contextWindowCapTokens(),
                worker.agentLimits().contextWindowCapTokens());
        assertEquals(prepared.limits().maxOutputTokensPerCall(),
                worker.agentLimits().maxOutputTokensPerCall());
        assertEquals(prepared.runtimeDate(), worker.runtimeDate());
        assertEquals(prepared.prompt(), worker.prompt());
        assertFalse(worker.toString().contains(secret));
    }

    @Test
    void endpointPolicyStopsEveryCaseBeforeWorkerOrProviderInjection(@TempDir Path tempDir)
            throws Exception {
        FormalExecutionPlan plan = FormalA1TestPlan.create(tempDir.resolve("unsupported"));
        AtomicInteger workerInjections = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        FormalEpisodeRequestFactory.HostCredential credential =
                new FormalEpisodeRequestFactory.HostCredential(
                        "deepseek", "https://relay.example.invalid/v1", "secret-never-used");

        FormalEpisodeRequestFactory.PreparationException error = assertThrows(
                FormalEpisodeRequestFactory.PreparationException.class,
                () -> {
                    FormalEpisodeRequestFactory.PreparedRequest request =
                            FormalEpisodeRequestFactory.create(
                                    plan, plan.episodes().get(1), credential);
                    workerInjections.incrementAndGet();
                    fakeProviderCall(request, providerCalls);
                });

        assertEquals(FormalEpisodeOutcome.EvaluationDefectCode.ENDPOINT_POLICY_MISMATCH,
                error.defect().code());
        assertEquals(0, workerInjections.get());
        assertEquals(0, providerCalls.get());
        assertFalse(error.getMessage().contains("secret-never-used"));
        assertFalse(error.getMessage().contains("relay.example.invalid"));
    }

    @Test
    void equalButReconstructedEpisodeIsNotCanonical(@TempDir Path tempDir) throws Exception {
        FormalExecutionPlan plan = FormalA1TestPlan.create(tempDir.resolve("canonical"));
        FormalExecutionPlan.EpisodePlan source = plan.episodes().get(0);
        FormalExecutionPlan.EpisodePlan reconstructed = new FormalExecutionPlan.EpisodePlan(
                source.ordinal(), source.modelOrdinal(), source.model(), source.repeat(),
                source.caseOrdinal(), source.casePlan());

        FormalEpisodeRequestFactory.PreparationException error = assertThrows(
                FormalEpisodeRequestFactory.PreparationException.class,
                () -> FormalEpisodeRequestFactory.create(
                        plan, reconstructed,
                        new FormalEpisodeRequestFactory.HostCredential(
                                "deepseek", null, "key")));
        assertEquals(FormalEpisodeOutcome.EvaluationDefectCode.NON_CANONICAL_EPISODE,
                error.defect().code());
    }

    @Test
    void arbitraryHttpsEndpointIsRejectedBeforeInjectionWithoutEcho(@TempDir Path tempDir)
            throws Exception {
        FormalExecutionPlan plan = FormalA1TestPlan.create(tempDir.resolve("endpoint"));
        String endpoint = "https://proxy.example.invalid/secret-route";
        AtomicInteger injections = new AtomicInteger();
        FormalEpisodeRequestFactory.HostCredential credential =
                new FormalEpisodeRequestFactory.HostCredential(
                        "deepseek", endpoint, "endpoint-secret");
        assertFalse(credential.toString().contains(endpoint));
        assertFalse(credential.toString().contains("endpoint-secret"));

        FormalEpisodeRequestFactory.PreparationException error = assertThrows(
                FormalEpisodeRequestFactory.PreparationException.class,
                () -> {
                    FormalEpisodeRequestFactory.PreparedRequest request =
                            FormalEpisodeRequestFactory.create(
                                    plan,
                                    plan.episodes().get(0),
                                    credential);
                    fakeProviderCall(request, injections);
                });

        assertEquals(FormalEpisodeOutcome.EvaluationDefectCode.ENDPOINT_POLICY_MISMATCH,
                error.defect().code());
        assertEquals(0, injections.get());
        assertFalse(error.getMessage().contains(endpoint));
        assertFalse(error.getMessage().contains("endpoint-secret"));
    }

    private static void fakeProviderCall(
            FormalEpisodeRequestFactory.PreparedRequest ignored,
            AtomicInteger providerCalls) {
        providerCalls.incrementAndGet();
    }
}
