package com.paicli.eval.benchmark;

import com.paicli.eval.benchmark.formal.*;
import com.paicli.eval.benchmark.relay.F2CommandAudit;
import com.paicli.eval.benchmark.safety.F2FrozenOracle;
import com.paicli.tool.CommandExecutionObserver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** F2-only source, readonly diagnostic mount and host-return binding; not a generic mount API. */
final class FormalCommandBinding {
    private static final String FIXTURE = "fixtures/final/F2";
    static final String CONTAINER_DIAGNOSTIC = "/workspace/diagnose.py";
    private final Path root;
    private final FormalExecutionPlan.CasePlan plan;
    private final F2FrozenOracle oracle;
    private final List<Capture> captures;

    private FormalCommandBinding(Path root, FormalExecutionPlan.CasePlan plan,
            F2FrozenOracle oracle, List<Capture> captures) {
        this.root = root; this.plan = plan; this.oracle = oracle; this.captures = List.copyOf(captures);
    }
    static boolean supports(FormalExecutionPlan.CasePlan plan) {
        return plan != null && "F2".equals(plan.id()) && "none".equals(plan.mockProfile())
                && plan.mode() == FinalExecutableSuiteContract.Mode.REACT
                && plan.toolProfile() == FinalExecutableSuiteContract.ToolProfile.LOCAL_COMMAND
                && plan.evidenceRequirements().containsAll(Set.of("command_audit", "provider_turns"));
    }
    static FormalCommandBinding capture(FormalExecutionPlan.CasePlan plan) throws IOException {
        if (!supports(plan)) throw new IOException("unsupported F2 binding");
        var source = plan.verifier().dependencies().stream().filter(d -> d.frozenPath().equals(F2FrozenOracle.PATH)).findFirst()
                .orElseThrow(() -> new IOException("F2 source is not registered"));
        if (!"0400".equals(source.mode())) throw new IOException("F2 source must be frozen readonly");
        Path root = source.sourcePath();
        for (int i = 0; i < Path.of(source.frozenPath()).getNameCount(); i++) root = root.getParent();
        if (root == null) throw new IOException("F2 frozen root unavailable");
        for (Path p = root; p != null; p = p.getParent()) for (String vcs : List.of(".git", ".hg", ".svn"))
            if (Files.exists(p.resolve(vcs), LinkOption.NOFOLLOW_LINKS)) throw new IOException("F2 source must remain outside VCS");
        var captures = new ArrayList<Capture>(); captures.add(read(root, source));
        var oracle = F2FrozenOracle.parse(captures.get(0).bytes());
        if (!oracle.prompt().equals(plan.prompt())) throw new IOException("F2 frozen prompt mismatch");
        var fixture = plan.fixture();
        if (fixture.kind() != FormalBenchmarkPreflight.FixtureKind.DIRECTORY || !FIXTURE.equals(fixture.frozenPath())
                || !root.resolve(FIXTURE).equals(fixture.sourcePath()) || fixture.fileCount() != oracle.files().size())
            throw new IOException("F2 frozen fixture identity mismatch");
        var expected = new HashSet<String>(); for (String name : oracle.files().keySet()) expected.add(FIXTURE + "/" + name);
        if (!expected.equals(fixture.files().stream().map(FormalExecutionPlan.FixtureFile::frozenPath).collect(java.util.stream.Collectors.toSet())))
            throw new IOException("F2 frozen fixture paths mismatch");
        for (var file : fixture.files()) {
            if (!"0400".equals(file.mode())) throw new IOException("F2 fixture must be frozen readonly");
            var captured = read(root, new FormalExecutionPlan.VerifierDependency(file.frozenPath(), root.resolve(file.frozenPath()), file.mode(), file.size(), file.sha256()));
            String relative = file.frozenPath().substring(FIXTURE.length() + 1);
            if (!Arrays.equals(captured.bytes(), oracle.files().get(relative).getBytes(StandardCharsets.UTF_8)))
                throw new IOException("F2 frozen fixture content mismatch");
            captures.add(captured);
        }
        var binding = new FormalCommandBinding(root, plan, oracle, captures); binding.verifyUnchanged(); return binding;
    }
    void verifyUnchanged() throws IOException {
        for (var prior : captures) if (!prior.fileKey().equals(read(root, prior.dependency()).fileKey()))
            throw new IOException("F2 frozen file replaced");
        requireInventory(root.resolve(FIXTURE), oracle.files().keySet());
    }
    Session newSession() throws IOException { verifyUnchanged(); return new Session(this); }

