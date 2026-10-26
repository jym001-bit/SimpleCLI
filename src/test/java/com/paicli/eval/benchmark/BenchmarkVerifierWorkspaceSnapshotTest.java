package com.paicli.eval.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkVerifierWorkspaceSnapshotTest {
    @Test
    void createsIndependentReadOnlyRegularFileSnapshot(@TempDir Path rawTempDir) throws Exception {
        Path tempDir = rawTempDir.toRealPath();
        Path source = Files.createDirectory(tempDir.resolve("workspace"));
        Path nested = Files.createDirectory(source.resolve("src"));
        Files.writeString(nested.resolve("Main.java"), "class Main {}\n");
        Path executable = Files.writeString(source.resolve("run.sh"), "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(executable, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        Path target = Files.createDirectory(tempDir.resolve("snapshot"));

        BenchmarkVerifierWorkspaceSnapshot.Snapshot snapshot =
                BenchmarkVerifierWorkspaceSnapshot.create(source, target);

        assertEquals(2, snapshot.fileCount());
        assertTrue(snapshot.totalBytes() > 0);
        assertEquals(64, snapshot.treeSha256().length());
        assertEquals("class Main {}\n", Files.readString(target.resolve("src/Main.java")));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ),
                Files.getPosixFilePermissions(target.resolve("src/Main.java")));
        assertTrue(Files.isExecutable(target.resolve("run.sh")));
        snapshot.verifyUnchanged();

        Files.setPosixFilePermissions(target.resolve("run.sh"), Set.of(
                PosixFilePermission.OWNER_READ));
        assertThrows(BenchmarkVerifierWorkspaceSnapshot.UnsafeWorkspaceException.class,
                snapshot::verifyUnchanged);
        Files.setPosixFilePermissions(target.resolve("run.sh"), Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_EXECUTE));

        Files.setPosixFilePermissions(target.resolve("src/Main.java"), Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE));
        Files.writeString(target.resolve("src/Main.java"), "changed\n");
        assertThrows(BenchmarkVerifierWorkspaceSnapshot.UnsafeWorkspaceException.class,
                snapshot::verifyUnchanged);
    }

    @Test
    void rejectsSymlinkAndHardlink(@TempDir Path rawTempDir) throws Exception {
        Path tempDir = rawTempDir.toRealPath();
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "outside");
        Path source = Files.createDirectory(tempDir.resolve("workspace"));
        Files.createSymbolicLink(source.resolve("link"), outside);
        Path target = Files.createDirectory(tempDir.resolve("snapshot"));

        assertThrows(BenchmarkVerifierWorkspaceSnapshot.UnsafeWorkspaceException.class,
                () -> BenchmarkVerifierWorkspaceSnapshot.create(source, target));

        Files.delete(source.resolve("link"));
        Path first = Files.writeString(source.resolve("first.txt"), "same inode");
        try {
            Files.createLink(source.resolve("second.txt"), first);
        } catch (UnsupportedOperationException | IOException error) {
            return;
        }
        assertThrows(BenchmarkVerifierWorkspaceSnapshot.UnsafeWorkspaceException.class,
                () -> BenchmarkVerifierWorkspaceSnapshot.create(source, target));
    }

    @Test
    void rejectsControlCharacterPathAndOverlappingTarget(@TempDir Path rawTempDir) throws Exception {
        Path tempDir = rawTempDir.toRealPath();
        Path source = Files.createDirectory(tempDir.resolve("workspace"));
        Files.writeString(source.resolve("bad\nname.txt"), "bad");
        Path target = Files.createDirectory(tempDir.resolve("snapshot"));

        assertThrows(BenchmarkVerifierWorkspaceSnapshot.UnsafeWorkspaceException.class,
                () -> BenchmarkVerifierWorkspaceSnapshot.create(source, target));
        assertThrows(IOException.class,
                () -> BenchmarkVerifierWorkspaceSnapshot.create(source, source));
    }
}
