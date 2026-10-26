package com.paicli.eval.benchmark;

import com.paicli.eval.benchmark.formal.FormalFixtureMaterializer;
import com.paicli.eval.benchmark.plan.E1FrozenOracle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Offline tests of the actual live diagnostic preparation path; never reads credentials. */
class E1LiveFixtureTest {
    @TempDir Path temp;

    @AfterEach void restoreTestDirectoryPermissions() throws Exception {
        try (var walk = Files.walk(temp)) {
            for (Path path : walk.toList()) if (!Files.isSymbolicLink(path))
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(Files.isDirectory(path) ? "rwx------" : "rw-------"));
        }
    }

    @Test void actualGeneratedContractBindsBeforeAnyProviderAndMaterializesOnlyCsvInputs() throws Exception {
        Path root = temp.toRealPath();
        var prepared = E1LiveDockerDiagnosticTest.prepareSource(root);
        var plan = prepared.plan();
        assertTrue(prepared.implementedRecipes() >= 20);
        assertEquals("PLAN", plan.mode().name()); assertEquals("FILE_ONLY", plan.toolProfile().name());
        assertEquals(1800, plan.timeoutSeconds()); assertEquals(200000, plan.tokenBudget());
        assertEquals(32, plan.hardMaxIterations()); assertEquals(8, plan.stagnationWindow());
        assertTrue(plan.evidenceRequirements().containsAll(List.of("plan_audit", "scoped_request_fingerprints")));
        assertFalse(Files.exists(prepared.source().resolve("suite.json")), "diagnostic must not invent a formal suite");
        assertTrue(Files.exists(prepared.source().resolve("provenance/final/E1/CASE-METADATA.json")));
        assertFalse(Files.exists(plan.fixture().sourcePath().resolve("CASE-METADATA.json")));
        assertEquals(prepared.source().resolve("fixtures/final/E1"), plan.fixture().sourcePath());
        var binding = FormalPlanBinding.capture(plan); binding.verifyUnchanged();
        var first = binding.newSession(); var second = binding.newSession(); assertNotSame(first, second);
        assertThrows(IOException.class, first::evidence, "source preparation alone is not a dispatch attestation");
        var store = BenchmarkArtifactStore.create(root.resolve("artifacts"), "fixture-test");
        var episode = store.episode("E1", "offline", 1, 1);
        Path workspace = episode.createPrivateDirectory("workspace");
        var materialized = FormalFixtureMaterializer.materialize(plan.fixture(), workspace); materialized.verifyReady();
        try (var files = Files.list(workspace)) {
            assertEquals(List.of("left.csv", "right.csv"), files.map(p -> p.getFileName().toString()).sorted().toList());
        }
        for (String name : List.of("left.csv", "right.csv")) {
            assertArrayEquals(Files.readAllBytes(plan.fixture().sourcePath().resolve(name)), Files.readAllBytes(workspace.resolve(name)));
            assertEquals(PosixFilePermissions.fromString("r--------"), Files.getPosixFilePermissions(plan.fixture().sourcePath().resolve(name)));
        }
        Files.writeString(workspace.resolve("left.csv"), "worker mutation");
        assertThrows(IOException.class, materialized::verifyReady);
        binding.verifyUnchanged();
    }

    @Test void refusesOutputReuseWithoutTouchingExistingEvidence() throws Exception {
        Path root = temp.toRealPath(), marker = root.resolve("existing-evidence.txt");
        Files.writeString(marker, "preserve");
        assertThrows(IOException.class, () -> E1LiveDockerDiagnosticTest.prepareSource(root));
        assertEquals("preserve", Files.readString(marker)); assertFalse(Files.exists(root.resolve("source")));
    }

    @Test void refusesNonPrivateOutputBeforeGeneration() throws Exception {
        Path root = temp.toRealPath(); Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-x---"));
        assertThrows(IOException.class, () -> E1LiveDockerDiagnosticTest.prepareSource(root));
        assertFalse(Files.exists(root.resolve("source")));
    }

    @Test void frozenSourceMutationCannotBeReboundAsTheSameTask() throws Exception {
        var prepared = E1LiveDockerDiagnosticTest.prepareSource(temp.toRealPath());
        var binding = FormalPlanBinding.capture(prepared.plan());
        Path oracle = prepared.source().resolve(E1FrozenOracle.PATH);
        Files.setPosixFilePermissions(oracle, PosixFilePermissions.fromString("rw-------"));
        Files.writeString(oracle, "{}"); Files.setPosixFilePermissions(oracle, PosixFilePermissions.fromString("r--------"));
        assertThrows(IOException.class, binding::verifyUnchanged);
        assertThrows(IOException.class, binding::newSession);
    }
}
