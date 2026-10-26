package com.paicli.eval.benchmark;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** Exact-match canary used to prove the provider credential never reached an artifact. */
final class BenchmarkSecretCanary {
    private BenchmarkSecretCanary() {
    }

    static boolean contains(String secret, String... values) {
        if (secret == null || secret.isEmpty() || values == null) {
            return false;
        }
        for (String value : values) {
            if (value != null && value.contains(secret)) {
                return true;
            }
        }
        return false;
    }

    static boolean containsInTree(Path root, String secret) throws IOException {
        if (root == null || secret == null || secret.isEmpty() || !Files.exists(root)) {
            return false;
        }
        byte[] needle = secret.getBytes(StandardCharsets.UTF_8);
        boolean[] found = {false};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isRegularFile() && contains(file, needle)) {
                    found[0] = true;
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return found[0];
    }

    private static boolean contains(Path file, byte[] needle) throws IOException {
        if (needle.length == 0) {
            return false;
        }
        int[] prefix = prefixTable(needle);
        int matched = 0;
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                for (int index = 0; index < read; index++) {
                    byte value = buffer[index];
                    while (matched > 0 && needle[matched] != value) {
                        matched = prefix[matched - 1];
                    }
                    if (needle[matched] == value) {
                        matched++;
                        if (matched == needle.length) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static int[] prefixTable(byte[] needle) {
        int[] prefix = new int[needle.length];
        int matched = 0;
        for (int index = 1; index < needle.length; index++) {
            while (matched > 0 && needle[index] != needle[matched]) {
                matched = prefix[matched - 1];
            }
            if (needle[index] == needle[matched]) {
                prefix[index] = ++matched;
            }
        }
        return prefix;
    }
}
