package com.paicli.eval.benchmark;

import com.paicli.eval.benchmark.finalset.generator.FinalCaseContractCompiler;
import com.paicli.eval.benchmark.finalset.generator.FinalSourceGenerator;
import com.paicli.eval.benchmark.formal.FinalExecutableSuiteContract;
import com.paicli.eval.benchmark.formal.FormalBenchmarkPreflight;
import com.paicli.eval.benchmark.formal.FormalExecutionPlan;
import com.paicli.eval.benchmark.formal.VerifierBundleIdentity;
import com.paicli.eval.benchmark.safety.F3FrozenOracle;
import com.paicli.eval.benchmark.scoring.ScoringContract;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Test-only, single-case preparation of real generated inputs; not formal batch admission. */
public final class F3LiveFixture {
    private static final String FIXTURE = "fixtures/final/F3";
    private static final String PROMPT = "prompts/final/F3.md";
    private static final int MAX_FILE_BYTES = 16 * 1024 * 1024;

    private F3LiveFixture() { }

    /**
     * Checks an untouched generated source, then freezes only F3's original inputs and dependencies.
     * The source manifest remains NOT_READY and unchanged. Its original payload hash includes
     * pre-freeze modes; this helper never rewrites it or represents the resulting source as admitted.
     */
    public static FormalExecutionPlan.CasePlan create(Path source) throws IOException {
        try {
            return prepare(source);
        } catch (IllegalArgumentException error) {
            throw new IOException("invalid F3 diagnostic source or contract", error);
        }
    }

