package com.paicli.eval.benchmark.formal;

import com.paicli.eval.benchmark.BenchmarkArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormalVerifierBundleMaterializerTest {
    @Test
    void copiesOnlyRegisteredDependenciesAndFreezesRelativeLayout(@TempDir Path tempDir)
            throws Exception {
        FormalBenchmarkPreflightTest.Fixture fixture =
                FormalBenchmarkPreflightTest.Fixture.create(tempDir.resolve("fixture"));
        FormalExecutionPlan plan = FormalExecutionPlan.from(
                fixture.verify(fixture.preflight()));
        FormalExecutionPlan.CasePlan first = plan.cases().get(0);
        BenchmarkArtifactStore store = BenchmarkArtifactStore.create(
                tempDir.resolve("artifacts"), "formal-dry-run");
        BenchmarkArtifactStore.EpisodeArtifacts episode =
                store.episode(first.id(), "deepseek-v4-flash", 1, 1);

        FormalVerifierBundleMaterializer.TrustedVerifierBundle bundle =
                FormalVerifierBundleMaterializer.materialize(first, episode);
        bundle.verifyUnchanged();

        assertEquals(first.verifier().bundleSha256(), bundle.bundleSha256());
        assertEquals(2, bundle.dependencies().size());
        assertTrue(Files.isRegularFile(bundle.root().resolve(
                "validators/final/case-01.sh")));
        assertTrue(Files.isRegularFile(bundle.root().resolve(
                "validators/final/case-01.verify.py")));
        assertFalse(Files.exists(bundle.root().resolve("suite.json")));
        assertEquals(Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_EXECUTE),
                Files.getPosixFilePermissions(bundle.root()));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ),
                Files.getPosixFilePermissions(bundle.root().resolve(
                        "validators/final/case-01.verify.py")));

        Path workspace = Files.createDirectories(tempDir.resolve("workspace"))
                .toAbsolutePath().normalize();
        Path evidence = Files.writeString(tempDir.resolve("evidence.json"), "{}")
                .toAbsolutePath().normalize();
        FormalExecutionPlan.MaterializedVerifier command =
                bundle.command(workspace, evidence);
        assertEquals(bundle.root(), command.workingDirectory());
        assertEquals(List.of(
                        "validators/final/case-01.sh",
                        workspace.toString(),
                        evidence.toString()),
                command.arguments());
    }

    @Test
    void detectsSourceDriftBeforeCopy(@TempDir Path tempDir) throws Exception {
        FormalBenchmarkPreflightTest.Fixture fixture =
                FormalBenchmarkPreflightTest.Fixture.create(tempDir.resolve("drift-fixture"));
        FormalExecutionPlan plan = FormalExecutionPlan.from(
                fixture.verify(fixture.preflight()));
        FormalExecutionPlan.CasePlan first = plan.cases().get(0);
        Files.writeString(first.verifier().dependencies().get(1).sourcePath(), "changed helper\n");
        BenchmarkArtifactStore.EpisodeArtifacts episode = BenchmarkArtifactStore.create(
                        tempDir.resolve("drift-artifacts"), "formal-drift")
                .episode(first.id(), "deepseek-v4-flash", 1, 1);

        FormalVerifierBundleMaterializer.MaterializationException error = assertThrows(
                FormalVerifierBundleMaterializer.MaterializationException.class,
                () -> FormalVerifierBundleMaterializer.materialize(first, episode));
        assertEquals(FormalVerifierBundleMaterializer.FailureKind.DATASET, error.kind());
        assertFalse(error.getMessage().contains(first.verifier().dependencies().get(1)
                .sourcePath().toString()));
    }

    @Test
    void recheckRejectsPermissionExtraMissingAndLinkedDependencies(@TempDir Path tempDir) throws Exception {
        for (String mutation : List.of("permissions", "extra", "missing", "symlink", "hardlink")) {
            var fixture = FormalBenchmarkPreflightTest.Fixture.create(tempDir.resolve(mutation));
            var first = FormalExecutionPlan.from(fixture.verify(fixture.preflight())).cases().get(0);
            var episode = BenchmarkArtifactStore.create(tempDir.resolve(mutation + "-output"), "run")
                    .episode(first.id(), "deepseek-v4-flash", 1, 1);
            var bundle = FormalVerifierBundleMaterializer.materialize(first, episode);
            bundle.verifyUnchanged();
            Path entry = bundle.entry();
            if ("permissions".equals(mutation)) {
                Files.setPosixFilePermissions(entry, PosixFilePermissions.fromString("rwx------"));
            } else if ("hardlink".equals(mutation)) {
                Files.createLink(tempDir.resolve("external-hardlink"), entry);
            } else {
                Path parent = "extra".equals(mutation) ? bundle.root() : entry.getParent();
                Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"));
                if ("extra".equals(mutation)) Files.createDirectory(parent.resolve("undeclared"));
                else {
                    Files.delete(entry); // Only this test's generated dependency.
                    if ("symlink".equals(mutation))
                        Files.createSymbolicLink(entry, first.verifier().dependencies().get(0).sourcePath());
                }
                Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("r-x------"));
            }
            assertThrows(IOException.class, bundle::verifyUnchanged, mutation);
        }
    }
}
