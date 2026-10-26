package com.paicli.eval.benchmark.formal;

import com.paicli.eval.benchmark.BenchmarkRunnerArtifactPolicy;
import com.paicli.eval.benchmark.CaseDefinition;
import com.paicli.eval.benchmark.SuiteDefinition;
import com.paicli.eval.benchmark.finalset.FinalDatasetFreezeManifest;
import com.paicli.eval.benchmark.finalset.FinalDatasetFreezer;
import com.paicli.eval.benchmark.scoring.ScoringContract;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Read-only, fail-closed admission gate for one pre-registered formal benchmark batch.
 *
 * <p>The gate accepts only the six immutable input artifacts. Execution-affecting values such as
 * mode, tool profile, budgets, mocks, evidence requirements and verifier selection are read from
 * the executable-suite contract; there is deliberately no API for a CLI override.</p>
 */
public final class FormalBenchmarkPreflight {
    private static final int MAX_SCORING_CONTRACT_BYTES = 1024 * 1024;
    private static final Set<String> FREEZE_METADATA_FILES = Set.of(
            FinalDatasetFreezer.MANIFEST_FILE,
            FinalDatasetFreezer.COMPLETE_MARKER);

    private final FreezeVerifier freezeVerifier;

    /** Uses the production freezer verifier, including its private-tree and canary checks. */
    public FormalBenchmarkPreflight() {
        FinalDatasetFreezer freezer = new FinalDatasetFreezer();
        this.freezeVerifier = freezer::verify;
    }

    /** Package-private seam for filesystem-portable binding tests. */
    FormalBenchmarkPreflight(FreezeVerifier freezeVerifier) {
        this.freezeVerifier = Objects.requireNonNull(freezeVerifier, "freezeVerifier");
    }

    /**
     * Verifies every registered byte and path binding without changing any input artifact.
     */
    public VerifiedPreflight verify(Path frozenRoot,
                                    Path publicRepositoryRoot,
                                    Path executableSuiteContractFile,
                                    Path formalBatchContractFile,
                                    Path candidateJar,
                                    Path runnerJar) throws IOException {
        Path requestedFrozenRoot = requireCanonicalDirectory(frozenRoot, "frozenRoot");
        Path publicRepository = requireCanonicalDirectory(
                publicRepositoryRoot, "publicRepositoryRoot");
        rejectOverlap(requestedFrozenRoot, publicRepository);

        FinalDatasetFreezer.FreezeResult freeze =
                freezeVerifier.verify(requestedFrozenRoot, publicRepository);
        if (freeze == null) {
            throw new IOException("final dataset verifier returned no result");
        }
        Path verifiedFrozenRoot = requireCanonicalDirectory(
                freeze.frozenRoot(), "verified frozenRoot");
        if (!requestedFrozenRoot.equals(verifiedFrozenRoot)) {
            throw new IOException("final dataset verifier returned a different frozen root");
        }

        FinalDatasetFreezeManifest manifest = verifyFreezeResult(verifiedFrozenRoot, freeze);
        Map<String, VerifiedFile> frozenFiles = verifyFrozenContent(
                verifiedFrozenRoot, manifest);

        Path executableContractPath = requireCanonicalRegularFile(
                executableSuiteContractFile, "executableSuiteContractFile", false);
        Path batchContractPath = requireCanonicalRegularFile(
                formalBatchContractFile, "formalBatchContractFile", false);
        StableFile executableContractBytes = StableFile.capture(
                executableContractPath, "executableSuiteContractFile");
        StableFile batchContractBytes = StableFile.capture(
                batchContractPath, "formalBatchContractFile");

        FormalBatchContract batch = FormalBatchContract.load(batchContractPath);
        FinalExecutableSuiteContract executableSuite =
                batch.validateAgainst(executableContractPath);
        batchContractBytes.verifyUnchanged();
        executableContractBytes.verifyUnchanged();
        requireEqual(batch.executableSuiteContractSha256(), executableContractBytes.sha256(),
                "batch executable-suite contract digest");

        bindFreeze(batch, manifest, freeze.manifestSha256());
        bindExecutableSuite(executableSuite, manifest);
        Path blueprintFile = bindBlueprint(
                verifiedFrozenRoot, executableSuite, frozenFiles);

        Path canonicalCandidateJar = requireCanonicalRegularFile(
                candidateJar, "candidateJar", true);
        Path canonicalRunnerJar = requireCanonicalRegularFile(runnerJar, "runnerJar", true);
        StableFile candidateBytes = StableFile.capture(canonicalCandidateJar, "candidateJar");
        StableFile runnerBytes = StableFile.capture(canonicalRunnerJar, "runnerJar");
        requireEqual(batch.candidateJarSha256(), candidateBytes.sha256(),
                "candidate JAR digest");
        requireEqual(batch.runnerJarSha256(), runnerBytes.sha256(), "runner JAR digest");
        BenchmarkRunnerArtifactPolicy.Inspection runnerInspection =
                BenchmarkRunnerArtifactPolicy.inspect(canonicalRunnerJar);
        requireEqual(batch.runnerInventorySha256(), runnerInspection.inventorySha256(),
                "runner inventory digest");
        candidateBytes.verifyUnchanged();
        runnerBytes.verifyUnchanged();

        Path suiteFile = resolveRegularInside(
                verifiedFrozenRoot, executableSuite.suitePath(), "suitePath");
        StableFile suiteBytes = StableFile.capture(suiteFile, "frozen suite");
        requireEqual(executableSuite.suiteSha256(), suiteBytes.sha256(),
                "executable-suite suite digest");
        SuiteDefinition suite = SuiteDefinition.load(suiteFile);
        suiteBytes.verifyUnchanged();
        List<VerifiedCase> cases = bindCases(
                verifiedFrozenRoot, suiteFile, suite, manifest, executableSuite, frozenFiles);
        suiteBytes.verifyUnchanged();

        // FormalBatchContract construction already proves this path is canonical, executable and
        // byte-bound. Resolve and hash it once more so the returned path is verified at admission.
        Path dockerExecutable = requireCanonicalExecutable(
                Path.of(batch.dockerExecutablePath()), "dockerExecutablePath");
        StableFile dockerBytes = StableFile.capture(dockerExecutable, "dockerExecutablePath");
        requireEqual(batch.dockerExecutableSha256(), dockerBytes.sha256(),
                "Docker executable digest");
        dockerBytes.verifyUnchanged();

        return new VerifiedPreflight(
                verifiedFrozenRoot,
                suiteFile,
                blueprintFile,
                executableContractPath,
                batchContractPath,
                canonicalCandidateJar,
                canonicalRunnerJar,
                dockerExecutable,
                manifest,
                executableSuite,
                batch,
                executableSuite.suiteId(),
                executableSuite.suiteVersion(),
                batchContractBytes.sha256(),
                executableContractBytes.sha256(),
                freeze.manifestSha256(),
                manifest.contentTreeSha256(),
                manifest.suiteSha256(),
                executableSuite.blueprintSha256(),
                manifest.validatorTreeSha256(),
                candidateBytes.sha256(),
                runnerBytes.sha256(),
                runnerInspection.inventorySha256(),
                batch.workerImageId(),
                batch.verifierImageId(),
                dockerBytes.sha256(),
                batch.models(),
                batch.repeats(),
                batch.runtimeDate(),
                batch.commonContextCapTokens(),
                batch.maxOutputTokensPerCall(),
                cases);
    }

