package com.paicli.eval.benchmark.finalset;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinalDatasetFreezerTest {
    private static final String CANARY_VALUE =
            "PAICLI-FINAL-CANARY-v1:" + "a".repeat(64);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-31T09:30:00Z"), ZoneOffset.UTC);
    private static final Set<PosixFilePermission> MODE_0700 = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> MODE_0600 = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> MODE_0500 = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> MODE_0400 = Set.of(
            PosixFilePermission.OWNER_READ);

    @Test
    void freezesVerifiesAndNeverOverwritesExistingSnapshot(@TempDir Path tempDir) throws Exception {
        Layout layout = createLayout(tempDir);
        assumeSecureDirectoryStream(layout);
        FinalDatasetFreezer freezer = new FinalDatasetFreezer(CLOCK);
        try {
            FinalDatasetFreezer.FreezeResult created = freezer.freeze(layout.request());

            assertEquals(layout.freeze(), created.frozenRoot());
            assertEquals("2026-08-31T09:30:00Z", created.manifest().createdAtUtc());
            assertEquals(1, created.manifest().caseCount());
            assertEquals(1, created.manifest().activeCaseCount());
            assertEquals(created, freezer.verify(layout.freeze(), layout.publicRepository()));

            assertEquals(MODE_0500, Files.getPosixFilePermissions(layout.freeze()));
            assertEquals(MODE_0400, Files.getPosixFilePermissions(
                    layout.freeze().resolve(FinalDatasetFreezer.MANIFEST_FILE)));
            assertEquals(MODE_0400, Files.getPosixFilePermissions(
                    layout.freeze().resolve(FinalDatasetFreezer.COMPLETE_MARKER)));
            assertEquals(MODE_0500, Files.getPosixFilePermissions(
                    layout.freeze().resolve("validators/final/check.sh")));
            assertEquals(MODE_0400, Files.getPosixFilePermissions(
                    layout.freeze().resolve("suite.json")));

            FinalDatasetFreezeManifest.FileEntry validator = created.manifest().files().stream()
                    .filter(entry -> entry.path().equals("validators/final/check.sh"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("0500", validator.mode());
            assertEquals(Files.size(layout.freeze().resolve(validator.path())), validator.size());

            String manifestJson = Files.readString(
                    layout.freeze().resolve(FinalDatasetFreezer.MANIFEST_FILE));
            String markerJson = Files.readString(
                    layout.freeze().resolve(FinalDatasetFreezer.COMPLETE_MARKER));
            assertFalse(manifestJson.contains(layout.source().toString()));
            assertFalse(manifestJson.contains(layout.freeze().toString()));
            assertFalse(manifestJson.contains(CANARY_VALUE));
            assertTrue(markerJson.contains(created.manifestSha256()));
            assertTrue(markerJson.contains(created.manifest().contentTreeSha256()));

            assertThrows(FileAlreadyExistsException.class,
                    () -> freezer.freeze(layout.request()));
            try (var children = Files.list(layout.freeze().getParent())) {
                assertEquals(List.of(layout.freeze()), children.sorted().toList());
            }
        } finally {
            makeTreeWritable(layout.freeze());
        }
    }

    @Test
    void rejectsSymbolicLinksAnywhereInPrivateSource(@TempDir Path tempDir) throws Exception {
        Layout layout = createLayout(tempDir);
        assumeSecureDirectoryStream(layout);
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "outside");
        try {
            Files.createSymbolicLink(layout.source().resolve("fixtures/final/case-a/link"), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException error) {
            Assumptions.abort("symbolic links unavailable on this host");
        }

        IOException error = assertThrows(IOException.class,
                () -> new FinalDatasetFreezer(CLOCK).freeze(layout.request()));

        assertTrue(error.getMessage().contains("symbolic link"));
        assertFalse(Files.exists(layout.freeze(), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void rejectsUnsafeSuiteAndValidatorPathsBeforeCopy(@TempDir Path tempDir) throws Exception {
        Layout layout = createLayout(tempDir);
        FinalDatasetFreezer freezer = new FinalDatasetFreezer(CLOCK);

        assertThrows(IllegalArgumentException.class, () -> freezer.freeze(
                request(layout, "../suite.json", "validators/final")));
        assertThrows(IllegalArgumentException.class, () -> freezer.freeze(
                request(layout, "/private/suite.json", "validators/final")));
        assertThrows(IllegalArgumentException.class, () -> freezer.freeze(
                request(layout, "suite.json", "validators/../final")));
        assertFalse(Files.exists(layout.freeze(), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void rejectsGitAncestorsForSourceAndDestination(@TempDir Path tempDir) throws Exception {
        Layout layout = createLayout(tempDir);
        Files.setPosixFilePermissions(layout.publicRepository(), MODE_0700);
        Path sourceInRepository = Files.createDirectories(
                layout.publicRepository().resolve("private-source"));
        Files.setPosixFilePermissions(sourceInRepository, MODE_0700);
        Path destinationParent = Files.createDirectories(
                layout.publicRepository().resolve("private-freezes"));
        Files.setPosixFilePermissions(destinationParent, MODE_0700);
        FinalDatasetFreezer freezer = new FinalDatasetFreezer(CLOCK);

        IOException sourceError = assertThrows(IOException.class, () -> freezer.freeze(
                new FinalDatasetFreezer.FreezeRequest(
                        sourceInRepository,
                        layout.freeze(),
                        layout.publicRepository(),
                        "suite.json",
                        "validators/final")));
        IOException destinationError = assertThrows(IOException.class, () -> freezer.freeze(
                new FinalDatasetFreezer.FreezeRequest(
                        layout.source(),
                        destinationParent.resolve("freeze-v1"),
                        layout.publicRepository(),
                        "suite.json",
                        "validators/final")));

        assertTrue(sourceError.getMessage().contains("Git worktree"));
        assertTrue(destinationError.getMessage().contains("Git worktree"));
    }

    @Test
    void rejectsCanaryAlreadyPresentInPublicRepository(@TempDir Path tempDir) throws Exception {
        Layout layout = createLayout(tempDir);
        Files.writeString(layout.publicRepository().resolve("accidental-leak.txt"), CANARY_VALUE);

        IOException error = assertThrows(IOException.class,
                () -> new FinalDatasetFreezer(CLOCK).freeze(layout.request()));

        assertTrue(error.getMessage().contains("already present in public repository"));
        assertFalse(Files.exists(layout.freeze(), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void rejectsCanaryCopiedIntoFixturePayload(@TempDir Path tempDir) throws Exception {
        Layout layout = createLayout(tempDir);
        Files.writeString(layout.source().resolve("fixtures/final/case-a/leak.txt"), CANARY_VALUE);

        IOException error = assertThrows(IOException.class,
                () -> new FinalDatasetFreezer(CLOCK).freeze(layout.request()));

        assertTrue(error.getMessage().contains("canary leaked into dataset payload"));
        assertFalse(Files.exists(layout.freeze(), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void rejectsActiveCaseWhoseVerifierIsOutsideBoundTree(@TempDir Path tempDir) throws Exception {
        Layout layout = createLayout(tempDir);
        assumeSecureDirectoryStream(layout);
        Path scripts = Files.createDirectories(layout.source().resolve("scripts"));
        Files.writeString(scripts.resolve("check.sh"), "#!/bin/sh\nexit 0\n");
        Files.writeString(layout.source().resolve("suite.json"),
                suiteJson("scripts/check.sh"));

        IOException error = assertThrows(IOException.class,
                () -> new FinalDatasetFreezer(CLOCK).freeze(layout.request()));

        assertTrue(error.getMessage().contains("no verifier file under validatorRoot"));
        assertFalse(Files.exists(layout.freeze(), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void rejectsFixtureThatWouldExposeHiddenValidatorTree(@TempDir Path tempDir) throws Exception {
        Layout layout = createLayout(tempDir);
        assumeSecureDirectoryStream(layout);
        String unsafeSuite = suiteJson("validators/final/check.sh")
                .replace("\"fixturePath\":\"fixtures/final/case-a\"",
                        "\"fixturePath\":\"validators\"");
        Files.writeString(layout.source().resolve("suite.json"), unsafeSuite);

        IOException error = assertThrows(IOException.class,
                () -> new FinalDatasetFreezer(CLOCK).freeze(layout.request()));

        assertTrue(error.getMessage().contains("must not expose validatorRoot"));
    }

    @Test
    void rejectsDummyValidatorArgumentThatDoesNotBindExecutedProgram(@TempDir Path tempDir)
            throws Exception {
        Layout layout = createLayout(tempDir);
        assumeSecureDirectoryStream(layout);
        String unsafeSuite = suiteJson("validators/final/check.sh")
                .replace("[\"validators/final/check.sh\",\"{workspace}\"]",
                        "[\"custom-runner\",\"fixtures/final/case-a/README.md\","
                                + "\"validators/final/check.sh\"]");
        Files.writeString(layout.source().resolve("suite.json"), unsafeSuite);

        IOException error = assertThrows(IOException.class,
                () -> new FinalDatasetFreezer(CLOCK).freeze(layout.request()));

        assertTrue(error.getMessage().contains("no verifier file under validatorRoot"));
    }

    @Test
    void rejectsInterpreterOptionsThatLaunderValidatorBinding(@TempDir Path tempDir)
            throws Exception {
        Layout layout = createLayout(tempDir);
        assumeSecureDirectoryStream(layout);
        String unsafeSuite = suiteJson("validators/final/check.sh")
                .replace("[\"validators/final/check.sh\",\"{workspace}\"]",
                        "[\"node\",\"--require\",\"validators/final/check.sh\","
                                + "\"--eval\",\"process.exit(0)\"]");
        Files.writeString(layout.source().resolve("suite.json"), unsafeSuite);

        IOException error = assertThrows(IOException.class,
                () -> new FinalDatasetFreezer(CLOCK).freeze(layout.request()));

        assertTrue(error.getMessage().contains("no verifier file under validatorRoot"));
    }

    @Test
    void rejectsCanaryInPrivateRelativePath(@TempDir Path tempDir) throws Exception {
        Layout layout = createLayout(tempDir);
        Files.writeString(
                layout.source().resolve("fixtures/final/case-a").resolve(CANARY_VALUE),
                "path leak\n");

        IOException error = assertThrows(IOException.class,
                () -> new FinalDatasetFreezer(CLOCK).freeze(layout.request()));

        assertTrue(error.getMessage().contains("private dataset path contains the private canary"));
    }

    @Test
    void rejectsAnyPublicRepositorySymbolicLink(@TempDir Path tempDir) throws Exception {
        Layout layout = createLayout(tempDir);
        try {
            Files.createSymbolicLink(
                    layout.publicRepository().resolve("private-canary-link"),
                    layout.source().resolve(FinalDatasetFreezer.CANARY_FILE));
        } catch (UnsupportedOperationException | IOException | SecurityException error) {
            Assumptions.abort("symbolic links unavailable on this host");
        }

        IOException error = assertThrows(IOException.class,
                () -> new FinalDatasetFreezer(CLOCK).freeze(layout.request()));

        assertTrue(error.getMessage().contains("public repository contains a symbolic link"));
    }

    @Test
    void rejectsHardLinkedSourceFiles(@TempDir Path tempDir) throws Exception {
        Layout layout = createLayout(tempDir);
        assumeSecureDirectoryStream(layout);
        Path validator = layout.source().resolve("validators/final/check.sh");
        try {
            Files.createLink(
                    layout.source().resolve("fixtures/final/case-a/validator-copy.sh"),
                    validator);
        } catch (UnsupportedOperationException | IOException | SecurityException error) {
            Assumptions.abort("hard links unavailable on this host");
        }

        IOException error = assertThrows(IOException.class,
                () -> new FinalDatasetFreezer(CLOCK).freeze(layout.request()));

        assertTrue(error.getMessage().contains("must have exactly one hard link"));
    }

    @Test
    void rejectsExtendedAclWhenProviderExposesAclView(@TempDir Path tempDir) throws Exception {
        Layout layout = createLayout(tempDir);
        AclFileAttributeView acl = Files.getFileAttributeView(
                layout.source(), AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        Assumptions.assumeTrue(acl != null, "ACL view unavailable on this host");
        AclEntry entry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(Files.getOwner(layout.source(), LinkOption.NOFOLLOW_LINKS))
                .setPermissions(AclEntryPermission.READ_DATA)
                .build();
        acl.setAcl(List.of(entry));

        IOException error = assertThrows(IOException.class,
                () -> new FinalDatasetFreezer(CLOCK).freeze(layout.request()));

        assertTrue(error.getMessage().contains("must not have an extended ACL"));
    }

    @Test
    void rejectsReservedDestinationBasenameCaseInsensitively(@TempDir Path tempDir)
            throws Exception {
        Layout layout = createLayout(tempDir);
        FinalDatasetFreezer.FreezeRequest request = new FinalDatasetFreezer.FreezeRequest(
                layout.source(), layout.freeze().getParent().resolve(".GiT"),
                layout.publicRepository(), "suite.json", "validators/final");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new FinalDatasetFreezer(CLOCK).freeze(request));

        assertTrue(error.getMessage().contains("reserved destination name"));
    }

    @Test
    void formalFreezeFailsClosedWithoutSecureDirectoryStream(@TempDir Path tempDir)
            throws Exception {
        Layout layout = createLayout(tempDir);
        Assumptions.assumeFalse(supportsSecureDirectoryStream(layout.freeze().getParent()),
                "host exposes SecureDirectoryStream");

        IOException error = assertThrows(IOException.class,
                () -> new FinalDatasetFreezer(CLOCK).freeze(layout.request()));

        assertTrue(error.getMessage().contains("requires SecureDirectoryStream"));
    }

    @Test
    void verifyDetectsByteAndModeTampering(@TempDir Path tempDir) throws Exception {
        Layout layout = createLayout(tempDir);
        assumeSecureDirectoryStream(layout);
        FinalDatasetFreezer freezer = new FinalDatasetFreezer(CLOCK);
        try {
            freezer.freeze(layout.request());
            Path fixture = layout.freeze().resolve("fixtures/final/case-a/README.md");
            Files.setPosixFilePermissions(layout.freeze(), MODE_0700);
            Files.setPosixFilePermissions(layout.freeze().resolve("fixtures"), MODE_0700);
            Files.setPosixFilePermissions(layout.freeze().resolve("fixtures/final"), MODE_0700);
            Files.setPosixFilePermissions(layout.freeze().resolve("fixtures/final/case-a"), MODE_0700);
            Files.setPosixFilePermissions(fixture, MODE_0600);
            Files.writeString(fixture, "tampered\n");

            IOException error = assertThrows(IOException.class,
                    () -> freezer.verify(layout.freeze(), layout.publicRepository()));

            assertTrue(error.getMessage().contains("does not match freeze manifest"));
        } finally {
            makeTreeWritable(layout.freeze());
        }
    }

    @Test
    void verifyDetectsPermissionOnlyTampering(@TempDir Path tempDir) throws Exception {
        Layout layout = createLayout(tempDir);
        assumeSecureDirectoryStream(layout);
        FinalDatasetFreezer freezer = new FinalDatasetFreezer(CLOCK);
        try {
            freezer.freeze(layout.request());
            Path fixture = layout.freeze().resolve("fixtures/final/case-a/README.md");
            Files.setPosixFilePermissions(fixture, MODE_0600);

            IOException error = assertThrows(IOException.class,
                    () -> freezer.verify(layout.freeze(), layout.publicRepository()));

            assertTrue(error.getMessage().contains("expected r--------"));
        } finally {
            makeTreeWritable(layout.freeze());
        }
    }

    @Test
    void verifyDetectsUnmanifestedEmptyDirectory(@TempDir Path tempDir) throws Exception {
        Layout layout = createLayout(tempDir);
        assumeSecureDirectoryStream(layout);
        FinalDatasetFreezer freezer = new FinalDatasetFreezer(CLOCK);
        try {
            freezer.freeze(layout.request());
            Files.setPosixFilePermissions(layout.freeze(), MODE_0700);
            Path extra = Files.createDirectory(layout.freeze().resolve("empty-extra"));
            Files.setPosixFilePermissions(extra, MODE_0500);
            Files.setPosixFilePermissions(layout.freeze(), MODE_0500);

            IOException error = assertThrows(IOException.class,
                    () -> freezer.verify(layout.freeze(), layout.publicRepository()));

            assertTrue(error.getMessage().contains("frozen directory tree"));
        } finally {
            makeTreeWritable(layout.freeze());
        }
    }

    @Test
    void manifestRejectsUnsortedAndEscapingEntries() {
        FinalDatasetFreezeManifest.FileEntry suite = entry("suite.json", "1".repeat(64), 1);
        FinalDatasetFreezeManifest.FileEntry canary = entry(
                FinalDatasetFreezer.CANARY_FILE, "2".repeat(64), 1);

        assertThrows(IllegalArgumentException.class,
                () -> new FinalDatasetFreezeManifest.FileEntry(
                        "../suite.json", "3".repeat(64), 1, "0400"));
        assertThrows(IllegalArgumentException.class,
                () -> manifest(List.of(suite, canary)));
    }

    private static void assumeSecureDirectoryStream(Layout layout) throws IOException {
        Assumptions.assumeTrue(supportsSecureDirectoryStream(layout.freeze().getParent()),
                "formal freeze requires SecureDirectoryStream");
    }

    private static boolean supportsSecureDirectoryStream(Path directory) throws IOException {
        try (DirectoryStream<Path> opened = Files.newDirectoryStream(directory)) {
            return opened instanceof SecureDirectoryStream<?>;
        }
    }

    private static Layout createLayout(Path tempDir) throws Exception {
        // macOS exposes JUnit's temporary root through /var -> /private/var. Product paths
        // intentionally reject every symlink segment, so construct requests from the real root.
        tempDir = tempDir.toRealPath();
        Assumptions.assumeTrue(Files.getFileAttributeView(
                tempDir, PosixFileAttributeView.class) != null, "POSIX filesystem required");
        Files.setPosixFilePermissions(tempDir, MODE_0700);
        Path publicRepository = Files.createDirectory(tempDir.resolve("public-paicli"));
        Files.createDirectory(publicRepository.resolve(".git"));
        Path source = Files.createDirectory(tempDir.resolve("private-source"));
        Files.setPosixFilePermissions(source, MODE_0700);
        Path fixture = Files.createDirectories(source.resolve("fixtures/final/case-a"));
        Files.writeString(fixture.resolve("README.md"), "private fixture\n");
        Path validators = Files.createDirectories(source.resolve("validators/final"));
        Path validator = Files.writeString(validators.resolve("check.sh"),
                "#!/bin/sh\nset -eu\ntest -f \"$1/README.md\"\n");
        Files.setPosixFilePermissions(validator, MODE_0700);
        Files.writeString(source.resolve("suite.json"),
                suiteJson("validators/final/check.sh"));
        Files.writeString(source.resolve(FinalDatasetFreezer.CANARY_FILE),
                CANARY_VALUE + "\n");
        Path freezeParent = Files.createDirectory(tempDir.resolve("private-freezes"));
        Files.setPosixFilePermissions(freezeParent, MODE_0700);
        Path freeze = freezeParent.resolve("freeze-v1");
        return new Layout(source, freeze, publicRepository);
    }

    private static String suiteJson(String verifier) {
        return """
                {
                  "version":"final-1.0",
                  "name":"PaiCLI private final suite",
                  "cases":[{
                    "id":"final-case-a",
                    "title":"Private final case A",
                    "category":"coding",
                    "level":"L1",
                    "weight":100,
                    "mode":"react",
                    "fixturePath":"fixtures/final/case-a",
                    "prompt":"Complete the private task",
                    "verifierType":"command",
                    "verifierCommand":["%s","{workspace}"],
                    "status":"active"
                  }]
                }
                """.formatted(verifier);
    }

    private static FinalDatasetFreezer.FreezeRequest request(Layout layout,
                                                             String suite,
                                                             String validators) {
        return new FinalDatasetFreezer.FreezeRequest(
                layout.source(), layout.freeze(), layout.publicRepository(), suite, validators);
    }

    private static FinalDatasetFreezeManifest.FileEntry entry(String path, String hash, long size) {
        return new FinalDatasetFreezeManifest.FileEntry(path, hash, size, "0400");
    }

    private static FinalDatasetFreezeManifest manifest(
            List<FinalDatasetFreezeManifest.FileEntry> entries) {
        return new FinalDatasetFreezeManifest(
                1,
                FinalDatasetFreezeManifest.FORMAT,
                "2026-08-31T09:30:00Z",
                "suite.json",
                "1".repeat(64),
                "1",
                1,
                1,
                "validators/final",
                "3".repeat(64),
                FinalDatasetFreezer.CANARY_FILE,
                "2".repeat(64),
                "4".repeat(64),
                2,
                entries);
    }

    private static void makeTreeWritable(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                Files.setPosixFilePermissions(directory, MODE_0700);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Files.setPosixFilePermissions(file, MODE_0600);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private record Layout(Path source, Path freeze, Path publicRepository) {
        private FinalDatasetFreezer.FreezeRequest request() {
            return FinalDatasetFreezerTest.request(this, "suite.json", "validators/final");
        }
    }
}