    static final class Session {
        private final FormalCommandBinding binding;
        private final F2CommandAudit audit;
        private boolean started, finished;
        private Path workspace, home, diagnostic;
        private Capture staged, underlying;
        private BenchmarkCoordinatorMain.WorkerExecution result;
        private BenchmarkVerifierWorkspaceSnapshot.Snapshot snapshot;
        private Session(FormalCommandBinding binding) {
            this.binding = binding; audit = new F2CommandAudit(binding.oracle.prompt());
        }
        synchronized void begin(BenchmarkProtocol.WorkerRequest request, Path workspace, Path home) throws IOException {
            if (started) throw new IOException("F2 frozen session is single-use");
            started = true; binding.verifyUnchanged();
            var limits = request.agentLimits();
            if (!binding.oracle.prompt().equals(request.prompt()) || !"REACT".equalsIgnoreCase(request.mode())
                    || request.toolProfile() != BenchmarkToolProfile.LOCAL_COMMAND
                    || limits.contextWindowCapTokens() != 1_000_000 || limits.maxOutputTokensPerCall() != 16_384
                    || limits.tokenBudget() != binding.plan.tokenBudget() || limits.hardMaxIterations() != binding.plan.hardMaxIterations()
                    || limits.stagnationWindow() != binding.plan.stagnationWindow()
                    || workspace.startsWith(binding.root) || home.startsWith(binding.root)
                    || !workspace.toString().equals(request.workspace()) || !home.toString().equals(request.home())
                    || !workspace.getParent().equals(home.getParent()) || !workspace.getParent().toString().equals(request.episodeDirectory())
                    || !workspace.equals(workspace.toRealPath()) || !home.equals(home.toRealPath()))
                throw new IOException("F2 frozen request mismatch");
            this.workspace = workspace; this.home = home;
            requireInventory(workspace, binding.oracle.files().keySet());
            for (var file : binding.oracle.files().entrySet()) {
                Path path = workspace.resolve(file.getKey());
                byte[] expected = file.getValue().getBytes(StandardCharsets.UTF_8);
                var captured = readLocal(path, "0600", expected);
                if (file.getKey().equals("diagnose.py")) underlying = captured;
            }
            Path directory = Files.createDirectory(workspace.getParent().resolve("f2-diagnostic"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            diagnostic = directory.resolve("diagnose.py");
            Files.createFile(diagnostic, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.write(diagnostic, underlying.bytes(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            Files.setPosixFilePermissions(diagnostic, PosixFilePermissions.fromString("r--------"));
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"));
            staged = readLocal(diagnostic, "0400", underlying.bytes());
        }
        synchronized Path mountSource() throws IOException { verifyDiagnostic(); return diagnostic; }
        F2CommandAudit audit() { return audit; }
        synchronized void finish(BenchmarkCoordinatorMain.WorkerExecution execution) throws IOException {
            if (!started || finished) throw new IOException("F2 dispatch lifecycle mismatch");
            finished = true; result = execution; verifyDiagnostic();
        }
        synchronized void requireReturned(BenchmarkCoordinatorMain.WorkerExecution execution) throws IOException {
            if (!finished || result == null || result != execution) throw new IOException("F2 dispatch result is not host-bound");
            verifyTerminalUnchanged();
        }
        synchronized void verifyTerminalUnchanged() throws IOException {
            if (!finished) throw new IOException("F2 dispatch not finished");
            binding.verifyUnchanged(); verifyDiagnostic();
        }
        private void verifyDiagnostic() throws IOException {
            if (staged == null || underlying == null || diagnostic == null) throw new IOException("F2 diagnostic not staged");
            if (!Files.getPosixFilePermissions(diagnostic.getParent()).equals(PosixFilePermissions.fromString("r-x------"))
                    || !staged.fileKey().equals(readLocal(diagnostic, "0400", staged.bytes()).fileKey()))
                throw new IOException("F2 diagnostic source or mount changed");
        }
        /** Candidate-controlled filesystem damage is a valid failure, never a frozen-source defect. */
        synchronized boolean candidateInputsSnapshotable() throws IOException {
            requireReturned(result);
            return candidateInputsSnapshotableAfterStop();
        }
        synchronized boolean candidateInputsSnapshotableAfterStop() throws IOException {
            if (!started || workspace == null) throw new IOException("F2 command session not started");
            var directories = new LinkedHashSet<Path>(); directories.add(workspace);
            for (String file : binding.oracle.files().keySet()) {
                for (Path p = workspace.resolve(file).getParent(); p != null && p.startsWith(workspace); p = p.getParent()) directories.add(p);
            }
            for (Path directory : directories) {
                if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) continue; // Deletion is replayed from the resulting snapshot.
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) continue; // General boundary checks retain symlink/special-file classification.
                try { if (!Files.getPosixFilePermissions(directory).equals(PosixFilePermissions.fromString("rwx------"))) return false; }
                catch (NoSuchFileException | AccessDeniedException changed) { return false; }
            }
            for (String name : binding.oracle.files().keySet()) {
                Path file = workspace.resolve(name);
                if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) continue;
                try { if (!Files.getPosixFilePermissions(file).equals(PosixFilePermissions.fromString("rw-------"))) return false; }
                catch (NoSuchFileException | AccessDeniedException changed) { return false; }
            }
            return true;
        }
        synchronized BenchmarkVerifierWorkspaceSnapshot.Snapshot snapshot(Path target) throws IOException {
            requireReturned(result);
            if (snapshot != null || !workspace.getParent().equals(target.getParent())) throw new IOException("F2 snapshot lifecycle/path mismatch");
            snapshot = BenchmarkVerifierWorkspaceSnapshot.create(workspace, target); return snapshot;
        }
        synchronized CommandEvidence evidence(BenchmarkVerifierWorkspaceSnapshot.Snapshot supplied) throws IOException {
            requireReturned(result); audit.requireHealthy();
            if (snapshot == null || snapshot != supplied) throw new IOException("F2 snapshot is not host-bound");
            snapshot.verifyUnchanged();
            var terminal = audit.terminal();
            if (terminal == null || result.response() == null || !terminal.answer().equals(result.response().answer()))
                throw new IOException("F2 terminal does not match dispatch result");
            var tools = terminal.toolExecutions();
            if (tools.size() != result.toolExecutions().size()) throw new IOException("F2 terminal tools mismatch");
            for (int i = 0; i < tools.size(); i++) {
                var wire = tools.get(i); var actual = result.toolExecutions().get(i);
                if (actual.ordinal() != i + 1 || wire.ordinal() != i + 1 || !wire.callId().equals(actual.callId()) || !wire.toolName().equals(actual.toolName())
                        || !wire.argumentsJson().equals(actual.argumentsJson()) || wire.elapsedMillis() != actual.elapsedMillis()
                        || wire.successful() != actual.successful() || wire.timedOut() != actual.timedOut() || wire.resultChars() != actual.resultChars()
                        || !wire.resultSha256().equals(actual.resultSha256()) || !wire.resultPreview().equals(actual.resultPreview()))
                    throw new IOException("F2 terminal tools differ from dispatch result");
            }
            return new CommandEvidence(1, "F2", F2FrozenOracle.PROFILE, binding.captures.get(0).dependency().sha256(),
                    F1BoundarySession.sha(binding.oracle.prompt().getBytes(StandardCharsets.UTF_8)), staged.dependency().sha256(),
                    new CommandObservation(1, "CANDIDATE_COMMAND_OBSERVATIONS_NOT_OS_AUDIT", terminal.commandObservations(), terminal.commandObservationFailures()),
                    audit.requestedTools(), new TerminalEvidence(terminal.answer(), result.toolExecutions()));
        }
        synchronized BenchmarkCoordinatorMain.WorkerExecution execution() throws IOException { requireReturned(result); return result; }
        /** A damaged Candidate tree is not readable evidence. All host-owned siblings still undergo exact canary scanning. */
        synchronized boolean containsSecretsOutsideDamagedWorkspace(String secret) throws IOException {
            requireReturned(result);
            if (candidateInputsSnapshotableAfterStop()) throw new IOException("F2 damaged workspace exclusion is not justified");
            try (var children = Files.list(workspace.getParent())) {
                for (Path child : children.toList())
                    if (!child.equals(workspace) && BenchmarkSecretCanary.containsInTree(child, secret)) return true;
            }
            return false;
        }
        synchronized void requirePaths(Path episode, Path workspace, Path home) throws IOException {
            if (!started || !Objects.equals(this.workspace, workspace) || !Objects.equals(this.home, home) || !workspace.getParent().equals(episode))
                throw new IOException("F2 evidence episode mismatch");
        }
    }
    record CommandObservation(int schemaVersion, String kind, List<CommandExecutionObserver.Event> commandObservations, long commandObservationFailures) { }
    record TerminalEvidence(String answer, List<BenchmarkToolExecutionEvidence> toolExecutions) { }
    record CommandEvidence(int schemaVersion, String caseId, String profile, String sourceSha256, String promptSha256,
            String diagnosticSha256, CommandObservation observation,
            List<com.paicli.eval.benchmark.relay.BenchmarkRelayProtocol.WireToolCall> requestedTools, TerminalEvidence terminal) { }

    private static void requireInventory(Path directory, Set<String> files) throws IOException {
        var expected = new HashSet<>(files);
        for (String name : files) for (Path p = Path.of(name).getParent(); p != null; p = p.getParent()) expected.add(p.toString());
        var actual = new HashSet<String>();
        try (var walk = Files.walk(directory)) {
            for (Path p : walk.toList()) {
                if (p.equals(directory)) continue;
                if (Files.isSymbolicLink(p) || !p.equals(p.toRealPath())) throw new IOException("F2 unsafe fixture entry");
                actual.add(directory.relativize(p).toString());
            }
        }
        if (!actual.equals(expected)) throw new IOException("F2 fixture inventory changed");
    }
    private static Capture readLocal(Path path, String mode, byte[] expected) throws IOException {
        Path root = path.getParent();
        return read(root, new FileIdentity(path.getFileName().toString(), path, mode,
                expected.length, F1BoundarySession.sha(expected)));
    }
    private static Capture read(Path root, FormalExecutionPlan.VerifierDependency entry) throws IOException {
        return read(root, new FileIdentity(entry.frozenPath(), entry.sourcePath(), entry.mode(), entry.size(), entry.sha256()));
    }
    private static Capture read(Path root, FileIdentity entry) throws IOException {
        Path file = entry.sourcePath();
        String permission = switch (entry.mode()) { case "0400" -> "r--------"; case "0600" -> "rw-------"; default -> throw new IOException("F2 file mode"); };
        if (entry.size() > F2FrozenOracle.MAX_BYTES || !root.resolve(entry.frozenPath()).equals(file)
                || Files.isSymbolicLink(file) || !file.equals(file.toRealPath())) throw new IOException("unsafe frozen F2 path");
        for (Path p = file.getParent(); p != null; p = p.getParent()) {
            if (Files.isSymbolicLink(p) || !p.equals(p.toRealPath())
                    || !Set.of("rwx------", "r-x------").contains(PosixFilePermissions.toString(Files.getPosixFilePermissions(p))))
                throw new IOException("unsafe frozen F2 parent");
            if (p.equals(root)) break;
        }
        var before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.fileKey() == null || before.size() != entry.size() || !privateSingle(file, permission))
            throw new IOException("unsafe frozen F2 file");
        byte[] bytes;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(F2FrozenOracle.MAX_BYTES + 1); }
        var after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.fileKey().equals(after.fileKey()) || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || after.size() != entry.size() || bytes.length != entry.size() || !privateSingle(file, permission)
                || !F1BoundarySession.sha(bytes).equals(entry.sha256())) throw new IOException("frozen F2 file changed");
        return new Capture(entry, before.fileKey().toString(), bytes);
    }
    private static boolean privateSingle(Path file, String mode) throws IOException {
        return ((Number)Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() == 1
                && Files.getPosixFilePermissions(file).equals(PosixFilePermissions.fromString(mode));
    }
    private record FileIdentity(String frozenPath, Path sourcePath, String mode, long size, String sha256) { }
    private record Capture(FileIdentity dependency, String fileKey, byte[] bytes) { }
}