    private static FinalDatasetFreezeManifest verifyFreezeResult(
            Path root,
            FinalDatasetFreezer.FreezeResult freeze) throws IOException {
        Path manifestFile = resolveRegularInside(
                root, FinalDatasetFreezer.MANIFEST_FILE, "freeze manifest");
        StableFile manifestBytes = StableFile.capture(manifestFile, "freeze manifest");
        requireEqual(freeze.manifestSha256(), manifestBytes.sha256(),
                "freeze manifest digest");
        FinalDatasetFreezeManifest diskManifest = FormalContractSupport.load(
                manifestFile, FinalDatasetFreezeManifest.class);
        manifestBytes.verifyUnchanged();
        if (!diskManifest.equals(freeze.manifest())) {
            throw new IOException("freeze verifier manifest differs from manifest bytes");
        }
        return diskManifest;
    }

    private static Map<String, VerifiedFile> verifyFrozenContent(
            Path root,
            FinalDatasetFreezeManifest manifest) throws IOException {
        Map<String, FinalDatasetFreezeManifest.FileEntry> declared = new HashMap<>();
        for (FinalDatasetFreezeManifest.FileEntry entry : manifest.files()) {
            declared.put(entry.path(), entry);
        }

        List<String> actualPaths = new ArrayList<>();
        Map<String, VerifiedFile> verified = new HashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path node : walk.toList()) {
                if (node.equals(root)) {
                    continue;
                }
                if (Files.isSymbolicLink(node)) {
                    throw new IOException("frozen dataset contains a symbolic link");
                }
                if (Files.isDirectory(node, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (!Files.isRegularFile(node, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("frozen dataset contains a non-regular entry");
                }
                String relative = portable(root.relativize(node));
                if (FREEZE_METADATA_FILES.contains(relative)) {
                    continue;
                }
                actualPaths.add(relative);
                FinalDatasetFreezeManifest.FileEntry expected = declared.get(relative);
                if (expected == null) {
                    throw new IOException("frozen dataset contains an unregistered file: " + relative);
                }
                Path canonical = requireCanonicalRegularFile(node, "frozen file " + relative, false);
                StableFile bytes = StableFile.capture(canonical, "frozen file " + relative);
                String actualMode = Files.isExecutable(canonical) ? "0500" : "0400";
                requireEqual(expected.sha256(), bytes.sha256(),
                        "frozen file digest for " + relative);
                if (expected.size() != bytes.size()) {
                    throw new IOException("frozen file size differs from manifest: " + relative);
                }
                requireEqual(expected.mode(), actualMode,
                        "frozen executable mode for " + relative);
                bytes.verifyUnchanged();
                verified.put(relative, new VerifiedFile(canonical, expected));
            }
        }

        actualPaths.sort(String::compareTo);
        List<String> declaredPaths = manifest.files().stream()
                .map(FinalDatasetFreezeManifest.FileEntry::path)
                .toList();
        if (!actualPaths.equals(declaredPaths)) {
            throw new IOException("frozen dataset file membership differs from manifest");
        }
        requireEqual(manifest.contentTreeSha256(), digestEntries(manifest.files()),
                "frozen content tree digest");

        String validatorPrefix = manifest.validatorRoot() + "/";
        List<FinalDatasetFreezeManifest.FileEntry> validatorEntries = manifest.files().stream()
                .filter(entry -> entry.path().startsWith(validatorPrefix))
                .toList();
        if (validatorEntries.isEmpty()) {
            throw new IOException("frozen validator tree is empty");
        }
        requireEqual(manifest.validatorTreeSha256(), digestEntries(validatorEntries),
                "frozen validator tree digest");
        resolveDirectoryInside(root, manifest.validatorRoot(), "validatorRoot");

        VerifiedFile suite = verified.get(manifest.suitePath());
        if (suite == null) {
            throw new IOException("frozen suite file is absent from manifest content");
        }
        requireEqual(manifest.suiteSha256(), suite.entry().sha256(),
                "freeze suite digest");
        return Map.copyOf(verified);
    }

    private static void bindFreeze(FormalBatchContract batch,
                                   FinalDatasetFreezeManifest manifest,
                                   String manifestSha256) throws IOException {
        requireEqual(batch.freezeManifestSha256(), manifestSha256,
                "batch freeze manifest digest");
        requireEqual(batch.contentTreeSha256(), manifest.contentTreeSha256(),
                "batch frozen content digest");
        requireEqual(batch.suiteSha256(), manifest.suiteSha256(),
                "batch frozen suite digest");
        requireEqual(batch.validatorTreeSha256(), manifest.validatorTreeSha256(),
                "batch frozen validator digest");
    }

    private static void bindExecutableSuite(FinalExecutableSuiteContract executableSuite,
                                            FinalDatasetFreezeManifest manifest)
            throws IOException {
        requireEqual(executableSuite.suitePath(), manifest.suitePath(),
                "executable-suite suitePath");
        requireEqual(executableSuite.suiteSha256(), manifest.suiteSha256(),
                "executable-suite suite digest");
        requireEqual(executableSuite.suiteVersion(), manifest.suiteVersion(),
                "executable-suite suite version");
        if (manifest.activeCaseCount() != FinalExecutableSuiteContract.REQUIRED_CASE_COUNT) {
            throw new IOException("frozen suite must contain exactly 28 active cases");
        }
    }

    private static Path bindBlueprint(
            Path root,
            FinalExecutableSuiteContract executableSuite,
            Map<String, VerifiedFile> frozenFiles) throws IOException {
        Path blueprintFile = resolveRegularInside(
                root, executableSuite.blueprintPath(), "blueprintPath");
        VerifiedFile frozenBlueprint = frozenFiles.get(executableSuite.blueprintPath());
        if (frozenBlueprint == null || !frozenBlueprint.canonicalPath().equals(blueprintFile)) {
            throw new IOException("blueprintPath is absent from the freeze manifest");
        }
        requireEqual(executableSuite.blueprintSha256(), frozenBlueprint.entry().sha256(),
                "blueprint manifest digest");
        StableFile blueprintBytes = StableFile.capture(blueprintFile, "frozen blueprint");
        requireEqual(executableSuite.blueprintSha256(), blueprintBytes.sha256(),
                "blueprint bytes");
        blueprintBytes.verifyUnchanged();
        return blueprintFile;
    }

    private static List<VerifiedCase> bindCases(
            Path root,
            Path suiteFile,
            SuiteDefinition suite,
            FinalDatasetFreezeManifest manifest,
            FinalExecutableSuiteContract executableSuite,
            Map<String, VerifiedFile> frozenFiles) throws IOException {
        requireEqual(executableSuite.suiteId(), suite.name(), "executable-suite suite id");
        requireEqual(executableSuite.suiteVersion(), suite.version(),
                "loaded suite version");
        if (manifest.caseCount() != suite.cases().size()
                || manifest.activeCaseCount() != suite.activeCases().size()) {
            throw new IOException("loaded suite counts differ from freeze manifest");
        }
        List<CaseDefinition> active = suite.activeCases();
        if (active.size() != FinalExecutableSuiteContract.REQUIRED_CASE_COUNT
                || executableSuite.cases().size()
                != FinalExecutableSuiteContract.REQUIRED_CASE_COUNT) {
            throw new IOException("formal suite must bind exactly 28 active cases");
        }

        Path validatorRoot = resolveDirectoryInside(
                root, manifest.validatorRoot(), "validatorRoot");
        List<VerifiedCase> verifiedCases = new ArrayList<>(active.size());
        for (int index = 0; index < active.size(); index++) {
            CaseDefinition definition = active.get(index);
            FinalExecutableSuiteContract.CaseContract contract =
                    executableSuite.cases().get(index);
            bindCaseMetadata(definition, contract, index);

            if (definition.verifierType() != CaseDefinition.VerifierType.COMMAND
                    || definition.verifierCommand().isEmpty()) {
                throw new IOException("formal case has no command verifier: " + contract.id());
            }
            VerifiedVerifierBundle verifierBundle = bindVerifierBundle(
                    root, validatorRoot, contract, frozenFiles);
            Path verifier = verifierBundle.entry().canonicalPath();
            Path commandEntry = resolveRegularInside(
                    suiteFile.getParent(), definition.verifierCommand().get(0),
                    "suite verifier command for " + contract.id());
            if (!commandEntry.equals(verifier)) {
                throw new IOException("suite verifier command differs from case contract: "
                        + contract.id());
            }
            VerifiedScoringContract scoring = bindScoringContract(
                    root, contract, frozenFiles);
            VerifiedFixture fixture = bindFixture(
                    root, suiteFile.getParent(), definition, frozenFiles);
            CaseDefinition.VerifierInvocation verifierInvocation =
                    definition.resolveVerifier(suiteFile.getParent());
            verifiedCases.add(VerifiedCase.from(
                    contract,
                    scoring,
                    definition.prompt(),
                    fixture,
                    verifierInvocation.workingDirectory(),
                    verifierInvocation.arguments(),
                    verifierBundle));
        }
        return List.copyOf(verifiedCases);
    }

    private static VerifiedVerifierBundle bindVerifierBundle(
            Path root,
            Path validatorRoot,
            FinalExecutableSuiteContract.CaseContract contract,
            Map<String, VerifiedFile> frozenFiles) throws IOException {
        List<VerifiedVerifierDependency> dependencies = new ArrayList<>(
                contract.verifierDependencyPaths().size());
        for (String registeredPath : contract.verifierDependencyPaths()) {
            String label = "verifier dependency for " + contract.id();
            Path canonical = resolveRegularInside(root, registeredPath, label);
            if (!canonical.startsWith(validatorRoot)) {
                throw new IOException("formal verifier dependency escapes validatorRoot: "
                        + contract.id());
            }
            VerifiedFile frozen = frozenFiles.get(registeredPath);
            if (frozen == null || !frozen.canonicalPath().equals(canonical)) {
                throw new IOException("formal verifier dependency is absent from the freeze manifest: "
                        + contract.id() + ": " + registeredPath);
            }
            FinalDatasetFreezeManifest.FileEntry entry = frozen.entry();
            dependencies.add(new VerifiedVerifierDependency(
                    registeredPath, canonical, entry.mode(), entry.size(), entry.sha256()));
        }
        List<VerifiedVerifierDependency> immutable = List.copyOf(dependencies);
        VerifiedVerifierDependency entry = immutable.stream()
                .filter(dependency -> dependency.frozenPath()
                        .equals(contract.verifierEntryPath()))
                .findFirst()
                .orElseThrow(() -> new IOException(
                        "formal verifier bundle omits its entry: " + contract.id()));
        if (!"0500".equals(entry.mode())) {
            throw new IOException("formal verifier entry is not an executable frozen file: "
                    + contract.id());
        }
        requireEqual(contract.verifierSha256(), entry.sha256(),
                "verifier entry digest for " + contract.id());
        String bundleSha256 = verifierBundleDigest(immutable);
        requireEqual(contract.verifierBundleSha256(), bundleSha256,
                "verifier bundle digest for " + contract.id());
        return new VerifiedVerifierBundle(entry, immutable, bundleSha256);
    }

    private static String verifierBundleDigest(
            List<VerifiedVerifierDependency> dependencies) {
        return VerifierBundleIdentity.digest(dependencies.stream()
                .map(dependency -> new VerifierBundleIdentity.Entry(
                        dependency.frozenPath(), dependency.mode(), dependency.size(),
                        dependency.sha256()))
                .toList());
    }

    private static VerifiedScoringContract bindScoringContract(
            Path root,
            FinalExecutableSuiteContract.CaseContract caseContract,
            Map<String, VerifiedFile> frozenFiles) throws IOException {
        String label = "scoringContractPath for " + caseContract.id();
        Path file = resolveRegularInside(root, caseContract.scoringContractPath(), label);
        VerifiedFile frozenScoring = frozenFiles.get(caseContract.scoringContractPath());
        if (frozenScoring == null || !frozenScoring.canonicalPath().equals(file)) {
            throw new IOException("scoring contract is absent from the freeze manifest: "
                    + caseContract.id());
        }
        if (!"0400".equals(frozenScoring.entry().mode())) {
            throw new IOException("scoring contract must be a non-executable frozen file: "
                    + caseContract.id());
        }
        requireEqual(caseContract.scoringContractSha256(), frozenScoring.entry().sha256(),
                "scoring contract manifest digest for " + caseContract.id());

        StableBytes bytes = StableBytes.capture(
                file, label, MAX_SCORING_CONTRACT_BYTES);
        requireEqual(caseContract.scoringContractSha256(), bytes.sha256(),
                "scoring contract bytes for " + caseContract.id());
        ScoringContract scoring = ScoringContract.parse(
                decodeUtf8Strict(bytes.bytes(), label));
        bytes.verifyUnchanged();

        requireEqual(caseContract.id(), scoring.caseId(),
                "scoring contract caseId for " + caseContract.id());
        requireEqual(caseContract.verifierSha256(), scoring.verifierSha256(),
                "scoring contract verifierSha256 for " + caseContract.id());
        List<String> mandatoryAssertionIds = scoring.assertions().stream()
                .filter(ScoringContract.AssertionRule::mandatory)
                .map(ScoringContract.AssertionRule::id)
                .toList();
        if (!caseContract.mandatoryAssertionIds().equals(mandatoryAssertionIds)) {
            throw new IOException("scoring contract mandatory assertion ids differ for "
                    + caseContract.id());
        }
        List<String> hardGateIds = scoring.hardGates().stream()
                .map(ScoringContract.HardGateRule::id)
                .toList();
        if (!caseContract.hardGateIds().equals(hardGateIds)) {
            throw new IOException("scoring contract hard gate ids differ for "
                    + caseContract.id());
        }
        return new VerifiedScoringContract(
                caseContract.scoringContractPath(), file, bytes.sha256(), scoring);
    }

    private static VerifiedFixture bindFixture(
            Path root,
            Path suiteDirectory,
            CaseDefinition definition,
            Map<String, VerifiedFile> frozenFiles) throws IOException {
        Path resolved = definition.resolveFixture(suiteDirectory);
        if (!resolved.startsWith(root)) {
            throw new IOException("formal case fixture escapes frozenRoot: " + definition.id());
        }

        final Path canonical;
        final FixtureKind kind;
        if (Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
            canonical = requireCanonicalRegularFile(
                    resolved, "fixture for " + definition.id(), false);
            kind = FixtureKind.FILE;
        } else if (Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) {
            canonical = requireCanonicalDirectory(resolved, "fixture for " + definition.id());
            kind = FixtureKind.DIRECTORY;
        } else {
            throw new IOException("formal case fixture is not a regular file or directory: "
                    + definition.id());
        }
        if (!canonical.startsWith(root)) {
            throw new IOException("formal case fixture resolves outside frozenRoot: "
                    + definition.id());
        }

        String frozenPath = portable(root.relativize(canonical));
        String prefix = frozenPath + "/";
        List<VerifiedFixtureFile> files = frozenFiles.entrySet().stream()
                .filter(entry -> kind == FixtureKind.FILE
                        ? entry.getKey().equals(frozenPath)
                        : entry.getKey().startsWith(prefix))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new VerifiedFixtureFile(
                        entry.getKey(),
                        entry.getValue().entry().sha256(),
                        entry.getValue().entry().size(),
                        entry.getValue().entry().mode()))
                .toList();
        if (kind == FixtureKind.FILE && files.size() != 1) {
            throw new IOException("formal case fixture file is absent from freeze manifest: "
                    + definition.id());
        }
        long totalBytes = 0L;
        for (VerifiedFixtureFile file : files) {
            totalBytes = Math.addExact(totalBytes, file.size());
        }
        return new VerifiedFixture(
                definition.fixturePath(),
                frozenPath,
                canonical,
                kind,
                digestFixture(kind, frozenPath, files),
                files.size(),
                totalBytes,
                files);
    }

