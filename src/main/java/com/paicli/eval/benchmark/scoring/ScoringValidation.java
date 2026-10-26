package com.paicli.eval.benchmark.scoring;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Small validation primitives for the public scoring protocol records. */
final class ScoringValidation {
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern EVIDENCE_REFERENCE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:+/#-]{0,255}");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private ScoringValidation() {
    }

    static String identifier(String value, String label) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must be a safe identifier");
        }
        return value;
    }

    static String sha256(String value, String label) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must be a lowercase SHA-256 digest");
        }
        return value;
    }

    static <T> List<T> nonEmptyCopy(List<T> values, String label) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        try {
            return List.copyOf(values);
        } catch (NullPointerException error) {
            throw new IllegalArgumentException(label + " must not contain null", error);
        }
    }

    static <T> List<T> copyAllowEmpty(List<T> values, String label) {
        if (values == null) {
            throw new IllegalArgumentException(label + " must not be null");
        }
        try {
            return List.copyOf(values);
        } catch (NullPointerException error) {
            throw new IllegalArgumentException(label + " must not contain null", error);
        }
    }

    static List<String> evidenceReferences(List<String> values, String label) {
        List<String> copy = nonEmptyCopy(values, label);
        Set<String> unique = new HashSet<>();
        for (String value : copy) {
            if (!EVIDENCE_REFERENCE.matcher(value).matches()
                    || value.startsWith("/") || value.contains("../") || value.contains("/..")) {
                throw new IllegalArgumentException(label + " contains an unsafe evidence reference");
            }
            if (!unique.add(value)) {
                throw new IllegalArgumentException(label + " contains duplicate evidence reference: " + value);
            }
        }
        return copy;
    }

    static <T> void requireUniqueIds(List<T> values,
                                     java.util.function.Function<T, String> id,
                                     String label) {
        Set<String> unique = new HashSet<>();
        for (T value : values) {
            if (!unique.add(id.apply(value))) {
                throw new IllegalArgumentException(label + " contains duplicate id: " + id.apply(value));
            }
        }
    }
}
