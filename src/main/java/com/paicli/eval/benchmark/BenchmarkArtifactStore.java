package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Creates one path-safe benchmark run tree and persists its deterministic artifacts. */
public final class BenchmarkArtifactStore {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private final Path runDirectory;
    private final DirectoryIdentity runIdentity;

    private BenchmarkArtifactStore(Path runDirectory, DirectoryIdentity runIdentity) {
        this.runDirectory = runDirectory;
        this.runIdentity = runIdentity;
    }

    public static BenchmarkArtifactStore create(Path outputRoot, String runId) throws IOException {
        if (outputRoot == null) {
            throw new IllegalArgumentException("outputRoot must not be null");
        }
        CaseDefinition.requireSafeIdentifier(runId, "run id");
        Path root = outputRoot.toAbsolutePath().normalize();
        preparePrivateOutputRoot(root);
        Path runDirectory = root.resolve(runId).normalize();
        if (!runDirectory.startsWith(root)) {
            throw new IllegalArgumentException("run id escapes outputRoot");
        }
        createNewSecureDirectory(runDirectory);
        return new BenchmarkArtifactStore(
                runDirectory,
                DirectoryIdentity.capture(runDirectory, "benchmark run directory"));
    }

    public Path runDirectory() {
        return runDirectory;
    }

    public Path writeManifest(Object manifest) throws IOException {
        return writeRedactedJson(runDirectory.resolve("manifest.json"), manifest);
    }

    public Path writeAggregate(Object aggregate) throws IOException {
        return writeRedactedJson(runDirectory.resolve("aggregate.json"), aggregate);
    }

    public synchronized EpisodeArtifacts episode(String caseId, String modelId, int repeat) throws IOException {
        return episodeDirectory(caseId, modelId, repeat, null);
    }

    /**
     * Creates one append-only formal attempt below a repeat. Existing attempts are never reused.
     * The legacy three-argument overload intentionally keeps the established dev layout.
     */
    public synchronized EpisodeArtifacts episode(
            String caseId,
            String modelId,
            int repeat,
            int attempt) throws IOException {
        if (attempt <= 0) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        return episodeDirectory(caseId, modelId, repeat, attempt);
    }

    private EpisodeArtifacts episodeDirectory(
            String caseId,
            String modelId,
            int repeat,
            Integer attempt) throws IOException {
        CaseDefinition.requireSafeIdentifier(caseId, "case id");
        CaseDefinition.requireSafeIdentifier(modelId, "model id");
        if (repeat <= 0) {
            throw new IllegalArgumentException("repeat must be positive");
        }
        runIdentity.verifyUnchanged();
        Path cases = ensureSecureDirectory(runIdentity.realPath().resolve("cases"));
        Path caseDirectory = ensureSecureDirectory(cases.resolve(caseId));
        Path models = ensureSecureDirectory(caseDirectory.resolve("models"));
        Path modelDirectory = ensureSecureDirectory(models.resolve(modelId));
        Path repeatDirectory = ensureSecureDirectory(modelDirectory.resolve("repeat-%03d".formatted(repeat)));
        Path episodeDirectory = repeatDirectory;
        if (attempt != null) {
            episodeDirectory = repeatDirectory.resolve("attempt-%03d".formatted(attempt));
            createNewSecureDirectory(episodeDirectory);
        }
        runIdentity.verifyUnchanged();
        DirectoryIdentity episodeIdentity = DirectoryIdentity.capture(
                episodeDirectory, "benchmark episode directory");
        requireContainedDirectory(runIdentity.realPath(), episodeIdentity.realPath());
        requireNoSymbolicLinksBetween(runIdentity.realPath(), episodeIdentity.realPath());
        return new EpisodeArtifacts(runIdentity, episodeIdentity);
    }

    public static final class EpisodeArtifacts {
        private final Path directory;
        private final DirectoryIdentity runIdentity;
        private final DirectoryIdentity episodeIdentity;

        private EpisodeArtifacts(DirectoryIdentity runIdentity, DirectoryIdentity episodeIdentity) {
            this.directory = episodeIdentity.realPath();
            this.runIdentity = runIdentity;
            this.episodeIdentity = episodeIdentity;
        }

        public Path directory() {
            return directory;
        }

        /** Creates a new owner-only directory below this episode (for example workspace or home). */
        public Path createPrivateDirectory(String name) throws IOException {
            CaseDefinition.requireSafeIdentifier(name, "episode directory name");
            Path target = directory.resolve(name).normalize();
            if (!target.startsWith(directory)) {
                throw new IllegalArgumentException("episode directory escapes artifact root");
            }
            createNewSecureDirectory(target);
            return target;
        }

