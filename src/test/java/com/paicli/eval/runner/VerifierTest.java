package com.paicli.eval.runner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifierTest {

    private static TaskSpec.VerifySpec spec(Integer exitCode, List<String> keywords) {
        return new TaskSpec.VerifySpec(null, exitCode, keywords, null, null, null);
    }

    @Test
    void passesWhenAllKeywordsPresent() {
        Verifier.Result result = Verifier.verify(spec(0, List.of("hello", "stub")), "hello from stub", null);

        assertTrue(result.ok());
    }

    @Test
    void reportsMissingKeywords() {
        Verifier.Result result = Verifier.verify(spec(0, List.of("hello", "absent-token")), "hello from stub", null);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("absent-token"));
    }

    @Test
    void nullAnswerFailsKeywordCheck() {
        Verifier.Result result = Verifier.verify(spec(0, List.of("hello")), null, null);

        assertFalse(result.ok());
    }

    @Test
    void emptySpecAlwaysPasses() {
        assertTrue(Verifier.verify(null, null, null).ok());
    }

    @Test
    void rejectsChangesOutsideAllowedList() {
        TaskSpec.VerifySpec scope = new TaskSpec.VerifySpec(
                null, 0, List.of(),
                List.of("src/main/java/shop/OrderService.java"), null, null);

        assertTrue(Verifier.verify(scope, "", null, List.of("src/main/java/shop/OrderService.java")).ok());
        assertFalse(Verifier.verify(scope, "", null,
                List.of("src/main/java/shop/OrderService.java", "README.md")).ok());
    }

    @Test
    void emptyAllowedListForbidsAnyChange() {
        TaskSpec.VerifySpec readOnly = new TaskSpec.VerifySpec(null, 0, List.of(), List.of(), null, null);

        assertTrue(Verifier.verify(readOnly, "", null, List.of()).ok());
        assertFalse(Verifier.verify(readOnly, "", null, List.of("README.md")).ok());
    }

    @Test
    void reportsTouchedUnchangedFiles() {
        TaskSpec.VerifySpec spec = new TaskSpec.VerifySpec(
                null, 0, List.of(), null, List.of("README.md"), null);

        assertFalse(Verifier.verify(spec, "", null, List.of("README.md")).ok());
        assertTrue(Verifier.verify(spec, "", null, List.of("src/App.java")).ok());
    }
}
