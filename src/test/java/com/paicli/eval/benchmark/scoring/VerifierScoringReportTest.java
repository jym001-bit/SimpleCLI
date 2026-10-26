package com.paicli.eval.benchmark.scoring;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VerifierScoringReportTest {
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);

    @Test
    void parsesStrictReportWithoutAcceptingAReporterSuppliedTotal() throws Exception {
        VerifierScoringReport report = VerifierScoringReport.parse(validJson());
        assertEquals("A1", report.caseId());
        assertEquals(2, report.components().size());
        assertEquals(report,
                VerifierScoringReport.parse(validJson().getBytes(StandardCharsets.UTF_8)));

        assertInvalid(validJson().replaceFirst("\"caseId\":\"A1\"",
                "\"caseId\":\"A1\",\"totalScore\":100"));
        assertInvalid(validJson().replaceFirst("\"pass\":true", "\"pass\":true,\"pass\":false"));
        assertInvalid(validJson() + " []");
        assertInvalid(validJson().replace("\"source\":\"JUDGE\"", "\"source\":null"));
        assertInvalid(validJson().replace("\"violated\":false,", ""));
        assertInvalid(validJson().replace("\"earnedPoints\":70", "\"earnedPoints\":70.0"));
        assertInvalid(validJson().replace("\"earnedPoints\":70", "\"earnedPoints\":\"70\""));
        assertThrows(IOException.class,
                () -> VerifierScoringReport.parse(new byte[ScoringJson.MAX_JSON_BYTES + 1]));
    }

    @Test
    void rejectsDuplicateIdsBoundsAndUnsafeOrDuplicateEvidenceReferences() {
        VerifierScoringReport valid = report();
        assertThrows(IllegalArgumentException.class, () -> new VerifierScoringReport(
                1, "A1",
                List.of(valid.assertions().get(0), valid.assertions().get(0)),
                valid.hardGates(), valid.components(), SHA_A, SHA_B));
        assertThrows(IllegalArgumentException.class, () -> new VerifierScoringReport.ComponentResult(
                "facts", 71, 70, ScoreSource.DETERMINISTIC, List.of("component:facts")));
        assertThrows(IllegalArgumentException.class, () -> new VerifierScoringReport.ComponentResult(
                "facts", -1, 70, ScoreSource.DETERMINISTIC, List.of("component:facts")));
        assertThrows(IllegalArgumentException.class, () -> new VerifierScoringReport.AssertionResult(
                "assert.correct", true, List.of("/private/path")));
        assertThrows(IllegalArgumentException.class, () -> new VerifierScoringReport.AssertionResult(
                "assert.correct", true, List.of("event:1", "event:1")));
    }

    static VerifierScoringReport report() {
        return new VerifierScoringReport(
                1, "A1",
                List.of(
                        new VerifierScoringReport.AssertionResult(
                                "assert.correct", true, List.of("assertion:correct")),
                        new VerifierScoringReport.AssertionResult(
                                "assert.semantic", true, List.of("judge:calibrated"))),
                List.of(new VerifierScoringReport.HardGateResult(
                        "gate.safe", false, List.of("audit:safe"))),
                List.of(
                        new VerifierScoringReport.ComponentResult(
                                "facts", 70, 70, ScoreSource.DETERMINISTIC,
                                List.of("component:facts")),
                        new VerifierScoringReport.ComponentResult(
                                "judge", 25, 30, ScoreSource.JUDGE,
                                List.of("component:judge"))),
                SHA_A, SHA_B);
    }

    static String validJson() {
        return """
                {"schemaVersion":1,"caseId":"A1",
                 "assertions":[
                   {"id":"assert.correct","pass":true,"evidenceRefs":["assertion:correct"]},
                   {"id":"assert.semantic","pass":true,"evidenceRefs":["judge:calibrated"]}],
                 "hardGates":[
                   {"id":"gate.safe","violated":false,"evidenceRefs":["audit:safe"]}],
                 "components":[
                   {"id":"facts","earnedPoints":70,"maxPoints":70,"source":"DETERMINISTIC",
                    "evidenceRefs":["component:facts"]},
                   {"id":"judge","earnedPoints":25,"maxPoints":30,"source":"JUDGE",
                    "evidenceRefs":["component:judge"]}],
                 "verifierSha256":"%s","toolchainSha256":"%s"}
                """.formatted(SHA_A, SHA_B);
    }

    private static void assertInvalid(String json) {
        assertThrows(IOException.class, () -> VerifierScoringReport.parse(json));
    }
}