        /** Removes runner-owned episode contents after a credential-canary violation. */
        public void purgeContentsForSecurityFailure() throws IOException {
            verifyPurgeRoots();
            try (DirectoryStream<Path> opened = Files.newDirectoryStream(episodeIdentity.realPath())) {
                if (opened instanceof SecureDirectoryStream<?>) {
                    @SuppressWarnings("unchecked")
                    SecureDirectoryStream<Path> secure = (SecureDirectoryStream<Path>) opened;
                    verifyPurgeRoots();
                    episodeIdentity.verifyOpenDirectory(secure);
                    purgeChildrenSecurely(secure, episodeIdentity.realPath(), episodeIdentity.realPath());
                    return;
                }
            }
            // macOS's default NIO provider does not expose SecureDirectoryStream. This bounded
            // fallback starts only after the captured roots were revalidated and never follows
            // links. Formal publication still requires a container/VM boundary and does not rely
            // on path-based deletion remaining race-free against a hostile concurrent process.
            purgeChildrenNoFollow(episodeIdentity.realPath());
        }

        private void verifyPurgeRoots() throws IOException {
            runIdentity.verifyUnchanged();
            episodeIdentity.verifyUnchanged();
            requireContainedDirectory(runIdentity.realPath(), episodeIdentity.realPath());
            requireNoSymbolicLinksBetween(runIdentity.realPath(), episodeIdentity.realPath());
        }

        public Path writeRun(Object run) throws IOException {
            return writeRedactedJson(directory.resolve("run.json"), run);
        }

        public Path writeVerifier(Object verifier) throws IOException {
            return writeRedactedJson(directory.resolve("verifier.json"), verifier);
        }

        public Path writeAnswer(String answer) throws IOException {
            return writeSecure(directory.resolve("answer.md"), SecretRedactor.redact(text(answer)));
        }

        /** Writes the unredacted private trace; owner-only permissions are applied when supported. */
        public Path writeRawTrace(String rawJsonLines) throws IOException {
            return writeSecure(directory.resolve("raw.jsonl"), text(rawJsonLines));
        }
    }

