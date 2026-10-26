package com.paicli.eval.benchmark.finalset;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.paicli.eval.benchmark.CaseDefinition;
import com.paicli.eval.benchmark.SuiteDefinition;

import java.io.IOException;
import java.io.InputStream;
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
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Materializes a private final-suite source tree into an owner-only, read-only frozen snapshot.
 *
 * <p>This class freezes dataset bytes only. It intentionally does not make a benchmark run
 * publishable, expose private fixtures to the public repository, or connect the snapshot to the
 * coordinator. It does not enforce the 28-case executable-final blueprint or bind per-case
 * tool/mock/budget/assertion/evidence contracts; that belongs to a higher-level preregistered run
 * manifest.</p>
 */
public final class FinalDatasetFreezer {
    public static final String MANIFEST_FILE = "freeze-manifest.json";
    public static final String COMPLETE_MARKER = ".freeze-complete";
    public static final String CANARY_FILE = ".private-final-dataset-canary";

    private static final int MAX_MANIFEST_BYTES = 16 * 1024 * 1024;
    private static final int MAX_MARKER_BYTES = 4 * 1024;
    private static final int MARKER_VERSION = 1;
    private static final String MARKER_FORMAT = "paicli-final-dataset-freeze-complete-v1";
    private static final Pattern CANARY = Pattern.compile(
            "PAICLI-FINAL-CANARY-v1:[0-9a-f]{64}");
    private static final Set<String> RESERVED_TOP_LEVEL = Set.of(
            MANIFEST_FILE, COMPLETE_MARKER, ".git", ".hg", ".svn");
    private static final Set<PosixFilePermission> WRITABLE_DIRECTORY = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> READ_ONLY_DIRECTORY = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> WRITABLE_FILE = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> WRITABLE_EXECUTABLE = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> READ_ONLY_FILE = Set.of(
            PosixFilePermission.OWNER_READ);
    private static final Set<PosixFilePermission> READ_ONLY_EXECUTABLE = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_EXECUTE);
    private static final ObjectMapper MAPPER = new ObjectMapper(
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Clock clock;

    public FinalDatasetFreezer() {
        this(Clock.systemUTC());
    }

    /** Exposed for deterministic evidence tests. */
    public FinalDatasetFreezer(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.clock = clock;
    }

    /** Creates a new freeze. Existing destinations are never replaced or merged. */
    public FreezeResult freeze(FreezeRequest request) throws IOException {
        if (request == null) {
            throw new IllegalArgumentException("freeze request must not be null");
        }
        FreezeContext context = prepareFreeze(request);
        try (SecureDirectoryStream<Path> destinationDirectory =
                     context.destinationParent().openSecureDirectory()) {
            DirectoryIdentity stagingIdentity = createStagingDirectory(
                    context.destinationParent(), destinationDirectory,
                    context.destination().getFileName().toString());
            Path staging = stagingIdentity.realPath();
            boolean moved = false;
            try {
                context.source().verifyUnchanged();
                copySourceTree(context.source().realPath(), staging);
                context.source().verifyUnchanged();
                stagingIdentity.verifyUnchanged();
                String canaryValue = readAndValidateCanary(staging.resolve(CANARY_FILE));
                requireCanaryOnlyInMarkerFile(staging, staging.resolve(CANARY_FILE), canaryValue);

                SuiteBinding binding = validateSuiteBinding(
                        staging, context.suitePath(), context.validatorRoot());
                List<FinalDatasetFreezeManifest.FileEntry> entries = collectSourceEntries(staging);
                FinalDatasetFreezeManifest manifest = buildManifest(
                        entries, binding, context.suitePath(), context.validatorRoot());
                writeJsonNew(staging.resolve(MANIFEST_FILE), manifest);
                String manifestSha256 = sha256(staging.resolve(MANIFEST_FILE));
                verifyManifestContent(stagingIdentity, context.publicRepository(), manifest);
                writeCompletionMarkerAtomically(staging, new CompletionMarker(
                        MARKER_VERSION,
                        MARKER_FORMAT,
                        manifestSha256,
                        manifest.contentTreeSha256()));

                verifySnapshot(stagingIdentity, context.publicRepository(), false);
                makeSnapshotReadOnly(staging, manifest);
                verifySnapshot(stagingIdentity, context.publicRepository(), true);

                // Keep sealed children read-only. Only the root regains owner write permission for
                // the captured-parent relative rename, after which the destination is sealed again.
                Files.setPosixFilePermissions(staging, WRITABLE_DIRECTORY);
                requireOwnerOnlyDirectory(staging, true, "freeze staging directory");
                context.destinationParent().verifyOpenDirectory(destinationDirectory);
                context.destinationParent().verifyUnchanged();
                stagingIdentity.verifyChildDirectory(
                        destinationDirectory, staging.getFileName());
                if (Files.exists(context.destination(), LinkOption.NOFOLLOW_LINKS)) {
                    throw new FileAlreadyExistsException(
                            "refusing to overwrite existing final dataset freeze: "
                                    + context.destination());
                }
                try {
                    destinationDirectory.move(
                            staging.getFileName(), destinationDirectory,
                            context.destination().getFileName());
                } catch (FileAlreadyExistsException error) {
                    throw new FileAlreadyExistsException(
                            "refusing to overwrite existing final dataset freeze: "
                                    + context.destination());
                }
                moved = true;
                context.destinationParent().verifyOpenDirectory(destinationDirectory);
                context.destinationParent().verifyUnchanged();
                DirectoryIdentity destinationIdentity = DirectoryIdentity.capture(
                        context.destination(), "frozenRoot");
                if (!stagingIdentity.fileKey().equals(destinationIdentity.fileKey())) {
                    throw new IOException("final dataset root identity changed during move");
                }
                Files.setPosixFilePermissions(context.destination(), READ_ONLY_DIRECTORY);
                VerifiedSnapshot finalSnapshot = verifySnapshot(
                        destinationIdentity, context.publicRepository(), true);
                if (!finalSnapshot.manifest().equals(manifest)
                        || !finalSnapshot.manifestSha256().equals(manifestSha256)) {
                    throw new IOException("final dataset changed while moving to destination");
                }
                context.destinationParent().verifyOpenDirectory(destinationDirectory);
                context.destinationParent().verifyUnchanged();
                return new FreezeResult(context.destination(),
                        finalSnapshot.manifest(), finalSnapshot.manifestSha256());
            } catch (IOException | RuntimeException error) {
                if (!moved) {
                    try {
                        deleteOwnedStagingTree(
                                context.destinationParent(), destinationDirectory, stagingIdentity);
                    } catch (IOException cleanupError) {
                        error.addSuppressed(cleanupError);
                    }
                }
                throw error;
            }
        }
    }

    /** Re-verifies hashes, tree membership, suite bindings, canary isolation and read-only modes. */
    public FreezeResult verify(Path frozenRoot, Path publicRepositoryRoot) throws IOException {
        Path frozen = requireAbsolutePath(frozenRoot, "frozenRoot");
        Path publicRepositoryPath = requirePublicRepository(publicRepositoryRoot);
        DirectoryIdentity publicRepository = DirectoryIdentity.capture(
                publicRepositoryPath, "publicRepositoryRoot");
        Path realFrozen = requireExistingPlainDirectory(frozen, "frozenRoot");
        DirectoryIdentity frozenIdentity = DirectoryIdentity.capture(realFrozen, "frozenRoot");
        Path frozenParent = realFrozen.getParent();
        if (frozenParent == null) {
            throw new IOException("frozenRoot must have an owner-only parent directory");
        }
        requireOwnerOnlyDirectory(frozenParent, false, "frozenRoot parent");
        rejectOverlap(realFrozen, publicRepository.realPath(),
                "frozenRoot", "publicRepositoryRoot");
        rejectGitAncestor(realFrozen, "frozenRoot");
        VerifiedSnapshot verified = verifySnapshot(frozenIdentity, publicRepository, true);
        return new FreezeResult(realFrozen, verified.manifest(), verified.manifestSha256());
    }

    private FreezeContext prepareFreeze(FreezeRequest request) throws IOException {
        Path source = requireAbsolutePath(request.sourceRoot(), "sourceRoot");
        Path destination = requireAbsolutePath(request.frozenRoot(), "frozenRoot");
        String suitePath = requireRequestedRelativePath(request.suitePath(), "suitePath");
        String validatorRoot = requireRequestedRelativePath(request.validatorRoot(), "validatorRoot");
        if (suitePath.equals(validatorRoot) || suitePath.startsWith(validatorRoot + "/")) {
            throw new IllegalArgumentException("suitePath must be outside validatorRoot");
        }

        Path realSource = requireExistingPlainDirectory(source, "sourceRoot");
        requireOwnerOnlyDirectory(realSource, false, "sourceRoot");
        DirectoryIdentity sourceIdentity = DirectoryIdentity.capture(realSource, "sourceRoot");
        Path sourceParent = realSource.getParent();
        if (sourceParent == null) {
            throw new IOException("sourceRoot must have a private parent directory");
        }
        requireOwnerOnlyDirectory(sourceParent, true, "sourceRoot parent");
        rejectGitAncestor(realSource, "sourceRoot");

        Path publicRepositoryPath = requirePublicRepository(request.publicRepositoryRoot());
        DirectoryIdentity publicRepository = DirectoryIdentity.capture(
                publicRepositoryPath, "publicRepositoryRoot");
        Path destinationParent = destination.getParent();
        if (destinationParent == null) {
            throw new IllegalArgumentException("frozenRoot must have a parent directory");
        }
        Path realDestinationParent = requireExistingPlainDirectory(
                destinationParent, "frozenRoot parent");
        requireOwnerOnlyDirectory(realDestinationParent, true, "frozenRoot parent");
        DirectoryIdentity destinationParentIdentity = DirectoryIdentity.capture(
                realDestinationParent, "frozenRoot parent");
        rejectGitAncestor(realDestinationParent, "frozenRoot parent");
        Path plannedDestination = realDestinationParent.resolve(destination.getFileName()).normalize();
        if (!plannedDestination.equals(destination)) {
            throw new IOException("frozenRoot changes after resolving its parent: " + destination);
        }
        rejectReservedDestinationName(plannedDestination.getFileName().toString());
        if (Files.exists(plannedDestination, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(
                    "refusing to overwrite existing final dataset freeze: " + plannedDestination);
        }

        rejectOverlap(realSource, publicRepository.realPath(),
                "sourceRoot", "publicRepositoryRoot");
        rejectOverlap(plannedDestination, publicRepository.realPath(),
                "frozenRoot", "publicRepositoryRoot");
        rejectOverlap(realSource, plannedDestination, "sourceRoot", "frozenRoot");

        sourceIdentity.verifyUnchanged();
        publicRepository.verifyUnchanged();
        String canaryValue = readAndValidateCanary(realSource.resolve(CANARY_FILE));
        requireCanaryOnlyInMarkerFile(realSource, realSource.resolve(CANARY_FILE), canaryValue);
        rejectCanaryInPublicRepository(publicRepository, canaryValue);
        sourceIdentity.verifyUnchanged();
        publicRepository.verifyUnchanged();
        destinationParentIdentity.verifyUnchanged();

        return new FreezeContext(sourceIdentity, plannedDestination, destinationParentIdentity,
                publicRepository, suitePath, validatorRoot);
    }

    private static Path requirePublicRepository(Path raw) throws IOException {
        Path repository = requireAbsolutePath(raw, "publicRepositoryRoot");
        Path realRepository = requireExistingPlainDirectory(repository, "publicRepositoryRoot");
        Path gitMarker = realRepository.resolve(".git");
        if (!Files.exists(gitMarker, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(gitMarker)
                || (!Files.isDirectory(gitMarker, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(gitMarker, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("publicRepositoryRoot must be a non-symlink Git worktree: "
                    + realRepository);
        }
        return realRepository;
    }

    private static Path requireAbsolutePath(Path raw, String label) {
        if (raw == null) {
            throw new IllegalArgumentException(label + " must not be null");
        }
        if (!raw.isAbsolute()) {
            throw new IllegalArgumentException(label + " must be absolute");
        }
        for (Path segment : raw) {
            String value = segment.toString();
            if (".".equals(value) || "..".equals(value)) {
                throw new IllegalArgumentException(label + " must not contain '.' or '..' segments");
            }
        }
        Path normalized = raw.normalize();
        if (!normalized.equals(raw)) {
            throw new IllegalArgumentException(label + " must already be normalized");
        }
        return normalized;
    }

    private static Path requireExistingPlainDirectory(Path path, String label) throws IOException {
        requireNoSymlinkSegments(path, label);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)
                || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " must be an existing non-symlink directory: " + path);
        }
        Path real = path.toRealPath();
        if (!real.equals(path)) {
            throw new IOException(label + " must not traverse aliases or symbolic links: " + path);
        }
        requirePosix(real, label);
        return real;
    }

    private static void requireNoSymlinkSegments(Path path, String label) throws IOException {
        Path current = path.getRoot();
        if (current == null) {
            throw new IllegalArgumentException(label + " must be absolute");
        }
        for (Path segment : path) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IOException(label + " traverses a symbolic link: " + current);
            }
        }
    }

    private static void requirePosix(Path path, String label) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(
                path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new IOException("cannot prove owner-only POSIX permissions for " + label + ": " + path);
        }
    }

    private static void requireOwnerOnlyDirectory(Path directory,
                                                  boolean requireWrite,
                                                  String label) throws IOException {
        requirePosix(directory, label);
        requireNoAcl(directory, label);
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
                directory, LinkOption.NOFOLLOW_LINKS);
        if (!permissions.contains(PosixFilePermission.OWNER_READ)
                || !permissions.contains(PosixFilePermission.OWNER_EXECUTE)
                || (requireWrite && !permissions.contains(PosixFilePermission.OWNER_WRITE))) {
            throw new IOException(label + " lacks required owner permissions: " + directory);
        }
        for (PosixFilePermission permission : permissions) {
            if (permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_")) {
                throw new IOException(label + " must be owner-only: " + directory);
            }
        }
        Path userHome = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        if (Files.exists(userHome, LinkOption.NOFOLLOW_LINKS)
                && !Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS)
                .equals(Files.getOwner(userHome, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException(label + " is not owned by the current user: " + directory);
        }
    }

    private static void requireNoAcl(Path path, String label) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(
                path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view != null && !view.getAcl().isEmpty()) {
            throw new IOException(label + " must not have an extended ACL: " + path);
        }
    }

    private static void rejectGitAncestor(Path path, String label) throws IOException {
        Path current = path;
        while (current != null) {
            Path marker = current.resolve(".git");
            if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(label + " must not be inside any Git worktree: " + current);
            }
            current = current.getParent();
        }
    }

    private static void rejectOverlap(Path first,
                                      Path second,
                                      String firstLabel,
                                      String secondLabel) throws IOException {
        if (first.startsWith(second) || second.startsWith(first)) {
            throw new IOException(firstLabel + " and " + secondLabel + " must be disjoint");
        }
    }

    private static String requireRequestedRelativePath(String raw, String label) {
        FinalDatasetFreezeManifest.requireRelativePath(raw, label);
        String top = raw.substring(0, raw.indexOf('/') < 0 ? raw.length() : raw.indexOf('/'));
        if (RESERVED_TOP_LEVEL.contains(top) || top.startsWith(".freeze-")) {
            throw new IllegalArgumentException(label + " uses a reserved top-level path: " + raw);
        }
        return raw;
    }

    private static void rejectReservedDestinationName(String raw) {
        String normalized = raw.toLowerCase(Locale.ROOT);
        boolean reserved = RESERVED_TOP_LEVEL.stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(normalized::equals);
        if (reserved || normalized.equals(CANARY_FILE.toLowerCase(Locale.ROOT))
                || normalized.startsWith(".freeze-")) {
            throw new IllegalArgumentException(
                    "frozenRoot uses a reserved destination name: " + raw);
        }
    }

    private static DirectoryIdentity createStagingDirectory(
            DirectoryIdentity parent,
            SecureDirectoryStream<Path> parentDirectory,
            String destinationName) throws IOException {
        Path stagingName = Path.of("." + destinationName + ".freeze-"
                + UUID.randomUUID() + ".tmp");
        Path staging = parent.realPath().resolve(stagingName);
        boolean created = false;
        try {
            parent.verifyOpenDirectory(parentDirectory);
            parent.verifyUnchanged();
            Files.createDirectory(staging,
                    PosixFilePermissions.asFileAttribute(WRITABLE_DIRECTORY));
            created = true;
            parent.verifyOpenDirectory(parentDirectory);
            parent.verifyUnchanged();
            requireOwnerOnlyDirectory(staging, true, "freeze staging directory");
            DirectoryIdentity identity = DirectoryIdentity.capture(
                    staging, "freeze staging directory");
            identity.verifyChildDirectory(parentDirectory, stagingName);
            return identity;
        } catch (UnsupportedOperationException error) {
            IOException failure = new IOException(
                    "POSIX permissions are required for final dataset freezing", error);
            cleanupEmptyStagingDirectory(
                    parent, parentDirectory, stagingName, created, failure);
            throw failure;
        } catch (IOException | RuntimeException error) {
            cleanupEmptyStagingDirectory(
                    parent, parentDirectory, stagingName, created, error);
            throw error;
        }
    }

    private static void cleanupEmptyStagingDirectory(
            DirectoryIdentity parent,
            SecureDirectoryStream<Path> parentDirectory,
            Path stagingName,
            boolean created,
            Throwable failure) {
        if (!created) {
            return;
        }
        try {
            parent.verifyOpenDirectory(parentDirectory);
            parentDirectory.deleteDirectory(stagingName);
            parent.verifyOpenDirectory(parentDirectory);
            parent.verifyUnchanged();
        } catch (IOException | RuntimeException cleanupError) {
            failure.addSuppressed(cleanupError);
        }
    }

    private static void copySourceTree(Path source, Path staging) throws IOException {
        BasicFileAttributes sourceIdentity = Files.readAttributes(
                source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Map<Object, Path> sourceFileKeys = new HashMap<>();
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                rejectUnsafeSourceNode(source, directory, attributes);
                Path relative = source.relativize(directory);
                if (relative.getNameCount() == 1 && relative.toString().isEmpty()) {
                    return FileVisitResult.CONTINUE;
                }
                if (!relative.toString().isEmpty()) {
                    String portable = portableRelative(relative);
                    rejectReservedSourcePath(portable);
                    Path target = resolveInside(staging, portable, "source directory");
                    Files.createDirectory(target,
                            PosixFilePermissions.asFileAttribute(WRITABLE_DIRECTORY));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                rejectUnsafeSourceNode(source, file, attributes);
                if (!attributes.isRegularFile()) {
                    throw new IOException("private final dataset contains a non-regular file: " + file);
                }
                requireSingleLinkAndUniqueIdentity(
                        file, attributes, sourceFileKeys, "private final dataset file");
                String portable = portableRelative(source.relativize(file));
                rejectReservedSourcePath(portable);
                Path target = resolveInside(staging, portable, "source file");
                boolean executable = isExecutable(file);
                Files.copy(file, target, LinkOption.NOFOLLOW_LINKS);
                if (Files.isSymbolicLink(target)
                        || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(target);
                    throw new IOException("source changed into a symbolic link during copy: " + file);
                }
                Files.setPosixFilePermissions(target,
                        executable ? WRITABLE_EXECUTABLE : WRITABLE_FILE);
                BasicFileAttributes after = Files.readAttributes(
                        file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (after.isSymbolicLink() || !after.isRegularFile()
                        || after.size() != attributes.size()
                        || !after.lastModifiedTime().equals(attributes.lastModifiedTime())
                        || (attributes.fileKey() != null
                        && !attributes.fileKey().equals(after.fileKey()))) {
                    throw new IOException("source file changed while freezing: " + file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
                throw new IOException("failed to read private final dataset entry: " + file, error);
            }
        });
        BasicFileAttributes after = Files.readAttributes(
                source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isDirectory() || after.isSymbolicLink()
                || !sourceIdentity.lastModifiedTime().equals(after.lastModifiedTime())
                || (sourceIdentity.fileKey() != null
                && !sourceIdentity.fileKey().equals(after.fileKey()))) {
            throw new IOException("sourceRoot identity changed while freezing: " + source);
        }
        List<FinalDatasetFreezeManifest.FileEntry> sourceEntries = collectSourceEntries(source);
        List<FinalDatasetFreezeManifest.FileEntry> copiedEntries = collectSourceEntries(staging);
        if (!sourceEntries.equals(copiedEntries)) {
            throw new IOException("sourceRoot changed or copied bytes differ during freezing: " + source);
        }
    }

    private static void rejectUnsafeSourceNode(Path source,
                                               Path node,
                                               BasicFileAttributes attributes) throws IOException {
        if (attributes.isSymbolicLink() || Files.isSymbolicLink(node)) {
            throw new IOException("private final dataset contains a symbolic link: " + node);
        }
        requireNoAcl(node, "private final dataset entry");
        Path normalized = node.toAbsolutePath().normalize();
        if (!normalized.startsWith(source)) {
            throw new IOException("private final dataset entry escapes sourceRoot: " + node);
        }
    }

    private static void rejectReservedSourcePath(String portable) throws IOException {
        String top = portable.substring(0,
                portable.indexOf('/') < 0 ? portable.length() : portable.indexOf('/'));
        if (RESERVED_TOP_LEVEL.contains(top) || top.startsWith(".freeze-")) {
            throw new IOException("private final dataset uses a reserved path: " + portable);
        }
        for (String segment : portable.split("/")) {
            if (".git".equals(segment) || ".hg".equals(segment) || ".svn".equals(segment)) {
                throw new IOException("private final dataset contains VCS metadata: " + portable);
            }
        }
    }

    private static String portableRelative(Path relative) {
        if (relative == null || relative.isAbsolute() || relative.getNameCount() == 0) {
            throw new IllegalArgumentException("dataset entry must have a relative path");
        }
        List<String> segments = new ArrayList<>();
        for (Path segment : relative) {
            segments.add(segment.toString());
        }
        String portable = String.join("/", segments);
        FinalDatasetFreezeManifest.requireRelativePath(portable, "dataset entry");
        return portable;
    }

    private static boolean isExecutable(Path file) throws IOException {
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
                file, LinkOption.NOFOLLOW_LINKS);
        return permissions.contains(PosixFilePermission.OWNER_EXECUTE)
                || permissions.contains(PosixFilePermission.GROUP_EXECUTE)
                || permissions.contains(PosixFilePermission.OTHERS_EXECUTE);
    }

    private static SuiteBinding validateSuiteBinding(Path root,
                                                     String suitePath,
                                                     String validatorRoot) throws IOException {
        Path suiteFile = resolveInside(root, suitePath, "suitePath");
        Path validators = resolveInside(root, validatorRoot, "validatorRoot");
        if (Files.isSymbolicLink(suiteFile)
                || !Files.isRegularFile(suiteFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("suitePath must identify a regular file: " + suitePath);
        }
        if (Files.isSymbolicLink(validators)
                || !Files.isDirectory(validators, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("validatorRoot must identify a directory: " + validatorRoot);
        }
        SuiteDefinition suite = SuiteDefinition.load(suiteFile);
        for (CaseDefinition definition : suite.cases()) {
            Path fixture = suite.resolveFixture(definition);
            if (!Files.exists(fixture, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(fixture)
                    || (!Files.isRegularFile(fixture, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(fixture, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("case fixture is missing from frozen source: " + definition.id());
            }
            if (fixture.startsWith(validators) || validators.startsWith(fixture)) {
                throw new IOException("case fixture must not expose validatorRoot: "
                        + definition.id());
            }
            if (definition.status() != CaseDefinition.Status.ACTIVE) {
                continue;
            }
            if (definition.verifierType() != CaseDefinition.VerifierType.COMMAND) {
                throw new IOException("active final case must use a command verifier: "
                        + definition.id());
            }
            if (!hasBoundValidatorFile(suiteFile.getParent(), validators,
                    definition.verifierCommand())) {
                throw new IOException("active final case has no verifier file under validatorRoot: "
                        + definition.id());
            }
        }
        return new SuiteBinding(suite);
    }

    private static boolean hasBoundValidatorFile(Path suiteDirectory,
                                                 Path validatorRoot,
                                                 List<String> arguments) {
        // Final suites use one executable wrapper under validatorRoot as argv[0]. Interpreters
        // have option-specific grammars (`--eval`, preload hooks, rc files, classpaths, and so on),
        // so inferring an entry point from a later positional argument is not a safe binding.
        return !arguments.isEmpty()
                && isBoundValidatorPath(
                suiteDirectory, validatorRoot, arguments.get(0), true);
    }

    private static boolean isBoundValidatorPath(Path suiteDirectory,
                                                Path validatorRoot,
                                                String argument,
                                                boolean requireExecutable) {
        if (argument == null || argument.isBlank()) {
            return false;
        }
        try {
            Path candidate = suiteDirectory.resolve(Path.of(argument)).normalize();
            return candidate.startsWith(validatorRoot)
                    && Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(candidate)
                    && (!requireExecutable || Files.isExecutable(candidate));
        } catch (RuntimeException ignored) {
            // SuiteDefinition already rejects malformed path arguments. Interpreter names and
            // other non-path tokens simply do not bind a validator file here.
            return false;
        }
    }

    private FinalDatasetFreezeManifest buildManifest(
            List<FinalDatasetFreezeManifest.FileEntry> entries,
            SuiteBinding binding,
            String suitePath,
            String validatorRoot) {
        Map<String, FinalDatasetFreezeManifest.FileEntry> byPath = indexEntries(entries);
        FinalDatasetFreezeManifest.FileEntry suiteEntry = requireEntry(byPath, suitePath);
        FinalDatasetFreezeManifest.FileEntry canaryEntry = requireEntry(byPath, CANARY_FILE);
        String validatorPrefix = validatorRoot + "/";
        List<FinalDatasetFreezeManifest.FileEntry> validators = entries.stream()
                .filter(entry -> entry.path().startsWith(validatorPrefix))
                .toList();
        if (validators.isEmpty()) {
            throw new IllegalArgumentException("validatorRoot contains no regular files");
        }
        long totalBytes = entries.stream().mapToLong(
                FinalDatasetFreezeManifest.FileEntry::size).sum();
        return new FinalDatasetFreezeManifest(
                FinalDatasetFreezeManifest.CURRENT_VERSION,
                FinalDatasetFreezeManifest.FORMAT,
                clock.instant().toString(),
                suitePath,
                suiteEntry.sha256(),
                binding.suite().version(),
                binding.suite().cases().size(),
                binding.suite().activeCases().size(),
                validatorRoot,
                digestEntries(validators),
                CANARY_FILE,
                canaryEntry.sha256(),
                digestEntries(entries),
                totalBytes,
                entries);
    }

    private static List<FinalDatasetFreezeManifest.FileEntry> collectSourceEntries(Path root)
            throws IOException {
        List<FinalDatasetFreezeManifest.FileEntry> entries = new ArrayList<>();
        Map<Object, Path> fileKeys = new HashMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (attributes.isSymbolicLink() || Files.isSymbolicLink(directory)) {
                    throw new IOException("frozen dataset contains a symbolic-link directory: "
                            + directory);
                }
                requireNoAcl(directory, "frozen dataset directory");
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                if (attributes.isSymbolicLink() || Files.isSymbolicLink(file)) {
                    throw new IOException("frozen dataset contains a symbolic link: " + file);
                }
                if (!attributes.isRegularFile()) {
                    throw new IOException("frozen dataset contains a non-regular file: " + file);
                }
                requireNoAcl(file, "frozen dataset file");
                requireSingleLinkAndUniqueIdentity(
                        file, attributes, fileKeys, "frozen dataset file");
                String portable = portableRelative(root.relativize(file));
                if (MANIFEST_FILE.equals(portable) || COMPLETE_MARKER.equals(portable)) {
                    return FileVisitResult.CONTINUE;
                }
                entries.add(new FinalDatasetFreezeManifest.FileEntry(
                        portable,
                        sha256(file),
                        attributes.size(),
                        isExecutable(file) ? "0500" : "0400"));
                return FileVisitResult.CONTINUE;
            }
        });
        entries.sort(Comparator.comparing(FinalDatasetFreezeManifest.FileEntry::path));
        return List.copyOf(entries);
    }

    private static void requireSingleLinkAndUniqueIdentity(
            Path file,
            BasicFileAttributes attributes,
            Map<Object, Path> seenFileKeys,
            String label) throws IOException {
        final Object linkCount;
        try {
            linkCount = Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException | IllegalArgumentException error) {
            throw new IOException("cannot prove hard-link isolation for " + label + ": " + file,
                    error);
        }
        if (!(linkCount instanceof Number number) || number.longValue() != 1L) {
            throw new IOException(label + " must have exactly one hard link: " + file);
        }
        Object fileKey = attributes.fileKey();
        if (fileKey == null) {
            throw new IOException("cannot prove unique file identity for " + label + ": " + file);
        }
        Path previous = seenFileKeys.putIfAbsent(fileKey, file);
        if (previous != null) {
            throw new IOException(label + " shares a file identity with " + previous + ": " + file);
        }
    }

    private static Map<String, FinalDatasetFreezeManifest.FileEntry> indexEntries(
            List<FinalDatasetFreezeManifest.FileEntry> entries) {
        Map<String, FinalDatasetFreezeManifest.FileEntry> indexed = new HashMap<>();
        for (FinalDatasetFreezeManifest.FileEntry entry : entries) {
            if (indexed.put(entry.path(), entry) != null) {
                throw new IllegalArgumentException("duplicate dataset entry: " + entry.path());
            }
        }
        return indexed;
    }

    private static FinalDatasetFreezeManifest.FileEntry requireEntry(
            Map<String, FinalDatasetFreezeManifest.FileEntry> entries,
            String path) {
        FinalDatasetFreezeManifest.FileEntry entry = entries.get(path);
        if (entry == null) {
            throw new IllegalArgumentException("required final dataset file is missing: " + path);
        }
        return entry;
    }

    private static void writeJsonNew(Path target, Object value) throws IOException {
        byte[] bytes = (MAPPER.writeValueAsString(value) + System.lineSeparator())
                .getBytes(StandardCharsets.UTF_8);
        Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Files.setPosixFilePermissions(target, WRITABLE_FILE);
        forceFile(target);
    }

    private static void writeCompletionMarkerAtomically(Path root, CompletionMarker marker)
            throws IOException {
        Path target = root.resolve(COMPLETE_MARKER);
        Path temporary = root.resolve(COMPLETE_MARKER + "." + UUID.randomUUID() + ".tmp");
        try {
            writeJsonNew(temporary, marker);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException error) {
                throw new IOException("filesystem does not support atomic completion marker", error);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void forceFile(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void makeSnapshotReadOnly(Path root,
                                             FinalDatasetFreezeManifest manifest) throws IOException {
        for (FinalDatasetFreezeManifest.FileEntry entry : manifest.files()) {
            Path file = resolveInside(root, entry.path(), "manifest file");
            Files.setPosixFilePermissions(file,
                    "0500".equals(entry.mode()) ? READ_ONLY_EXECUTABLE : READ_ONLY_FILE);
        }
        Files.setPosixFilePermissions(root.resolve(MANIFEST_FILE), READ_ONLY_FILE);
        Files.setPosixFilePermissions(root.resolve(COMPLETE_MARKER), READ_ONLY_FILE);

        List<Path> directories;
        try (var stream = Files.walk(root)) {
            directories = stream.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                    .toList();
        }
        for (Path directory : directories) {
            Files.setPosixFilePermissions(directory, READ_ONLY_DIRECTORY);
        }
    }

    private static VerifiedSnapshot verifySnapshot(DirectoryIdentity rootIdentity,
                                                   DirectoryIdentity publicRepository,
                                                   boolean requireReadOnly) throws IOException {
        rootIdentity.verifyUnchanged();
        publicRepository.verifyUnchanged();
        Path root = rootIdentity.realPath();
        Path manifestFile = root.resolve(MANIFEST_FILE);
        Path markerFile = root.resolve(COMPLETE_MARKER);
        requireBoundedRegularFile(manifestFile, MAX_MANIFEST_BYTES, "freeze manifest");
        requireBoundedRegularFile(markerFile, MAX_MARKER_BYTES, "completion marker");
        FinalDatasetFreezeManifest manifest = readJson(
                manifestFile, FinalDatasetFreezeManifest.class);
        CompletionMarker marker = readJson(markerFile, CompletionMarker.class);
        String manifestSha256 = sha256(manifestFile);
        if (!marker.manifestSha256().equals(manifestSha256)) {
            throw new IOException("completion marker does not match freeze manifest digest");
        }
        if (!marker.contentTreeSha256().equals(manifest.contentTreeSha256())) {
            throw new IOException("completion marker does not match frozen content tree digest");
        }

        verifyManifestContent(rootIdentity, publicRepository, manifest);
        if (requireReadOnly) {
            verifyReadOnlyModes(root, manifest);
        }
        rootIdentity.verifyUnchanged();
        publicRepository.verifyUnchanged();
        return new VerifiedSnapshot(manifest, manifestSha256);
    }

    private static void verifyManifestContent(DirectoryIdentity rootIdentity,
                                              DirectoryIdentity publicRepository,
                                              FinalDatasetFreezeManifest manifest) throws IOException {
        rootIdentity.verifyUnchanged();
        publicRepository.verifyUnchanged();
        Path root = rootIdentity.realPath();
        List<FinalDatasetFreezeManifest.FileEntry> actualEntries = collectSourceEntries(root);
        if (!actualEntries.equals(manifest.files())) {
            throw new IOException("frozen file tree does not match freeze manifest");
        }
        verifyDirectorySet(root, manifest.files());
        if (!digestEntries(actualEntries).equals(manifest.contentTreeSha256())) {
            throw new IOException("frozen content tree digest does not match freeze manifest");
        }
        String validatorPrefix = manifest.validatorRoot() + "/";
        List<FinalDatasetFreezeManifest.FileEntry> validatorEntries = actualEntries.stream()
                .filter(entry -> entry.path().startsWith(validatorPrefix))
                .toList();
        if (validatorEntries.isEmpty()
                || !digestEntries(validatorEntries).equals(manifest.validatorTreeSha256())) {
            throw new IOException("frozen validator tree does not match freeze manifest");
        }
        SuiteBinding binding = validateSuiteBinding(
                root, manifest.suitePath(), manifest.validatorRoot());
        if (!binding.suite().version().equals(manifest.suiteVersion())
                || binding.suite().cases().size() != manifest.caseCount()
                || binding.suite().activeCases().size() != manifest.activeCaseCount()) {
            throw new IOException("frozen Suite metadata does not match freeze manifest");
        }

        if (!CANARY_FILE.equals(manifest.canaryPath())) {
            throw new IOException("freeze manifest does not use the required canary path");
        }
        String canaryValue = readAndValidateCanary(
                resolveInside(root, manifest.canaryPath(), "canaryPath"));
        requireCanaryOnlyInMarkerFile(root,
                resolveInside(root, manifest.canaryPath(), "canaryPath"), canaryValue);
        rejectCanaryInPublicRepository(publicRepository, canaryValue);
        rootIdentity.verifyUnchanged();
        publicRepository.verifyUnchanged();
    }

    private static void verifyDirectorySet(
            Path root,
            List<FinalDatasetFreezeManifest.FileEntry> entries) throws IOException {
        Set<String> expected = new HashSet<>();
        for (FinalDatasetFreezeManifest.FileEntry entry : entries) {
            Path parent = Path.of(entry.path()).getParent();
            while (parent != null) {
                expected.add(portableRelative(parent));
                parent = parent.getParent();
            }
        }
        Set<String> actual = new HashSet<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (attributes.isSymbolicLink() || Files.isSymbolicLink(directory)) {
                    throw new IOException("frozen dataset contains a symbolic-link directory: "
                            + directory);
                }
                Path relative = root.relativize(directory);
                if (!relative.toString().isEmpty()) {
                    actual.add(portableRelative(relative));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        if (!actual.equals(expected)) {
            throw new IOException("frozen directory tree contains an unmanifested or empty directory");
        }
    }

    private static void verifyReadOnlyModes(Path root,
                                            FinalDatasetFreezeManifest manifest) throws IOException {
        Map<String, String> expectedModes = new HashMap<>();
        for (FinalDatasetFreezeManifest.FileEntry entry : manifest.files()) {
            expectedModes.put(entry.path(), entry.mode());
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                requireExactPermissions(directory, READ_ONLY_DIRECTORY, "frozen directory");
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                String relative = portableRelative(root.relativize(file));
                Set<PosixFilePermission> expected;
                if (MANIFEST_FILE.equals(relative) || COMPLETE_MARKER.equals(relative)) {
                    expected = READ_ONLY_FILE;
                } else {
                    String mode = expectedModes.get(relative);
                    if (mode == null) {
                        throw new IOException("unmanifested file in frozen dataset: " + relative);
                    }
                    expected = "0500".equals(mode) ? READ_ONLY_EXECUTABLE : READ_ONLY_FILE;
                }
                requireExactPermissions(file, expected, "frozen file");
                return FileVisitResult.CONTINUE;
            }
        });
        requireOwnerOnlyDirectory(root, false, "frozenRoot");
    }

    private static void requireExactPermissions(Path path,
                                                Set<PosixFilePermission> expected,
                                                String label) throws IOException {
        Set<PosixFilePermission> actual = Files.getPosixFilePermissions(
                path, LinkOption.NOFOLLOW_LINKS);
        if (!actual.equals(expected)) {
            throw new IOException(label + " has mode " + PosixFilePermissions.toString(actual)
                    + " but expected " + PosixFilePermissions.toString(expected) + ": " + path);
        }
    }

    private static void requireBoundedRegularFile(Path file, long maximum, String label)
            throws IOException {
        if (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " is missing or not a regular file: " + file);
        }
        long size = Files.size(file);
        if (size <= 0 || size > maximum) {
            throw new IOException(label + " size is outside the accepted bound: " + size);
        }
    }

    private static <T> T readJson(Path file, Class<T> type) throws IOException {
        try (InputStream input = Files.newInputStream(
                file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            return MAPPER.readValue(input, type);
        } catch (IllegalArgumentException error) {
            throw new IOException("invalid " + type.getSimpleName() + ": " + file, error);
        }
    }

    private static String readAndValidateCanary(Path canaryFile) throws IOException {
        if (Files.isSymbolicLink(canaryFile)
                || !Files.isRegularFile(canaryFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("private final dataset canary is missing: " + canaryFile);
        }
        if (Files.size(canaryFile) > 256) {
            throw new IOException("private final dataset canary is too large");
        }
        final String raw;
        try (InputStream input = Files.newInputStream(
                canaryFile, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            raw = new String(input.readAllBytes(), StandardCharsets.US_ASCII);
        }
        String value = raw.endsWith("\n") ? raw.substring(0, raw.length() - 1) : raw;
        if (!CANARY.matcher(value).matches()) {
            throw new IOException("private final dataset canary has an invalid format");
        }
        return value;
    }

    private static void requireCanaryOnlyInMarkerFile(Path root,
                                                      Path canaryFile,
                                                      String canaryValue) throws IOException {
        byte[] needle = canaryValue.getBytes(StandardCharsets.US_ASCII);
        Path normalizedCanary = canaryFile.toAbsolutePath().normalize();
        final Path[] leak = {null};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                rejectCanaryInRelativePath(
                        root, directory, canaryValue, "private dataset path");
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                rejectCanaryInRelativePath(root, file, canaryValue, "private dataset path");
                if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                    return FileVisitResult.CONTINUE;
                }
                if (!file.toAbsolutePath().normalize().equals(normalizedCanary)
                        && containsBytes(file, needle)) {
                    leak[0] = file;
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        if (leak[0] != null) {
            throw new IOException("private canary leaked into dataset payload: "
                    + root.relativize(leak[0]));
        }
    }

    private static void rejectCanaryInPublicRepository(
            DirectoryIdentity repositoryIdentity,
            String canaryValue)
            throws IOException {
        repositoryIdentity.verifyUnchanged();
        Path repository = repositoryIdentity.realPath();
        byte[] needle = canaryValue.getBytes(StandardCharsets.US_ASCII);
        final Path[] contaminated = {null};
        Files.walkFileTree(repository, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                rejectCanaryInRelativePath(
                        repository, directory, canaryValue, "public repository path");
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                rejectCanaryInRelativePath(
                        repository, file, canaryValue, "public repository path");
                if (attributes.isSymbolicLink() || Files.isSymbolicLink(file)) {
                    throw new IOException(
                            "public repository contains a symbolic link; canary scan cannot prove isolation: "
                                    + repository.relativize(file));
                }
                if (attributes.isRegularFile() && containsBytes(file, needle)) {
                    contaminated[0] = file;
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
                throw new IOException("cannot complete public-repository canary scan: " + file, error);
            }
        });
        if (contaminated[0] != null) {
            throw new IOException("final dataset canary is already present in public repository: "
                    + repository.relativize(contaminated[0]));
        }
        repositoryIdentity.verifyUnchanged();
    }

    private static void rejectCanaryInRelativePath(Path root,
                                                   Path node,
                                                   String canaryValue,
                                                   String label) throws IOException {
        Path relative = root.relativize(node);
        if (relative.toString().isEmpty()) {
            return;
        }
        String portable = portableRelative(relative);
        if (portable.contains(canaryValue)) {
            throw new IOException(label + " contains the private canary: " + portable);
        }
    }

    private static boolean containsBytes(Path file, byte[] needle) throws IOException {
        int[] failure = buildFailureTable(needle);
        int matched = 0;
        try (InputStream input = Files.newInputStream(
                file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                for (int index = 0; index < read; index++) {
                    byte value = buffer[index];
                    while (matched > 0 && value != needle[matched]) {
                        matched = failure[matched - 1];
                    }
                    if (value == needle[matched]) {
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

    private static int[] buildFailureTable(byte[] needle) {
        int[] failure = new int[needle.length];
        int matched = 0;
        for (int index = 1; index < needle.length; index++) {
            while (matched > 0 && needle[index] != needle[matched]) {
                matched = failure[matched - 1];
            }
            if (needle[index] == needle[matched]) {
                failure[index] = ++matched;
            }
        }
        return failure;
    }

    private static Path resolveInside(Path root, String portable, String label) throws IOException {
        FinalDatasetFreezeManifest.requireRelativePath(portable, label);
        Path resolved = root.resolve(Path.of(portable)).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IOException(label + " escapes frozen root: " + portable);
        }
        return resolved;
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest = newSha256();
        try (InputStream input = Files.newInputStream(
                file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String digestEntries(List<FinalDatasetFreezeManifest.FileEntry> entries) {
        MessageDigest digest = newSha256();
        for (FinalDatasetFreezeManifest.FileEntry entry : entries) {
            updateDigest(digest, entry.path());
            digest.update((byte) 0);
            updateDigest(digest, entry.sha256());
            digest.update((byte) 0);
            updateDigest(digest, entry.mode());
            digest.update((byte) 0);
            updateDigest(digest, Long.toString(entry.size()));
            digest.update((byte) '\n');
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void deleteOwnedStagingTree(
            DirectoryIdentity parent,
            SecureDirectoryStream<Path> parentDirectory,
            DirectoryIdentity staging) throws IOException {
        Path name = staging.realPath().getFileName();
        if (name == null || name.isAbsolute() || name.getNameCount() != 1
                || !name.toString().contains(".freeze-")
                || !name.toString().endsWith(".tmp")) {
            throw new IOException(
                    "refusing to clean an unrecognized staging path: " + staging.realPath());
        }
        parent.verifyOpenDirectory(parentDirectory);
        staging.verifyChildDirectory(parentDirectory, name);
        PosixFileAttributeView stagingPermissions = parentDirectory.getFileAttributeView(
                name, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (stagingPermissions == null) {
            throw new IOException("cannot safely restore staging permissions before cleanup");
        }
        stagingPermissions.setPermissions(WRITABLE_DIRECTORY);
        try (SecureDirectoryStream<Path> stagingDirectory =
                     parentDirectory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
            staging.verifyOpenDirectory(stagingDirectory);
            deleteOwnedChildrenSecurely(
                    stagingDirectory, staging.realPath(), staging.realPath());
            staging.verifyOpenDirectory(stagingDirectory);
        }
        parent.verifyOpenDirectory(parentDirectory);
        staging.verifyChildDirectory(parentDirectory, name);
        parentDirectory.deleteDirectory(name);
        parent.verifyOpenDirectory(parentDirectory);
        parent.verifyUnchanged();
    }

    private static void deleteOwnedChildrenSecurely(
            SecureDirectoryStream<Path> directory,
            Path displayDirectory,
            Path stagingRoot) throws IOException {
        List<Path> names = new ArrayList<>();
        for (Path entry : directory) {
            Path name = entry.getFileName();
            if (name == null || name.isAbsolute() || name.getNameCount() != 1
                    || ".".equals(name.toString()) || "..".equals(name.toString())) {
                throw new IOException("unsafe staging cleanup entry: " + entry);
            }
            names.add(name);
        }
        for (Path name : names) {
            Path display = displayDirectory.resolve(name).normalize();
            if (!display.startsWith(stagingRoot) || display.equals(stagingRoot)) {
                throw new IOException("staging cleanup entry escapes captured root: " + display);
            }
            BasicFileAttributeView basicView = directory.getFileAttributeView(
                    name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (basicView == null) {
                throw new IOException("cannot inspect staging cleanup entry: " + display);
            }
            BasicFileAttributes attributes = basicView.readAttributes();
            if (attributes.isSymbolicLink()) {
                throw new IOException("refusing to delete staging symbolic link: " + display);
            }
            PosixFileAttributeView permissions = directory.getFileAttributeView(
                    name, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (permissions == null) {
                throw new IOException("cannot safely change staging cleanup permissions: " + display);
            }
            if (attributes.isDirectory()) {
                permissions.setPermissions(WRITABLE_DIRECTORY);
                try (SecureDirectoryStream<Path> child =
                             directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
                    deleteOwnedChildrenSecurely(child, display, stagingRoot);
                }
                directory.deleteDirectory(name);
            } else if (attributes.isRegularFile()) {
                permissions.setPermissions(WRITABLE_FILE);
                directory.deleteFile(name);
            } else {
                throw new IOException("refusing to delete non-regular staging entry: " + display);
            }
        }
    }

    public record FreezeRequest(Path sourceRoot,
                                Path frozenRoot,
                                Path publicRepositoryRoot,
                                String suitePath,
                                String validatorRoot) {
    }

    public record FreezeResult(Path frozenRoot,
                               FinalDatasetFreezeManifest manifest,
                               String manifestSha256) {
        public FreezeResult {
            if (frozenRoot == null || manifest == null || manifestSha256 == null) {
                throw new IllegalArgumentException("freeze result fields must not be null");
            }
            frozenRoot = frozenRoot.toAbsolutePath().normalize();
            FinalDatasetFreezeManifest.requireSha256(manifestSha256, "manifestSha256");
        }
    }

    private record DirectoryIdentity(Path declaredPath,
                                     Path realPath,
                                     Object fileKey,
                                     String label) {
        private static DirectoryIdentity capture(Path raw, String label) throws IOException {
            Path declared = raw.toAbsolutePath().normalize();
            Path real = requireExistingPlainDirectory(declared, label);
            BasicFileAttributes attributes = Files.readAttributes(
                    real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.fileKey() == null) {
                throw new IOException("cannot capture stable directory identity for "
                        + label + ": " + real);
            }
            return new DirectoryIdentity(declared, real, attributes.fileKey(), label);
        }

        private void verifyUnchanged() throws IOException {
            Path current = requireExistingPlainDirectory(declaredPath, label);
            if (!current.equals(realPath)) {
                throw new IOException(label + " changed real location: " + declaredPath);
            }
            verifyAttributes(Files.readAttributes(
                    realPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
        }

        private void verifyOpenDirectory(SecureDirectoryStream<Path> directory)
                throws IOException {
            BasicFileAttributeView view = directory.getFileAttributeView(
                    BasicFileAttributeView.class);
            if (view == null) {
                throw new IOException("cannot inspect open " + label);
            }
            verifyAttributes(view.readAttributes());
        }

        private void verifyChildDirectory(SecureDirectoryStream<Path> parent,
                                          Path name) throws IOException {
            BasicFileAttributeView view = parent.getFileAttributeView(
                    name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) {
                throw new IOException("cannot inspect " + label + " through captured parent");
            }
            verifyAttributes(view.readAttributes());
        }

        private void verifyAttributes(BasicFileAttributes attributes) throws IOException {
            if (attributes == null || attributes.isSymbolicLink() || !attributes.isDirectory()) {
                throw new IOException(label + " is no longer the captured directory: "
                        + declaredPath);
            }
            if (!fileKey.equals(attributes.fileKey())) {
                throw new IOException(label + " identity changed: " + declaredPath);
            }
        }

        private SecureDirectoryStream<Path> openSecureDirectory() throws IOException {
            verifyUnchanged();
            DirectoryStream<Path> opened = Files.newDirectoryStream(realPath);
            if (!(opened instanceof SecureDirectoryStream<?>)) {
                opened.close();
                throw new IOException("formal final freezing requires SecureDirectoryStream for "
                        + label + ": " + realPath);
            }
            @SuppressWarnings("unchecked")
            SecureDirectoryStream<Path> secure = (SecureDirectoryStream<Path>) opened;
            try {
                verifyOpenDirectory(secure);
                verifyUnchanged();
                return secure;
            } catch (IOException | RuntimeException error) {
                secure.close();
                throw error;
            }
        }
    }

    private record FreezeContext(DirectoryIdentity source,
                                 Path destination,
                                 DirectoryIdentity destinationParent,
                                 DirectoryIdentity publicRepository,
                                 String suitePath,
                                 String validatorRoot) {
    }

    private record SuiteBinding(SuiteDefinition suite) {
    }

    private record VerifiedSnapshot(FinalDatasetFreezeManifest manifest, String manifestSha256) {
    }

    private record CompletionMarker(
            @JsonProperty(value = "markerVersion", required = true) int markerVersion,
            @JsonProperty(value = "format", required = true) String format,
            @JsonProperty(value = "manifestSha256", required = true) String manifestSha256,
            @JsonProperty(value = "contentTreeSha256", required = true) String contentTreeSha256) {
        private CompletionMarker {
            if (markerVersion != MARKER_VERSION) {
                throw new IllegalArgumentException("unsupported completion marker version");
            }
            if (!MARKER_FORMAT.equals(format)) {
                throw new IllegalArgumentException("unsupported completion marker format");
            }
            FinalDatasetFreezeManifest.requireSha256(manifestSha256, "manifestSha256");
            FinalDatasetFreezeManifest.requireSha256(contentTreeSha256, "contentTreeSha256");
        }
    }
}
