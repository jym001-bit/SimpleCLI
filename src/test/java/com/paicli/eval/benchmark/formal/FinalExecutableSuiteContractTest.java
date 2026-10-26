package com.paicli.eval.benchmark.formal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinalExecutableSuiteContractTest {
    @Test
    void writesLoadsAndPreservesTheExactPreregisteredOrder(@TempDir Path tempDir) throws Exception {
        FinalExecutableSuiteContract contract = validContract();
        Path file = tempDir.resolve("final-executable-suite.json");

        contract.write(file);
        FinalExecutableSuiteContract loaded = FinalExecutableSuiteContract.load(file);

        assertEquals(contract, loaded);
        assertEquals(28, loaded.cases().size());
        assertEquals("case-01", loaded.orderedCaseIds().get(0));
        assertEquals("case-28", loaded.orderedCaseIds().get(27));
        assertEquals("blueprint.json", loaded.blueprintPath());
        assertEquals(FinalExecutableSuiteContract.ToolProfile.READ_ONLY,
                loaded.requireCase("case-01").toolProfile());
        assertEquals(FinalExecutableSuiteContract.ToolProfile.CODE_RAG,
                loaded.requireCase("case-02").toolProfile());
        assertEquals("scoring/case-01.json",
                loaded.requireCase("case-01").scoringContractPath());
        assertThrows(FileAlreadyExistsException.class, () -> contract.write(file));
    }

    @Test
    void rejectsWrongCaseCountDuplicateIdsAndLevelWeightDrift() {
        FinalExecutableSuiteContract valid = validContract();

        assertThrows(IllegalArgumentException.class, () -> copyWithCases(
                valid, valid.cases().subList(0, 27)));

        List<FinalExecutableSuiteContract.CaseContract> duplicate = new ArrayList<>(valid.cases());
        duplicate.set(27, duplicate.get(0));
        assertThrows(IllegalArgumentException.class, () -> copyWithCases(valid, duplicate));

        List<FinalExecutableSuiteContract.CaseContract> drifted = new ArrayList<>(valid.cases());
        FinalExecutableSuiteContract.CaseContract original = drifted.get(0);
        drifted.set(0, copyCase(original, FinalExecutableSuiteContract.Level.L2, original.weight()));
        assertThrows(IllegalArgumentException.class, () -> copyWithCases(valid, drifted));
    }

    @Test
    void rejectsEmptyExecutionEvidenceAndUnsafeVerifierPath() {
        FinalExecutableSuiteContract.CaseContract valid = validContract().cases().get(0);

        assertThrows(IllegalArgumentException.class, () -> new FinalExecutableSuiteContract.CaseContract(
                valid.id(), valid.category(), valid.level(), valid.weight(), valid.mode(),
                valid.toolProfile(), valid.timeoutSeconds(), valid.tokenBudget(),
                valid.hardMaxIterations(), valid.stagnationWindow(), valid.mockProfile(),
                List.of(), valid.mandatoryAssertionIds(), valid.hardGateIds(),
                valid.evidenceRequirements(), valid.scoringContractPath(),
                valid.scoringContractSha256(), valid.verifierEntryPath(),
                valid.verifierSha256(), valid.verifierDependencyPaths(),
                valid.verifierBundleSha256()));
        assertThrows(IllegalArgumentException.class, () -> new FinalExecutableSuiteContract.CaseContract(
                valid.id(), valid.category(), valid.level(), valid.weight(), valid.mode(),
                valid.toolProfile(), valid.timeoutSeconds(), valid.tokenBudget(),
                valid.hardMaxIterations(), valid.stagnationWindow(), valid.mockProfile(),
                valid.episodeEvents(), valid.mandatoryAssertionIds(), valid.hardGateIds(),
                valid.evidenceRequirements(), valid.scoringContractPath(),
                valid.scoringContractSha256(), "../validator.sh", valid.verifierSha256(),
                valid.verifierDependencyPaths(), valid.verifierBundleSha256()));
        assertThrows(IllegalArgumentException.class, () -> copyCaseWithScoring(
                valid, "../scoring.json", valid.scoringContractSha256()));
        assertThrows(IllegalArgumentException.class, () -> copyCaseWithScoring(
                valid, valid.verifierEntryPath(), valid.scoringContractSha256()));
    }

    @Test
    void rejectsVerifierDependencyOmissionUnsafePathAndNonCanonicalOrder() {
        FinalExecutableSuiteContract.CaseContract valid = validContract().cases().get(0);

        assertThrows(IllegalArgumentException.class, () -> copyCaseWithVerifierDependencies(
                valid, List.of("validators/final/case-01.helper")));
        assertThrows(IllegalArgumentException.class, () -> copyCaseWithVerifierDependencies(
                valid, List.of(valid.verifierEntryPath(), "../outside.py")));
        assertThrows(IllegalArgumentException.class, () -> copyCaseWithVerifierDependencies(
                valid, List.of(valid.verifierEntryPath(), "validators/final/case-01.helper")));
        assertThrows(IllegalArgumentException.class, () -> copyCaseWithVerifierDependencies(
                valid, List.of(valid.verifierEntryPath(), valid.verifierEntryPath())));
    }

    @Test
    void rejectsUnsafeOrAliasedBlueprintPath() {
        FinalExecutableSuiteContract valid = validContract();
        assertThrows(IllegalArgumentException.class, () -> copyWithBlueprint(
                valid, "../blueprint.json", valid.blueprintSha256()));
        assertThrows(IllegalArgumentException.class, () -> copyWithBlueprint(
                valid, valid.suitePath(), valid.blueprintSha256()));
    }

    @Test
    void loaderRejectsUnknownDuplicateTrailingAndGlobalOverrideFields(@TempDir Path tempDir)
            throws Exception {
        Path validFile = tempDir.resolve("valid.json");
        validContract().write(validFile);
        String json = Files.readString(validFile);

        assertInvalid(tempDir.resolve("unknown.json"),
                json.replaceFirst("\\{", "{\"unexpected\":true,"));
        assertInvalid(tempDir.resolve("duplicate.json"),
                json.replaceFirst("\"suiteId\"\\s*:\\s*\"formal-suite\"",
                        "\"suiteId\":\"formal-suite\",\"suiteId\":\"other\""));
        assertInvalid(tempDir.resolve("trailing.json"), json + "\n{}\n");
        assertInvalid(tempDir.resolve("global-override.json"),
                json.replaceFirst("\\{", "{\"toolProfile\":\"LOCAL_COMMAND\","));
    }

    static FinalExecutableSuiteContract validContract() {
        List<FinalExecutableSuiteContract.CaseContract> cases = new ArrayList<>();
        for (int index = 1; index <= 28; index++) {
            FinalExecutableSuiteContract.Level level;
            int weight;
            if (index <= 10) {
                level = FinalExecutableSuiteContract.Level.L1;
                weight = 4;
            } else if (index <= 19) {
                level = FinalExecutableSuiteContract.Level.L2;
                weight = 4;
            } else {
                level = FinalExecutableSuiteContract.Level.L3;
                weight = index <= 25 ? 3 : 2;
            }
            String id = "case-%02d".formatted(index);
            cases.add(new FinalExecutableSuiteContract.CaseContract(
                    id,
                    "category-%02d".formatted((index - 1) % 7 + 1),
                    level,
                    weight,
                    index == 20 ? FinalExecutableSuiteContract.Mode.PLAN
                            : index == 21 ? FinalExecutableSuiteContract.Mode.TEAM
                            : FinalExecutableSuiteContract.Mode.REACT,
                    index == 2 ? FinalExecutableSuiteContract.ToolProfile.CODE_RAG
                            : index % 4 == 0
                                    ? FinalExecutableSuiteContract.ToolProfile.LOCAL_COMMAND
                                    : FinalExecutableSuiteContract.ToolProfile.READ_ONLY,
                    600,
                    100_000,
                    128,
                    8,
                    "none",
                    List.of("worker_dispatch_started", "worker_dispatch_finished",
                            "verifier_dispatch_started", "verifier_dispatch_finished"),
                    List.of(id + ".assertion"),
                    List.of(id + ".hard_gate"),
                    List.of("answer", "llm_metrics", "tool_events"),
                    "scoring/" + id + ".json",
                    hex(100 + index),
                    "validators/final/" + id + ".sh",
                    hex(index),
                    List.of(
                            "validators/final/" + id + ".sh",
                            "validators/final/" + id + ".verify.py"),
                    hex(200 + index)));
        }
        return new FinalExecutableSuiteContract(
                FinalExecutableSuiteContract.CURRENT_VERSION,
                FinalExecutableSuiteContract.FORMAT,
                "formal-suite",
                "v1.0.0",
                "blueprint.json",
                "a".repeat(64),
                "suite.json",
                "b".repeat(64),
                cases);
    }

    private static FinalExecutableSuiteContract copyWithCases(
            FinalExecutableSuiteContract source,
            List<FinalExecutableSuiteContract.CaseContract> cases) {
        return new FinalExecutableSuiteContract(
                source.contractVersion(), source.format(), source.suiteId(), source.suiteVersion(),
                source.blueprintPath(), source.blueprintSha256(), source.suitePath(),
                source.suiteSha256(), cases);
    }

    private static FinalExecutableSuiteContract copyWithBlueprint(
            FinalExecutableSuiteContract source,
            String blueprintPath,
            String blueprintSha256) {
        return new FinalExecutableSuiteContract(
                source.contractVersion(), source.format(), source.suiteId(), source.suiteVersion(),
                blueprintPath, blueprintSha256, source.suitePath(), source.suiteSha256(),
                source.cases());
    }

    private static FinalExecutableSuiteContract.CaseContract copyCase(
            FinalExecutableSuiteContract.CaseContract source,
            FinalExecutableSuiteContract.Level level,
            int weight) {
        return new FinalExecutableSuiteContract.CaseContract(
                source.id(), source.category(), level, weight, source.mode(), source.toolProfile(),
                source.timeoutSeconds(), source.tokenBudget(), source.hardMaxIterations(),
                source.stagnationWindow(), source.mockProfile(), source.episodeEvents(),
                source.mandatoryAssertionIds(), source.hardGateIds(), source.evidenceRequirements(),
                source.scoringContractPath(), source.scoringContractSha256(),
                source.verifierEntryPath(), source.verifierSha256(),
                source.verifierDependencyPaths(), source.verifierBundleSha256());
    }

    private static FinalExecutableSuiteContract.CaseContract copyCaseWithScoring(
            FinalExecutableSuiteContract.CaseContract source,
            String scoringContractPath,
            String scoringContractSha256) {
        return new FinalExecutableSuiteContract.CaseContract(
                source.id(), source.category(), source.level(), source.weight(), source.mode(),
                source.toolProfile(), source.timeoutSeconds(), source.tokenBudget(),
                source.hardMaxIterations(), source.stagnationWindow(), source.mockProfile(),
                source.episodeEvents(), source.mandatoryAssertionIds(), source.hardGateIds(),
                source.evidenceRequirements(), scoringContractPath, scoringContractSha256,
                source.verifierEntryPath(), source.verifierSha256(),
                source.verifierDependencyPaths(), source.verifierBundleSha256());
    }

    private static FinalExecutableSuiteContract.CaseContract copyCaseWithVerifierDependencies(
            FinalExecutableSuiteContract.CaseContract source,
            List<String> verifierDependencyPaths) {
        return new FinalExecutableSuiteContract.CaseContract(
                source.id(), source.category(), source.level(), source.weight(), source.mode(),
                source.toolProfile(), source.timeoutSeconds(), source.tokenBudget(),
                source.hardMaxIterations(), source.stagnationWindow(), source.mockProfile(),
                source.episodeEvents(), source.mandatoryAssertionIds(), source.hardGateIds(),
                source.evidenceRequirements(), source.scoringContractPath(),
                source.scoringContractSha256(), source.verifierEntryPath(),
                source.verifierSha256(), verifierDependencyPaths, source.verifierBundleSha256());
    }

    private static void assertInvalid(Path file, String json) throws IOException {
        Files.writeString(file, json);
        IOException error = assertThrows(IOException.class,
                () -> FinalExecutableSuiteContract.load(file));
        assertTrue(error.getMessage().contains("formal contract")
                || error.getCause() != null);
    }

    private static String hex(int value) {
        return "%064x".formatted(value);
    }
}