    private static void bindCaseMetadata(
            CaseDefinition definition,
            FinalExecutableSuiteContract.CaseContract contract,
            int index) throws IOException {
        String label = "formal case at index " + index;
        requireEqual(contract.id(), definition.id(), label + " id");
        requireEqual(contract.category(), definition.category(), label + " category");
        if (contract.weight() != definition.weight()) {
            throw new IOException(label + " weight differs between suite and contract");
        }
        requireEqual(contract.level().name(), definition.level().name(), label + " level");
        requireEqual(contract.mode().name(), definition.mode().name(), label + " mode");
    }

    private static Path requireCanonicalDirectory(Path path, String label) throws IOException {
        Path declared = requireAbsoluteNormalized(path, label);
        if (Files.isSymbolicLink(declared)
                || !Files.isDirectory(declared, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " must be a non-symlink directory");
        }
        Path real = declared.toRealPath();
        if (!declared.equals(real)) {
            throw new IOException(label + " must be a canonical path without symlink ancestors");
        }
        return real;
    }

    private static Path requireCanonicalRegularFile(Path path,
                                                    String label,
                                                    boolean requireJar) throws IOException {
        Path declared = requireAbsoluteNormalized(path, label);
        if (Files.isSymbolicLink(declared)
                || !Files.isRegularFile(declared, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " must be a non-symlink regular file");
        }
        Path real = declared.toRealPath();
        if (!declared.equals(real)) {
            throw new IOException(label + " must be a canonical path without symlink ancestors");
        }
        if (requireJar) {
            Path name = real.getFileName();
            if (name == null || !name.toString().endsWith(".jar") || Files.size(real) <= 0) {
                throw new IOException(label + " must be a non-empty .jar file");
            }
        }
        return real;
    }

