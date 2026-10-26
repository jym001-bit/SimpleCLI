package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.eval.benchmark.finalset.generator.F3BindingTestSource;
import com.paicli.eval.benchmark.formal.FormalExecutionPlan;
import com.paicli.eval.benchmark.relay.*;
import com.paicli.eval.benchmark.safety.F3FrozenOracle;
import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Host binding controls with a real in-memory native Worker, not a model score or formal batch. */
@Timeout(60)
class FormalInjectionBindingTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;
    @BeforeEach void canonicalPrivateRoot() throws Exception { temp = temp.toRealPath(); chmod(temp, "rwx------"); }
    @AfterEach void thawSyntheticFiles() throws Exception {
        try (var paths = Files.walk(temp)) {
            for (Path path : paths.toList()) if (!Files.isSymbolicLink(path)) chmod(path, Files.isDirectory(path) ? "rwx------" : "rw-------");
        }
    }

    @Test void exactFrozenSourceCreatesFreshSingleUseSessions() throws Exception {
        var plan = source("source"); var binding = FormalInjectionBinding.capture(plan);
        assertTrue(FormalInjectionBinding.supports(plan)); assertFalse(FormalInjectionBinding.supports(null)); binding.verifyUnchanged();
        var first = binding.newSession(); var second = binding.newSession(); assertNotSame(first, second);
        assertThrows(IOException.class, first::audit); assertThrows(IOException.class, first::mock);
        assertThrows(IOException.class, first::execution); assertThrows(IOException.class, () -> first.finish(null));
        var dirs = dirs("episode", plan); var request = request(dirs, plan.prompt(), BenchmarkToolProfile.MOCK_MCP_FILE_ONLY, 100_000);
        first.begin(request, dirs.workspace(), dirs.home());
        assertSame(first.audit(), first.audit()); assertSame(first.mock(), first.mock());
        assertThrows(IOException.class, () -> first.begin(request, dirs.workspace(), dirs.home()));
        first.finish(null); assertThrows(IOException.class, first::execution); assertThrows(IOException.class, () -> first.finish(null));
    }

    @Test void wrongPromptProfileCapsAndExtraInputsConsumeNoReusableSessionAuthority() throws Exception {
        var plan = source("source"); var binding = FormalInjectionBinding.capture(plan); var dirs = dirs("episode", plan);
        for (var wrong : List.of(request(dirs, plan.prompt() + "changed", BenchmarkToolProfile.MOCK_MCP_FILE_ONLY, 100_000),
                request(dirs, plan.prompt(), BenchmarkToolProfile.MOCK_MCP, 100_000),
                request(dirs, plan.prompt(), BenchmarkToolProfile.MOCK_MCP_FILE_ONLY, 99_999))) {
            var session = binding.newSession();
            assertThrows(IOException.class, () -> session.begin(wrong, dirs.workspace(), dirs.home()));
            assertThrows(IOException.class, session::audit);
            assertThrows(IOException.class, () -> session.begin(request(dirs, plan.prompt(), BenchmarkToolProfile.MOCK_MCP_FILE_ONLY, 100_000), dirs.workspace(), dirs.home()));
        }
        Files.createDirectory(dirs.workspace().resolve("unregistered-empty-directory"));
        assertThrows(IOException.class, () -> binding.newSession().begin(request(dirs, plan.prompt(), BenchmarkToolProfile.MOCK_MCP_FILE_ONLY, 100_000), dirs.workspace(), dirs.home()));
    }

    @Test void sourceAndFixturePermissionsContentInodesLinksAndExtraEntriesAreRechecked() throws Exception {
        for (String mutation : List.of("source-mode", "source-content", "source-inode", "hardlink", "fixture-mode", "extra-directory")) {
            var plan = source(mutation); var binding = FormalInjectionBinding.capture(plan);
            Path source = temp.resolve(mutation).resolve(F3FrozenOracle.PATH);
            switch (mutation) {
                case "source-mode" -> chmod(source, "rw-------");
                case "source-content" -> { chmod(source, "rw-------"); Files.writeString(source, "{}"); chmod(source, "r--------"); }
                case "source-inode" -> {
                    Path replacement = temp.resolve("replacement.json"); Files.copy(source, replacement); chmod(replacement, "r--------");
                    chmod(source.getParent(), "rwx------"); Files.move(replacement, source, StandardCopyOption.REPLACE_EXISTING); chmod(source.getParent(), "r-x------");
                }
                case "hardlink" -> Files.createLink(temp.resolve("source-hardlink"), source);
                case "fixture-mode" -> chmod(plan.fixture().sourcePath().resolve("README.md"), "rw-------");
                case "extra-directory" -> { chmod(plan.fixture().sourcePath(), "rwx------"); Files.createDirectory(plan.fixture().sourcePath().resolve("extra")); }
            }
            assertThrows(IOException.class, binding::verifyUnchanged, mutation);
            assertThrows(IOException.class, binding::newSession, mutation);
        }
    }

    @Test void runtimeInputPermissionsCannotBorrowFrozenSourceAuthority() throws Exception {
        var plan = source("source"); var binding = FormalInjectionBinding.capture(plan); var dirs = dirs("episode", plan);
        chmod(dirs.workspace().resolve("README.md"), "r--------");
        assertThrows(IOException.class, () -> binding.newSession().begin(request(dirs, plan.prompt(), BenchmarkToolProfile.MOCK_MCP_FILE_ONLY, 100_000), dirs.workspace(), dirs.home()));
        assertFalse(Files.exists(dirs.episode().resolve("f3-source.json")), "reject before private execution-source creation");
    }

    @Test void nativeTerminalAndVerifierSnapshotAreBoundByExactReturnedObjects() throws Exception {
        var plan = source("source"); var binding = FormalInjectionBinding.capture(plan); var dirs = dirs("episode", plan);
        var session = binding.newSession(); var result = nativeTerminal(session, dirs, plan.prompt());
        assertSame(result, session.execution());
        var clonedResult = BenchmarkCoordinatorMain.WorkerExecution.completed(result.response(), result.exitCode(), result.elapsedMillis(), result.diagnostic(), result.toolExecutions());
        assertThrows(IOException.class, () -> session.requireReturned(clonedResult));
        var snapshot = session.snapshot(directory(dirs.episode().resolve("verifier-workspace")));
        var clonedSnapshot = new BenchmarkVerifierWorkspaceSnapshot.Snapshot(snapshot.directory(), snapshot.treeSha256(), snapshot.fileCount(), snapshot.totalBytes());
        assertThrows(IOException.class, () -> session.evidence(clonedSnapshot));
        var evidence = session.evidence(snapshot);
        assertEquals("F3", evidence.caseId()); assertEquals(F3FrozenOracle.PROFILE, evidence.profile());
        assertEquals("done", evidence.development().path("answer").textValue());
        assertEquals(JSON.valueToTree(F3DevelopmentSession.readTree(snapshot.directory())), evidence.development().path("workspaceAfter"));
        evidence.development().put("answer", "caller mutation");
        assertEquals("done", evidence.development().path("answer").textValue());
        assertFalse(Files.exists(dirs.episode().resolve("f3-evidence")), "in-memory evidence snapshot must not write a development envelope");
        assertThrows(IOException.class, () -> session.snapshot(directory(dirs.episode().resolve("second-snapshot"))));
        assertThrows(IOException.class, () -> session.requirePaths(dirs.episode(), dirs.home(), dirs.workspace()));
    }

    @Test void stoppedWorkspaceAndSnapshotReplacementCannotChangeTheBoundEvidence() throws Exception {
        var plan = source("source"); var binding = FormalInjectionBinding.capture(plan); var dirs = dirs("episode", plan);
        var session = binding.newSession(); nativeTerminal(session, dirs, plan.prompt());
        var snapshot = session.snapshot(directory(dirs.episode().resolve("verifier-workspace")));
        String original = Files.readString(dirs.workspace().resolve("README.md"));
        Files.writeString(dirs.workspace().resolve("README.md"), "changed after snapshot");
        assertThrows(IOException.class, () -> session.evidence(snapshot));
        Files.writeString(dirs.workspace().resolve("README.md"), original);
        session.evidence(snapshot);
        // macOS requires owner write permission to rename this test-owned directory.
        // Restore the archived original, then test a same-byte replacement at the old path.
        chmod(snapshot.directory(), "rwx------");
        Path preserved = Files.move(snapshot.directory(), dirs.episode().resolve("preserved-original-snapshot"));
        chmod(preserved, "r-x------");
        Path replacement = directory(snapshot.directory());
        BenchmarkVerifierWorkspaceSnapshot.create(dirs.workspace(), replacement);
        assertThrows(IOException.class, () -> session.evidence(snapshot), "same-byte replacement must not replace the bound snapshot directory");
    }

    @Test void privateExecutionSourceDriftStillFailsAfterWorkerReturn() throws Exception {
        var plan = source("source"); var binding = FormalInjectionBinding.capture(plan); var dirs = dirs("episode", plan);
        var session = binding.newSession(); var result = nativeTerminal(session, dirs, plan.prompt());
        Path privateSource = dirs.episode().resolve("f3-source.json"); chmod(privateSource, "rw-------");
        Files.writeString(privateSource, "{}"); chmod(privateSource, "r--------");
        assertThrows(IOException.class, () -> session.requireReturned(result));
    }

    private FormalExecutionPlan.CasePlan source(String name) throws Exception { return F3BindingTestSource.generate(temp.resolve(name)); }
    private Dirs dirs(String name, FormalExecutionPlan.CasePlan plan) throws Exception {
        Path episode = directory(temp.resolve(name)), workspace = directory(episode.resolve("workspace")), home = directory(episode.resolve("home"));
        // Match production's private writable materialization, not the legacy dev copy's 0400/0755 attributes.
        com.paicli.eval.benchmark.formal.FormalFixtureMaterializer.materialize(plan.fixture(), workspace).verifyReady();
        return new Dirs(episode, workspace, home);
    }
    private static Path directory(Path path) throws IOException {
        return Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }
    private static void chmod(Path path, String mode) throws IOException { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode)); }
    private static BenchmarkProtocol.WorkerRequest request(Dirs dirs, String prompt, BenchmarkToolProfile profile, int tokens) {
        return new BenchmarkProtocol.WorkerRequest(BenchmarkProtocol.VERSION, "deepseek", "deepseek-v4-flash", null, "synthetic-f3-binding-key",
                "REACT", profile, new BenchmarkProtocol.AgentLimits(tokens, 32, 8, 1_000_000, 16_384), "2026-09-05", prompt,
                dirs.workspace().toString(), dirs.home().toString(), dirs.episode().toString());
    }
    private static BenchmarkCoordinatorMain.WorkerExecution nativeTerminal(FormalInjectionBinding.Session session, Dirs dirs, String prompt) throws Exception {
        var request = request(dirs, prompt, BenchmarkToolProfile.MOCK_MCP_FILE_ONLY, 100_000); session.begin(request, dirs.workspace(), dirs.home());
        LlmClient provider = new LlmClient() {
            public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
                assertEquals(7, tools.size()); return new ChatResponse("assistant", "done", "", List.of(), 100, 10, 0, getModelName(), true);
            }
            public ChatResponse chat(List<Message> messages, List<Tool> tools) { return chat(messages, tools, StreamListener.NO_OP); }
            public String getModelName() { return "deepseek-v4-flash"; }
            public String getProviderName() { return "deepseek"; }
            public int maxContextWindow() { return 1_000_000; }
        };
        var start = new BenchmarkRelayProtocol.SessionStart(new BenchmarkRelayProtocol.Header(BenchmarkRelayProtocol.Direction.COORDINATOR_TO_WORKER,
                BenchmarkRelayProtocol.FrameType.SESSION_START, "", 0), "f3-bound-native", provider.getProviderName(), provider.getModelName(),
                BenchmarkRelayProtocol.AgentMode.REACT, BenchmarkRelayProtocol.ToolProfile.MOCK_MCP_FILE_ONLY, prompt, "2026-09-05", "UTC",
                System.currentTimeMillis() + 60_000, new BenchmarkRelayProtocol.AgentLimits(100_000, 32, 8, 1_000_000, 16_384),
                BenchmarkProviderRelay.capabilitiesOf(provider), new BenchmarkRelayProtocol.Limits(BenchmarkFramedChannel.MAX_FRAME_BYTES,
                BenchmarkFramedChannel.MAX_SESSION_BYTES, 512, 128), List.of("support"), BenchmarkRelayProtocol.InteractionMode.SINGLE_TURN);
        var pool = Executors.newSingleThreadExecutor();
        try (var workerIn = new PipedInputStream(1 << 20); var hostIn = new PipedInputStream(1 << 20);
             var hostOut = new PipedOutputStream(workerIn); var workerOut = new PipedOutputStream(hostIn)) {
            var future = pool.submit(() -> { BenchmarkRelayWorkerMain.run(workerIn, workerOut, dirs.workspace()); return null; });
            var relay = BenchmarkProviderRelay.connect(new BenchmarkFramedChannel(hostIn, hostOut), start, provider, session.mock(), null, null, null, session.audit());
            BenchmarkProviderRelay.ServeResult state;
            do { state = relay.serveNext(); } while (state != BenchmarkProviderRelay.ServeResult.WORKER_COMPLETE && state != BenchmarkProviderRelay.ServeResult.WORKER_FAILURE);
            future.get(10, TimeUnit.SECONDS); assertEquals(BenchmarkProviderRelay.ServeResult.WORKER_COMPLETE, state);
            var terminal = assertInstanceOf(BenchmarkRelayProtocol.WorkerComplete.class, relay.terminalFrame());
            var result = BenchmarkCoordinatorMain.WorkerExecution.completed(BenchmarkProtocol.WorkerResponse.success(terminal.answer(), null), 0, 1, "scripted binding-only control");
            session.finish(result); return result;
        } finally { pool.shutdownNow(); }
    }
    private record Dirs(Path episode, Path workspace, Path home) { }
}
