package com.paicli.eval.benchmark;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Bounded verifier evidence for one tool execution observed by the trusted benchmark runner.
 *
 * <p>The record intentionally excludes reasoning, system prompts and the full tool result. The
 * exact arguments and a bounded result preview remain private verifier inputs; public reports must
 * only publish aggregate counts or allowlisted fields.</p>
 */
public record BenchmarkToolExecutionEvidence(
        int ordinal,
        String callId,
        String toolName,
        String argumentsJson,
        String resultPreview,
        String resultSha256,
        long resultChars,
        long elapsedMillis,
        boolean timedOut,
        boolean successful) {

    public static final int MAX_EVENTS = 4_096;
    public static final int MAX_ARGUMENT_CHARS = 1_048_576;
    public static final int MAX_RESULT_PREVIEW_CHARS = 16_384;
    private static final Pattern SAFE_IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public BenchmarkToolExecutionEvidence {
        if (ordinal <= 0 || ordinal > MAX_EVENTS) {
            throw new IllegalArgumentException("tool execution ordinal is out of range");
        }
        requireIdentifier(callId, "callId");
        requireIdentifier(toolName, "toolName");
        argumentsJson = requireBounded(argumentsJson, MAX_ARGUMENT_CHARS, "argumentsJson");
        resultPreview = requireBounded(resultPreview, MAX_RESULT_PREVIEW_CHARS, "resultPreview");
        if (resultSha256 == null || !SHA256.matcher(resultSha256).matches()) {
            throw new IllegalArgumentException("resultSha256 must be a lowercase SHA-256 digest");
        }
        if (resultChars < 0 || elapsedMillis < 0) {
            throw new IllegalArgumentException("tool result sizes and elapsed time must be non-negative");
        }
        if (resultPreview.length() > Math.min(resultChars, MAX_RESULT_PREVIEW_CHARS)) {
            throw new IllegalArgumentException(
                    "result preview cannot exceed the original result length or preview limit");
        }
        if (timedOut && successful) {
            throw new IllegalArgumentException("a timed-out tool execution cannot be successful");
        }
    }

    public static BenchmarkToolExecutionEvidence from(
            int ordinal,
            String callId,
            String toolName,
            String argumentsJson,
            String result,
            long elapsedMillis,
            boolean timedOut,
            boolean successful) {
        String value = result == null ? "" : result;
        String preview = value.length() <= MAX_RESULT_PREVIEW_CHARS
                ? value
                : value.substring(0, MAX_RESULT_PREVIEW_CHARS);
        return new BenchmarkToolExecutionEvidence(
                ordinal,
                callId,
                toolName,
                argumentsJson == null ? "" : argumentsJson,
                preview,
                sha256(value),
                value.length(),
                elapsedMillis,
                timedOut,
                successful);
    }

    BenchmarkToolExecutionEvidence scrubExactSecret(String exactSecret) {
        String safeArguments = argumentsJson;
        String safePreview = resultPreview;
        if (exactSecret != null && !exactSecret.isBlank()) {
            safeArguments = safeArguments.replace(exactSecret, SecretRedactor.REDACTED);
            safePreview = safePreview.replace(exactSecret, SecretRedactor.REDACTED);
        }
        safeArguments = SecretRedactor.redact(safeArguments);
        safePreview = SecretRedactor.redact(safePreview);
        return new BenchmarkToolExecutionEvidence(
                ordinal, callId, toolName, safeArguments, safePreview, resultSha256,
                resultChars, elapsedMillis, timedOut, successful);
    }

    @Override
    public String toString() {
        return "BenchmarkToolExecutionEvidence[ordinal=" + ordinal
                + ", callId=" + callId + ", toolName=" + toolName
                + ", argumentsJson=<redacted chars=" + argumentsJson.length()
                + ">, resultPreview=<redacted chars=" + resultPreview.length()
                + ">, resultSha256=" + resultSha256 + ", resultChars=" + resultChars
                + ", elapsedMillis=" + elapsedMillis + ", timedOut=" + timedOut
                + ", successful=" + successful + "]";
    }

    private static void requireIdentifier(String value, String label) {
        if (value == null || !SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " is not a safe identifier");
        }
    }

    private static String requireBounded(String value, int maxChars, String label) {
        String normalized = value == null ? "" : value;
        if (normalized.length() > maxChars) {
            throw new IllegalArgumentException(label + " exceeds its character limit");
        }
        return normalized;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
