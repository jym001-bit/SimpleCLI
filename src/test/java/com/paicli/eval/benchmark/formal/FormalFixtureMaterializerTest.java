package com.paicli.eval.benchmark.formal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class FormalFixtureMaterializerTest {
    @TempDir Path temp;

    @AfterEach
    void restoreTestOwnedPermissions() throws Exception {
        try (var paths = Files.walk(temp)) {
            for (Path p : paths.toList()) if (!Files.isSymbolicLink(p))
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(
                        Files.isDirectory(p) ? "rwx------" : "rw-------"));
        }
    }

    @Test
    void registeredDirectoryCopiesBecomeWritableWithoutChangingFrozenSource() throws Exception {
        var fixture = fixture(false);
        Path workspace = workspace();
        var result = FormalFixtureMaterializer.materialize(fixture, workspace);
        assertEquals(fixture.snapshotSha256(), result.frozenSnapshotSha256());
        assertEquals("frozen fixture\n", Files.readString(workspace.resolve("src/data.txt")));
        assertEquals(PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(workspace.resolve("src/data.txt")));
        assertEquals(PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(workspace.resolve("run.sh")));
        assertEquals(PosixFilePermissions.fromString("r--------"),
                Files.getPosixFilePermissions(fixture.sourcePath().resolve("src/data.txt")));
        result.verifyReady();
        Files.writeString(workspace.resolve("src/data.txt"), "candidate may edit\n");
        assertThrows(IOException.class, result::verifyReady);
        assertEquals("frozen fixture\n",
                Files.readString(fixture.sourcePath().resolve("src/data.txt")));
    }

    @Test
    void fileFixtureMapsToItsBasenameAndRejectsExtraWorkerFiles() throws Exception {
        var fixture = fixture(true);
        Path workspace = workspace();
        var result = FormalFixtureMaterializer.materialize(fixture, workspace);
        assertEquals("frozen fixture\n", Files.readString(workspace.resolve("fixture.txt")));
        result.verifyReady();
        Files.writeString(workspace.resolve("unregistered.txt"), "extra");
        assertThrows(IOException.class, result::verifyReady);
    }

    @Test
    void sameLengthContentDriftFailsBeforeLaunch() throws Exception {
        var fixture = fixture(true);
        mode(fixture.sourcePath(), "rw-------");
        Files.writeString(fixture.sourcePath(), "broken fixture\n");
        mode(fixture.sourcePath(), "r--------");
        assertThrows(IOException.class, () ->
                FormalFixtureMaterializer.materialize(fixture, workspace()));
    }

    @Test
    void unregisteredSourceFilesAndEmptyDirectoriesFail() throws Exception {
        var fixture = fixture(false);
        mode(fixture.sourcePath(), "rwx------");
        Path extra = Files.writeString(fixture.sourcePath().resolve("oracle.txt"), "hidden");
        mode(fixture.sourcePath(), "r-x------");
        assertThrows(IOException.class, () ->
                FormalFixtureMaterializer.materialize(fixture, workspace()));
        mode(fixture.sourcePath(), "rwx------");
        Files.delete(extra);
        Files.createDirectory(fixture.sourcePath().resolve("extra-directory"));
        mode(fixture.sourcePath(), "r-x------");
        assertThrows(IOException.class, () ->
                FormalFixtureMaterializer.materialize(fixture, workspace()));
    }

    @Test
    void sourceHardlinksAndSymlinksAreRejected() throws Exception {
        var fixture = fixture(true);
        Path extra = temp.resolve("hardlink");
        Files.createLink(extra, fixture.sourcePath());
        assertThrows(IOException.class, () ->
                FormalFixtureMaterializer.materialize(fixture, workspace()));
        Files.delete(extra);
        Path source = fixture.sourcePath();
        Files.move(source, temp.resolve("moved-source"));
        Files.createSymbolicLink(source, temp.resolve("moved-source"));
        assertThrows(IOException.class, () ->
                FormalFixtureMaterializer.materialize(fixture, workspace()));
    }

    @Test
    void modeDriftAndNonemptyWorkspaceAreRejected() throws Exception {
        var fixture = fixture(true);
        mode(fixture.sourcePath(), "rw-------");
        assertThrows(IOException.class, () ->
                FormalFixtureMaterializer.materialize(fixture, workspace()));
        mode(fixture.sourcePath(), "r--------");
        Path workspace = workspace();
        Files.writeString(workspace.resolve("user-file"), "keep me");
        assertThrows(IOException.class, () ->
                FormalFixtureMaterializer.materialize(fixture, workspace));
        assertEquals("keep me", Files.readString(workspace.resolve("user-file")));
    }

    private Path workspace() throws Exception {
        Path p = Files.createTempDirectory(temp.toRealPath(), "workspace-");
        mode(p, "rwx------");
        return p;
    }

    private FormalExecutionPlan.FixtureSnapshot fixture(boolean singleFile) throws Exception {
        Path source = temp.toRealPath().resolve(singleFile ? "fixture.txt" : "fixture");
        String frozen = singleFile ? "fixtures/fixture.txt" : "fixtures/case";
        var kind = singleFile ? FormalBenchmarkPreflight.FixtureKind.FILE
                : FormalBenchmarkPreflight.FixtureKind.DIRECTORY;
        List<FormalExecutionPlan.FixtureFile> files = new ArrayList<>();
        if (singleFile) {
            Files.writeString(source, "frozen fixture\n");
            mode(source, "r--------");
            files.add(entry(source, frozen, "0400"));
        } else {
            Files.createDirectories(source.resolve("src"));
            Path data = Files.writeString(source.resolve("src/data.txt"), "frozen fixture\n");
            Path script = Files.writeString(source.resolve("run.sh"), "#!/bin/sh\nexit 0\n");
            mode(data, "r--------"); mode(script, "r-x------");
            mode(source.resolve("src"), "r-x------"); mode(source, "r-x------");
            files.add(entry(script, frozen + "/run.sh", "0500"));
            files.add(entry(data, frozen + "/src/data.txt", "0400"));
        }
        StringBuilder identity = new StringBuilder("paicli-formal-fixture-snapshot-v1\0")
                .append(kind.name()).append('\0').append(frozen).append('\n');
        for (var file : files) identity.append(file.frozenPath()).append('\0')
                .append(file.sha256()).append('\0').append(file.mode()).append('\0')
                .append(file.size()).append('\n');
        return new FormalExecutionPlan.FixtureSnapshot(frozen, frozen, source, kind,
                digest(identity.toString().getBytes(StandardCharsets.UTF_8)), files.size(),
                files.stream().mapToLong(FormalExecutionPlan.FixtureFile::size).sum(), files);
    }

    private static FormalExecutionPlan.FixtureFile entry(Path p, String path, String mode)
            throws Exception {
        return new FormalExecutionPlan.FixtureFile(path, digest(Files.readAllBytes(p)),
                Files.size(p), mode);
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void mode(Path p, String mode) throws Exception {
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(mode));
    }
}