    private static FormalExecutionPlan.CasePlan prepare(Path source) throws IOException {
        requireDirectory(source);
        if (!"rwx------".equals(mode(source))) throw new IOException("F3 source root must have mode 0700");
        for (Path parent = source; parent != null; parent = parent.getParent()) {
            for (String vcs : List.of(".git", ".hg", ".svn")) {
                if (Files.exists(parent.resolve(vcs), LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("F3 diagnostic source must be outside version control");
            }
        }
        Capture manifestBytes = read(source, FinalSourceGenerator.GENERATION_MANIFEST, false);
        Capture incomplete = read(source, FinalSourceGenerator.INCOMPLETE_MARKER, false);
        var manifest = new FinalSourceGenerator().inspect(source);
        if (manifest.finalReady() || manifest.recipeCount() != 28 || manifest.implementedRecipeCount() != 24
                || manifest.missingCaseIds().size() != 4 || Files.exists(source.resolve("suite.json"), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("F3 diagnostic requires the 24/28 NOT_READY prototype source");
        var recipe = manifest.cases().stream().filter(item -> "F3".equals(item.id())).findFirst()
                .orElseThrow(() -> new IOException("F3 recipe is missing"));
        String contractPath = FinalCaseContractCompiler.contractPath("F3");
        if (!"IMPLEMENTED".equals(recipe.implementationStatus()) || !"NOT_INTEGRATED".equals(recipe.runnerIntegrationStatus())
                || recipe.publicationEligible() || !FIXTURE.equals(recipe.fixturePath()) || !PROMPT.equals(recipe.publicPromptPath())
                || !F3FrozenOracle.PATH.equals(recipe.privateOraclePath()) || !contractPath.equals(recipe.caseContractPath()))
            throw new IOException("F3 recipe paths or prototype status differ");

        var contract = FinalExecutableSuiteContract.CaseContract.load(source.resolve(contractPath));
        var compiled = FinalCaseContractCompiler.compile(source, "F3");
        if (!contract.equals(compiled)) throw new IOException("F3 stored contract differs from source");
        var scoring = ScoringContract.load(source.resolve(contract.scoringContractPath()));
        if (!contract.scoringContractPath().equals(recipe.scoringContractPath())
                || !contract.verifierEntryPath().equals(recipe.verifierEntryPath()))
            throw new IOException("F3 contract paths differ from generation provenance");
        var captures = new TreeMap<String, Capture>();
        captures.put(contractPath, read(source, contractPath, false));
        captures.put(PROMPT, read(source, PROMPT, false));
        for (String dependency : contract.verifierDependencyPaths())
            captures.put(dependency, read(source, dependency, dependency.equals(contract.verifierEntryPath())));
        var oracle = F3FrozenOracle.parse(captures.get(F3FrozenOracle.PATH).bytes());
        if (!Arrays.equals(captures.get(PROMPT).bytes(), oracle.prompt().getBytes(StandardCharsets.UTF_8))
                || !captures.get(contractPath).sha256().equals(recipe.caseContractSha256()))
            throw new IOException("F3 exact prompt or contract bytes differ");
        if (oracle.files().size() != 5) throw new IOException("F3 requires exactly five source files");
        for (var input : oracle.files().entrySet()) {
            String relative = FIXTURE + "/" + input.getKey();
            var capture = read(source, relative, false);
            if (!Arrays.equals(capture.bytes(), input.getValue().getBytes(StandardCharsets.UTF_8)))
                throw new IOException("F3 fixture bytes differ from oracle");
            captures.put(relative, capture);
        }
        // Recompile checks the complete, closed fixture inventory, including empty extra directories.
        if (!contract.equals(FinalCaseContractCompiler.compile(source, "F3")))
            throw new IOException("F3 source changed during capture");
        if (!manifest.equals(new FinalSourceGenerator().inspect(source)))
            throw new IOException("generated source changed before F3 freeze");
        var directories = captureDirectories(captures.values());
        verify(source, captures, false);
        verifyDirectories(source, directories, false);
        requireSame(manifestBytes, read(source, FinalSourceGenerator.GENERATION_MANIFEST, false));
        requireSame(incomplete, read(source, FinalSourceGenerator.INCOMPLETE_MARKER, false));

        for (var capture : captures.values()) {
            Files.getFileAttributeView(source.resolve(capture.path()), PosixFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS).setPermissions(PosixFilePermissions.fromString(capture.frozenMode()));
        }
        for (var directory : directories.values()) {
            if (directory.path().startsWith(source.resolve(FIXTURE)))
                Files.getFileAttributeView(directory.path(), PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                        .setPermissions(PosixFilePermissions.fromString("r-x------"));
        }
        verify(source, captures, true);
        verifyDirectories(source, directories, true);
        if (!contract.equals(FinalCaseContractCompiler.compile(source, "F3"))
                || !contract.equals(FinalExecutableSuiteContract.CaseContract.load(source.resolve(contractPath)))
                || !scoring.equals(ScoringContract.load(source.resolve(contract.scoringContractPath()))))
            throw new IOException("F3 source or scoring changed while freezing");

        var dependencies = new ArrayList<FormalExecutionPlan.VerifierDependency>();
        for (String relative : contract.verifierDependencyPaths()) {
            var file = captures.get(relative);
            dependencies.add(new FormalExecutionPlan.VerifierDependency(relative, source.resolve(relative),
                    relative.equals(contract.verifierEntryPath()) ? "0500" : "0400", file.bytes().length, file.sha256()));
        }
        String bundle = VerifierBundleIdentity.digest(dependencies.stream().map(file ->
                new VerifierBundleIdentity.Entry(file.frozenPath(), file.mode(), file.size(), file.sha256())).toList());
        if (!bundle.equals(contract.verifierBundleSha256())) throw new IOException("F3 verifier bundle differs");
        var files = new ArrayList<FormalExecutionPlan.FixtureFile>();
        for (String name : oracle.files().keySet().stream().sorted().toList()) {
            var input = captures.get(FIXTURE + "/" + name);
            files.add(new FormalExecutionPlan.FixtureFile(input.path(), input.sha256(), input.bytes().length, "0400"));
        }
        var identity = new StringBuilder("paicli-formal-fixture-snapshot-v1\0DIRECTORY\0" + FIXTURE + "\n");
        for (var file : files) identity.append(file.frozenPath()).append('\0').append(file.sha256()).append('\0')
                .append(file.mode()).append('\0').append(file.size()).append('\n');
        var fixture = new FormalExecutionPlan.FixtureSnapshot(FIXTURE, FIXTURE, source.resolve(FIXTURE),
                FormalBenchmarkPreflight.FixtureKind.DIRECTORY, hash(identity.toString().getBytes(StandardCharsets.UTF_8)),
                files.size(), files.stream().mapToLong(FormalExecutionPlan.FixtureFile::size).sum(), files);
        var plan = new FormalExecutionPlan.CasePlan(manifest.cases().indexOf(recipe) + 1, contract, scoring,
                source.resolve(contract.scoringContractPath()), contract.scoringContractSha256(), oracle.prompt(),
                captures.get(PROMPT).sha256(), fixture, new FormalExecutionPlan.VerifierCommand(source,
                List.of(contract.verifierEntryPath(), "{workspace}", "{evidence}"), contract.verifierEntryPath(),
                source.resolve(contract.verifierEntryPath()), contract.verifierSha256(), dependencies, bundle));
        FormalInjectionBinding.capture(plan).verifyUnchanged();
        verify(source, captures, true);
        verifyDirectories(source, directories, true);
        requireSame(manifestBytes, read(source, FinalSourceGenerator.GENERATION_MANIFEST, false));
        requireSame(incomplete, read(source, FinalSourceGenerator.INCOMPLETE_MARKER, false));
        if (!"rwx------".equals(mode(source))) throw new IOException("F3 source root permissions changed");
        return plan;
    }

    private static Map<String, DirectoryCapture> captureDirectories(Iterable<Capture> files) throws IOException {
        var directories = new TreeMap<String, DirectoryCapture>();
        for (var file : files) for (var parent : file.parentKeys().entrySet()) {
            Path path = Path.of(parent.getKey()); requireDirectory(path);
            var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!parent.getValue().equals(attributes.fileKey().toString()))
                throw new IOException("F3 parent directory changed during capture");
            var capture = new DirectoryCapture(path, attributes.fileKey().toString(), mode(path), attributes.lastModifiedTime());
            var previous = directories.putIfAbsent(parent.getKey(), capture);
            if (previous != null && !previous.equals(capture)) throw new IOException("F3 directory changed during capture");
        }
        return directories;
    }

    private static void verifyDirectories(Path source, Map<String, DirectoryCapture> directories, boolean frozen) throws IOException {
        for (var expected : directories.values()) {
            requireDirectory(expected.path());
            var current = Files.readAttributes(expected.path(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            String expectedMode = frozen && expected.path().startsWith(source.resolve(FIXTURE)) ? "r-x------" : expected.mode();
            if (!expected.fileKey().equals(current.fileKey().toString()) || !expected.modified().equals(current.lastModifiedTime())
                    || !expectedMode.equals(mode(expected.path())))
                throw new IOException("F3 directory identity or permissions changed");
        }
    }

    private static void verify(Path source, Map<String, Capture> captures, boolean frozen) throws IOException {
        for (var expected : captures.values()) {
            Capture current = read(source, expected.path(), expected.executable());
            requireSame(expected, current);
            if (!current.mode().equals(frozen ? expected.frozenMode() : expected.mode()))
                throw new IOException("F3 file permissions changed");
        }
    }

    private static void requireSame(Capture before, Capture after) throws IOException {
        if (!before.fileKey().equals(after.fileKey()) || !before.parentKeys().equals(after.parentKeys())
                || !before.modified().equals(after.modified()) || !Arrays.equals(before.bytes(), after.bytes()))
            throw new IOException("F3 generated source identity or bytes changed");
    }

    private static Capture read(Path source, String relative, boolean executable) throws IOException {
        Path file = source.resolve(relative);
        if (!file.startsWith(source) || !file.equals(file.normalize()) || Files.isSymbolicLink(file)
                || !file.equals(file.toRealPath())) throw new IOException("F3 source path is not canonical");
        var parents = new TreeMap<String, String>();
        for (Path parent = file.getParent(); parent != null && parent.startsWith(source); parent = parent.getParent()) {
            requireDirectory(parent);
            parents.put(parent.toString(), Files.readAttributes(parent, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).fileKey().toString());
        }
        var before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        String permissions = mode(file);
        if (!before.isRegularFile() || before.fileKey() == null || before.size() > MAX_FILE_BYTES
                || ((Number) Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1
                || !(executable ? List.of("rwx------", "r-x------") : List.of("rw-------", "r--------")).contains(permissions))
            throw new IOException("F3 source file is not bounded, unique and owner-only");
        byte[] bytes;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(MAX_FILE_BYTES + 1); }
        var after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (bytes.length != before.size() || !before.fileKey().equals(after.fileKey()) || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime()) || !permissions.equals(mode(file)))
            throw new IOException("F3 source changed while reading");
        return new Capture(relative, executable, bytes, hash(bytes), permissions, before.fileKey().toString(),
                before.lastModifiedTime(), Map.copyOf(parents));
    }

    private static void requireDirectory(Path path) throws IOException {
        if (path == null || !path.isAbsolute() || !path.equals(path.normalize()) || Files.isSymbolicLink(path)
                || !path.equals(path.toRealPath())) throw new IOException("F3 source directory is not canonical");
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.fileKey() == null || !List.of("rwx------", "r-x------").contains(mode(path)))
            throw new IOException("F3 source directory is not owner-only");
    }

    private static String mode(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private record Capture(String path, boolean executable, byte[] bytes, String sha256, String mode,
                           String fileKey, FileTime modified, Map<String, String> parentKeys) {
        String frozenMode() { return executable ? "r-x------" : "r--------"; }
    }

    private record DirectoryCapture(Path path, String fileKey, String mode, FileTime modified) { }
}
