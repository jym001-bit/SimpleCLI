package com.paicli.eval.benchmark.finalset.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.eval.benchmark.formal.FinalExecutableSuiteContract;
import com.paicli.eval.benchmark.scoring.ScoringContract;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FinalCaseContractCompilerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;

    @AfterEach
    void restoreOnlyGeneratedTestPermissions() throws Exception {
        try (var walk = Files.walk(temp)) {
            for (Path p : walk.toList()) if (!Files.isSymbolicLink(p))
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(
                        Files.isDirectory(p) ? "rwx------" : "rw-------"));
        }
    }

    @Test
    void generatorWritesTwentyFourContractsWithOriginalWeightsNotAReducedSuite() throws Exception {
        var result = generated();
        assertEquals(3, result.manifest().manifestVersion());
        int compiled = 0, weight = 0;
        for (var item : result.manifest().cases()) {
            assertFalse(item.publicationEligible());
            assertEquals("NOT_INTEGRATED", item.runnerIntegrationStatus());
            if (item.caseContractPath().isEmpty()) {
                assertEquals("", item.caseContractSha256());
                assertThrows(IOException.class, () -> FinalCaseContractCompiler.compile(result.sourceRoot(), item.id()));
                continue;
            }
            compiled++;
            Path path = result.sourceRoot().resolve(item.caseContractPath());
            var contract = FinalExecutableSuiteContract.CaseContract.load(path);
            assertEquals(FinalCaseContractCompiler.compile(result.sourceRoot(), item.id()), contract);
            assertEquals(FinalCaseContractCompiler.sha256(path), item.caseContractSha256());
            assertEquals(item.weight(), contract.weight()); weight += contract.weight();
            assertTrue(contract.verifierDependencyPaths().contains(contract.scoringContractPath()));
            assertTrue(contract.verifierDependencyPaths().contains(item.privateOraclePath()));
            assertTrue(contract.verifierDependencyPaths().stream().noneMatch(p -> p.startsWith("references/")));
        }
        assertEquals(25, compiled); assertEquals(88, weight);
        assertFalse(Files.exists(result.sourceRoot().resolve("suite.json")));
        assertThrows(IllegalStateException.class, () -> new FinalSourceGenerator().requireFinalReady(result.sourceRoot()));
    }

    @Test
    void requiredJudgeAndStateEvidenceAreNotDowngradedToStaticFiles() throws Exception {
        var result = generated();
        for (String id : List.of("A3", "A4")) {
            var c = FinalCaseContractCompiler.compile(result.sourceRoot(), id);
            assertTrue(c.evidenceRequirements().contains("judge_report"));
            assertTrue(ScoringContract.load(result.sourceRoot().resolve(c.scoringContractPath())).requiresJudge());
        }
        for (var pair : List.of(List.of("A2", "semantic_index"), List.of("A4", "workspace_metadata"),
                List.of("B5", "scheduler_audit"), List.of("C2", "process_lifecycle"),
                List.of("C3", "command_provenance"), List.of("D1", "mock_audit"),
                List.of("D2", "mock_audit"), List.of("D2", "mock_state"), List.of("D3", "mock_audit"),
                List.of("D3", "mock_state"), List.of("D3", "approval_relay"),
                List.of("D4", "web_audit"), List.of("D4", "provider_turns"),
                List.of("E1", "plan_audit"), List.of("E1", "scoped_request_fingerprints"),
                List.of("F2", "command_audit"), List.of("F2", "provider_turns"),
                List.of("F3", "mock_audit"), List.of("F3", "mock_state"), List.of("F3", "provider_turns"),
                List.of("F3", "raw_tool_results"), List.of("F3", "stream_deltas"),
                List.of("F4", "mock_audit"), List.of("F4", "mock_state"), List.of("F4", "approval_relay"), List.of("F4", "provider_turns")))
            assertTrue(FinalCaseContractCompiler.compile(result.sourceRoot(), pair.get(0))
                    .evidenceRequirements().contains(pair.get(1)));
        assertEquals(900, FinalCaseContractCompiler.limits("C3").timeoutSeconds());
        assertEquals(new FinalCaseContractCompiler.Limits(1800, 200_000, 32, 8), FinalCaseContractCompiler.limits("E1"));
        assertEquals(new FinalCaseContractCompiler.Limits(720, 100_000, 32, 8), FinalCaseContractCompiler.limits("F2"));
        assertEquals(new FinalCaseContractCompiler.Limits(720, 100_000, 32, 8), FinalCaseContractCompiler.limits("F3"));
    }

    @Test
    void f2CompilerBindsTheExactPromptDiagnosticAndNestedArchive() throws Exception {
        var result = generated(); Path root = result.sourceRoot();
        var contract = FinalCaseContractCompiler.compile(root, "F2");
        assertEquals(4, contract.weight()); assertEquals(FinalExecutableSuiteContract.Level.L1, contract.level());
        assertEquals(FinalExecutableSuiteContract.Mode.REACT, contract.mode());
        assertEquals(FinalExecutableSuiteContract.ToolProfile.LOCAL_COMMAND, contract.toolProfile());
        assertEquals("none", contract.mockProfile());
        assertTrue(contract.verifierDependencyPaths().containsAll(F2CaseMaterializer.RUNTIME_PATHS));
        var scoring = ScoringContract.load(root.resolve(contract.scoringContractPath()));
        assertEquals(F2CaseMaterializer.ASSERTIONS.stream().map(id -> "F2." + id).toList(),
                scoring.assertions().stream().map(ScoringContract.AssertionRule::id).toList());
        for (String name : List.of("prompts/final/F2.md", "fixtures/final/F2/diagnose.py", "fixtures/final/F2/archive/sentinel.txt")) {
            Path path = root.resolve(name); String original = Files.readString(path);
            Files.writeString(path, original + "changed");
            assertThrows(IOException.class, () -> FinalCaseContractCompiler.compile(root, "F2"));
            Files.writeString(path, original);
        }
        Path extra = root.resolve("fixtures/final/F2/archive/unexpected.txt"); Files.writeString(extra, "extra");
        assertThrows(IOException.class, () -> FinalCaseContractCompiler.compile(root, "F2"));
        Files.delete(extra); // Only the synthetic file this test just created.
        assertEquals(contract, FinalCaseContractCompiler.compile(root, "F2"));
    }

    @Test
    void e1CompilerBindsPromptTwoCsvInputsAndBothIndependentRuntimeFiles() throws Exception {
        var result = generated(); Path root = result.sourceRoot();
        var contract = FinalCaseContractCompiler.compile(root, "E1");
        assertEquals(FinalExecutableSuiteContract.Mode.PLAN, contract.mode());
        assertEquals(FinalExecutableSuiteContract.ToolProfile.FILE_ONLY, contract.toolProfile());
        assertTrue(contract.verifierDependencyPaths().containsAll(E1CaseMaterializer.RUNTIME_PATHS));
        Path prompt = root.resolve("prompts/final/E1.md"), csv = root.resolve("fixtures/final/E1/left.csv");
        for (Path path : List.of(prompt, csv)) {
            String original = Files.readString(path); Files.writeString(path, original + "changed");
            assertThrows(IOException.class, () -> FinalCaseContractCompiler.compile(root, "E1"));
            Files.writeString(path, original);
        }
        Path extra = root.resolve("fixtures/final/E1/CASE-METADATA.json"); Files.writeString(extra, "{}");
        assertThrows(IOException.class, () -> FinalCaseContractCompiler.compile(root, "E1"));
        Files.delete(extra); // Only the extra file just created by this test.
        assertEquals(contract, FinalCaseContractCompiler.compile(root, "E1"));
    }

    @Test
    void frozenPermissionNormalizationKeepsBundleIdentityAndRejectsWrongModes() throws Exception {
        Path root = generated().sourceRoot();
        var before = FinalCaseContractCompiler.compile(root, "G1");
        for (String path : before.verifierDependencyPaths())
            Files.setPosixFilePermissions(root.resolve(path), PosixFilePermissions.fromString(
                    path.equals(before.verifierEntryPath()) ? "r-x------" : "r--------"));
        assertEquals(before, FinalCaseContractCompiler.compile(root, "G1"));
        Files.setPosixFilePermissions(root.resolve(before.scoringContractPath()),
                PosixFilePermissions.fromString("r-x------"));
        assertThrows(IOException.class, () -> FinalCaseContractCompiler.compile(root, "G1"));
    }

    @Test
    void refusesReweightedScoringEvenWhenMaximaStillSumToOneHundred() throws Exception {
        Path root = generated().sourceRoot();
        var c = FinalCaseContractCompiler.compile(root, "G1");
        Path scoring = root.resolve(c.scoringContractPath());
        ObjectNode node = (ObjectNode) JSON.readTree(scoring.toFile());
        ObjectNode first = (ObjectNode) node.path("components").get(0);
        ObjectNode second = (ObjectNode) node.path("components").get(1);
        first.put("maxPoints", first.path("maxPoints").asInt() + 1);
        second.put("maxPoints", second.path("maxPoints").asInt() - 1);
        JSON.writeValue(scoring.toFile(), node);
        assertNotNull(ScoringContract.load(scoring));
        assertThrows(IOException.class, () -> FinalCaseContractCompiler.compile(root, "G1"));
    }

    @Test
    void refusesMissingLinkedOrCrossCaseOracleDependencies() throws Exception {
        Path root = generated().sourceRoot();
        Path oracle = root.resolve("validators/final/_private/oracles/G1.json");
        String original = Files.readString(oracle);
        Files.writeString(oracle, Files.readString(root.resolve("validators/final/_private/oracles/G2.json")));
        assertThrows(IOException.class, () -> FinalCaseContractCompiler.compile(root, "G1"));
        Files.delete(oracle); // Generated test file only.
        assertThrows(IOException.class, () -> FinalCaseContractCompiler.compile(root, "G1"));
        Path outside = Files.writeString(temp.resolve("oracle-copy.json"), original);
        Files.createSymbolicLink(oracle, outside);
        assertThrows(IOException.class, () -> FinalCaseContractCompiler.compile(root, "G1"));
    }

    @Test
    void inspectRevalidatesManifestContractBindingAndStrictCaseParsing() throws Exception {
        var result = generated();
        Path file = result.sourceRoot().resolve(FinalSourceGenerator.GENERATION_MANIFEST);
        ObjectNode manifest = (ObjectNode) JSON.readTree(file.toFile());
        ((ObjectNode) manifest.path("cases").get(0)).put("caseContractSha256", "a".repeat(64));
        JSON.writeValue(file.toFile(), manifest);
        assertThrows(IOException.class, () -> new FinalSourceGenerator().inspect(result.sourceRoot()));
        Path contract = result.sourceRoot().resolve(result.manifest().cases().get(0).caseContractPath());
        String bytes = Files.readString(contract);
        Files.writeString(contract, bytes.replaceFirst("\\{", "{\"id\":\"A1\","));
        assertThrows(IOException.class, () -> FinalExecutableSuiteContract.CaseContract.load(contract));
    }

    private FinalSourceGenerator.GenerationResult generated() throws Exception {
        Path parent = temp.toRealPath();
        Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"));
        return new FinalSourceGenerator().generateIncompleteSource(new FinalSourceGenerator.GenerationRequest(
                parent.resolve("source"), "9876543210abcdef".repeat(4)));
    }
}
