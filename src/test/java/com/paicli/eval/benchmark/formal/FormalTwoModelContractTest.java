package com.paicli.eval.benchmark.formal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** New two-model registrations never reinterpret or truncate the legacy three-model cohort. */
class FormalTwoModelContractTest {
    @TempDir Path temp;

    @Test void bothVersionedCohortsRoundTripWithTheirOriginalShapeAndLimits() throws Exception {
        var legacy = legacy("legacy");
        var current = current("current");
        assertEquals(3, legacy.batch().contractVersion());
        assertEquals("paicli-formal-batch-contract-v3", legacy.batch().format());
        assertEquals(List.of("deepseek", "hunyuan", "glm"), providers(legacy.batch()));
        assertEquals(252, legacy.batch().expectedEpisodeCount());
        assertEquals(4, current.batch().contractVersion());
        assertEquals("paicli-formal-batch-contract-v4", current.batch().format());
        assertEquals(List.of("deepseek", "glm"), providers(current.batch()));
        assertEquals(168, current.batch().expectedEpisodeCount());
        for (var fixture : List.of(legacy, current)) {
            var batch = fixture.batch();
            assertEquals(batch, FormalBatchContract.load(fixture.batchContractFile()));
            assertEquals(28, batch.caseOrder().size()); assertEquals(3, batch.repeats());
            assertEquals(1_000_000, batch.commonContextCapTokens());
            assertEquals(16_384, batch.maxOutputTokensPerCall());
            assertEquals("ALL_MODEL_SYMMETRIC_RERUN", batch.invalidRunPolicy());
            assertFalse(batch.bestOfN()); assertFalse(batch.dirty());
            Path copy = fixture.base().resolve("batch-copy.json");
            batch.write(copy);
            assertEquals(batch, FormalBatchContract.load(copy));
            assertThrows(IOException.class, () -> batch.write(copy));
            assertFalse(tree(batch).has("expectedEpisodeCount"), "derived count must not change contract JSON shape");
        }
    }

    @Test void rejectsVersionFormatAndCohortCrossMixesAndFutureVersions() throws Exception {
        var oldBatch = legacy("old-mixes").batch();
        var newBatch = current("new-mixes").batch();
        reject(newBatch, n -> n.set("models", tree(oldBatch).get("models")));
        reject(oldBatch, n -> n.set("models", tree(newBatch).get("models")));
        reject(oldBatch, n -> n.put("contractVersion", 4));
        reject(newBatch, n -> n.put("contractVersion", 3));
        reject(oldBatch, n -> n.put("format", FormalBatchContract.FORMAT));
        reject(newBatch, n -> n.put("format", FormalBatchContract.LEGACY_FORMAT));
        reject(newBatch, n -> { n.put("contractVersion", 5); n.put("format", "paicli-formal-batch-contract-v5"); });
        reject(newBatch, n -> { n.put("contractVersion", 2); n.put("format", "paicli-formal-batch-contract-v2"); });
        reject(newBatch, n -> n.withArray("models").remove(1));
        reject(newBatch, n -> { var a = n.withArray("models"); a.set(1, a.get(0).deepCopy()); });
        reject(newBatch, n -> { var a = n.withArray("models"); JsonNode first = a.get(0); a.set(0, a.get(1)); a.set(1, first); });
        reject(newBatch, n -> ((ObjectNode) n.withArray("models").get(1)).put("model", "glm-5.3"));
        reject(newBatch, n -> ((ObjectNode) n.withArray("models").get(1)).put("provider", "hunyuan"));
    }

    @Test void twoModelsStillRequireFullCasesSymmetricRepeatsCapsAndFlags() throws Exception {
        var batch = current("unchanged-gates").batch();
        reject(batch, n -> n.put("repeats", 2));
        reject(batch, n -> n.withArray("caseOrder").remove(27));
        reject(batch, n -> { var a = n.withArray("caseOrder"); a.set(27, a.get(0)); });
        reject(batch, n -> n.put("commonContextCapTokens", 999_999));
        reject(batch, n -> n.put("maxOutputTokensPerCall", 16_385));
        reject(batch, n -> n.put("invalidRunPolicy", "RERUN_ONLY_FAILED_MODEL"));
        reject(batch, n -> n.put("bestOfN", true));
        reject(batch, n -> n.put("dirty", true));
    }

