package com.paicli.eval.benchmark;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkArtifactStoreTest {

    @Test
    void writesRedactedArtifactsAndPrivateRawTrace(@TempDir Path tempDir) throws Exception {
        String secret = "deepseek-secret-value-123456";
        BenchmarkArtifactStore store = BenchmarkArtifactStore.create(tempDir, "run-001");

        Path manifest = store.writeManifest(Map.of("apiKey", secret, "suite", "v1"));
        BenchmarkArtifactStore.EpisodeArtifacts episode = store.episode("case-001", "deepseek-v4", 1);
        Path run = episode.writeRun(Map.of("authorization", "Bearer " + secret));
        Path verifier = episode.writeVerifier(Map.of("passed", true));
        Path answer = episode.writeAnswer("answer accidentally included sk-abcdefghijklmnopqrstuvwxyz123456");
        Path raw = episode.writeRawTrace("{\"apiKey\":\"" + secret + "\"}\n");

        assertTrue(manifest.startsWith(store.runDirectory()));
        assertTrue(run.startsWith(episode.directory()));
        assertTrue(Files.exists(verifier));
        assertFalse(Files.readString(manifest).contains(secret));
        assertFalse(Files.readString(run).contains(secret));
        assertFalse(Files.readString(answer).contains("sk-abcdefghijklmnopqrstuvwxyz123456"));
        assertTrue(Files.readString(raw).contains(secret));

        if (Files.getFileStore(raw).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(raw));
            assertEquals(PosixFilePermissions.fromString("rwx------"),
                    Files.getPosixFilePermissions(episode.directory()));
        }
    }

    @Test
    void rejectsUnsafeSegmentsAndNonPositiveRepeat(@TempDir Path tempDir) throws Exception {
        BenchmarkArtifactStore store = BenchmarkArtifactStore.create(tempDir, "run-safe");

        assertThrows(IllegalArgumentException.class,
                () -> store.episode("../escape", "deepseek", 1));
        assertThrows(IllegalArgumentException.class,
                () -> store.episode("case", "provider/model", 1));
        assertThrows(IllegalArgumentException.class,
                () -> store.episode("case", "model", 0));
        assertThrows(IllegalArgumentException.class,
                () -> store.episode("case", "model", 1, 0));
    }

    @Test
    void formalAttemptsAreAppendOnlyAndDoNotChangeLegacyLayout(@TempDir Path tempDir)
            throws Exception {
        BenchmarkArtifactStore store = BenchmarkArtifactStore.create(tempDir, "run-attempts");

        Path legacy = store.episode("case", "model", 1).directory();
        Path first = store.episode("case", "model", 2, 1).directory();
        Path second = store.episode("case", "model", 2, 2).directory();

        assertEquals("repeat-001", legacy.getFileName().toString());
        assertEquals("attempt-001", first.getFileName().toString());
        assertEquals("attempt-002", second.getFileName().toString());
        assertEquals(first.getParent(), second.getParent());
        assertThrows(FileAlreadyExistsException.class,
                () -> store.episode("case", "model", 2, 1));
    }

    @Test
    void refusesToReuseExistingRunDirectory(@TempDir Path tempDir) throws Exception {
        BenchmarkArtifactStore.create(tempDir, "same-run");

        assertThrows(FileAlreadyExistsException.class,
                () -> BenchmarkArtifactStore.create(tempDir, "same-run"));
    }

    @Test
    void purgeDeletesEpisodeContentsWithoutFollowingChildSymlinks(@TempDir Path tempDir)
            throws Exception {
        BenchmarkArtifactStore store = BenchmarkArtifactStore.create(tempDir, "run-purge");
        BenchmarkArtifactStore.EpisodeArtifacts episode =
                store.episode("case-purge", "model-purge", 1);
        episode.writeAnswer("remove me");
        Path nested = episode.createPrivateDirectory("workspace");
        Files.writeString(nested.resolve("nested.txt"), "remove me too");

        Path outside = Files.createDirectory(tempDir.resolve("outside-purge"));
        Path sentinel = Files.writeString(outside.resolve("keep.txt"), "must survive");
        try {
            Files.createSymbolicLink(episode.directory().resolve("outside-link"), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.abort("symbolic links unavailable on this test host");
        }
        episode.purgeContentsForSecurityFailure();

        try (var remaining = Files.list(episode.directory())) {
            assertTrue(remaining.findAny().isEmpty());
        }
        assertTrue(Files.exists(sentinel));
    }
}
