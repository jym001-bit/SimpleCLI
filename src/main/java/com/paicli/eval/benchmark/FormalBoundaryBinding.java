package com.paicli.eval.benchmark;

import com.paicli.eval.benchmark.formal.*;
import com.paicli.eval.benchmark.safety.F1FrozenOracle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** F1-only frozen source binding. No generic outside mount or symlink capability. */
final class FormalBoundaryBinding {
    private static final String FIXTURE = "fixtures/final/F1";
    private final Path root;
    private final F1FrozenOracle oracle;
    private final List<Capture> captures;
    private FormalBoundaryBinding(Path root, F1FrozenOracle oracle, List<Capture> captures) {
        this.root = root; this.oracle = oracle; this.captures = List.copyOf(captures);
    }
    static boolean supports(FormalExecutionPlan.CasePlan plan) {
        return plan != null && "F1".equals(plan.id()) && "none".equals(plan.mockProfile())
                && plan.mode() == FinalExecutableSuiteContract.Mode.REACT
                && plan.toolProfile() == FinalExecutableSuiteContract.ToolProfile.FILE_ONLY
                && plan.evidenceRequirements().contains("boundary_state");
    }
    static FormalBoundaryBinding capture(FormalExecutionPlan.CasePlan plan) throws IOException {
        if (!supports(plan)) throw new IOException("unsupported F1 binding");
        var source = plan.verifier().dependencies().stream().filter(d -> d.frozenPath().equals(F1FrozenOracle.PATH)).findFirst()
                .orElseThrow(() -> new IOException("F1 source is not registered"));
        Path root = source.sourcePath();
        for (int i = 0; i < Path.of(source.frozenPath()).getNameCount(); i++) root = root.getParent();
        if (root == null) throw new IOException("F1 frozen root unavailable");
        for (Path p = root; p != null; p = p.getParent()) for (String vcs : List.of(".git", ".hg", ".svn"))
            if (Files.exists(p.resolve(vcs), LinkOption.NOFOLLOW_LINKS)) throw new IOException("F1 source must remain outside VCS");
        var captures = new ArrayList<Capture>(); captures.add(read(root, source));
        var oracle = F1FrozenOracle.parse(captures.get(0).bytes());
        if (!oracle.prompt().equals(plan.prompt())) throw new IOException("F1 frozen prompt mismatch");
        var fixture = plan.fixture();
        if (fixture.kind() != FormalBenchmarkPreflight.FixtureKind.DIRECTORY || !FIXTURE.equals(fixture.frozenPath())
                || !root.resolve(FIXTURE).equals(fixture.sourcePath()) || fixture.fileCount() != 2)
            throw new IOException("F1 frozen fixture identity mismatch");
        var expected = new HashSet<String>(); for (String name : oracle.files().keySet()) expected.add(FIXTURE + "/" + name);
        if (!expected.equals(fixture.files().stream().map(FormalExecutionPlan.FixtureFile::frozenPath).collect(java.util.stream.Collectors.toSet())))
            throw new IOException("F1 frozen fixture paths mismatch");
        for (var file : fixture.files()) {
            var captured = read(root, new FormalExecutionPlan.VerifierDependency(file.frozenPath(), root.resolve(file.frozenPath()), file.mode(), file.size(), file.sha256()));
            if (!Arrays.equals(captured.bytes(), oracle.files().get(Path.of(file.frozenPath()).getFileName().toString()).getBytes(StandardCharsets.UTF_8)))
                throw new IOException("F1 frozen fixture content mismatch");
            captures.add(captured);
        }
        var binding = new FormalBoundaryBinding(root, oracle, captures); binding.verifyUnchanged(); return binding;
    }
    void verifyUnchanged() throws IOException {
        for (var prior : captures) if (!prior.fileKey().equals(read(root, prior.dependency()).fileKey()))
            throw new IOException("F1 frozen file replaced");
        try (var files = Files.list(root.resolve(FIXTURE))) {
            if (!files.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()).equals(oracle.files().keySet()))
                throw new IOException("F1 frozen fixture inventory changed");
        }
    }
    Session newSession() throws IOException { verifyUnchanged(); return new Session(this); }

    static final class Session {
        private final FormalBoundaryBinding binding;
        private boolean started, finished;
        private Path workspace, home;
        private F1BoundarySession boundary;
        private BenchmarkCoordinatorMain.WorkerExecution result;
        private BenchmarkVerifierWorkspaceSnapshot.Snapshot snapshot;
        private Session(FormalBoundaryBinding binding) { this.binding = binding; }
        synchronized F1BoundarySession begin(BenchmarkProtocol.WorkerRequest request, Path workspace, Path home) throws IOException {
            if (started) throw new IOException("F1 frozen session is single-use");
            started = true;
            binding.verifyUnchanged();
            if (!binding.oracle.prompt().equals(request.prompt()) || !"REACT".equalsIgnoreCase(request.mode())
                    || request.toolProfile() != BenchmarkToolProfile.FILE_ONLY
                    || request.agentLimits().contextWindowCapTokens() != 1_000_000 || request.agentLimits().maxOutputTokensPerCall() != 16_384
                    || workspace.startsWith(binding.root) || home.startsWith(binding.root)
                    || !workspace.toString().equals(request.workspace()) || !home.toString().equals(request.home())
                    || !workspace.getParent().toString().equals(request.episodeDirectory()))
                throw new IOException("F1 frozen request mismatch");
            this.workspace = workspace; this.home = home;
            boundary = F1BoundarySession.prepareFrozen(workspace, home, binding.oracle);
            return boundary;
        }
        synchronized void finish(BenchmarkCoordinatorMain.WorkerExecution execution) throws IOException {
            if (!started || finished || boundary == null) throw new IOException("F1 dispatch lifecycle mismatch");
            finished = true; result = execution;
            boundary.evidence();
        }
        synchronized void requireReturned(BenchmarkCoordinatorMain.WorkerExecution execution) throws IOException {
            if (!finished || result == null || result != execution) throw new IOException("F1 dispatch result is not host-bound");
            verifyTerminalUnchanged();
        }
        synchronized void verifyTerminalUnchanged() throws IOException {
            if (!finished || boundary == null) throw new IOException("F1 dispatch not finished");
            binding.verifyUnchanged(); boundary.verifyTerminalUnchanged();
        }
        synchronized BenchmarkVerifierWorkspaceSnapshot.Snapshot snapshot(Path target) throws IOException {
            requireReturned(result);
            if (snapshot != null || !workspace.getParent().equals(target.getParent())) throw new IOException("F1 snapshot lifecycle/path mismatch");
            snapshot = BenchmarkVerifierWorkspaceSnapshot.createBoundaryProjection(workspace, target, boundary);
            return snapshot;
        }
        synchronized BoundaryEvidence evidence(BenchmarkVerifierWorkspaceSnapshot.Snapshot supplied) throws IOException {
            requireReturned(result);
            if (snapshot == null || snapshot != supplied) throw new IOException("F1 snapshot is not host-bound");
            snapshot.verifyUnchanged();
            return new BoundaryEvidence(1, "F1", F1FrozenOracle.PROFILE, capturesSha(),
                    F1BoundarySession.sha(binding.oracle.prompt().getBytes(StandardCharsets.UTF_8)), "HOST_PORTAL_OMITTED_V1", boundary.evidence());
        }
        private String capturesSha() { return binding.captures.get(0).dependency().sha256(); }
        synchronized BenchmarkCoordinatorMain.WorkerExecution execution() throws IOException { requireReturned(result); return result; }
        synchronized void requirePaths(Path episode, Path workspace, Path home) throws IOException {
            if (!started || !Objects.equals(this.workspace, workspace) || !Objects.equals(this.home, home) || !workspace.getParent().equals(episode))
                throw new IOException("F1 evidence episode mismatch");
        }
    }
    record BoundaryEvidence(int schemaVersion, String caseId, String profile, String sourceSha256,
                            String promptSha256, String workspaceProjection, F1BoundarySession.Evidence observation) { }

    private static Capture read(Path root, FormalExecutionPlan.VerifierDependency entry) throws IOException {
        Path file = entry.sourcePath();
        if (!"0400".equals(entry.mode()) || entry.size() > F1FrozenOracle.MAX_BYTES || !root.resolve(entry.frozenPath()).equals(file)
                || Files.isSymbolicLink(file) || !file.equals(file.toRealPath())) throw new IOException("unsafe frozen F1 path");
        for (Path p = file.getParent(); p != null; p = p.getParent()) {
            if (Files.isSymbolicLink(p) || !p.equals(p.toRealPath())
                    || !Set.of("rwx------", "r-x------").contains(PosixFilePermissions.toString(Files.getPosixFilePermissions(p))))
                throw new IOException("unsafe frozen F1 parent");
            if (p.equals(root)) break;
        }
        var before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.fileKey() == null || before.size() != entry.size() || !privateSingle(file))
            throw new IOException("unsafe frozen F1 file");
        byte[] bytes;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(F1FrozenOracle.MAX_BYTES + 1); }
        var after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.fileKey().equals(after.fileKey()) || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || after.size() != entry.size() || bytes.length != entry.size() || !privateSingle(file)
                || !F1BoundarySession.sha(bytes).equals(entry.sha256())) throw new IOException("frozen F1 file changed");
        return new Capture(entry, before.fileKey().toString(), bytes);
    }
    private static boolean privateSingle(Path file) throws IOException {
        return ((Number)Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() == 1
                && Files.getPosixFilePermissions(file).equals(PosixFilePermissions.fromString("r--------"));
    }
    private record Capture(FormalExecutionPlan.VerifierDependency dependency, String fileKey, byte[] bytes) { }
}
