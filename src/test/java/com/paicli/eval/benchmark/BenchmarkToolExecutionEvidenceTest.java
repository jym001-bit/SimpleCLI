package com.paicli.eval.benchmark;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkToolExecutionEvidenceTest {
    private static final String ZERO_SHA256 = "0".repeat(64);

    @Test
    void fromKeepsOrderedIdentityAndBoundedResultEvidence() throws Exception {
        String result = "x".repeat(BenchmarkToolExecutionEvidence.MAX_RESULT_PREVIEW_CHARS) + "tail";

        BenchmarkToolExecutionEvidence first = BenchmarkToolExecutionEvidence.from(
                1, "call-1", "read_file", "{\"path\":\"README.md\"}",
                result, 17, false, true);
        BenchmarkToolExecutionEvidence second = BenchmarkToolExecutionEvidence.from(
                2, "call-2", "grep_code", "{\"pattern\":\"needle\"}",
                "not found", 3, false, false);

        assertEquals(1, first.ordinal());
        assertEquals("call-1", first.callId());
        assertEquals("read_file", first.toolName());
        assertEquals("{\"path\":\"README.md\"}", first.argumentsJson());
        assertEquals(BenchmarkToolExecutionEvidence.MAX_RESULT_PREVIEW_CHARS,
                first.resultPreview().length());
        assertEquals(result.length(), first.resultChars());
        assertEquals(sha256(result), first.resultSha256());
        assertEquals(17, first.elapsedMillis());
        assertTrue(first.successful());
        assertFalse(first.timedOut());

        assertEquals(2, second.ordinal());
        assertEquals("not found", second.resultPreview());
        assertEquals(sha256("not found"), second.resultSha256());
        assertFalse(second.successful());
    }

    @Test
    void fromNormalizesNullArgumentsAndResultWithoutInventingSuccess() throws Exception {
        BenchmarkToolExecutionEvidence evidence = BenchmarkToolExecutionEvidence.from(
                1, "call-1", "read_file", null, null, 0, false, false);

        assertEquals("", evidence.argumentsJson());
        assertEquals("", evidence.resultPreview());
        assertEquals(0, evidence.resultChars());
        assertEquals(sha256(""), evidence.resultSha256());
        assertFalse(evidence.successful());
    }

    @Test
    void rejectsOutOfRangeUnsafeOversizedAndInconsistentValues() {
        assertThrows(IllegalArgumentException.class, () -> evidence(0));
        assertThrows(IllegalArgumentException.class,
                () -> evidence(BenchmarkToolExecutionEvidence.MAX_EVENTS + 1));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkToolExecutionEvidence(
                1, "call id", "read_file", "{}", "ok", ZERO_SHA256,
                2, 1, false, true));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkToolExecutionEvidence(
                1, "call-1", "bad tool", "{}", "ok", ZERO_SHA256,
                2, 1, false, true));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkToolExecutionEvidence(
                1, "call-1", "read_file",
                "x".repeat(BenchmarkToolExecutionEvidence.MAX_ARGUMENT_CHARS + 1),
                "ok", ZERO_SHA256, 2, 1, false, true));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkToolExecutionEvidence(
                1, "call-1", "read_file", "{}",
                "x".repeat(BenchmarkToolExecutionEvidence.MAX_RESULT_PREVIEW_CHARS + 1),
                ZERO_SHA256, 2, 1, false, true));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkToolExecutionEvidence(
                1, "call-1", "read_file", "{}", "ok", "A".repeat(64),
                2, 1, false, true));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkToolExecutionEvidence(
                1, "call-1", "read_file", "{}", "ok", ZERO_SHA256,
                -1, 1, false, false));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkToolExecutionEvidence(
                1, "call-1", "read_file", "{}", "ok", ZERO_SHA256,
                2, -1, false, false));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkToolExecutionEvidence(
                1, "call-1", "read_file", "{}", "ok", ZERO_SHA256,
                2, 1, true, true));
    }

    @Test
    void allowsRedactionShortenedPreviewButRejectsPreviewLongerThanOriginalResult() {
        BenchmarkToolExecutionEvidence shortened = new BenchmarkToolExecutionEvidence(
                1, "call-1", "read_file", "{}", "x", ZERO_SHA256,
                2, 1, false, true);

        assertEquals("x", shortened.resultPreview());
        assertEquals(2, shortened.resultChars());
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkToolExecutionEvidence(
                1, "call-1", "read_file", "{}", "abc", ZERO_SHA256,
                2, 1, false, true));
    }

    @Test
    void exactSecretScrubCoversArgumentsAndPreviewWhilePreservingDigestMetadata() {
        String secret = "literal-provider-key-not-pattern-shaped";
        BenchmarkToolExecutionEvidence original = BenchmarkToolExecutionEvidence.from(
                1, "call-1", "read_file",
                "{\"token\":\"" + secret + "\"}",
                "tool returned " + secret, 7, false, true);

        BenchmarkToolExecutionEvidence scrubbed = original.scrubExactSecret(secret);

        assertFalse(scrubbed.argumentsJson().contains(secret));
        assertFalse(scrubbed.resultPreview().contains(secret));
        assertTrue(scrubbed.argumentsJson().contains(SecretRedactor.REDACTED));
        assertTrue(scrubbed.resultPreview().contains(SecretRedactor.REDACTED));
        assertEquals(original.resultSha256(), scrubbed.resultSha256());
        assertEquals(original.resultChars(), scrubbed.resultChars());
        assertEquals(original.elapsedMillis(), scrubbed.elapsedMillis());
        assertTrue(scrubbed.resultPreview().length() < scrubbed.resultChars());
        assertFalse(scrubbed.toString().contains(secret));
        assertFalse(scrubbed.toString().contains("tool returned"));
    }

    @Test
    void scrubAlsoAppliesConservativePatternRedactionWithoutExactSecret() {
        String bearer = "Bearer abcdefghijklmnop";
        BenchmarkToolExecutionEvidence original = BenchmarkToolExecutionEvidence.from(
                1, "call-1", "read_file",
                "{\"authorization\":\"" + bearer + "\"}",
                "response contained " + bearer, 7, false, true);

        BenchmarkToolExecutionEvidence scrubbed = original.scrubExactSecret(null);

        assertFalse(scrubbed.argumentsJson().contains("abcdefghijklmnop"));
        assertFalse(scrubbed.resultPreview().contains("abcdefghijklmnop"));
        assertTrue(scrubbed.argumentsJson().contains(SecretRedactor.REDACTED));
        assertTrue(scrubbed.resultPreview().contains(SecretRedactor.REDACTED));
        assertEquals(original.resultChars(), scrubbed.resultChars());
    }

    @Test
    void blankSecretLeavesEvidenceUnchanged() {
        BenchmarkToolExecutionEvidence original = BenchmarkToolExecutionEvidence.from(
                1, "call-1", "read_file", "{}", "ok", 1, false, true);

        assertEquals(original, original.scrubExactSecret(null));
        assertEquals(original, original.scrubExactSecret("  "));
    }

    private static BenchmarkToolExecutionEvidence evidence(int ordinal) {
        return new BenchmarkToolExecutionEvidence(
                ordinal, "call-1", "read_file", "{}", "ok", ZERO_SHA256,
                2, 1, false, true);
    }

    private static String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
