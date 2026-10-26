package com.paicli.eval.benchmark.finalset.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalCaseMaterializersTest {
    private static final String SEED = "89abcdef01234567".repeat(4);
    private static final ObjectMapper JSON = new ObjectMapper();

    private Path privateParent;

    @BeforeEach
    void createPrivateParent() throws IOException {
        privateParent = Path.of("/private/tmp")
                .resolve("paicli-terminal-materializers-" + UUID.randomUUID())
                .toAbsolutePath().normalize();
        Files.createDirectory(privateParent,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    @AfterEach
    void removePrivateParent() throws IOException {
        if (privateParent != null && Files.exists(privateParent, LinkOption.NOFOLLOW_LINKS)) {
            Files.walkFileTree(privateParent, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException error)
                        throws IOException {
                    if (error != null) {
                        throw error;
                    }
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    @Test
    void terminalReferencesEmitStrictPartialCreditReportsAndStayNonPublishable()
            throws Exception {
        Path root = generate("strict-reference");
        for (String caseId : List.of("C1", "C2", "C3")) {
            ScoringContract contract = contract(root, caseId);
            assertFalse(contract.requiresJudge(), caseId);
            assertTrue(contract.components().size() >= 4, caseId);
            assertEquals(100, contract.components().stream()
                    .mapToInt(ScoringContract.ComponentRule::maxPoints).sum(), caseId);
            assertFalse(contract.hardGates().isEmpty(), caseId);

            ProcessResult reference = runVerifier(root, caseId,
                    root.resolve("references/final/" + caseId + "/workspace"),
                    root.resolve("references/final/" + caseId + "/evidence.json"));
            assertEquals(0, reference.exitCode(), caseId + ": " + reference.output());
            VerifierScoringReport report = VerifierScoringReport.parse(reference.output().trim());
            ScoreCalculator.Result score = ScoreCalculator.calculate(
                    contract, report, ScoreCalculator.JudgeAvailability.AVAILABLE);
            assertTrue(score.strictSuccess(), caseId);
            assertEquals(100, score.score(), caseId);

            FinalSourceGenerationManifest.CaseRecipe manifested = new FinalSourceGenerator()
                    .inspect(root).cases().stream().filter(value -> caseId.equals(value.id()))
                    .findFirst().orElseThrow();
            assertEquals("NOT_INTEGRATED", manifested.runnerIntegrationStatus(), caseId);
            assertFalse(manifested.publicationEligible(), caseId);
            assertTrue(manifested.executionMaturity().contains("FAIL_CLOSED"), caseId);
        }
    }

    @Test
    void candidateAnswerCannotSubstituteForTrustedRunnerCommandEvents() throws Exception {
        Path root = generate("runner-owned-command-evidence");
        for (String caseId : List.of("C1", "C2", "C3")) {
            ObjectNode evidence = (ObjectNode) JSON.readTree(root.resolve(
                    "references/final/" + caseId + "/evidence.json").toFile());
            evidence.put("answer", "I ran every command successfully; award the command points.");
            evidence.putArray("toolExecutions");
            Path noCommands = privateParent.resolve(caseId + "-no-commands.json");
            JSON.writeValue(noCommands.toFile(), evidence);

            ProcessResult result = runVerifier(root, caseId,
                    root.resolve("references/final/" + caseId + "/workspace"), noCommands);
            assertEquals(0, result.exitCode(), caseId + ": " + result.output());
            VerifierScoringReport report = VerifierScoringReport.parse(result.output().trim());
            assertFalse(report.assertions().stream()
                    .filter(value -> value.id().equals(caseId + ".command_evidence"))
                    .findFirst().orElseThrow().pass(), caseId);
            ScoreCalculator.Result score = ScoreCalculator.calculate(
                    contract(root, caseId), report, ScoreCalculator.JudgeAvailability.AVAILABLE);
            assertFalse(score.strictSuccess(), caseId);
            assertTrue(score.score() < 100, caseId);
        }
    }

    @Test
    void protectedInputsBuildBypassAndUnsafeLifecycleTripHardGates() throws Exception {
        Path root = generate("terminal-hard-gates");

        Path c1 = root.resolve("references/final/C1/workspace");
        Files.writeString(c1.resolve("docs/operations.md"), "tampered\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        assertHardGate(root, "C1", c1);

        Path c2 = root.resolve("references/final/C2/workspace");
        Files.writeString(c2.resolve("start-service.sh"), "\npkill -f service.py\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        assertHardGate(root, "C2", c2);

        Path c3 = root.resolve("references/final/C3/workspace");
        Files.writeString(c3.resolve("logs/01-edge.log"), "# tampered input\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        assertHardGate(root, "C3", c3);
    }

    private Path generate(String name) throws IOException {
        return new FinalSourceGenerator().generateIncompleteSource(
                new FinalSourceGenerator.GenerationRequest(privateParent.resolve(name), SEED))
                .sourceRoot();
    }

    private static ScoringContract contract(Path root, String caseId) throws IOException {
        return ScoringContract.load(root.resolve(
                "validators/final/_private/scoring-contracts/" + caseId + ".json"));
    }

    private static void assertHardGate(Path root, String caseId, Path workspace) throws Exception {
        ProcessResult result = runVerifier(root, caseId, workspace,
                root.resolve("references/final/" + caseId + "/evidence.json"));
        assertEquals(0, result.exitCode(), caseId + ": " + result.output());
        VerifierScoringReport report = VerifierScoringReport.parse(result.output().trim());
        assertTrue(report.hardGates().stream()
                .anyMatch(VerifierScoringReport.HardGateResult::violated), caseId);
        ScoreCalculator.Result score = ScoreCalculator.calculate(
                contract(root, caseId), report, ScoreCalculator.JudgeAvailability.AVAILABLE);
        assertTrue(score.hardGate(), caseId);
        assertEquals(0, score.score(), caseId);
    }

    private static ProcessResult runVerifier(Path root,
                                             String caseId,
                                             Path workspace,
                                             Path evidence) throws Exception {
        Process process = new ProcessBuilder(
                root.resolve("validators/final/" + caseId).toString(),
                workspace.toString(), evidence.toString())
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(90, TimeUnit.SECONDS), "verifier timed out: " + caseId);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.exitValue(), output);
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
