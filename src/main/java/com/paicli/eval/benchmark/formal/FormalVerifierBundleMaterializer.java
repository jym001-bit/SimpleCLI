package com.paicli.eval.benchmark.formal;

import com.paicli.eval.benchmark.BenchmarkArtifactStore;
import com.paicli.eval.benchmark.CaseDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Builds a verifier-only dependency tree from a v4 case plan.
 *
 * <p>The caller must create this tree only after the Candidate has stopped and must mount it only
 * into the trusted verifier. This type deliberately has no Candidate/worker integration method.
 * Every copied file is selected solely from {@link FormalExecutionPlan.VerifierDependency}; no
 * directory scan can add an undeclared helper, oracle or hidden check.</p>
 */
public final class FormalVerifierBundleMaterializer {
    private static final String DIRECTORY_NAME = "trusted-verifier";
    private static final int MAX_DEPENDENCIES = 4096;
    private static final long MAX_TOTAL_BYTES = 256L * 1024L * 1024L;
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FROZEN_DIRECTORY = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> DATA_FILE = Set.of(
            PosixFilePermission.OWNER_READ);
    private static final Set<PosixFilePermission> EXECUTABLE_FILE = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_EXECUTE);

    private FormalVerifierBundleMaterializer() {
    }

    /** Creates one new owner-only, read-only verifier bundle below an append-only attempt. */
    public static TrustedVerifierBundle materialize(
            FormalExecutionPlan.CasePlan casePlan,
            BenchmarkArtifactStore.EpisodeArtifacts episode) throws MaterializationException {
        Objects.requireNonNull(casePlan, "casePlan");
        Objects.requireNonNull(episode, "episode");
        List<FormalExecutionPlan.VerifierDependency> dependencies =
                casePlan.verifier().dependencies();
        validateBudget(dependencies);

        final Path root;
        try {
            root = episode.createPrivateDirectory(DIRECTORY_NAME)
                    .toAbsolutePath().normalize();
            setPermissions(root, PRIVATE_DIRECTORY);
            requireDirectoryNoFollow(root, "bundle-root");
        } catch (IOException | RuntimeException error) {
            throw failure(FailureKind.INFRA, "BUNDLE_ROOT_CREATE_FAILED");
        }

        List<MaterializedDependency> copied = new ArrayList<>(dependencies.size());
        List<VerifierBundleIdentity.Entry> actualIdentities = new ArrayList<>(
                dependencies.size());
        for (FormalExecutionPlan.VerifierDependency dependency : dependencies) {
            try {
                MaterializedDependency materialized = copyOne(root, dependency);
                copied.add(materialized);
                actualIdentities.add(new VerifierBundleIdentity.Entry(
                        dependency.frozenPath(),
                        dependency.mode(),
                        materialized.size(),
                        materialized.sha256()));
            } catch (MaterializationException error) {
                throw error;
            } catch (IOException | RuntimeException error) {
                throw failure(FailureKind.INFRA, "DEPENDENCY_COPY_FAILED");
            }
        }

        String actualBundleSha256;
        try {
            actualBundleSha256 = VerifierBundleIdentity.digest(actualIdentities);
        } catch (IllegalArgumentException error) {
            throw failure(FailureKind.DATASET, "BUNDLE_IDENTITY_INVALID");
        }
        if (!actualBundleSha256.equals(casePlan.verifier().bundleSha256())) {
            throw failure(FailureKind.DATASET, "BUNDLE_DIGEST_MISMATCH");
        }

        final Path entry = root.resolve(casePlan.verifier().entryPath()).normalize();
        if (!entry.startsWith(root) || entry.equals(root)) {
            throw failure(FailureKind.SECURITY, "ENTRY_PATH_ESCAPE");
        }
        try {
            MaterializedDependency entryDependency = copied.stream()
                    .filter(value -> value.frozenPath().equals(casePlan.verifier().entryPath()))
                    .findFirst()
                    .orElseThrow();
            if (!entryDependency.target().equals(entry)
                    || !"0500".equals(entryDependency.mode())) {
                throw failure(FailureKind.DATASET, "ENTRY_BINDING_MISMATCH");
            }
            freezeDirectories(root);
            requireDirectoryMode(root, FROZEN_DIRECTORY);
        } catch (MaterializationException error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw failure(FailureKind.INFRA, "BUNDLE_FREEZE_FAILED");
        }
        return new TrustedVerifierBundle(
                root, entry, actualBundleSha256, copied, casePlan.verifier());
    }

    private static MaterializedDependency copyOne(
            Path root,
            FormalExecutionPlan.VerifierDependency dependency)
            throws IOException, MaterializationException {
        Path source = dependency.sourcePath();
        requireSourcePath(source, dependency.frozenPath());
        StableFile before = StableFile.capture(source);
        requireExpectedSource(before, source, dependency);

        Path target = root.resolve(dependency.frozenPath()).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw failure(FailureKind.SECURITY, "DEPENDENCY_PATH_ESCAPE");
        }
        createPrivateParents(root, target.getParent());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(FailureKind.SECURITY, "DEPENDENCY_TARGET_EXISTS");
        }

        CopyDigest copied = copyNoFollow(source, target, dependency.size());
        if (copied.size() != dependency.size()
                || !copied.sha256().equals(dependency.sha256())) {
            throw failure(FailureKind.DATASET, "DEPENDENCY_CHANGED_DURING_COPY");
        }
        setPermissions(target, permissions(dependency.mode()));

        StableFile after = StableFile.capture(source);
        requireExpectedSource(after, source, dependency);
        if (!before.sameIdentity(after)) {
            throw failure(FailureKind.DATASET, "DEPENDENCY_CHANGED_DURING_COPY");
        }
        StableFile destination = StableFile.capture(target);
        if (destination.size() != dependency.size()
                || !destination.sha256().equals(dependency.sha256())) {
            throw failure(FailureKind.SECURITY, "DEPENDENCY_TARGET_TAMPERED");
        }
        try {
            requireFileMode(target, permissions(dependency.mode()));
        } catch (IOException error) {
            throw failure(FailureKind.SECURITY, "DEPENDENCY_TARGET_MODE_TAMPERED");
        }
        return new MaterializedDependency(
                dependency.frozenPath(), target, dependency.mode(),
                destination.size(), destination.sha256());
    }

    private static void requireSourcePath(Path source, String frozenPath)
            throws IOException, MaterializationException {
        if (!source.isAbsolute() || !source.normalize().equals(source)
                || Files.isSymbolicLink(source)
                || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(FailureKind.SECURITY, "DEPENDENCY_SOURCE_UNSAFE");
        }
        Path followed = source.toRealPath();
        if (!followed.equals(source)) {
            throw failure(FailureKind.SECURITY, "DEPENDENCY_SOURCE_SYMLINKED");
        }
        if (frozenPath.isBlank()) {
            throw failure(FailureKind.DATASET, "DEPENDENCY_PATH_INVALID");
        }
    }

    private static void requireExpectedSource(
            StableFile snapshot,
            Path source,
            FormalExecutionPlan.VerifierDependency dependency)
            throws MaterializationException {
        if (snapshot.size() != dependency.size()
                || !snapshot.sha256().equals(dependency.sha256())) {
            throw failure(FailureKind.DATASET, "DEPENDENCY_SOURCE_DRIFT");
        }
        boolean executable = Files.isExecutable(source);
        if (("0500".equals(dependency.mode())) != executable) {
            throw failure(FailureKind.DATASET, "DEPENDENCY_MODE_DRIFT");
        }
    }

    private static CopyDigest copyNoFollow(Path source, Path target, long expectedSize)
            throws IOException, MaterializationException {
        MessageDigest digest = newSha256();
        long total = 0L;
        try (InputStream input = Files.newInputStream(
                source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             OutputStream output = Files.newOutputStream(
                     target,
                     StandardOpenOption.CREATE_NEW,
                     StandardOpenOption.WRITE,
                     LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                total = Math.addExact(total, read);
                if (total > expectedSize || total > MAX_TOTAL_BYTES) {
                    throw failure(FailureKind.DATASET, "DEPENDENCY_SIZE_DRIFT");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        return new CopyDigest(total, HexFormat.of().formatHex(digest.digest()));
    }

    private static void createPrivateParents(Path root, Path parent)
            throws IOException, MaterializationException {
        if (parent == null || !parent.startsWith(root)) {
            throw failure(FailureKind.SECURITY, "DEPENDENCY_PARENT_ESCAPE");
        }
        Path current = root;
        Path relative = root.relativize(parent);
        for (Path part : relative) {
            current = current.resolve(part).normalize();
            if (!current.startsWith(root)) {
                throw failure(FailureKind.SECURITY, "DEPENDENCY_PARENT_ESCAPE");
            }
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                requireDirectoryNoFollow(current, "bundle-parent");
            } else {
                Files.createDirectory(current);
                setPermissions(current, PRIVATE_DIRECTORY);
                requireDirectoryNoFollow(current, "bundle-parent");
            }
        }
    }

    private static void freezeDirectories(Path root) throws IOException {
        List<Path> directories = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    Path directory, BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                    throw new IOException("unsafe verifier bundle directory");
                }
                directories.add(directory);
                return FileVisitResult.CONTINUE;
            }
        });
        directories.sort(Comparator.comparingInt(Path::getNameCount).reversed());
        for (Path directory : directories) {
            setPermissions(directory, FROZEN_DIRECTORY);
            requireDirectoryMode(directory, FROZEN_DIRECTORY);
        }
    }

    private static void requireDirectoryNoFollow(Path path, String label) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw new IOException(label + " is not a no-follow directory");
        }
    }

    private static void requireFileMode(Path path, Set<PosixFilePermission> expected)
            throws IOException {
        Set<PosixFilePermission> actual = Files.getPosixFilePermissions(
                path, LinkOption.NOFOLLOW_LINKS);
        if (!actual.equals(expected)) {
            throw new IOException("verifier dependency mode differs after copy");
        }
    }

    private static void requireDirectoryMode(Path path, Set<PosixFilePermission> expected)
            throws IOException {
        Set<PosixFilePermission> actual = Files.getPosixFilePermissions(
                path, LinkOption.NOFOLLOW_LINKS);
        if (!actual.equals(expected)) {
            throw new IOException("verifier bundle directory mode differs after freeze");
        }
    }

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions)
            throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(
                path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new IOException("POSIX no-follow permissions are unavailable");
        }
        view.setPermissions(permissions);
    }

    private static Set<PosixFilePermission> permissions(String mode) {
        return "0500".equals(mode) ? EXECUTABLE_FILE : DATA_FILE;
    }

    private static void validateBudget(
            List<FormalExecutionPlan.VerifierDependency> dependencies)
            throws MaterializationException {
        if (dependencies.isEmpty() || dependencies.size() > MAX_DEPENDENCIES) {
            throw failure(FailureKind.DATASET, "DEPENDENCY_COUNT_OUT_OF_BOUNDS");
        }
        long total = 0L;
        try {
            for (FormalExecutionPlan.VerifierDependency dependency : dependencies) {
                total = Math.addExact(total, dependency.size());
            }
        } catch (ArithmeticException error) {
            throw failure(FailureKind.DATASET, "DEPENDENCY_BYTES_OUT_OF_BOUNDS");
        }
        if (total > MAX_TOTAL_BYTES) {
            throw failure(FailureKind.DATASET, "DEPENDENCY_BYTES_OUT_OF_BOUNDS");
        }
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static MaterializationException failure(FailureKind kind, String code) {
        return new MaterializationException(kind, code);
    }

    /** Trusted result intended only for the verifier container/process. */
    public static final class TrustedVerifierBundle {
        private final Path root;
        private final Path entry;
        private final String bundleSha256;
        private final List<MaterializedDependency> dependencies;
        private final FormalExecutionPlan.VerifierCommand registeredCommand;

        private TrustedVerifierBundle(
                Path root,
                Path entry,
                String bundleSha256,
                List<MaterializedDependency> dependencies,
                FormalExecutionPlan.VerifierCommand registeredCommand) {
            this.root = root;
            this.entry = entry;
            this.bundleSha256 = bundleSha256;
            this.dependencies = List.copyOf(dependencies);
            this.registeredCommand = registeredCommand;
        }

        public Path root() {
            return root;
        }

        public Path entry() {
            return entry;
        }

        public String bundleSha256() {
            return bundleSha256;
        }

        public List<MaterializedDependency> dependencies() {
            return dependencies;
        }

        /** Recheck the complete mounted tree immediately before and after trusted verification. */
        public void verifyUnchanged() throws IOException {
            requireDirectoryNoFollow(root, "bundle-root");
            if (!root.toRealPath().equals(root)) throw failure(FailureKind.SECURITY, "BUNDLE_ROOT_CHANGED");
            requireDirectoryMode(root, FROZEN_DIRECTORY);
            java.util.Map<Path, MaterializedDependency> expected = new java.util.HashMap<>();
            Set<Path> directories = new java.util.HashSet<>();
            for (var dependency : dependencies) {
                expected.put(dependency.target(), dependency);
                for (Path parent = dependency.target().getParent(); !parent.equals(root);
                     parent = parent.getParent()) directories.add(parent);
            }
            Set<Path> seen = new java.util.HashSet<>();
            Set<Object> fileKeys = new java.util.HashSet<>();
            try (var walk = Files.walk(root)) {
                var iterator = walk.iterator();
                int count = 0;
                while (iterator.hasNext()) {
                    Path node = iterator.next();
                    if (++count > dependencies.size() + directories.size() + 1)
                        throw failure(FailureKind.SECURITY, "BUNDLE_EXTRA_ENTRY");
                    if (node.equals(root)) continue;
                    if (Files.isSymbolicLink(node)) throw failure(FailureKind.SECURITY, "BUNDLE_LINK");
                    if (Files.isDirectory(node, LinkOption.NOFOLLOW_LINKS)) {
                        if (!directories.contains(node)) throw failure(FailureKind.SECURITY, "BUNDLE_EXTRA_DIRECTORY");
                        requireDirectoryMode(node, FROZEN_DIRECTORY);
                        continue;
                    }
                    var dependency = expected.get(node);
                    if (dependency == null) throw failure(FailureKind.SECURITY, "BUNDLE_EXTRA_FILE");
                    var bytes = StableFile.capture(node);
                    if (!fileKeys.add(bytes.fileKey())
                            || ((Number) Files.getAttribute(node, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1
                            || bytes.size() != dependency.size()
                            || !bytes.sha256().equals(dependency.sha256()))
                        throw failure(FailureKind.SECURITY, "BUNDLE_CONTENT_CHANGED");
                    requireFileMode(node, permissions(dependency.mode()));
                    seen.add(node);
                }
            }
            if (!seen.equals(expected.keySet())) throw failure(FailureKind.SECURITY, "BUNDLE_MISSING_FILE");
        }

        /** Materializes placeholders while changing cwd from the dataset to this private bundle. */
        public FormalExecutionPlan.MaterializedVerifier command(Path workspace, Path evidence) {
            Path safeWorkspace = requireAbsolute(workspace, "workspace");
            if (registeredCommand.requiresEvidence() && evidence == null) {
                throw new IllegalArgumentException("registered verifier requires evidence");
            }
            Path safeEvidence = evidence == null ? null : requireAbsolute(evidence, "evidence");
            List<String> arguments = registeredCommand.registeredArguments().stream()
                    .map(argument -> {
                        if (CaseDefinition.WORKSPACE_PLACEHOLDER.equals(argument)) {
                            return safeWorkspace.toString();
                        }
                        if (CaseDefinition.EVIDENCE_PLACEHOLDER.equals(argument)) {
                            return Objects.requireNonNull(safeEvidence).toString();
                        }
                        return argument;
                    })
                    .toList();
            return new FormalExecutionPlan.MaterializedVerifier(root, arguments);
        }

        private static Path requireAbsolute(Path value, String label) {
            Objects.requireNonNull(value, label);
            Path normalized = value.toAbsolutePath().normalize();
            if (!value.isAbsolute() || !normalized.equals(value)) {
                throw new IllegalArgumentException(label + " must be absolute and normalized");
            }
            return value;
        }
    }

    public record MaterializedDependency(
            String frozenPath,
            Path target,
            String mode,
            long size,
            String sha256) {
        public MaterializedDependency {
            FormalContractSupport.requireRelativePath(frozenPath, "materialized dependency path");
            target = target.toAbsolutePath().normalize();
            if (!"0400".equals(mode) && !"0500".equals(mode)) {
                throw new IllegalArgumentException("invalid materialized dependency mode");
            }
            if (size < 0) {
                throw new IllegalArgumentException("invalid materialized dependency size");
            }
            FormalContractSupport.requireSha256(sha256, "materialized dependency sha256");
        }
    }

    public enum FailureKind {
        INFRA,
        SECURITY,
        DATASET
    }

    /** Typed materialization failure with a bounded non-path-bearing reason code. */
    @SuppressWarnings("serial") // In-process control flow; never serialized into artifacts.
    public static final class MaterializationException extends IOException {
        private final FailureKind kind;
        private final String code;

        private MaterializationException(FailureKind kind, String code) {
            super("formal verifier bundle materialization failed: " + code);
            this.kind = Objects.requireNonNull(kind, "kind");
            this.code = Objects.requireNonNull(code, "code");
        }

        public FailureKind kind() {
            return kind;
        }

        public String code() {
            return code;
        }
    }

    private record CopyDigest(long size, String sha256) {
    }

    private record StableFile(
            Object fileKey,
            long size,
            FileTime modified,
            String sha256) {
        private static StableFile capture(Path file) throws IOException {
            BasicFileAttributes before = Files.readAttributes(
                    file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile() || before.isSymbolicLink() || before.fileKey() == null) {
                throw new IOException("verifier dependency lacks stable regular-file identity");
            }
            MessageDigest digest = newSha256();
            long total = 0L;
            try (InputStream input = Files.newInputStream(
                    file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    total = Math.addExact(total, read);
                    if (total > MAX_TOTAL_BYTES) {
                        throw new IOException("verifier dependency exceeds materializer bound");
                    }
                    digest.update(buffer, 0, read);
                }
            }
            BasicFileAttributes after = Files.readAttributes(
                    file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!sameBasic(before, after) || total != before.size()) {
                throw new IOException("verifier dependency changed while hashing");
            }
            return new StableFile(
                    before.fileKey(), before.size(), before.lastModifiedTime(),
                    HexFormat.of().formatHex(digest.digest()));
        }

        private boolean sameIdentity(StableFile other) {
            return fileKey.equals(other.fileKey)
                    && size == other.size
                    && modified.equals(other.modified)
                    && sha256.equals(other.sha256);
        }

        private static boolean sameBasic(
                BasicFileAttributes left, BasicFileAttributes right) {
            return left.isRegularFile() && right.isRegularFile()
                    && !left.isSymbolicLink() && !right.isSymbolicLink()
                    && Objects.equals(left.fileKey(), right.fileKey())
                    && left.size() == right.size()
                    && left.lastModifiedTime().equals(right.lastModifiedTime());
        }
    }
}