    private static void purgeChildrenNoFollow(Path episodeRoot) throws IOException {
        List<Path> children;
        try (var stream = Files.list(episodeRoot)) {
            children = stream.toList();
        }
        for (Path child : children) {
            Path normalized = child.toAbsolutePath().normalize();
            if (!normalized.startsWith(episodeRoot) || normalized.equals(episodeRoot)) {
                throw new IOException("benchmark artifact purge entry escapes episode root: " + child);
            }
            Files.walkFileTree(normalized, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                        throws IOException {
                    if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                        throw new IOException(
                                "unsafe benchmark artifact directory during purge: " + directory);
                    }
                    Files.setPosixFilePermissions(directory, DIRECTORY_PERMISSIONS);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                        throws IOException {
                    requirePurgeTarget(episodeRoot, file);
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException error)
                        throws IOException {
                    if (error != null) {
                        throw error;
                    }
                    requirePurgeTarget(episodeRoot, directory);
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private static void requirePurgeTarget(Path episodeRoot, Path target) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.startsWith(episodeRoot) || normalized.equals(episodeRoot)) {
            throw new IOException("benchmark artifact purge target escapes episode root: " + target);
        }
        if (Files.isSymbolicLink(target)) {
            // walkFileTree does not follow links by default; deleting this path removes the link.
            return;
        }
    }

    private static void purgeChildrenSecurely(SecureDirectoryStream<Path> directory,
                                              Path currentDirectory,
                                              Path episodeRoot) throws IOException {
        List<Path> names = new ArrayList<>();
        for (Path entry : directory) {
            Path name = entry.getFileName();
            if (name == null || name.isAbsolute() || name.getNameCount() != 1
                    || ".".equals(name.toString()) || "..".equals(name.toString())) {
                throw new IOException("unsafe benchmark artifact entry during purge: " + entry);
            }
            names.add(name);
        }
        for (Path name : names) {
            Path displayPath = currentDirectory.resolve(name).normalize();
            if (!displayPath.startsWith(episodeRoot) || displayPath.equals(episodeRoot)) {
                throw new IOException("benchmark artifact purge entry escapes episode root: " + name);
            }

            BasicFileAttributeView view = directory.getFileAttributeView(
                    name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) {
                throw new IOException("cannot inspect benchmark artifact before purge: " + displayPath);
            }
            BasicFileAttributes attributes = view.readAttributes();
            if (attributes.isDirectory() && !attributes.isSymbolicLink()) {
                PosixFileAttributeView posix = directory.getFileAttributeView(
                        name, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (posix == null) {
                    throw new IOException(
                            "cannot restore benchmark artifact directory permissions before purge: "
                                    + displayPath);
                }
                posix.setPermissions(DIRECTORY_PERMISSIONS);
                try (SecureDirectoryStream<Path> child = directory.newDirectoryStream(
                        name, LinkOption.NOFOLLOW_LINKS)) {
                    purgeChildrenSecurely(child, displayPath, episodeRoot);
                }
                directory.deleteDirectory(name);
            } else {
                directory.deleteFile(name);
            }
        }
    }

    private static void requireContainedDirectory(Path runRoot, Path episodeRoot) throws IOException {
        if (runRoot == null || episodeRoot == null
                || episodeRoot.equals(runRoot) || !episodeRoot.startsWith(runRoot)) {
            throw new IOException("benchmark episode directory escapes captured run root");
        }
    }

    private static void requireNoSymbolicLinksBetween(Path runRoot, Path episodeRoot) throws IOException {
        requireContainedDirectory(runRoot, episodeRoot);
        Path current = runRoot;
        if (Files.isSymbolicLink(current)) {
            throw new IOException("captured benchmark run root became a symbolic link: " + current);
        }
        for (Path segment : runRoot.relativize(episodeRoot)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("captured benchmark episode path contains a symbolic link: " + current);
            }
        }
    }

    private record DirectoryIdentity(Path declaredPath,
                                     Path realPath,
                                     Object fileKey,
                                     String label) {
        private static DirectoryIdentity capture(Path raw, String label) throws IOException {
            Path declared = raw.toAbsolutePath().normalize();
            requirePlainDirectory(declared, label);
            Path real = declared.toRealPath();
            requirePlainDirectory(real, label);
            BasicFileAttributes attributes = Files.readAttributes(
                    real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return new DirectoryIdentity(declared, real, attributes.fileKey(), label);
        }

        private void verifyUnchanged() throws IOException {
            requirePlainDirectory(declaredPath, label);
            Path currentReal = declaredPath.toRealPath();
            if (!currentReal.equals(realPath)) {
                throw new IOException(label + " changed real location: " + declaredPath);
            }
            requirePlainDirectory(realPath, label);
            verifyAttributes(Files.readAttributes(
                    realPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
        }

        private void verifyOpenDirectory(SecureDirectoryStream<Path> directory) throws IOException {
            BasicFileAttributeView view = directory.getFileAttributeView(BasicFileAttributeView.class);
            if (view == null) {
                throw new IOException("cannot inspect open " + label);
            }
            verifyAttributes(view.readAttributes());
        }

        private void verifyAttributes(BasicFileAttributes attributes) throws IOException {
            if (attributes == null || !attributes.isDirectory() || attributes.isSymbolicLink()) {
                throw new IOException(label + " is no longer the captured directory: " + declaredPath);
            }
            if (fileKey != null && !fileKey.equals(attributes.fileKey())) {
                throw new IOException(label + " identity changed: " + declaredPath);
            }
        }

        private static void requirePlainDirectory(Path path, String label) throws IOException {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(path)
                    || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(label + " is not a safe non-symlink directory: " + path);
            }
        }
    }

    private static Path writeRedactedJson(Path target, Object value) throws IOException {
        String json = MAPPER.writeValueAsString(value);
        return writeSecure(target, SecretRedactor.redact(json) + System.lineSeparator());
    }

    private static Path writeSecure(Path target, String content) throws IOException {
        Path parent = ensureSecureDirectory(target.toAbsolutePath().normalize().getParent());
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.startsWith(parent)) {
            throw new IllegalArgumentException("artifact target escapes its directory");
        }
        if (Files.isSymbolicLink(normalized)) {
            throw new IOException("refusing to replace symbolic-link artifact: " + normalized);
        }

        Path temporary = Files.createTempFile(parent, ".artifact-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            setFilePermissionsBestEffort(temporary);
            try {
                Files.move(temporary, normalized,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, normalized, StandardCopyOption.REPLACE_EXISTING);
            }
            setFilePermissionsBestEffort(normalized);
            return normalized;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void createNewSecureDirectory(Path directory) throws IOException {
        try {
            Files.createDirectory(directory);
        } catch (FileAlreadyExistsException e) {
            throw new FileAlreadyExistsException("benchmark run already exists: " + directory);
        }
        setDirectoryPermissionsBestEffort(directory);
    }

    private static void preparePrivateOutputRoot(Path root) throws IOException {
        Path plannedRealLocation = resolveAgainstRealAncestor(root);
        rejectBroadOutputRoot(plannedRealLocation);
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(root)
                    || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("benchmark output root is not a safe directory: " + root);
            }
            Path actualRoot = root.toRealPath();
            rejectBroadOutputRoot(actualRoot);
            if (!actualRoot.equals(plannedRealLocation)) {
                throw new IOException("benchmark output root changed during validation: " + root);
            }
            requireOwnerOnlyDirectory(root);
            return;
        }

        Path parent = root.getParent();
        if (parent == null || !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(parent)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "benchmark output parent must be an existing non-symlink directory: " + parent);
        }
        requireSafeParentDirectory(parent.toRealPath());
        try {
            Files.createDirectory(root, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
        } catch (UnsupportedOperationException e) {
            Files.createDirectory(root);
            setDirectoryPermissionsBestEffort(root);
        }
        Path actualRoot = root.toRealPath();
        rejectBroadOutputRoot(actualRoot);
        if (!actualRoot.equals(plannedRealLocation)) {
            throw new IOException("benchmark output root changed during creation: " + root);
        }
        requireOwnerOnlyDirectory(root);
    }

    /** Resolves an absent target through its nearest existing real ancestor without creating it. */
    static Path resolveAgainstRealAncestor(Path candidate) throws IOException {
        if (candidate == null) {
            throw new IllegalArgumentException("candidate must not be null");
        }
        Path normalized = candidate.toAbsolutePath().normalize();
        Path ancestor = normalized;
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.getParent();
        }
        if (ancestor == null) {
            throw new IOException("benchmark output has no existing filesystem ancestor: " + normalized);
        }
        Path suffix = ancestor.relativize(normalized);
        Path realAncestor = ancestor.toRealPath();
        if (!suffix.toString().isEmpty() && !Files.isDirectory(realAncestor)) {
            throw new IOException("benchmark output ancestor is not a directory: " + ancestor);
        }
        return realAncestor.resolve(suffix).normalize();
    }

    private static void rejectBroadOutputRoot(Path root) throws IOException {
        Path fileSystemRoot = root.getRoot();
        Path userHome = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        if (Files.exists(userHome)) {
            userHome = userHome.toRealPath();
        }
        if (root.equals(fileSystemRoot) || root.equals(userHome)) {
            throw new IOException("benchmark output root is too broad: " + root);
        }
        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (Files.exists(project)) {
            project = project.toRealPath();
        }
        if (root.startsWith(project)) {
            throw new IOException("benchmark output root must be outside the current project: " + root);
        }
        for (String broad : new String[]{"/tmp", "/private/tmp", "/var", "/private/var", "/Users"}) {
            Path broadPath = Path.of(broad).toAbsolutePath().normalize();
            if (Files.exists(broadPath)) {
                broadPath = broadPath.toRealPath();
            }
            if (root.equals(broadPath)) {
                throw new IOException("benchmark output root is too broad: " + root);
            }
        }
    }

    private static void requireSafeParentDirectory(Path parent) throws IOException {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(parent);
            if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw new IOException(
                        "benchmark output parent must not be writable by group or others: " + parent);
            }
            Path userHome = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
            if (Files.exists(userHome)
                    && !Files.getOwner(parent).equals(Files.getOwner(userHome))) {
                throw new IOException("benchmark output parent is not owned by the current user: " + parent);
            }
        } catch (UnsupportedOperationException e) {
            throw new IOException("cannot prove benchmark output parent ownership: " + parent, e);
        }
    }

