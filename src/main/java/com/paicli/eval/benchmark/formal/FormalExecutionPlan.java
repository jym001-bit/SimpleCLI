package com.paicli.eval.benchmark.formal;

import com.paicli.eval.benchmark.CaseDefinition;
import com.paicli.eval.benchmark.finalset.FinalDatasetFreezeManifest;
import com.paicli.eval.benchmark.scoring.ScoringContract;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, zero-I/O execution plan derived exclusively from one admitted formal preflight.
 *
 * <p>The plan preserves the three preregistered contracts verbatim and adds the suite-only values
 * captured during admission: prompt bytes, scoring policy, complete fixture snapshot identity and
 * complete verifier argv and manifest-derived verifier dependency bundle. Building this plan
 * never reloads the suite file or any other mutable path.</p>
 */
public record FormalExecutionPlan(
        int planVersion,
        String format,
        String executionOrder,
        boolean publishable,
        FinalDatasetFreezeManifest freezeManifest,
        FinalExecutableSuiteContract executableSuiteContract,
        FormalBatchContract formalBatchContract,
        ArtifactBindings artifacts,
        List<CasePlan> cases,
        List<EpisodePlan> episodes) {
    public static final int LEGACY_VERSION = 4;
    public static final String LEGACY_FORMAT = "paicli-formal-execution-plan-v4";
    public static final int CURRENT_VERSION = 5;
    public static final String FORMAT = "paicli-formal-execution-plan-v5";
    public static final String EXECUTION_ORDER = "MODEL_REPEAT_CASE";
    /** Legacy v4/v3 three-model count. Runtime validation must use the retained batch contract. */
    @Deprecated
    public static final int REQUIRED_EPISODE_COUNT =
            FinalExecutableSuiteContract.REQUIRED_CASE_COUNT * 3 * 3;

    public FormalExecutionPlan {
        if (planVersion != LEGACY_VERSION && planVersion != CURRENT_VERSION) {
            throw new IllegalArgumentException("unsupported formal execution plan version: "
                    + planVersion);
        }
        if (!(planVersion == LEGACY_VERSION ? LEGACY_FORMAT : FORMAT).equals(format)) {
            throw new IllegalArgumentException("unsupported formal execution plan format: " + format);
        }
        if (!EXECUTION_ORDER.equals(executionOrder)) {
            throw new IllegalArgumentException("formal execution order must be " + EXECUTION_ORDER);
        }
        if (publishable) {
            throw new IllegalArgumentException(
                    "an unexecuted formal plan must retain publishable=false");
        }
        Objects.requireNonNull(freezeManifest, "freezeManifest");
        Objects.requireNonNull(executableSuiteContract, "executableSuiteContract");
        Objects.requireNonNull(formalBatchContract, "formalBatchContract");
        int expectedBatchVersion = planVersion == LEGACY_VERSION
                ? FormalBatchContract.LEGACY_VERSION : FormalBatchContract.CURRENT_VERSION;
        if (formalBatchContract.contractVersion() != expectedBatchVersion) {
            throw new IllegalArgumentException("formal plan version does not match its batch contract version");
        }
        Objects.requireNonNull(artifacts, "artifacts");
        cases = List.copyOf(cases);
        episodes = List.copyOf(episodes);

        validateRegistration(
                freezeManifest, executableSuiteContract, formalBatchContract, artifacts, cases);
        List<EpisodePlan> expectedEpisodes = buildEpisodes(
                formalBatchContract.models(), formalBatchContract.repeats(), cases);
        if (!episodes.equals(expectedEpisodes)) {
            throw new IllegalArgumentException(
                    "formal episodes must exactly follow model, repeat and preregistered case order");
        }
        if (episodes.size() != formalBatchContract.expectedEpisodeCount()) {
            throw new IllegalArgumentException("formal plan must contain the full registered episode count");
        }
    }

    /**
     * Converts an admitted preflight without opening, statting or hashing any filesystem path.
     */
    public static FormalExecutionPlan from(
            FormalBenchmarkPreflight.VerifiedPreflight verified) {
        Objects.requireNonNull(verified, "verified");
        ArtifactBindings artifacts = ArtifactBindings.from(verified);
        FinalExecutableSuiteContract executable = verified.executableSuiteContract();
        FormalBatchContract batch = verified.formalBatchContract();
        if (!verified.models().equals(batch.models())
                || verified.repeats() != batch.repeats()
                || !verified.runtimeDate().equals(batch.runtimeDate())
                || verified.commonContextCapTokens() != batch.commonContextCapTokens()
                || verified.maxOutputTokensPerCall() != batch.maxOutputTokensPerCall()) {
            throw new IllegalArgumentException(
                    "verified batch execution fields differ from retained batch contract");
        }
        List<FinalExecutableSuiteContract.CaseContract> registeredCases = executable.cases();
        if (verified.cases().size() != registeredCases.size()) {
            throw new IllegalArgumentException(
                    "verified case count differs from executable-suite contract");
        }

        List<CasePlan> cases = new ArrayList<>(registeredCases.size());
        for (int index = 0; index < registeredCases.size(); index++) {
            cases.add(CasePlan.from(
                    index + 1, registeredCases.get(index), verified.cases().get(index)));
        }
        List<CasePlan> immutableCases = List.copyOf(cases);
        List<EpisodePlan> episodes = buildEpisodes(
                batch.models(),
                batch.repeats(),
                immutableCases);
        return new FormalExecutionPlan(
                batch.contractVersion() == FormalBatchContract.LEGACY_VERSION ? LEGACY_VERSION : CURRENT_VERSION,
                batch.contractVersion() == FormalBatchContract.LEGACY_VERSION ? LEGACY_FORMAT : FORMAT,
                EXECUTION_ORDER,
                false,
                verified.freezeManifest(),
                executable,
                batch,
                artifacts,
                immutableCases,
                episodes);
    }

    public CasePlan requireCase(String caseId) {
        FormalContractSupport.requireSafeIdentifier(caseId, "case id");
        return cases.stream()
                .filter(casePlan -> casePlan.id().equals(caseId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown formal execution case: " + caseId));
    }

    @Override
    public String toString() {
        return "FormalExecutionPlan[suite=" + executableSuiteContract.suiteId()
                + "@" + executableSuiteContract.suiteVersion()
                + ", batch=" + shortDigest(artifacts.formalBatchContractSha256())
                + ", order=" + executionOrder + ", cases=" + cases.size()
                + ", episodes=" + episodes.size() + ", publishable=false]";
    }

    private static void validateRegistration(
            FinalDatasetFreezeManifest freeze,
            FinalExecutableSuiteContract executable,
            FormalBatchContract batch,
            ArtifactBindings artifacts,
            List<CasePlan> cases) {
        requireEqual(executable.suiteId(), artifacts.suiteId(), "suiteId");
        requireEqual(executable.suiteVersion(), artifacts.suiteVersion(), "suiteVersion");
        requireEqual(freeze.suitePath(), executable.suitePath(), "suitePath");
        requireEqual(freeze.suiteSha256(), executable.suiteSha256(), "suiteSha256");
        requireEqual(executable.blueprintSha256(), artifacts.blueprintSha256(),
                "blueprintSha256");
        requireEqual(freeze.suiteVersion(), executable.suiteVersion(), "freeze suiteVersion");
        requireEqual(freeze.contentTreeSha256(), artifacts.contentTreeSha256(),
                "contentTreeSha256");
        requireEqual(freeze.suiteSha256(), artifacts.suiteSha256(), "artifact suiteSha256");
        requireEqual(freeze.validatorTreeSha256(), artifacts.validatorTreeSha256(),
                "validatorTreeSha256");
        requireEqual(batch.executableSuiteContractSha256(),
                artifacts.executableSuiteContractSha256(),
                "executableSuiteContractSha256");
        requireEqual(batch.freezeManifestSha256(), artifacts.freezeManifestSha256(),
                "freezeManifestSha256");
        requireEqual(batch.contentTreeSha256(), artifacts.contentTreeSha256(),
                "batch contentTreeSha256");
        requireEqual(batch.suiteSha256(), artifacts.suiteSha256(), "batch suiteSha256");
        requireEqual(batch.validatorTreeSha256(), artifacts.validatorTreeSha256(),
                "batch validatorTreeSha256");
        requireEqual(batch.candidateJarSha256(), artifacts.candidateJarSha256(),
                "candidateJarSha256");
        requireEqual(batch.runnerJarSha256(), artifacts.runnerJarSha256(), "runnerJarSha256");
        requireEqual(batch.runnerInventorySha256(), artifacts.runnerInventorySha256(),
                "runnerInventorySha256");
        requireEqual(batch.workerImageId(), artifacts.workerImageId(), "workerImageId");
        requireEqual(batch.verifierImageId(), artifacts.verifierImageId(), "verifierImageId");
        requireEqual(batch.dockerExecutableSha256(), artifacts.dockerExecutableSha256(),
                "dockerExecutableSha256");
        if (!Path.of(batch.dockerExecutablePath()).equals(artifacts.dockerExecutable())) {
            throw new IllegalArgumentException("docker executable path differs from batch contract");
        }
        Path expectedSuiteFile = artifacts.frozenRoot().resolve(freeze.suitePath()).normalize();
        if (!artifacts.suiteFile().equals(expectedSuiteFile)) {
            throw new IllegalArgumentException("suite file path differs from freeze manifest");
        }
        Path expectedBlueprintFile = artifacts.frozenRoot()
                .resolve(executable.blueprintPath()).normalize();
        if (!artifacts.blueprintFile().equals(expectedBlueprintFile)) {
            throw new IllegalArgumentException(
                    "blueprint file path differs from executable-suite contract");
        }
        FinalDatasetFreezeManifest.FileEntry blueprintEntry = freeze.files().stream()
                .filter(entry -> entry.path().equals(executable.blueprintPath()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "blueprintPath is absent from retained freeze manifest"));
        requireEqual(executable.blueprintSha256(), blueprintEntry.sha256(),
                "freeze blueprintSha256");
        if (cases.size() != FinalExecutableSuiteContract.REQUIRED_CASE_COUNT) {
            throw new IllegalArgumentException("formal plan must contain exactly 28 cases");
        }
        List<String> ids = cases.stream().map(CasePlan::id).toList();
        if (!ids.equals(executable.orderedCaseIds()) || !ids.equals(batch.caseOrder())) {
            throw new IllegalArgumentException("formal plan case order differs from registration");
        }
        for (int index = 0; index < cases.size(); index++) {
            CasePlan casePlan = cases.get(index);
            if (casePlan.ordinal() != index + 1
                    || !casePlan.contract().equals(executable.cases().get(index))) {
                throw new IllegalArgumentException(
                        "formal case plan differs from executable-suite registration");
            }
            Path expectedFixture = artifacts.frozenRoot()
                    .resolve(casePlan.fixture().frozenPath()).normalize();
            Path expectedScoring = artifacts.frozenRoot()
                    .resolve(casePlan.contract().scoringContractPath()).normalize();
            Path expectedVerifier = artifacts.frozenRoot()
                    .resolve(casePlan.contract().verifierEntryPath()).normalize();
            if (!casePlan.fixture().sourcePath().equals(expectedFixture)
                    || !casePlan.scoringContractFile().equals(expectedScoring)
                    || !casePlan.verifier().workingDirectory()
                    .equals(artifacts.suiteFile().getParent())
                    || !casePlan.verifier().entry().equals(expectedVerifier)) {
                throw new IllegalArgumentException(
                        "formal case execution path differs from admission: " + casePlan.id());
            }
            FinalDatasetFreezeManifest.FileEntry scoringEntry = freeze.files().stream()
                    .filter(entry -> entry.path().equals(
                            casePlan.contract().scoringContractPath()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "scoring contract is absent from retained freeze manifest: "
                                    + casePlan.id()));
            requireEqual(casePlan.scoringContractSha256(), scoringEntry.sha256(),
                    "freeze scoringContractSha256 for " + casePlan.id());
            List<String> dependencyPaths = casePlan.verifier().dependencies().stream()
                    .map(VerifierDependency::frozenPath)
                    .toList();
            if (!dependencyPaths.equals(casePlan.contract().verifierDependencyPaths())) {
                throw new IllegalArgumentException(
                        "verifier dependency paths differ from registration: "
                                + casePlan.id());
            }
            requireEqual(casePlan.contract().verifierBundleSha256(),
                    casePlan.verifier().bundleSha256(),
                    "verifierBundleSha256 for " + casePlan.id());
            String validatorPrefix = freeze.validatorRoot() + "/";
            for (VerifierDependency dependency : casePlan.verifier().dependencies()) {
                if (!dependency.frozenPath().startsWith(validatorPrefix)) {
                    throw new IllegalArgumentException(
                            "verifier dependency escapes retained validatorRoot: "
                                    + casePlan.id());
                }
                FinalDatasetFreezeManifest.FileEntry frozenDependency = freeze.files().stream()
                        .filter(entry -> entry.path().equals(dependency.frozenPath()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "verifier dependency is absent from retained freeze manifest: "
                                        + casePlan.id()));
                Path expectedDependency = artifacts.frozenRoot()
                        .resolve(dependency.frozenPath()).normalize();
                if (!dependency.sourcePath().equals(expectedDependency)
                        || dependency.size() != frozenDependency.size()
                        || !dependency.mode().equals(frozenDependency.mode())
                        || !dependency.sha256().equals(frozenDependency.sha256())) {
                    throw new IllegalArgumentException(
                            "verifier dependency differs from retained freeze manifest: "
                                    + casePlan.id());
                }
            }
        }
    }

    private static List<EpisodePlan> buildEpisodes(
            List<FormalBatchContract.ModelBinding> models,
            int repeats,
            List<CasePlan> cases) {
        List<EpisodePlan> episodes = new ArrayList<>(models.size() * repeats * cases.size());
        int ordinal = 1;
        for (int modelIndex = 0; modelIndex < models.size(); modelIndex++) {
            FormalBatchContract.ModelBinding model = models.get(modelIndex);
            for (int repeat = 1; repeat <= repeats; repeat++) {
                for (CasePlan casePlan : cases) {
                    episodes.add(new EpisodePlan(
                            ordinal++, modelIndex + 1, model, repeat,
                            casePlan.ordinal(), casePlan));
                }
            }
        }
        return List.copyOf(episodes);
    }

    /** Runtime paths and digests admitted by the preflight; construction performs no I/O. */
    public record ArtifactBindings(
            Path frozenRoot,
            Path suiteFile,
            Path blueprintFile,
            Path executableSuiteContractFile,
            Path formalBatchContractFile,
            Path candidateJar,
            Path runnerJar,
            Path dockerExecutable,
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
            String dockerExecutableSha256) {
        public ArtifactBindings {
            frozenRoot = requireAbsoluteNormalized(frozenRoot, "frozenRoot");
            suiteFile = requireAbsoluteNormalized(suiteFile, "suiteFile");
            blueprintFile = requireAbsoluteNormalized(blueprintFile, "blueprintFile");
            executableSuiteContractFile = requireAbsoluteNormalized(
                    executableSuiteContractFile, "executableSuiteContractFile");
            formalBatchContractFile = requireAbsoluteNormalized(
                    formalBatchContractFile, "formalBatchContractFile");
            candidateJar = requireAbsoluteNormalized(candidateJar, "candidateJar");
            runnerJar = requireAbsoluteNormalized(runnerJar, "runnerJar");
            dockerExecutable = requireAbsoluteNormalized(dockerExecutable, "dockerExecutable");
            FormalContractSupport.requireSafeIdentifier(suiteId, "suiteId");
            FormalContractSupport.requireSafeIdentifier(suiteVersion, "suiteVersion");
            FormalContractSupport.requireSha256(
                    formalBatchContractSha256, "formalBatchContractSha256");
            FormalContractSupport.requireSha256(
                    executableSuiteContractSha256, "executableSuiteContractSha256");
            FormalContractSupport.requireSha256(freezeManifestSha256, "freezeManifestSha256");
            FormalContractSupport.requireSha256(contentTreeSha256, "contentTreeSha256");
            FormalContractSupport.requireSha256(suiteSha256, "suiteSha256");
            FormalContractSupport.requireSha256(blueprintSha256, "blueprintSha256");
            FormalContractSupport.requireSha256(validatorTreeSha256, "validatorTreeSha256");
            FormalContractSupport.requireSha256(candidateJarSha256, "candidateJarSha256");
            FormalContractSupport.requireSha256(runnerJarSha256, "runnerJarSha256");
            FormalContractSupport.requireSha256(runnerInventorySha256, "runnerInventorySha256");
            FormalContractSupport.requireImageId(workerImageId, "workerImageId");
            FormalContractSupport.requireImageId(verifierImageId, "verifierImageId");
            FormalContractSupport.requireSha256(
                    dockerExecutableSha256, "dockerExecutableSha256");
        }

        private static ArtifactBindings from(
                FormalBenchmarkPreflight.VerifiedPreflight verified) {
            return new ArtifactBindings(
                    verified.frozenRoot(),
                    verified.suiteFile(),
                    verified.blueprintFile(),
                    verified.executableSuiteContractFile(),
                    verified.formalBatchContractFile(),
                    verified.candidateJar(),
                    verified.runnerJar(),
                    verified.dockerExecutable(),
                    verified.suiteId(),
                    verified.suiteVersion(),
                    verified.formalBatchContractSha256(),
                    verified.executableSuiteContractSha256(),
                    verified.freezeManifestSha256(),
                    verified.contentTreeSha256(),
                    verified.suiteSha256(),
                    verified.blueprintSha256(),
                    verified.validatorTreeSha256(),
                    verified.candidateJarSha256(),
                    verified.runnerJarSha256(),
                    verified.runnerInventorySha256(),
                    verified.workerImageId(),
                    verified.verifierImageId(),
                    verified.dockerExecutableSha256());
        }

        @Override
        public String toString() {
            return "ArtifactBindings[suite=" + suiteId + "@" + suiteVersion
                    + ", batch=" + shortDigest(formalBatchContractSha256)
                    + ", freeze=" + shortDigest(freezeManifestSha256)
                    + ", blueprint=" + shortDigest(blueprintSha256)
                    + ", candidate=" + shortDigest(candidateJarSha256)
                    + ", runner=" + shortDigest(runnerJarSha256) + "]";
        }
    }

    /** Complete executable case selection; the exact preregistered case contract is retained. */
    public record CasePlan(
            int ordinal,
            FinalExecutableSuiteContract.CaseContract contract,
            ScoringContract scoringContract,
            Path scoringContractFile,
            String scoringContractSha256,
            String prompt,
            String promptSha256,
            FixtureSnapshot fixture,
            VerifierCommand verifier) {
        public CasePlan {
            if (ordinal <= 0 || ordinal > FinalExecutableSuiteContract.REQUIRED_CASE_COUNT) {
                throw new IllegalArgumentException("formal case ordinal is outside 1..28");
            }
            Objects.requireNonNull(contract, "contract");
            Objects.requireNonNull(scoringContract, "scoringContract");
            scoringContractFile = requireAbsoluteNormalized(
                    scoringContractFile, "scoringContractFile");
            FormalContractSupport.requireSha256(
                    scoringContractSha256, "scoringContractSha256");
            validateScoringContractBinding(
                    contract, scoringContract, scoringContractSha256);
            if (prompt == null || prompt.isBlank()) {
                throw new IllegalArgumentException("formal case prompt must not be blank");
            }
            FormalContractSupport.requireSha256(promptSha256, "promptSha256");
            if (!promptSha256.equals(sha256Text(prompt))) {
                throw new IllegalArgumentException("promptSha256 differs from prompt bytes");
            }
            Objects.requireNonNull(fixture, "fixture");
            Objects.requireNonNull(verifier, "verifier");
            requireEqual(contract.verifierEntryPath(), verifier.entryPath(),
                    "case verifierEntryPath");
            requireEqual(contract.verifierSha256(), verifier.entrySha256(),
                    "case verifierSha256");
            if (!contract.verifierDependencyPaths().equals(
                    verifier.dependencies().stream()
                            .map(VerifierDependency::frozenPath)
                            .toList())) {
                throw new IllegalArgumentException(
                        "case verifierDependencyPaths differ from verifier command");
            }
            requireEqual(contract.verifierBundleSha256(), verifier.bundleSha256(),
                    "case verifierBundleSha256");
        }

        private static CasePlan from(
                int ordinal,
                FinalExecutableSuiteContract.CaseContract contract,
                FormalBenchmarkPreflight.VerifiedCase verified) {
            requireVerifiedContractEqual(contract, verified);
            return new CasePlan(
                    ordinal,
                    contract,
                    verified.scoringContract(),
                    verified.scoringContractFile(),
                    verified.scoringContractSha256(),
                    verified.prompt(),
                    verified.promptSha256(),
                    FixtureSnapshot.from(verified.fixture()),
                    new VerifierCommand(
                            verified.verifierWorkingDirectory(),
                            verified.verifierArguments(),
                            contract.verifierEntryPath(),
                            verified.verifierEntry(),
                            verified.verifierSha256(),
                            verified.verifierDependencies().stream()
                                    .map(VerifierDependency::from)
                                    .toList(),
                            verified.verifierBundleSha256()));
        }

        public String id() {
            return contract.id();
        }

        public String category() {
            return contract.category();
        }

        public String scoringContractPath() {
            return contract.scoringContractPath();
        }

        /** Frozen digest only; it is not inferred to equal any container image identity. */
        public String toolchainSha256() {
            return scoringContract.toolchainSha256();
        }

        public FinalExecutableSuiteContract.Level level() {
            return contract.level();
        }

        public int weight() {
            return contract.weight();
        }

        public FinalExecutableSuiteContract.Mode mode() {
            return contract.mode();
        }

        public FinalExecutableSuiteContract.ToolProfile toolProfile() {
            return contract.toolProfile();
        }

        public int timeoutSeconds() {
            return contract.timeoutSeconds();
        }

        public int tokenBudget() {
            return contract.tokenBudget();
        }

        public int hardMaxIterations() {
            return contract.hardMaxIterations();
        }

        public int stagnationWindow() {
            return contract.stagnationWindow();
        }

        public String mockProfile() {
            return contract.mockProfile();
        }

        public List<String> episodeEvents() {
            return contract.episodeEvents();
        }

        public List<String> mandatoryAssertionIds() {
            return contract.mandatoryAssertionIds();
        }

        public List<String> hardGateIds() {
            return contract.hardGateIds();
        }

        public List<String> evidenceRequirements() {
            return contract.evidenceRequirements();
        }

        @Override
        public String toString() {
            return "CasePlan[id=" + id() + ", ordinal=" + ordinal + ", mode=" + mode()
                    + ", toolProfile=" + toolProfile() + ", fixture="
                    + shortDigest(fixture.snapshotSha256()) + ", scoring="
                    + shortDigest(scoringContractSha256) + ", verifier="
                    + shortDigest(verifier.entrySha256()) + "]";
        }
    }

    /** Complete manifest-derived fixture identity; no fixture bytes are reread while planning. */
    public record FixtureSnapshot(
            String declaredPath,
            String frozenPath,
            Path sourcePath,
            FormalBenchmarkPreflight.FixtureKind kind,
            String snapshotSha256,
            int fileCount,
            long totalBytes,
            List<FixtureFile> files) {
        public FixtureSnapshot {
            if (declaredPath == null || declaredPath.isBlank()) {
                throw new IllegalArgumentException("fixture declaredPath must not be blank");
            }
            FormalContractSupport.requireRelativePath(frozenPath, "fixture frozenPath");
            sourcePath = requireAbsoluteNormalized(sourcePath, "fixture sourcePath");
            Objects.requireNonNull(kind, "kind");
            FormalContractSupport.requireSha256(snapshotSha256, "fixture snapshotSha256");
            if (fileCount < 0 || totalBytes < 0) {
                throw new IllegalArgumentException("fixture counts must not be negative");
            }
            files = List.copyOf(files);
            if (files.size() != fileCount) {
                throw new IllegalArgumentException("fixture fileCount differs from files");
            }
            long actualBytes = 0L;
            String previous = null;
            Set<String> unique = new HashSet<>();
            String prefix = frozenPath + "/";
            for (FixtureFile file : files) {
                if (!unique.add(file.frozenPath())
                        || (previous != null && previous.compareTo(file.frozenPath()) >= 0)) {
                    throw new IllegalArgumentException(
                            "fixture files must be unique and sorted by frozen path");
                }
                if (kind == FormalBenchmarkPreflight.FixtureKind.FILE
                        ? !file.frozenPath().equals(frozenPath)
                        : !file.frozenPath().startsWith(prefix)) {
                    throw new IllegalArgumentException("fixture file lies outside fixture root");
                }
                previous = file.frozenPath();
                actualBytes = Math.addExact(actualBytes, file.size());
            }
            if (actualBytes != totalBytes) {
                throw new IllegalArgumentException("fixture totalBytes differs from files");
            }
            if (kind == FormalBenchmarkPreflight.FixtureKind.FILE && files.size() != 1) {
                throw new IllegalArgumentException("file fixture must contain exactly one file");
            }
            if (!snapshotSha256.equals(snapshotDigest(kind, frozenPath, files))) {
                throw new IllegalArgumentException(
                        "fixture snapshotSha256 differs from manifest-derived identity");
            }
        }

        private static FixtureSnapshot from(FormalBenchmarkPreflight.VerifiedFixture source) {
            List<FixtureFile> files = source.files().stream()
                    .map(file -> new FixtureFile(
                            file.frozenPath(), file.sha256(), file.size(), file.mode()))
                    .toList();
            return new FixtureSnapshot(
                    source.declaredPath(), source.frozenPath(), source.sourcePath(), source.kind(),
                    source.snapshotSha256(), source.fileCount(), source.totalBytes(), files);
        }

        @Override
        public String toString() {
            return "FixtureSnapshot[kind=" + kind + ", snapshot="
                    + shortDigest(snapshotSha256) + ", files=" + fileCount
                    + ", bytes=" + totalBytes + "]";
        }
    }

    public record FixtureFile(String frozenPath, String sha256, long size, String mode) {
        public FixtureFile {
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

    /** Registered shell-free verifier argv plus its complete frozen dependency identity. */
    public record VerifierCommand(
            Path workingDirectory,
            List<String> registeredArguments,
            String entryPath,
            Path entry,
            String entrySha256,
            List<VerifierDependency> dependencies,
            String bundleSha256) {
        public VerifierCommand {
            workingDirectory = requireAbsoluteNormalized(
                    workingDirectory, "verifier workingDirectory");
            registeredArguments = List.copyOf(registeredArguments);
            if (registeredArguments.isEmpty()) {
                throw new IllegalArgumentException("verifier argv must not be empty");
            }
            FormalContractSupport.requireRelativePath(entryPath, "verifier entryPath");
            entry = requireAbsoluteNormalized(entry, "verifier entry");
            FormalContractSupport.requireSha256(entrySha256, "verifier entrySha256");
            dependencies = List.copyOf(dependencies);
            String previous = null;
            VerifierDependency entryDependency = null;
            for (VerifierDependency dependency : dependencies) {
                if (previous != null
                        && previous.compareTo(dependency.frozenPath()) >= 0) {
                    throw new IllegalArgumentException(
                            "verifier dependencies must be unique and sorted by frozen path");
                }
                if (entryPath.equals(dependency.frozenPath())) {
                    entryDependency = dependency;
                }
                previous = dependency.frozenPath();
            }
            if (entryDependency == null
                    || !entryDependency.sourcePath().equals(entry)
                    || !entryDependency.sha256().equals(entrySha256)
                    || !"0500".equals(entryDependency.mode())) {
                throw new IllegalArgumentException(
                        "verifier entry differs from verifier dependency bundle");
            }
            FormalContractSupport.requireSha256(bundleSha256, "verifier bundleSha256");
            String derivedBundleSha256 = VerifierBundleIdentity.digest(dependencies.stream()
                    .map(dependency -> new VerifierBundleIdentity.Entry(
                            dependency.frozenPath(), dependency.mode(), dependency.size(),
                            dependency.sha256()))
                    .toList());
            if (!bundleSha256.equals(derivedBundleSha256)) {
                throw new IllegalArgumentException(
                        "verifier bundleSha256 differs from dependency identity");
            }
            if (!registeredArguments.get(0).equals(entryPath)) {
                throw new IllegalArgumentException(
                        "verifier argv[0] must equal the preregistered verifier entry path");
            }
            for (String argument : registeredArguments) {
                if (argument == null || argument.isBlank()
                        || argument.indexOf('\0') >= 0 || argument.indexOf('\n') >= 0
                        || argument.indexOf('\r') >= 0) {
                    throw new IllegalArgumentException("verifier argv contains an unsafe argument");
                }
                requireWholePlaceholder(argument, CaseDefinition.WORKSPACE_PLACEHOLDER);
                requireWholePlaceholder(argument, CaseDefinition.EVIDENCE_PLACEHOLDER);
            }
        }

        public boolean requiresEvidence() {
            return registeredArguments.stream().anyMatch(
                    CaseDefinition.EVIDENCE_PLACEHOLDER::equals);
        }

        /** Replaces only the two whole-argument runner-owned placeholders; no shell is involved. */
        public MaterializedVerifier materialize(Path workspace, Path evidence) {
            Path safeWorkspace = requireAbsoluteNormalized(workspace, "workspace");
            if (requiresEvidence() && evidence == null) {
                throw new IllegalArgumentException(
                        "verifier evidence is required by the registered argv");
            }
            Path safeEvidence = evidence == null
                    ? null
                    : requireAbsoluteNormalized(evidence, "evidence");
            List<String> arguments = registeredArguments.stream()
                    .map(argument -> {
                        if (CaseDefinition.WORKSPACE_PLACEHOLDER.equals(argument)) {
                            return safeWorkspace.toString();
                        }
                        if (CaseDefinition.EVIDENCE_PLACEHOLDER.equals(argument)) {
                            return Objects.requireNonNull(safeEvidence).toString();
                        }
                        return argument;
                    })
                    .toList();
            return new MaterializedVerifier(workingDirectory, arguments);
        }

        @Override
        public String toString() {
            return "VerifierCommand[argv=" + registeredArguments.size()
                    + ", dependencies=" + dependencies.size() + ", bundle="
                    + shortDigest(bundleSha256) + "]";
        }
    }

    /** Immutable verifier dependency copied from preflight without filesystem access. */
    public record VerifierDependency(
            String frozenPath,
            Path sourcePath,
            String mode,
            long size,
            String sha256) {
        public VerifierDependency {
            FormalContractSupport.requireRelativePath(
                    frozenPath, "verifier dependency frozenPath");
            sourcePath = requireAbsoluteNormalized(
                    sourcePath, "verifier dependency sourcePath");
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

        private static VerifierDependency from(
                FormalBenchmarkPreflight.VerifiedVerifierDependency source) {
            return new VerifierDependency(
                    source.frozenPath(), source.canonicalPath(), source.mode(), source.size(),
                    source.sha256());
        }
    }

    public record MaterializedVerifier(Path workingDirectory, List<String> arguments) {
        public MaterializedVerifier {
            workingDirectory = requireAbsoluteNormalized(
                    workingDirectory, "materialized verifier workingDirectory");
            arguments = List.copyOf(arguments);
            if (arguments.isEmpty()) {
                throw new IllegalArgumentException("materialized verifier argv must not be empty");
            }
        }
    }

    /** One deterministic episode in model-major, repeat-major, case-major order. */
    public record EpisodePlan(
            int ordinal,
            int modelOrdinal,
            FormalBatchContract.ModelBinding model,
            int repeat,
            int caseOrdinal,
            CasePlan casePlan) {
        public EpisodePlan {
            if (ordinal <= 0 || modelOrdinal <= 0 || repeat <= 0 || caseOrdinal <= 0) {
                throw new IllegalArgumentException("formal episode ordinals must be positive");
            }
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(casePlan, "casePlan");
            if (casePlan.ordinal() != caseOrdinal) {
                throw new IllegalArgumentException("episode case ordinal differs from case plan");
            }
        }

        public String caseId() {
            return casePlan.id();
        }

        @Override
        public String toString() {
            return "EpisodePlan[ordinal=" + ordinal + ", model=" + model.provider() + "/"
                    + model.model() + ", repeat=" + repeat + ", case=" + caseId() + "]";
        }
    }

    private static void requireVerifiedContractEqual(
            FinalExecutableSuiteContract.CaseContract contract,
            FormalBenchmarkPreflight.VerifiedCase verified) {
        if (!contract.id().equals(verified.id())
                || !contract.category().equals(verified.category())
                || contract.level() != verified.level()
                || contract.weight() != verified.weight()
                || contract.mode() != verified.mode()
                || contract.toolProfile() != verified.toolProfile()
                || contract.timeoutSeconds() != verified.timeoutSeconds()
                || contract.tokenBudget() != verified.tokenBudget()
                || contract.hardMaxIterations() != verified.hardMaxIterations()
                || contract.stagnationWindow() != verified.stagnationWindow()
                || !contract.mockProfile().equals(verified.mockProfile())
                || !contract.episodeEvents().equals(verified.episodeEvents())
                || !contract.mandatoryAssertionIds().equals(verified.mandatoryAssertionIds())
                || !contract.hardGateIds().equals(verified.hardGateIds())
                || !contract.evidenceRequirements().equals(verified.evidenceRequirements())
                || !contract.scoringContractPath().equals(verified.scoringContractPath())
                || !contract.scoringContractSha256()
                .equals(verified.scoringContractSha256())
                || !contract.verifierEntryPath().equals(verified.verifierEntryPath())
                || !contract.verifierSha256().equals(verified.verifierSha256())
                || !contract.verifierDependencyPaths().equals(
                verified.verifierDependencies().stream()
                        .map(FormalBenchmarkPreflight.VerifiedVerifierDependency::frozenPath)
                        .toList())
                || !contract.verifierBundleSha256()
                .equals(verified.verifierBundleSha256())) {
            throw new IllegalArgumentException(
                    "verified case differs from executable-suite contract: " + contract.id());
        }
    }

    private static void validateScoringContractBinding(
            FinalExecutableSuiteContract.CaseContract caseContract,
            ScoringContract scoringContract,
            String scoringContractSha256) {
        requireEqual(caseContract.scoringContractSha256(), scoringContractSha256,
                "case scoringContractSha256");
        requireEqual(caseContract.id(), scoringContract.caseId(),
                "scoring contract caseId");
        requireEqual(caseContract.verifierSha256(), scoringContract.verifierSha256(),
                "scoring contract verifierSha256");
        List<String> mandatoryAssertionIds = scoringContract.assertions().stream()
                .filter(ScoringContract.AssertionRule::mandatory)
                .map(ScoringContract.AssertionRule::id)
                .toList();
        if (!caseContract.mandatoryAssertionIds().equals(mandatoryAssertionIds)) {
            throw new IllegalArgumentException(
                    "scoring contract mandatory assertion ids differ from case contract");
        }
        List<String> hardGateIds = scoringContract.hardGates().stream()
                .map(ScoringContract.HardGateRule::id)
                .toList();
        if (!caseContract.hardGateIds().equals(hardGateIds)) {
            throw new IllegalArgumentException(
                    "scoring contract hard gate ids differ from case contract");
        }
    }

    private static String snapshotDigest(
            FormalBenchmarkPreflight.FixtureKind kind,
            String frozenPath,
            List<FixtureFile> files) {
        MessageDigest digest = newSha256();
        update(digest, "paicli-formal-fixture-snapshot-v1");
        digest.update((byte) 0);
        update(digest, kind.name());
        digest.update((byte) 0);
        update(digest, frozenPath);
        digest.update((byte) '\n');
        for (FixtureFile file : files) {
            update(digest, file.frozenPath());
            digest.update((byte) 0);
            update(digest, file.sha256());
            digest.update((byte) 0);
            update(digest, file.mode());
            digest.update((byte) 0);
            update(digest, Long.toString(file.size()));
            digest.update((byte) '\n');
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256Text(String text) {
        MessageDigest digest = newSha256();
        digest.update(text.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void requireWholePlaceholder(String argument, String placeholder) {
        if (argument.contains(placeholder) && !argument.equals(placeholder)) {
            throw new IllegalArgumentException(
                    "verifier placeholder must be one complete argv item: " + placeholder);
        }
    }

    private static Path requireAbsoluteNormalized(Path path, String label) {
        if (path == null || !path.isAbsolute() || !path.normalize().equals(path)) {
            throw new IllegalArgumentException(label + " must be an absolute normalized path");
        }
        return path;
    }

    private static void requireEqual(String expected, String actual, String label) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalArgumentException(label + " differs from the admitted registration");
        }
    }

    private static String shortDigest(String digest) {
        return digest.substring(0, 12);
    }
}
