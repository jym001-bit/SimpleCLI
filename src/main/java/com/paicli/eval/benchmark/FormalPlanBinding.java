package com.paicli.eval.benchmark;

import com.paicli.eval.benchmark.formal.*;
import com.paicli.eval.benchmark.plan.E1FrozenOracle;
import com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol;
import com.paicli.eval.benchmark.relay.PlanRequestAudit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** E1 frozen-source/host-audit binding. This primitive does not open formal admission. */
final class FormalPlanBinding {
    private static final String FIXTURE = "fixtures/final/E1";
    private final Path root;
    private final E1FrozenOracle oracle;
    private final List<Capture> captures;
    private final String sourceSha256;

    private FormalPlanBinding(Path root, E1FrozenOracle oracle, List<Capture> captures) {
        this.root = root; this.oracle = oracle; this.captures = List.copyOf(captures);
        sourceSha256 = captures.get(0).entry().sha256();
    }
    static boolean supports(FormalExecutionPlan.CasePlan plan) {
        return plan != null && "E1".equals(plan.id()) && "none".equals(plan.mockProfile())
                && plan.mode() == FinalExecutableSuiteContract.Mode.PLAN
                && plan.toolProfile() == FinalExecutableSuiteContract.ToolProfile.FILE_ONLY
                && plan.evidenceRequirements().containsAll(List.of("plan_audit", "scoped_request_fingerprints"));
    }
    static FormalPlanBinding capture(FormalExecutionPlan.CasePlan plan) throws IOException {
        if (!supports(plan)) throw new IOException("unsupported frozen Plan binding");
        var source = plan.verifier().dependencies().stream().filter(d -> d.frozenPath().equals(E1FrozenOracle.PATH)).findFirst()
                .orElseThrow(() -> new IOException("E1 source is not registered"));
        Path root = source.sourcePath();
        for (int i = 0; i < Path.of(source.frozenPath()).getNameCount(); i++) root = root.getParent();
        if (root == null) throw new IOException("E1 source root unavailable");
        for (Path dir = root; dir != null; dir = dir.getParent()) for (String vcs : List.of(".git", ".hg", ".svn"))
            if (Files.exists(dir.resolve(vcs), LinkOption.NOFOLLOW_LINKS)) throw new IOException("E1 source must remain outside version control");
        var captured = new ArrayList<Capture>(); captured.add(read(root, source, E1FrozenOracle.MAX_BYTES));
        var oracle = E1FrozenOracle.parse(captured.get(0).bytes());
        if (!oracle.prompt().equals(plan.prompt())) throw new IOException("E1 prompt differs from frozen source");
        var fixture = plan.fixture();
        if (fixture.kind() != FormalBenchmarkPreflight.FixtureKind.DIRECTORY || !FIXTURE.equals(fixture.frozenPath())
                || !root.resolve(FIXTURE).equals(fixture.sourcePath()) || fixture.fileCount() != 2)
            throw new IOException("E1 fixture identity differs from source");
        var expected = new HashSet<String>();
        for (String name : oracle.files().keySet()) expected.add(FIXTURE + "/" + name);
        if (!expected.equals(fixture.files().stream().map(FormalExecutionPlan.FixtureFile::frozenPath).collect(java.util.stream.Collectors.toSet())))
            throw new IOException("E1 fixture contains unexpected files");
        for (var file : fixture.files()) {
            var dependency = new FormalExecutionPlan.VerifierDependency(file.frozenPath(), root.resolve(file.frozenPath()), file.mode(), file.size(), file.sha256());
            var bytes = read(root, dependency, 32_768); captured.add(bytes);
            if (!Arrays.equals(bytes.bytes(), oracle.files().get(Path.of(file.frozenPath()).getFileName().toString()).getBytes(StandardCharsets.UTF_8)))
                throw new IOException("E1 fixture bytes differ from oracle");
        }
        var binding = new FormalPlanBinding(root, oracle, captured); binding.verifyUnchanged(); return binding;
    }
    void verifyUnchanged() throws IOException {
        for (Capture old : captures) {
            Capture now = read(root, old.entry(), old.entry().frozenPath().equals(E1FrozenOracle.PATH) ? E1FrozenOracle.MAX_BYTES : 32_768);
            if (!old.fileKey().equals(now.fileKey())) throw new IOException("E1 frozen file was replaced");
        }
        Path fixture = root.resolve(FIXTURE);
        try (var entries = Files.list(fixture)) {
            if (!entries.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()).equals(oracle.files().keySet()))
                throw new IOException("E1 frozen fixture changed");
        }
    }
    Session newSession() throws IOException { verifyUnchanged(); return new Session(this); }

    /** A fresh, single-use host object, never reconstructed from Candidate files or JSON. */
    static final class Session {
        private final FormalPlanBinding binding;
        private final PlanRequestAudit audit;
        private boolean started, finished;
        private Path workspace, home;
        private BenchmarkCoordinatorMain.WorkerExecution result;
        private Session(FormalPlanBinding binding) { this.binding = binding; audit = new PlanRequestAudit(binding.oracle.prompt()); }
        synchronized void begin(BenchmarkProtocol.WorkerRequest request, Path workspace, Path home) throws IOException {
            if (started) throw new IOException("E1 host session cannot be reused");
            binding.verifyUnchanged();
            if (!"PLAN".equalsIgnoreCase(request.mode().trim()) || request.toolProfile() != BenchmarkToolProfile.FILE_ONLY
                    || !binding.oracle.prompt().equals(request.prompt())
                    || request.agentLimits().contextWindowCapTokens() != 1_000_000 || request.agentLimits().maxOutputTokensPerCall() != 16_384)
                throw new IOException("E1 request differs from bound task/caps");
            Path real = workspace.toRealPath();
            if (!real.equals(workspace) || Files.isSymbolicLink(workspace) || !home.equals(home.toRealPath())
                    || !real.getParent().equals(home.getParent()) || real.startsWith(binding.root) || home.startsWith(binding.root)
                    || !real.toString().equals(request.workspace()) || !home.toString().equals(request.home())
                    || !real.getParent().toString().equals(request.episodeDirectory()))
                throw new IOException("E1 workspace/home must be canonical and separate from frozen source");
            try (var files = Files.list(real)) {
                if (!files.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()).equals(binding.oracle.files().keySet()))
                    throw new IOException("E1 Candidate fixture has unexpected files");
            }
            for (var entry : binding.oracle.files().entrySet()) {
                Path file = real.resolve(entry.getKey()); byte[] expected = entry.getValue().getBytes(StandardCharsets.UTF_8);
                if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || ((Number)Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1
                        || Files.size(file) != expected.length || !Arrays.equals(Files.readAllBytes(file), expected))
                    throw new IOException("E1 Candidate fixture differs from frozen bytes");
            }
            for (Path dir : List.of(real, home, real.getParent()))
                if (!Files.getPosixFilePermissions(dir).equals(PosixFilePermissions.fromString("rwx------")))
                    throw new IOException("E1 dispatch directories must be owner-only");
            this.workspace = real; this.home = home;
            started = true;
        }
        synchronized PlanRequestAudit audit() throws IOException {
            if (!started || finished) throw new IOException("E1 audit is outside its dispatch");
            return audit;
        }
        synchronized void finish(BenchmarkCoordinatorMain.WorkerExecution result) {
            if (!started || finished) throw new IllegalStateException("E1 dispatch lifecycle mismatch");
            this.result = result; finished = true;
        }
        /** Diagnostic snapshot survives launch failure or source drift; not a scoring attestation. */
        synchronized PlanRequestAudit.Snapshot retainedAudit() { return audit.snapshot(); }
        synchronized void requireReturned(BenchmarkCoordinatorMain.WorkerExecution execution) throws IOException {
            if (!started || !finished || result == null || result != execution)
                throw new IOException("E1 dispatch did not return its bound host result");
        }
        synchronized PlanEvidence evidence() throws IOException {
            if (!started || !finished || result == null || result.response() == null)
                throw new IOException("E1 host evidence requires a completed dispatch result");
            binding.verifyUnchanged();
            return new PlanEvidence(1, "E1", E1FrozenOracle.PROFILE, BenchmarkRelayProtocol.VERSION,
                    binding.sourceSha256, BenchmarkRelayProtocol.textSha256(binding.oracle.prompt()), audit.snapshot(),
                    result.response().metrics().scopedRequestFingerprints());
        }
        synchronized TracingLlmClient.Metrics metrics() throws IOException {
            if (!finished || result == null || result.response() == null) throw new IOException("E1 dispatch metrics unavailable");
            return result.response().metrics();
        }
        synchronized BenchmarkCoordinatorMain.WorkerExecution execution() throws IOException {
            metrics(); return result;
        }
        synchronized void requirePaths(Path episode, Path workspace, Path home) throws IOException {
            if (!started || !this.workspace.equals(workspace) || !this.home.equals(home) || !this.workspace.getParent().equals(episode))
                throw new IOException("E1 evidence cannot be attached to another episode");
        }
    }
    record PlanEvidence(int schemaVersion, String caseId, String profile, int relayVersion, String sourceSha256,
                        String promptSha256, PlanRequestAudit.Snapshot audit, ScopedRequestFingerprints scopedRequestFingerprints) { }

    private static Capture read(Path root, FormalExecutionPlan.VerifierDependency entry, int maximum) throws IOException {
        Path file = entry.sourcePath();
        if (!"0400".equals(entry.mode()) || entry.size() > maximum || !root.resolve(entry.frozenPath()).equals(file)
                || Files.isSymbolicLink(file) || !file.equals(file.toRealPath())) throw new IOException("unsafe frozen E1 path");
        for (Path dir = file.getParent(); dir != null; dir = dir.getParent()) {
            if (Files.isSymbolicLink(dir) || !dir.equals(dir.toRealPath())
                    || !Set.of("rwx------", "r-x------").contains(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir))))
                throw new IOException("unsafe frozen E1 parent");
            if (dir.equals(root)) break;
        }
        var before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.fileKey() == null || before.size() != entry.size() || !privateSingleFile(file))
            throw new IOException("unsafe frozen E1 file");
        byte[] bytes;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(maximum + 1); }
        var after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.fileKey().equals(after.fileKey()) || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || after.size() != entry.size() || bytes.length != entry.size() || !privateSingleFile(file)
                || !hash(bytes).equals(entry.sha256()))
            throw new IOException("frozen E1 source drift");
        return new Capture(entry, before.fileKey().toString(), bytes);
    }
    private static boolean privateSingleFile(Path file) throws IOException {
        return ((Number)Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() == 1
                && Files.getPosixFilePermissions(file).equals(PosixFilePermissions.fromString("r--------"));
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private record Capture(FormalExecutionPlan.VerifierDependency entry, String fileKey, byte[] bytes) { }
}
