package com.paicli.eval.benchmark.formal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Copies only admitted fixture files; hidden verifier/reference trees never enter the worker. */
public final class FormalFixtureMaterializer {
    private static final int MAX_FILES = 50_000;
    private static final long MAX_BYTES = 512L * 1024 * 1024;

    private FormalFixtureMaterializer() {}

    public static MaterializedFixture materialize(FormalExecutionPlan.FixtureSnapshot fixture,
                                                   Path emptyWorkspace) throws IOException {
        Objects.requireNonNull(fixture, "fixture");
        if (fixture.fileCount() > MAX_FILES || fixture.totalBytes() > MAX_BYTES) {
            throw failure("FIXTURE_BUDGET_EXCEEDED");
        }
        Path workspace = canonical(emptyWorkspace);
        if (!Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(workspace).equals(
                        PosixFilePermissions.fromString("rwx------"))) {
            throw failure("WORKSPACE_NOT_PRIVATE");
        }
        try (var entries = Files.list(workspace)) {
            if (entries.findAny().isPresent()) throw failure("WORKSPACE_NOT_EMPTY");
        }
        Path source = canonical(fixture.sourcePath());
        if (workspace.startsWith(source) || source.startsWith(workspace)) {
            throw failure("WORKSPACE_SOURCE_OVERLAP");
        }
        Map<Path, FormalExecutionPlan.FixtureFile> expected = new LinkedHashMap<>();
        for (var file : fixture.files()) expected.put(relative(fixture, file), file);
        verifySourceInventory(fixture, source, expected.keySet());

        Set<Object> sourceIdentities = new HashSet<>();
        for (var entry : expected.entrySet()) {
            Path relative = entry.getKey();
            var file = entry.getValue();
            Path input = fixture.kind() == FormalBenchmarkPreflight.FixtureKind.FILE
                    ? source : source.resolve(relative);
            canonical(input); // Reject symlink parents, not only the final file.
            BasicFileAttributes before = attributes(input, file, sourceIdentities);
            Path output = workspace.resolve(relative).normalize();
            if (!output.startsWith(workspace) || output.equals(workspace)) {
                throw failure("FIXTURE_PATH_ESCAPE");
            }
            createParents(workspace, output.getParent());
            Files.createFile(output, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("0500".equals(file.mode())
                            ? "rwx------" : "rw-------")));
            MessageDigest digest = sha256();
            long size = 0;
            try (InputStream in = Files.newInputStream(input, LinkOption.NOFOLLOW_LINKS);
                 OutputStream out = Files.newOutputStream(output, StandardOpenOption.WRITE,
                         LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    size = Math.addExact(size, count);
                    if (size > file.size()) throw failure("FIXTURE_SIZE_DRIFT");
                    digest.update(buffer, 0, count);
                    out.write(buffer, 0, count);
                }
            }
            BasicFileAttributes after = attributes(input, file, null);
            if (!Objects.equals(before.fileKey(), after.fileKey())
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || size != file.size()
                    || !HexFormat.of().formatHex(digest.digest()).equals(file.sha256())) {
                throw failure("FIXTURE_CONTENT_DRIFT");
            }
        }
        verifySourceInventory(fixture, source, expected.keySet());
        MaterializedFixture result = new MaterializedFixture(workspace, fixture);
        result.verifyReady();
        return result;
    }

    private static void verifySourceInventory(FormalExecutionPlan.FixtureSnapshot fixture,
                                               Path source, Set<Path> expected) throws IOException {
        if (fixture.kind() == FormalBenchmarkPreflight.FixtureKind.FILE) {
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
                throw failure("FIXTURE_KIND_DRIFT");
            return;
        }
        verifyDirectoryInventory(source, expected, true);
    }

    private static void verifyDirectoryInventory(Path source, Set<Path> expected,
                                                   boolean frozen) throws IOException {
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS))
            throw failure("FIXTURE_KIND_DRIFT");
        var directoryMode = PosixFilePermissions.fromString(frozen ? "r-x------" : "rwx------");
        if (!Files.getPosixFilePermissions(source).equals(directoryMode))
            throw failure("DIRECTORY_MODE_DRIFT");
        Set<Path> seen = new HashSet<>();
        Set<Path> directories = new HashSet<>();
        for (Path path : expected) {
            for (Path parent = path.getParent(); parent != null; parent = parent.getParent())
                directories.add(parent);
        }
        try (var walk = Files.walk(source)) {
            var iterator = walk.iterator();
            int count = 0;
            while (iterator.hasNext()) {
                Path node = iterator.next();
                if (++count > MAX_FILES + directories.size() + 1)
                    throw failure("FIXTURE_INVENTORY_DRIFT");
                if (node.equals(source)) continue;
                Path relative = source.relativize(node);
                if (Files.isSymbolicLink(node)) throw failure("FIXTURE_LINK");
                if (Files.isDirectory(node, LinkOption.NOFOLLOW_LINKS)) {
                    if (!directories.contains(relative)) throw failure("FIXTURE_EXTRA_DIRECTORY");
                    if (!Files.getPosixFilePermissions(node).equals(directoryMode))
                        throw failure("DIRECTORY_MODE_DRIFT");
                } else if (Files.isRegularFile(node, LinkOption.NOFOLLOW_LINKS)
                        && expected.contains(relative)) {
                    seen.add(relative);
                } else throw failure("FIXTURE_UNREGISTERED_ENTRY");
            }
        }
        if (!seen.equals(expected)) throw failure("FIXTURE_MISSING_FILE");
    }

    private static BasicFileAttributes attributes(Path path,
            FormalExecutionPlan.FixtureFile expected, Set<Object> identities) throws IOException {
        var attributes = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                || attributes.fileKey() == null
                || ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS))
                        .longValue() != 1
                || identities != null && !identities.add(attributes.fileKey()))
            throw failure("FIXTURE_UNSAFE_FILE");
        if (attributes.size() != expected.size()) throw failure("FIXTURE_SIZE_DRIFT");
        String mode = expected.mode().equals("0500") ? "r-x------" : "r--------";
        if (!Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString(mode)))
            throw failure("FIXTURE_MODE_DRIFT");
        return attributes;
    }

    private static Path relative(FormalExecutionPlan.FixtureSnapshot fixture,
                                 FormalExecutionPlan.FixtureFile file) {
        return fixture.kind() == FormalBenchmarkPreflight.FixtureKind.FILE
                ? Path.of(file.frozenPath()).getFileName()
                : Path.of(fixture.frozenPath()).relativize(Path.of(file.frozenPath()));
    }

    private static Path canonical(Path path) throws IOException {
        if (path == null || !path.isAbsolute() || !path.normalize().equals(path))
            throw failure("NON_CANONICAL_PATH");
        for (Path node = path; node != null; node = node.getParent())
            if (Files.isSymbolicLink(node)) throw failure("FIXTURE_LINK");
        if (!path.toRealPath().equals(path)) throw failure("NON_CANONICAL_PATH");
        return path;
    }

    private static void createParents(Path root, Path parent) throws IOException {
        Path current = root;
        for (Path part : root.relativize(parent)) {
            current = current.resolve(part);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS))
                Files.createDirectory(current, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------")));
            canonical(current);
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
                throw failure("WORKSPACE_UNSAFE_PARENT");
        }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static IOException failure(String code) {
        return new IOException("formal fixture materialization failed: " + code);
    }

    /** Kept by the runner until worker launch; not an assertion about post-task contents. */
    public static final class MaterializedFixture {
        private final Path workspace;
        private final FormalExecutionPlan.FixtureSnapshot admitted;

        private MaterializedFixture(Path workspace, FormalExecutionPlan.FixtureSnapshot admitted) {
            this.workspace = workspace;
            this.admitted = admitted;
        }

        public Path workspace() { return workspace; }
        public String frozenSnapshotSha256() { return admitted.snapshotSha256(); }

        public void verifyReady() throws IOException {
            canonical(workspace);
            Set<Path> expected = new HashSet<>();
            for (var file : admitted.files()) {
                Path relative = relative(admitted, file);
                expected.add(relative);
                Path target = canonical(workspace.resolve(relative));
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(target) != file.size()
                        || ((Number) Files.getAttribute(target, "unix:nlink")).longValue() != 1)
                    throw failure("WORKSPACE_CONTENT_DRIFT");
                MessageDigest digest = sha256();
                try (var in = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
                    byte[] buffer = new byte[16 * 1024];
                    long size = 0;
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        size += count;
                        if (size > file.size()) throw failure("WORKSPACE_SIZE_DRIFT");
                        digest.update(buffer, 0, count);
                    }
                }
                if (!HexFormat.of().formatHex(digest.digest()).equals(file.sha256()))
                    throw failure("WORKSPACE_CONTENT_DRIFT");
                String mode = file.mode().equals("0500") ? "rwx------" : "rw-------";
                if (!Files.getPosixFilePermissions(target).equals(PosixFilePermissions.fromString(mode)))
                    throw failure("WORKSPACE_MODE_DRIFT");
            }
            // Directory inventory is checked separately because a FILE fixture becomes a
            // single file inside a workspace, not the workspace itself.
            verifyDirectoryInventory(workspace, expected, false);
        }
    }
}
