package com.paicli.eval.benchmark;

import com.paicli.eval.benchmark.finalset.generator.FinalSourceGenerator;
import com.paicli.eval.benchmark.formal.FormalFixtureMaterializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** No API calls. Regression for the live harness's pre-existing metadata false hard gate. */
class D3LiveFixtureTest {
    @TempDir Path temp;

    @AfterEach void restorePermissions() throws Exception {
        try (var walk = Files.walk(temp)) {
            for (Path path : walk.toList()) if (!Files.isSymbolicLink(path))
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(Files.isDirectory(path) ? "rwx------" : "rw-------"));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"D3", "D4"})
    void onlyAdmittedReadmeEntersWorkerAndRealMutationsStillFail(String caseId) throws Exception {
        Path root = temp.toRealPath();
        var generated = new FinalSourceGenerator().generateIncompleteSource(
                new FinalSourceGenerator.GenerationRequest(root.resolve("source"), (caseId.equals("D3") ? "d3d1a690" : "d4d1a690").repeat(8)));
        Path original = generated.sourceRoot().resolve("fixtures/final/" + caseId);
        assertTrue(Files.exists(original.resolve("CASE-METADATA.json")));
        var fixture = caseId.equals("D3") ? D3LiveDockerDiagnosticTest.stageAdmittedFixture(generated.sourceRoot(), root.resolve("admitted"))
                : D4LiveDockerDiagnosticTest.stageAdmittedFixture(generated.sourceRoot(), root.resolve("admitted"));
        Path workspace = Files.createDirectory(root.resolve("workspace"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        var materialized = FormalFixtureMaterializer.materialize(fixture, workspace);
        materialized.verifyReady();
        try (var files = Files.list(workspace)) {
            assertEquals(List.of("README.md"), files.map(p -> p.getFileName().toString()).toList());
        }
        assertEquals(Files.readString(original.resolve("README.md")), Files.readString(workspace.resolve("README.md")));
        assertTrue(Files.exists(original.resolve("CASE-METADATA.json")), "keep the original generated source");
        Files.writeString(workspace.resolve("unexpected.txt"), "candidate write");
        assertThrows(IOException.class, materialized::verifyReady);
    }
}
