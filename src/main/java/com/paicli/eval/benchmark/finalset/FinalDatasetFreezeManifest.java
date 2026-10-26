package com.paicli.eval.benchmark.finalset;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Content manifest for one owner-only, immutable final-dataset snapshot.
 *
 * <p>The manifest deliberately contains only portable relative paths and content metadata. Private
 * source and destination paths must never be serialized into the freeze.</p>
 */
public record FinalDatasetFreezeManifest(
        @JsonProperty(value = "manifestVersion", required = true) int manifestVersion,
        @JsonProperty(value = "format", required = true) String format,
        @JsonProperty(value = "createdAtUtc", required = true) String createdAtUtc,
        @JsonProperty(value = "suitePath", required = true) String suitePath,
        @JsonProperty(value = "suiteSha256", required = true) String suiteSha256,
        @JsonProperty(value = "suiteVersion", required = true) String suiteVersion,
        @JsonProperty(value = "caseCount", required = true) int caseCount,
        @JsonProperty(value = "activeCaseCount", required = true) int activeCaseCount,
        @JsonProperty(value = "validatorRoot", required = true) String validatorRoot,
        @JsonProperty(value = "validatorTreeSha256", required = true) String validatorTreeSha256,
        @JsonProperty(value = "canaryPath", required = true) String canaryPath,
        @JsonProperty(value = "canarySha256", required = true) String canarySha256,
        @JsonProperty(value = "contentTreeSha256", required = true) String contentTreeSha256,
        @JsonProperty(value = "totalBytes", required = true) long totalBytes,
        @JsonProperty(value = "files", required = true) List<FileEntry> files
) {
    public static final int CURRENT_VERSION = 1;
    public static final String FORMAT = "paicli-final-dataset-freeze-v1";
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile("^[A-Za-z]:[\\\\/].*");

    public FinalDatasetFreezeManifest {
        files = files == null ? null : List.copyOf(files);
        if (manifestVersion != CURRENT_VERSION) {
            throw new IllegalArgumentException("unsupported final dataset manifest version: "
                    + manifestVersion);
        }
        if (!FORMAT.equals(format)) {
            throw new IllegalArgumentException("unsupported final dataset manifest format: " + format);
        }
        requireInstant(createdAtUtc);
        requireRelativePath(suitePath, "suitePath");
        requireSha256(suiteSha256, "suiteSha256");
        requireSafeLabel(suiteVersion, "suiteVersion");
        if (caseCount <= 0) {
            throw new IllegalArgumentException("caseCount must be positive");
        }
        if (activeCaseCount <= 0 || activeCaseCount > caseCount) {
            throw new IllegalArgumentException("activeCaseCount must be between 1 and caseCount");
        }
        requireRelativePath(validatorRoot, "validatorRoot");
        requireSha256(validatorTreeSha256, "validatorTreeSha256");
        requireRelativePath(canaryPath, "canaryPath");
        requireSha256(canarySha256, "canarySha256");
        requireSha256(contentTreeSha256, "contentTreeSha256");
        if (totalBytes < 0) {
            throw new IllegalArgumentException("totalBytes must not be negative");
        }
        validateEntries(files, suitePath, suiteSha256, canaryPath, canarySha256, totalBytes);
    }

    private static void validateEntries(List<FileEntry> entries,
                                        String suitePath,
                                        String suiteSha256,
                                        String canaryPath,
                                        String canarySha256,
                                        long declaredBytes) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("files must not be empty");
        }
        Set<String> paths = new HashSet<>();
        String previous = null;
        long bytes = 0;
        FileEntry suite = null;
        FileEntry canary = null;
        for (FileEntry entry : entries) {
            if (entry == null) {
                throw new IllegalArgumentException("files must not contain null entries");
            }
            if (!paths.add(entry.path())) {
                throw new IllegalArgumentException("duplicate final dataset manifest path: "
                        + entry.path());
            }
            if (previous != null && previous.compareTo(entry.path()) >= 0) {
                throw new IllegalArgumentException("files must be strictly sorted by portable path");
            }
            previous = entry.path();
            bytes = Math.addExact(bytes, entry.size());
            if (entry.path().equals(suitePath)) {
                suite = entry;
            }
            if (entry.path().equals(canaryPath)) {
                canary = entry;
            }
        }
        if (bytes != declaredBytes) {
            throw new IllegalArgumentException("totalBytes does not match file entries");
        }
        if (suite == null || !suite.sha256().equals(suiteSha256)) {
            throw new IllegalArgumentException("suitePath/suiteSha256 is not bound to a file entry");
        }
        if (canary == null || !canary.sha256().equals(canarySha256)) {
            throw new IllegalArgumentException("canaryPath/canarySha256 is not bound to a file entry");
        }
    }

    static void requireRelativePath(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        String normalized = value.replace('\\', '/');
        if (!value.equals(normalized)
                || value.startsWith("/")
                || value.startsWith("~/")
                || WINDOWS_ABSOLUTE.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must be a portable relative path: " + value);
        }
        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException(label + " contains an unsafe path segment: " + value);
            }
            for (int index = 0; index < segment.length(); index++) {
                char character = segment.charAt(index);
                if (character == '\0' || character == '\n' || character == '\r'
                        || Character.isISOControl(character)) {
                    throw new IllegalArgumentException(label + " contains control characters");
                }
            }
        }
    }

    static void requireSha256(String value, String label) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must be a lowercase SHA-256 hex digest");
        }
    }

    private static void requireInstant(String value) {
        requireText(value, "createdAtUtc");
        try {
            Instant.parse(value);
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException("createdAtUtc must be an ISO-8601 instant", error);
        }
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
    }

    private static void requireSafeLabel(String value, String label) {
        requireText(value, label);
        if (value.length() > 128 || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException(label + " must be a portable identifier");
        }
    }

    /** One regular source file after owner-only, read-only mode normalization. */
    public record FileEntry(
            @JsonProperty(value = "path", required = true) String path,
            @JsonProperty(value = "sha256", required = true) String sha256,
            @JsonProperty(value = "size", required = true) long size,
            @JsonProperty(value = "mode", required = true) String mode
    ) {
        public FileEntry {
            requireRelativePath(path, "file path");
            requireSha256(sha256, "file sha256");
            if (size < 0) {
                throw new IllegalArgumentException("file size must not be negative");
            }
            if (!"0400".equals(mode) && !"0500".equals(mode)) {
                throw new IllegalArgumentException("frozen file mode must be 0400 or 0500");
            }
        }
    }
}
