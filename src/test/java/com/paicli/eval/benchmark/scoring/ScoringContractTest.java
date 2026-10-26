package com.paicli.eval.benchmark.scoring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoringContractTest {
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);

    @Test
    void parsesFrozenContractAndDetectsJudgeRequirement(@TempDir Path tempDir) throws Exception {
        ScoringContract contract = ScoringContract.parse(validJson());

        assertEquals("A1", contract.caseId());
        assertEquals(80, contract.strictSuccessMinimum());
        assertEquals(100, contract.components().stream()
                .mapToInt(ScoringContract.ComponentRule::maxPoints).sum());
        assertTrue(contract.requiresJudge());

        Path file = tempDir.resolve("scoring-contract.json");
        Files.writeString(file, validJson());
        assertEquals(contract, ScoringContract.load(file));
    }

    @Test
    void strictJsonRejectsUnknownDuplicateTrailingNullAndMissingFields() {
        assertInvalid(validJson().replaceFirst("\"caseId\":\"A1\"",
                "\"caseId\":\"A1\",\"unknown\":1"));
        assertInvalid(validJson().replaceFirst("\"caseId\":\"A1\"",
                "\"caseId\":\"A1\",\"caseId\":\"A2\""));
        assertInvalid(validJson() + " true");
        assertInvalid(validJson().replace("\"caseId\":\"A1\"", "\"caseId\":null"));
        assertInvalid(validJson().replace("\"strictSuccessMinimum\":80,", ""));
        assertInvalid(validJson().replace("\"mandatory\":true", "\"mandatory\":null"));
    }

    @Test
    void contractRejectsDuplicateIdsUnknownMappingsAndNonHundredPointProfiles() {
        ScoringContract valid = contract();
        assertThrows(IllegalArgumentException.class, () -> new ScoringContract(
                1, "A1", 80,
                List.of(
                        new ScoringContract.AssertionRule("assert.correct", "facts", true),
                        new ScoringContract.AssertionRule("assert.correct", "judge", false)),
                valid.hardGates(), valid.components(), SHA_A, SHA_B));
        assertThrows(IllegalArgumentException.class, () -> new ScoringContract(
                1, "A1", 80,
                List.of(new ScoringContract.AssertionRule("assert.correct", "missing", true)),
                valid.hardGates(), valid.components(), SHA_A, SHA_B));
        assertThrows(IllegalArgumentException.class, () -> new ScoringContract(
                1, "A1", 80, valid.assertions(), valid.hardGates(),
                List.of(
                        new ScoringContract.ComponentRule("facts", 60, ScoreSource.DETERMINISTIC),
                        new ScoringContract.ComponentRule("judge", 30, ScoreSource.JUDGE)),
                SHA_A, SHA_B));
        assertThrows(IllegalArgumentException.class, () -> new ScoringContract(
                1, "A1", 80, valid.assertions(),
                List.of(new ScoringContract.HardGateRule("gate.safe"),
                        new ScoringContract.HardGateRule("gate.safe")),
                valid.components(), SHA_A, SHA_B));
    }

    @Test
    void deterministicOnlyContractDoesNotRequireJudge() {
        ScoringContract contract = new ScoringContract(
                1, "B1", 80,
                List.of(new ScoringContract.AssertionRule("assert.tests", "tests", true)),
                List.of(),
                List.of(new ScoringContract.ComponentRule("tests", 100,
                        ScoreSource.DETERMINISTIC)),
                SHA_A, SHA_B);
        assertFalse(contract.requiresJudge());
    }

    private static ScoringContract contract() {
        return new ScoringContract(
                1, "A1", 80,
                List.of(
                        new ScoringContract.AssertionRule("assert.correct", "facts", true),
                        new ScoringContract.AssertionRule("assert.semantic", "judge", true)),
                List.of(new ScoringContract.HardGateRule("gate.safe")),
                List.of(
                        new ScoringContract.ComponentRule("facts", 70, ScoreSource.DETERMINISTIC),
                        new ScoringContract.ComponentRule("judge", 30, ScoreSource.JUDGE)),
                SHA_A, SHA_B);
    }

    private static String validJson() {
        return """
                {"schemaVersion":1,"caseId":"A1","strictSuccessMinimum":80,
                 "assertions":[
                   {"id":"assert.correct","componentId":"facts","mandatory":true},
                   {"id":"assert.semantic","componentId":"judge","mandatory":true}],
                 "hardGates":[{"id":"gate.safe"}],
                 "components":[
                   {"id":"facts","maxPoints":70,"source":"DETERMINISTIC"},
                   {"id":"judge","maxPoints":30,"source":"JUDGE"}],
                 "verifierSha256":"%s","toolchainSha256":"%s"}
                """.formatted(SHA_A, SHA_B);
    }

    private static void assertInvalid(String json) {
        assertThrows(IOException.class, () -> ScoringContract.parse(json));
    }
}
