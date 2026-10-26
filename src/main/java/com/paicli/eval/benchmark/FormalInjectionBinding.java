package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.eval.benchmark.formal.FinalExecutableSuiteContract;
import com.paicli.eval.benchmark.formal.FormalBenchmarkPreflight;
import com.paicli.eval.benchmark.formal.FormalExecutionPlan;
import com.paicli.eval.benchmark.mock.F3SupportBundleMock;
import com.paicli.eval.benchmark.relay.F3ToolResultAudit;
import com.paicli.eval.benchmark.safety.F3FrozenOracle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.stream.Collectors;

/** F3-only frozen source, native mixed-tool audit and stopped-workspace binding. No mount API. */
final class FormalInjectionBinding {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FIXTURE = "fixtures/final/F3";
    private static final BenchmarkProtocol.AgentLimits LIMITS =
            new BenchmarkProtocol.AgentLimits(100_000, 32, 8, 1_000_000, 16_384);
    private final Path root;
    private final F3FrozenOracle oracle;
    private final List<Capture> captures;

    private FormalInjectionBinding(Path root, F3FrozenOracle oracle, List<Capture> captures) {
        this.root = root; this.oracle = oracle; this.captures = List.copyOf(captures);
    }

    static boolean supports(FormalExecutionPlan.CasePlan plan) {
        return plan != null && "F3".equals(plan.id()) && F3FrozenOracle.PROFILE.equals(plan.mockProfile())
                && plan.mode() == FinalExecutableSuiteContract.Mode.REACT
                && "MOCK_MCP_FILE_ONLY".equals(plan.toolProfile().name())
                && plan.tokenBudget() == LIMITS.tokenBudget() && plan.hardMaxIterations() == LIMITS.hardMaxIterations()
                && plan.stagnationWindow() == LIMITS.stagnationWindow()
                && plan.evidenceRequirements().containsAll(Set.of("mock_audit", "mock_state", "provider_turns",
                        "raw_tool_results", "stream_deltas"));
    }

    static FormalInjectionBinding capture(FormalExecutionPlan.CasePlan plan) throws IOException {
        if (!supports(plan)) throw new IOException("unsupported F3 frozen binding");
        var source = plan.verifier().dependencies().stream().filter(d -> F3FrozenOracle.PATH.equals(d.frozenPath()))
                .findFirst().orElseThrow(() -> new IOException("F3 frozen source is not registered"));
        Path root = source.sourcePath();
        for (int i = 0; i < Path.of(source.frozenPath()).getNameCount(); i++) root = root == null ? null : root.getParent();
        if (root == null) throw new IOException("F3 frozen root unavailable");
        requireOutsideVcs(root);
        var captures = new ArrayList<Capture>(); captures.add(read(root, source));
        var oracle = F3FrozenOracle.parse(captures.get(0).bytes());
        if (!oracle.definition().prompt().equals(plan.prompt())) throw new IOException("F3 frozen prompt mismatch");
        var fixture = plan.fixture(); var expectedFiles = oracle.definition().files();
        if (fixture.kind() != FormalBenchmarkPreflight.FixtureKind.DIRECTORY || !FIXTURE.equals(fixture.frozenPath())
                || !root.resolve(FIXTURE).equals(fixture.sourcePath()) || expectedFiles.size() != 5
                || fixture.fileCount() != expectedFiles.size()) throw new IOException("F3 frozen fixture identity mismatch");
        Set<String> expectedPaths = expectedFiles.keySet().stream().map(name -> FIXTURE + "/" + name).collect(Collectors.toSet());
        if (!expectedPaths.equals(fixture.files().stream().map(FormalExecutionPlan.FixtureFile::frozenPath).collect(Collectors.toSet())))
            throw new IOException("F3 frozen fixture paths mismatch");
        for (var file : fixture.files()) {
            var captured = read(root, new FormalExecutionPlan.VerifierDependency(file.frozenPath(), root.resolve(file.frozenPath()),
                    file.mode(), file.size(), file.sha256()));
            String name = file.frozenPath().substring(FIXTURE.length() + 1);
            if (!Arrays.equals(captured.bytes(), expectedFiles.get(name).getBytes(StandardCharsets.UTF_8)))
                throw new IOException("F3 frozen fixture content mismatch");
            captures.add(captured);
        }
        var binding = new FormalInjectionBinding(root, oracle, captures); binding.verifyUnchanged(); return binding;
    }

    void verifyUnchanged() throws IOException {
        requireOutsideVcs(root);
        for (var prior : captures) {
            var current = read(root, prior.entry());
            if (!prior.fileKey().equals(current.fileKey()) || !prior.parentKeys().equals(current.parentKeys()))
                throw new IOException("F3 frozen source or fixture identity changed");
        }
        requireInventory(root.resolve(FIXTURE), oracle.definition().files().keySet(), false);
    }

    Session newSession() throws IOException { verifyUnchanged(); return new Session(this); }