    private static Path requireCanonicalExecutable(Path path, String label) throws IOException {
        Path executable = requireCanonicalRegularFile(path, label, false);
        if (!Files.isExecutable(executable)) {
            throw new IOException(label + " must be executable");
        }
        return executable;
    }

    private static Path requireAbsoluteNormalized(Path path, String label) {
        if (path == null) {
            throw new IllegalArgumentException(label + " must not be null");
        }
        if (!path.isAbsolute() || !path.normalize().equals(path)) {
            throw new IllegalArgumentException(label + " must be an absolute normalized path");
        }
        return path;
    }

    private static Path resolveRegularInside(Path root, String relative, String label)
            throws IOException {
        Path lexical = resolveInside(root, relative, label);
        Path canonical = requireCanonicalRegularFile(lexical, label, false);
        if (!canonical.startsWith(root)) {
            throw new IOException(label + " resolves outside its trusted root");
        }
        return canonical;
    }

    private static Path resolveDirectoryInside(Path root, String relative, String label)
            throws IOException {
        Path lexical = resolveInside(root, relative, label);
        Path canonical = requireCanonicalDirectory(lexical, label);
        if (!canonical.startsWith(root)) {
            throw new IOException(label + " resolves outside its trusted root");
        }
        return canonical;
    }

    private static Path resolveInside(Path root, String relative, String label) throws IOException {
        FormalContractSupport.requireRelativePath(relative, label);
        Path resolved = root.resolve(Path.of(relative)).normalize();
        if (resolved.equals(root) || !resolved.startsWith(root)) {
            throw new IOException(label + " escapes its trusted root");
        }
        return resolved;
    }

