package com.paicli.eval.benchmark;

import com.paicli.eval.benchmark.formal.FormalEpisodeOutcome;
import com.paicli.eval.benchmark.formal.FormalTestAdmission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FormalBatchPreparationTest {
    @Test
    void preparesAll168RegisteredTwoModelEpisodesWithoutReadingHy4Credentials(@TempDir Path base)
            throws Exception {
        var admission = FormalTestAdmission.createTwoModels(base.resolve("two-models"), null);
        List<String> loaded = new ArrayList<>();
        var ready = FormalBatchPreparation.prepare(admission, provider -> {
            assertNotEquals("hunyuan", provider, "unregistered credentials must never be read");
            loaded.add(provider);
            return credential(provider);
        });
        assertEquals(List.of("deepseek", "glm"), loaded);
        assertEquals(4, ready.plan().formalBatchContract().contractVersion());
        assertEquals(5, ready.plan().planVersion());
        assertEquals(168, ready.plan().formalBatchContract().expectedEpisodeCount());
        assertEquals(168, ready.episodeCount()); assertEquals(28, ready.caseCount());
        assertFalse(ready.publishable());
        for (int i = 0; i < 168; i++) {
            var episode = ready.plan().episodes().get(i);
            var request = ready.requests().get(i);
            String provider = i < 84 ? "deepseek" : "glm";
            String model = i < 84 ? "deepseek-v4-flash" : "glm-5.3-flash";
            assertEquals(provider, request.provider()); assertEquals(model, request.model());
            assertEquals(i + 1, request.key().episodeOrdinal());
            assertEquals(i % 84 / 28 + 1, request.key().repeat());
            assertEquals(ready.plan().cases().get(i % 28).id(), request.key().caseId());
            assertEquals(admission.batchSha256(), request.key().batchSha256());
            assertEquals(episode.casePlan().mode().toJson(), request.mode());
            assertEquals(episode.casePlan().prompt(), request.prompt());
            assertEquals(1_000_000, request.limits().contextWindowCapTokens());
            assertEquals(16_384, request.limits().maxOutputTokensPerCall());
        }
        assertThrows(UnsupportedOperationException.class, () -> ready.requests().clear());
        ready.verifyBeforeExecution();
    }

    @Test
    void missingRegisteredGlmCredentialRejectsTwoModelBatchWithoutConsultingHy4(@TempDir Path base)
            throws Exception {
        var admission = FormalTestAdmission.createTwoModels(base.resolve("two-models-missing-key"), null);
        List<String> loaded = new ArrayList<>();
        assertThrows(IOException.class, () -> FormalBatchPreparation.prepare(admission, provider -> {
            assertNotEquals("hunyuan", provider);
            loaded.add(provider);
            return provider.equals("glm") ? null : credential(provider);
        }));
        assertEquals(List.of("deepseek", "glm"), loaded);
    }

    @Test
    void preparesAll252FrozenEpisodesAcrossModesAndModels(@TempDir Path base) throws Exception {
        var admission = FormalTestAdmission.create(base.resolve("batch"), null);
        List<String> loaded = new ArrayList<>();
        var ready = FormalBatchPreparation.prepare(admission, provider -> {
            loaded.add(provider);
            return credential(provider);
        });
        assertEquals(List.of("deepseek", "hunyuan", "glm"), loaded);
        assertEquals(252, ready.episodeCount());
        assertEquals(28, ready.caseCount());
        assertFalse(ready.publishable());
        assertEquals(admission.batchSha256(), ready.batchSha256());
        assertFalse(ready.toString().contains("private-secret"));
        for (int i = 0; i < 252; i++) {
            var episode = admission.plan().episodes().get(i);
            var prepared = ready.requests().get(i);
            assertEquals(episode.ordinal(), prepared.key().episodeOrdinal());
            assertEquals(admission.batchSha256(), prepared.key().batchSha256());
            assertEquals(episode.caseId(), prepared.key().caseId());
            assertEquals(episode.model().model(), prepared.model());
            assertEquals(episode.casePlan().mode().toJson(), prepared.mode());
            assertEquals(episode.casePlan().prompt(), prepared.prompt());
            assertEquals(1_000_000, prepared.limits().contextWindowCapTokens());
            assertEquals(16_384, prepared.limits().maxOutputTokensPerCall());
        }
        assertThrows(UnsupportedOperationException.class, () -> ready.requests().clear());
        ready.verifyBeforeExecution();
        Files.writeString(ready.plan().artifacts().candidateJar(), "different candidate");
        assertThrows(IOException.class, ready::verifyBeforeExecution);
    }

    @Test
    void unsupportedFrozenNeedsRejectWholeBatchBeforeLoadingAnyCredentials(@TempDir Path base)
            throws Exception {
        var admission = FormalTestAdmission.create(base.resolve("unsupported"), "MOCK_MCP");
        var error = assertThrows(FormalEpisodeRequestFactory.PreparationException.class,
                () -> FormalBatchPreparation.prepare(admission, provider -> {
                    fail("credentials must not be loaded for an unsupported batch");
                    return credential(provider);
                }));
        assertEquals(FormalEpisodeOutcome.EvaluationDefectCode.UNSUPPORTED_TOOL_PROFILE,
                error.defect().code());
    }

    @Test
    void eachStaticToolSurfaceIsMappedWithoutWidening(@TempDir Path base) throws Exception {
        for (String profile : List.of("REASONING_ONLY", "READ_ONLY", "FILE_ONLY", "LOCAL_COMMAND")) {
            var admission = FormalTestAdmission.create(base.resolve(profile), profile);
            var ready = FormalBatchPreparation.prepare(admission, FormalBatchPreparationTest::credential);
            assertEquals(BenchmarkToolProfile.valueOf(profile), ready.requests().get(0).toolProfile());
            assertEquals(252, ready.episodeCount());
        }
    }

    @Test
    void missingHy4CredentialPreventsReadyBatchInsteadOfPreparingTwoModels(@TempDir Path base)
            throws Exception {
        var admission = FormalTestAdmission.create(base.resolve("missing-key"), null);
        assertThrows(IOException.class, () -> FormalBatchPreparation.prepare(admission,
                provider -> provider.equals("hunyuan") ? null : credential(provider)));
    }

    @Test
    void rejectsCredentialProviderMismatchWithoutExposingTheSecret(@TempDir Path base)
            throws Exception {
        var admission = FormalTestAdmission.create(base.resolve("wrong-key"), null);
        var error = assertThrows(FormalEpisodeRequestFactory.PreparationException.class,
                () -> FormalBatchPreparation.prepare(admission, provider -> credential("deepseek")));
        assertEquals(FormalEpisodeOutcome.EvaluationDefectCode.CREDENTIAL_PROVIDER_MISMATCH,
                error.defect().code());
        assertFalse(error.toString().contains("private-secret"));
    }

    private static FormalEpisodeRequestFactory.HostCredential credential(String provider) {
        return new FormalEpisodeRequestFactory.HostCredential(provider, null,
                "private-secret-for-" + provider);
    }
}