    @Test void loaderRejectsCoercedScalarsMissingUnknownDuplicateAndTrailingFields() throws Exception {
        for (var fixture : List.of(legacy("legacy-types"), current("current-types"))) {
            var batch = fixture.batch();
            reject(batch, n -> n.put("contractVersion", (double) batch.contractVersion()));
            reject(batch, n -> n.put("contractVersion", batch.contractVersion() + 0.1));
            reject(batch, n -> n.put("contractVersion", Integer.toString(batch.contractVersion())));
            reject(batch, n -> n.put("contractVersion", true));
            reject(batch, n -> n.put("repeats", 3.0));
            reject(batch, n -> n.put("repeats", "3"));
            reject(batch, n -> n.put("bestOfN", "false"));
            reject(batch, n -> n.put("dirty", 0));
            reject(batch, n -> n.putNull("contractVersion"));
            reject(batch, n -> n.remove("maxOutputTokensPerCall"));
            reject(batch, n -> n.put("unknown", 1));
            String json = FormalContractSupport.MAPPER.writeValueAsString(batch);
            rejectJson(json.replaceFirst("\\{", "{\"repeats\":3,"));
            rejectJson(json + "\nfalse\n");
            rejectJson("null");
            rejectJson("[]");
        }
    }

    @Test void preflightRetainsExactTwoModelContractWithoutReplacingLegacyRegistration() throws Exception {
        var fixture = current("preflight-two");
        byte[] original = Files.readAllBytes(fixture.batchContractFile());
        var verified = fixture.verify(fixture.preflight());
        assertEquals(fixture.batch(), verified.formalBatchContract());
        assertEquals(List.of("deepseek", "glm"), verified.models().stream().map(FormalBatchContract.ModelBinding::provider).toList());
        assertEquals(28, verified.cases().size()); assertEquals(3, verified.repeats());
        assertEquals(FormalContractSupport.sha256(fixture.batchContractFile()), verified.formalBatchContractSha256());
        assertArrayEquals(original, Files.readAllBytes(fixture.batchContractFile()));
        ObjectNode changed = tree(fixture.batch());
        changed.put("executableSuiteContractSha256", "0".repeat(64));
        Files.writeString(fixture.batchContractFile(), changed.toString());
        assertThrows(IllegalArgumentException.class, () -> fixture.verify(fixture.preflight()));
    }

    @Test void buildsAll168InFrozenOrderWithNoHy4AndWithoutPostAdmissionIo() throws Exception {
        var fixture = current("plan-two");
        var verified = fixture.verify(fixture.preflight());
        Files.writeString(verified.suiteFile(), "changed after admission");
        Files.writeString(verified.blueprintFile(), "changed after admission");
        Files.writeString(verified.formalBatchContractFile(), "changed after admission");
        var plan = FormalExecutionPlan.from(verified);
        assertEquals(5, plan.planVersion()); assertEquals("paicli-formal-execution-plan-v5", plan.format());
        assertEquals(168, plan.episodes().size()); assertEquals(28, plan.cases().size());
        assertFalse(plan.publishable()); assertSame(verified.formalBatchContract(), plan.formalBatchContract());
        for (int i = 0; i < 168; i++) {
            var episode = plan.episodes().get(i);
            assertEquals(i + 1, episode.ordinal()); assertEquals(i / 84 + 1, episode.modelOrdinal());
            assertEquals(i < 84 ? "deepseek" : "glm", episode.model().provider());
            assertEquals((i % 84) / 28 + 1, episode.repeat());
            assertEquals(i % 28 + 1, episode.caseOrdinal());
            assertSame(plan.cases().get(i % 28), episode.casePlan());
        }
        Set<AttemptKey> keys = plan.episodes().stream().map(e -> AttemptKey.from(plan, e, 1)).collect(Collectors.toSet());
        assertEquals(168, keys.size());
        assertThrows(UnsupportedOperationException.class, () -> plan.episodes().remove(0));
    }

