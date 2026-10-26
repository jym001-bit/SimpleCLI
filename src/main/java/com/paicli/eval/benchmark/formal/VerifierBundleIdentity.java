package com.paicli.eval.benchmark.formal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Deterministic identity for one preregistered verifier dependency bundle. */
public final class VerifierBundleIdentity {
    private static final String DOMAIN = "paicli-formal-verifier-bundle-v1";

    private VerifierBundleIdentity() {
    }

    public static String digest(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("verifier bundle entries must not be empty");
        }
        MessageDigest digest = newSha256();
        update(digest, DOMAIN);
        digest.update((byte) '\n');
        String previous = null;
        for (Entry entry : List.copyOf(entries)) {
            if (entry == null) {
                throw new IllegalArgumentException("verifier bundle entries must not contain null");
            }
            if (previous != null && previous.compareTo(entry.path()) >= 0) {
                throw new IllegalArgumentException(
                        "verifier bundle entries must be unique and strictly sorted");
            }
            update(digest, entry.path());
            digest.update((byte) 0);
            update(digest, entry.mode());
            digest.update((byte) 0);
            update(digest, Long.toString(entry.size()));
            digest.update((byte) 0);
            update(digest, entry.sha256());
            digest.update((byte) '\n');
            previous = entry.path();
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public record Entry(String path, String mode, long size, String sha256) {
        public Entry {
            FormalContractSupport.requireRelativePath(path, "verifier bundle path");
            if (!"0400".equals(mode) && !"0500".equals(mode)) {
                throw new IllegalArgumentException(
                        "verifier bundle mode must be 0400 or 0500");
            }
            if (size < 0) {
                throw new IllegalArgumentException(
                        "verifier bundle file size must not be negative");
            }
            FormalContractSupport.requireSha256(sha256, "verifier bundle file sha256");
        }
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }
}
