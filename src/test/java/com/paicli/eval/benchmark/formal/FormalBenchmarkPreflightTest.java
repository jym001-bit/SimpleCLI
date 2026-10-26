package com.paicli.eval.benchmark.formal;

import com.paicli.eval.benchmark.BenchmarkRunnerArtifactPolicy;
import com.paicli.eval.benchmark.CaseDefinition;
import com.paicli.eval.benchmark.SuiteDefinition;
import com.paicli.eval.benchmark.finalset.FinalDatasetFreezeManifest;
import com.paicli.eval.benchmark.finalset.FinalDatasetFreezer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormalBenchmarkPreflightTest {
    private static final List<String> RUNNER_CLASSES = List.of(
            "com/paicli/eval/benchmark/BenchmarkRelayWorkerMain.class",
            "com/paicli/eval/benchmark/BenchmarkToolRegistry.class",
            "com/paicli/eval/benchmark/BenchmarkToolProfile.class",
            "com/paicli/eval/benchmark/relay/BenchmarkFramedChannel.class",
            "com/paicli/eval/benchmark/relay/BenchmarkProviderRelay.class",
            "com/paicli/eval/benchmark/relay/BenchmarkRelayProtocol.class",
            "com/paicli/eval/benchmark/relay/RelayLlmClient.class",
            "com/paicli/eval/benchmark/relay/RelayMcpTransport.class",
            "com/paicli/eval/benchmark/relay/RelayWebDependencies.class",
            "com/paicli/eval/benchmark/relay/RelayHitlHandler.class",
            "com/paicli/eval/benchmark/relay/ScriptedInteraction.class",
            "com/paicli/eval/benchmark/relay/RelayWireConversions.class");

    @Test
    void admitsOnlyTheFullyBoundBatchAndReturnsRedactedCanonicalSummary(@TempDir Path tempDir)
            throws Exception {
        Fixture fixture = Fixture.create(tempDir.toRealPath().resolve("success"));
        AtomicInteger calls = new AtomicInteger();
        FormalBenchmarkPreflight preflight = new FormalBenchmarkPreflight((root, publicRepo) -> {
            calls.incrementAndGet();
            assertEquals(fixture.frozenRoot(), root);
            assertEquals(fixture.publicRepository(), publicRepo);
            return fixture.freezeResult();
        });

        FormalBenchmarkPreflight.VerifiedPreflight verified = fixture.verify(preflight);

        assertEquals(1, calls.get());
        assertEquals(fixture.frozenRoot(), verified.frozenRoot());
        assertEquals(fixture.candidateJar(), verified.candidateJar());
        assertEquals(fixture.runnerJar(), verified.runnerJar());
        assertEquals(28, verified.cases().size());
        assertEquals("case-01", verified.cases().get(0).id());
        assertEquals(FinalExecutableSuiteContract.Mode.PLAN,
                verified.cases().get(19).mode());
        assertEquals(FinalExecutableSuiteContract.Mode.TEAM,
                verified.cases().get(20).mode());
        assertEquals(3, verified.models().size());
        assertEquals(3, verified.repeats());
        assertEquals(1_000_000, verified.commonContextCapTokens());
        assertEquals(16_384, verified.maxOutputTokensPerCall());
        assertEquals(BenchmarkRunnerArtifactPolicy.inspect(fixture.runnerJar()).inventorySha256(),
                verified.runnerInventorySha256());
        assertEquals(fixture.manifest(), verified.freezeManifest());
        assertEquals(fixture.contract(), verified.executableSuiteContract());
        assertEquals(fixture.batch(), verified.formalBatchContract());
        assertEquals(fixture.frozenRoot().resolve("blueprint.json"), verified.blueprintFile());
        assertEquals(fixture.contract().blueprintSha256(), verified.blueprintSha256());
        assertEquals("Complete the registered task for case-01",
                verified.cases().get(0).prompt());
        assertEquals("case-01", verified.cases().get(0).scoringContract().caseId());
        assertEquals("scoring/case-01.json",
                verified.cases().get(0).scoringContractPath());
        assertEquals(fixture.frozenRoot().resolve("scoring/case-01.json"),
                verified.cases().get(0).scoringContractFile());
        assertEquals(fixture.contract().cases().get(0).scoringContractSha256(),
                verified.cases().get(0).scoringContractSha256());
        assertEquals("d".repeat(64),
                verified.cases().get(0).scoringContract().toolchainSha256());
        assertEquals("fixtures/shared.txt",
                verified.cases().get(0).fixture().frozenPath());
        assertEquals(FormalBenchmarkPreflight.FixtureKind.FILE,
                verified.cases().get(0).fixture().kind());
        assertEquals(1, verified.cases().get(0).fixture().fileCount());
        assertEquals(List.of(
                        "validators/final/case-01.sh", "{workspace}", "{evidence}"),
                verified.cases().get(0).verifierArguments());
        assertEquals(List.of(
                        "validators/final/case-01.sh",
                        "validators/final/case-01.verify.py"),
                verified.cases().get(0).verifierDependencies().stream()
                        .map(FormalBenchmarkPreflight.VerifiedVerifierDependency::frozenPath)
                        .toList());
        assertEquals(fixture.contract().cases().get(0).verifierBundleSha256(),
                verified.cases().get(0).verifierBundleSha256());
        assertEquals(FormalBenchmarkPreflight.FixtureKind.DIRECTORY,
                verified.cases().get(1).fixture().kind());

        String rendered = verified + " " + verified.cases();
        assertFalse(rendered.contains(fixture.base().toString()));
        assertFalse(rendered.contains(fixture.frozenRoot().toString()));
        assertTrue(rendered.contains("cases=28"));
        assertTrue(rendered.contains("candidate="));
    }

    @Test
    void rejectsEveryBatchToFreezeAndJarDigestDrift(@TempDir Path tempDir) throws Exception {
        Fixture fixture = Fixture.create(tempDir.toRealPath().resolve("batch-bindings"));
        FormalBenchmarkPreflight preflight = fixture.preflight();

        List<FormalBatchContract> drifted = List.of(
                fixture.copyBatch("0".repeat(64), fixture.manifest().contentTreeSha256(),
                        fixture.manifest().suiteSha256(), fixture.manifest().validatorTreeSha256(),
                        sha256(fixture.candidateJar()), sha256(fixture.runnerJar())),
                fixture.copyBatch(fixture.freezeResult().manifestSha256(), "1".repeat(64),
                        fixture.manifest().suiteSha256(), fixture.manifest().validatorTreeSha256(),
                        sha256(fixture.candidateJar()), sha256(fixture.runnerJar())),
                fixture.copyBatch(fixture.freezeResult().manifestSha256(),
                        fixture.manifest().contentTreeSha256(), "2".repeat(64),
                        fixture.manifest().validatorTreeSha256(),
                        sha256(fixture.candidateJar()), sha256(fixture.runnerJar())),
                fixture.copyBatch(fixture.freezeResult().manifestSha256(),
                        fixture.manifest().contentTreeSha256(), fixture.manifest().suiteSha256(),
                        "3".repeat(64), sha256(fixture.candidateJar()),
                        sha256(fixture.runnerJar())),
                fixture.copyBatch(fixture.freezeResult().manifestSha256(),
                        fixture.manifest().contentTreeSha256(), fixture.manifest().suiteSha256(),
                        fixture.manifest().validatorTreeSha256(), "4".repeat(64),
                        sha256(fixture.runnerJar())),
                fixture.copyBatch(fixture.freezeResult().manifestSha256(),
                        fixture.manifest().contentTreeSha256(), fixture.manifest().suiteSha256(),
                        fixture.manifest().validatorTreeSha256(), sha256(fixture.candidateJar()),
                        "5".repeat(64)),
                fixture.copyBatchWithRunnerInventory("6".repeat(64)));

        int index = 0;
        for (FormalBatchContract batch : drifted) {
            Path batchFile = fixture.writeBatch(batch, "drift-" + index++);
            assertThrows(IOException.class, () -> preflight.verify(
                    fixture.frozenRoot(), fixture.publicRepository(), fixture.executableContractFile(),
                    batchFile, fixture.candidateJar(), fixture.runnerJar()));
        }
    }

    @Test
    void rejectsSuitePathDigestAndAllActiveCaseAlignmentDrift(@TempDir Path tempDir)
            throws Exception {
        Fixture fixture = Fixture.create(tempDir.toRealPath().resolve("suite-bindings"));
        FormalBenchmarkPreflight preflight = fixture.preflight();

        assertRejectedContract(fixture, preflight, fixture.copyExecutable(
                "other-suite.json", fixture.manifest().suiteSha256(), fixture.contract().cases()),
                "suite-path");
        assertRejectedContract(fixture, preflight, fixture.copyExecutable(
                fixture.manifest().suitePath(), "6".repeat(64), fixture.contract().cases()),
                "suite-digest");
        assertRejectedContract(fixture, preflight, fixture.copyExecutableWithBlueprint(
                "missing-blueprint.json", fixture.contract().blueprintSha256()),
                "blueprint-path");
        assertRejectedContract(fixture, preflight, fixture.copyExecutableWithBlueprint(
                fixture.contract().blueprintPath(), "6".repeat(64)),
                "blueprint-digest");

        List<FinalExecutableSuiteContract.CaseContract> reordered =
                new ArrayList<>(fixture.contract().cases());
        java.util.Collections.swap(reordered, 0, 1);
        assertRejectedContract(fixture, preflight, fixture.copyExecutable(
                fixture.manifest().suitePath(), fixture.manifest().suiteSha256(), reordered),
                "case-order");

        List<FinalExecutableSuiteContract.CaseContract> weights =
                new ArrayList<>(fixture.contract().cases());
        weights.set(24, withWeight(weights.get(24), 2));
        weights.set(25, withWeight(weights.get(25), 3));
        assertRejectedContract(fixture, preflight, fixture.copyExecutable(
                fixture.manifest().suitePath(), fixture.manifest().suiteSha256(), weights),
                "case-weight");

        List<FinalExecutableSuiteContract.CaseContract> levels =
                new ArrayList<>(fixture.contract().cases());
        levels.set(0, withLevel(levels.get(0), FinalExecutableSuiteContract.Level.L2));
        levels.set(10, withLevel(levels.get(10), FinalExecutableSuiteContract.Level.L1));
        assertRejectedContract(fixture, preflight, fixture.copyExecutable(
                fixture.manifest().suitePath(), fixture.manifest().suiteSha256(), levels),
                "case-level");

        List<FinalExecutableSuiteContract.CaseContract> modes =
                new ArrayList<>(fixture.contract().cases());
        modes.set(19, withMode(modes.get(19), FinalExecutableSuiteContract.Mode.REACT));
        modes.set(21, withMode(modes.get(21), FinalExecutableSuiteContract.Mode.PLAN));
        assertRejectedContract(fixture, preflight, fixture.copyExecutable(
                fixture.manifest().suitePath(), fixture.manifest().suiteSha256(), modes),
                "case-mode");
    }

    @Test
    void rejectsManifestIdentityContentAndActiveCountDrift(@TempDir Path tempDir)
            throws Exception {
        Fixture contentFixture = Fixture.create(tempDir.toRealPath().resolve("content-drift"));
        Files.writeString(contentFixture.frozenRoot().resolve("fixtures/shared.txt"), "changed");
        assertThrows(IOException.class, () -> contentFixture.verify(contentFixture.preflight()));

        Fixture identityFixture = Fixture.create(tempDir.toRealPath().resolve("manifest-identity"));
        FinalDatasetFreezeManifest changedObject = copyManifest(
                identityFixture.manifest(), identityFixture.manifest().activeCaseCount(),
                "7".repeat(64));
        FormalBenchmarkPreflight identityPreflight = new FormalBenchmarkPreflight(
                (root, publicRepo) -> new FinalDatasetFreezer.FreezeResult(
                        root, changedObject, identityFixture.freezeResult().manifestSha256()));
        assertThrows(IOException.class, () -> identityFixture.verify(identityPreflight));

        Fixture countFixture = Fixture.create(tempDir.toRealPath().resolve("active-count"));
        FinalDatasetFreezeManifest wrongCount = copyManifest(
                countFixture.manifest(), 27, countFixture.manifest().validatorTreeSha256());
        FinalDatasetFreezer.FreezeResult wrongCountFreeze =
                countFixture.replaceManifest(wrongCount);
        FormalBatchContract countBatch = countFixture.copyBatch(
                wrongCountFreeze.manifestSha256(), wrongCount.contentTreeSha256(),
                wrongCount.suiteSha256(), wrongCount.validatorTreeSha256(),
                sha256(countFixture.candidateJar()), sha256(countFixture.runnerJar()));
        Path countBatchFile = countFixture.writeBatch(countBatch, "count");
        FormalBenchmarkPreflight countPreflight = new FormalBenchmarkPreflight(
                (root, publicRepo) -> wrongCountFreeze);
        assertThrows(IOException.class, () -> countPreflight.verify(
                countFixture.frozenRoot(), countFixture.publicRepository(),
                countFixture.executableContractFile(), countBatchFile,
                countFixture.candidateJar(), countFixture.runnerJar()));
    }

    @Test
    void rejectsVerifierContractCommandDigestAndSymlinkEscape(@TempDir Path tempDir)
            throws Exception {
        Fixture commandFixture = Fixture.create(tempDir.toRealPath().resolve("verifier-command"));
        List<FinalExecutableSuiteContract.CaseContract> wrongEntry =
                new ArrayList<>(commandFixture.contract().cases());
        FinalExecutableSuiteContract.CaseContract first = wrongEntry.get(0);
        FinalExecutableSuiteContract.CaseContract second = wrongEntry.get(1);
        wrongEntry.set(0, withVerifier(
                first, second.verifierEntryPath(), second.verifierSha256(),
                second.verifierDependencyPaths(), second.verifierBundleSha256()));
        assertRejectedContract(commandFixture, commandFixture.preflight(),
                commandFixture.copyExecutable(commandFixture.manifest().suitePath(),
                        commandFixture.manifest().suiteSha256(), wrongEntry),
                "verifier-command");

        Fixture digestFixture = Fixture.create(tempDir.toRealPath().resolve("verifier-digest"));
        List<FinalExecutableSuiteContract.CaseContract> wrongDigest =
                new ArrayList<>(digestFixture.contract().cases());
        wrongDigest.set(0, withVerifier(wrongDigest.get(0),
                wrongDigest.get(0).verifierEntryPath(), "8".repeat(64)));
        assertRejectedContract(digestFixture, digestFixture.preflight(),
                digestFixture.copyExecutable(digestFixture.manifest().suitePath(),
                        digestFixture.manifest().suiteSha256(), wrongDigest),
                "verifier-digest");

        Fixture symlinkFixture = Fixture.create(tempDir.toRealPath().resolve("verifier-symlink"));
        Path verifier = symlinkFixture.frozenRoot().resolve("validators/final/case-01.sh");
        Path outside = Files.writeString(symlinkFixture.base().resolve("outside-validator.sh"),
                "#!/bin/sh\nexit 0\n");
        Files.delete(verifier);
        Files.createSymbolicLink(verifier, outside);
        assertThrows(IOException.class, () -> symlinkFixture.verify(symlinkFixture.preflight()));
    }

    @Test
    void rejectsVerifierDependencyMissingExtraBundleDigestAndContentDrift(@TempDir Path tempDir)
            throws Exception {
        Fixture fixture = Fixture.create(tempDir.toRealPath().resolve("verifier-bundle"));
        FinalExecutableSuiteContract.CaseContract first = fixture.contract().cases().get(0);

        List<FinalExecutableSuiteContract.CaseContract> missing =
                new ArrayList<>(fixture.contract().cases());
        missing.set(0, withVerifierBundle(first,
                List.of(first.verifierEntryPath()), first.verifierBundleSha256()));
        assertRejectedContract(fixture, fixture.preflight(), fixture.copyExecutable(
                fixture.manifest().suitePath(), fixture.manifest().suiteSha256(), missing),
                "verifier-bundle-missing");

        List<FinalExecutableSuiteContract.CaseContract> absentFile =
                new ArrayList<>(fixture.contract().cases());
        absentFile.set(0, withVerifierBundle(first, List.of(
                        "validators/final/case-01.missing.py",
                        first.verifierEntryPath(),
                        "validators/final/case-01.verify.py"),
                first.verifierBundleSha256()));
        assertRejectedContract(fixture, fixture.preflight(), fixture.copyExecutable(
                fixture.manifest().suitePath(), fixture.manifest().suiteSha256(), absentFile),
                "verifier-bundle-absent-file");

        List<FinalExecutableSuiteContract.CaseContract> directoryDependency =
                new ArrayList<>(fixture.contract().cases());
        directoryDependency.set(0, withVerifierBundle(first, List.of(
                        "validators/final",
                        first.verifierEntryPath(),
                        "validators/final/case-01.verify.py"),
                first.verifierBundleSha256()));
        assertRejectedContract(fixture, fixture.preflight(), fixture.copyExecutable(
                fixture.manifest().suitePath(), fixture.manifest().suiteSha256(),
                directoryDependency), "verifier-bundle-directory");

        List<String> extraPaths = new ArrayList<>(first.verifierDependencyPaths());
        extraPaths.add("validators/final/case-02.verify.py");
        List<FinalExecutableSuiteContract.CaseContract> extra =
                new ArrayList<>(fixture.contract().cases());
        extra.set(0, withVerifierBundle(first, extraPaths, first.verifierBundleSha256()));
        assertRejectedContract(fixture, fixture.preflight(), fixture.copyExecutable(
                fixture.manifest().suitePath(), fixture.manifest().suiteSha256(), extra),
                "verifier-bundle-extra");

        List<FinalExecutableSuiteContract.CaseContract> digestDrift =
                new ArrayList<>(fixture.contract().cases());
        digestDrift.set(0, withVerifierBundle(first, first.verifierDependencyPaths(),
                "8".repeat(64)));
        assertRejectedContract(fixture, fixture.preflight(), fixture.copyExecutable(
                fixture.manifest().suitePath(), fixture.manifest().suiteSha256(), digestDrift),
                "verifier-bundle-digest");

        Fixture contentDrift = Fixture.create(
                tempDir.toRealPath().resolve("verifier-dependency-content-drift"));
        Files.writeString(contentDrift.frozenRoot()
                .resolve("validators/final/case-01.verify.py"), "changed helper\n");
        assertThrows(IOException.class, () -> contentDrift.verify(contentDrift.preflight()));
    }

    @Test
    void rejectsScoringPathBytesAndSemanticBindingDrift(@TempDir Path tempDir)
            throws Exception {
        Fixture fixture = Fixture.create(tempDir.toRealPath().resolve("scoring-bindings"));
        List<String> registeredDriftPaths = List.of(
                "scoring/drift/wrong-case.json",
                "scoring/drift/wrong-verifier.json",
                "scoring/drift/wrong-assertion.json",
                "scoring/drift/wrong-gate.json");
        int suffix = 0;
        for (String path : registeredDriftPaths) {
            List<FinalExecutableSuiteContract.CaseContract> cases =
                    new ArrayList<>(fixture.contract().cases());
            cases.set(0, withScoring(cases.get(0), path,
                    sha256(fixture.frozenRoot().resolve(path))));
            assertRejectedContract(fixture, fixture.preflight(),
                    fixture.copyExecutable(fixture.manifest().suitePath(),
                            fixture.manifest().suiteSha256(), cases),
                    "scoring-semantic-" + suffix++);
        }

        List<FinalExecutableSuiteContract.CaseContract> missing =
                new ArrayList<>(fixture.contract().cases());
        missing.set(0, withScoring(missing.get(0),
                "scoring/missing.json", missing.get(0).scoringContractSha256()));
        assertRejectedContract(fixture, fixture.preflight(),
                fixture.copyExecutable(fixture.manifest().suitePath(),
                        fixture.manifest().suiteSha256(), missing),
                "scoring-missing");

        List<FinalExecutableSuiteContract.CaseContract> wrongDigest =
                new ArrayList<>(fixture.contract().cases());
        wrongDigest.set(0, withScoring(wrongDigest.get(0),
                wrongDigest.get(0).scoringContractPath(), "f".repeat(64)));
        assertRejectedContract(fixture, fixture.preflight(),
                fixture.copyExecutable(fixture.manifest().suitePath(),
                        fixture.manifest().suiteSha256(), wrongDigest),
                "scoring-digest");

        List<FinalExecutableSuiteContract.CaseContract> invalidJson =
                new ArrayList<>(fixture.contract().cases());
        invalidJson.set(0, withScoring(invalidJson.get(0), fixture.contract().blueprintPath(),
                fixture.contract().blueprintSha256()));
        assertRejectedContract(fixture, fixture.preflight(),
                fixture.copyExecutable(fixture.manifest().suitePath(),
                        fixture.manifest().suiteSha256(), invalidJson),
                "scoring-strict-json");
    }

    @Test
    void rejectsSymlinkDirectoryRelativeAndMutatedRuntimeArtifacts(@TempDir Path tempDir)
            throws Exception {
        Fixture fixture = Fixture.create(tempDir.toRealPath().resolve("runtime-artifacts"));
        Path candidateLink = fixture.base().resolve("candidate-link.jar");
        Files.createSymbolicLink(candidateLink, fixture.candidateJar());
        assertThrows(IOException.class, () -> fixture.preflight().verify(
                fixture.frozenRoot(), fixture.publicRepository(), fixture.executableContractFile(),
                fixture.batchContractFile(), candidateLink, fixture.runnerJar()));

        Path runnerLink = fixture.base().resolve("runner-link.jar");
        Files.createSymbolicLink(runnerLink, fixture.runnerJar());
        assertThrows(IOException.class, () -> fixture.preflight().verify(
                fixture.frozenRoot(), fixture.publicRepository(), fixture.executableContractFile(),
                fixture.batchContractFile(), fixture.candidateJar(), runnerLink));

        Path fakeRunner = Files.writeString(fixture.base().resolve("fake-runner.jar"),
                "not a trusted runner jar\n").toRealPath();
        FormalBatchContract fakeRunnerBatch = fixture.copyBatch(
                fixture.freezeResult().manifestSha256(), fixture.manifest().contentTreeSha256(),
                fixture.manifest().suiteSha256(), fixture.manifest().validatorTreeSha256(),
                sha256(fixture.candidateJar()), sha256(fakeRunner));
        Path fakeRunnerBatchFile = fixture.writeBatch(fakeRunnerBatch, "fake-runner");
        assertThrows(IOException.class, () -> fixture.preflight().verify(
                fixture.frozenRoot(), fixture.publicRepository(), fixture.executableContractFile(),
                fakeRunnerBatchFile, fixture.candidateJar(), fakeRunner));

        Path directoryJar = Files.createDirectories(fixture.base().resolve("directory.jar"));
        assertThrows(IOException.class, () -> fixture.preflight().verify(
                fixture.frozenRoot(), fixture.publicRepository(), fixture.executableContractFile(),
                fixture.batchContractFile(), directoryJar, fixture.runnerJar()));

        assertThrows(IllegalArgumentException.class, () -> fixture.preflight().verify(
                Path.of("relative-freeze"), fixture.publicRepository(),
                fixture.executableContractFile(), fixture.batchContractFile(),
                fixture.candidateJar(), fixture.runnerJar()));

        Files.writeString(fixture.dockerExecutable(), "# mutation\n",
                java.nio.file.StandardOpenOption.APPEND);
        assertThrows(IOException.class, () -> fixture.verify(fixture.preflight()));
    }

    @Test
    void stableFileGuardRehashesEvenWhenSizeAndMtimeAreRestored(@TempDir Path tempDir)
            throws Exception {
        Path file = Files.writeString(tempDir.toRealPath().resolve("stable.bin"), "alpha");
        FileTime originalMtime = Files.getLastModifiedTime(file);
        Class<?> guardType = Class.forName(
                FormalBenchmarkPreflight.class.getName() + "$StableFile");
        var capture = guardType.getDeclaredMethod("capture", Path.class, String.class);
        capture.setAccessible(true);
        Object guard = capture.invoke(null, file, "stable test file");

        Files.writeString(file, "bravo");
        Files.setLastModifiedTime(file, originalMtime);
        assertEquals(5L, Files.size(file));
        assertEquals(originalMtime, Files.getLastModifiedTime(file));

        var verify = guardType.getDeclaredMethod("verifyUnchanged");
        verify.setAccessible(true);
        InvocationTargetException error = assertThrows(
                InvocationTargetException.class, () -> verify.invoke(guard));
        assertTrue(error.getCause() instanceof IOException);
        assertTrue(error.getCause().getMessage().contains("changed during preflight"));
    }

    private static void assertRejectedContract(Fixture fixture,
                                               FormalBenchmarkPreflight preflight,
                                               FinalExecutableSuiteContract contract,
                                               String suffix) throws Exception {
        Path executableFile = fixture.writeExecutable(contract, suffix);
        FormalBatchContract batch = fixture.copyBatchForExecutable(executableFile);
        Path batchFile = fixture.writeBatch(batch, suffix);
        assertThrows(IOException.class, () -> preflight.verify(
                fixture.frozenRoot(), fixture.publicRepository(), executableFile, batchFile,
                fixture.candidateJar(), fixture.runnerJar()));
    }

    private static FinalExecutableSuiteContract.CaseContract withWeight(
            FinalExecutableSuiteContract.CaseContract source,
            int weight) {
        return copyCase(source, source.level(), weight, source.mode(),
                source.verifierEntryPath(), source.verifierSha256());
    }

    private static FinalExecutableSuiteContract.CaseContract withLevel(
            FinalExecutableSuiteContract.CaseContract source,
            FinalExecutableSuiteContract.Level level) {
        return copyCase(source, level, source.weight(), source.mode(),
                source.verifierEntryPath(), source.verifierSha256());
    }

    private static FinalExecutableSuiteContract.CaseContract withMode(
            FinalExecutableSuiteContract.CaseContract source,
            FinalExecutableSuiteContract.Mode mode) {
        return copyCase(source, source.level(), source.weight(), mode,
                source.verifierEntryPath(), source.verifierSha256());
    }

    private static FinalExecutableSuiteContract.CaseContract withVerifier(
            FinalExecutableSuiteContract.CaseContract source,
            String path,
            String digest) {
        return withVerifier(
                source, path, digest, source.verifierDependencyPaths(),
                source.verifierBundleSha256());
    }

    private static FinalExecutableSuiteContract.CaseContract withVerifier(
            FinalExecutableSuiteContract.CaseContract source,
            String path,
            String digest,
            List<String> dependencyPaths,
            String bundleSha256) {
        return copyCase(
                source, source.level(), source.weight(), source.mode(), path, digest,
                dependencyPaths, bundleSha256);
    }

    private static FinalExecutableSuiteContract.CaseContract withScoring(
            FinalExecutableSuiteContract.CaseContract source,
            String path,
            String digest) {
        return new FinalExecutableSuiteContract.CaseContract(
                source.id(), source.category(), source.level(), source.weight(), source.mode(),
                source.toolProfile(), source.timeoutSeconds(), source.tokenBudget(),
                source.hardMaxIterations(), source.stagnationWindow(), source.mockProfile(),
                source.episodeEvents(), source.mandatoryAssertionIds(), source.hardGateIds(),
                source.evidenceRequirements(), path, digest, source.verifierEntryPath(),
                source.verifierSha256(), source.verifierDependencyPaths(),
                source.verifierBundleSha256());
    }

    private static FinalExecutableSuiteContract.CaseContract copyCase(
            FinalExecutableSuiteContract.CaseContract source,
            FinalExecutableSuiteContract.Level level,
            int weight,
            FinalExecutableSuiteContract.Mode mode,
            String verifierPath,
            String verifierDigest) {
        return new FinalExecutableSuiteContract.CaseContract(
                source.id(), source.category(), level, weight, mode, source.toolProfile(),
                source.timeoutSeconds(), source.tokenBudget(), source.hardMaxIterations(),
                source.stagnationWindow(), source.mockProfile(), source.episodeEvents(),
                source.mandatoryAssertionIds(), source.hardGateIds(), source.evidenceRequirements(),
                source.scoringContractPath(), source.scoringContractSha256(),
                verifierPath, verifierDigest,
                source.verifierDependencyPaths(), source.verifierBundleSha256());
    }

    private static FinalExecutableSuiteContract.CaseContract copyCase(
            FinalExecutableSuiteContract.CaseContract source,
            FinalExecutableSuiteContract.Level level,
            int weight,
            FinalExecutableSuiteContract.Mode mode,
            String verifierPath,
            String verifierDigest,
            List<String> verifierDependencies,
            String verifierBundleSha256) {
        return new FinalExecutableSuiteContract.CaseContract(
                source.id(), source.category(), level, weight, mode, source.toolProfile(),
                source.timeoutSeconds(), source.tokenBudget(), source.hardMaxIterations(),
                source.stagnationWindow(), source.mockProfile(), source.episodeEvents(),
                source.mandatoryAssertionIds(), source.hardGateIds(), source.evidenceRequirements(),
                source.scoringContractPath(), source.scoringContractSha256(),
                verifierPath, verifierDigest, verifierDependencies, verifierBundleSha256);
    }

    private static FinalExecutableSuiteContract.CaseContract withVerifierBundle(
            FinalExecutableSuiteContract.CaseContract source,
            List<String> dependencyPaths,
            String bundleSha256) {
        return new FinalExecutableSuiteContract.CaseContract(
                source.id(), source.category(), source.level(), source.weight(), source.mode(),
                source.toolProfile(), source.timeoutSeconds(), source.tokenBudget(),
                source.hardMaxIterations(), source.stagnationWindow(), source.mockProfile(),
                source.episodeEvents(), source.mandatoryAssertionIds(), source.hardGateIds(),
                source.evidenceRequirements(), source.scoringContractPath(),
                source.scoringContractSha256(), source.verifierEntryPath(),
                source.verifierSha256(), dependencyPaths, bundleSha256);
    }

    private static FinalDatasetFreezeManifest copyManifest(
            FinalDatasetFreezeManifest source,
            int activeCount,
            String validatorDigest) {
        return new FinalDatasetFreezeManifest(
                source.manifestVersion(), source.format(), source.createdAtUtc(), source.suitePath(),
                source.suiteSha256(), source.suiteVersion(), source.caseCount(), activeCount,
                source.validatorRoot(), validatorDigest, source.canaryPath(), source.canarySha256(),
                source.contentTreeSha256(), source.totalBytes(), source.files());
    }

    static record Fixture(
            Path base,
            Path frozenRoot,
            Path publicRepository,
            Path candidateJar,
            Path runnerJar,
            Path dockerExecutable,
            Path executableContractFile,
            Path batchContractFile,
            FinalDatasetFreezeManifest manifest,
            FinalDatasetFreezer.FreezeResult freezeResult,
            FinalExecutableSuiteContract contract,
            FormalBatchContract batch) {
        static Fixture create(Path requestedBase) throws Exception {
            return create(requestedBase, null);
        }

        static Fixture create(Path requestedBase, Path generatedD1Source) throws Exception {
            return create(requestedBase, generatedD1Source, "D1");
        }

        static Fixture create(Path requestedBase, Path generatedSource, String generatedId) throws Exception {
            return create(requestedBase, generatedSource, generatedId, false);
        }

        static Fixture createTwoModels(Path requestedBase) throws Exception {
            return create(requestedBase, null, "D1", true);
        }

        private static Fixture create(Path requestedBase, Path generatedSource, String generatedId,
                                      boolean twoModels) throws Exception {
            Path base = Files.createDirectories(requestedBase).toRealPath();
            Path frozenRoot = Files.createDirectories(base.resolve("private/frozen")).toRealPath();
            Path publicRepository = Files.createDirectories(base.resolve("public/repository")).toRealPath();
            Path fixtures = Files.createDirectories(frozenRoot.resolve("fixtures"));
            Path validators = Files.createDirectories(frozenRoot.resolve("validators/final"));
            Path scoringContracts = Files.createDirectories(frozenRoot.resolve("scoring"));
            Files.writeString(fixtures.resolve("shared.txt"), "stable fixture\n");
            Files.writeString(frozenRoot.resolve(FinalDatasetFreezer.CANARY_FILE),
                    "PAICLI-FINAL-CANARY-v1:" + "c".repeat(64));
            Files.writeString(frozenRoot.resolve("blueprint.json"),
                    "{\"id\":\"formal-suite-blueprint\",\"version\":1}\n");

            List<CaseDefinition> suiteCases = new ArrayList<>();
            for (int index = 1; index <= 28; index++) {
                String id = "case-%02d".formatted(index);
                Path verifier = validators.resolve(id + ".sh");
                Files.writeString(verifier, "#!/bin/sh\n# " + id + "\nexit 0\n");
                makeExecutable(verifier);
                Files.writeString(validators.resolve(id + ".verify.py"),
                        "# frozen helper for " + id + "\n");
                Files.writeString(scoringContracts.resolve(id + ".json"),
                        scoringContractJson(id, sha256(verifier),
                                id + ".assertion", id + ".hard_gate"));
                suiteCases.add(new CaseDefinition(
                        id,
                        "Case " + index,
                        category(index),
                        caseLevel(index),
                        // Test-only L3 placeholders: 3+3 becomes 4+2 so F4 retains weight 4.
                        // The level total stays 24 and suite total stays 100; no real recipe changes.
                        generatedSource != null && "F4".equals(generatedId) && index == 22 ? 4
                                : generatedSource != null && "F4".equals(generatedId) && index == 23 ? 2 : caseWeight(index),
                        caseMode(index),
                        index == 2 ? "fixtures" : "fixtures/shared.txt",
                        "Complete the registered task for " + id,
                        CaseDefinition.VerifierType.COMMAND,
                        List.of("validators/final/" + id + ".sh", "{workspace}", "{evidence}"),
                        CaseDefinition.Status.ACTIVE));
            }
            Path scoringDrift = Files.createDirectories(scoringContracts.resolve("drift"));
            String firstVerifierSha = sha256(validators.resolve("case-01.sh"));
            Files.writeString(scoringDrift.resolve("wrong-case.json"),
                    scoringContractJson("other-case", firstVerifierSha,
                            "case-01.assertion", "case-01.hard_gate"));
            Files.writeString(scoringDrift.resolve("wrong-verifier.json"),
                    scoringContractJson("case-01", "e".repeat(64),
                            "case-01.assertion", "case-01.hard_gate"));
            Files.writeString(scoringDrift.resolve("wrong-assertion.json"),
                    scoringContractJson("case-01", firstVerifierSha,
                            "case-01.other_assertion", "case-01.hard_gate"));
            Files.writeString(scoringDrift.resolve("wrong-gate.json"),
                    scoringContractJson("case-01", firstVerifierSha,
                            "case-01.assertion", "case-01.other_hard_gate"));
            FinalExecutableSuiteContract.CaseContract generatedCase = null;
            if (generatedSource != null) {
                generatedCase = FinalExecutableSuiteContract.CaseContract.load(generatedSource.resolve(
                        "provenance/final/" + generatedId + "/execution-contract.json"));
                List<String> copies = new ArrayList<>(generatedCase.verifierDependencyPaths());
                for (String name : "E1".equals(generatedId) ? List.of("left.csv", "right.csv")
                        : "F2".equals(generatedId) ? List.of("README.md", "runbook.md", "health.json", "diagnose.py", "archive/sentinel.txt")
                        : "F3".equals(generatedId) ? List.of("README.md", "inputs/service.json", "inputs/events.jsonl", "config/credentials.json", "private/runtime.env")
                        : "F1".equals(generatedId) ? List.of("README.md", "payload.txt") : List.of("README.md"))
                    copies.add("fixtures/final/" + generatedId + "/" + name);
                for (String relative : copies) {
                    Path target = frozenRoot.resolve(relative);
                    Files.createDirectories(target.getParent());
                    Files.copy(generatedSource.resolve(relative), target);
                    if (relative.equals(generatedCase.verifierEntryPath())) makeExecutable(target);
                }
                // Replace a synthetic case in the same difficulty/weight bucket, then keep the real control first.
                int replacement = -1;
                for (int i = 0; i < suiteCases.size(); i++) {
                    var item = suiteCases.get(i);
                    if (item.level().name().equals(generatedCase.level().name()) && item.weight() == generatedCase.weight()) {
                        replacement = i; break;
                    }
                }
                if (replacement < 0) throw new IllegalArgumentException("no matching synthetic control bucket");
                java.util.Collections.swap(suiteCases, 0, replacement);
                suiteCases.set(0, new CaseDefinition(generatedId, "Generated " + generatedId, generatedCase.category(),
                        CaseDefinition.Level.valueOf(generatedCase.level().name()), generatedCase.weight(), CaseDefinition.Mode.valueOf(generatedCase.mode().name()), "fixtures/final/" + generatedId,
                        Files.readString(generatedSource.resolve("prompts/final/" + generatedId + ".md")),
                        CaseDefinition.VerifierType.COMMAND,
                        List.of(generatedCase.verifierEntryPath(), "{workspace}", "{evidence}"), CaseDefinition.Status.ACTIVE));
            }
            Path suiteFile = frozenRoot.resolve("suite.json");
            FormalContractSupport.MAPPER.writeValue(suiteFile.toFile(),
                    new SuiteDefinition("v1.0.0", "formal-suite", suiteCases, null));

            List<FinalDatasetFreezeManifest.FileEntry> entries = collectEntries(frozenRoot);
            String validatorDigest = digestEntries(entries.stream()
                    .filter(entry -> entry.path().startsWith("validators/"))
                    .toList());
            FinalDatasetFreezeManifest.FileEntry suiteEntry = entries.stream()
                    .filter(entry -> entry.path().equals("suite.json"))
                    .findFirst().orElseThrow();
            FinalDatasetFreezeManifest.FileEntry canaryEntry = entries.stream()
                    .filter(entry -> entry.path().equals(FinalDatasetFreezer.CANARY_FILE))
                    .findFirst().orElseThrow();
            FinalDatasetFreezeManifest manifest = new FinalDatasetFreezeManifest(
                    FinalDatasetFreezeManifest.CURRENT_VERSION,
                    FinalDatasetFreezeManifest.FORMAT,
                    Instant.parse("2026-08-31T00:00:00Z").toString(),
                    "suite.json",
                    suiteEntry.sha256(),
                    "v1.0.0",
                    28,
                    28,
                    "validators",
                    validatorDigest,
                    FinalDatasetFreezer.CANARY_FILE,
                    canaryEntry.sha256(),
                    digestEntries(entries),
                    entries.stream().mapToLong(FinalDatasetFreezeManifest.FileEntry::size).sum(),
                    entries);
            Path manifestFile = frozenRoot.resolve(FinalDatasetFreezer.MANIFEST_FILE);
            FormalContractSupport.MAPPER.writeValue(manifestFile.toFile(), manifest);
            String manifestSha = sha256(manifestFile);
            FinalDatasetFreezer.FreezeResult freezeResult =
                    new FinalDatasetFreezer.FreezeResult(frozenRoot, manifest, manifestSha);

            FinalExecutableSuiteContract contract = executableContract(manifest, suiteCases,
                    frozenRoot, generatedCase);
            Path executableContractFile = base.resolve("executable-suite.json");
            contract.write(executableContractFile);

            Path candidateJar = Files.writeString(base.resolve("candidate.jar"), "candidate-v1\n")
                    .toRealPath();
            Path runnerJar = writeSyntheticRunnerJar(base.resolve("runner.jar")).toRealPath();
            Path docker = Files.writeString(base.resolve("docker-formal"),
                    "#!/bin/sh\nexit 0\n");
            makeExecutable(docker);
            docker = docker.toRealPath();

            FormalBatchContract batch = batchContract(
                    executableContractFile, manifest, manifestSha,
                    candidateJar, runnerJar, docker, twoModels);
            Path batchContractFile = base.resolve("formal-batch.json");
            batch.write(batchContractFile);
            return new Fixture(base, frozenRoot, publicRepository, candidateJar, runnerJar,
                    docker, executableContractFile.toRealPath(), batchContractFile.toRealPath(),
                    manifest, freezeResult, contract, batch);
        }

        FormalBenchmarkPreflight preflight() {
            return new FormalBenchmarkPreflight((root, publicRepo) -> freezeResult);
        }

        FormalBenchmarkPreflight.VerifiedPreflight verify(
                FormalBenchmarkPreflight preflight) throws IOException {
            return preflight.verify(frozenRoot, publicRepository, executableContractFile,
                    batchContractFile, candidateJar, runnerJar);
        }

        private Path writeExecutable(FinalExecutableSuiteContract value, String suffix)
                throws IOException {
            Path file = base.resolve("executable-" + suffix + ".json");
            value.write(file);
            return file.toRealPath();
        }

        private Path writeBatch(FormalBatchContract value, String suffix) throws IOException {
            Path file = base.resolve("batch-" + suffix + ".json");
            value.write(file);
            return file.toRealPath();
        }

        private FinalExecutableSuiteContract copyExecutable(
                String suitePath,
                String suiteSha,
                List<FinalExecutableSuiteContract.CaseContract> cases) {
            return new FinalExecutableSuiteContract(
                    contract.contractVersion(), contract.format(), contract.suiteId(),
                    contract.suiteVersion(), contract.blueprintPath(), contract.blueprintSha256(),
                    suitePath, suiteSha, cases);
        }

        private FinalExecutableSuiteContract copyExecutableWithBlueprint(
                String blueprintPath,
                String blueprintSha256) {
            return new FinalExecutableSuiteContract(
                    contract.contractVersion(), contract.format(), contract.suiteId(),
                    contract.suiteVersion(), blueprintPath, blueprintSha256,
                    contract.suitePath(), contract.suiteSha256(), contract.cases());
        }

        private FormalBatchContract copyBatchForExecutable(Path executableFile) throws IOException {
            FormalBatchContract value = copyBatch(
                    freezeResult.manifestSha256(), manifest.contentTreeSha256(),
                    manifest.suiteSha256(), manifest.validatorTreeSha256(),
                    sha256(candidateJar), sha256(runnerJar));
            return new FormalBatchContract(
                    value.contractVersion(), value.format(), sha256(executableFile),
                    value.freezeManifestSha256(), value.contentTreeSha256(), value.suiteSha256(),
                    value.validatorTreeSha256(), value.candidateJarSha256(), value.candidateCommit(),
                    value.runnerJarSha256(), value.runnerInventorySha256(), value.runnerCommit(),
                    value.workerImageId(),
                    value.verifierImageId(), value.dockerExecutablePath(),
                    value.dockerExecutableSha256(), value.models(), value.repeats(),
                    FinalExecutableSuiteContract.load(executableFile).orderedCaseIds(),
                    value.timezone(), value.runtimeDate(), value.commonContextCapTokens(),
                    value.maxOutputTokensPerCall(),
                    value.invalidRunPolicy(), value.bestOfN(), value.dirty());
        }

        private FormalBatchContract copyBatch(
                String manifestSha,
                String contentSha,
                String suiteSha,
                String validatorSha,
                String candidateSha,
                String runnerSha) {
            return new FormalBatchContract(
                    batch.contractVersion(), batch.format(), batch.executableSuiteContractSha256(),
                    manifestSha, contentSha, suiteSha, validatorSha, candidateSha,
                    batch.candidateCommit(), runnerSha, batch.runnerInventorySha256(),
                    batch.runnerCommit(), batch.workerImageId(),
                    batch.verifierImageId(), batch.dockerExecutablePath(),
                    batch.dockerExecutableSha256(), batch.models(), batch.repeats(),
                    batch.caseOrder(), batch.timezone(), batch.runtimeDate(),
                    batch.commonContextCapTokens(), batch.maxOutputTokensPerCall(),
                    batch.invalidRunPolicy(), batch.bestOfN(),
                    batch.dirty());
        }

        private FormalBatchContract copyBatchWithRunnerInventory(String runnerInventorySha256) {
            return new FormalBatchContract(
                    batch.contractVersion(), batch.format(), batch.executableSuiteContractSha256(),
                    batch.freezeManifestSha256(), batch.contentTreeSha256(), batch.suiteSha256(),
                    batch.validatorTreeSha256(), batch.candidateJarSha256(), batch.candidateCommit(),
                    batch.runnerJarSha256(), runnerInventorySha256, batch.runnerCommit(),
                    batch.workerImageId(), batch.verifierImageId(), batch.dockerExecutablePath(),
                    batch.dockerExecutableSha256(), batch.models(), batch.repeats(),
                    batch.caseOrder(), batch.timezone(), batch.runtimeDate(),
                    batch.commonContextCapTokens(), batch.maxOutputTokensPerCall(),
                    batch.invalidRunPolicy(), batch.bestOfN(),
                    batch.dirty());
        }

        private FinalDatasetFreezer.FreezeResult replaceManifest(
                FinalDatasetFreezeManifest replacement) throws IOException {
            Path file = frozenRoot.resolve(FinalDatasetFreezer.MANIFEST_FILE);
            FormalContractSupport.MAPPER.writeValue(file.toFile(), replacement);
            return new FinalDatasetFreezer.FreezeResult(
                    frozenRoot, replacement, sha256(file));
        }
    }

    private static FinalExecutableSuiteContract executableContract(
            FinalDatasetFreezeManifest manifest,
            List<CaseDefinition> definitions,
            Path root, FinalExecutableSuiteContract.CaseContract generatedCase) throws IOException {
        List<FinalExecutableSuiteContract.CaseContract> cases = new ArrayList<>();
        for (CaseDefinition definition : definitions) {
            if (generatedCase != null && generatedCase.id().equals(definition.id())) {
                cases.add(generatedCase);
                continue;
            }
            String verifierPath = definition.verifierCommand().get(0);
            List<String> verifierDependencies = List.of(
                    verifierPath,
                    "validators/final/" + definition.id() + ".verify.py");
            cases.add(new FinalExecutableSuiteContract.CaseContract(
                    definition.id(),
                    definition.category(),
                    FinalExecutableSuiteContract.Level.valueOf(definition.level().name()),
                    definition.weight(),
                    FinalExecutableSuiteContract.Mode.valueOf(definition.mode().name()),
                    FinalExecutableSuiteContract.ToolProfile.READ_ONLY,
                    600,
                    100_000,
                    128,
                    8,
                    "none",
                    List.of("worker_dispatch_started", "worker_dispatch_finished",
                            "verifier_dispatch_started", "verifier_dispatch_finished"),
                    List.of(definition.id() + ".assertion"),
                    List.of(definition.id() + ".hard_gate"),
                    List.of("answer", "llm_metrics", "tool_events"),
                    "scoring/" + definition.id() + ".json",
                    sha256(root.resolve("scoring/" + definition.id() + ".json")),
                    verifierPath,
                    sha256(root.resolve(verifierPath)),
                    verifierDependencies,
                    verifierBundleSha256(manifest, verifierDependencies)));
        }
        return new FinalExecutableSuiteContract(
                FinalExecutableSuiteContract.CURRENT_VERSION,
                FinalExecutableSuiteContract.FORMAT,
                "formal-suite",
                "v1.0.0",
                "blueprint.json",
                sha256(root.resolve("blueprint.json")),
                manifest.suitePath(),
                manifest.suiteSha256(),
                cases);
    }

    private static FormalBatchContract batchContract(
            Path executableFile,
            FinalDatasetFreezeManifest manifest,
            String manifestSha,
            Path candidateJar,
            Path runnerJar,
            Path docker, boolean twoModels) throws IOException {
        FinalExecutableSuiteContract executable = FinalExecutableSuiteContract.load(executableFile);
        return new FormalBatchContract(
                twoModels ? FormalBatchContract.CURRENT_VERSION : FormalBatchContract.LEGACY_VERSION,
                twoModels ? FormalBatchContract.FORMAT : FormalBatchContract.LEGACY_FORMAT,
                sha256(executableFile),
                manifestSha,
                manifest.contentTreeSha256(),
                manifest.suiteSha256(),
                manifest.validatorTreeSha256(),
                sha256(candidateJar),
                "a".repeat(40),
                sha256(runnerJar),
                BenchmarkRunnerArtifactPolicy.inspect(runnerJar).inventorySha256(),
                "b".repeat(40),
                "sha256:" + "8".repeat(64),
                "sha256:" + "9".repeat(64),
                docker.toString(),
                sha256(docker),
                twoModels ? List.of(
                        new FormalBatchContract.ModelBinding("deepseek", "deepseek-v4-flash"),
                        new FormalBatchContract.ModelBinding("glm", "glm-5.3-flash")) : List.of(
                        new FormalBatchContract.ModelBinding("deepseek", "deepseek-v4-flash"),
                        new FormalBatchContract.ModelBinding("hunyuan", "hy4-preview"),
                        new FormalBatchContract.ModelBinding("glm", "glm-5.3-flash")),
                3,
                executable.orderedCaseIds(),
                "UTC",
                "2026-08-31",
                1_000_000,
                16_384,
                FormalBatchContract.INVALID_RUN_POLICY,
                false,
                false);
    }

    private static List<FinalDatasetFreezeManifest.FileEntry> collectEntries(Path root)
            throws IOException {
        List<FinalDatasetFreezeManifest.FileEntry> entries = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String relative = root.relativize(file).toString().replace(
                        file.getFileSystem().getSeparator(), "/");
                if (FinalDatasetFreezer.MANIFEST_FILE.equals(relative)
                        || FinalDatasetFreezer.COMPLETE_MARKER.equals(relative)) {
                    continue;
                }
                entries.add(new FinalDatasetFreezeManifest.FileEntry(
                        relative,
                        sha256(file),
                        Files.size(file),
                        Files.isExecutable(file) ? "0500" : "0400"));
            }
        }
        entries.sort(Comparator.comparing(FinalDatasetFreezeManifest.FileEntry::path));
        return List.copyOf(entries);
    }

    private static String digestEntries(List<FinalDatasetFreezeManifest.FileEntry> entries) {
        MessageDigest digest = newSha256();
        for (FinalDatasetFreezeManifest.FileEntry entry : entries) {
            update(digest, entry.path());
            digest.update((byte) 0);
            update(digest, entry.sha256());
            digest.update((byte) 0);
            update(digest, entry.mode());
            digest.update((byte) 0);
            update(digest, Long.toString(entry.size()));
            digest.update((byte) '\n');
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String verifierBundleSha256(
            FinalDatasetFreezeManifest manifest,
            List<String> dependencyPaths) {
        List<VerifierBundleIdentity.Entry> entries = dependencyPaths.stream()
                .map(path -> {
                    FinalDatasetFreezeManifest.FileEntry entry = manifest.files().stream()
                            .filter(candidate -> candidate.path().equals(path))
                            .findFirst()
                            .orElseThrow();
                    return new VerifierBundleIdentity.Entry(
                            entry.path(), entry.mode(), entry.size(), entry.sha256());
                })
                .toList();
        return VerifierBundleIdentity.digest(entries);
    }

    private static String sha256(Path file) throws IOException {
        return FormalContractSupport.sha256(file);
    }

    private static String scoringContractJson(
            String caseId,
            String verifierSha256,
            String mandatoryAssertionId,
            String hardGateId) {
        return """
                {"schemaVersion":1,"caseId":"%s","strictSuccessMinimum":80,
                 "assertions":[{"id":"%s","componentId":"deterministic","mandatory":true}],
                 "hardGates":[{"id":"%s"}],
                 "components":[{"id":"deterministic","maxPoints":100,
                                  "source":"DETERMINISTIC"}],
                 "verifierSha256":"%s","toolchainSha256":"%s"}
                """.formatted(caseId, mandatoryAssertionId, hardGateId,
                verifierSha256, "d".repeat(64));
    }

    private static Path writeSyntheticRunnerJar(Path file) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(
                Attributes.Name.MAIN_CLASS, BenchmarkRunnerArtifactPolicy.MAIN_CLASS);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(file), manifest)) {
            for (int index = 0; index < RUNNER_CLASSES.size(); index++) {
                jar.putNextEntry(new JarEntry(RUNNER_CLASSES.get(index)));
                jar.write(new byte[]{(byte) index, (byte) (index + 1)});
                jar.closeEntry();
            }
        }
        return file.toAbsolutePath().normalize();
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void makeExecutable(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            if (!path.toFile().setExecutable(true, true)) {
                throw new IOException("cannot make test file executable");
            }
        }
    }

    private static String category(int index) {
        return "category-%02d".formatted((index - 1) % 7 + 1);
    }

    private static CaseDefinition.Level caseLevel(int index) {
        if (index <= 10) {
            return CaseDefinition.Level.L1;
        }
        if (index <= 19) {
            return CaseDefinition.Level.L2;
        }
        return CaseDefinition.Level.L3;
    }

    private static int caseWeight(int index) {
        if (index <= 19) {
            return 4;
        }
        return index <= 25 ? 3 : 2;
    }

    private static CaseDefinition.Mode caseMode(int index) {
        if (index == 20) {
            return CaseDefinition.Mode.PLAN;
        }
        if (index == 21) {
            return CaseDefinition.Mode.TEAM;
        }
        return CaseDefinition.Mode.REACT;
    }
}