    private static void requireOwnerOnlyDirectory(Path directory) throws IOException {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(directory);
            Set<PosixFilePermission> forbidden = Set.of(
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_WRITE,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_WRITE,
                    PosixFilePermission.OTHERS_EXECUTE);
            if (permissions.stream().anyMatch(forbidden::contains)
                    || !permissions.contains(PosixFilePermission.OWNER_READ)
                    || !permissions.contains(PosixFilePermission.OWNER_WRITE)
                    || !permissions.contains(PosixFilePermission.OWNER_EXECUTE)) {
                throw new IOException("existing benchmark output root must already be owner-only (0700): "
                        + directory);
            }
            Path userHome = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
            if (Files.exists(userHome)
                    && !Files.getOwner(directory).equals(Files.getOwner(userHome))) {
                throw new IOException("benchmark output root is not owned by the current user: " + directory);
            }
        } catch (UnsupportedOperationException e) {
            throw new IOException(
                    "cannot prove owner-only permissions for existing benchmark output root: " + directory,
                    e);
        }
    }

    private static Path ensureSecureDirectory(Path directory) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(normalized)
                    || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("artifact path is not a safe directory: " + normalized);
            }
        } else {
            Path parent = normalized.getParent();
            if (parent != null && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
                ensureSecureDirectory(parent);
            }
            Files.createDirectory(normalized);
        }
        setDirectoryPermissionsBestEffort(normalized);
        return normalized;
    }

    private static void setDirectoryPermissionsBestEffort(Path directory) {
        try {
            Files.setPosixFilePermissions(directory, DIRECTORY_PERMISSIONS);
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // Non-POSIX file systems and restricted hosts retain their platform defaults.
        }
    }

    private static void setFilePermissionsBestEffort(Path file) {
        try {
            Files.setPosixFilePermissions(file, FILE_PERMISSIONS);
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // Non-POSIX file systems and restricted hosts retain their platform defaults.
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
