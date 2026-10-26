package com.paicli.eval.benchmark.formal;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Shared fail-closed JSON and validation primitives for formal benchmark contracts. */
final class FormalContractSupport {
    static final long MAX_CONTRACT_BYTES = 4L * 1024L * 1024L;
    static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    static final Pattern IMAGE_ID = Pattern.compile("sha256:[0-9a-f]{64}");
    static final Pattern COMMIT_ID = Pattern.compile("(?:[0-9a-f]{40}|[0-9a-f]{64})");
    private static final Pattern SAFE_IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile("^[A-Za-z]:[\\\\/].*");

    static final ObjectMapper MAPPER = new ObjectMapper(
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private FormalContractSupport() {
    }

    static <T> T load(Path file, Class<T> type) throws IOException {
        Path safeFile = requireBoundedRegularFile(file);
        try (InputStream input = Files.newInputStream(safeFile, StandardOpenOption.READ)) {
            return MAPPER.readValue(input, type);
        } catch (IOException | IllegalArgumentException error) {
            throw new IOException("invalid formal contract: " + safeFile, error);
        }
    }

    static Path writeNew(Path file, Object value) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("contract path must not be null");
        }
        Path target = file.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null || Files.isSymbolicLink(parent)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("contract parent must be an existing non-symlink directory: " + parent);
        }
        byte[] json = MAPPER.writeValueAsBytes(value);
        if (json.length == 0 || json.length > MAX_CONTRACT_BYTES) {
            throw new IOException("formal contract size is outside the accepted bound: " + json.length);
        }
        // Serialize and parse before publication so invalid record evolution fails closed.
        MAPPER.readValue(json, value.getClass());
        Files.write(target, json, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return target;
    }

    static String sha256(Path file) throws IOException {
        if (file == null || Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("SHA-256 source must be a non-symlink regular file: " + file);
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        try (InputStream input = Files.newInputStream(file, StandardOpenOption.READ)) {
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static void requireVersionAndFormat(int version,
                                        int expectedVersion,
                                        String format,
                                        String expectedFormat) {
        if (version != expectedVersion) {
            throw new IllegalArgumentException("unsupported formal contract version: " + version);
        }
        if (!expectedFormat.equals(format)) {
            throw new IllegalArgumentException("unsupported formal contract format: " + format);
        }
    }

    static String requireSafeIdentifier(String value, String label) {
        if (value == null || !SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must match " + SAFE_IDENTIFIER.pattern());
        }
        return value;
    }

    static String requireSha256(String value, String label) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must be a lowercase SHA-256 digest");
        }
        return value;
    }

    static String requireImageId(String value, String label) {
        if (value == null || !IMAGE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must be sha256:<64 lowercase hex>");
        }
        return value;
    }

    static String requireCommitId(String value, String label) {
        if (value == null || !COMMIT_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must be a 40- or 64-character lowercase commit id");
        }
        return value;
    }

    static String requireRelativePath(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        if (value.indexOf('\\') >= 0 || value.startsWith("/") || value.startsWith("~/")
                || WINDOWS_ABSOLUTE.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must be a portable relative path: " + value);
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException(label + " contains an unsafe path segment: " + value);
            }
            requireNoControlCharacters(segment, label);
        }
        return value;
    }

    static List<String> requireIdentifierList(List<String> values, String label) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        List<String> copy = List.copyOf(values);
        Set<String> unique = new HashSet<>();
        for (String value : copy) {
            requireSafeIdentifier(value, label + " item");
            if (!unique.add(value)) {
                throw new IllegalArgumentException(label + " contains duplicate item: " + value);
            }
        }
        return copy;
    }

    static void requirePositive(int value, String label) {
        if (value <= 0) {
            throw new IllegalArgumentException(label + " must be positive");
        }
    }

    static Path requireCanonicalExecutable(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("dockerExecutablePath must not be blank");
        }
        final Path path;
        try {
            path = Path.of(value);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("dockerExecutablePath is invalid", error);
        }
        if (!path.isAbsolute() || !path.normalize().equals(path)) {
            throw new IllegalArgumentException(
                    "dockerExecutablePath must be an absolute normalized canonical path");
        }
        try {
            if (Files.isSymbolicLink(path)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isExecutable(path)
                    || !path.toRealPath().equals(path)) {
                throw new IllegalArgumentException(
                        "dockerExecutablePath must identify a canonical non-symlink executable");
            }
        } catch (IOException error) {
            throw new IllegalArgumentException(
                    "dockerExecutablePath cannot be resolved canonically", error);
        }
        return path;
    }

    private static Path requireBoundedRegularFile(Path file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("contract path must not be null");
        }
        Path normalized = file.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("formal contract must be a non-symlink regular file: " + normalized);
        }
        long size = Files.size(normalized);
        if (size <= 0 || size > MAX_CONTRACT_BYTES) {
            throw new IOException("formal contract size is outside the accepted bound: " + size);
        }
        return normalized;
    }

    private static void requireNoControlCharacters(String value, String label) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException(label + " must not contain control characters");
            }
        }
    }
}
