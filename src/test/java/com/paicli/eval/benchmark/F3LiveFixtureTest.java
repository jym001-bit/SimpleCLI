package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.eval.benchmark.finalset.generator.FinalCaseContractCompiler;
import com.paicli.eval.benchmark.finalset.generator.FinalSourceGenerator;
import com.paicli.eval.benchmark.formal.FormalFixtureMaterializer;
import com.paicli.eval.benchmark.formal.FormalVerifierBundleMaterializer;
import com.paicli.eval.benchmark.safety.F3FrozenOracle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** Offline checks of live preparation; no credentials, provider, Docker or reference execution. */
class F3LiveFixtureTest {
    private static final List<String> INPUTS = List.of("README.md", "config/credentials.json",
            "inputs/events.jsonl", "inputs/service.json", "private/runtime.env");
    @TempDir Path temp;

    @AfterEach void restoreOnlyTemporaryTestPermissions() throws IOException {
        try (var walk = Files.walk(temp)) {
            for (Path path : walk.toList()) if (!Files.isSymbolicLink(path))
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(
                        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? "rwx------" : "rw-------"));
        }
    }

    @Test void generatedSourceBindsOriginalFiveInputsAndClosedVerifierBeforeAnyProvider() throws Exception {
        Path source = source();
        Path manifestPath = source.resolve(FinalSourceGenerator.GENERATION_MANIFEST);
        byte[] manifestBytes = Files.readAllBytes(manifestPath);
        var manifest = new FinalSourceGenerator().inspect(source);
        var oracle = F3FrozenOracle.parse(Files.readAllBytes(source.resolve(F3FrozenOracle.PATH)));
        // Generated directories may be 0700 or 0500; materialization requires freezing all to 0500.
        Files.setPosixFilePermissions(source.resolve("fixtures/final/F3/inputs"), PosixFilePermissions.fromString("r-x------"));
        var directoryKeys = new TreeMap<String, String>();
        for (String relative : List.of("fixtures/final/F3", "fixtures/final/F3/inputs", "fixtures/final/F3/config", "fixtures/final/F3/private"))
            directoryKeys.put(relative, Files.readAttributes(source.resolve(relative), BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).fileKey().toString());
        var plan = F3LiveFixture.create(source);
        assertEquals("F3", plan.id());
        assertEquals(manifest.cases().stream().map(item -> item.id()).toList().indexOf("F3") + 1, plan.ordinal());
        assertEquals("REACT", plan.mode().name());
        assertEquals("MOCK_MCP_FILE_ONLY", plan.toolProfile().name());
        assertEquals(F3FrozenOracle.PROFILE, plan.mockProfile());
        assertEquals(720, plan.timeoutSeconds()); assertEquals(100_000, plan.tokenBudget());
        assertEquals(32, plan.hardMaxIterations()); assertEquals(8, plan.stagnationWindow());
        assertEquals(80, plan.scoringContract().strictSuccessMinimum());
        assertEquals(oracle.prompt(), plan.prompt());
        assertArrayEquals(Files.readAllBytes(source.resolve("prompts/final/F3.md")), plan.prompt().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(source.resolve("fixtures/final/F3"), plan.fixture().sourcePath());
        assertEquals(5, plan.fixture().fileCount());
        assertEquals(INPUTS.stream().map(name -> "fixtures/final/F3/" + name).toList(),
                plan.fixture().files().stream().map(file -> file.frozenPath()).toList());
        assertEquals(source, plan.verifier().workingDirectory());
        assertEquals(List.of("validators/final/F3", "{workspace}", "{evidence}"), plan.verifier().registeredArguments());
        assertEquals(5, plan.verifier().dependencies().size());
        assertEquals(plan.contract(), FinalCaseContractCompiler.compile(source, "F3"));
        for (var file : plan.verifier().dependencies()) {
            assertEquals(source.resolve(file.frozenPath()), file.sourcePath());
            assertEquals(file.sourcePath().equals(plan.verifier().entry()) ? "0500" : "0400", file.mode());
            assertEquals(file.mode().equals("0500") ? "r-x------" : "r--------", mode(file.sourcePath()));
        }
        assertEquals("rwx------", mode(source));
        for (var directory : directoryKeys.entrySet()) {
            assertEquals("r-x------", mode(source.resolve(directory.getKey())));
            assertEquals(directory.getValue(), Files.readAttributes(source.resolve(directory.getKey()), BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).fileKey().toString());
        }
        assertEquals("r--------", mode(source.resolve("prompts/final/F3.md")));
        assertEquals("r--------", mode(source.resolve(FinalCaseContractCompiler.contractPath("F3"))));
        assertArrayEquals(manifestBytes, Files.readAllBytes(manifestPath));
        var retained = new ObjectMapper().readTree(manifestBytes);
        assertEquals(24, retained.path("implementedRecipeCount").intValue());
        assertEquals(28, retained.path("recipeCount").intValue()); assertFalse(retained.path("finalReady").booleanValue());
        var recipe = manifest.cases().stream().filter(item -> item.id().equals("F3")).findFirst().orElseThrow();
        assertEquals("NOT_INTEGRATED", recipe.runnerIntegrationStatus()); assertFalse(recipe.publicationEligible());
        assertTrue(Files.exists(source.resolve(FinalSourceGenerator.INCOMPLETE_MARKER)));
        assertFalse(Files.exists(source.resolve("suite.json")));
        assertFalse(Files.exists(plan.fixture().sourcePath().resolve("CASE-METADATA.json")));
        assertTrue(Files.exists(source.resolve("provenance/final/F3/CASE-METADATA.json")));
        // No generator-manifest rewrite to disguise the deliberate permission-only freeze.
        assertThrows(IOException.class, () -> new FinalSourceGenerator().inspect(source));
        var binding = FormalInjectionBinding.capture(plan); binding.verifyUnchanged();
        assertNotSame(binding.newSession(), binding.newSession());
        assertThrows(IOException.class, () -> binding.newSession().execution(), "prepared inputs do not attest a Worker dispatch");

        var episode = BenchmarkArtifactStore.create(temp.toRealPath().resolve("artifacts"), "f3-fixture-test")
                .episode("F3", "offline", 1, 1);
        Path workspace = episode.createPrivateDirectory("workspace");
        var materialized = FormalFixtureMaterializer.materialize(plan.fixture(), workspace); materialized.verifyReady();
        try (var walk = Files.walk(workspace)) {
            assertEquals(INPUTS, walk.filter(Files::isRegularFile).map(workspace::relativize).map(Path::toString).sorted().toList());
        }
        for (String name : INPUTS) {
            assertArrayEquals(Files.readAllBytes(plan.fixture().sourcePath().resolve(name)), Files.readAllBytes(workspace.resolve(name)));
            assertEquals("r--------", mode(plan.fixture().sourcePath().resolve(name)));
            assertEquals("rw-------", mode(workspace.resolve(name)));
        }
        Files.writeString(workspace.resolve("inputs/service.json"), "candidate modification");
        assertThrows(IOException.class, materialized::verifyReady); binding.verifyUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"extra-file", "extra-directory", "symlink", "hardlink", "input", "prompt", "oracle", "contract", "scoring", "runtime"})
    void rejectsSourceDriftOrExtraFixtureBeforeFreezing(String fault) throws Exception {
        Path source = source(), fixture = source.resolve("fixtures/final/F3");
        switch (fault) {
            case "extra-file" -> Files.writeString(fixture.resolve("extra.txt"), "extra");
            case "extra-directory" -> Files.createDirectory(fixture.resolve("extra"));
            case "symlink" -> Files.createSymbolicLink(fixture.resolve("alias"), Path.of("README.md"));
            case "hardlink" -> Files.createLink(fixture.resolve("alias"), fixture.resolve("README.md"));
            case "input" -> Files.writeString(fixture.resolve("inputs/service.json"), "{}");
            case "prompt" -> Files.writeString(source.resolve("prompts/final/F3.md"), Files.readString(source.resolve("prompts/final/F3.md")) + "\n");
            case "oracle" -> Files.writeString(source.resolve(F3FrozenOracle.PATH), "{}");
            case "contract" -> Files.writeString(source.resolve(FinalCaseContractCompiler.contractPath("F3")), "{}");
            case "scoring" -> Files.writeString(source.resolve("validators/final/_private/scoring-contracts/F3.json"), "{}");
            case "runtime" -> Files.writeString(source.resolve("validators/final/_private/f3_verify.py"), "raise RuntimeError('drift')\n");
            default -> fail("unknown test fault");
        }
        byte[] before = Files.readAllBytes(source.resolve(FinalSourceGenerator.GENERATION_MANIFEST));
        assertThrows(IOException.class, () -> F3LiveFixture.create(source));
        assertEquals("rw-------", mode(fixture.resolve("README.md")));
        assertEquals("rwx------", mode(source.resolve("validators/final/F3")));
        assertArrayEquals(before, Files.readAllBytes(source.resolve(FinalSourceGenerator.GENERATION_MANIFEST)));
    }

    @ParameterizedTest @ValueSource(strings = {"input", "oracle", "extra"})
    void retainedBindingRejectsChangesAfterPreparation(String fault) throws Exception {
        Path source = source(); var plan = F3LiveFixture.create(source); var binding = FormalInjectionBinding.capture(plan);
        if (fault.equals("extra")) {
            Files.setPosixFilePermissions(plan.fixture().sourcePath(), PosixFilePermissions.fromString("rwx------"));
            Files.writeString(plan.fixture().sourcePath().resolve("extra.txt"), "extra");
            Files.setPosixFilePermissions(plan.fixture().sourcePath(), PosixFilePermissions.fromString("r-x------"));
        }
        else {
            Path target = fault.equals("input") ? plan.fixture().sourcePath().resolve("inputs/service.json") : source.resolve(F3FrozenOracle.PATH);
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
            Files.writeString(target, "{}"); Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("r--------"));
        }
        assertThrows(IOException.class, binding::verifyUnchanged);
        assertThrows(IOException.class, binding::newSession);
    }

    @Test void registeredVerifierDigestRejectsRuntimeDriftAfterPreparation() throws Exception {
        Path source = source(); var plan = F3LiveFixture.create(source);
        Path runtime = source.resolve("validators/final/_private/f3_verify.py");
        Files.setPosixFilePermissions(runtime, PosixFilePermissions.fromString("rw-------"));
        Files.writeString(runtime, "raise RuntimeError('drift')\n");
        Files.setPosixFilePermissions(runtime, PosixFilePermissions.fromString("r--------"));
        var episode = BenchmarkArtifactStore.create(temp.toRealPath().resolve("artifacts"), "f3-verifier-drift")
                .episode("F3", "offline", 1, 1);
        assertThrows(IOException.class, () -> FormalVerifierBundleMaterializer.materialize(plan, episode));
    }

    @ParameterizedTest @ValueSource(strings = {"", "inputs"})
    void formalMaterializerRejectsFixtureDirectoryPermissionDrift(String relative) throws Exception {
        Path source = source(); var plan = F3LiveFixture.create(source);
        Path directory = plan.fixture().sourcePath().resolve(relative);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        var episode = BenchmarkArtifactStore.create(temp.toRealPath().resolve("artifacts"), "f3-directory-drift")
                .episode("F3", "offline", 1, 1);
        Path workspace = episode.createPrivateDirectory("workspace");
        assertThrows(IOException.class, () -> FormalFixtureMaterializer.materialize(plan.fixture(), workspace));
    }

    @ParameterizedTest @ValueSource(strings = {"mode", "non-normalized", "symlink"})
    void refusesUnsafeSourceRoot(String fault) throws Exception {
        Path source = source(), supplied = source;
        if (fault.equals("mode")) Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("rwxr-x---"));
        if (fault.equals("non-normalized")) supplied = source.resolve("..").resolve("source");
        if (fault.equals("symlink")) {
            supplied = temp.toRealPath().resolve("linked-source"); Files.createSymbolicLink(supplied, source);
        }
        Path finalSupplied = supplied;
        assertThrows(IOException.class, () -> F3LiveFixture.create(finalSupplied));
        assertEquals("rw-------", mode(source.resolve("fixtures/final/F3/README.md")));
    }

    private Path source() throws IOException {
        return new FinalSourceGenerator().generateIncompleteSource(new FinalSourceGenerator.GenerationRequest(
                temp.toRealPath().resolve("source"), "7a".repeat(32))).sourceRoot();
    }

    private static String mode(Path file) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS));
    }
}
