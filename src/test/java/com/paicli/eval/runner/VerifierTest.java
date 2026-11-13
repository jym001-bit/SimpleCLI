package com.paicli.eval.runner;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifierTest {

    private static TaskSpec.VerifySpec spec(Integer exitCode, List<String> keywords) {
        return new TaskSpec.VerifySpec(null, exitCode, keywords, null, null, null, null, null);
    }

    private static TaskSpec.VerifySpec answerSpec(TaskSpec.AnswerSpec answer) {
        return new TaskSpec.VerifySpec(null, null, null, null, null, null, null, answer);
    }

    private static TaskSpec.AnswerSpec exact(String field, List<String> expected) {
        return new TaskSpec.AnswerSpec(null, null, null, Map.of(field, expected), null);
    }

    private static TaskSpec.AnswerSpec sequence(String field, List<String> expected) {
        return new TaskSpec.AnswerSpec(null, null, null, null, Map.of(field, expected));
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
                List.of("src/main/java/shop/OrderService.java"), null, null, null, null);

        assertTrue(Verifier.verify(scope, "", null, List.of("src/main/java/shop/OrderService.java")).ok());
        assertFalse(Verifier.verify(scope, "", null,
                List.of("src/main/java/shop/OrderService.java", "README.md")).ok());
    }

    @Test
    void emptyAllowedListForbidsAnyChange() {
        TaskSpec.VerifySpec readOnly = new TaskSpec.VerifySpec(null, 0, List.of(), List.of(), null, null, null, null);

        assertTrue(Verifier.verify(readOnly, "", null, List.of()).ok());
        assertFalse(Verifier.verify(readOnly, "", null, List.of("README.md")).ok());
    }

    @Test
    void reportsTouchedUnchangedFiles() {
        TaskSpec.VerifySpec spec = new TaskSpec.VerifySpec(
                null, 0, List.of(), null, List.of("README.md"), null, null, null);

        assertFalse(Verifier.verify(spec, "", null, List.of("README.md")).ok());
        assertTrue(Verifier.verify(spec, "", null, List.of("src/App.java")).ok());
    }

    // ---- exact：集合相等，忽略顺序 ----

    @Test
    void exactPassesRegardlessOfOrderAndWhitespace() {
        TaskSpec.VerifySpec spec = answerSpec(exact("names", List.of("A", "B")));

        assertTrue(Verifier.verify(spec, "{\"names\":[\" B \",\"A\"]}", null).ok());
    }

    @Test
    void exactRejectsMissingItem() {
        TaskSpec.VerifySpec spec = answerSpec(exact("names", List.of("A", "B")));
        Verifier.Result result = Verifier.verify(spec, "{\"names\":[\"A\"]}", null);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("集合不一致"));
    }

    @Test
    void exactRejectsExtraItem() {
        TaskSpec.VerifySpec spec = answerSpec(exact("names", List.of("A", "B")));
        Verifier.Result result = Verifier.verify(spec, "{\"names\":[\"A\",\"B\",\"C\"]}", null);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("集合不一致"));
    }

    @Test
    void exactRejectsDuplicateItem() {
        TaskSpec.VerifySpec spec = answerSpec(exact("names", List.of("A")));
        Verifier.Result result = Verifier.verify(spec, "{\"names\":[\"A\",\" A \"]}", null);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("重复元素"));
    }

    @Test
    void exactRejectsNonArrayValue() {
        TaskSpec.VerifySpec spec = answerSpec(exact("names", List.of("A")));
        Verifier.Result result = Verifier.verify(spec, "{\"names\":\"A\"}", null);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("必须是数组"));
    }

    // ---- sequence：顺序相等 ----

    @Test
    void sequencePassesInOrderAndTrims() {
        TaskSpec.VerifySpec spec = answerSpec(sequence("chain", List.of("A", "B", "C")));

        assertTrue(Verifier.verify(spec, "{\"chain\":[\" A \",\"B\",\"C\"]}", null).ok());
    }

    @Test
    void sequenceRejectsWrongOrder() {
        TaskSpec.VerifySpec spec = answerSpec(sequence("chain", List.of("A", "B")));
        Verifier.Result result = Verifier.verify(spec, "{\"chain\":[\"B\",\"A\"]}", null);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("顺序不一致"));
    }

    @Test
    void sequenceRejectsMissingItem() {
        TaskSpec.VerifySpec spec = answerSpec(sequence("chain", List.of("A", "B")));
        Verifier.Result result = Verifier.verify(spec, "{\"chain\":[\"A\"]}", null);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("顺序不一致"));
    }

    @Test
    void sequenceRejectsExtraItem() {
        TaskSpec.VerifySpec spec = answerSpec(sequence("chain", List.of("A", "B")));
        Verifier.Result result = Verifier.verify(spec, "{\"chain\":[\"A\",\"B\",\"C\"]}", null);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("顺序不一致"));
    }

    @Test
    void sequenceRejectsDuplicateItem() {
        TaskSpec.VerifySpec spec = answerSpec(sequence("chain", List.of("A", "B")));
        Verifier.Result result = Verifier.verify(spec, "{\"chain\":[\"A\",\"A\"]}", null);

        assertFalse(result.ok());
        assertTrue(result.detail().contains("重复元素"));
    }
}
