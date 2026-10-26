package com.paicli.eval.benchmark;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Materializes only verifier files explicitly referenced by argv into a private read-only bundle. */
final class BenchmarkVerifierBundle {
    static final int MAX_FILES = 10_000;
    static final long MAX_TOTAL_BYTES = 256L * 1024L * 1024L;
    private static final Set<PosixFilePermission> DIRECTORY_MODE = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_MODE = Set.of(PosixFilePermission.OWNER_READ);
    private static final Set<PosixFilePermission> EXECUTABLE_FILE_MODE = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> STAGING_FILE_MODE = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> STAGING_EXECUTABLE_MODE = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);

    private BenchmarkVerifierBundle() {
    }

    static Bundle create(CaseDefinition.VerifierInvocation invocation, Path emptyTarget)
            throws IOException {
        if (invocation == null || invocation.arguments().isEmpty()) {
            throw new IllegalArgumentException("verifier invocation must not be empty");
        }
        Path sourceRoot = requireDirectory(invocation.workingDirectory(), "verifier source root");
        Path targetRoot = requireDirectory(emptyTarget, "verifier bundle root");
        if (sourceRoot.equals(targetRoot)
                || sourceRoot.startsWith(targetRoot)
                || targetRoot.startsWith(sourceRoot)) {
            throw new IOException("verifier bundle must not overlap its source root");
        }
        try (var children = Files.list(targetRoot)) {
            if (children.findAny().isPresent()) {
                throw new IOException("verifier bundle root must be empty");
            }
        }

        List<Path> selected = selectedPaths(invocation, sourceRoot);
        if (selected.isEmpty()) {
            throw new IOException("verifier argv does not bind any verifier file");
        }
        CopyBudget budget = new CopyBudget();
        for (Path relative : selected) {
            copySelected(sourceRoot, targetRoot, relative, budget);
        }
        freeze(targetRoot);
        TreeState frozen = inspectFrozen(targetRoot);
        if (frozen.fileCount() != budget.fileCount || frozen.totalBytes() != budget.totalBytes) {
            throw new IOException("verifier bundle changed while it was frozen");
        }
        return new Bundle(
                new CaseDefinition.VerifierInvocation(targetRoot, invocation.arguments()),
                frozen.sha256(), frozen.fileCount(), frozen.totalBytes());
    }

    record Bundle(CaseDefinition.VerifierInvocation invocation,
                  String treeSha256,
                  int fileCount,
                  long totalBytes) {
        Bundle {
            if (invocation == null || treeSha256 == null
                    || !treeSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("verifier bundle metadata is invalid");
            }
            if (fileCount <= 0 || totalBytes < 0) {
                throw new IllegalArgumentException("verifier bundle counts are invalid");
            }
        }

        void verifyUnchanged() throws IOException {
            Path root = requireDirectory(invocation.workingDirectory(), "verifier bundle root");
            if (!root.equals(invocation.workingDirectory())) {
                throw new IOException("verifier bundle identity changed");
            }
            TreeState current = inspectFrozen(root);
            if (!treeSha256.equals(current.sha256())
                    || fileCount != current.fileCount()
                    || totalBytes != current.totalBytes()) {
                throw new IOException("verifier bundle content changed");
            }
        }
    }

    private static List<Path> selectedPaths(CaseDefinition.VerifierInvocation invocation,
                                            Path sourceRoot) throws IOException {
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        for (String argument : invocation.arguments()) {
            if (CaseDefinition.WORKSPACE_PLACEHOLDER.equals(argument)
                    || CaseDefinition.EVIDENCE_PLACEHOLDER.equals(argument)
                    || argument.startsWith("-")) {
                continue;
            }
            final Path relative;
            try {
                relative = Path.of(argument).normalize();
            } catch (RuntimeException error) {
                throw new IOException("verifier argv path is invalid", error);
            }
            if (relative.isAbsolute() || relative.toString().isEmpty()
                    || relative.equals(Path.of(".")) || relative.startsWith("..")) {
                continue;
            }
            Path candidate = sourceRoot.resolve(relative).normalize();
            if (!candidate.startsWith(sourceRoot)
                    || !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            requireNoSymlinkSegments(sourceRoot, relative);
            if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("verifier argv references a special file");
            }
            candidates.add(relative);
        }
        List<Path> ordered = candidates.stream()
                .sorted(Comparator.comparingInt(Path::getNameCount).thenComparing(Path::toString))
                .toList();
        List<Path> minimal = new ArrayList<>();
        for (Path candidate : ordered) {
            boolean covered = minimal.stream().anyMatch(parent -> candidate.startsWith(parent));
            if (!covered) {
                minimal.add(candidate);
            }
        }
        return List.copyOf(minimal);
    }

    private static void copySelected(Path sourceRoot,
                                     Path targetRoot,
                                     Path relative,
                                     CopyBudget budget) throws IOException {
        Path source = sourceRoot.resolve(relative).normalize();
        if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            createParents(targetRoot, relative.getParent());
            copyFile(source, targetRoot, targetRoot.resolve(relative), budget);
            return;
        }
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                rejectNode(directory, attributes, true, budget.identities);
                Path sourceRelative = sourceRoot.relativize(directory);
                createParents(targetRoot, sourceRelative);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                copyFile(
                        file,
                        targetRoot,
                        targetRoot.resolve(sourceRoot.relativize(file)),
                        budget);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
                throw new IOException("verifier bundle source could not be read");
            }
        });
    }

    private static void copyFile(Path source,
                                 Path targetRoot,
                                 Path target,
                                 CopyBudget budget) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        rejectNode(source, attributes, false, budget.identities);
        budget.fileCount = Math.addExact(budget.fileCount, 1);
        budget.totalBytes = Math.addExact(budget.totalBytes, attributes.size());
        if (budget.fileCount > MAX_FILES || budget.totalBytes > MAX_TOTAL_BYTES) {
            throw new IOException("verifier bundle exceeds its frozen limits");
        }
        createParents(targetRoot, targetRoot.relativize(target).getParent());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("verifier bundle contains overlapping argv paths");
        }
        try (InputStream input = Files.newInputStream(
                source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            Files.copy(input, target);
        }
        boolean executable = Files.getPosixFilePermissions(source, LinkOption.NOFOLLOW_LINKS).stream()
                .anyMatch(permission -> permission == PosixFilePermission.OWNER_EXECUTE
                        || permission == PosixFilePermission.GROUP_EXECUTE
                        || permission == PosixFilePermission.OTHERS_EXECUTE);
        setMode(target, executable ? STAGING_EXECUTABLE_MODE : STAGING_FILE_MODE);
    }

    private static void createParents(Path root, Path relative) throws IOException {
        if (relative == null) {
            return;
        }
        Path current = root;
        for (Path segment : relative) {
            current = current.resolve(segment).normalize();
            if (!current.startsWith(root)) {
                throw new IOException("verifier bundle path escapes target");
            }
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(current);
            } else if (Files.isSymbolicLink(current)
                    || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("verifier bundle parent is unsafe");
            }
        }
    }

    private static void freeze(Path root) throws IOException {
        List<Path> entries;
        try (var stream = Files.walk(root)) {
            entries = stream.sorted(
                    Comparator.comparingInt(Path::getNameCount).reversed()
                            .thenComparing(Comparator.reverseOrder()))
                    .toList();
        }
        for (Path entry : entries) {
            if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                setMode(entry, DIRECTORY_MODE);
            } else {
                boolean executable = Files.getPosixFilePermissions(entry).contains(
                        PosixFilePermission.OWNER_EXECUTE);
                setMode(entry, executable ? EXECUTABLE_FILE_MODE : FILE_MODE);
            }
        }
    }

    private static TreeState inspectFrozen(Path root) throws IOException {
        List<String> entries = new ArrayList<>();
        Set<Object> identities = new HashSet<>();
        int[] fileCount = {0};
        long[] totalBytes = {0L};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (Files.isSymbolicLink(directory) || !attributes.isDirectory()
                        || attributes.fileKey() == null || !identities.add(attributes.fileKey())) {
                    throw new IOException("frozen verifier bundle contains an unsafe directory");
                }
                if (!Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS)
                        .equals(DIRECTORY_MODE)) {
                    throw new IOException("frozen verifier bundle directory mode changed");
                }
                Path relative = root.relativize(directory);
                entries.add("D\0" + portable(relative) + "\0" + "0500");
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                if (attributes.isSymbolicLink() || !attributes.isRegularFile()
                        || attributes.fileKey() == null || !identities.add(attributes.fileKey())) {
                    throw new IOException("frozen verifier bundle contains an unsafe file");
                }
                Object links = Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
                if (!(links instanceof Number number) || number.longValue() != 1L) {
                    throw new IOException("frozen verifier bundle contains a hard-linked file");
                }
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
                        file, LinkOption.NOFOLLOW_LINKS);
                String mode;
                if (permissions.equals(FILE_MODE)) {
                    mode = "0400";
                } else if (permissions.equals(EXECUTABLE_FILE_MODE)) {
                    mode = "0500";
                } else {
                    throw new IOException("frozen verifier bundle file mode changed");
                }
                fileCount[0] = Math.addExact(fileCount[0], 1);
                totalBytes[0] = Math.addExact(totalBytes[0], attributes.size());
                entries.add("F\0" + portable(root.relativize(file)) + "\0" + mode + "\0"
                        + attributes.size() + "\0" + sha256(file));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
                throw new IOException("frozen verifier bundle cannot be inspected", error);
            }
        });
        entries.sort(String::compareTo);
        MessageDigest digest = newDigest();
        for (String entry : entries) {
            digest.update(entry.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
        return new TreeState(
                HexFormat.of().formatHex(digest.digest()), fileCount[0], totalBytes[0]);
    }

    private static void rejectNode(Path path,
                                   BasicFileAttributes attributes,
                                   boolean directory,
                                   Set<Object> identities) throws IOException {
        if (attributes == null || attributes.isSymbolicLink() || Files.isSymbolicLink(path)
                || (directory ? !attributes.isDirectory() : !attributes.isRegularFile())) {
            throw new IOException("verifier bundle source contains a symlink or special file");
        }
        Object identity = attributes.fileKey();
        if (identity == null || !identities.add(identity)) {
            throw new IOException("verifier bundle source identity is unavailable or reused");
        }
        if (!directory) {
            final Object links;
            try {
                links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            } catch (UnsupportedOperationException error) {
                throw new IOException("verifier bundle source lacks hard-link metadata", error);
            }
            if (!(links instanceof Number number) || number.longValue() != 1L) {
                throw new IOException("verifier bundle source contains a hard-linked file");
            }
        }
    }

    private static void requireNoSymlinkSegments(Path root, Path relative) throws IOException {
        Path current = root;
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("verifier argv path contains a symbolic link");
            }
        }
    }

    private static Path requireDirectory(Path raw, String label) throws IOException {
        if (raw == null || !raw.isAbsolute() || Files.isSymbolicLink(raw)
                || !Files.isDirectory(raw, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " is not a safe directory");
        }
        return raw.toRealPath();
    }

    private static void setMode(Path path, Set<PosixFilePermission> mode) throws IOException {
        try {
            Files.setPosixFilePermissions(path, mode);
        } catch (UnsupportedOperationException error) {
            throw new IOException("verifier bundle requires POSIX permissions", error);
        }
    }

    private static String portable(Path relative) {
        StringBuilder result = new StringBuilder();
        for (Path segment : relative) {
            if (!result.isEmpty()) {
                result.append('/');
            }
            result.append(segment);
        }
        return result.toString();
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream input = Files.newInputStream(
                file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static final class CopyBudget {
        private final Set<Object> identities = new HashSet<>();
        private int fileCount;
        private long totalBytes;
    }

    private record TreeState(String sha256, int fileCount, long totalBytes) {
    }
}
