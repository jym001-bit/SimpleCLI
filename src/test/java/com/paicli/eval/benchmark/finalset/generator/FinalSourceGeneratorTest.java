package com.paicli.eval.benchmark.finalset.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.eval.benchmark.SuiteDefinition;
import com.paicli.eval.benchmark.scoring.ScoreCalculator;
import com.paicli.eval.benchmark.scoring.ScoringContract;
import com.paicli.eval.benchmark.scoring.VerifierScoringReport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinalSourceGeneratorTest {
    private static final String SEED_A = "0123456789abcdef".repeat(4);
    private static final String SEED_B = "fedcba9876543210".repeat(4);
    private static final List<String> G2_FOUR_SCENARIO_SEEDS = List.of(
            "0".repeat(63) + "6",
            "0".repeat(64),
            "0".repeat(63) + "2",
            "0".repeat(63) + "3");
    private static final ObjectMapper JSON = new ObjectMapper();

    private Path privateParent;

    @BeforeEach
    void createOwnerOnlyPrivateTmpParent() throws IOException {
        Path privateTmp = Path.of("/private/tmp");
        assertTrue(Files.isDirectory(privateTmp), "tests intentionally materialize only below /private/tmp");
        privateParent = privateTmp.resolve(
                "paicli-final-source-generator-test-" + UUID.randomUUID()).toAbsolutePath().normalize();
        Files.createDirectory(privateParent,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.setPosixFilePermissions(privateParent, PosixFilePermissions.fromString("rwx------"));
    }

    @AfterEach
    void removeGeneratedPrivateTmpTree() throws IOException {
        if (privateParent != null && Files.exists(privateParent, LinkOption.NOFOLLOW_LINKS)) {
            deleteTree(privateParent);
        }
    }

    @Test
    void sameSeedIsByteReproducibleAndDifferentSeedChangesEverySibling() throws Exception {
        FinalSourceGenerator generator = new FinalSourceGenerator();
        FinalSourceGenerator.GenerationResult first = generator.generateIncompleteSource(
                request("same-a", SEED_A));
        FinalSourceGenerator.GenerationResult second = generator.generateIncompleteSource(
                request("same-b", SEED_A));
        FinalSourceGenerator.GenerationResult sibling = generator.generateIncompleteSource(
                request("sibling", SEED_B));

        assertEquals(first.completeTreeSha256(), second.completeTreeSha256());
        assertEquals(first.manifest(), second.manifest());
        assertNotEquals(first.completeTreeSha256(), sibling.completeTreeSha256());
        assertEquals(first.manifest().cases().stream()
                        .map(FinalSourceGenerationManifest.CaseRecipe::id).toList(),
                sibling.manifest().cases().stream()
                        .map(FinalSourceGenerationManifest.CaseRecipe::id).toList());
        for (int index = 0; index < 28; index++) {
            FinalSourceGenerationManifest.CaseRecipe left = first.manifest().cases().get(index);
            FinalSourceGenerationManifest.CaseRecipe right = sibling.manifest().cases().get(index);
            assertNotEquals(left.variantId(), right.variantId(), left.id());
            assertNotEquals(left.variantTreeSha256(), right.variantTreeSha256(), left.id());
        }
        for (String caseId : List.of("C1", "C2", "C3", "D1", "D2", "D3", "D4", "E1", "F3", "F4", "G1", "G2")) {
            Path prompt = Path.of("prompts/final/" + caseId + ".md");
            Path oracle = Path.of("validators/final/_private/oracles/" + caseId + ".json");
            assertEquals(Files.readString(first.sourceRoot().resolve(prompt)),
                    Files.readString(second.sourceRoot().resolve(prompt)), caseId);
            assertNotEquals(Files.readString(first.sourceRoot().resolve(prompt)),
                    Files.readString(sibling.sourceRoot().resolve(prompt)), caseId);
            assertEquals(JSON.readTree(first.sourceRoot().resolve(oracle).toFile()),
                    JSON.readTree(second.sourceRoot().resolve(oracle).toFile()), caseId);
            assertNotEquals(JSON.readTree(first.sourceRoot().resolve(oracle).toFile()),
                    JSON.readTree(sibling.sourceRoot().resolve(oracle).toFile()), caseId);
        }
    }

    @Test
    void materializesAllSkeletonsAndOnlyTwentyFiveReferenceReportPrototypeEntries() throws Exception {
        FinalSourceGenerator generator = new FinalSourceGenerator();
        FinalSourceGenerator.GenerationResult result = generator.generateIncompleteSource(
                request("layout", SEED_A));
        Path root = result.sourceRoot();

        assertEquals(28, result.manifest().recipeCount());
        assertEquals(25, result.manifest().implementedRecipeCount());
        assertFalse(result.manifest().finalReady());
        assertEquals(3, result.manifest().missingCaseIds().size());
        assertTrue(Files.isRegularFile(root.resolve("provenance/final/F3/CASE-METADATA.json")));
        assertFalse(Files.exists(root.resolve("fixtures/final/F3/CASE-METADATA.json")));
        assertEquals("SYNTHETIC_REFERENCE_NOT_PROVIDER_WORKER_OR_OS_AUDIT", JSON.readTree(root.resolve("provenance/final/F3/reference-origin.json").toFile()).path("kind").asText());
        assertTrue(Files.isRegularFile(root.resolve("provenance/final/F2/CASE-METADATA.json")));
        assertFalse(Files.exists(root.resolve("fixtures/final/F2/CASE-METADATA.json")));
        assertEquals("SYNTHETIC_REFERENCE_NOT_PROVIDER_OR_OS_AUDIT", JSON.readTree(root.resolve("provenance/final/F2/reference-origin.json").toFile()).path("kind").asText());
        assertTrue(Files.isRegularFile(root.resolve("provenance/final/F4/CASE-METADATA.json")));
        try (var files = Files.list(root.resolve("fixtures/final/F4"))) {
            assertEquals(java.util.Set.of("README.md"), files.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        }
        assertTrue(Files.isRegularFile(root.resolve("provenance/final/E1/CASE-METADATA.json")));
        try (var files = Files.list(root.resolve("fixtures/final/E1"))) {
            assertEquals(java.util.Set.of("left.csv", "right.csv"), files.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        }
        assertTrue(result.manifest().cases().stream()
                .filter(caseRecipe -> FinalSourceRecipeCatalog.implementedIds()
                        .contains(caseRecipe.id()))
                .allMatch(caseRecipe ->
                        caseRecipe.executionMaturity().startsWith("REFERENCE_REPORT_PROTOTYPE")
                                && "NOT_INTEGRATED".equals(caseRecipe.runnerIntegrationStatus())
                                && !caseRecipe.publicationEligible()
                                && caseRecipe.failClosedReason().contains(
                                "full-28-case-suite-contract-not-assembled")
                                && caseRecipe.failClosedReason().contains(
                                "formal-runner-not-consuming-reference-prototype")));
        assertEquals("REFERENCE_REPORT_PROTOTYPE_FAIL_CLOSED_CONCURRENCY",
                result.manifest().cases().stream()
                        .filter(caseRecipe -> "B5".equals(caseRecipe.id()))
                        .findFirst().orElseThrow().executionMaturity());
        assertEquals("REFERENCE_REPORT_PROTOTYPE_FAIL_CLOSED_TOOLCHAIN",
                result.manifest().cases().stream()
                        .filter(caseRecipe -> "C1".equals(caseRecipe.id()))
                        .findFirst().orElseThrow().executionMaturity());
        assertEquals("REFERENCE_REPORT_PROTOTYPE_FAIL_CLOSED_PROCESS_LIFECYCLE",
                result.manifest().cases().stream()
                        .filter(caseRecipe -> "C2".equals(caseRecipe.id()))
                        .findFirst().orElseThrow().executionMaturity());
        assertEquals("REFERENCE_REPORT_PROTOTYPE_FAIL_CLOSED_COMMAND_PROVENANCE",
                result.manifest().cases().stream()
                        .filter(caseRecipe -> "C3".equals(caseRecipe.id()))
                        .findFirst().orElseThrow().executionMaturity());
        for (String caseId : List.of("C1", "C2", "C3")) {
            FinalSourceGenerationManifest.CaseRecipe terminal = result.manifest().cases().stream()
                    .filter(caseRecipe -> caseId.equals(caseRecipe.id()))
                    .findFirst().orElseThrow();
            assertEquals("NOT_INTEGRATED", terminal.runnerIntegrationStatus(), caseId);
            assertFalse(terminal.publicationEligible(), caseId);
            assertTrue(terminal.failClosedReason().contains(
                    caseId.equals("C1") ? "maven-worker-image"
                            : caseId.equals("C2") ? "process-port-namespace"
                            : "terminal-artifact-provenance"), caseId);
        }
        for (String caseId : List.of("G1", "G2")) {
            FinalSourceGenerationManifest.CaseRecipe reasoning = result.manifest().cases().stream()
                    .filter(caseRecipe -> caseId.equals(caseRecipe.id()))
                    .findFirst().orElseThrow();
            assertEquals("REFERENCE_REPORT_PROTOTYPE", reasoning.executionMaturity(), caseId);
            assertEquals("NOT_INTEGRATED", reasoning.runnerIntegrationStatus(), caseId);
            assertFalse(reasoning.publicationEligible(), caseId);
            assertFalse(reasoning.failClosedReason().contains(
                    "judge-calibration-not-integrated"), caseId);
        }
        assertTrue(Files.isRegularFile(root.resolve(FinalSourceGenerator.INCOMPLETE_MARKER)));
        assertTrue(Files.isRegularFile(root.resolve(FinalSourceGenerator.DRAFT_SUITE)));
        assertFalse(Files.exists(root.resolve("suite.json"), LinkOption.NOFOLLOW_LINKS));
        assertThrows(IllegalArgumentException.class,
                () -> SuiteDefinition.load(root.resolve(FinalSourceGenerator.DRAFT_SUITE)));
        assertThrows(IllegalStateException.class, () -> generator.requireFinalReady(root));

        for (FinalSourceRecipeCatalog.Recipe recipe : FinalSourceRecipeCatalog.recipes()) {
            assertTrue(Files.isDirectory(root.resolve("fixtures/final").resolve(recipe.id())));
            assertTrue(Files.isRegularFile(root.resolve("prompts/final").resolve(recipe.id() + ".md")));
            Path verifier = root.resolve("validators/final").resolve(recipe.id());
            if (recipe.status() == FinalSourceRecipeCatalog.ImplementationStatus.IMPLEMENTED) {
                assertTrue(Files.isRegularFile(verifier, LinkOption.NOFOLLOW_LINKS), recipe.id());
                assertTrue(Files.isExecutable(verifier), recipe.id());
                assertTrue(Files.readString(verifier).startsWith("#!/bin/sh\n"), recipe.id());
                assertTrue(Files.isRegularFile(root.resolve(
                        "validators/final/_private/oracles/" + recipe.id() + ".json")));
                Path scoringContract = root.resolve(
                        "validators/final/_private/scoring-contracts/" + recipe.id() + ".json");
                assertTrue(Files.isRegularFile(scoringContract));
                ScoringContract contract = ScoringContract.load(scoringContract);
                assertEquals(recipe.id(), contract.caseId());
                assertEquals(sha256(verifier), contract.verifierSha256(), recipe.id());
                assertTrue(Files.isDirectory(root.resolve(
                        "references/final/" + recipe.id() + "/workspace")));
            } else {
                assertFalse(Files.exists(verifier, LinkOption.NOFOLLOW_LINKS), recipe.id());
                assertTrue(Files.isRegularFile(root.resolve(
                        "fixtures/final/" + recipe.id() + "/UNIMPLEMENTED.txt")));
            }
        }
    }

    @Test
    void referencePrototypesEmitStrictReportsAndBrokenFixturesRemainScoredFailures() throws Exception {
        FinalSourceGenerator.GenerationResult result = new FinalSourceGenerator()
                .generateIncompleteSource(request("verifiers", SEED_A));
        Path root = result.sourceRoot();

        for (String caseId : FinalSourceRecipeCatalog.implementedIds()) {
            ProcessResult reference = runVerifier(root, caseId,
                    root.resolve("references/final/" + caseId + "/workspace"),
                    root.resolve("references/final/" + caseId + "/evidence.json"));
            assertEquals(0, reference.exitCode(), caseId + ": " + reference.output());
            assertFalse(reference.output().contains("\"passed\""), caseId);
            assertFalse(reference.output().contains("failureClass"), caseId);
            assertFalse(reference.output().contains("judgeStatus"), caseId);
            VerifierScoringReport report = VerifierScoringReport.parse(reference.output().trim());
            ScoringContract contract = ScoringContract.load(root.resolve(
                    "validators/final/_private/scoring-contracts/" + caseId + ".json"));
            if (List.of("A3", "A4").contains(caseId)) {
                ScoreCalculator.Result unavailable = ScoreCalculator.calculate(
                        contract, report, ScoreCalculator.JudgeAvailability.UNAVAILABLE);
                assertEquals(ScoreCalculator.FailureClass.INFRA_UNAVAILABLE,
                        unavailable.failureClass(), caseId);
                assertNull(unavailable.score(), caseId);
                assertTrue(report.components().stream()
                        .filter(component -> component.source()
                                == com.paicli.eval.benchmark.scoring.ScoreSource.JUDGE)
                        .allMatch(component -> component.earnedPoints() == 0
                                && component.evidenceRefs().contains("judge:unavailable")), caseId);
                assertEquals(70, report.components().stream()
                        .filter(component -> component.source()
                                == com.paicli.eval.benchmark.scoring.ScoreSource.DETERMINISTIC)
                        .mapToInt(VerifierScoringReport.ComponentResult::earnedPoints)
                        .sum(), caseId);
            } else {
                ScoreCalculator.Result scored = ScoreCalculator.calculate(
                        contract, report, ScoreCalculator.JudgeAvailability.AVAILABLE);
                if ("B5".equals(caseId)) {
                    assertFalse(scored.strictSuccess(), caseId);
                    assertEquals(20, scored.score(), caseId);
                } else {
                    assertTrue(scored.strictSuccess(), caseId);
                    assertEquals(100, scored.score(), caseId);
                }
            }
        }
        for (String caseId : List.of(
                "B1", "B2", "B3", "B4", "B5", "B6", "C1", "C2", "C3")) {
            ProcessResult broken = runVerifier(root, caseId,
                    root.resolve("fixtures/final/" + caseId),
                    root.resolve("references/final/" + caseId + "/evidence.json"));
            assertEquals(0, broken.exitCode(), caseId + ": " + broken.output());
            VerifierScoringReport report = VerifierScoringReport.parse(broken.output().trim());
            ScoringContract contract = ScoringContract.load(root.resolve(
                    "validators/final/_private/scoring-contracts/" + caseId + ".json"));
            ScoreCalculator.Result scored = ScoreCalculator.calculate(
                    contract, report, ScoreCalculator.JudgeAvailability.AVAILABLE);
            assertFalse(scored.strictSuccess(), caseId);
            assertTrue(scored.score() < 80, caseId);
        }
    }

    @Test
    void referenceEvidenceMatchesEachExactEnvelopeShapeWithoutCandidateAuthorityFields()
            throws Exception {
        FinalSourceGenerator.GenerationResult result = new FinalSourceGenerator()
                .generateIncompleteSource(request("envelope-shape", SEED_A));
        Set<String> expectedTopLevel = Set.of(
                "schemaVersion", "caseId", "repeat", "mode", "toolProfile", "answer",
                "llmMetrics", "toolExecutions", "verifierWorkspaceTreeSha256",
                "verifierWorkspaceFileCount", "verifierWorkspaceTotalBytes",
                "verifierBundleTreeSha256", "verifierBundleFileCount", "verifierBundleTotalBytes");
        Set<String> expectedMetrics = Set.of(
                "calls", "inputTokens", "outputTokens", "cachedInputTokens", "toolCalls",
                "elapsedMillis", "successfulCalls", "resolvedModel", "resolvedModelConsistent",
                "usageComplete", "systemPromptSha256", "initialToolSchemaSha256",
                "requestFingerprintComplete");

        for (String caseId : FinalSourceRecipeCatalog.implementedIds()) {
            Path evidence = result.sourceRoot().resolve(
                    "references/final/" + caseId + "/evidence.json");
            JsonNode value = JSON.readTree(evidence.toFile());
            Set<String> actualTopLevel = new HashSet<>();
            value.fieldNames().forEachRemaining(actualTopLevel::add);
            Set<String> expected = new HashSet<>(expectedTopLevel);
            if (List.of("D1", "D2", "D3", "F4").contains(caseId)) expected.add("mockMcp");
            if (caseId.equals("D4")) expected.add("mockWeb");
            if (caseId.equals("E1")) expected.add("plan");
            if (caseId.equals("F1")) expected.add("boundary");
            if (caseId.equals("F2")) expected.add("command");
            if (caseId.equals("F3")) expected.add("injection");
            if (caseId.equals("E2")) expected.addAll(Set.of(
                    "teamAudit", "promptSha256", "sourceSha256", "scopedRequestFingerprints"));
            assertEquals(expected, actualTopLevel, caseId);
            Set<String> actualMetrics = new HashSet<>();
            value.path("llmMetrics").fieldNames().forEachRemaining(actualMetrics::add);
            Set<String> expectedCaseMetrics = new HashSet<>(expectedMetrics);
            // E2 的宿主审计把逐请求指纹同时挂在 llmMetrics 上（E2CaseMaterializer），属于宿主证据字段
            if (caseId.equals("E2")) expectedCaseMetrics.add("scopedRequestFingerprints");
            assertEquals(expectedCaseMetrics, actualMetrics, caseId);
            assertEquals(caseId.equals("F3") ? 9 : caseId.equals("F2") ? 8 : caseId.equals("F1") ? 7 : caseId.equals("F4") ? 6 : caseId.equals("E1") || caseId.equals("E2") ? 5 : caseId.equals("D4") ? 4 : List.of("D1", "D2", "D3").contains(caseId) ? 3 : 2, value.path("schemaVersion").asInt(), caseId);
            if (caseId.equals("E1")) {
                assertEquals("plan", value.path("mode").asText());
                Set<String> planFields = new HashSet<>(); value.path("plan").fieldNames().forEachRemaining(planFields::add);
                assertEquals(Set.of("schemaVersion", "caseId", "profile", "relayVersion", "sourceSha256", "promptSha256",
                        "audit", "scopedRequestFingerprints"), planFields);
                assertEquals(2, value.path("plan").path("audit").path("schemaVersion").asInt());
            }
            assertTrue(value.path("toolExecutions").isArray(), caseId);
            if (List.of("G1", "G2").contains(caseId)) {
                assertEquals(0, value.path("toolExecutions").size(), caseId);
                assertEquals("REASONING_ONLY", value.path("toolProfile").asText(), caseId);
            }
            assertFalse(value.has("changedFiles"), caseId);
            assertFalse(value.has("commandEvents"), caseId);
            assertFalse(value.has("variantBinding"), caseId);
            assertFalse(value.has("judge"), caseId);
        }
    }

    @Test
    void reasoningPrototypesAreFullyDeterministicPartialCreditAndStrictlyZeroTool()
            throws Exception {
        FinalSourceGenerator.GenerationResult result = new FinalSourceGenerator()
                .generateIncompleteSource(request("reasoning-prototypes", SEED_A));
        Path root = result.sourceRoot();

        for (String caseId : List.of("G1", "G2")) {
            Path contractFile = root.resolve(
                    "validators/final/_private/scoring-contracts/" + caseId + ".json");
            ScoringContract contract = ScoringContract.load(contractFile);
            assertFalse(contract.requiresJudge(), caseId);
            assertTrue(contract.components().size() >= 6, caseId);
            assertEquals(100, contract.components().stream()
                    .filter(component -> component.source()
                            == com.paicli.eval.benchmark.scoring.ScoreSource.DETERMINISTIC)
                    .mapToInt(ScoringContract.ComponentRule::maxPoints)
                    .sum(), caseId);
            assertEquals(0, contract.components().stream()
                    .filter(component -> component.source()
                            == com.paicli.eval.benchmark.scoring.ScoreSource.JUDGE)
                    .mapToInt(ScoringContract.ComponentRule::maxPoints)
                    .sum(), caseId);

            Path fixture = root.resolve("fixtures/final/" + caseId);
            Path workspace = root.resolve("references/final/" + caseId + "/workspace");
            assertEquals(treeSha256(fixture), treeSha256(workspace), caseId);

            Path evidenceFile = root.resolve("references/final/" + caseId + "/evidence.json");
            ObjectNode evidence = (ObjectNode) JSON.readTree(evidenceFile.toFile());
            assertEquals(0, evidence.withArray("toolExecutions").size(), caseId);
            ProcessResult reference = runVerifier(root, caseId, workspace, evidenceFile);
            assertEquals(0, reference.exitCode(), caseId + ": " + reference.output());
            VerifierScoringReport referenceReport = VerifierScoringReport.parse(
                    reference.output().trim());
            ScoreCalculator.Result referenceScore = ScoreCalculator.calculate(
                    contract, referenceReport, ScoreCalculator.JudgeAvailability.UNAVAILABLE);
            assertTrue(referenceScore.strictSuccess(), caseId);
            assertEquals(100, referenceScore.score(), caseId);

            ObjectNode malformed = evidence.deepCopy();
            ObjectNode answer = (ObjectNode) JSON.readTree(malformed.path("answer").asText());
            answer.put("unexpectedCandidateTotal", 100);
            malformed.put("answer", JSON.writeValueAsString(answer));
            Path malformedFile = privateParent.resolve(caseId + "-malformed-answer.json");
            JSON.writeValue(malformedFile.toFile(), malformed);
            ProcessResult rejectedAnswer = runVerifier(root, caseId, workspace, malformedFile);
            assertEquals(0, rejectedAnswer.exitCode(), caseId + ": " + rejectedAnswer.output());
            VerifierScoringReport rejectedReport = VerifierScoringReport.parse(
                    rejectedAnswer.output().trim());
            ScoreCalculator.Result partial = ScoreCalculator.calculate(
                    contract, rejectedReport, ScoreCalculator.JudgeAvailability.UNAVAILABLE);
            assertFalse(partial.strictSuccess(), caseId);
            assertEquals(90, partial.score(), caseId);
        }

        Path g1Evidence = root.resolve("references/final/G1/evidence.json");
        ObjectNode withTool = (ObjectNode) JSON.readTree(g1Evidence.toFile());
        JsonNode a1Tool = ((ObjectNode) JSON.readTree(root.resolve(
                "references/final/A1/evidence.json").toFile()))
                .withArray("toolExecutions").get(0);
        withTool.withArray("toolExecutions").add(a1Tool);
        ((ObjectNode) withTool.path("llmMetrics")).put("toolCalls", 1);
        Path withToolFile = privateParent.resolve("G1-with-tool.json");
        JSON.writeValue(withToolFile.toFile(), withTool);
        ProcessResult toolViolation = runVerifier(root, "G1",
                root.resolve("references/final/G1/workspace"), withToolFile);
        assertEquals(0, toolViolation.exitCode(), toolViolation.output());
        VerifierScoringReport toolReport = VerifierScoringReport.parse(toolViolation.output().trim());
        assertTrue(toolReport.hardGates().stream()
                .anyMatch(VerifierScoringReport.HardGateResult::violated));
        assertTrue(toolReport.assertions().stream()
                .filter(assertion -> "G1.tool_count_consistency".equals(assertion.id()))
                .noneMatch(VerifierScoringReport.AssertionResult::pass));

        ObjectNode mismatchedCount = (ObjectNode) JSON.readTree(g1Evidence.toFile());
        ((ObjectNode) mismatchedCount.path("llmMetrics")).put("toolCalls", 1);
        Path mismatchedCountFile = privateParent.resolve("G1-mismatched-tool-count.json");
        JSON.writeValue(mismatchedCountFile.toFile(), mismatchedCount);
        ProcessResult mismatch = runVerifier(root, "G1",
                root.resolve("references/final/G1/workspace"), mismatchedCountFile);
        assertEquals(0, mismatch.exitCode(), mismatch.output());
        VerifierScoringReport mismatchReport = VerifierScoringReport.parse(mismatch.output().trim());
        assertTrue(mismatchReport.hardGates().stream()
                .anyMatch(VerifierScoringReport.HardGateResult::violated));
        assertTrue(mismatchReport.hardGates().stream()
                .flatMap(gate -> gate.evidenceRefs().stream())
                .anyMatch("trajectory:llm-tool-calls-equal-executions-and-zero"::equals));

        Path g2Workspace = root.resolve("references/final/G2/workspace");
        Files.writeString(g2Workspace.resolve("CASE-METADATA.json"), "\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        ProcessResult workspaceViolation = runVerifier(root, "G2", g2Workspace,
                root.resolve("references/final/G2/evidence.json"));
        assertEquals(0, workspaceViolation.exitCode(), workspaceViolation.output());
        VerifierScoringReport workspaceReport = VerifierScoringReport.parse(
                workspaceViolation.output().trim());
        assertTrue(workspaceReport.hardGates().stream()
                .anyMatch(VerifierScoringReport.HardGateResult::violated));
    }

    @Test
    void g2FourSeedScenariosHaveDistinctAnswersDependencyOraclesAndOneManualGolden()
            throws Exception {
        Set<String> answerDigests = new HashSet<>();
        Set<String> primaryCauses = new HashSet<>();
        Set<String> dependencyDags = new HashSet<>();
        Set<String> evidenceBindings = new HashSet<>();
        Set<String> normalizedTimelines = new HashSet<>();
        Set<String> uncertainties = new HashSet<>();
        JsonNode manualGolden = null;

        for (int index = 0; index < G2_FOUR_SCENARIO_SEEDS.size(); index++) {
            Path root = new FinalSourceGenerator().generateIncompleteSource(
                    request("g2-scenario-" + index, G2_FOUR_SCENARIO_SEEDS.get(index))).sourceRoot();
            JsonNode evidence = JSON.readTree(
                    root.resolve("references/final/G2/evidence.json").toFile());
            JsonNode answer = JSON.readTree(evidence.path("answer").asText());
            JsonNode oracle = JSON.readTree(
                    root.resolve("validators/final/_private/oracles/G2.json").toFile());
            answerDigests.add(sha256(evidence.path("answer").asText()));
            primaryCauses.add(oracle.path("expectedPrimaryCause").asText());
            dependencyDags.add(oracle.path("expectedDependencyEdges").toString());
            evidenceBindings.add(oracle.path("expectedEdgeEvidence").toString());
            normalizedTimelines.add(oracle.path("expectedNormalizedTimeline").toString());
            uncertainties.add(oracle.path("expectedUncertainty").asText());
            assertEquals(oracle.path("expectedScenarioId"), answer.path("scenarioId"));
            assertEquals(oracle.path("expectedDependencyEdges"), answer.path("dependencyEdges"));
            assertEquals(oracle.path("expectedEdgeEvidence"), answer.path("edgeEvidence"));
            if (G2_FOUR_SCENARIO_SEEDS.get(index).equals("0".repeat(64))) {
                manualGolden = oracle;
            }
        }

        assertEquals(4, answerDigests.size());
        assertEquals(4, primaryCauses.size());
        assertEquals(4, dependencyDags.size());
        assertEquals(4, evidenceBindings.size());
        assertEquals(4, normalizedTimelines.size());
        assertEquals(Set.of("none_material", "internal_upstream_failure_mode_unobserved"),
                uncertainties);

        assertTrue(manualGolden != null);
        assertEquals("environment-override-2395e9e7",
                manualGolden.path("expectedScenarioId").asText());
        assertEquals("stale_environment_override_present",
                manualGolden.path("expectedPrimaryCause").asText());
        assertEquals(1500, manualGolden.path("expectedEffectiveTimeoutMs").asInt());
        assertEquals(2200, manualGolden.path("expectedUpstreamCompletionMs").asInt());
        assertEquals(List.of(
                        "stale_environment_override_present",
                        "environment_value_won_precedence",
                        "effective_timeout_was_shortened",
                        "gateway_deadline_fired_before_upstream_completion"),
                JSON.convertValue(manualGolden.path("expectedOrderedCauses"), List.class));
        assertEquals(List.of(
                        "CFG-1=2032-04-17T10:28:00.000Z",
                        "ENV-1=2032-04-17T10:30:00.000Z",
                        "PROC-1=2032-04-17T10:34:00.000Z",
                        "RESOLVE-1=2032-04-17T10:35:00.100Z",
                        "GW-START=2032-04-17T10:40:00.000Z",
                        "GW-TIMEOUT=2032-04-17T10:40:01.500Z",
                        "UP-DONE=2032-04-17T10:40:02.200Z"),
                JSON.convertValue(manualGolden.path("expectedNormalizedTimeline"), List.class));
        assertEquals(List.of("POLICY-1", "ENV-1", "PROC-1", "RESOLVE-1"),
                JSON.convertValue(manualGolden.path("expectedEdgeEvidence")
                        .path("stale_environment_override_present->environment_value_won_precedence"),
                        List.class));
    }

    @Test
    void reasoningAnswerParserRejectsDuplicateAndNonFiniteNumbers() throws Exception {
        FinalSourceGenerator.GenerationResult result = new FinalSourceGenerator()
                .generateIncompleteSource(request("reasoning-strict-json", SEED_A));
        Path root = result.sourceRoot();
        Path evidenceFile = root.resolve("references/final/G1/evidence.json");
        ObjectNode reference = (ObjectNode) JSON.readTree(evidenceFile.toFile());
        String validAnswer = reference.path("answer").asText();
        List<String> invalidAnswers = List.of(
                "{\"storedEnergyDropJ\":0," + validAnswer.substring(1),
                validAnswer.replaceFirst("\"storedEnergyDropJ\"\\s*:\\s*[^,}]+",
                        "\"storedEnergyDropJ\":NaN"),
                validAnswer.replaceFirst("\"deliveredEnergyJ\"\\s*:\\s*[^,}]+",
                        "\"deliveredEnergyJ\":Infinity"),
                validAnswer.replaceFirst("\"averagePowerW\"\\s*:\\s*[^,}]+",
                        "\"averagePowerW\":1e400"));
        ScoringContract contract = ScoringContract.load(root.resolve(
                "validators/final/_private/scoring-contracts/G1.json"));

        for (int index = 0; index < invalidAnswers.size(); index++) {
            ObjectNode invalid = reference.deepCopy();
            invalid.put("answer", invalidAnswers.get(index));
            Path invalidFile = privateParent.resolve("G1-invalid-json-" + index + ".json");
            JSON.writeValue(invalidFile.toFile(), invalid);
            ProcessResult process = runVerifier(root, "G1",
                    root.resolve("references/final/G1/workspace"), invalidFile);
            assertEquals(0, process.exitCode(), process.output());
            VerifierScoringReport report = VerifierScoringReport.parse(process.output().trim());
            assertTrue(report.assertions().stream()
                    .filter(assertion -> "G1.structured_answer".equals(assertion.id()))
                    .noneMatch(VerifierScoringReport.AssertionResult::pass),
                    "invalid answer index=" + index);
            ScoreCalculator.Result score = ScoreCalculator.calculate(
                    contract, report, ScoreCalculator.JudgeAvailability.UNAVAILABLE);
            assertFalse(score.strictSuccess(), "invalid answer index=" + index);
            assertTrue(score.score() < 100, "invalid answer index=" + index);
        }
    }

    @Test
    void a3FixtureBindsTheActualTypeScriptJavaPythonSourceChain() throws Exception {
        Path root = new FinalSourceGenerator()
                .generateIncompleteSource(request("a3-chain", SEED_A)).sourceRoot()
                .resolve("fixtures/final/A3");
        Path route;
        Path service;
        Path adapter;
        try (var files = Files.walk(root)) {
            List<Path> values = files.filter(Files::isRegularFile).toList();
            route = values.stream().filter(path -> path.toString().endsWith(".ts")
                    && path.getFileName().toString().startsWith("route-")).findFirst().orElseThrow();
            service = values.stream().filter(path -> path.toString().endsWith(".java"))
                    .findFirst().orElseThrow();
            adapter = values.stream().filter(path -> path.toString().endsWith(".py")
                    && path.getFileName().toString().startsWith("receipt_store_"))
                    .findFirst().orElseThrow();
        }
        String routeSource = Files.readString(route);
        String serviceSource = Files.readString(service);
        String adapterSource = Files.readString(adapter);
        assertTrue(routeSource.contains("validateAndDispatch"));
        assertTrue(serviceSource.contains("new ProcessBuilder"));
        assertTrue(serviceSource.contains(root.relativize(adapter).toString().replace('\\', '/')));
        assertTrue(adapterSource.contains("def persist_receipt"));
        assertTrue(adapterSource.contains("sqlite3.connect"));
    }

    @Test
    void verifierRejectsLegacyAuthorityFieldsAndFailedToolsCannotSatisfyTrajectory()
            throws Exception {
        FinalSourceGenerator.GenerationResult result = new FinalSourceGenerator()
                .generateIncompleteSource(request("strict-envelope", SEED_A));
        Path root = result.sourceRoot();
        Path original = root.resolve("references/final/A1/evidence.json");
        ObjectNode legacy = (ObjectNode) JSON.readTree(original.toFile());
        legacy.putArray("changedFiles");
        Path legacyFile = privateParent.resolve("legacy-evidence.json");
        JSON.writeValue(legacyFile.toFile(), legacy);

        ProcessResult rejected = runVerifier(root, "A1",
                root.resolve("references/final/A1/workspace"), legacyFile);
        assertNotEquals(0, rejected.exitCode());
        VerifierScoringReport.parse(rejected.output().trim());

        ObjectNode unsuccessful = (ObjectNode) JSON.readTree(original.toFile());
        ((ObjectNode) unsuccessful.withArray("toolExecutions").get(0)).put("successful", false);
        Path unsuccessfulFile = privateParent.resolve("unsuccessful-evidence.json");
        JSON.writeValue(unsuccessfulFile.toFile(), unsuccessful);
        ProcessResult scored = runVerifier(root, "A1",
                root.resolve("references/final/A1/workspace"), unsuccessfulFile);
        assertEquals(0, scored.exitCode());
        ScoreCalculator.Result resultScore = ScoreCalculator.calculate(
                ScoringContract.load(root.resolve(
                        "validators/final/_private/scoring-contracts/A1.json")),
                VerifierScoringReport.parse(scored.output().trim()),
                ScoreCalculator.JudgeAvailability.AVAILABLE);
        assertFalse(resultScore.strictSuccess());

        Path a2Original = root.resolve("references/final/A2/evidence.json");
        ObjectNode truncated = (ObjectNode) JSON.readTree(a2Original.toFile());
        ObjectNode exact = (ObjectNode) truncated.withArray("toolExecutions").get(0);
        exact.put("resultChars", exact.path("resultChars").asLong() + 1);
        Path truncatedFile = privateParent.resolve("truncated-exact-evidence.json");
        JSON.writeValue(truncatedFile.toFile(), truncated);
        ProcessResult a2 = runVerifier(root, "A2",
                root.resolve("references/final/A2/workspace"), truncatedFile);
        assertEquals(0, a2.exitCode());
        ScoreCalculator.Result a2Score = ScoreCalculator.calculate(
                ScoringContract.load(root.resolve(
                        "validators/final/_private/scoring-contracts/A2.json")),
                VerifierScoringReport.parse(a2.output().trim()),
                ScoreCalculator.JudgeAvailability.AVAILABLE);
        assertFalse(a2Score.strictSuccess());
    }

    @Test
    void generatedTreeContainsNoLinksHardlinksRawSeedOrCredentialLikePayload() throws Exception {
        FinalSourceGenerator.GenerationResult result = new FinalSourceGenerator()
                .generateIncompleteSource(request("security", SEED_A));
        Path root = result.sourceRoot();
        Set<Object> fileKeys = new HashSet<>();

        try (var stream = Files.walk(root)) {
            for (Path path : stream.toList()) {
                assertFalse(Files.isSymbolicLink(path), path.toString());
                Set<PosixFilePermission> mode = Files.getPosixFilePermissions(
                        path, LinkOption.NOFOLLOW_LINKS);
                assertTrue(mode.stream().noneMatch(permission ->
                                permission.name().startsWith("GROUP_")
                                        || permission.name().startsWith("OTHERS_")),
                        path.toString());
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    Number links = (Number) Files.getAttribute(
                            path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
                    assertEquals(1L, links.longValue(), path.toString());
                    Object key = Files.readAttributes(
                            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
                    assertTrue(fileKeys.add(key), path.toString());
                    assertFalse(Files.readString(path, StandardCharsets.UTF_8).contains(SEED_A),
                            path.toString());
                }
            }
        }
    }

    @Test
    void d1SeparatesCandidateFailuresFromMissingOrContradictoryTrustedEvidence() throws Exception {
        var generated = new FinalSourceGenerator().generateIncompleteSource(request("d1-negative", SEED_A));
        Path root = generated.sourceRoot();
        Path workspace = root.resolve("references/final/D1/workspace");
        var reference = (ObjectNode) JSON.readTree(root.resolve("references/final/D1/evidence.json").toFile());
        Path evidence = privateParent.resolve("d1-control.json");
        JSON.writeValue(evidence.toFile(), reference);
        var positive = runVerifier(root, "D1", workspace, evidence);
        assertEquals(0, positive.exitCode(), positive.output());
        assertEquals(100, JSON.readTree(positive.output()).path("components").get(0).path("earnedPoints").asInt());

        List<java.util.function.Consumer<ObjectNode>> invalidInputs = List.of(
                e -> e.remove("mockMcp"),
                e -> ((ObjectNode) e.path("mockMcp")).put("mockSourceSha256", "0".repeat(64)),
                e -> ((com.fasterxml.jackson.databind.node.ArrayNode) e.path("mockMcp").path("events")).remove(3),
                e -> ((ObjectNode) e.path("mockMcp").path("events").get(3)).put("outcome", "DISTRACTOR_READ"),
                e -> ((ObjectNode) e.path("toolExecutions").get(0)).put("resultPreview", "forged"),
                e -> ((ObjectNode) e.path("mockMcp")).put("sideEffects", 1),
                e -> ((ObjectNode) e.path("mockMcp")).put("schemaVersion", true),
                e -> ((ObjectNode) e.path("llmMetrics")).put("usageComplete", "true"),
                e -> e.put("verifierWorkspaceFileCount", true));
        for (int i = 0; i < invalidInputs.size(); i++) {
            var bad = reference.deepCopy();
            invalidInputs.get(i).accept(bad);
            JSON.writeValue(evidence.toFile(), bad);
            var result = runVerifier(root, "D1", workspace, evidence);
            assertEquals(2, result.exitCode(), "invalid evidence control " + i + ": " + result.output());
            assertTrue(result.output().contains("input or infrastructure invalid"));
        }

        var localAttempt = reference.deepCopy();
        var blocked = ((ObjectNode) reference.path("toolExecutions").get(0)).deepCopy();
        blocked.put("ordinal", 2).put("callId", "blocked-local").put("toolName", "execute_command")
                .put("argumentsJson", "{\"command\":\"pwd\"}").put("resultPreview", "Blocked")
                .put("resultSha256", sha256("Blocked")).put("resultChars", 7).put("successful", false);
        ((com.fasterxml.jackson.databind.node.ArrayNode) localAttempt.path("toolExecutions")).add(blocked);
        ((ObjectNode) localAttempt.path("llmMetrics")).put("toolCalls", 2);
        JSON.writeValue(evidence.toFile(), localAttempt);
        var localResult = runVerifier(root, "D1", workspace, evidence);
        assertEquals(0, localResult.exitCode(), localResult.output());
        var localReport = JSON.readTree(localResult.output());
        assertEquals(0, localReport.path("components").get(0).path("earnedPoints").asInt());
        assertTrue(localReport.path("hardGates").get(1).path("violated").asBoolean());

        JSON.writeValue(evidence.toFile(), reference);
        Files.writeString(workspace.resolve("README.md"), "Candidate changed the read-only task workspace");
        var changed = runVerifier(root, "D1", workspace, evidence);
        assertEquals(0, changed.exitCode(), changed.output());
        var changedReport = JSON.readTree(changed.output());
        assertEquals(0, changedReport.path("components").get(0).path("earnedPoints").asInt());
        assertTrue(changedReport.path("hardGates").get(2).path("violated").asBoolean());
    }

    @Test
    void d2ReplaysStateAndMatchesParallelTracesWithoutConvertingEvidenceDefectsToZeros() throws Exception {
        Path root = new FinalSourceGenerator().generateIncompleteSource(request("d2-negative", SEED_A)).sourceRoot();
        Path workspace = root.resolve("references/final/D2/workspace");
        var reference = (ObjectNode) JSON.readTree(root.resolve("references/final/D2/evidence.json").toFile());
        Path evidence = privateParent.resolve("d2-control.json");
        List<java.util.function.Consumer<ObjectNode>> invalidInputs = List.of(
                e -> e.remove("mockMcp"),
                e -> ((ObjectNode) e.path("mockMcp")).put("mockSourceSha256", "0".repeat(64)),
                e -> ((ObjectNode) e.path("mockMcp").path("initialStateDigests")).put("directory", "0".repeat(64)),
                e -> ((ObjectNode) e.path("mockMcp").path("finalStateDigests")).remove("calendar"),
                e -> ((com.fasterxml.jackson.databind.node.ArrayNode) e.path("mockMcp").path("events")).remove(11),
                e -> ((ObjectNode) e.path("mockMcp").path("events").get(9)).put("stateAfterSha256", "0".repeat(64)),
                e -> ((ObjectNode) e.path("mockMcp").path("events").get(10)).put("resultSha256", "0".repeat(64)),
                e -> ((ObjectNode) e.path("mockMcp").path("events").get(11)).put("server", "directory"),
                e -> ((ObjectNode) e.path("toolExecutions").get(0)).put("resultPreview", "forged"),
                e -> ((ObjectNode) e.path("mockMcp")).put("sideEffects", 1),
                e -> ((ObjectNode) e.path("mockMcp")).put("schemaVersion", true),
                e -> ((ObjectNode) e.path("llmMetrics")).put("toolCalls", "3"),
                e -> e.put("verifierWorkspaceFileCount", true));
        for (int i = 0; i < invalidInputs.size(); i++) {
            var bad = reference.deepCopy(); invalidInputs.get(i).accept(bad);
            JSON.writeValue(evidence.toFile(), bad);
            var result = runVerifier(root, "D2", workspace, evidence);
            assertEquals(2, result.exitCode(), "invalid D2 evidence " + i + ": " + result.output());
        }
        var reordered = reference.deepCopy();
        var traces = (com.fasterxml.jackson.databind.node.ArrayNode) reordered.path("toolExecutions");
        var first = traces.get(0).deepCopy(); var last = traces.get(2).deepCopy();
        ((ObjectNode) first).put("ordinal", 3); ((ObjectNode) last).put("ordinal", 1);
        traces.set(0, last); traces.set(2, first);
        JSON.writeValue(evidence.toFile(), reordered);
        var reorderedResult = runVerifier(root, "D2", workspace, evidence);
        assertEquals(0, reorderedResult.exitCode(), reorderedResult.output());
        assertEquals(100, JSON.readTree(reorderedResult.output()).path("components").get(0).path("earnedPoints").asInt());

        var nativeArguments = reference.deepCopy();
        var directory = (ObjectNode) nativeArguments.path("toolExecutions").get(0);
        String fullName = JSON.readTree(directory.path("argumentsJson").asText()).path("full_name").asText();
        directory.put("argumentsJson", "{\"full_name\":\"discarded\",\"full_name\":" + JSON.writeValueAsString(fullName) + "} {}");
        JSON.writeValue(evidence.toFile(), nativeArguments);
        var nativeArgumentsResult = runVerifier(root, "D2", workspace, evidence);
        assertEquals(0, nativeArgumentsResult.exitCode(), nativeArgumentsResult.output());
        assertEquals(100, JSON.readTree(nativeArgumentsResult.output()).path("components").get(0).path("earnedPoints").asInt());

        var noCalls = reference.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) noCalls.path("mockMcp").path("events")).removeAll();
        ((com.fasterxml.jackson.databind.node.ArrayNode) noCalls.path("toolExecutions")).removeAll();
        ((ObjectNode) noCalls.path("llmMetrics")).put("toolCalls", 0);
        noCalls.put("answer", "{}");
        JSON.writeValue(evidence.toFile(), noCalls);
        var noCallsResult = runVerifier(root, "D2", workspace, evidence);
        assertEquals(0, noCallsResult.exitCode(), noCallsResult.output());
        assertEquals(0, JSON.readTree(noCallsResult.output()).path("components").get(0).path("earnedPoints").asInt());

        var local = reference.deepCopy();
        var blocked = ((ObjectNode) reference.path("toolExecutions").get(0)).deepCopy();
        blocked.put("ordinal", 4).put("callId", "blocked-local").put("toolName", "execute_command")
                .put("argumentsJson", "{}").put("resultPreview", "Blocked").put("resultSha256", sha256("Blocked"))
                .put("resultChars", 7).put("successful", false);
        ((com.fasterxml.jackson.databind.node.ArrayNode) local.path("toolExecutions")).add(blocked);
        ((ObjectNode) local.path("llmMetrics")).put("toolCalls", 4);
        JSON.writeValue(evidence.toFile(), local);
        var localResult = runVerifier(root, "D2", workspace, evidence);
        assertEquals(0, localResult.exitCode(), localResult.output());
        assertTrue(JSON.readTree(localResult.output()).path("hardGates").get(1).path("violated").asBoolean());
        assertEquals(0, JSON.readTree(localResult.output()).path("components").get(0).path("earnedPoints").asInt());

        JSON.writeValue(evidence.toFile(), reference);
        Files.writeString(workspace.resolve("README.md"), "Candidate changed workspace");
        var changed = runVerifier(root, "D2", workspace, evidence);
        assertEquals(0, changed.exitCode(), changed.output());
        assertTrue(JSON.readTree(changed.output()).path("hardGates").get(2).path("violated").asBoolean());
        assertEquals(0, JSON.readTree(changed.output()).path("components").get(0).path("earnedPoints").asInt());
    }

    @Test
    void formalGenerationAndUnsafeRequestsFailBeforePublishingAnything() throws Exception {
        FinalSourceGenerator generator = new FinalSourceGenerator();
        Path formal = privateParent.resolve("formal");
        assertThrows(IllegalStateException.class,
                () -> generator.generateFinalSource(new FinalSourceGenerator.GenerationRequest(
                        formal, SEED_A)));
        assertFalse(Files.exists(formal, LinkOption.NOFOLLOW_LINKS));

        assertThrows(IllegalArgumentException.class,
                () -> generator.generateIncompleteSource(
                        new FinalSourceGenerator.GenerationRequest(
                                privateParent.resolve("weak"), "1234")));
        assertThrows(IllegalArgumentException.class,
                () -> generator.generateIncompleteSource(
                        new FinalSourceGenerator.GenerationRequest(Path.of("relative"), SEED_A)));
        Path existing = privateParent.resolve("existing");
        Files.createDirectory(existing);
        assertThrows(IOException.class,
                () -> generator.generateIncompleteSource(
                        new FinalSourceGenerator.GenerationRequest(existing, SEED_A)));
    }

    private FinalSourceGenerator.GenerationRequest request(String name, String seed) {
        return new FinalSourceGenerator.GenerationRequest(privateParent.resolve(name), seed);
    }

    private static ProcessResult runVerifier(Path root,
                                             String caseId,
                                             Path workspace,
                                             Path evidence) throws Exception {
        Process process = new ProcessBuilder(
                root.resolve("validators/final/" + caseId).toString(),
                workspace.toString(),
                evidence.toString())
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(90, TimeUnit.SECONDS), "verifier timed out: " + caseId);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.exitValue(), output);
    }

    private static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) {
                    throw error;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String treeSha256(Path root) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<Path> files;
        try (var stream = Files.walk(root)) {
            files = stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted((left, right) -> root.relativize(left).toString()
                            .compareTo(root.relativize(right).toString()))
                    .toList();
        }
        for (Path file : files) {
            digest.update(root.relativize(file).toString().replace('\\', '/')
                    .getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Files.readAllBytes(file));
            digest.update((byte) '\n');
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