    private static void rejectOverlap(Path frozenRoot, Path publicRepository) throws IOException {
        if (frozenRoot.startsWith(publicRepository) || publicRepository.startsWith(frozenRoot)) {
            throw new IOException("frozenRoot and publicRepositoryRoot must not overlap");
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest = newSha256();
        try (InputStream input = Files.newInputStream(
                file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] bytes) {
        MessageDigest digest = newSha256();
        digest.update(bytes);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String decodeUtf8Strict(byte[] bytes, String label) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new IOException(label + " is not valid UTF-8", error);
        }
    }

    private static String digestEntries(List<FinalDatasetFreezeManifest.FileEntry> entries) {
        MessageDigest digest = newSha256();
        for (FinalDatasetFreezeManifest.FileEntry entry : entries) {
            updateDigest(digest, entry.path());
            digest.update((byte) 0);
            updateDigest(digest, entry.sha256());
            digest.update((byte) 0);
            updateDigest(digest, entry.mode());
            digest.update((byte) 0);
            updateDigest(digest, Long.toString(entry.size()));
            digest.update((byte) '\n');
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String digestFixture(FixtureKind kind,
                                        String frozenPath,
                                        List<VerifiedFixtureFile> files) {
        MessageDigest digest = newSha256();
        updateDigest(digest, "paicli-formal-fixture-snapshot-v1");
        digest.update((byte) 0);
        updateDigest(digest, kind.name());
        digest.update((byte) 0);
        updateDigest(digest, frozenPath);
        digest.update((byte) '\n');
        for (VerifiedFixtureFile file : files) {
            updateDigest(digest, file.frozenPath());
            digest.update((byte) 0);
            updateDigest(digest, file.sha256());
            digest.update((byte) 0);
            updateDigest(digest, file.mode());
            digest.update((byte) 0);
            updateDigest(digest, Long.toString(file.size()));
            digest.update((byte) '\n');
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256Text(String value) {
        MessageDigest digest = newSha256();
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String portable(Path relative) {
        String portable = relative.toString().replace(relative.getFileSystem().getSeparator(), "/");
        FormalContractSupport.requireRelativePath(portable, "frozen file path");
        return portable;
    }

    private static void requireEqual(String expected, String actual, String label)
            throws IOException {
        if (!Objects.equals(expected, actual)) {
            throw new IOException(label + " does not match its pre-registered value");
        }
    }

    @FunctionalInterface
    interface FreezeVerifier {
        FinalDatasetFreezer.FreezeResult verify(Path frozenRoot, Path publicRepositoryRoot)
                throws IOException;
    }

    private record VerifiedFile(
            Path canonicalPath,
            FinalDatasetFreezeManifest.FileEntry entry) {
    }

    private record VerifiedScoringContract(
            String registeredPath,
            Path canonicalFile,
            String sha256,
            ScoringContract contract) {
    }

    private record VerifiedVerifierBundle(
            VerifiedVerifierDependency entry,
            List<VerifiedVerifierDependency> dependencies,
            String sha256) {
        private VerifiedVerifierBundle {
            Objects.requireNonNull(entry, "entry");
            dependencies = List.copyOf(dependencies);
            FormalContractSupport.requireSha256(sha256, "verifier bundle sha256");
        }
    }

    /** One bounded byte view plus the stable identity used to prove it was not swapped. */
    private record StableBytes(StableFile stableFile, byte[] bytes) {
        private StableBytes {
            Objects.requireNonNull(stableFile, "stableFile");
            bytes = bytes.clone();
        }

        private static StableBytes capture(Path path, String label, int maxBytes)
                throws IOException {
            if (maxBytes <= 0) {
                throw new IllegalArgumentException("maxBytes must be positive");
            }
            BasicFileAttributes before = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile() || before.fileKey() == null
                    || before.size() <= 0 || before.size() > maxBytes) {
                throw new IOException(label + " size or identity is outside the accepted bound");
            }

            byte[] captured;
            try (InputStream input = Files.newInputStream(
                    path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                 ByteArrayOutputStream output = new ByteArrayOutputStream(
                         (int) Math.min(before.size(), 16_384L))) {
                byte[] buffer = new byte[16_384];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    if (read > maxBytes - total) {
                        throw new IOException(label + " exceeds the accepted byte bound");
                    }
                    output.write(buffer, 0, read);
                    total += read;
                }
                captured = output.toByteArray();
            }

            BasicFileAttributes after = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!StableFile.same(before, after) || captured.length != before.size()) {
                throw new IOException(label + " changed during bounded byte capture");
            }
            StableFile stable = new StableFile(
                    path, before.fileKey(), before.size(), before.lastModifiedTime(),
                    FormalBenchmarkPreflight.sha256(captured));
            return new StableBytes(stable, captured);
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        private String sha256() {
            return stableFile.sha256();
        }

        private void verifyUnchanged() throws IOException {
            stableFile.verifyUnchanged();
        }
    }

    private record StableFile(
            Path path,
            Object fileKey,
            long size,
            FileTime lastModifiedTime,
            String sha256) {
        private static StableFile capture(Path path, String label) throws IOException {
            BasicFileAttributes before = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile() || before.fileKey() == null) {
                throw new IOException("cannot capture stable identity for " + label);
            }
            String digest = FormalBenchmarkPreflight.sha256(path);
            BasicFileAttributes after = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!same(before, after)) {
                throw new IOException(label + " changed while it was hashed");
            }
            return new StableFile(path, before.fileKey(), before.size(),
                    before.lastModifiedTime(), digest);
        }

        private void verifyUnchanged() throws IOException {
            if (Files.isSymbolicLink(path)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("verified file was replaced after admission");
            }
            BasicFileAttributes before = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            String currentSha256 = FormalBenchmarkPreflight.sha256(path);
            BasicFileAttributes after = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!same(before, after)
                    || !Objects.equals(fileKey, before.fileKey())
                    || size != before.size()
                    || !lastModifiedTime.equals(before.lastModifiedTime())
                    || !sha256.equals(currentSha256)) {
                throw new IOException("verified file changed during preflight");
            }
        }

        private static boolean same(BasicFileAttributes first, BasicFileAttributes second) {
            return Objects.equals(first.fileKey(), second.fileKey())
                    && first.size() == second.size()
                    && first.lastModifiedTime().equals(second.lastModifiedTime());
        }
    }

    /** Safe execution summary plus only paths that were resolved and verified canonically. */
    public record VerifiedPreflight(
            Path frozenRoot,
            Path suiteFile,
            Path blueprintFile,
            Path executableSuiteContractFile,
            Path formalBatchContractFile,
            Path candidateJar,
            Path runnerJar,
            Path dockerExecutable,
            FinalDatasetFreezeManifest freezeManifest,
            FinalExecutableSuiteContract executableSuiteContract,
            FormalBatchContract formalBatchContract,
            String suiteId,
            String suiteVersion,
            String formalBatchContractSha256,
            String executableSuiteContractSha256,
            String freezeManifestSha256,
            String contentTreeSha256,
            String suiteSha256,
            String blueprintSha256,
            String validatorTreeSha256,
            String candidateJarSha256,
            String runnerJarSha256,
            String runnerInventorySha256,
            String workerImageId,
            String verifierImageId,
            String dockerExecutableSha256,
            List<FormalBatchContract.ModelBinding> models,
            int repeats,
            String runtimeDate,
            int commonContextCapTokens,
            int maxOutputTokensPerCall,
            List<VerifiedCase> cases) {
        public VerifiedPreflight {
            Objects.requireNonNull(blueprintFile, "blueprintFile");
            Objects.requireNonNull(freezeManifest, "freezeManifest");
            Objects.requireNonNull(executableSuiteContract, "executableSuiteContract");
            Objects.requireNonNull(formalBatchContract, "formalBatchContract");
            FormalContractSupport.requireSha256(blueprintSha256, "blueprintSha256");
            models = List.copyOf(models);
            cases = List.copyOf(cases);
            if (!models.equals(formalBatchContract.models())
                    || repeats != formalBatchContract.repeats()
                    || !runtimeDate.equals(formalBatchContract.runtimeDate())
                    || commonContextCapTokens
                            != formalBatchContract.commonContextCapTokens()
                    || maxOutputTokensPerCall
                            != formalBatchContract.maxOutputTokensPerCall()) {
                throw new IllegalArgumentException(
                        "verified execution fields differ from retained batch contract");
            }
        }

        @Override
        public String toString() {
            return "VerifiedPreflight[suite=" + suiteId + "@" + suiteVersion
                    + ", batch=" + shortDigest(formalBatchContractSha256)
                    + ", freeze=" + shortDigest(freezeManifestSha256)
                    + ", blueprint=" + shortDigest(blueprintSha256)
                    + ", candidate=" + shortDigest(candidateJarSha256)
                    + ", runner=" + shortDigest(runnerJarSha256)
                    + ", runnerInventory=" + shortDigest(runnerInventorySha256)
                    + ", docker=" + shortDigest(dockerExecutableSha256)
                    + ", models=" + models + ", repeats=" + repeats
                    + ", cases=" + cases.size() + ", runtimeDate=" + runtimeDate + "]";
        }
    }

    /** Authoritative case execution selection copied only from the verified suite contract. */
    public record VerifiedCase(
            String id,
            String category,
            FinalExecutableSuiteContract.Level level,
            int weight,
            FinalExecutableSuiteContract.Mode mode,
            FinalExecutableSuiteContract.ToolProfile toolProfile,
            int timeoutSeconds,
            int tokenBudget,
            int hardMaxIterations,
            int stagnationWindow,
            String mockProfile,
            List<String> episodeEvents,
            List<String> mandatoryAssertionIds,
            List<String> hardGateIds,
            List<String> evidenceRequirements,
            ScoringContract scoringContract,
            String scoringContractPath,
            Path scoringContractFile,
            String scoringContractSha256,
            String prompt,
            String promptSha256,
            VerifiedFixture fixture,
            Path verifierWorkingDirectory,
            List<String> verifierArguments,
            String verifierEntryPath,
            Path verifierEntry,
            String verifierSha256,
            List<VerifiedVerifierDependency> verifierDependencies,
            String verifierBundleSha256) {
        public VerifiedCase {
            episodeEvents = List.copyOf(episodeEvents);
            mandatoryAssertionIds = List.copyOf(mandatoryAssertionIds);
            hardGateIds = List.copyOf(hardGateIds);
            evidenceRequirements = List.copyOf(evidenceRequirements);
            Objects.requireNonNull(scoringContract, "scoringContract");
            FormalContractSupport.requireRelativePath(
                    scoringContractPath, "scoringContractPath");
            scoringContractFile = requireAbsoluteNormalized(
                    scoringContractFile, "scoringContractFile");
            FormalContractSupport.requireSha256(
                    scoringContractSha256, "scoringContractSha256");
            if (!id.equals(scoringContract.caseId())) {
                throw new IllegalArgumentException(
                        "verified scoring contract caseId differs from case id");
            }
            List<String> scoringMandatory = scoringContract.assertions().stream()
                    .filter(ScoringContract.AssertionRule::mandatory)
                    .map(ScoringContract.AssertionRule::id)
                    .toList();
            if (!mandatoryAssertionIds.equals(scoringMandatory)) {
                throw new IllegalArgumentException(
                        "verified scoring contract mandatory assertions differ from case");
            }
            List<String> scoringHardGates = scoringContract.hardGates().stream()
                    .map(ScoringContract.HardGateRule::id)
                    .toList();
            if (!hardGateIds.equals(scoringHardGates)) {
                throw new IllegalArgumentException(
                        "verified scoring contract hard gates differ from case");
            }
            if (prompt == null || prompt.isBlank()) {
                throw new IllegalArgumentException("verified case prompt must not be blank");
            }
            FormalContractSupport.requireSha256(promptSha256, "promptSha256");
            Objects.requireNonNull(fixture, "fixture");
            Objects.requireNonNull(verifierWorkingDirectory, "verifierWorkingDirectory");
            verifierArguments = List.copyOf(verifierArguments);
            if (verifierArguments.isEmpty()) {
                throw new IllegalArgumentException("verified verifier argv must not be empty");
            }
            FormalContractSupport.requireRelativePath(
                    verifierEntryPath, "verified verifierEntryPath");
            verifierEntry = requireAbsoluteNormalized(verifierEntry, "verified verifierEntry");
            FormalContractSupport.requireSha256(verifierSha256, "verifierSha256");
            if (!verifierSha256.equals(scoringContract.verifierSha256())) {
                throw new IllegalArgumentException(
                        "verified scoring contract verifier digest differs from case");
            }
            verifierDependencies = List.copyOf(verifierDependencies);
            String previous = null;
            VerifiedVerifierDependency entryDependency = null;
            for (VerifiedVerifierDependency dependency : verifierDependencies) {
                if (previous != null
                        && previous.compareTo(dependency.frozenPath()) >= 0) {
                    throw new IllegalArgumentException(
                            "verified verifier dependencies must be unique and sorted");
                }
                if (verifierEntryPath.equals(dependency.frozenPath())) {
                    entryDependency = dependency;
                }
                previous = dependency.frozenPath();
            }
            if (entryDependency == null
                    || !entryDependency.canonicalPath().equals(verifierEntry)
                    || !entryDependency.sha256().equals(verifierSha256)
                    || !"0500".equals(entryDependency.mode())) {
                throw new IllegalArgumentException(
                        "verified verifier entry differs from dependency bundle");
            }
            FormalContractSupport.requireSha256(
                    verifierBundleSha256, "verifierBundleSha256");
            if (!verifierBundleSha256.equals(
                    verifierBundleDigest(verifierDependencies))) {
                throw new IllegalArgumentException(
                        "verified verifier bundle digest differs from dependencies");
            }
        }

        private static VerifiedCase from(FinalExecutableSuiteContract.CaseContract source,
                                         VerifiedScoringContract scoring,
                                         String prompt,
                                         VerifiedFixture fixture,
                                         Path verifierWorkingDirectory,
                                         List<String> verifierArguments,
                                         VerifiedVerifierBundle verifierBundle) {
            return new VerifiedCase(
                    source.id(), source.category(), source.level(), source.weight(), source.mode(),
                    source.toolProfile(), source.timeoutSeconds(), source.tokenBudget(),
                    source.hardMaxIterations(), source.stagnationWindow(), source.mockProfile(),
                    source.episodeEvents(), source.mandatoryAssertionIds(), source.hardGateIds(),
                    source.evidenceRequirements(), scoring.contract(), scoring.registeredPath(),
                    scoring.canonicalFile(), scoring.sha256(), prompt, sha256Text(prompt), fixture,
                    verifierWorkingDirectory, verifierArguments, source.verifierEntryPath(),
                    verifierBundle.entry().canonicalPath(), source.verifierSha256(),
                    verifierBundle.dependencies(), verifierBundle.sha256());
        }

        @Override
        public String toString() {
            return "VerifiedCase[id=" + id + ", level=" + level + ", weight=" + weight
                    + ", mode=" + mode + ", toolProfile=" + toolProfile
                    + ", scoring=" + shortDigest(scoringContractSha256)
                    + ", verifierBundle=" + shortDigest(verifierBundleSha256) + "]";
        }
    }

    public enum FixtureKind {
        FILE,
        DIRECTORY
    }

    /** One verifier dependency bound from exactly one verified freeze-manifest entry. */
    public record VerifiedVerifierDependency(
            String frozenPath,
            Path canonicalPath,
            String mode,
            long size,
            String sha256) {
        public VerifiedVerifierDependency {
            FormalContractSupport.requireRelativePath(
                    frozenPath, "verifier dependency path");
            canonicalPath = requireAbsoluteNormalized(
                    canonicalPath, "verifier dependency canonicalPath");
            if (!"0400".equals(mode) && !"0500".equals(mode)) {
                throw new IllegalArgumentException(
                        "verifier dependency mode must be 0400 or 0500");
            }
            if (size < 0) {
                throw new IllegalArgumentException(
                        "verifier dependency size must not be negative");
            }
            FormalContractSupport.requireSha256(sha256, "verifier dependency sha256");
        }
    }

    /** One freeze-manifest file contributing to a case fixture snapshot. */
    public record VerifiedFixtureFile(
            String frozenPath,
            String sha256,
            long size,
            String mode) {
        public VerifiedFixtureFile {
            FormalContractSupport.requireRelativePath(frozenPath, "fixture file path");
            FormalContractSupport.requireSha256(sha256, "fixture file sha256");
            if (size < 0) {
                throw new IllegalArgumentException("fixture file size must not be negative");
            }
            if (!"0400".equals(mode) && !"0500".equals(mode)) {
                throw new IllegalArgumentException("fixture file mode must be 0400 or 0500");
            }
        }
    }

    /** Canonical fixture source plus its complete identity in the verified freeze manifest. */
    public record VerifiedFixture(
            String declaredPath,
            String frozenPath,
            Path sourcePath,
            FixtureKind kind,
            String snapshotSha256,
            int fileCount,
            long totalBytes,
            List<VerifiedFixtureFile> files) {
        public VerifiedFixture {
            if (declaredPath == null || declaredPath.isBlank()) {
                throw new IllegalArgumentException("fixture declaredPath must not be blank");
            }
            FormalContractSupport.requireRelativePath(frozenPath, "fixture frozenPath");
            Objects.requireNonNull(sourcePath, "sourcePath");
            Objects.requireNonNull(kind, "kind");
            FormalContractSupport.requireSha256(snapshotSha256, "fixture snapshotSha256");
            if (fileCount < 0 || totalBytes < 0) {
                throw new IllegalArgumentException("fixture snapshot counts must not be negative");
            }
            files = List.copyOf(files);
            if (fileCount != files.size()) {
                throw new IllegalArgumentException("fixture fileCount differs from files");
            }
            long actualBytes = 0L;
            for (VerifiedFixtureFile file : files) {
                actualBytes = Math.addExact(actualBytes, file.size());
            }
            if (actualBytes != totalBytes) {
                throw new IllegalArgumentException("fixture totalBytes differs from files");
            }
            if (kind == FixtureKind.FILE
                    && (files.size() != 1 || !files.get(0).frozenPath().equals(frozenPath))) {
                throw new IllegalArgumentException("file fixture must bind exactly its manifest file");
            }
        }

        @Override
        public String toString() {
            return "VerifiedFixture[kind=" + kind + ", snapshot="
                    + shortDigest(snapshotSha256) + ", files=" + fileCount
                    + ", bytes=" + totalBytes + "]";
        }
    }

    private static String shortDigest(String digest) {
        return digest.substring(0, 12);
    }
}
