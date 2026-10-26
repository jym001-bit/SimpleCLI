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
import java.util.List;
import java.util.Set;

/**
 * Copies a stopped Candidate workspace into a bounded, regular-file-only,
 * owner-read-only tree for deterministic verification.
 */
final class BenchmarkVerifierWorkspaceSnapshot {
    static final int MAX_FILES = 50_000;
    static final long MAX_TOTAL_BYTES = 512L * 1024L * 1024L;
    private static final Set<PosixFilePermission> DIRECTORY_MODE = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_MODE = Set.of(
            PosixFilePermission.OWNER_READ);
    private static final Set<PosixFilePermission> EXECUTABLE_FILE_MODE = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> WRITABLE_FILE_MODE = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> WRITABLE_EXECUTABLE_FILE_MODE = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);

    private BenchmarkVerifierWorkspaceSnapshot() {
    }

    static Snapshot create(Path source, Path emptyTarget) throws IOException {
        return create(source, emptyTarget, null);
    }

    /** F1-only projection: omit the original host link, never copy or follow it. */
    static Snapshot createBoundaryProjection(Path source, Path emptyTarget, F1BoundarySession boundary) throws IOException {
        java.util.Objects.requireNonNull(boundary, "bound F1 boundary").requireSnapshotSource(source);
        Snapshot result = create(source, emptyTarget, boundary);
        boundary.verifyTerminalUnchanged();
        return result;
    }

    private static Snapshot create(Path source, Path emptyTarget, F1BoundarySession boundary) throws IOException {
        Path realSource = requireDirectory(source, "candidate workspace");
        Path realTarget = requireDirectory(emptyTarget, "verifier workspace snapshot");
        if (realSource.equals(realTarget)
                || realSource.startsWith(realTarget)
                || realTarget.startsWith(realSource)) {
            throw new IOException("verifier workspace snapshot must not overlap the candidate workspace");
        }
        try (var children = Files.list(realTarget)) {
            if (children.findAny().isPresent()) {
                throw new IOException("verifier workspace snapshot target must be empty");
            }
        }

        CopyState state = new CopyState(realSource, realTarget, boundary);
        Files.walkFileTree(realSource, state);
        makeReadOnly(realTarget);
        TreeState frozen = inspect(realTarget, true);
        if (frozen.fileCount() != state.fileCount || frozen.totalBytes() != state.totalBytes) {
            throw new IOException("verifier workspace snapshot changed while it was frozen");
        }
        return new Snapshot(realTarget, frozen.sha256(), frozen.fileCount(), frozen.totalBytes());
    }

    record Snapshot(Path directory, String treeSha256, int fileCount, long totalBytes) {
        Snapshot {
            directory = directory.toAbsolutePath().normalize();
            if (treeSha256 == null || !treeSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("snapshot tree digest is invalid");
            }
            if (fileCount < 0 || totalBytes < 0) {
                throw new IllegalArgumentException("snapshot counts must not be negative");
            }
        }

        void verifyUnchanged() throws IOException {
            Path real = requireDirectory(directory, "verifier workspace snapshot");
            if (!real.equals(directory)) {
                throw new UnsafeWorkspaceException("verifier workspace snapshot identity changed");
            }
            TreeState current = inspect(real, true);
            if (!treeSha256.equals(current.sha256())
                    || fileCount != current.fileCount()
                    || totalBytes != current.totalBytes()) {
                throw new UnsafeWorkspaceException("verifier workspace snapshot content changed");
            }
        }
    }

    static final class UnsafeWorkspaceException extends IOException {
        UnsafeWorkspaceException(String message) {
            super(message);
        }
    }

    private static final class CopyState extends SimpleFileVisitor<Path> {
        private final Path source;
        private final Path target;
        private final F1BoundarySession boundary;
        private final Set<Object> identities = new HashSet<>();
        private int fileCount;
        private long totalBytes;

        private CopyState(Path source, Path target, F1BoundarySession boundary) {
            this.source = source;
            this.target = target;
            this.boundary = boundary;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                throws IOException {
            rejectUnsafeNode(directory, attributes, true, identities);
            Path relative = source.relativize(directory);
            requirePortableRelative(relative);
            if (!relative.toString().isEmpty()) {
                Files.createDirectory(target.resolve(relative));
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            if (boundary != null && attributes.isSymbolicLink() && boundary.excludesFromSnapshot(source, file))
                return FileVisitResult.CONTINUE;
            rejectUnsafeNode(file, attributes, false, identities);
            Path relative = source.relativize(file);
            requirePortableRelative(relative);
            fileCount = Math.addExact(fileCount, 1);
            totalBytes = Math.addExact(totalBytes, attributes.size());
            if (fileCount > MAX_FILES || totalBytes > MAX_TOTAL_BYTES) {
                throw new UnsafeWorkspaceException("candidate workspace exceeds verifier snapshot limits");
            }
            Path destination = target.resolve(relative).normalize();
            if (!destination.startsWith(target)) {
                throw new UnsafeWorkspaceException("candidate workspace entry escapes snapshot root");
            }
            try (InputStream input = Files.newInputStream(
                    file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                Files.copy(input, destination);
            }
            boolean executable = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS).stream()
                    .anyMatch(permission -> permission == PosixFilePermission.OWNER_EXECUTE
                            || permission == PosixFilePermission.GROUP_EXECUTE
                            || permission == PosixFilePermission.OTHERS_EXECUTE);
            setMode(destination,
                    executable ? WRITABLE_EXECUTABLE_FILE_MODE : WRITABLE_FILE_MODE);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
            throw new UnsafeWorkspaceException("candidate workspace could not be snapshotted");
        }
    }

    private static void makeReadOnly(Path root) throws IOException {
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
                boolean executable = Files.getPosixFilePermissions(entry).stream()
                        .anyMatch(permission -> permission == PosixFilePermission.OWNER_EXECUTE
                                || permission == PosixFilePermission.GROUP_EXECUTE
                                || permission == PosixFilePermission.OTHERS_EXECUTE);
                setMode(entry, executable ? EXECUTABLE_FILE_MODE : FILE_MODE);
            }
        }
    }

    private static TreeState inspect(Path root, boolean requireReadOnly) throws IOException {
        List<Entry> entries = new ArrayList<>();
        Set<Object> identities = new HashSet<>();
        long[] bytes = {0L};
        int[] files = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                rejectUnsafeNode(directory, attributes, true, identities);
                Path relative = root.relativize(directory);
                requirePortableRelative(relative);
                if (requireReadOnly) {
                    requireMode(directory, DIRECTORY_MODE);
                }
                if (!relative.toString().isEmpty()) {
                    entries.add(new Entry("D", portable(relative), "0500", 0L, ""));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                rejectUnsafeNode(file, attributes, false, identities);
                Path relative = root.relativize(file);
                requirePortableRelative(relative);
                files[0] = Math.addExact(files[0], 1);
                bytes[0] = Math.addExact(bytes[0], attributes.size());
                if (files[0] > MAX_FILES || bytes[0] > MAX_TOTAL_BYTES) {
                    throw new UnsafeWorkspaceException("verifier workspace snapshot exceeds limits");
                }
                Set<PosixFilePermission> actual = Files.getPosixFilePermissions(
                        file, LinkOption.NOFOLLOW_LINKS);
                if (requireReadOnly && !actual.equals(FILE_MODE)
                        && !actual.equals(EXECUTABLE_FILE_MODE)) {
                    throw new UnsafeWorkspaceException("verifier workspace snapshot has unsafe permissions");
                }
                String mode = actual.equals(EXECUTABLE_FILE_MODE) ? "0500" : "0400";
                entries.add(new Entry(
                        "F", portable(relative), mode, attributes.size(), sha256(file)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
                throw new UnsafeWorkspaceException("verifier workspace snapshot cannot be inspected");
            }
        });
        entries.sort(Comparator.comparing(Entry::path).thenComparing(Entry::type));
        MessageDigest digest = sha256Digest();
        for (Entry entry : entries) {
            update(digest, entry.type());
            update(digest, entry.path());
            update(digest, entry.mode());
            update(digest, Long.toString(entry.size()));
            update(digest, entry.sha256());
        }
        return new TreeState(HexFormat.of().formatHex(digest.digest()), files[0], bytes[0]);
    }

    private static void rejectUnsafeNode(Path path,
                                         BasicFileAttributes attributes,
                                         boolean directory,
                                         Set<Object> identities) throws IOException {
        if (attributes == null || attributes.isSymbolicLink() || Files.isSymbolicLink(path)
                || (directory ? !attributes.isDirectory() : !attributes.isRegularFile())) {
            throw new UnsafeWorkspaceException("candidate workspace contains a symlink or special file");
        }
        if (!directory) {
            final Object links;
            try {
                links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            } catch (UnsupportedOperationException error) {
                throw new UnsafeWorkspaceException("filesystem does not expose hard-link counts");
            }
            if (!(links instanceof Number number) || number.longValue() != 1L) {
                throw new UnsafeWorkspaceException("candidate workspace contains a hard-linked entry");
            }
        }
        Object identity = attributes.fileKey();
        if (identity == null || !identities.add(identity)) {
            throw new UnsafeWorkspaceException("candidate workspace entry identity is unavailable or reused");
        }
    }

    private static void requirePortableRelative(Path relative) throws IOException {
        if (relative == null || relative.isAbsolute() || relative.normalize().startsWith("..")) {
            throw new UnsafeWorkspaceException("candidate workspace path is not relative");
        }
        // Path.relativize(root, root) is represented as a single empty segment on
        // some providers. The root itself is valid; empty non-root segments are not.
        if (relative.toString().isEmpty()) {
            return;
        }
        for (Path segment : relative) {
            String value = segment.toString();
            if (value.isEmpty() || ".".equals(value) || "..".equals(value)) {
                throw new UnsafeWorkspaceException("candidate workspace path has an unsafe segment");
            }
            for (int index = 0; index < value.length(); index++) {
                if (Character.isISOControl(value.charAt(index))) {
                    throw new UnsafeWorkspaceException("candidate workspace path contains control characters");
                }
            }
        }
    }

    private static String portable(Path relative) {
        StringBuilder value = new StringBuilder();
        for (Path segment : relative) {
            if (!value.isEmpty()) {
                value.append('/');
            }
            value.append(segment);
        }
        return value.toString();
    }

    private static Path requireDirectory(Path raw, String label) throws IOException {
        if (raw == null || !raw.isAbsolute() || Files.isSymbolicLink(raw)
                || !Files.isDirectory(raw, LinkOption.NOFOLLOW_LINKS)) {
            throw new UnsafeWorkspaceException(label + " is not a safe directory");
        }
        return raw.toRealPath();
    }

    private static void setMode(Path path, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException error) {
            throw new UnsafeWorkspaceException("filesystem does not support POSIX snapshot permissions");
        }
    }

    private static void requireMode(Path path, Set<PosixFilePermission> expected) throws IOException {
        final Set<PosixFilePermission> actual;
        try {
            actual = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException error) {
            throw new UnsafeWorkspaceException("filesystem does not support POSIX snapshot permissions");
        }
        if (!actual.equals(expected)) {
            throw new UnsafeWorkspaceException("verifier workspace snapshot has unsafe permissions");
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest = sha256Digest();
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

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private record Entry(String type, String path, String mode, long size, String sha256) {
    }

    private record TreeState(String sha256, int fileCount, long totalBytes) {
    }
}
