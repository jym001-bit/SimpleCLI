package com.paicli.eval.runner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifierTest {

    @Test
    void passesWhenAllKeywordsPresent() {
        Verifier.Result result = Verifier.verify(
                new TaskSpec.VerifySpec(null, 0, List.of("hello", "stub")),
                "hello from stub",
                null);

        assertTrue(result.ok());
    }

    @Test
    void reportsMissingKeywords() {
        Verifier.Result result = Verifier.verify(
                new TaskSpec.VerifySpec(null, 0, List.of("hello", "absent-token")),
                "hello from stub",
                null);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("absent-token"));
    }

    @Test
    void nullAnswerFailsKeywordCheck() {
        Verifier.Result result = Verifier.verify(
                new TaskSpec.VerifySpec(null, 0, List.of("hello")),
                null,
                null);

        assertFalse(result.ok());
    }

    @Test
    void emptySpecAlwaysPasses() {
        assertTrue(Verifier.verify(null, null, null).ok());
    }
}
