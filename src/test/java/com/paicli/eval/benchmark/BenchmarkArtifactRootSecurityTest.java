package com.paicli.eval.benchmark;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkArtifactRootSecurityTest {
    @Test
    void rejectsHomeItselfWithoutChangingPermissions() throws Exception {
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        var before = Files.getPosixFilePermissions(home);

        assertThrows(IOException.class, () -> BenchmarkArtifactStore.create(home, "run-home"));

        assertEquals(before, Files.getPosixFilePermissions(home));
    }

    @Test
    void rejectsSymlinkOutputRoot(@TempDir Path tempDir) throws Exception {
        Path target = Files.createDirectory(tempDir.resolve("target"));
        Path link = tempDir.resolve("output-link");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.abort("symbolic links unavailable on this test host");
        }

        assertThrows(IOException.class, () -> BenchmarkArtifactStore.create(link, "run-link"));
        assertFalse(Files.exists(target.resolve("run-link")));
    }

    @Test
    void refusesPermissiveExistingRootAndDoesNotChmodIt(@TempDir Path tempDir) throws Exception {
        Path output = Files.createDirectory(tempDir.resolve("permissive"));
        try {
            Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException e) {
            Assumptions.abort("POSIX permissions unavailable on this test host");
        }

        assertThrows(IOException.class,
                () -> BenchmarkArtifactStore.create(output, "run-permissive"));

        assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"),
                Files.getPosixFilePermissions(output));
    }

    @Test
    void createsOnlyDedicatedLastLevelAsOwnerOnly(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("private-results");

        BenchmarkArtifactStore store = BenchmarkArtifactStore.create(output, "run-private");

        assertEquals(PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(output));
        assertEquals(output.resolve("run-private"), store.runDirectory());
    }

    @Test
    void rejectsOutputInsideCurrentProjectBeforeCreatingIt() {
        Path output = Path.of(System.getProperty("user.dir"))
                .resolve(".benchmark-artifact-root-security-test")
                .toAbsolutePath()
                .normalize();

        IOException error = assertThrows(IOException.class,
                () -> BenchmarkArtifactStore.create(output, "run-project"));

        assertTrue(error.getMessage().contains("outside the current project"));
        assertFalse(Files.exists(output));
    }

    @Test
    void resolvesSafeAncestorSymlinkToItsRealDestination(@TempDir Path tempDir) throws Exception {
        Path target = Files.createDirectory(tempDir.resolve("target"));
        Path parent = Files.createDirectory(target.resolve("private-parent"));
        Path link = tempDir.resolve("ancestor-link");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.abort("symbolic links unavailable on this test host");
        }
        Path output = link.resolve(parent.getFileName()).resolve("results");

        BenchmarkArtifactStore store = BenchmarkArtifactStore.create(output, "run-real-ancestor");

        assertEquals(target.resolve("private-parent/results/run-real-ancestor").toRealPath(),
                store.runDirectory().toRealPath());
    }

    @Test
    void purgeRefusesEpisodeRootReplacedBySymlinkWithoutTouchingTarget(@TempDir Path tempDir)
            throws Exception {
        BenchmarkArtifactStore store = BenchmarkArtifactStore.create(tempDir, "run-episode-link");
        BenchmarkArtifactStore.EpisodeArtifacts episode =
                store.episode("case-link", "model-link", 1);
        Path episodeDirectory = episode.directory();
        episode.writeAnswer("private episode content");
        Path displacedEpisode = episodeDirectory.resolveSibling("repeat-displaced");
        Files.move(episodeDirectory, displacedEpisode);

        Path outside = Files.createDirectory(tempDir.resolve("outside-episode-link"));
        Path sentinel = Files.writeString(outside.resolve("keep.txt"), "must survive");
        try {
            Files.createSymbolicLink(episodeDirectory, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.abort("symbolic links unavailable on this test host");
        }

        assertThrows(IOException.class, episode::purgeContentsForSecurityFailure);

        assertTrue(Files.exists(sentinel));
        assertTrue(Files.exists(displacedEpisode.resolve("answer.md")));
    }

    @Test
    void purgeRefusesRunRootReplacedBySymlinkWithoutTouchingTarget(@TempDir Path tempDir)
            throws Exception {
        BenchmarkArtifactStore store = BenchmarkArtifactStore.create(tempDir, "run-root-link");
        BenchmarkArtifactStore.EpisodeArtifacts episode =
                store.episode("case-root-link", "model-root-link", 1);
        episode.writeAnswer("private episode content");
        Path runDirectory = store.runDirectory().toRealPath();
        Path relativeEpisode = runDirectory.relativize(episode.directory());
        Path displacedRun = tempDir.resolve("run-root-displaced");
        Files.move(runDirectory, displacedRun);

        Path outside = Files.createDirectory(tempDir.resolve("outside-run-link"));
        Path sentinel = Files.writeString(outside.resolve("keep.txt"), "must survive");
        try {
            Files.createSymbolicLink(runDirectory, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.abort("symbolic links unavailable on this test host");
        }

        assertThrows(IOException.class, episode::purgeContentsForSecurityFailure);

        assertTrue(Files.exists(sentinel));
        assertTrue(Files.exists(displacedRun.resolve(relativeEpisode).resolve("answer.md")));
    }

    @Test
    void purgeRefusesSamePathEpisodeReplacementWhenFileKeyIsAvailable(@TempDir Path tempDir)
            throws Exception {
        BenchmarkArtifactStore store = BenchmarkArtifactStore.create(tempDir, "run-file-key");
        BenchmarkArtifactStore.EpisodeArtifacts episode =
                store.episode("case-file-key", "model-file-key", 1);
        Path episodeDirectory = episode.directory();
        episode.writeAnswer("private episode content");
        Object originalFileKey = Files.readAttributes(
                episodeDirectory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        Assumptions.assumeTrue(originalFileKey != null, "fileKey unavailable on this test host");

        Path displacedEpisode = episodeDirectory.resolveSibling("repeat-file-key-displaced");
        Files.move(episodeDirectory, displacedEpisode);
        Files.createDirectory(episodeDirectory);
        Path replacementSentinel = Files.writeString(
                episodeDirectory.resolve("keep.txt"), "replacement must survive");

        IOException error = assertThrows(
                IOException.class, episode::purgeContentsForSecurityFailure);

        assertTrue(error.getMessage().contains("identity changed"));
        assertTrue(Files.exists(replacementSentinel));
        assertTrue(Files.exists(displacedEpisode.resolve("answer.md")));
    }
}
