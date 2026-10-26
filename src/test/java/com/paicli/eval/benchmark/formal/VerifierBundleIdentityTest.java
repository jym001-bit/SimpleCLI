package com.paicli.eval.benchmark.formal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VerifierBundleIdentityTest {
    @Test
    void digestBindsPathModeSizeAndContentDigest() {
        VerifierBundleIdentity.Entry baseline = new VerifierBundleIdentity.Entry(
                "validators/final/case.sh", "0500", 17L, "a".repeat(64));
        String digest = VerifierBundleIdentity.digest(List.of(baseline));

        assertNotEquals(digest, VerifierBundleIdentity.digest(List.of(
                new VerifierBundleIdentity.Entry(
                        "validators/final/other.sh", "0500", 17L, "a".repeat(64)))));
        assertNotEquals(digest, VerifierBundleIdentity.digest(List.of(
                new VerifierBundleIdentity.Entry(
                        "validators/final/case.sh", "0400", 17L, "a".repeat(64)))));
        assertNotEquals(digest, VerifierBundleIdentity.digest(List.of(
                new VerifierBundleIdentity.Entry(
                        "validators/final/case.sh", "0500", 18L, "a".repeat(64)))));
        assertNotEquals(digest, VerifierBundleIdentity.digest(List.of(
                new VerifierBundleIdentity.Entry(
                        "validators/final/case.sh", "0500", 17L, "b".repeat(64)))));
    }

    @Test
    void rejectsUnsortedOrDuplicateEntries() {
        VerifierBundleIdentity.Entry first = new VerifierBundleIdentity.Entry(
                "validators/final/a.sh", "0500", 1L, "a".repeat(64));
        VerifierBundleIdentity.Entry second = new VerifierBundleIdentity.Entry(
                "validators/final/b.py", "0400", 2L, "b".repeat(64));

        assertThrows(IllegalArgumentException.class,
                () -> VerifierBundleIdentity.digest(List.of(second, first)));
        assertThrows(IllegalArgumentException.class,
                () -> VerifierBundleIdentity.digest(List.of(first, first)));
    }
}