    @Test void planVersionCannotBeMixedOrRelabeledAndCoverageCannotBeTrimmed() throws Exception {
        var oldFixture = legacy("old-plan"); var newFixture = current("new-plan");
        var oldPlan = FormalExecutionPlan.from(oldFixture.verify(oldFixture.preflight()));
        var newPlan = FormalExecutionPlan.from(newFixture.verify(newFixture.preflight()));
        assertEquals(4, oldPlan.planVersion()); assertEquals(FormalExecutionPlan.LEGACY_FORMAT, oldPlan.format());
        assertEquals(252, oldPlan.episodes().size());
        assertThrows(IllegalArgumentException.class, () -> copyPlan(oldPlan, 5, FormalExecutionPlan.FORMAT, oldPlan.episodes()));
        assertThrows(IllegalArgumentException.class, () -> copyPlan(newPlan, 4, FormalExecutionPlan.LEGACY_FORMAT, newPlan.episodes()));
        assertThrows(IllegalArgumentException.class, () -> copyPlan(newPlan, 5, FormalExecutionPlan.LEGACY_FORMAT, newPlan.episodes()));
        assertThrows(IllegalArgumentException.class, () -> copyPlan(oldPlan, 4, FormalExecutionPlan.FORMAT, oldPlan.episodes()));
        assertThrows(IllegalArgumentException.class, () -> copyPlan(newPlan, 6, "paicli-formal-execution-plan-v6", newPlan.episodes()));
        assertThrows(IllegalArgumentException.class, () -> copyPlan(newPlan, 5, newPlan.format(), newPlan.episodes().subList(0, 167)));
        assertThrows(IllegalArgumentException.class, () -> copyPlan(newPlan, 5, newPlan.format(), newPlan.episodes().subList(0, 84)));
        var reordered = new ArrayList<>(newPlan.episodes()); java.util.Collections.swap(reordered, 0, 1);
        assertThrows(IllegalArgumentException.class, () -> copyPlan(newPlan, 5, newPlan.format(), reordered));
        var duplicated = new ArrayList<>(newPlan.episodes()); duplicated.set(167, duplicated.get(0));
        assertThrows(IllegalArgumentException.class, () -> copyPlan(newPlan, 5, newPlan.format(), duplicated));
        assertNotEquals(AttemptKey.from(oldPlan, oldPlan.episodes().get(0), 1), AttemptKey.from(newPlan, newPlan.episodes().get(0), 1));
    }

    @Test void admissionRechecksCurrentContractRatherThanAcceptingAChangedCohort() throws Exception {
        var fixture = current("admission-drift");
        var admission = new FormalBenchmarkAdmission(fixture.preflight()).admit(new FormalBenchmarkAdmission.Inputs(
                fixture.frozenRoot(), fixture.publicRepository(), fixture.executableContractFile(),
                fixture.batchContractFile(), fixture.candidateJar(), fixture.runnerJar()));
        admission.verifyUnchanged(); assertEquals(168, admission.plan().episodes().size());
        var changed = tree(fixture.batch()); changed.put("contractVersion", 3); changed.put("format", FormalBatchContract.LEGACY_FORMAT);
        changed.withArray("models").insert(1, (JsonNode) FormalContractSupport.MAPPER.valueToTree(
                new FormalBatchContract.ModelBinding("hunyuan", "hy4-preview")));
        Files.writeString(fixture.batchContractFile(), changed.toString());
        assertEquals(252, FormalBatchContract.load(fixture.batchContractFile()).expectedEpisodeCount());
        assertThrows(IOException.class, admission::verifyUnchanged);
    }

    private FormalBenchmarkPreflightTest.Fixture legacy(String name) throws Exception {
        return FormalBenchmarkPreflightTest.Fixture.create(temp.toRealPath().resolve(name));
    }
    private FormalBenchmarkPreflightTest.Fixture current(String name) throws Exception {
        return FormalBenchmarkPreflightTest.Fixture.createTwoModels(temp.toRealPath().resolve(name));
    }
    private static List<String> providers(FormalBatchContract batch) {
        return batch.models().stream().map(FormalBatchContract.ModelBinding::provider).toList();
    }
    private static ObjectNode tree(FormalBatchContract batch) { return FormalContractSupport.MAPPER.valueToTree(batch); }
    private void reject(FormalBatchContract batch, Consumer<ObjectNode> mutation) throws IOException {
        ObjectNode value = tree(batch); mutation.accept(value); rejectJson(value.toString());
    }
    private void rejectJson(String value) throws IOException {
        Path file = Files.createTempFile(temp, "bad-contract-", ".json");
        Files.writeString(file, value);
        assertThrows(IOException.class, () -> FormalBatchContract.load(file));
    }
    private static FormalExecutionPlan copyPlan(FormalExecutionPlan source, int version, String format,
                                               List<FormalExecutionPlan.EpisodePlan> episodes) {
        return new FormalExecutionPlan(version, format, source.executionOrder(), false, source.freezeManifest(),
                source.executableSuiteContract(), source.formalBatchContract(), source.artifacts(), source.cases(), episodes);
    }
}
