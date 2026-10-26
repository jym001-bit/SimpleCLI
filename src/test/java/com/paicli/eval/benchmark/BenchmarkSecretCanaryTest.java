package com.paicli.eval.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkSecretCanaryTest {
    @Test
    void findsExactCredentialAcrossStreamBufferBoundary(@TempDir Path tempDir) throws Exception {
        String secret = "provider-key-canary-123456789";
        String prefix = "x".repeat(16_384 - 7);
        Files.writeString(tempDir.resolve("ledger.jsonl"), prefix + secret + " suffix");

        assertTrue(BenchmarkSecretCanary.containsInTree(tempDir, secret));
        assertFalse(BenchmarkSecretCanary.containsInTree(tempDir, secret + "-different"));
    }
}
