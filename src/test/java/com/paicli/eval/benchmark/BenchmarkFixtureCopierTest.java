package com.paicli.eval.benchmark;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BenchmarkFixtureCopierTest {
    @Test
    void copiesDirectoryContentsIntoFreshWorkspace(@TempDir Path tempDir) throws Exception {
        Path fixture = Files.createDirectories(tempDir.resolve("fixture/src"));
        Files.writeString(fixture.resolve("Main.java"), "class Main {}");
        Files.writeString(fixture.getParent().resolve("README.md"), "instructions");
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));

        BenchmarkFixtureCopier.verifySafe(fixture.getParent());
        BenchmarkFixtureCopier.copy(fixture.getParent(), workspace);

        assertEquals("instructions", Files.readString(workspace.resolve("README.md")));
        assertEquals("class Main {}", Files.readString(workspace.resolve("src/Main.java")));
    }

    @Test
    void rejectsAnySymbolicLinkWithoutFollowingIt(@TempDir Path tempDir) throws Exception {
        Path fixture = Files.createDirectory(tempDir.resolve("fixture"));
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "outside");
        try {
            Files.createSymbolicLink(fixture.resolve("link.txt"), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.abort("symbolic links unavailable on this test host");
        }

        assertThrows(IOException.class, () -> BenchmarkFixtureCopier.verifySafe(fixture));
        assertThrows(IOException.class,
                () -> BenchmarkFixtureCopier.copy(fixture, Files.createDirectory(tempDir.resolve("workspace"))));
    }
}