    static final class Session {
        private final FormalInjectionBinding binding;
        private boolean started, begun, finished;
        private Path workspace, home;
        private F3DevelopmentSession development;
        private BenchmarkCoordinatorMain.WorkerExecution result;
        private BenchmarkVerifierWorkspaceSnapshot.Snapshot snapshot;
        private String snapshotDirectoryKey;

        private Session(FormalInjectionBinding binding) { this.binding = binding; }

        synchronized void begin(BenchmarkProtocol.WorkerRequest request, Path workspace, Path home) throws IOException {
            if (started || finished) throw new IOException("F3 frozen session is single-use");
            started = true; binding.verifyUnchanged();
            if (request == null || workspace == null || home == null || workspace.getParent() == null
                    || !workspace.getParent().equals(home.getParent()) || workspace.equals(home)
                    || workspace.startsWith(binding.root) || home.startsWith(binding.root)
                    || binding.root.startsWith(workspace) || binding.root.startsWith(home)
                    || !workspace.equals(workspace.toRealPath()) || !home.equals(home.toRealPath())
                    || !workspace.toString().equals(request.workspace()) || !home.toString().equals(request.home())
                    || !workspace.getParent().toString().equals(request.episodeDirectory())
                    || !"REACT".equalsIgnoreCase(request.mode()) || request.toolProfile() != BenchmarkToolProfile.MOCK_MCP_FILE_ONLY
                    || !LIMITS.equals(request.agentLimits()) || !binding.oracle.definition().prompt().equals(request.prompt()))
                throw new IOException("F3 frozen request, paths, profile or limits differ");
            requireInventory(workspace, binding.oracle.definition().files().keySet(), true);
            for (String name : binding.oracle.definition().files().keySet()) {
                Path file = workspace.resolve(name);
                if (!Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rw-------")))
                    throw new IOException("F3 runtime input must have private writable permissions");
            }
            this.workspace = workspace; this.home = home;
            development = new F3DevelopmentSession(workspace.getParent(), binding.oracle.definition());
            development.begin(request, workspace, home);
            binding.verifyUnchanged(); begun = true;
        }

        synchronized F3SupportBundleMock mock() throws IOException { requireStarted(); return development.mock(); }
        synchronized F3ToolResultAudit audit() throws IOException { requireStarted(); return development.audit(); }
        synchronized void finish(BenchmarkCoordinatorMain.WorkerExecution execution) throws IOException {
            requireStarted();
            if (finished) throw new IOException("F3 dispatch already finished");
            finished = true; result = execution;
            development.finish(execution); binding.verifyUnchanged();
        }
        synchronized void requireReturned(BenchmarkCoordinatorMain.WorkerExecution execution) throws IOException {
            if (!finished || result == null || execution != result) throw new IOException("F3 dispatch result is not host-bound");
            verifyTerminalUnchanged();
        }
        synchronized BenchmarkCoordinatorMain.WorkerExecution execution() throws IOException { requireReturned(result); return result; }
        synchronized void verifyTerminalUnchanged() throws IOException {
            if (!finished) throw new IOException("F3 dispatch is not finished");
            binding.verifyUnchanged(); development.verifyUnchanged();
        }
        synchronized void requirePaths(Path episode, Path workspace, Path home) throws IOException {
            requireStarted();
            if (!this.workspace.equals(workspace) || !this.home.equals(home) || !workspace.getParent().equals(episode))
                throw new IOException("F3 evidence episode mismatch");
        }
        synchronized BenchmarkVerifierWorkspaceSnapshot.Snapshot snapshot(Path target) throws IOException {
            requireReturned(result);
            if (snapshot != null || target == null || !workspace.getParent().equals(target.getParent()))
                throw new IOException("F3 snapshot lifecycle or path mismatch");
            ObjectNode evidence = development.snapshotEvidence(result);
            snapshot = BenchmarkVerifierWorkspaceSnapshot.create(workspace, target);
            var attributes = Files.readAttributes(snapshot.directory(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.fileKey() == null) throw new IOException("F3 snapshot has no directory identity");
            snapshotDirectoryKey = attributes.fileKey().toString();
            requireSnapshotMatches(evidence);
            return snapshot;
        }
        synchronized InjectionEvidence evidence(BenchmarkVerifierWorkspaceSnapshot.Snapshot supplied) throws IOException {
            requireReturned(result);
            if (snapshot == null || snapshot != supplied) throw new IOException("F3 verifier snapshot is not host-bound");
            ObjectNode evidence = development.snapshotEvidence(result); requireSnapshotMatches(evidence);
            return new InjectionEvidence(1, "F3", F3FrozenOracle.PROFILE, binding.captures.get(0).entry().sha256(),
                    F1BoundarySession.sha(binding.oracle.definition().prompt().getBytes(StandardCharsets.UTF_8)), evidence);
        }
        private void requireSnapshotMatches(ObjectNode evidence) throws IOException {
            snapshot.verifyUnchanged();
            var attributes = Files.readAttributes(snapshot.directory(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.fileKey() == null || !attributes.fileKey().toString().equals(snapshotDirectoryKey))
                throw new IOException("F3 verifier snapshot directory was replaced");
            if (!evidence.path("workspaceAfter").equals(JSON.valueToTree(F3DevelopmentSession.readTree(snapshot.directory()))))
                throw new IOException("F3 verifier snapshot differs from stopped Candidate workspace");
            snapshot.verifyUnchanged(); binding.verifyUnchanged();
        }
        private void requireStarted() throws IOException {
            if (!begun || development == null) throw new IOException("F3 frozen session not started");
        }
    }

    /** Constructed only from a bound host Session; returned JSON cannot mutate retained evidence. */
    record InjectionEvidence(int schemaVersion, String caseId, String profile, String sourceSha256,
                             String promptSha256, ObjectNode development) {
        InjectionEvidence {
            if (schemaVersion != 1 || !"F3".equals(caseId) || !F3FrozenOracle.PROFILE.equals(profile)
                    || sourceSha256 == null || !sourceSha256.matches("[a-f0-9]{64}")
                    || promptSha256 == null || !promptSha256.matches("[a-f0-9]{64}") || development == null)
                throw new IllegalArgumentException("invalid F3 host evidence");
            development = development.deepCopy();
        }
        @Override public ObjectNode development() { return development.deepCopy(); }
        @Override public String toString() { return "InjectionEvidence[caseId=F3, privateEvidence=<redacted>]"; }
    }

    private static void requireOutsideVcs(Path root) throws IOException {
        for (Path p = root; p != null; p = p.getParent()) for (String vcs : List.of(".git", ".hg", ".svn"))
            if (Files.exists(p.resolve(vcs), LinkOption.NOFOLLOW_LINKS)) throw new IOException("F3 source must remain outside VCS");
    }
    private static void requireInventory(Path directory, Set<String> files, boolean runtime) throws IOException {
        var expected = new HashSet<>(files);
        for (String name : files) for (Path p = Path.of(name).getParent(); p != null; p = p.getParent()) expected.add(p.toString());
        var actual = new HashSet<String>();
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.toList()) {
                if (Files.isSymbolicLink(path) || !path.equals(path.toRealPath())) throw new IOException("unsafe F3 fixture entry");
                var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isDirectory()) {
                    String mode = PosixFilePermissions.toString(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
                    if (!(runtime ? mode.equals("rwx------") : Set.of("rwx------", "r-x------").contains(mode)))
                        throw new IOException("unsafe F3 fixture directory permissions");
                } else if (!attributes.isRegularFile() || attributes.fileKey() == null
                        || ((Number)Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1)
                    throw new IOException("unsafe F3 fixture file");
                if (!path.equals(directory)) actual.add(directory.relativize(path).toString());
                if (actual.size() > expected.size()) throw new IOException("extra F3 fixture entry");
            }
        }
        if (!actual.equals(expected)) throw new IOException("F3 fixture inventory differs");
    }
    private static Capture read(Path root, FormalExecutionPlan.VerifierDependency entry) throws IOException {
        Path file = entry.sourcePath();
        if (!"0400".equals(entry.mode()) || entry.size() < 0 || entry.size() > F3FrozenOracle.MAX_BYTES
                || !root.resolve(entry.frozenPath()).equals(file) || Files.isSymbolicLink(file) || !file.equals(file.toRealPath()))
            throw new IOException("unsafe frozen F3 file path or mode");
        Map<Path, String> parents = new LinkedHashMap<>();
        for (Path p = file.getParent(); p != null; p = p.getParent()) {
            var attributes = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.fileKey() == null || !p.equals(p.toRealPath())
                    || !Set.of("rwx------", "r-x------").contains(PosixFilePermissions.toString(Files.getPosixFilePermissions(p))))
                throw new IOException("unsafe frozen F3 parent");
            parents.put(p, attributes.fileKey().toString()); if (p.equals(root)) break;
        }
        var before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.fileKey() == null || before.size() != entry.size() || !privateSingleFile(file))
            throw new IOException("unsafe frozen F3 file identity");
        byte[] bytes;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(F3FrozenOracle.MAX_BYTES + 1); }
        var after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.fileKey().equals(after.fileKey()) || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || after.size() != entry.size() || bytes.length != entry.size() || !privateSingleFile(file)
                || !F1BoundarySession.sha(bytes).equals(entry.sha256())) throw new IOException("frozen F3 file changed");
        return new Capture(entry, before.fileKey().toString(), Map.copyOf(parents), bytes);
    }
    private static boolean privateSingleFile(Path file) throws IOException {
        return ((Number)Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() == 1
                && Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("r--------"));
    }
    private record Capture(FormalExecutionPlan.VerifierDependency entry, String fileKey, Map<Path, String> parentKeys, byte[] bytes) { }
}
